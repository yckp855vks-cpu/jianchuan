#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""MediaShare - 局域网影音共享服务（纯标准库，零依赖）

用法:
    python server.py                          # 读 config.json
    python server.py "D:\\电影"               # 临时指定根目录
    python server.py "D:\\电影" 8848          # 临时指定根目录和端口
"""

import hashlib
import json
import mimetypes
import os
import posixpath
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# frozen 时 config.json 在 EXE 目录，ui.html 从打包资源目录读
if getattr(sys, "frozen", False):
    HERE = os.environ.get("MEDIASHARE_EXE_DIR", os.path.dirname(os.path.abspath(sys.executable)))
    _BUNDLE = os.environ.get("MEDIASHARE_BUNDLE_DIR", sys._MEIPASS)
else:
    HERE = os.path.dirname(os.path.abspath(__file__))
    _BUNDLE = HERE
CONFIG_PATH = os.path.join(HERE, "config.json")
UI_PATH = os.path.join(_BUNDLE, "ui.html")

VIDEO_EXT = {".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".ts", ".m2ts",
             ".webm", ".rmvb", ".rm", ".mpg", ".mpeg", ".m4v", ".3gp", ".vob", ".iso"}
AUDIO_EXT = {".mp3", ".flac", ".wav", ".m4a", ".aac", ".ogg", ".ape", ".wma", ".opus"}
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic", ".avif"}
DOC_EXT = {".pdf", ".txt", ".epub", ".mobi", ".doc", ".docx", ".xls", ".xlsx",
           ".ppt", ".pptx", ".zip", ".rar", ".7z", ".md", ".csv"}

MIME_OVERRIDE = {
    ".mkv": "video/x-matroska",
    ".m2ts": "video/mp2t",
    ".ts": "video/mp2t",
    ".rmvb": "application/vnd.rn-realmedia-vbr",
    ".flv": "video/x-flv",
    ".mov": "video/quicktime",
    ".m4v": "video/x-m4v",
    ".avi": "video/x-msvideo",
    ".srt": "text/plain; charset=utf-8",
    ".ass": "text/plain; charset=utf-8",
    ".vtt": "text/vtt; charset=utf-8",
    ".m3u8": "application/vnd.apple.mpegurl",
}

CHUNK = 512 * 1024
UPLOAD_DIR_NAME = "手机上传"  # 上传文件默认存放的共享目录名


def load_config():
    cfg = {"root": "", "port": 8848, "host": "0.0.0.0", "title": "MediaShare"}
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                cfg.update(json.load(f))
        except Exception as exc:  # 配置坏了就用默认值，别让服务起不来
            print(f"[warn] config.json 读取失败: {exc}")
    return cfg


def kind_of(name):
    ext = os.path.splitext(name)[1].lower()
    if ext in VIDEO_EXT:
        return "video"
    if ext in AUDIO_EXT:
        return "audio"
    if ext in IMAGE_EXT:
        return "image"
    if ext in DOC_EXT:
        return "doc"
    return "other"


def guess_type(path):
    ext = os.path.splitext(path)[1].lower()
    if ext in MIME_OVERRIDE:
        return MIME_OVERRIDE[ext]
    ctype, _ = mimetypes.guess_type(path)
    return ctype or "application/octet-stream"


def human_size(n):
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024 or unit == "TB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024.0


class Share:
    """把一个或多个根目录包装起来，所有路径访问都先过这一层做越权校验。

    只有一个根时路径就是普通的相对路径；有多个根时最外层是虚拟目录，
    路径第一段是根名。这样投影仪上看到的是「电影 / 剧集 / 录像」，
    而不是一整个装满杂物和程序目录的磁盘根。
    """

    def __init__(self, roots):
        # roots: [(显示名, 绝对路径), ...]
        self.roots = []
        for name, path in roots:
            name = (name or "").strip().strip("/\\")
            if not name or "/" in name or "\\" in name:
                raise ValueError(f"共享目录名称不合法: {name!r}")
            real = os.path.realpath(path)
            if not os.path.isdir(real):
                # 目录可能稍后才出现（比如转码输出目录），所以先记着，不直接丢掉
                pass
            self.roots.append((name, real))
        if not self.roots:
            raise FileNotFoundError("没有可用的共享目录")
        self.multi = len(self.roots) > 1
        self.root = self.roots[0][1]  # 单根场景下沿用旧名字，方便外部打印

    def _inside(self, base, real):
        b, r = os.path.normcase(base), os.path.normcase(real)
        return r == b or r.startswith(b + os.sep)

    def resolve(self, rel):
        rel = (rel or "").replace("\\", "/").lstrip("/")
        # 逐段过滤，挡住 ../ 与空字节
        parts = [p for p in posixpath.normpath("/" + rel).split("/") if p not in ("", ".", "..")]
        base = None
        if self.multi:
            if not parts:
                raise PermissionError("这里是共享目录列表")
            for name, path in self.roots:
                if name == parts[0]:
                    base, parts = path, parts[1:]
                    break
            if base is None:
                raise PermissionError("未知的共享目录")
        else:
            base = self.roots[0][1]
        target = os.path.join(base, *parts) if parts else base
        real = os.path.realpath(target)
        if not self._inside(base, real):
            raise PermissionError("路径越界")
        return real

    def rel_of(self, real):
        real = os.path.realpath(real)
        best = None
        for name, base in self.roots:
            if self._inside(base, real):
                if best is None or len(base) > len(best[1]):
                    rel = os.path.relpath(real, base)
                    best = (name, base, "" if rel == "." else rel.replace(os.sep, "/"))
        if best is None:
            return ""
        name, _, rel = best
        if not self.multi:
            return rel
        return name if not rel else name + "/" + rel

    def listing(self, rel):
        # 多根时最外层是虚拟目录，不落盘
        if self.multi and not (rel or "").strip("/\\"):
            entries = []
            for name, base in self.roots:
                if not os.path.isdir(base):
                    continue          # 目录还没出现（比如转码还没产出），先不显示
                try:
                    mtime = int(os.stat(base).st_mtime)
                except OSError:
                    mtime = 0
                entries.append({
                    "name": name, "path": name, "dir": True, "kind": "folder",
                    "size": 0, "size_text": "", "mtime": mtime, "ext": "",
                    "root": True,
                })
            return {"path": "", "entries": entries}

        real = self.resolve(rel)
        if not os.path.isdir(real):
            raise FileNotFoundError(rel)
        entries = []
        with os.scandir(real) as it:
            for de in it:
                name = de.name
                if name.startswith(".") or name.lower() in ("thumbs.db", "desktop.ini"):
                    continue
                try:
                    st = de.stat()
                except OSError:
                    continue
                is_dir = de.is_dir()
                entries.append({
                    "name": name,
                    "path": (self.rel_of(real) + "/" + name).lstrip("/"),
                    "dir": is_dir,
                    "kind": "folder" if is_dir else kind_of(name),
                    "size": 0 if is_dir else st.st_size,
                    "size_text": "" if is_dir else human_size(st.st_size),
                    "mtime": int(st.st_mtime),
                    "ext": "" if is_dir else os.path.splitext(name)[1].lstrip(".").lower(),
                })
        entries.sort(key=lambda e: (not e["dir"], e["name"].lower()))
        return {"path": self.rel_of(real), "entries": entries}


# ===================== 播放进度（跨设备续播） =====================
# 手机看到一半，投影仪打开接着看 —— 所以进度存在服务器这边，不存浏览器。

PROGRESS_PATH = os.path.join(HERE, "progress.json")
_PROGRESS = None
_PROGRESS_LOCK = threading.Lock()
_PROGRESS_DIRTY = {"v": False, "last": 0.0}


def _load_progress():
    global _PROGRESS
    if _PROGRESS is not None:
        return _PROGRESS
    data = {}
    try:
        if os.path.isfile(PROGRESS_PATH):
            with open(PROGRESS_PATH, "r", encoding="utf-8") as f:
                raw = json.load(f)
            if isinstance(raw, dict):
                data = raw
    except Exception:
        data = {}
    _PROGRESS = data
    return _PROGRESS


def progress_get(rel):
    item = _load_progress().get((rel or "").strip("/"))
    if not item:
        return None
    pos = float(item.get("pos") or 0)
    dur = float(item.get("dur") or 0)
    if pos < 5:
        return None
    return {"pos": round(pos, 1), "dur": round(dur, 1), "ts": item.get("ts") or 0}


def progress_set(rel, pos, dur):
    rel = (rel or "").strip("/")
    if not rel:
        return
    pos = max(0.0, float(pos or 0))
    dur = max(0.0, float(dur or 0))
    with _PROGRESS_LOCK:
        data = _load_progress()
        # 看到 97% 以上就算看完了，别让它下次又跳回片尾
        if dur > 0 and pos / dur >= 0.97:
            data.pop(rel, None)
        elif pos < 5:
            data.pop(rel, None)
        else:
            data[rel] = {"pos": round(pos, 1), "dur": round(dur, 1), "ts": int(time.time())}
        _PROGRESS_DIRTY["v"] = True
        if time.time() - _PROGRESS_DIRTY["last"] > 5:
            _save_progress_locked()


def _save_progress_locked():
    if not _PROGRESS_DIRTY["v"]:
        return
    tmp = PROGRESS_PATH + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(_PROGRESS, f, ensure_ascii=False, indent=0)
        os.replace(tmp, PROGRESS_PATH)
        _PROGRESS_DIRTY["v"] = False
        _PROGRESS_DIRTY["last"] = time.time()
    except Exception:
        pass


def progress_save_now():
    with _PROGRESS_LOCK:
        _save_progress_locked()


def progress_map_for(entries):
    """给目录列表的每个视频挂上进度百分比，前端画小进度条用。"""
    data = _load_progress()
    if not data:
        return
    for e in entries:
        if e.get("dir"):
            continue
        item = data.get(e.get("path"))
        if not item:
            continue
        dur = float(item.get("dur") or 0)
        pos = float(item.get("pos") or 0)
        if dur <= 0:
            continue
        pct = max(0.0, min(1.0, pos / dur))
        e["progress"] = {"pos": round(pos), "dur": round(dur), "pct": round(pct, 3)}


def local_ips():
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(0.4)
        s.connect(("223.5.5.5", 80))
        ips.append(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips:
                ips.append(ip)
    except OSError:
        pass
    return [ip for ip in ips if not ip.startswith("127.") and not ip.startswith("169.254.")]


class Handler(BaseHTTPRequestHandler):
    server_version = "MediaShare/1.0"
    protocol_version = "HTTP/1.1"

    # ---------- 基础输出 ----------

    def log_message(self, fmt, *args):
        if self.server.quiet:
            return
        sys.stdout.write("[%s] %s %s\n" % (time.strftime("%H:%M:%S"), self.client_address[0], fmt % args))

    def send_json(self, obj, status=HTTPStatus.OK):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def send_html(self, text, status=HTTPStatus.OK):
        body = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def fail(self, status, message):
        self.send_json({"error": message}, status)

    # ---------- 路由 ----------

    def do_GET(self):
        try:
            self.route(send_body=True)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_HEAD(self):
        try:
            self.route(send_body=False)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_POST(self):
        try:
            self.route_post()
        except (BrokenPipeError, ConnectionResetError):
            pass

    def route(self, send_body=True):
        parsed = urllib.parse.urlsplit(self.path)
        path = urllib.parse.unquote(parsed.path)
        query = urllib.parse.parse_qs(parsed.query)
        share = self.server.share

        if path in ("/", "/index.html"):
            with open(UI_PATH, "r", encoding="utf-8") as f:
                self.send_html(f.read())
            return

        if path == "/api/list":
            rel = query.get("path", [""])[0]
            try:
                payload = share.listing(rel)
            except (FileNotFoundError, NotADirectoryError):
                self.fail(HTTPStatus.NOT_FOUND, "目录不存在")
                return
            except PermissionError:
                self.fail(HTTPStatus.FORBIDDEN, "拒绝访问")
                return
            payload["crumbs"] = crumbs_of(payload["path"])
            progress_map_for(payload["entries"])
            # 给视频条目附加海报 URL（同目录海报文件 O(1) 查找；抽帧交给前端懒加载）
            for e in payload["entries"]:
                if e.get("dir") or e.get("kind") != "video":
                    continue
                e["poster"] = poster_url_of_resolved(share, e["path"])
            self.send_json(payload)
            return

        if path == "/api/videos":
            # 返回所有共享目录下的视频，按系列分组（给「视频」Tab 用）
            all_videos = []
            for rname, rpath in share.roots:
                if not os.path.isdir(rpath):
                    continue
                for dirpath, dirnames, filenames in os.walk(rpath):
                    dirnames[:] = [d for d in dirnames if not d.startswith(".")]
                    for fn in filenames:
                        if os.path.splitext(fn)[1].lower() not in VIDEO_EXT:
                            continue
                        full = os.path.join(dirpath, fn)
                        try:
                            st = os.stat(full)
                        except OSError:
                            continue
                        rel_path = share.rel_of(os.path.realpath(full))
                        all_videos.append({
                            "name": fn,
                            "path": rel_path,
                            "kind": "video",
                            "size": st.st_size,
                            "size_text": human_size(st.st_size),
                            "mtime": int(st.st_mtime),
                            "ext": os.path.splitext(fn)[1].lstrip(".").lower(),
                            "root": rname,
                            "series": _series_name(fn),
                        })
            # 按系列分组
            series_map = {}
            for v in all_videos:
                key = v["series"] or "其他"
                series_map.setdefault(key, []).append(v)
            groups = []
            for sname in sorted(series_map.keys()):
                items = sorted(series_map[sname], key=lambda x: x["name"])
                groups.append({"series": sname, "count": len(items), "items": items})
            self.send_json({"groups": groups, "total": len(all_videos)})
            return

        if path == "/api/search":
            rel = query.get("path", [""])[0]
            q = query.get("q", [""])[0].strip().lower()
            if not q:
                self.send_json({"entries": [], "path": rel})
                return
            try:
                base = share.resolve(rel)
            except PermissionError:
                self.fail(HTTPStatus.FORBIDDEN, "拒绝访问")
                return
            hits = []
            deadline = time.time() + 8.0
            for dirpath, dirnames, filenames in os.walk(base):
                if time.time() > deadline or len(hits) >= 400:
                    break
                dirnames[:] = [d for d in dirnames if not d.startswith(".")]
                for name in filenames + dirnames:
                    if q in name.lower():
                        full = os.path.join(dirpath, name)
                        is_dir = os.path.isdir(full)
                        try:
                            st = os.stat(full)
                        except OSError:
                            continue
                        hits.append({
                            "name": name,
                            "path": share.rel_of(os.path.realpath(full)),
                            "dir": is_dir,
                            "kind": "folder" if is_dir else kind_of(name),
                            "size": 0 if is_dir else st.st_size,
                            "size_text": "" if is_dir else human_size(st.st_size),
                            "mtime": int(st.st_mtime),
                            "ext": "" if is_dir else os.path.splitext(name)[1].lstrip(".").lower(),
                        })
            self.send_json({"entries": hits, "path": rel, "truncated": len(hits) >= 400})
            return

        if path == "/api/progress":
            rel = query.get("path", [""])[0]
            self.send_json(progress_get(rel) or {})
            return

        if path == "/api/history":
            # 播放历史：来自 progress.json，按最近观看时间倒序。
            # 只保留还在磁盘上的视频，附上清洗后的标题和海报。
            data = _load_progress()
            items = []
            for rel, item in data.items():
                if not rel:
                    continue
                try:
                    real = share.resolve(rel)
                except (PermissionError, FileNotFoundError):
                    continue
                if not os.path.isfile(real):
                    continue
                if os.path.splitext(real)[1].lower() not in VIDEO_EXT:
                    continue
                pos = float(item.get("pos") or 0)
                dur = float(item.get("dur") or 0)
                if pos < 5 or (dur > 0 and pos / dur >= 0.97):
                    continue
                items.append({
                    "path": rel,
                    "name": os.path.basename(real),
                    "title": pretty_title(os.path.basename(real)),
                    "pos": round(pos, 1),
                    "dur": round(dur, 1),
                    "pct": round(pos / dur, 3) if dur > 0 else 0,
                    "ts": item.get("ts") or 0,
                    "poster": poster_url_of_resolved(share, rel),
                })
            items.sort(key=lambda x: x["ts"], reverse=True)
            self.send_json({"items": items, "count": len(items)})
            return

        if path == "/api/episodes":
            # 返回同目录下的所有视频（给播放页的集数列表用）
            rel = query.get("path", [""])[0]
            try:
                real = share.resolve(rel)
            except PermissionError:
                self.fail(HTTPStatus.FORBIDDEN, "拒绝访问")
                return
            if not os.path.isfile(real):
                self.fail(HTTPStatus.NOT_FOUND, "文件不存在")
                return
            parent = os.path.dirname(real)
            eps = []
            try:
                for fn in sorted(os.listdir(parent)):
                    if os.path.splitext(fn)[1].lower() not in VIDEO_EXT:
                        continue
                    full = os.path.join(parent, fn)
                    if not os.path.isfile(full):
                        continue
                    try:
                        st = os.stat(full)
                    except OSError:
                        continue
                    eps.append({
                        "name": fn,
                        "path": share.rel_of(os.path.realpath(full)),
                        "title": pretty_title(fn),
                        "size": st.st_size,
                        "size_text": human_size(st.st_size),
                        "current": fn == os.path.basename(real),
                    })
            except OSError:
                pass
            self.send_json({"episodes": eps, "dir": share.rel_of(parent)})
            return

        if path == "/play":
            rel = query.get("path", [""])[0]
            try:
                real = share.resolve(rel)
            except PermissionError:
                self.fail(HTTPStatus.FORBIDDEN, "拒绝访问")
                return
            if not os.path.isfile(real):
                self.fail(HTTPStatus.NOT_FOUND, "文件不存在")
                return
            self.send_html(play_page(rel, os.path.basename(real), self.server.public_base,
                                    real, query.get("title", [""])[0]))
            return

        if path == "/detail":
            rel = query.get("path", [""])[0]
            try:
                real = share.resolve(rel)
            except PermissionError:
                self.fail(HTTPStatus.FORBIDDEN, "拒绝访问")
                return
            if not os.path.isfile(real):
                self.fail(HTTPStatus.NOT_FOUND, "文件不存在")
                return
            parent = os.path.dirname(real)
            cur_name = os.path.basename(real)
            eps = []
            try:
                for fn in sorted(os.listdir(parent)):
                    if os.path.splitext(fn)[1].lower() not in VIDEO_EXT:
                        continue
                    full = os.path.join(parent, fn)
                    if not os.path.isfile(full):
                        continue
                    try:
                        st = os.stat(full)
                    except OSError:
                        continue
                    eps.append({
                        "name": fn,
                        "path": share.rel_of(os.path.realpath(full)),
                        "title": pretty_title(fn),
                        "size_text": human_size(st.st_size),
                        "current": fn == cur_name,
                    })
            except OSError:
                pass
            title = query.get("title", [""])[0] or pretty_title(cur_name)
            self.send_html(detail_page(rel, title, eps))
            return

        if path == "/api/audiotracks":
            rel = query.get("path", [""])[0]
            try:
                real = share.resolve(rel)
            except PermissionError:
                self.fail(HTTPStatus.FORBIDDEN, "拒绝访问")
                return
            self.send_json({"tracks": probe_audio_tracks(real)})
            return

        if path == "/api/poster":
            rel = query.get("path", [""])[0]
            try:
                real = share.resolve(rel)
            except PermissionError:
                self.fail(HTTPStatus.FORBIDDEN, "拒绝访问")
                return
            if not os.path.isfile(real):
                self.fail(HTTPStatus.NOT_FOUND, "文件不存在")
                return
            thumb = extract_thumb(real)
            if not thumb or not os.path.isfile(thumb):
                self.fail(HTTPStatus.NOT_FOUND, "无法生成海报")
                return
            try:
                size = os.path.getsize(thumb)
                with open(thumb, "rb") as f:
                    body = f.read()
            except OSError:
                self.fail(HTTPStatus.NOT_FOUND, "无法生成海报")
                return
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(size))
            self.send_header("Cache-Control", "max-age=86400")
            self.end_headers()
            if send_body:
                self.wfile.write(body)
            return

        if path == "/api/subtitle":
            rel = query.get("path", [""])[0]
            idx = query.get("i", [""])[0].strip()   # 空/-1 = 外挂字幕；>=0 = 内嵌轨号
            try:
                real = share.resolve(rel)
            except PermissionError:
                self.fail(HTTPStatus.FORBIDDEN, "拒绝访问")
                return
            body = None
            if idx not in ("", "-1"):
                try:
                    text = extract_embedded_subtitle(real, int(idx))
                except ValueError:
                    text = None
                if text:
                    body = text.encode("utf-8")
            if body is None:
                sub = find_subtitle(real)
                if not sub:
                    self.fail(HTTPStatus.NOT_FOUND, "无字幕")
                    return
                body = subtitle_to_vtt(sub).encode("utf-8")
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "text/vtt; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "max-age=600")
            self.end_headers()
            if send_body:
                self.wfile.write(body)
            return

        if path.startswith("/media/"):
            self.serve_file(share, path[len("/media/"):], send_body)
            return

        if path.startswith("/download/"):
            self.serve_file(share, path[len("/download/"):], send_body, download=True)
            return

        self.fail(HTTPStatus.NOT_FOUND, "未知路径")

    # ---------- POST：上传 / 智能归类 ----------

    def route_post(self):
        parsed = urllib.parse.urlsplit(self.path)
        path = urllib.parse.unquote(parsed.path)
        query = urllib.parse.parse_qs(parsed.query)

        if path == "/api/upload":
            self.handle_upload(query)
        elif path == "/api/organize":
            self.handle_organize(query)
        elif path == "/api/progress":
            self.handle_progress()
        else:
            self.fail(HTTPStatus.NOT_FOUND, "未知路径")

    def handle_progress(self):
        """记录播放位置。body: {"path": "...", "pos": 123.4, "dur": 1430.8}"""
        try:
            n = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(n) if n else b"{}"
            data = json.loads(raw.decode("utf-8", "replace") or "{}")
        except Exception:
            self.fail(HTTPStatus.BAD_REQUEST, "参数格式不对")
            return
        rel = str(data.get("path") or "").strip()
        if not rel:
            self.fail(HTTPStatus.BAD_REQUEST, "缺少 path")
            return
        progress_set(rel, data.get("pos") or 0, data.get("dur") or 0)
        self.send_json({"ok": True})

    def handle_upload(self, query):
        """接收手机上传的视频文件。支持分块续传。

        参数（query string）:
            name  - 文件名（不允许带路径分隔符）
            path  - 目标子目录（相对于共享根，可为空）
            uid   - 上传会话 ID（用于分块续传，可为空 = 单次上传）
            chunk - 当前块序号（0-based，可为空 = 单次）
            total - 总块数（可为空 = 单次）
        """
        share = self.server.share
        name = query.get("name", [""])[0].strip()
        sub_path = query.get("path", [""])[0].strip().replace("\\", "/").strip("/")
        uid = query.get("uid", [""])[0].strip()
        chunk_idx = int(query.get("chunk", ["-1"])[0])
        total_chunks = int(query.get("total", ["1"])[0])
        content_length = int(self.headers.get("Content-Length", "0"))

        # 文件名安全校验
        safe_name = os.path.basename(name.replace("\\", "/"))
        if not safe_name or safe_name.startswith(".") or safe_name in ("thumbs.db", "desktop.ini"):
            self.fail(HTTPStatus.BAD_REQUEST, "文件名不合法")
            return
        ext = os.path.splitext(safe_name)[1].lower()
        if ext not in VIDEO_EXT and ext not in AUDIO_EXT and ext not in IMAGE_EXT and ext not in DOC_EXT:
            self.fail(HTTPStatus.BAD_REQUEST, f"不支持的文件类型: {ext}")
            return

        # 确定上传目录：优先用「手机上传」共享根
        upload_root = None
        for rname, rpath in share.roots:
            if rname == UPLOAD_DIR_NAME:
                upload_root = rpath
                break
        if upload_root is None:
            # 没有专门的上传根就用第一个根 + /手机上传
            upload_root = os.path.join(share.roots[0][1], UPLOAD_DIR_NAME)

        target_dir = os.path.join(upload_root, *sub_path.split("/")) if sub_path else upload_root
        try:
            os.makedirs(target_dir, exist_ok=True)
        except OSError as exc:
            self.fail(HTTPStatus.INTERNAL_SERVER_ERROR, f"创建目录失败: {exc}")
            return

        final_path = os.path.join(target_dir, safe_name)

        # ---- 单次上传（chunk < 0 或 total == 1） ----
        if chunk_idx < 0 or total_chunks <= 1:
            try:
                received = 0
                with open(final_path, "wb") as f:
                    while received < content_length:
                        data = self.rfile.read(min(CHUNK, content_length - received))
                        if not data:
                            break
                        f.write(data)
                        received += len(data)
            except OSError as exc:
                self.fail(HTTPStatus.INTERNAL_SERVER_ERROR, f"写入失败: {exc}")
                return
            self.send_json({
                "ok": True, "name": safe_name, "size": received,
                "path": share.rel_of(os.path.realpath(final_path)),
            })
            return

        # ---- 分块上传 ----
        if not uid or not re.fullmatch(r"[\w\-]{4,64}", uid):
            self.fail(HTTPStatus.BAD_REQUEST, "分块上传需要合法的 uid")
            return
        tmp_dir = os.path.join(upload_root, ".upload_tmp")
        os.makedirs(tmp_dir, exist_ok=True)
        chunk_file = os.path.join(tmp_dir, f"{uid}_{chunk_idx:05d}.part")

        try:
            received = 0
            with open(chunk_file, "wb") as f:
                while received < content_length:
                    data = self.rfile.read(min(CHUNK, content_length - received))
                    if not data:
                        break
                    f.write(data)
                    received += len(data)
        except OSError as exc:
            self.fail(HTTPStatus.INTERNAL_SERVER_ERROR, f"写入块失败: {exc}")
            return

        # 检查是否所有块都齐了
        if chunk_idx == total_chunks - 1:
            try:
                with open(final_path, "wb") as out:
                    for i in range(total_chunks):
                        cf = os.path.join(tmp_dir, f"{uid}_{i:05d}.part")
                        if not os.path.isfile(cf):
                            self.fail(HTTPStatus.BAD_REQUEST, f"缺少第 {i} 块，请重新上传")
                            return
                        with open(cf, "rb") as inp:
                            shutil.copyfileobj(inp, out, CHUNK)
                # 清理临时块
                for i in range(total_chunks):
                    cf = os.path.join(tmp_dir, f"{uid}_{i:05d}.part")
                    try:
                        os.remove(cf)
                    except OSError:
                        pass
            except OSError as exc:
                self.fail(HTTPStatus.INTERNAL_SERVER_ERROR, f"合并失败: {exc}")
                return
            self.send_json({
                "ok": True, "name": safe_name,
                "size": os.path.getsize(final_path),
                "path": share.rel_of(os.path.realpath(final_path)),
            })
        else:
            self.send_json({"ok": True, "chunk": chunk_idx, "received": received})

    def handle_organize(self, query):
        """扫描共享目录，把同系列的视频文件自动归入子文件夹。

        参数: path - 要整理的目录（相对路径，空 = 整理所有根）
              dry  - "1" 只预览不移动
        """
        share = self.server.share
        rel = query.get("path", [""])[0].strip()
        dry_run = query.get("dry", ["0"])[0] == "1"

        try:
            base = share.resolve(rel) if rel else None
        except PermissionError:
            self.fail(HTTPStatus.FORBIDDEN, "拒绝访问")
            return

        # 确定要扫描的目录列表
        scan_dirs = []
        if base:
            scan_dirs.append(base)
        else:
            for _, rpath in share.roots:
                if os.path.isdir(rpath):
                    scan_dirs.append(rpath)

        actions = []  # [(文件名, 系列名, 原路径, 新路径)]
        for scan_base in scan_dirs:
            try:
                entries = os.listdir(scan_base)
            except OSError:
                continue
            videos = [e for e in entries
                      if os.path.splitext(e)[1].lower() in VIDEO_EXT
                      and os.path.isfile(os.path.join(scan_base, e))]
            # 按系列名分组
            groups = {}
            for vname in videos:
                series = _series_name(vname)
                if series:
                    groups.setdefault(series, []).append(vname)

            for series, files in groups.items():
                if len(files) < 2:
                    continue  # 只有一个文件不算系列，不归类
                target_dir = os.path.join(scan_base, series)
                for vname in sorted(files):
                    src = os.path.join(scan_base, vname)
                    dst = os.path.join(target_dir, vname)
                    if os.path.exists(dst):
                        continue
                    actions.append({
                        "file": vname, "series": series,
                        "from": share.rel_of(os.path.realpath(src)),
                        "to": share.rel_of(os.path.realpath(dst)),
                    })
                    if not dry_run:
                        try:
                            os.makedirs(target_dir, exist_ok=True)
                            shutil.move(src, dst)
                        except OSError:
                            pass

        self.send_json({"ok": True, "dry_run": dry_run, "moves": actions, "count": len(actions)})

    # ---------- 文件流（支持 Range，进度条能拖） ----------

    def serve_file(self, share, rel, send_body=True, download=False):
        try:
            real = share.resolve(rel)
        except PermissionError:
            self.fail(HTTPStatus.FORBIDDEN, "拒绝访问")
            return
        if not os.path.isfile(real):
            self.fail(HTTPStatus.NOT_FOUND, "文件不存在")
            return

        size = os.path.getsize(real)
        ctype = guess_type(real)
        start, end = 0, size - 1
        partial = False

        rng = self.headers.get("Range")
        if rng:
            m = re.match(r"bytes=(\d*)-(\d*)", rng.strip())
            if m:
                if m.group(1):
                    start = int(m.group(1))
                    end = int(m.group(2)) if m.group(2) else size - 1
                elif m.group(2):
                    start = max(0, size - int(m.group(2)))
                    end = size - 1
                if start >= size:
                    self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                end = min(end, size - 1)
                if end < start:
                    end = size - 1
                partial = True

        length = end - start + 1
        self.send_response(HTTPStatus.PARTIAL_CONTENT if partial else HTTPStatus.OK)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "no-cache")
        if partial:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        if download:
            fn = urllib.parse.quote(os.path.basename(real))
            self.send_header("Content-Disposition", f'attachment; filename="{fn}"')
        self.end_headers()

        if not send_body:
            return

        try:
            with open(real, "rb") as f:
                f.seek(start)
                left = length
                while left > 0:
                    chunk = f.read(min(CHUNK, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass  # 用户拖进度条时正常断流


def crumbs_of(rel):
    out = [{"name": "主页", "path": ""}]
    acc = []
    for seg in [s for s in rel.split("/") if s]:
        acc.append(seg)
        out.append({"name": seg, "path": "/".join(acc)})
    return out


# ===================== 内嵌字幕（MKV/MP4 里的字幕轨） =====================
# 浏览器 <video> 读不了容器里的字幕轨，只能用 ffmpeg 抽出来转成 VTT。
# ffmpeg 不是必需品：找不到就退回外挂字幕（同名 .srt/.ass/.vtt）。

SUB_CACHE_DIR = os.path.join(HERE, ".subcache")
THUMB_CACHE_DIR = os.path.join(HERE, ".thumbs")
_FF_TOOLS = {}
_SUB_PROBE_CACHE = {}
_SUB_PROBE_LOCK = threading.Lock()
_SUB_EXTRACT_LOCK = threading.Lock()
# 抽帧有界并发：同时最多 3 个 ffmpeg 抽帧，既不串行拖慢（全局锁），也不挤爆 CPU/IO。
_THUMB_SEM = threading.Semaphore(3)

POSTER_NAMES = ("poster", "folder", "cover", "fanart", "backdrop", "thumb")
POSTER_EXTS = (".jpg", ".jpeg", ".png", ".webp")

FFMPEG_HINTS = [
    r"C:\ffmpeg\bin\ffmpeg.exe",
    r"C:\ffmpeg\ffmpeg.exe",
    r"C:\Program Files\ffmpeg\bin\ffmpeg.exe",
    r"C:\Program Files (x86)\ffmpeg\bin\ffmpeg.exe",
    os.path.join(HERE, "ffmpeg.exe"),
    os.path.join(HERE, "bin", "ffmpeg.exe"),
]


def _find_tool(name):
    """找 ffmpeg / ffprobe：先看 PATH，再试常见安装位置。"""
    if name in _FF_TOOLS:
        return _FF_TOOLS[name]
    found = shutil.which(name)
    if not found:
        for hint in FFMPEG_HINTS:
            cand = hint if hint.lower().endswith(name + ".exe") else \
                os.path.join(os.path.dirname(hint), name + ".exe")
            if os.path.isfile(cand):
                found = cand
                break
    _FF_TOOLS[name] = found
    return found


def _no_window_kwargs():
    """Windows 下别弹出黑框（EXE 是 --windowed 的，弹框会很突兀）。"""
    if os.name != "nt":
        return {}
    try:
        si = subprocess.STARTUPINFO()
        si.dwFlags |= subprocess.STARTF_USESHOWWINDOW
        si.wShowWindow = 0
        return {"startupinfo": si, "creationflags": 0x08000000}
    except Exception:
        return {}


# 语言标签 → 优先级（越小越优先）。简体内嵌轨排最前面。
_LANG_PRIORITY = (
    (("chi", "zho", "zh", "chs", "sc", "cmn"), 0),
    (("cht", "tc", "zh-tw", "zh-hk"), 1),
    (("eng", "en"), 5),
)


def _sub_score(track):
    """给字幕轨打分：中文优先，简体/简体中文标题更优先，强制字幕降一档。"""
    lang = (track.get("language") or "").lower()
    title = (track.get("title") or "")
    low = title.lower()
    score = 50
    for langs, pri in _LANG_PRIORITY:
        if lang in langs or any(x in low for x in langs):
            score = pri * 10
            break
    if any(k in low for k in ("简体", "simplified", "简中", "chs", "gb")):
        score -= 6
    if any(k in low for k in ("繁体", "traditional", "繁中", "cht", "big5")):
        score -= 2
    if track.get("forced"):
        score += 12
    if track.get("default"):
        score -= 1
    return score


def probe_subtitles(video_real):
    """返回文件内嵌字幕轨列表（按中文优先排序）。结果按 mtime+size 缓存。"""
    probe = _find_tool("ffprobe")
    if not probe:
        return []
    try:
        st = os.stat(video_real)
    except OSError:
        return []
    key = os.path.abspath(video_real)
    sig = (st.st_mtime, st.st_size)
    with _SUB_PROBE_LOCK:
        hit = _SUB_PROBE_CACHE.get(key)
        if hit and hit[0] == sig:
            return hit[1]
    tracks = []
    try:
        proc = subprocess.run(
            [probe, "-v", "error", "-select_streams", "s",
             "-show_entries", "stream=index,codec_name:stream_tags=language,title:stream_disposition=default,forced",
             "-of", "json", video_real],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=20,
            **_no_window_kwargs())
        if proc.returncode == 0:
            data = json.loads(proc.stdout.decode("utf-8", "replace") or "{}")
            for s in data.get("streams") or []:
                tags = s.get("tags") or {}
                disp = s.get("disposition") or {}
                tracks.append({
                    "index": int(s.get("index", 0)),
                    "codec": s.get("codec_name") or "",
                    "language": tags.get("language") or "",
                    "title": tags.get("title") or "",
                    "default": bool(disp.get("default")),
                    "forced": bool(disp.get("forced")),
                })
    except Exception:
        tracks = []
    tracks.sort(key=_sub_score)
    with _SUB_PROBE_LOCK:
        _SUB_PROBE_CACHE[key] = (sig, tracks)
    return tracks


_AUDIO_LANG = (
    (("chi", "zho", "zh", "chs", "sc", "cmn"), "国语"),
    (("yue", "can", "hk"), "粤语"),
    (("jpn", "ja", "jp"), "日语"),
    (("eng", "en"), "英语"),
    (("kor", "ko"), "韩语"),
)


def probe_audio_tracks(video_real):
    """返回音轨列表（按默认轨/中文优先排序）。结果按 mtime+size 缓存。"""
    probe = _find_tool("ffprobe")
    if not probe:
        return []
    try:
        st = os.stat(video_real)
    except OSError:
        return []
    key = os.path.abspath(video_real)
    sig = (st.st_mtime, st.st_size, "audio")
    with _SUB_PROBE_LOCK:
        hit = _SUB_PROBE_CACHE.get(key)
        if hit and hit[0] == sig:
            return hit[1]
    tracks = []
    try:
        proc = subprocess.run(
            [probe, "-v", "error", "-select_streams", "a",
             "-show_entries", "stream=index,codec_name,channels:stream_tags=language,title:stream_disposition=default",
             "-of", "json", video_real],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=20,
            **_no_window_kwargs())
        if proc.returncode == 0:
            data = json.loads(proc.stdout.decode("utf-8", "replace") or "{}")
            for s in data.get("streams") or []:
                tags = s.get("tags") or {}
                disp = s.get("disposition") or {}
                lang = (tags.get("language") or "").lower()
                title = tags.get("title") or ""
                label = ""
                if title:
                    label = title[:16]
                else:
                    for langs, cn in _AUDIO_LANG:
                        if lang in langs:
                            label = cn
                            break
                if not label and lang and lang not in ("und", "undefined", "unknown"):
                    label = lang.upper()
                ch = s.get("channels") or 0
                if ch:
                    label = (label + " ") if label else ""
                    label += f"{ch}声道"
                tracks.append({
                    "index": int(s.get("index", 0)),
                    "codec": s.get("codec_name") or "",
                    "language": lang,
                    "title": title,
                    "default": bool(disp.get("default")),
                    "label": label or ("音轨 %d" % (len(tracks) + 1)),
                })
    except Exception:
        tracks = []
    # 默认轨排前，其余保持 ffprobe 顺序
    tracks.sort(key=lambda t: (0 if t["default"] else 1, t["index"]))
    with _SUB_PROBE_LOCK:
        _SUB_PROBE_CACHE[key] = (sig, tracks)
    return tracks


def _track_label(track, i):
    lang = (track.get("language") or "").lower()
    title = (track.get("title") or "").strip()
    if title:
        # 厂商标题常常是 "Chinese (Simplified)" 之类，翻成中文更好认
        low = title.lower()
        if "simplified" in low:
            return "简体中文"
        if "traditional" in low:
            return "繁体中文"
        if "chinese" in low:
            return "中文"
        return title[:16]
    if lang in ("chi", "zho", "zh", "chs", "sc", "cmn"):
        return "中文"
    if lang in ("cht", "tc"):
        return "繁体中文"
    if lang in ("eng", "en"):
        return "English"
    if lang:
        return lang.upper()
    return "字幕 %d" % (i + 1)


def extract_embedded_subtitle(video_real, stream_index):
    """把内嵌字幕轨抽成 VTT 文本，成功返回 str，失败返回 None。

    抽一次要读整个文件（1GB 大概 1-3 秒），所以落盘缓存，路径按
    文件 mtime+size+轨号做 key，文件一换自动失效。
    """
    ffmpeg = _find_tool("ffmpeg")
    if not ffmpeg:
        return None
    try:
        st = os.stat(video_real)
    except OSError:
        return None
    raw = "%s|%s|%s|%s" % (os.path.abspath(video_real), st.st_mtime, st.st_size, stream_index)
    digest = hashlib.sha1(raw.encode("utf-8", "replace")).hexdigest()[:20]
    try:
        os.makedirs(SUB_CACHE_DIR, exist_ok=True)
    except OSError:
        pass
    cached = os.path.join(SUB_CACHE_DIR, digest + ".vtt")

    with _SUB_EXTRACT_LOCK:
        if os.path.isfile(cached) and os.path.getsize(cached) > 8:
            try:
                with open(cached, "r", encoding="utf-8", errors="replace") as f:
                    return f.read()
            except OSError:
                pass
        tmp = cached + ".part"
        for codec in ("webvtt", "srt"):
            cmd = [ffmpeg, "-v", "error", "-y", "-i", video_real,
                   "-map", "0:%d" % stream_index, "-c:s", codec,
                   "-f", codec, tmp]
            try:
                proc = subprocess.run(cmd, stdout=subprocess.DEVNULL,
                                      stderr=subprocess.DEVNULL, timeout=180,
                                      **_no_window_kwargs())
            except Exception:
                proc = None
            if proc is not None and proc.returncode == 0 and os.path.isfile(tmp) \
                    and os.path.getsize(tmp) > 8:
                try:
                    with open(tmp, "r", encoding="utf-8", errors="replace") as f:
                        text = f.read()
                except OSError:
                    text = ""
                if codec == "srt":
                    text = subtitle_to_vtt_from_text(text)
                if text.strip():
                    try:
                        with open(cached, "w", encoding="utf-8", newline="\n") as f:
                            f.write(text)
                        os.remove(tmp)
                    except OSError:
                        pass
                    return text
        try:
            if os.path.isfile(tmp):
                os.remove(tmp)
        except OSError:
            pass
    return None


def subtitle_to_vtt_from_text(text):
    body = text.replace("\r\n", "\n").replace("\r", "\n")
    body = re.sub(r"(\d{2}:\d{2}:\d{2}),(\d{3})", r"\1.\2", body)
    return "WEBVTT\n\n" + body


# ===================== 海报（刮削） =====================
# 视频卡片没有封面看着很空。优先级：
#   1. 同目录下的海报文件（poster.jpg / folder.jpg / cover.jpg / 同名 .jpg…）
#   2. ffmpeg 从视频抽一帧（约 10% 位置，避开纯黑片头），落盘缓存到 .thumbs/
# 海报只做展示，不落库、不联网刮元数据。

def find_poster_file(video_real):
    d, base = os.path.split(video_real)
    stem = os.path.splitext(base)[0]
    try:
        names = [n for n in os.listdir(d)]
    except OSError:
        return None
    # 1) 精确同名图
    for ext in POSTER_EXTS:
        cand = os.path.join(d, stem + ext)
        if os.path.isfile(cand):
            return cand
    # 2) 常见海报名（poster.jpg / folder.jpg / cover.jpg ...）
    lows = {n.lower(): n for n in names}
    for pname in POSTER_NAMES:
        for ext in POSTER_EXTS:
            cand = lows.get(pname + ext)
            if cand and os.path.isfile(os.path.join(d, cand)):
                return os.path.join(d, cand)
    return None


# 目录级海报缓存：同一目录的视频复用一次 listdir 结果，避免每个视频重复扫目录。
# 用目录 mtime 做失效判断，目录内容一变就重新扫。
_POSTER_DIR_CACHE = {}
_POSTER_DIR_CACHE_LOCK = threading.Lock()


def _dir_poster_names(d):
    """返回目录里所有海报文件名集合（小写），带 mtime 缓存。"""
    try:
        st = os.stat(d)
        sig = (int(st.st_mtime), int(st.st_size) if os.path.isdir(d) else 0)
    except OSError:
        return None
    with _POSTER_DIR_CACHE_LOCK:
        cached = _POSTER_DIR_CACHE.get(d)
        if cached and cached[0] == sig:
            return cached[1]
    try:
        names = [n for n in os.listdir(d)]
    except OSError:
        return None
    lows = {n.lower(): n for n in names}
    with _POSTER_DIR_CACHE_LOCK:
        _POSTER_DIR_CACHE[d] = (sig, lows)
        if len(_POSTER_DIR_CACHE) > 512:
            # 简单淘汰：清掉一半
            for k in list(_POSTER_DIR_CACHE.keys())[:256]:
                _POSTER_DIR_CACHE.pop(k, None)
    return lows


def find_poster_file_cached(video_real):
    """带目录缓存的 find_poster_file，供 /api/list 批量计算海报用。"""
    d, base = os.path.split(video_real)
    stem = os.path.splitext(base)[0]
    # 1) 精确同名图（不需要列目录）
    for ext in POSTER_EXTS:
        cand = os.path.join(d, stem + ext)
        if os.path.isfile(cand):
            return cand
    # 2) 常见海报名
    lows = _dir_poster_names(d)
    if not lows:
        return None
    for pname in POSTER_NAMES:
        for ext in POSTER_EXTS:
            cand = lows.get(pname + ext)
            if cand and os.path.isfile(os.path.join(d, cand)):
                return os.path.join(d, cand)
    return None


def thumb_path_for(video_real):
    try:
        st = os.stat(video_real)
    except OSError:
        return None
    raw = "%s|%s|%s" % (os.path.abspath(video_real), st.st_mtime, st.st_size)
    digest = hashlib.sha1(raw.encode("utf-8", "replace")).hexdigest()[:20]
    return os.path.join(THUMB_CACHE_DIR, digest + ".jpg")


def extract_thumb(video_real):
    """从视频抽一帧做缩略图，返回缓存 JPG 路径；失败返回 None。

    抽帧需要能 seek 的输入，网络 / 损坏文件会失败，直接返回 None 不阻塞。
    """
    ffmpeg = _find_tool("ffmpeg")
    if not ffmpeg:
        return None
    cached = thumb_path_for(video_real)
    if cached and os.path.isfile(cached) and os.path.getsize(cached) > 512:
        return cached
    try:
        os.makedirs(THUMB_CACHE_DIR, exist_ok=True)
    except OSError:
        return None
    tmp = cached + ".part"
    with _THUMB_SEM:
        if os.path.isfile(cached) and os.path.getsize(cached) > 512:
            return cached
        # 抽 10% 处的一帧；片头往往是黑场，10% 更可能抽到实际画面
        cmd = [ffmpeg, "-v", "error", "-ss", "10", "-i", video_real,
               "-frames:v", "1", "-vf", "scale=480:-2", "-q:v", "3",
               "-f", "image2", "-y", tmp]
        try:
            proc = subprocess.run(cmd, stdout=subprocess.DEVNULL,
                                  stderr=subprocess.DEVNULL, timeout=30,
                                  **_no_window_kwargs())
        except Exception:
            proc = None
        if proc is not None and proc.returncode == 0 and os.path.isfile(tmp) \
                and os.path.getsize(tmp) > 512:
            try:
                os.replace(tmp, cached)
                return cached
            except OSError:
                return tmp
        try:
            if os.path.isfile(tmp):
                os.remove(tmp)
        except OSError:
            pass
    return None


def poster_url_of_resolved(share, rel):
    """由调用方（有 share 上下文）计算海报 URL：优先同目录海报文件，否则走抽帧接口。"""
    rel = (rel or "").strip("/")
    if not rel:
        return ""
    try:
        real = share.resolve(rel)
    except (PermissionError, FileNotFoundError):
        return ""
    if not os.path.isfile(real):
        return ""
    poster = find_poster_file_cached(real)
    if poster:
        # 海报文件相对路径 = 视频所在目录 + 海报文件名
        parent = rel.rsplit("/", 1)[0] if "/" in rel else ""
        poster_rel = (parent + "/" if parent else "") + os.path.basename(poster)
        return "/media/" + urllib.parse.quote(poster_rel)
    # 无海报文件 → 走抽帧接口（前端按需触发，服务端缓存）
    return "/api/poster?path=" + urllib.parse.quote(rel)


SUB_EXT = (".srt", ".vtt", ".ass", ".ssa")

# 字幕语言标签：匹配命中越多、越靠前的中文标签优先级越高
_SUB_LANG_SUFFIXES = (
    # (后缀小写, 优先级 0=最优先)
    (".zh-cn", 0), (".zh-hans", 0), (".zh-cns", 0), (".chs", 0),
    (".chinese.simplified", 0), (".sc", 0), (".gb", 0), (".zh", 1),
    (".zh-hant", 1), (".cht", 1), (".chinese.traditional", 1), (".tc", 1),
    (".big5", 1), (".chi", 1), (".chinese", 1), (".chs&cht", 1), (".简中", 0),
    (".简体", 0), (".繁体", 1), (".简繁", 1), (".中字", 1), (".双语", 1),
    (".中文", 1), (".eng", 5), (".english", 5), (".en", 5), (".jp", 6),
    (".jpn", 6), (".japanese", 6),
)


def _sub_lang_score(name):
    """给候选字幕文件名打语言分：中文简体标签优先，无标签 3 分，纯英文 5 分。"""
    low = name.lower()
    for suf, pri in _SUB_LANG_SUFFIXES:
        if low.endswith(suf):
            return pri
    return 3


def find_subtitle(video_real):
    """找视频的外挂字幕，按「同名精确 → 语言标签 → 同名片段」优先级返回。

    覆盖常见命名：xxx.srt、xxx.zh-CN.srt、xxx.简体.srt、xxx.chs.ass、
    xxx.chi.srt、xxx.SC.srt 等；多个候选时优先中文简体。
    """
    stem = os.path.splitext(video_real)[0]
    d, base = os.path.split(video_real)
    stem_name = os.path.splitext(base)[0]

    candidates = []  # [(语言分, 路径)]
    seen = set()
    try:
        names = os.listdir(d)
    except OSError:
        names = []

    for name in names:
        low = name.lower()
        if not low.endswith(SUB_EXT):
            continue
        full = os.path.join(d, name)
        # 精确同名（含 .zh/.chs 等语言后缀）
        bare = os.path.splitext(name)[0]
        if bare == os.path.splitext(base)[0] or \
           bare.startswith(stem_name + ".") or \
           low.startswith(stem_name.lower() + "."):
            key = os.path.normcase(full)
            if key in seen:
                continue
            seen.add(key)
            candidates.append((_sub_lang_score(name), full))
            continue
        # 同名片段（比如 xxx.S01E01.mp4 配 xxx.S01E01.chs.srt 已在上面对上，
        # 这里兜底同名前缀但中间被语言标签隔开的情况）
        if low.startswith(stem_name.lower()):
            key = os.path.normcase(full)
            if key in seen:
                continue
            seen.add(key)
            candidates.append((_sub_lang_score(name), full))

    if not candidates:
        return None
    candidates.sort(key=lambda c: c[0])
    return candidates[0][1]


def has_subtitle(video_real):
    return find_subtitle(video_real) is not None


def read_text_any(path):
    for enc in ("utf-8-sig", "utf-8", "gb18030", "big5", "utf-16"):
        try:
            with open(path, "r", encoding=enc) as f:
                return f.read()
        except UnicodeDecodeError:
            continue
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def subtitle_to_vtt(path):
    text = read_text_any(path).replace("\r\n", "\n").replace("\r", "\n")
    if path.lower().endswith(".vtt"):
        return text if text.lstrip().startswith("WEBVTT") else "WEBVTT\n\n" + text
    if path.lower().endswith((".ass", ".ssa")):
        return ass_to_vtt(text)
    # SRT -> VTT：只有时间轴分隔符 . 与 , 的差别
    body = re.sub(r"(\d{2}:\d{2}:\d{2}),(\d{3})", r"\1.\2", text)
    return "WEBVTT\n\n" + body


def ass_to_vtt(text):
    """把 ASS/SSA 的 Dialogue 转成 VTT，够电视上看就行。"""
    lines = ["WEBVTT", ""]
    fmt = None
    for raw in text.split("\n"):
        line = raw.strip()
        if line.lower().startswith("format:") and "text" in line.lower():
            fmt = [p.strip().lower() for p in line.split(":", 1)[1].split(",")]
            continue
        if not line.lower().startswith("dialogue:"):
            continue
        parts = line.split(":", 1)[1].split(",", len(fmt) - 1 if fmt else 9)
        if len(parts) < 10:
            continue
        start, end, body = parts[1].strip(), parts[2].strip(), parts[-1]
        body = re.sub(r"\{[^}]*\}", "", body).replace("\\N", "\n").replace("\\n", "\n")
        lines.append(f"{ass_time(start)} --> {ass_time(end)}")
        lines.append(body)
        lines.append("")
    return "\n".join(lines)


def ass_time(t):
    m = re.match(r"(\d+):(\d{2}):(\d{2})[.:](\d{1,3})", t)
    if not m:
        return "00:00:00.000"
    h, mi, s, ms = m.groups()
    return f"{int(h):02d}:{mi}:{s}.{ms.ljust(3, '0')[:3]}"


# 播放页模板单独放在 player_template.py（手机 / TV 双适配）
from player_template import PLAY_TEMPLATE  # noqa: E402


def escape_html(s):
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;").replace("'", "&#39;"))


_TAG_HINT = re.compile(
    r"(字幕组|字幕社|压制|搬运|汉化|1080p|720p|2160p|4k|8k|web-?dl|webrip|bluray|bdrip|hdtv|"
    r"x26[45]|h\.?26[45]|hevc|avc|aac|ac3|eac3|flac|ddp?5\.1|nvenc|10bit|hdr|sdr|mp4|mkv|avi|"
    r"sub$|fansub|bea?nsub|fzsd|lilith|sakurato|neko|dmhy|jysub|dmg|reino|vcb|moozzi|nsw|ohys)",
    re.I)
_TAG_EXACT = re.compile(r"^(gb|big5|chs|cht|jpn|sc|tc|简体|繁体|简繁|中字|内嵌|双语|中日双语)$", re.I)


def _is_tag(t):
    if re.fullmatch(r"\d{1,3}", t):
        return True
    if re.fullmatch(r"[\d.\s]+(gb|mb|gib)", t, re.I):
        return True
    if _TAG_EXACT.match(t):
        return True
    return bool(_TAG_HINT.search(t))


def _series_name(filename):
    """从文件名提取系列名（去掉集数、画质、字幕组等标签）。

    '死神.27.GB.1080P.x264_AAC.mp4' -> '死神'
    '[字幕组][死神][27][1080P].mkv'  -> '死神'
    """
    base = os.path.splitext(filename)[0]
    # 去掉方括号里的标签
    kept = []
    def _take(mo):
        t = mo.group(1).strip()
        if t and not _is_tag(t):
            kept.append(t)
        return " "
    outside = re.sub(r"\[([^\]]*)\]", _take, base)
    clean = " ".join(kept) + " " + outside
    # 去掉集数标记
    clean = re.sub(r"[Ss]\d{1,2}[Ee]\d{1,3}", " ", clean)
    clean = re.sub(r"第\s*\d{1,3}\s*[集话話]", " ", clean)
    clean = re.sub(r"\b[Ee][Pp]?\d{1,3}\b", " ", clean)
    # 去掉画质/编码标签
    clean = re.sub(r"\b(2160p|1080p|720p|480p|4k|8k|web-?dl|webrip|bluray|bdrip|hdtv|"
                   r"x264|x265|h\.?264|h\.?265|hevc|avc|aac|ac3|eac3|flac|ddp?5\.1|"
                   r"nvenc|10bit|8bit|hdr10|hdr|sdr|hi10p)\b", " ", clean, flags=re.I)
    clean = re.sub(r"[_.\[\]]+", " ", clean)
    clean = re.sub(r"\s{2,}", " ", clean).strip(" -·_")
    # 如果清洗后太短或只剩数字，返回 None
    if len(clean.replace(" ", "")) < 2 or re.fullmatch(r"[\d\s]+", clean):
        return None
    return clean


def pretty_title(name):
    """把 [组名][片名][27][GB][1080P][x264_AAC].mp4 压成「第 27 集 · 片名」。"""
    base = os.path.splitext(name)[0]
    ep = None
    for pat in (r"\[(\d{1,3})\]", r"[Ss]\d{1,2}[Ee](\d{1,3})", r"第\s*(\d{1,3})\s*[集话話]"):
        m = re.search(pat, base)
        if m:
            ep = int(m.group(1))
            break

    kept = []

    def _take(mo):
        t = mo.group(1).strip()
        if t and not _is_tag(t):
            kept.append(t)
        return " "

    outside = re.sub(r"\[([^\]]*)\]", _take, base)
    clean = " ".join(kept) + " " + outside
    clean = re.sub(r"\b[Ss]\d{1,2}[Ee]\d{1,3}\b", " ", clean)
    clean = re.sub(r"\b(2160p|1080p|720p|4k|8k|webrip|web-dl|web|bluray|bdrip|hdtv|"
                   r"x264|x265|h\.?264|h\.?265|hevc|avc|aac|ac3|eac3|ddp?5\.1|nvenc|10bit|hdr|sdr)\b",
                   " ", clean, flags=re.I)
    clean = re.sub(r"[_.]+", " ", clean)
    clean = re.sub(r"\s{2,}", " ", clean).strip(" -·")
    if len(clean.replace(" ", "")) < 2:
        clean = base
    if ep:
        return f"第 {ep} 集" + (f" · {clean}" if clean else "")
    return clean


def detail_page(rel, title, episodes):
    """选集页：显示同系列所有集数，点一集跳 /play（电视端被原生播放器拦截）。"""
    esc = lambda s: (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                     .replace('"', "&quot;"))
    items = []
    for i, ep in enumerate(episodes):
        cur_cls = " class=\"cur\"" if ep["current"] else ""
        cur_badge = "<span class=\"cbadge\">▶ 播放中</span>" if ep["current"] else ""
        ep_url = "/play?path=" + urllib.parse.quote(ep["path"], safe="/") + "&title=" + urllib.parse.quote(ep["title"])
        items.append(
            f'<a href="{ep_url}" class="ep-item{(" cur" if ep["current"] else "")}" data-idx="{i}">'
            f'<span class="ep-num">{i + 1}</span>'
            f'<span class="ep-name">{esc(ep["title"] or ep["name"])}</span>'
            f'{cur_badge}'
            f'<span class="ep-size">{ep["size_text"]}</span>'
            f'</a>'
        )
    total = len(episodes)
    cur_idx = next((i for i, e in enumerate(episodes) if e["current"]), 0)
    items_html = "\n".join(items)
    return f"""<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>{esc(title)} · 选集</title>
<style>
  html,body{{margin:0;height:100%;background:#0a0e14;color:#e8eef7;
    font-family:system-ui,-apple-system,"Microsoft YaHei",sans-serif;overflow:hidden}}
  .top{{padding:22px 28px 12px;display:flex;align-items:center;gap:16px}}
  .back{{flex:none;color:#0a0d13;background:#22d3ee;text-decoration:none;padding:10px 22px;
    border-radius:999px;font-size:15px;font-weight:700;cursor:pointer;border:0}}
  .back:focus{{box-shadow:0 0 0 3px #ffffffaa;transform:scale(1.04)}}
  .ttl{{flex:1;font-size:21px;font-weight:700;color:#f0f6ff;overflow:hidden;
    text-overflow:ellipsis;white-space:nowrap}}
  .cnt{{flex:none;font-size:14px;color:#8fa3b8;background:#ffffff0d;padding:5px 14px;border-radius:99px}}
  .list{{padding:8px 28px 100px;overflow-y:auto;height:calc(100% - 78px);
    display:flex;flex-direction:column;gap:8px}}
  .ep-item{{display:flex;align-items:center;gap:14px;padding:15px 20px;border-radius:13px;
    background:#111823;border:1px solid #1c2a3a;color:#dde6f0;text-decoration:none;
    cursor:pointer;transition:all .15s;flex-shrink:0}}
  .ep-item:hover,.ep-item:focus{{background:#1a2536;border-color:#22d3ee88;
    box-shadow:0 0 0 2px #22d3ee55;transform:scale(1.015);outline:none}}
  .ep-item.cur{{border-color:#22d3ee66;background:#0d2a3a}}
  .ep-num{{flex:none;width:38px;height:38px;border-radius:10px;background:#22d3ee22;
    color:#22d3ee;font-size:17px;font-weight:700;display:flex;align-items:center;justify-content:center}}
  .ep-name{{flex:1;font-size:17px;line-height:1.35;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}}
  .cbadge{{flex:none;font-size:13px;color:#22d3ee;background:#22d3ee22;padding:3px 11px;border-radius:99px;font-weight:600}}
  .ep-size{{flex:none;font-size:13px;color:#6b7f94}}
  .hint{{position:fixed;bottom:0;left:0;right:0;height:48px;display:flex;align-items:center;
    justify-content:center;gap:22px;font-size:13px;color:#7a8ea3;
    background:linear-gradient(transparent,#0a0e14 40%);z-index:5}}
  .kbd{{display:inline-block;background:#ffffff12;border:1px solid #ffffff22;border-radius:6px;
    padding:2px 8px;font-size:12px;margin-right:4px}}
  /* 电视 */
  body.tv .top{{padding:28px 36px 16px}}
  body.tv .back{{font-size:21px;padding:14px 30px}}
  body.tv .ttl{{font-size:28px}}
  body.tv .cnt{{font-size:18px;padding:7px 18px}}
  body.tv .list{{padding:12px 36px 120px;gap:12px}}
  body.tv .ep-item{{padding:20px 26px;border-radius:15px;gap:18px}}
  body.tv .ep-num{{width:48px;height:48px;font-size:22px;border-radius:13px}}
  body.tv .ep-name{{font-size:23px}}
  body.tv .cbadge{{font-size:17px;padding:5px 14px}}
  body.tv .ep-size{{font-size:17px}}
  body.tv .hint{{height:60px;font-size:18px;gap:30px}}
  body.tv .kbd{{font-size:16px;padding:3px 11px}}
</style></head>
<body>
<div class="top">
  <a class="back" href="javascript:history.back()">← 返回</a>
  <div class="ttl">{esc(title)}</div>
  <div class="cnt">共 {total} 集</div>
</div>
<div class="list" id="list">
{items_html}
</div>
<div class="hint">
  <span><span class="kbd">▲ ▼</span>选集</span>
  <span><span class="kbd">OK</span>播放</span>
  <span><span class="kbd">返回</span>回列表</span>
</div>
<script>
  if (/Android|TV|BRAVIA|MiBOX|NVIDIA SHIELD|Xming|Formovie/i.test(navigator.userAgent)) {{
    document.body.classList.add('tv');
  }}
  var items = document.querySelectorAll('.ep-item');
  var cur = {cur_idx};
  function focusEp(i) {{
    if (i < 0) i = 0;
    if (i >= items.length) i = items.length - 1;
    cur = i;
    items[i].focus();
    items[i].scrollIntoView({{block:'nearest'}});
  }}
  // 初始聚焦当前集
  setTimeout(function(){{ focusEp(cur); }}, 80);
  document.addEventListener('keydown', function(e){{
    var code = e.keyCode;
    if (code === 38 || code === 40) {{  // 上下
      e.preventDefault();
      focusEp(code === 38 ? cur - 1 : cur + 1);
    }} else if (code === 37 || code === 39) {{  // 左右也当上下用，遥控器手感更好
      e.preventDefault();
      focusEp(code === 37 ? cur - 1 : cur + 1);
    }} else if (code === 13) {{  // OK
      e.preventDefault();
      if (items[cur]) location.href = items[cur].getAttribute('href');
    }} else if (code === 4 || code === 27 || code === 8) {{  // 返回
      e.preventDefault();
      history.back();
    }}
  }});
</script>
</body></html>"""


def play_page(rel, name, base_url, real, title=""):
    quoted = urllib.parse.quote(rel)
    parent = rel.rsplit("/", 1)[0] if "/" in rel else ""
    back = "/?p=" + urllib.parse.quote(parent) if parent else "/"
    media_url = base_url + "/media/" + quoted
    host = base_url.split("//", 1)[-1]
    intent_url = "intent://" + host + "/media/" + quoted + "#Intent;scheme=http;type=video/*;end"

    # 字幕：外挂同名文件 + 容器内嵌轨（MKV 的 ASS/SUP 等，靠 ffmpeg 抽）
    sub_items = []
    if find_subtitle(real):
        sub_items.append({"label": "外挂字幕", "lang": "zh", "idx": -1})
    for i, t in enumerate(probe_subtitles(real)):
        sub_items.append({"label": _track_label(t, i), "lang": (t.get("language") or "zh")[:5],
                          "idx": t["index"]})
    tracks = []
    for pos, item in enumerate(sub_items):
        src = "/api/subtitle?path=" + quoted
        if item["idx"] >= 0:
            src += "&i=" + str(item["idx"])
        tracks.append(
            '<track kind="subtitles" srclang="%s" label="%s" data-i="%d"%s src="%s">' % (
                escape_html(item["lang"] or "zh"), escape_html(item["label"]),
                item["idx"], " default" if pos == 0 else "", escape_html(src)))
    sub_tag = "\n         ".join(tracks)

    shown = title.strip() or pretty_title(name)
    # 音轨列表：传给前端做切换按钮（浏览器 video.audioTracks 只读不了中文名，
    # 用 ffprobe 拿到的 label 来显示）
    audio_items = [{"label": t["label"], "index": t["index"], "default": t["default"]}
                   for t in probe_audio_tracks(real)]
    audio_json = json.dumps(audio_items, ensure_ascii=False)
    return (PLAY_TEMPLATE
            .replace("{{BACK}}", back)
            .replace("{{SRC}}", quoted)
            .replace("{{SUB}}", sub_tag)
            .replace("{{SUBNUM}}", str(len(sub_items)))
            .replace("{{SUBNAME}}", escape_html(sub_items[0]["label"] if sub_items else ""))
            .replace("{{AUDIO}}", audio_json)
            .replace("{{TITLE}}", escape_html(shown))
            .replace("{{NAME}}", escape_html(shown))
            .replace("{{RAW}}", escape_html(name))
            .replace("{{MEDIA}}", media_url)
            .replace("{{INTENT}}", intent_url.replace("'", "%27")))


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def build_roots(cfg, cli_path):
    """命令行给了路径就只共享它；否则读 config.json 的 roots，再退回单根 root。
    自动追加「手机上传」目录，手机无线上传的文件默认存在那里。"""
    if cli_path:
        roots = [(os.path.basename(os.path.abspath(cli_path)) or "共享", cli_path)]
    else:
        roots = []
        for item in cfg.get("roots") or []:
            if isinstance(item, str):
                roots.append((os.path.basename(item) or item, item))
            elif isinstance(item, dict) and item.get("path"):
                roots.append((item.get("name") or os.path.basename(item["path"]), item["path"]))
        if not roots and cfg.get("root"):
            roots.append((cfg.get("name") or os.path.basename(cfg["root"]) or "共享", cfg["root"]))

    # 自动追加「手机上传」目录
    upload_dir = os.path.join(HERE, UPLOAD_DIR_NAME)
    has_upload = any(n == UPLOAD_DIR_NAME for n, _ in roots)
    if not has_upload:
        try:
            os.makedirs(upload_dir, exist_ok=True)
            roots.append((UPLOAD_DIR_NAME, upload_dir))
        except OSError:
            pass
    return roots


def main():
    cfg = load_config()
    cli_path = sys.argv[1] if len(sys.argv) > 1 else ""
    port = int(sys.argv[2]) if len(sys.argv) > 2 else int(cfg.get("port", 8848))
    host = cfg.get("host", "0.0.0.0")

    roots = build_roots(cfg, cli_path)
    usable, missing = [], []
    for name, path in roots:
        (usable if os.path.isdir(path) else missing).append((name, path))
    for name, path in missing:
        print(f"[警告] 跳过不存在的共享目录: {name} -> {path}")
    if not usable:
        print(f"[错误] 没有可用的共享目录: {roots!r}")
        print("       请修改 config.json 里的 roots（或 root），也可以运行时传路径: python server.py \"D:\\\\电影\"")
        sys.exit(1)

    share = Share(usable)
    httpd = Server((host, port), Handler)
    httpd.share = share
    httpd.quiet = bool(cfg.get("quiet", False))

    ips = local_ips()
    httpd.public_base = f"http://{ips[0] if ips else '127.0.0.1'}:{port}"

    line = "─" * 52
    print(line)
    print(f"  {cfg.get('title', 'MediaShare')}  ·  局域网影音共享")
    print(line)
    print(f"  监听端口: {port}")
    print("  共享内容:")
    for name, path in share.roots:
        print(f"    {name}  ->  {path}")
    print()
    if ips:
        print("  投影仪 / 手机在浏览器里打开下面任一地址：")
        for ip in ips:
            print(f"    http://{ip}:{port}")
    else:
        print("  [警告] 没有检测到局域网地址，检查一下 Wi-Fi 是否连着")
    print()
    print("  停止服务：在本窗口按 Ctrl+C")
    print(line)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n  已停止。")
    finally:
        httpd.server_close()


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    main()
