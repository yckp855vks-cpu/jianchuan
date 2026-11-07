#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
简传 Lite —— 局域网文件 / 文本互传
零依赖：只用 Python 3 标准库，不需要 pip install 任何东西。

用法：
    python server.py                # 默认 8787 端口，自动打开浏览器
    python server.py --port 9000    # 指定端口
    python server.py --no-browser   # 不自动开浏览器
"""
from __future__ import annotations

import argparse
import ctypes
import hmac
import io
import json
import mimetypes
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import traceback
import urllib.request
import uuid
import webbrowser
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, quote

# 桌面通知：winotify 纯 Python、无二进制依赖，toast 会进操作中心可回看。
# 不可用（未安装 / 用户机 PowerShell 被禁）时降级控制台打印，绝不阻断收文件。
try:
    from winotify import Notification as _WinNotification
except Exception:  # noqa: BLE001
    _WinNotification = None

# 剪贴板双向同步：手机当 BLE 外设，本机 bleak 连过去；蓝牙不可用时退回局域网 HTTP。
# 整个模块缺失（没装 bleak/pyperclip）也不能拖垮文件传输主功能。
try:
    import clipboard_bridge as _cb
except Exception:  # noqa: BLE001
    _cb = None

APP_NAME = "简传 Lite"
# 客户端版本号。页面右下角显示的就是它，打包脚本也读它来命名 exe
# （简传Lite-v1.12.0.exe），所以改这里一处，界面和文件名同时生效。
# 早期这里是 "1.2.0"，和 APK 的 1.11.x 完全对不上，看不出谁新谁旧，统一到 1.12.x。
VERSION = "1.14.3"
# HTTP Server 头用的协议版本，与产品版本无关，别跟着改
PROTO_VERSION = "1.2.0"

# 路径：打包成 exe 后，static 等「只读资源」在 PyInstaller 解压目录里，
# 而 data（消息/文件/密码）必须放在 exe 旁边，跟 exe 一起搬家。
if getattr(sys, "frozen", False):
    BASE_DIR = os.path.dirname(os.path.abspath(sys.executable))
    RESOURCE_DIR = getattr(sys, "_MEIPASS", BASE_DIR)
else:
    BASE_DIR = os.path.dirname(os.path.abspath(__file__))
    RESOURCE_DIR = BASE_DIR

ROOT = BASE_DIR
DATA_DIR = os.path.join(BASE_DIR, "data")
FILE_DIR = os.path.join(DATA_DIR, "files")
STORE_PATH = os.path.join(DATA_DIR, "messages.json")
CONFIG_PATH = os.path.join(DATA_DIR, "config.json")
STATIC_DIR = os.path.join(RESOURCE_DIR, "static")


def latest_apk():
    """挑 static 目录里版本号最高的 jianchuan-v*.apk，返回 (文件名, 绝对路径)。

    以前这里写死文件名，每出一版 APK 都要改代码 + 重打 exe；忘了改就会
    网页挂着旧包、卡片上显示旧版本号（用户已经踩过两次）。
    目录里没有就直接返回 ("", None)。
    """
    try:
        names = [n for n in os.listdir(STATIC_DIR)
                 if n.startswith("jianchuan-v") and n.endswith(".apk")]
    except OSError:
        return "", None
    if not names:
        return "", None

    def _ver(n):
        m = re.search(r"v(\d+(?:\.\d+)*)", n)
        return tuple(int(x) for x in m.group(1).split(".")) if m else (0,)

    best = max(names, key=_ver)
    return best, os.path.join(STATIC_DIR, best)


MAX_UPLOAD = 4 * 1024 * 1024 * 1024   # 单文件上限 4GB
HISTORY_LIMIT = 1500                  # 服务器保留的消息条数
ONLINE_TTL = 30.0                     # 超过这么久没轮询就算离线
SESSION_TTL = 7 * 24 * 3600           # 登录一次管 7 天

os.makedirs(FILE_DIR, exist_ok=True)

# ---------------------------------------------------------------- 内存状态
_lock = threading.RLock()
_messages: list = []
_next_id: int = 1
_online: dict = {}
_sessions: dict = {}                  # token -> 过期时间戳
PASSWORD = ""
PORT = 8787
_tunnel_proc = None                   # cloudflared 子进程

# 剪贴板桥单例。构造本身不碰硬件，蓝牙在 start() 时才起线程。
_clip_bridge = (
    _cb.ClipboardBridge(log=lambda m: print(f"[剪贴板] {m}", flush=True))
    if _cb is not None else None
)


def clip_status() -> dict:
    if _clip_bridge is None:
        return {
            "enabled": False,
            "available": False,
            "last": "剪贴板模块未加载（缺 bleak 或 pyperclip）",
        }
    return _clip_bridge.status()
_tunnel_url = ""                      # 抓到的公网网址
_tunnel_lock = threading.RLock()


# ---------------------------------------------------------------- 数据读写
def load_store() -> None:
    global _messages, _next_id
    if not os.path.isfile(STORE_PATH):
        return
    try:
        with open(STORE_PATH, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        ms = data.get("messages")
        if isinstance(ms, list):
            _messages = ms
        _next_id = int(data.get("next_id") or ((ms[-1]["id"] + 1) if ms else 1))
    except Exception as exc:  # noqa: BLE001
        print(f"[警告] 历史记录读取失败：{exc}")


def save_store() -> None:
    with _lock:
        snapshot = {"messages": _messages, "next_id": _next_id}
    tmp = STORE_PATH + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(snapshot, fh, ensure_ascii=False)
        os.replace(tmp, STORE_PATH)
    except OSError as exc:
        print(f"[警告] 历史记录写入失败：{exc}")


# ---------------------------------------------------------------- 幂等去重
# 手机切网络时，请求可能已经到达服务器但响应丢了，用户重发就会产生重复消息。
# 前端为每条消息生成一个 dedupe key，同一个 key 在 TTL 内只落库一次。
_dedupe: dict = {}
DEDUPE_TTL = 600


def dedupe_lookup(key: str):
    if not key:
        return None
    with _lock:
        item = _dedupe.get(key)
        if not item:
            return None
        if time.time() - item[0] > DEDUPE_TTL:
            _dedupe.pop(key, None)
            return None
        return item[1]


def dedupe_store(key: str, msg: dict) -> None:
    if not key:
        return
    with _lock:
        _dedupe[key] = (time.time(), msg)
        if len(_dedupe) > 3000:
            now = time.time()
            for k in [k for k, v in _dedupe.items() if now - v[0] > DEDUPE_TTL]:
                _dedupe.pop(k, None)


def add_message(payload: dict) -> dict:
    global _messages, _next_id
    with _lock:
        msg = dict(payload)
        msg["id"] = _next_id
        msg["time"] = round(time.time(), 3)
        _next_id += 1
        _messages.append(msg)
        dropped = []
        if len(_messages) > HISTORY_LIMIT:
            dropped = _messages[:-HISTORY_LIMIT]
            _messages = _messages[-HISTORY_LIMIT:]
        save_store()
    for old in dropped:
        f = old.get("file") or {}
        if f.get("stored"):
            try:
                os.remove(os.path.join(FILE_DIR, f["stored"]))
            except OSError:
                pass
    return msg


def find_message(mid: int):
    with _lock:
        for m in reversed(_messages):
            if m.get("id") == mid:
                return m
    return None


def clear_all() -> None:
    global _messages
    with _lock:
        old = _messages
        _messages = []
        save_store()
    for m in old:
        f = m.get("file") or {}
        if f.get("stored"):
            try:
                os.remove(os.path.join(FILE_DIR, f["stored"]))
            except OSError:
                pass


def delete_one(mid: int) -> bool:
    global _messages
    with _lock:
        target = None
        keep = []
        for m in _messages:
            if m.get("id") == mid:
                target = m
            else:
                keep.append(m)
        if target is None:
            return False
        _messages = keep
        save_store()
    f = target.get("file") or {}
    if f.get("stored"):
        try:
            os.remove(os.path.join(FILE_DIR, f["stored"]))
        except OSError:
            pass
    return True


# ---------------------------------------------------------------- 在线设备
def device_name(agent: str) -> str:
    ua = (agent or "").lower()
    if not ua:
        return "未知设备"
    if "iphone" in ua:
        return "iPhone"
    if "ipad" in ua:
        return "iPad"
    if "android" in ua:
        return "Android"
    if "windows nt" in ua:
        if "edg/" in ua:
            return "Windows · Edge"
        if "firefox" in ua:
            return "Windows · Firefox"
        return "Windows · 浏览器"
    if "macintosh" in ua or "mac os x" in ua:
        if "safari" in ua and "chrome" not in ua:
            return "Mac · Safari"
        return "Mac"
    if "linux" in ua:
        return "Linux"
    return "浏览器"


def touch_client(cid: str, ip: str, agent: str) -> None:
    if not cid:
        return
    with _lock:
        _online[cid] = {
            "id": cid,
            "ip": ip,
            "ua": agent,
            "device": device_name(agent),
            "last": time.time(),
        }


def online_list() -> list:
    now = time.time()
    with _lock:
        dead = [k for k, v in _online.items() if now - v.get("last", 0) > ONLINE_TTL]
        for k in dead:
            _online.pop(k, None)
        return [{k: v for k, v in item.items() if k != "last"} for item in _online.values()]


# ---------------------------------------------------------------- 访问密码 / 会话 / 设置
def read_config() -> dict:
    cfg: dict = {}
    if os.path.isfile(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as fh:
                cfg = json.load(fh)
        except Exception:  # noqa: BLE001
            cfg = {}
    return cfg


def write_config(cfg: dict) -> None:
    try:
        with open(CONFIG_PATH, "w", encoding="utf-8") as fh:
            json.dump(cfg, fh, ensure_ascii=False, indent=2)
    except OSError:
        pass


def load_password(cmd_arg: str) -> str:
    """密码优先级：命令行 --password > data/config.json 里的 > 随机生成并持久化。"""
    cfg = read_config()
    pw = cmd_arg or (cfg.get("password") or "")
    if not pw:
        pw = secrets.token_urlsafe(12)   # 随机生成，重启不变
        cfg["password"] = pw
        write_config(cfg)
    return pw


def new_session() -> str:
    tok = secrets.token_hex(32)
    with _lock:
        now = time.time()
        _sessions[tok] = now + SESSION_TTL
        for k in [k for k, v in _sessions.items() if v < now]:
            _sessions.pop(k, None)
    return tok


def check_session(tok: str) -> bool:
    if not tok:
        return False
    with _lock:
        exp = _sessions.get(tok)
        if exp and exp > time.time():
            return True
        _sessions.pop(tok, None)
        return False


# ---------------------------------------------------------------- 公网隧道
def _unpack_bundled_cloudflared():
    """把包里那份 cloudflared.zip 解到 data 目录，返回 exe 路径（只解一次）。

    为什么不直接把 62MB 的 exe 塞进包里：PyInstaller 的 onefile 每次启动都要把
    内嵌文件解到临时目录，实测启动从 3.3s 变成 5.0s。改成包里存 17MB 的 zip，
    启动只解 17MB，真正的 62MB 解压推迟到第一次开公网（那时本来也要等几秒）。
    """
    box = os.path.join(DATA_DIR, "cloudflared")
    if os.path.isdir(box):
        for n in sorted(os.listdir(box)):
            if n.lower().endswith(".exe"):
                return os.path.join(box, n)
    base = getattr(sys, "_MEIPASS", None)
    if not base:
        return None
    zpath = os.path.join(base, "cloudflared", "cloudflared.zip")
    if not os.path.isfile(zpath):
        return None
    try:
        os.makedirs(box, exist_ok=True)
        with zipfile.ZipFile(zpath) as z:
            z.extractall(box)
        for n in sorted(os.listdir(box)):
            if n.lower().endswith(".exe"):
                return os.path.join(box, n)
    except Exception:
        print("[提示] 内置 cloudflared 解压失败", file=sys.stderr)
    return None


def cloudflared_available() -> bool:
    """有没有可用的 cloudflared。**只查文件存在，不解压**。

    状态查询（/api/tunnel）每次都会问，如果这里顺手解压 62MB，
    页面一打开就卡两秒。解压留到真正开隧道时再做。
    """
    home = os.path.expandvars("%USERPROFILE%")
    for c in (os.path.join(home, "Documents", "cloudflared-windows-amd64.exe"),
              os.path.join(home, "Documents", "cloudflared.exe")):
        if os.path.isfile(c):
            return True
    box = os.path.join(DATA_DIR, "cloudflared")
    if os.path.isdir(box):
        try:
            if any(n.lower().endswith(".exe") for n in os.listdir(box)):
                return True
        except OSError:
            pass
    base = getattr(sys, "_MEIPASS", None)
    if base and os.path.isfile(os.path.join(base, "cloudflared", "cloudflared.zip")):
        return True
    return bool(shutil.which("cloudflared") or shutil.which("cloudflared-windows-amd64"))


def find_cloudflared():
    """找 cloudflared.exe：用户自放的优先，其次是打包内置的那份（会解压）。

    以前只有「文档目录 + PATH」两条路，于是这个软件拷给别人就等于没有公网功能
    —— 对方电脑上根本没这个文件，拨开关只会弹「没找到 cloudflared」。
    """
    home = os.path.expandvars("%USERPROFILE%")
    candidates = [
        os.path.join(home, "Documents", "cloudflared-windows-amd64.exe"),
        os.path.join(home, "Documents", "cloudflared.exe"),
    ]
    for c in candidates:
        if os.path.isfile(c):
            return c
    got = _unpack_bundled_cloudflared()
    if got:
        return got
    return shutil.which("cloudflared") or shutil.which("cloudflared-windows-amd64")


def tunnel_status() -> dict:
    running = False
    with _tunnel_lock:
        if _tunnel_proc is not None and _tunnel_proc.poll() is None:
            running = True
    return {"running": running, "url": _tunnel_url, "cloudflared": cloudflared_available()}


def _read_tunnel_output(proc) -> None:
    global _tunnel_url
    try:
        for line in proc.stdout:
            line = line.strip()
            if not line:
                continue
            m = re.search(r"https://[a-zA-Z0-9-]+\.trycloudflare\.com", line)
            if m and not _tunnel_url:
                _tunnel_url = m.group(0)
                try:
                    with open(os.path.join(ROOT, "公网地址.txt"), "w", encoding="utf-8") as fh:
                        fh.write("公网地址：" + _tunnel_url + "\n")
                except OSError:
                    pass
    except Exception:  # noqa: BLE001
        pass


def tunnel_start() -> dict:
    global _tunnel_proc, _tunnel_url
    cfd = find_cloudflared()
    if not cfd:
        return {"ok": False, "error": "没找到 cloudflared，请确认安装位置"}
    with _tunnel_lock:
        if _tunnel_proc is not None and _tunnel_proc.poll() is None:
            return {"ok": True, "url": _tunnel_url, "running": True}
    _tunnel_url = ""
    try:
        creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        proc = subprocess.Popen(
            [cfd, "tunnel", "--url", f"http://127.0.0.1:{PORT}", "--no-autoupdate"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", bufsize=1,
            creationflags=creationflags,
        )
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": str(exc)}
    _tunnel_proc = proc
    threading.Thread(target=_read_tunnel_output, args=(proc,), daemon=True).start()
    return {"ok": True, "url": _tunnel_url, "running": True}


def tunnel_stop() -> dict:
    global _tunnel_proc, _tunnel_url
    with _tunnel_lock:
        proc = _tunnel_proc
        _tunnel_proc = None
    if proc is not None:
        try:
            proc.terminate()
        except Exception:  # noqa: BLE001
            pass
        time.sleep(0.4)
        try:
            proc.kill()
        except Exception:  # noqa: BLE001
            pass
    _tunnel_url = ""
    return {"ok": True}


# ---------------------------------------------------------------- 局域网自动发现（UDP）
DISCOVERY_PORT = 8788


def udp_discovery_server() -> None:
    """监听 UDP 广播：手机 App 发一句「JC_DISCOVER」，就回答自己在哪个端口。
    这样 App 打开就能自动找到同一局域网里的电脑，不用手动输地址。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.bind(("0.0.0.0", DISCOVERY_PORT))
    except OSError:
        return
    while True:
        try:
            data, addr = s.recvfrom(2048)
        except OSError:
            break
        if data.strip() == b"JC_DISCOVER":
            try:
                ips = all_ips()
                reply = json.dumps({
                    "app": APP_NAME,
                    "port": PORT,
                    "host": socket.gethostname(),
                    "ip": ips[0] if ips else addr[0],
                    "ips": ips,
                }).encode("utf-8")
                s.sendto(reply, addr)
            except OSError:
                pass


# ---------------------------------------------------------------- 网络工具
def _probe_ip(target: str):
    """向目标发一个 UDP 包（不会真的发出去），用本地出口地址判断当前走哪块网卡。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.settimeout(0.3)
        s.connect((target, 1))
        ip = s.getsockname()[0]
        if ip.startswith("127.") or ip.startswith("0.") or ip.startswith("169.254."):
            return None
        return ip
    except Exception:  # noqa: BLE001
        return None
    finally:
        s.close()


def all_ips() -> list:
    """实时枚举本机所有可用局域网 IPv4，默认路由所在的那块网卡排最前。

    每次调用都重新探测 —— 换 WiFi / 插拔网线 / 开热点之后地址会变，
    不能启动时算一次就记住（这是旧版本的 bug）。
    """
    ordered: list = []
    for target in ("10.255.255.255", "8.8.8.8", "1.1.1.1", "114.114.114.114"):
        ip = _probe_ip(target)
        if ip and ip not in ordered:
            ordered.append(ip)
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip in ordered:
                continue
            if ip.startswith("127.") or ip.startswith("0.") or ip.startswith("169.254."):
                continue
            ordered.append(ip)
    except Exception:  # noqa: BLE001
        pass
    if not ordered:
        ordered.append("127.0.0.1")
    return ordered


def lan_ip() -> str:
    return all_ips()[0]


LAN_IP = "127.0.0.1"


def pick_port(start: int, tries: int = 25) -> int:
    for port in range(start, start + tries):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            s.bind(("0.0.0.0", port))
            return port
        except OSError:
            continue
        finally:
            s.close()
    raise SystemExit(f"[错误] {start}~{start + tries} 之间没有可用端口")


def safe_filename(raw: str) -> str:
    name = (raw or "").replace("\\", "/")
    name = name.split("/")[-1]
    name = "".join(ch for ch in name if ch.isprintable()).strip()
    name = name.lstrip(".")
    return (name or "file")[:180]


# ---------------------------------------------------------------- 自动落盘 + 通知
# 手机传来的文件原本只落在 data/files/ 里（历史消息会被清理），用户看不到。
# 这里额外复制/硬链接一份到用户可见的下载目录，并弹桌面通知。

def user_save_dir() -> str:
    """落盘目录：config.json 的 save_dir 可覆盖，默认 %USERPROFILE%\\Downloads\\简传Lite"""
    cfg = read_config()
    d = (cfg.get("save_dir") or "").strip()
    if not d:
        d = os.path.join(
            os.environ.get("USERPROFILE") or os.path.expanduser("~"),
            "Downloads", "简传Lite",
        )
    os.makedirs(d, exist_ok=True)
    return d


def unique_path(d: str, name: str) -> str:
    """冲突改名：文件.ext → 文件 (1).ext → 文件 (2).ext（与手机端 MobileServer 同规则）"""
    base, ext = os.path.splitext(name)
    cand = os.path.join(d, name)
    i = 1
    while os.path.exists(cand):
        cand = os.path.join(d, f"{base} ({i}){ext}")
        i += 1
    return cand


def save_to_user_dir(src: str, name: str) -> dict:
    """把 data/files 里的文件落到用户下载目录。同盘硬链接秒级且不占双份空间，跨盘回退复制。"""
    try:
        dst = unique_path(user_save_dir(), name)
        try:
            os.link(src, dst)
        except OSError:
            shutil.copy2(src, dst)
        return {"ok": True, "path": dst}
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": str(exc)}


# 接收记录：给网页右栏「最近收到」用，复用已有的 1.2s 轮询，不加新端点
_received: list = []
_RECEIVED_LIMIT = 50


def record_received(name: str, size: int, saved: dict, stored: str = "") -> None:
    entry = {
        "name": name,
        "size": size,
        "ok": bool(saved.get("ok")),
        "path": saved.get("path", ""),
        # stored 是 data/files 里的物理文件名，用来把「聊天记录里的这条文件」
        # 和「落盘后的真实路径」对上号（落盘时可能被改名成 xxx (1).ext）
        "stored": stored,
        "error": saved.get("error", ""),
        "time": round(time.time(), 3),
    }
    with _lock:
        _received.append(entry)
        if len(_received) > _RECEIVED_LIMIT:
            del _received[:-_RECEIVED_LIMIT]


def saved_path_of(stored: str) -> str:
    """按 stored 反查用户落盘路径（落盘失败或没记录时返回空串）"""
    if not stored:
        return ""
    with _lock:
        for entry in reversed(_received):
            if entry.get("stored") == stored and entry.get("ok") and entry.get("path"):
                if os.path.isfile(entry["path"]):
                    return entry["path"]
    return ""


# ---------------------------------------------------------------- 在文件夹中显示
# 传完文件还得自己去翻下载目录，很烦。这里让网页上的文件夹图标能直接定位到文件。

def _reveal_allowed_roots() -> list[str]:
    roots = [FILE_DIR]
    try:
        roots.append(user_save_dir())
    except Exception:  # noqa: BLE001
        pass
    return [os.path.realpath(r) for r in roots]


def _find_explorer_windows(folder_name: str) -> list:
    """枚举标题里含 folder_name 的资源管理器窗口句柄。

    Windows 前台锁（foreground lock）会让后台进程刚拉起的资源管理器窗口停在别人后面，
    所以打开后要主动把它提到前台，否则用户看到的是「窗口被简传挡住了」。
    """
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.windll.user32
    found = []

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def _cb(hwnd, _lparam):
        if not user32.IsWindowVisible(hwnd):
            return True
        cls = ctypes.create_unicode_buffer(256)
        user32.GetClassNameW(hwnd, cls, 256)
        if cls.value != "CabinetWClass":
            return True
        n = user32.GetWindowTextLengthW(hwnd)
        if n <= 0:
            return True
        title = ctypes.create_unicode_buffer(n + 1)
        user32.GetWindowTextW(hwnd, title, n + 1)
        if folder_name and folder_name.lower() in title.value.lower():
            found.append(hwnd)
        return True

    user32.EnumWindows(_cb, 0)
    return found


def _force_foreground(hwnd) -> None:
    """把窗口拉到最前并抢焦点。AttachThreadInput 是为了绕开前台锁。"""
    try:
        import ctypes

        user32 = ctypes.windll.user32
        kernel32 = ctypes.windll.kernel32
        if user32.IsIconic(hwnd):
            user32.ShowWindow(hwnd, 9)  # SW_RESTORE
        fg = user32.GetForegroundWindow()
        cur_tid = kernel32.GetCurrentThreadId()
        fg_tid = user32.GetWindowThreadProcessId(fg, None)
        attached = False
        if fg_tid and fg_tid != cur_tid:
            attached = bool(user32.AttachThreadInput(fg_tid, cur_tid, True))
        user32.BringWindowToTop(hwnd)
        user32.SetForegroundWindow(hwnd)
        if attached:
            user32.AttachThreadInput(fg_tid, cur_tid, False)
    except Exception:  # noqa: BLE001
        pass


def _focus_explorer(folder_name: str, timeout: float = 2.5) -> None:
    """explorer 起来要几百毫秒，轮询等窗口出现再置前。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            hwnds = _find_explorer_windows(folder_name)
        except Exception:  # noqa: BLE001
            return
        if hwnds:
            _force_foreground(hwnds[-1])
            return
        time.sleep(0.12)


def reveal_in_explorer(path: str) -> dict:
    """在资源管理器里选中该文件（Windows）；非 Windows 退化为打开所在目录。

    安全：调用方传进来的路径必须先通过 _reveal_allowed_roots() 白名单校验，
    否则网页上一个构造好的 path 就能让本机打开任意目录。
    """
    target = os.path.realpath(path)
    if not os.path.isfile(target):
        return {"ok": False, "error": "文件已不在原位置（可能被移动或删除）"}
    try:
        if sys.platform.startswith("win"):
            # 用 list 形式传参，不走 shell —— 路径里带引号/&/| 都不会有注入问题。
            # explorer.exe 成功时也常返回 1，所以不看返回码。
            subprocess.Popen(["explorer", "/select,", target], close_fds=True)
            # 置前要等窗口出现，放后台线程，别把 HTTP 响应拖住
            folder_name = os.path.basename(os.path.dirname(target))
            threading.Thread(
                target=_focus_explorer, args=(folder_name,), daemon=True
            ).start()
        elif sys.platform == "darwin":
            subprocess.Popen(["open", "-R", target], close_fds=True)
        else:
            subprocess.Popen(["xdg-open", os.path.dirname(target)], close_fds=True)
        return {"ok": True, "path": target}
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": f"打不开资源管理器：{exc}"}


def open_file_native(path: str) -> dict:
    """用系统默认程序打开文件，等价于在资源管理器里双击它。

    和 reveal_in_explorer 的区别：那个只是"在文件夹里选中"，这个是真正把文件
    交给相册/播放器/WPS。网页上那个圆形按钮对电脑本机来说本来就该是「打开」——
    文件早就落在这台电脑的磁盘上了，再让人"下载"一次是多余的。

    安全：路径同样必须先过 _reveal_allowed_roots() 白名单，
    否则网页上一个构造好的 path 就能让本机运行任意文件。
    """
    target = os.path.realpath(path)
    if not os.path.isfile(target):
        return {"ok": False, "error": "文件已不在原位置（可能被移动或删除）"}
    try:
        if sys.platform.startswith("win"):
            # os.startfile 就是 shell 的 "open" 动作，不会弹控制台窗口
            os.startfile(target)  # noqa: S606  (Windows only API)
        elif sys.platform == "darwin":
            subprocess.Popen(["open", target], close_fds=True)
        else:
            subprocess.Popen(["xdg-open", target], close_fds=True)
        return {"ok": True, "path": target}
    except OSError as exc:
        return {"ok": False, "error": f"没有能打开它的程序：{exc}"}


# winotify 每次 show() 内部会起一个隐藏 PowerShell，连发多条会很吵。
# 这里 3 秒合并成一条通知。
_notify_lock = threading.Lock()
_notify_q: list = []
_notify_timer: threading.Timer | None = None

# 网页在前台时收到文件不弹系统通知：人就盯着列表，右下角再飘一条"收到文件"
# 是纯重复播报。前端每 5 秒 POST /api/focus 一次（document.hasFocus() 且未最小化），
# 超过 FOCUS_TTL 没上报就当已经切走 —— 否则页面崩了/关掉后通知会永远哑掉。
_focus_lock = threading.Lock()
_page_focus = {"focused": False, "ts": 0.0}
FOCUS_TTL = 15.0


def mark_page_focus(focused: bool) -> None:
    with _focus_lock:
        _page_focus["focused"] = bool(focused)
        _page_focus["ts"] = time.time()


def page_in_foreground() -> bool:
    with _focus_lock:
        if not _page_focus["focused"]:
            return False
        return (time.time() - _page_focus["ts"]) < FOCUS_TTL


def own_window_active() -> bool:
    """独立窗口（pywebview）是不是当前前台窗口。

    document.hasFocus() 在 WebView2 里偶发返回 false（点过标题栏、窗口刚激活时），
    光靠前端心跳会把"人明明看着"误判成"不在前台"，通知又冒出来。
    这里在系统层面兜一层：前台窗口的进程 id 就是自己 → 用户正看着这个窗口。
    """
    if os.name != "nt":
        return False
    try:
        user32 = ctypes.windll.user32
        hwnd = user32.GetForegroundWindow()
        if not hwnd:
            return False
        pid = ctypes.c_ulong(0)
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        return pid.value == os.getpid()
    except Exception:  # noqa: BLE001
        return False


def user_watching() -> bool:
    """人是不是正盯着这个页面 —— 两条通道任一成立就算（浏览器模式靠心跳，独立窗口靠进程 id）。"""
    return page_in_foreground() or own_window_active()


def notify_received(name: str, size: int, saved: dict) -> None:
    with _notify_lock:
        _notify_q.append((name, size, saved))
        global _notify_timer
        if _notify_timer is None:
            _notify_timer = threading.Timer(3.0, _fire_notify)
            _notify_timer.daemon = True
            _notify_timer.start()


def _fire_notify() -> None:
    global _notify_timer
    with _notify_lock:
        items = list(_notify_q)
        _notify_q.clear()
        _notify_timer = None
    if not items:
        return
    ok_items = [x for x in items if x[2].get("ok")]
    fail_items = [x for x in items if not x[2].get("ok")]
    if ok_items:
        first_name, first_size, first_saved = ok_items[0]
        n = len(ok_items)
        if n == 1:
            msg = f"「{first_name}」({_human_size(first_size)})，已存到 {first_saved.get('path', '')}"
        else:
            msg = f"收到 {n} 个文件（如「{first_name}」），已存到 {user_save_dir()}"
        if user_watching():
            # 页面就在眼前：文件已经落进列表了，别再弹一条把用户的注意力拽走
            print(f"[通知·前台静默] {msg}", flush=True)
        else:
            _notify_show("简传 Lite · 收到文件", msg)
    if fail_items:
        fn, _fs, fsv = fail_items[0]
        _notify_show("简传 Lite · 保存失败",
                     f"「{fn}」传输到了电脑但没能存到下载目录：{fsv.get('error', '')}")


def _notify_show(title: str, msg: str) -> None:
    if _WinNotification is not None:
        try:
            n = _WinNotification(
                app_id="简传Lite",
                title=title,
                msg=msg,
            )
            n.show()
            return
        except Exception:  # noqa: BLE001
            pass
    # 降级：控制台打印（打包成 exe 时 console=True，仍然能看到）
    print(f"[通知] {title} | {msg}", flush=True)


def _human_size(n: int) -> str:
    n = float(n)
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.1f} {unit}" if unit != "B" else f"{int(n)} B"
        n /= 1024
    return f"{n:.1f} GB"


# Python 的 mimetypes 对音视频格式覆盖不全（尤其 flac/mkv/m4a），补齐一下，
# 否则浏览器拿到 application/octet-stream 就放不出来。
EXTRA_MIME = {
    ".mp4": "video/mp4", ".m4v": "video/mp4", ".webm": "video/webm",
    ".mkv": "video/x-matroska", ".mov": "video/quicktime", ".avi": "video/x-msvideo",
    ".ogv": "video/ogg", ".3gp": "video/3gpp", ".ts": "video/mp2t",
    ".mp3": "audio/mpeg", ".m4a": "audio/mp4", ".aac": "audio/aac",
    ".wav": "audio/wav", ".flac": "audio/flac", ".ogg": "audio/ogg",
    ".opus": "audio/ogg", ".weba": "audio/webm", ".wma": "audio/x-ms-wma",
    ".amr": "audio/amr", ".ape": "audio/x-ape",
}


def guess_mime(name: str, given: str = "") -> str:
    if given and given != "application/octet-stream":
        return given
    ext = os.path.splitext(name)[1].lower()
    if ext in EXTRA_MIME:
        return EXTRA_MIME[ext]
    return mimetypes.guess_type(name)[0] or "application/octet-stream"


# ---------------------------------------------------------------- 外挂字幕
# 手机 App 的原生播放器是 libVLC，srt/ass 直接能喂；电脑端网页是 <video>，
# 只认 WebVTT。所以服务端干两件事：报「跟这个视频同名的字幕有哪几个」，
# 以及把 srt/ass 现场转成 VTT 再吐出去。转换只发生在第一次请求，
# 字幕文件都是几十 KB，不做缓存也不会拖慢什么。
SUB_EXTS = (".srt", ".ass", ".ssa", ".vtt", ".sub")
SUB_MAX_BYTES = 16 * 1024 * 1024


def sub_rank(name: str) -> int:
    """中文字幕排前面。排在第一位的那条会被前端默认选中，所以顺序就是优先级。"""
    n = name.lower()
    if "chs" in n or "zh-cn" in n or "zh_cn" in n or "简" in n or "sc." in n:
        return 0
    if "cht" in n or "zh-tw" in n or "zh_tw" in n or "繁" in n or "tc." in n:
        return 1
    if "chinese" in n or ".zh" in n or "_zh" in n:
        return 2
    if "eng" in n or ".en." in n or "_en." in n:
        return 9
    return 5


def sub_label(name: str):
    """(srclang, 显示名)。<track> 的语言标签只给浏览器看，显示名给人看。"""
    r = sub_rank(name)
    if r == 0:
        return ("zh-CN", "简体中文")
    if r == 1:
        return ("zh-TW", "繁體中文")
    if r == 2:
        return ("zh", "中文")
    if r == 9:
        return ("en", "English")
    ext = os.path.splitext(name)[1].lstrip(".").upper()
    return ("zh", ext or "字幕")


def sub_allowed(path: str) -> bool:
    """?path= 只认「落盘目录」和 data/files 里的文件。

    手机 App 的原生播放器是按绝对路径来问字幕的，所以必须留这个入口；
    但不能让它变成「随便传个路径就能读磁盘」—— 所以白名单卡死在这两个根目录。
    """
    try:
        rp = os.path.realpath(path)
    except OSError:
        return False
    for root in (user_save_dir(), FILE_DIR):
        try:
            rd = os.path.realpath(root)
        except OSError:
            continue
        if rp == rd or rp.startswith(rd + os.sep):
            return True
    return False


def subs_for(video_path: str) -> list:
    """同目录下与视频同名（同前缀）的字幕文件，中文优先。"""
    if not video_path:
        return []
    folder = os.path.dirname(video_path)
    base = os.path.splitext(os.path.basename(video_path))[0].lower()
    if not folder or not base:
        return []
    try:
        names = os.listdir(folder)
    except OSError:
        return []
    hits = []
    for n in names:
        low = n.lower()
        if not low.endswith(SUB_EXTS):
            continue
        # 只认「视频名.xxx.srt」，避免把别的片子的字幕挂上来
        if not low.startswith(base + "."):
            continue
        try:
            if not os.path.isfile(os.path.join(folder, n)):
                continue
            if os.path.getsize(os.path.join(folder, n)) <= 0:
                continue
        except OSError:
            continue
        hits.append(n)
    hits.sort(key=lambda n: (sub_rank(n), n.lower()))
    return hits


def media_path_of(msg: dict) -> str:
    """消息里的文件在磁盘上的真实路径。

    优先用落到 Downloads\\简传Lite 的那份：只有它保留了原始文件名，外挂字幕
    才可能跟它同名放在一起。data/files 里是 uuid 名，永远不可能匹配到字幕。

    先读消息自带的 saved.path（写进 messages.json，重启后还在），
    再退回 _received 内存表（老数据没有 saved 字段时兜底）。
    """
    f = (msg or {}).get("file") or {}
    saved = (msg or {}).get("saved") or {}
    if saved.get("ok") and saved.get("path") and os.path.isfile(saved["path"]):
        return saved["path"]
    p = saved_path_of(f.get("stored") or "")
    if p and os.path.isfile(p):
        return p
    stored = f.get("stored") or ""
    if stored:
        q = os.path.join(FILE_DIR, stored)
        if os.path.isfile(q):
            return q
    return ""


def _decode_sub(raw: bytes) -> str:
    """字幕编码五花八门：旧资源多是 GBK，也有带 BOM 的 UTF-16。
    宁可乱码也别报错 —— 乱码至少还能看出是字幕，报错就整条不显示了。"""
    if raw[:2] in (b"\xff\xfe", b"\xfe\xff"):
        try:
            return raw.decode("utf-16")
        except UnicodeDecodeError:
            pass
    for enc in ("utf-8-sig", "gb18030", "big5"):
        try:
            return raw.decode(enc)
        except (UnicodeDecodeError, LookupError):
            continue
    return raw.decode("utf-8", "replace")


def _vtt_ts(sec: float) -> str:
    sec = max(0.0, float(sec))
    h = int(sec // 3600)
    m = int(sec % 3600 // 60)
    s = sec % 60
    return "%02d:%02d:%06.3f" % (h, m, s)


def _parse_srt_ts(text: str) -> float:
    t = (text or "").strip().replace(",", ".")
    m = re.match(r"(?:(\d+):)?(\d{1,2}):(\d{1,2}(?:\.\d+)?)", t)
    if not m:
        return 0.0
    return int(m.group(1) or 0) * 3600 + int(m.group(2)) * 60 + float(m.group(3))


def _parse_ass_ts(text: str) -> float:
    m = re.match(r"\s*(\d+):(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?", text or "")
    if not m:
        return 0.0
    frac = m.group(4) or "0"
    return (int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3))
            + int(frac) / (10 ** len(frac)))


def srt_to_vtt(text: str) -> str:
    out = ["WEBVTT", ""]
    text = text.replace("\r\n", "\n").replace("\r", "\n").lstrip("\ufeff")
    for block in re.split(r"\n{2,}", text):
        lines = [l for l in block.split("\n") if l.strip()]
        if not lines:
            continue
        # SRT 的序号行在 VTT 里不需要
        if re.fullmatch(r"\d+", lines[0].strip()):
            lines = lines[1:]
        if not lines:
            continue
        arrow = None
        for i, l in enumerate(lines[:3]):
            if "-->" in l:
                arrow = i
                break
        if arrow is None:
            continue
        parts = lines[arrow].split("-->")
        if len(parts) < 2:
            continue
        start = _parse_srt_ts(parts[0])
        end = _parse_srt_ts(re.split(r"\s+", parts[1].strip())[0])
        body = "\n".join(re.sub(r"<[^>]+>", "", l) for l in lines[arrow + 1:]).strip()
        if not body:
            continue
        out.extend([_vtt_ts(start) + " --> " + _vtt_ts(end), body, ""])
    return "\n".join(out)


def ass_to_vtt(text: str) -> str:
    """ASS/SSA 只取时间和文本：样式一概丢掉，能看懂台词就够了。"""
    out = ["WEBVTT", ""]
    fields = None
    in_events = False
    for raw in text.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        line = raw.strip()
        if not line:
            continue
        low = line.lower()
        if low.startswith("[") and low.endswith("]"):
            in_events = low == "[events]"
            continue
        if not in_events:
            continue
        if low.startswith("format:"):
            fields = [x.strip().lower() for x in line.split(":", 1)[1].split(",")]
            continue
        if not low.startswith("dialogue:"):
            continue
        payload = line.split(":", 1)[1]
        if fields:
            parts = payload.split(",", len(fields) - 1)
            try:
                si, ei, ti = fields.index("start"), fields.index("end"), fields.index("text")
            except ValueError:
                continue
        else:
            parts = payload.split(",", 9)
            si, ei, ti = 1, 2, 9
        if len(parts) <= max(si, ei, ti):
            continue
        start = _parse_ass_ts(parts[si])
        end = _parse_ass_ts(parts[ei])
        body = re.sub(r"\{[^}]*\}", "", parts[ti])
        body = body.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
        body = re.sub(r"<[^>]+>", "", body).strip()
        if not body:
            continue
        out.extend([_vtt_ts(start) + " --> " + _vtt_ts(end), body, ""])
    return "\n".join(out)


def running_instance(port: int):
    """检查这个端口上跑的是不是已经是本程序（避免开出两个实例抢同一份数据）。"""
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/info", timeout=0.8) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        if data.get("app") == APP_NAME:
            return data
    except Exception:  # noqa: BLE001
        return None
    return None


# ---------------------------------------------------------------- 请求处理
class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = f"JianChuanLite/{PROTO_VERSION}"

    # 默认会把每条请求打到 stderr，太吵，关掉
    def log_message(self, fmt, *args):  # noqa: A003
        return

    # ------------------------------ 基础响应
    def _send(self, code: int, body=b"", ctype: str = "application/json; charset=utf-8", extra=None):
        if isinstance(body, str):
            body = body.encode("utf-8")
        try:
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            if extra:
                for k, v in extra.items():
                    self.send_header(k, v)
            self.end_headers()
            if self.command != "HEAD" and body:
                self.wfile.write(body)
        except ConnectionError:
            self.close_connection = True

    def _json(self, obj, code: int = 200):
        body = json.dumps(obj, ensure_ascii=False)
        self._send(code, body, "application/json; charset=utf-8")

    def _qr_svg(self, text: str, size: int = 240):
        """把一段文字渲染成二维码 SVG。

        原本网页里的二维码是调 api.qrserver.com 在线生成的，
        局域网没外网时会直接白屏。这里在本地生成，离线可用。
        """
        try:
            import qrcode
            from qrcode.image.svg import SvgPathImage
        except Exception:  # noqa: BLE001
            return self._send(501, "本机缺少 qrcode 库，无法生成二维码", "text/plain; charset=utf-8")
        try:
            qr = qrcode.QRCode(border=2, box_size=10,
                               error_correction=qrcode.constants.ERROR_CORRECT_M)
            qr.add_data(text)
            qr.make(fit=True)
            svg = qr.make_image(image_factory=SvgPathImage).to_string()
            if isinstance(svg, bytes):
                svg = svg.decode("utf-8")
            # 默认尺寸是物理毫米（如 37mm），而且模块数随内容长度变化，
            # 所以不能写死替换 "37mm"，要用正则把任意 mm 尺寸换成像素。
            svg = re.sub(r'(width|height)="[\d.]+mm"',
                         lambda m: f'{m.group(1)}="{size}"', svg)
            # 补一个白底：页面是深色玻璃风，SVG 默认透明背景，
            # 黑色码点贴在深色面板上会糊成一片，手机扫不出来。
            vb = re.search(r'viewBox="([\d.\s-]+)"', svg)
            if vb:
                parts = vb.group(1).split()
                w = parts[2] if len(parts) >= 4 else "100%"
                h = parts[3] if len(parts) >= 4 else "100%"
                svg = re.sub(r'(<svg[^>]*>)',
                             rf'\1<rect x="0" y="0" width="{w}" height="{h}" fill="#ffffff"/>',
                             svg, count=1)
            svg = svg.replace("<svg ", '<svg preserveAspectRatio="xMidYMid meet" ', 1)
            self._send(200, svg, "image/svg+xml; charset=utf-8")
        except Exception as exc:  # noqa: BLE001
            self._send(500, f"二维码生成失败: {exc}", "text/plain; charset=utf-8")

    # ------------------------------ 鉴权
    def _token(self):
        cookie = self.headers.get("Cookie", "")
        for part in cookie.split(";"):
            part = part.strip()
            if part.startswith("jc_token="):
                return part[len("jc_token="):]
        return ""

    def _from_tunnel(self):
        """请求是不是从 cloudflared 公网隧道转发进来的。

        cloudflared 转发时会带上 CF-Connecting-IP / CDN-Loop / Cf-Ray 这几个头，
        本机和局域网直连都没有。用这个区分「公网访客」和「局域网自己人」。
        """
        h = self.headers
        return bool(h.get("CF-Connecting-IP") or h.get("CDN-Loop") or h.get("Cf-Ray"))

    def _needs_auth(self):
        """只有公网隧道进来的请求才要求密码；本机、局域网直连一律免密。"""
        return bool(PASSWORD) and self._from_tunnel()

    def _authed(self):
        return check_session(self._token())

    def _post_auth(self):
        raw = self._read_body(65536)
        try:
            data = json.loads(raw.decode("utf-8") or "{}")
        except Exception:  # noqa: BLE001
            data = {}
        pwd = data.get("password") or ""
        if PASSWORD and hmac.compare_digest(pwd.encode("utf-8", "ignore"), PASSWORD.encode("utf-8", "ignore")):
            tok = new_session()
            body = b'{"ok":true}'
            try:
                self.send_response(200)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Set-Cookie",
                                 f"jc_token={tok}; Path=/; HttpOnly; Max-Age={SESSION_TTL}; SameSite=Lax")
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(body)
            except ConnectionError:
                self.close_connection = True
            return
        time.sleep(0.7)   # 密码错慢半拍，拖慢穷举
        return self._json({"error": "密码错误"}, 401)

    def _settings_get(self):
        cfg = read_config()
        return {
            "password": cfg.get("password", ""),
            "self_server": cfg.get("self_server", ""),
            "tunnel": tunnel_status(),
        }

    def _post_settings(self):
        raw = self._read_body(65536)
        try:
            data = json.loads(raw.decode("utf-8") or "{}")
        except Exception:  # noqa: BLE001
            return self._json({"error": "格式错误"}, 400)
        cfg = read_config()
        if data.get("password"):
            cfg["password"] = str(data["password"]).strip()
        if "self_server" in data:
            cfg["self_server"] = str(data["self_server"]).strip()
        write_config(cfg)
        global PASSWORD
        PASSWORD = cfg.get("password", "")
        return self._json({"ok": True, "password": PASSWORD})

    def _static(self, rel: str):
        rel = rel.split("?")[0].lstrip("/")
        path = os.path.normpath(os.path.join(STATIC_DIR, rel))
        if not path.startswith(STATIC_DIR) or not os.path.isfile(path):
            return self._send(404, "Not Found", "text/plain; charset=utf-8")
        ctype = mimetypes.guess_type(path)[0] or "application/octet-stream"
        if ctype.startswith("text/") or ctype in ("application/javascript", "application/json"):
            ctype += "; charset=utf-8"
        try:
            with open(path, "rb") as fh:
                data = fh.read()
        except OSError:
            return self._send(500, "Read Error", "text/plain; charset=utf-8")
        self._send(200, data, ctype)

    # ------------------------------ GET
    def do_HEAD(self):
        """有些下载工具会先用 HEAD 预检文件，得回答得像样一点。"""
        parsed = urlparse(self.path)
        path = parsed.path
        if path.startswith('/f/') or path.startswith('/p/'):
            mid = path[3:]
            try:
                midv = int(mid)
            except ValueError:
                return self._send(404, 'not found', 'text/plain; charset=utf-8')
            msg = find_message(midv)
            f = (msg or {}).get('file') or {}
            stored = f.get('stored') or ''
            full = os.path.join(FILE_DIR, stored) if stored else ''
            if not stored or not os.path.isfile(full):
                return self._send(404, 'not found', 'text/plain; charset=utf-8')
            fname = f.get('name') or 'file'
            mime = guess_mime(fname, f.get('mime') or '')
            try:
                self.send_response(200)
                self.send_header('Content-Type', mime)
                self.send_header('Content-Length', str(os.path.getsize(full)))
                self.send_header('Accept-Ranges', 'bytes')
                self.end_headers()
            except ConnectionError:
                self.close_connection = True
            return
        code = 200 if path in ('/', '/index.html') else 404
        if path.startswith('/static/'):
            # 静态资源也要正确响应 HEAD：浏览器/下载工具下载 APK 前会先预检，
            # 这里漏掉会导致手机端「下载失败」。
            rel = path[len('/static/'):].split('?')[0]
            full = os.path.normpath(os.path.join(STATIC_DIR, rel))
            if full.startswith(STATIC_DIR) and os.path.isfile(full):
                code = 200
        try:
            self.send_response(code)
            if code == 200 and path.startswith('/static/'):
                rel = path[len('/static/'):].split('?')[0]
                full = os.path.normpath(os.path.join(STATIC_DIR, rel))
                self.send_header('Content-Type', mimetypes.guess_type(full)[0] or 'application/octet-stream')
                self.send_header('Content-Length', str(os.path.getsize(full)))
            else:
                self.send_header('Content-Length', '0')
            self.end_headers()
        except ConnectionError:
            self.close_connection = True

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        qs = parse_qs(parsed.query)

        if path in ("/", "/index.html"):
            return self._static("index.html")
        if path == "/favicon.ico":
            return self._send(204, b"", "text/plain; charset=utf-8")
        if path.startswith("/static/"):
            rel = path[len("/static/"):].split("?")[0]
            # 稳定别名：前端兜底链接、二维码可以永远写 jianchuan-latest.apk，
            # 出新版 APK 不用改任何代码（真名带版本号，只用于展示和缓存区分）
            if rel == "jianchuan-latest.apk":
                name, _ = latest_apk()
                if not name:
                    return self._send(404, "还没有安卓包", "text/plain; charset=utf-8")
                return self._static(name)
            return self._static(rel)
        if path == "/api/qr":
            # 本地生成二维码（不依赖外网）
            text = (qs.get("text", [""])[0] or "").strip()
            if not text:
                return self._send(400, "缺少 text 参数", "text/plain; charset=utf-8")
            try:
                size = max(80, min(600, int(qs.get("size", ["240"])[0])))
            except ValueError:
                size = 240
            return self._qr_svg(text, size)
        if path == "/api/apk":
            # 安卓版安装包信息（下载地址 + 版本）
            # 版本号从文件名里解析，别再硬编码 —— 之前写死成 v1.9.1，
            # 换了几版 APK 之后卡片上显示的还是老版本号。
            # 再进一步：直接扫 static 目录挑版本号最高的那个。以前每出一版 APK
            # 都要改这里、再重打 exe，忘了改网页就还发旧包（已经踩过两次）。
            apk_name, apk_path = latest_apk()
            exists = bool(apk_path) and os.path.isfile(apk_path)
            size = os.path.getsize(apk_path) if exists else 0
            m = re.search(r"v(\d+(?:\.\d+)*)", apk_name or "")
            version = m.group(1) if m else ""
            return self._json({
                "ok": True,
                "name": f"简传Lite v{version}" if version else "简传Lite 安卓版",
                "version": version,
                "file": apk_name,
                "size": size,
                "url": f"http://{LAN_IP}:{PORT}/static/{apk_name}",
            })
        # 本机管理接口：公网访客一律 403（防外人看密码、关隧道）
        if path in ("/api/tunnel", "/api/settings"):
            if self._from_tunnel():
                return self._json({"error": "公网访客无权操作"}, 403)
        # 下面这些是数据接口 / 文件：只有公网隧道进来的才要求登录
        if path in ("/api/info", "/api/messages") or path.startswith("/f/") or path.startswith("/p/"):
            if self._needs_auth() and not self._authed():
                return self._json({"error": "需要登录", "need_auth": True}, 401)
        if path == "/api/info":
            return self._json(self._info())
        if path == "/api/messages":
            return self._json(self._messages_payload(qs))
        if path == "/api/tunnel":
            return self._json(tunnel_status())
        if path == "/api/clipboard":
            return self._json(clip_status())
        if path == "/api/settings":
            return self._json(self._settings_get())
        # 外挂字幕：索引 + 转 VTT。和文件接口一样，只有隧道进来的才要登录
        if path in ("/api/subs", "/api/sub"):
            if self._needs_auth() and not self._authed():
                return self._json({"error": "需要登录", "need_auth": True}, 401)
            if path == "/api/subs":
                return self._json(self._subs_payload(qs))
            return self._serve_sub(qs)
        if path.startswith("/f/") or path.startswith("/p/"):
            mid = path[3:]
            return self._serve_file(mid, inline=path.startswith("/p/"))
        self._send(404, "404 Not Found", "text/plain; charset=utf-8")

    # ------------------------------ POST
    def do_POST(self):
        parsed = urlparse(self.path)
        path, qs = parsed.path, parse_qs(parsed.query)
        if path == "/api/auth":
            return self._post_auth()
        # 本机管理接口：公网访客一律 403（防外人关隧道、改密码）
        if path in ("/api/tunnel/start", "/api/tunnel/stop", "/api/settings", "/api/clipboard", "/api/reveal", "/api/open"):
            if self._from_tunnel():
                return self._json({"error": "公网访客无权操作"}, 403)
        # 「打开文件」比「在文件夹里选中」更进一步 —— 它会真的启动程序，
        # 所以只认本机页面。局域网里别人的浏览器点「打开」应该开他自己设备上的文件，
        # 不该遥控这台电脑跑程序（那种请求一律走前端的上传/下载分支，不会打到这里）。
        if path == "/api/open" and self.client_address[0] not in ("127.0.0.1", "::1"):
            return self._json({"error": "只允许本机"}, 403)
        # 标题栏深浅只给本机页面用（exe 窗口里切主题时调），局域网/公网都别碰
        if path == "/api/titlebar":
            if self.client_address[0] not in ("127.0.0.1", "::1"):
                return self._json({"error": "只允许本机"}, 403)
            dark = str((qs.get("dark") or ["1"])[0]).lower() in ("1", "true", "yes")
            return self._json({"ok": apply_titlebar_dark(dark)})
        # 页面焦点心跳：只认电脑本机那个页面。
        # 注意不能只看 client_address —— cloudflared 转发进来也是 127.0.0.1，
        # 公网访客（比如人在外面用手机开网页）的心跳会一直把状态刷成"前台"，
        # 电脑端就永远不弹通知了。所以要用 _from_tunnel() 排除。
        if path == "/api/focus":
            if self._from_tunnel() or self.client_address[0] not in ("127.0.0.1", "::1"):
                return self._json({"ok": True, "ignored": True})
            raw = self._read_body(4096)
            try:
                data = json.loads(raw.decode("utf-8") or "{}")
            except Exception:  # noqa: BLE001
                data = {}
            mark_page_focus(bool(data.get("focused")))
            return self._json({"ok": True})
        if self._needs_auth() and not self._authed():
            return self._json({"error": "需要登录", "need_auth": True}, 401)
        if path == "/api/tunnel/start":
            return self._json(tunnel_start())
        if path == "/api/tunnel/stop":
            return self._json(tunnel_stop())
        if path == "/api/settings":
            return self._post_settings()
        if path == "/api/clipboard":
            return self._post_clipboard()
        if path == "/api/clip":
            return self._post_clip_from_phone()
        if path == "/api/wechat_notify":
            return self._post_wechat_notify()
        if path == "/api/reveal":
            return self._post_reveal()
        if path == "/api/open":
            return self._post_open()
        if path == "/api/send":
            return self._post_send()
        if path == "/api/upload":
            return self._post_upload(qs)
        if path == "/api/delete":
            return self._post_delete()
        if path == "/api/clear":
            clear_all()
            return self._json({"ok": True})
        self._send(404, "404 Not Found", "text/plain; charset=utf-8")

    # ------------------------------ 业务
    def _file_target_from_body(self):
        """把 body 里的 path / stored 解析成一个确实存在、且在白名单里的本地文件。

        返回 (路径, 错误信息, HTTP 状态码)，err 为空表示成功。
        reveal 和 open 共用这套解析 —— 两个接口找的是同一个文件，逻辑不能分叉。
        """
        raw = self._read_body(64 * 1024)
        data = {}
        if raw:
            try:
                data = json.loads(raw.decode("utf-8") or "{}")
            except Exception:  # noqa: BLE001
                data = {}
        stored = (data.get("stored") or "").strip()
        path = (data.get("path") or "").strip()
        if path and not os.path.isfile(path):
            path = ""          # 落盘路径被用户挪走/删了，退回按 stored 找
        if not path:
            path = saved_path_of(stored)
        if not path and stored and os.path.basename(stored) == stored:
            # 兼容旧消息（没有 saved 字段）和落盘失败的情况：退回 data/files
            path = os.path.join(FILE_DIR, stored)
        if not path:
            return "", "找不到这个文件了", 404
        # 白名单校验：网页能传任意 path 进来，不卡住就等于开了个任意文件执行/浏览的口子
        real = os.path.normcase(os.path.realpath(path))
        roots = [os.path.normcase(r) for r in _reveal_allowed_roots()]
        if not any(real.startswith(r + os.sep) for r in roots):
            return "", "不允许的路径", 403
        return path, "", 0

    def _post_reveal(self):
        """在资源管理器里定位文件。body: {"path": "已落盘路径"} 或 {"stored": "物理文件名"}"""
        path, err, code = self._file_target_from_body()
        if err:
            return self._json({"ok": False, "error": err}, code)
        return self._json(reveal_in_explorer(path))

    def _post_open(self):
        """用系统默认程序打开文件。body 同 /api/reveal。"""
        path, err, code = self._file_target_from_body()
        if err:
            return self._json({"ok": False, "error": err}, code)
        return self._json(open_file_native(path))

    def _post_clipboard(self):
        """开关电脑端剪贴板同步。body: {"action": "start" | "stop" | "toggle"}"""
        if _clip_bridge is None:
            return self._json({"error": "剪贴板模块没加载（缺 bleak 或 pyperclip）"}, 501)
        raw = self._read_body(64 * 1024)
        data = {}
        if raw:
            try:
                data = json.loads(raw.decode("utf-8") or "{}")
            except Exception:  # noqa: BLE001
                data = {}
        action = (data.get("action") or "toggle").lower()
        if action == "sync_now":
            pushed = _clip_bridge.force_sync()
            st = clip_status()
            st["pushed"] = pushed
            return self._json(st)
        running = bool(_clip_bridge.status().get("enabled"))
        if action == "start" or (action == "toggle" and not running):
            _clip_bridge.start()
        elif action == "stop" or (action == "toggle" and running):
            _clip_bridge.stop()
        return self._json(clip_status())

    def _post_clip_from_phone(self):
        """手机 → 电脑：手机把新复制的文本 POST 过来，直接写进本机剪贴板。

        这里不能用 JSON 包装，手机端是裸 UTF-8 文本 body —— 剪贴板内容里
        什么字符都可能有，JSON 转义反而容易出岔子。
        """
        if _clip_bridge is None:
            return self._json({"ok": False, "error": "剪贴板模块没加载"}, 501)
        if not _clip_bridge.status().get("enabled"):
            # 电脑端没开同步，静默忽略，别污染用户剪贴板
            return self._json({"ok": False, "reason": "off"})
        raw = self._read_body(256 * 1024)
        if not raw:
            return self._json({"ok": False, "error": "内容为空"}, 400)
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            return self._json({"ok": False, "error": "不是合法的 UTF-8"}, 400)
        if not text.strip():
            return self._json({"ok": False, "error": "内容为空"}, 400)
        _clip_bridge.on_remote_text(text, via="局域网")
        return self._json({"ok": True, "len": len(text)})

    def _post_wechat_notify(self):
        """手机 → 电脑：手机收到微信通知，POST 过来，电脑弹桌面提醒。

        隐私边界（与手机端一致）：只走局域网。隧道（公网）来的请求一律丢弃，
        微信消息内容绝不经过任何云中转。
        """
        if self._from_tunnel():
            return self._json({"error": "只走局域网"}, 403)
        raw = self._read_body(64 * 1024)
        if not raw:
            return self._json({"error": "内容为空"}, 400)
        try:
            data = json.loads(raw.decode("utf-8"))
        except Exception:  # noqa: BLE001
            return self._json({"error": "格式错误"}, 400)
        title = str(data.get("title", "")).strip()
        text = str(data.get("text", "")).strip()
        if not title and not text:
            return self._json({"error": "内容为空"}, 400)
        # 弹桌面通知（复用收文件的 winotify 通道，进操作中心可回看）
        body = text if text else title
        _notify_show("微信 · " + (title if title else "新消息"), body)
        return self._json({"ok": True})

    def _info(self):
        port = self.server.server_address[1]
        ips = all_ips()          # 每次请求实时探测，换网络后立刻能反映出来
        return {
            "app": APP_NAME,
            "version": VERSION,
            "ip": ips[0],
            "port": port,
            "url": f"http://{ips[0]}:{port}",
            "ips": ips,
            "urls": [f"http://{x}:{port}" for x in ips],
            "host": socket.gethostname(),
            "tunnel": self._from_tunnel(),   # 前端据此决定显不显示开关/设置
            # 「这个页面是不是跑在这台电脑上」。前端据此决定文件气泡右侧是「打开」还是「下载」：
            # 只有本机页面点打开才有意义（文件就躺在这台电脑磁盘上）；
            # 局域网/公网别人的浏览器点打开，得开他自己设备上的文件，所以那边保持下载。
            "local": (
                self.client_address[0] in ("127.0.0.1", "::1")
                and not self._from_tunnel()
            ),
            # 密码只给本机/局域网看。以前只打在控制台，一旦 exe 隐藏了黑框，
            # 用户就彻底找不到自己的公网密码了。隧道进来的访客一律拿空串。
            "password": "" if self._from_tunnel() else PASSWORD,
            "online": online_list(),
            "clipboard": clip_status(),
        }

    def _messages_payload(self, qs):
        try:
            after = int((qs.get("after") or ["0"])[0] or 0)
        except ValueError:
            after = 0
        cid = (qs.get("client") or [""])[0]
        touch_client(cid, self.client_address[0], self.headers.get("User-Agent", ""))
        with _lock:
            total = len(_messages)
            if after <= 0:
                items = _messages[-80:]
            else:
                items = [m for m in _messages if m.get("id", 0) > after]
            recv = list(_received[-20:])
        # 剪贴板待发内容只喂给手机 App 的 WebView（UA 带 JCApp/）：
        # 电脑浏览器也在轮询同一个接口，不区分的话会把内容抢走、手机就收不到了。
        clips = []
        _ua = self.headers.get("User-Agent", "")
        if _clip_bridge is not None and ("JCApp/" in _ua or "简传LiteApp" in _ua):
            clips = _clip_bridge.drain_outbox()
        return {
            "messages": items,
            "online": online_list(),
            "total": total,
            "received": recv,
            "clips": clips,
        }

    def _read_body(self, limit: int = 8 * 1024 * 1024) -> bytes:
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if length <= 0:
            return b""
        return self.rfile.read(min(length, limit))

    def _post_send(self):
        raw = self._read_body()
        try:
            data = json.loads(raw.decode("utf-8") or "{}")
        except Exception:  # noqa: BLE001
            return self._json({"error": "内容格式错误"}, 400)
        text = (data.get("text") or "").strip()
        if not text:
            return self._json({"error": "内容为空"}, 400)
        if len(text) > 20000:
            return self._json({"error": "文本太长（上限 2 万字）"}, 400)
        cid = data.get("client") or ""
        agent = self.headers.get("User-Agent", "")
        dedupe = (data.get("dedupe") or "")[:120]
        if dedupe:
            hit = dedupe_lookup(dedupe)
            if hit:
                return self._json({"message": hit, "duplicate": True})
        touch_client(cid, self.client_address[0], agent)
        msg = add_message({
            "type": "text",
            "text": text,
            "ip": self.client_address[0],
            "client": cid,
            "device": device_name(agent),
        })
        dedupe_store(dedupe, msg)
        self._json({"message": msg})

    def _post_upload(self, qs):
        name = safe_filename((qs.get("name") or ["file"])[0])
        mime = guess_mime(name, (qs.get("mime") or [""])[0])
        cid = (qs.get("client") or [""])[0]
        agent = self.headers.get("User-Agent", "")
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if length <= 0:
            record_received(name, 0, {"ok": False, "error": "空文件"})
            return self._json({"error": "空文件"}, 400)
        if length > MAX_UPLOAD:
            self.close_connection = True
            record_received(name, length, {"ok": False, "error": "超过 4GB 上限"})
            return self._json({"error": "文件超过 4GB 上限"}, 413)

        dedupe = (qs.get("dedupe") or [""])[0][:120]
        if dedupe:
            hit = dedupe_lookup(dedupe)
            if hit:
                # 已经存过了：把这次的上传数据读掉丢弃，保证连接正常，然后返回原消息
                left = length
                while left > 0:
                    chunk = self.rfile.read(min(262144, left))
                    if not chunk:
                        break
                    left -= len(chunk)
                return self._json({"message": hit, "duplicate": True})

        ext = os.path.splitext(name)[1][:16]
        stored = uuid.uuid4().hex + ext
        tmp = os.path.join(FILE_DIR, stored + ".part")
        left = length
        ok = True
        try:
            with open(tmp, "wb") as fh:
                while left > 0:
                    chunk = self.rfile.read(min(262144, left))
                    if not chunk:
                        ok = False
                        break
                    fh.write(chunk)
                    left -= len(chunk)
        except ConnectionError:
            ok = False

        if not ok or left > 0:
            try:
                os.remove(tmp)
            except OSError:
                pass
            self.close_connection = True
            record_received(name, length, {"ok": False, "error": "传输中断，请重试"})
            return self._json({"error": "传输中断，请重试"}, 400)

        os.replace(tmp, os.path.join(FILE_DIR, stored))
        # 自动落到用户下载目录（Downloads\简传Lite），并弹桌面通知
        saved = save_to_user_dir(os.path.join(FILE_DIR, stored), name)
        record_received(name, length, saved, stored=stored)
        notify_received(name, length, saved)
        touch_client(cid, self.client_address[0], agent)
        msg = add_message({
            "type": "file",
            "file": {"name": name, "size": length, "mime": mime, "stored": stored},
            "saved": saved,
            "ip": self.client_address[0],
            "client": cid,
            "device": device_name(agent),
        })
        dedupe_store(dedupe, msg)
        self._json({"message": msg})

    def _post_delete(self):
        raw = self._read_body(65536)
        try:
            data = json.loads(raw.decode("utf-8") or "{}")
            mid = int(data.get("id"))
        except Exception:  # noqa: BLE001
            return self._json({"error": "参数错误"}, 400)
        self._json({"ok": delete_one(mid)})

    def _sub_ref(self, qs):
        """把 id / path 解析成 (视频真实路径, 回给前端的查询串前缀)。

        id 优先（电脑端网页用）；手机 App 的原生播放器只会给绝对路径，走 path。
        path 必须落在落盘目录或 data/files 里，别处一律拒绝。
        """
        try:
            mid = int((qs.get("id") or ["0"])[0])
        except ValueError:
            mid = 0
        if mid > 0:
            video = media_path_of(find_message(mid) or {})
            if video:
                return video, "id=%d" % mid
        raw = (qs.get("path") or [""])[0]
        if raw and sub_allowed(raw) and os.path.isfile(raw):
            return raw, "path=" + quote(raw)
        return "", ""

    def _subs_payload(self, qs):
        """同目录外挂字幕索引。/api/subs?id=<消息id> 或 /api/subs?path=<绝对路径>"""
        video, ref = self._sub_ref(qs)
        names = subs_for(video) if video else []
        base = os.path.splitext(os.path.basename(video))[0].lower() if video else ""
        subs = []
        for i, n in enumerate(names):
            lang, label = sub_label(n)
            tag = n[len(base) + 1:] if base and n.lower().startswith(base + ".") else n
            subs.append({
                "name": n,
                "tag": tag,
                "lang": lang,
                "label": label,
                "url": "/api/sub?%s&f=%s" % (ref, quote(n)),
                "default": i == 0,
            })
        return {"ok": True, "count": len(subs), "subs": subs}

    def _serve_sub(self, qs):
        """把一条外挂字幕转成 WebVTT 吐出去。VLC 也吃 VTT，所以两条链路共用。"""
        name = os.path.basename((qs.get("f") or [""])[0] or "")
        video, _ = self._sub_ref(qs)
        if not video or not name:
            return self._send(404, "字幕不存在", "text/plain; charset=utf-8")
        folder = os.path.dirname(video)
        path = os.path.join(folder, name)
        # 只认同目录、扩展名在白名单里的文件；别让 ../ 把整个磁盘掏出来
        if os.path.realpath(os.path.dirname(path)) != os.path.realpath(folder):
            return self._send(403, "不允许的路径", "text/plain; charset=utf-8")
        ext = os.path.splitext(name)[1].lower()
        if ext not in SUB_EXTS or not os.path.isfile(path):
            return self._send(404, "字幕不存在", "text/plain; charset=utf-8")
        try:
            if os.path.getsize(path) > SUB_MAX_BYTES:
                return self._send(413, "字幕文件过大", "text/plain; charset=utf-8")
            with open(path, "rb") as fh:
                raw = fh.read()
        except OSError:
            return self._send(404, "字幕读取失败", "text/plain; charset=utf-8")
        text = _decode_sub(raw)
        if ext == ".vtt":
            vtt = text if text.lstrip().startswith("WEBVTT") else "WEBVTT\n\n" + text
        elif ext in (".ass", ".ssa"):
            vtt = ass_to_vtt(text)
        else:
            vtt = srt_to_vtt(text)
        return self._send(200, vtt.encode("utf-8"), "text/vtt; charset=utf-8")

    def _serve_file(self, mid: str, inline: bool = False):
        try:
            midv = int(mid)
        except ValueError:
            return self._send(404, "文件不存在", "text/plain; charset=utf-8")
        msg = find_message(midv)
        f = (msg or {}).get("file") or {}
        path = os.path.join(FILE_DIR, f.get("stored", "")) if f.get("stored") else ""
        if not path or not os.path.isfile(path):
            return self._send(404, "文件不存在或已清理", "text/plain; charset=utf-8")

        size = os.path.getsize(path)
        fname = f.get("name") or "file"
        mime = guess_mime(fname, f.get("mime") or "")
        ascii_name = re.sub(r"[^\x20-\x7e]", "_", fname).replace('"', "'") or "file"
        disp = "inline" if inline else "attachment"

        # 分段请求（Range）：视频拖动进度条、iOS Safari 播放视频都必须支持
        start, end, partial = 0, size - 1, False
        rng = self.headers.get("Range")
        if rng and rng.lower().startswith("bytes="):
            spec = rng[6:].split(",")[0].strip()
            try:
                if spec.startswith("-"):
                    start = max(0, size - int(spec[1:]))
                else:
                    a, _, b = spec.partition("-")
                    start = int(a) if a.strip() else 0
                    if b.strip():
                        end = min(int(b), size - 1)
                if start > end or start >= size or start < 0:
                    raise ValueError("bad range")
                partial = True
            except Exception:  # noqa: BLE001
                start, end, partial = 0, size - 1, False

        length = end - start + 1
        try:
            if partial:
                self.send_response(206)
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            else:
                self.send_response(200)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(length))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Disposition",
                             f"{disp}; filename=\"{ascii_name}\"; filename*=UTF-8''{quote(fname)}")
            self.send_header("Cache-Control", "private, max-age=86400")
            self.end_headers()
            with open(path, "rb") as fh:
                fh.seek(start)
                remaining = length
                while remaining > 0:
                    chunk = fh.read(min(262144, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
        except ConnectionError:
            self.close_connection = True


# ---------------------------------------------------------------- 入口
BANNER = """
  ┌───────────────────────────────────────────────┐
  │   简传 Lite · 局域网文件与文本互传            │
  └───────────────────────────────────────────────┘
"""


def open_browser(url: str) -> None:
    """用 Edge 的「应用模式」开一个独立窗口（无标签页），找不到就退回系统默认浏览器。
    仅供「脚本版 + 非窗口模式」使用；exe 默认走 pywebview 独立窗口。"""
    for edge in (
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        os.path.expandvars(r"%LOCALAPPDATA%\Microsoft\Edge\Application\msedge.exe"),
    ):
        if os.path.isfile(edge):
            try:
                subprocess.Popen([edge, f"--app={url}", "--new-window"])
                return
            except Exception:  # noqa: BLE001
                pass
    webbrowser.open(url)


class _RECT(ctypes.Structure):
    _fields_ = [
        ("left", ctypes.c_long),
        ("top", ctypes.c_long),
        ("right", ctypes.c_long),
        ("bottom", ctypes.c_long),
    ]


def _work_area() -> tuple:
    """屏幕可用区域（不含任务栏），并按 DPI 缩放折成窗口用的逻辑单位。

    Windows 缩放到 125% 时，系统 API 给的是物理像素、窗口坐标却是逻辑像素，
    直接用物理值算尺寸会算出比屏幕还大的窗口，被压成一条扁的。"""
    zero = _RECT(0, 0, 0, 0)
    try:
        u = ctypes.windll.user32
        r = _RECT()
        if not u.SystemParametersInfoW(0x0030, 0, ctypes.byref(r), 0):  # SPI_GETWORKAREA
            return zero
        try:
            scale = u.GetDpiForSystem() / 96.0
        except Exception:  # noqa: BLE001
            scale = 1.0
        scale = scale or 1.0
        return _RECT(
            int(r.left / scale), int(r.top / scale),
            int(r.right / scale), int(r.bottom / scale),
        )
    except Exception:  # noqa: BLE001
        return zero


def _hwnd_of(window) -> int:
    """拿到原生窗口句柄；平台不支持就返回 0，调用方退回 pywebview 的实现。"""
    try:
        return int(window.native.Handle.ToInt64())
    except Exception:  # noqa: BLE001
        return 0


def _round_corners(hwnd: int) -> None:
    """Win11 的圆角：无边框窗口默认是直角的，加个圆角看着才像原生窗口。
    Win10 上这个属性不被支持，调用失败就算了，不影响使用。"""
    if not hwnd:
        return
    try:
        pref = ctypes.c_int(2)  # DWMWCP_ROUND
        ctypes.windll.dwmapi.DwmSetWindowAttribute(
            hwnd, 33, ctypes.byref(pref), ctypes.sizeof(pref)
        )
    except Exception:  # noqa: BLE001
        pass


def _dark_titlebar(hwnd: int, dark: bool) -> None:
    """把系统标题栏刷成深色 / 浅色。

    DWM 的沉浸式深色标题栏（DWMWA_USE_IMMERSIVE_DARK_MODE）：Win10 20H1+
    用属性 20，更早的预览版用 19，两个都试一遍，失败就当这功能不存在。
    注意这是**系统画的**标题栏，只能深/浅两档，做不出主题那种渐变。"""
    if not hwnd:
        return
    try:
        val = ctypes.c_int(1 if dark else 0)
        dwm = ctypes.windll.dwmapi
        rc = -1
        for attr in (20, 19):
            # 20 是 Win10 20H1+ 的属性号，19 是更早的预览版；哪个成功就用哪个
            rc = dwm.DwmSetWindowAttribute(
                hwnd, attr, ctypes.byref(val), ctypes.sizeof(val)
            )
            if rc == 0:
                break
        if rc != 0:
            return
        # 只是设属性的话，标题栏有时不立刻重画（尤其窗口不在前台）。
        # 踢一下非客户区，让它马上换成新的底色。
        u = ctypes.windll.user32
        SWP_NOSIZE, SWP_NOMOVE, SWP_NOZORDER = 0x0001, 0x0002, 0x0004
        SWP_NOACTIVATE, SWP_FRAMECHANGED = 0x0010, 0x0020
        u.SetWindowPos(
            hwnd, 0, 0, 0, 0, 0,
            SWP_NOSIZE | SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED,
        )
    except Exception:  # noqa: BLE001
        pass


class WindowApi:
    """无边框窗口的三个按钮（最小化 / 最大化 / 关闭）走这里。
    只在页面自己画的顶栏里被调用；浏览器访问根本碰不到。

    注意：窗口对象必须存成 `_window`（下划线开头）。pywebview 会把 js_api
    上的**所有公开属性**递归反射成前端可调用的方法表，直接存 `self.window`
    会一路钻进 WinForms 的 AccessibilityObject 里无限递归，把 UI 线程刷死
    （表现为窗口「未响应」+ 满屏 maximum recursion depth exceeded）。"""

    def __init__(self) -> None:
        self._window = None
        self._maximized = False
        self._restore = None

    def minimize(self) -> None:
        if self._window is not None:
            self._window.minimize()

    def toggle_maximize(self) -> bool:
        """最大化铺满**可用区域**，不是铺满整块屏 —— 无边框窗口走系统最大化
        会把任务栏一起盖掉。

        尺寸/位置一律走 pywebview 的 move/resize（它内部按逻辑像素替你换算
        DPI）；直接 SetWindowPos 会按物理像素解释，125% 缩放下会缩水成 0.8 倍。"""
        w = self._window
        if w is None:
            return self._maximized
        try:
            if self._maximized and self._restore:
                l, t, wd, ht = self._restore
                w.move(l, t)
                w.resize(wd, ht)
                self._maximized = False
            else:
                self._restore = (w.x, w.y, w.width, w.height)
                wa = _work_area()
                if wa.right > wa.left and wa.bottom > wa.top:
                    w.move(wa.left, wa.top)
                    w.resize(wa.right - wa.left, wa.bottom - wa.top)
                    self._maximized = True
        except Exception:  # noqa: BLE001
            pass
        return self._maximized

    def close_window(self) -> None:
        if self._window is not None:
            self._window.destroy()

    def set_dark_titlebar(self, dark: bool) -> None:
        """页面切主题时顺手把标题栏刷成同一个深浅。"""
        _dark_titlebar(_hwnd_of(self._window) if self._window else 0, bool(dark))


# 窗口在跑的时候指向 WindowApi 实例，没有窗口（浏览器模式）就是 None。
# 页面切主题有两条路走：js_api 和本地 HTTP 兜底，两条都落到这个实例上。
_window_api = None


def apply_titlebar_dark(dark: bool) -> bool:
    """把主窗口的标题栏刷成深/浅。没有窗口就返回 False（浏览器模式）。"""
    api = _window_api
    if api is None:
        return False
    api.set_dark_titlebar(dark)
    return True


def _window_geometry() -> tuple:
    """窗口开多大、开在哪。

    默认 1080×740：老版本按「摆得下三栏」算成 1340×880，在 1536×816 的笔记本上
    几乎顶满整屏，用户第一反应就是「窗口太大」。1080 宽时左栏还有 208px，
    二维码、公网开关、公网密码三样都完整，消息区也放得下。
    用户拖过的话优先用他上次的大小和位置。屏幕实在太小就退让成「有多大开多大」。"""
    wa = _work_area()
    if wa.right <= wa.left:
        return 1080, 740, None, None
    aw, ah = wa.right - wa.left, wa.bottom - wa.top
    # 用户拖过就先按他拖的来 —— 比任何默认值都准
    saved = read_config().get("window")
    if isinstance(saved, dict):
        try:
            sw, sh = int(saved.get("w", 0)), int(saved.get("h", 0))
            sx, sy = int(saved.get("x", 0)), int(saved.get("y", 0))
        except (TypeError, ValueError):
            sw = sh = 0
        # 换显示器/改缩放后旧尺寸可能比屏幕还大，装不下就退回默认
        if 640 <= sw <= aw and 480 <= sh <= ah:
            # 位置只要主体还留在屏幕里就沿用，否则重新居中（别开在屏幕外面找不着）
            keep_pos = 0 <= sx <= aw - 200 and 0 <= sy <= ah - 150
            return sw, sh, (sx if keep_pos else None), (sy if keep_pos else None)
    w = min(1080, max(aw - 40, 640))
    h = min(740, max(ah - 30, 460))
    x = wa.left + (aw - w) // 2
    y = wa.top + (ah - h) // 2
    return w, h, x, y


def save_window_geometry(window) -> None:
    """把当前窗口大小记进 config，下次照这个开。

    以前每次启动都是写死的 1340×880，用户拖小了、下次一开又弹回大的，
    只能每次手动再拖一遍。"""
    try:
        w, h = int(window.width), int(window.height)
        x, y = int(window.x), int(window.y)
    except Exception:  # noqa: BLE001
        return
    if w < 400 or h < 300:      # 最小化/异常态读出来是垃圾值，别覆盖
        return
    cfg = read_config()
    cfg["window"] = {"w": w, "h": h, "x": x, "y": y}
    write_config(cfg)


def run_window(url: str, theme: str = "") -> None:
    """独立窗口：pywebview 内嵌 WebView2，保留系统那条标题栏，但刷成深色。

    标题栏交给系统画（拖动、边缘缩放、贴边、任务栏停靠全都是原生行为），
    这里只做两件事：把标题栏刷深，按可用区域给一个够摆下三栏的初始尺寸。
    页面在带 `?app=1` 时会画自绘顶栏 —— 这条路径不再使用，所以不带这个参数。
    阻塞直到窗口关闭。"""
    import webview  # 延迟导入，脚本版没装也不影响

    api = WindowApi()
    w, h, x, y = _window_geometry()
    kwargs = {}
    if x is not None:
        kwargs["x"] = x
        kwargs["y"] = y
    # 窗口图标要给 webview.start(icon=...)，create_window 没有这个参数
    # （传进去会直接抛 TypeError，然后整段 fallback 成浏览器窗口，很难发现）
    icon = os.path.join(STATIC_DIR, "app.ico")
    if not os.path.isfile(icon):
        icon = None
    # app=1 只是告诉页面「我在独立窗口里」：别把标题改成带地址的形式。
    # 自绘顶栏要额外带 wbar=1，现在不用。
    sep = "&" if "?" in url else "?"
    extra = "&theme=" + theme if theme else ""
    window = webview.create_window(
        APP_NAME,
        f"{url}{sep}app=1{extra}",
        js_api=api,
        width=w,
        height=h,
        min_size=(760, 520),
        background_color="#e8eff6",
        **kwargs,
    )
    api._window = window
    global _window_api
    _window_api = api
    # 窗口一露面就把标题栏刷成目标深浅，别先白/黑一下再跳。
    # 页面加载完还会用 pywebviewready 再同步一次（那时读的是 localStorage 记住的主题）。
    light_themes = {"light", "mint", "sunset"}
    # 没指定主题时按浅色起（首次启动就是晨雾）；页面加载完会用记住的主题再同步一次
    start_dark = bool(theme) and theme not in light_themes
    try:
        window.events.shown += lambda *_: api.set_dark_titlebar(start_dark)
    except Exception:  # noqa: BLE001
        pass
    # 关窗前把尺寸/位置记下来，下次照这个开（用户拖过一次就不用再拖）
    try:
        window.events.closing += lambda *_: save_window_geometry(window)
    except Exception:  # noqa: BLE001
        pass
    webview.start(icon=icon)


def main() -> None:
    global LAN_IP, PASSWORD, PORT
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--no-browser", action="store_true")
    ap.add_argument("--window", action="store_true", help="用独立窗口（pywebview）打开")
    ap.add_argument("--browser", action="store_true", help="强制用浏览器打开")
    ap.add_argument("--password", default="", help="访问密码，不填则用已保存的密码或自动生成一个")
    ap.add_argument(
        "--theme",
        default="",
        help="强制指定启动主题：dark / light / mint / sunset / dusk；"
             "不填则由页面自己决定（首次晨雾，之后记住上次选的）",
    )
    args = ap.parse_args()

    load_store()
    PASSWORD = load_password(args.password)
    LAN_IP = lan_ip()

    # 打开方式：exe 双击默认独立窗口；脚本版默认浏览器，可用 --window 强制窗口
    frozen = getattr(sys, "frozen", False)
    if args.no_browser:
        use_window = False
    elif args.browser:
        use_window = False
    elif args.window:
        use_window = True
    else:
        use_window = frozen

    alive = running_instance(args.port)
    if alive:
        url = alive.get("url") or f"http://127.0.0.1:{args.port}"
        print(BANNER)
        print(f"  检测到已经在运行了，不用重复启动。")
        print(f"  本机地址    http://127.0.0.1:{args.port}")
        print(f"  局域网地址  {url}")
        print(f"  访问密码    {PASSWORD}")
        print()
        if not args.no_browser:
            open_browser(f"http://127.0.0.1:{args.port}/")
        time.sleep(1.5)
        return

    port = pick_port(args.port)
    PORT = port

    try:
        httpd = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    except OSError as exc:
        # 不能只 print + exit：无控制台 exe 里那就是「双击了，什么都没发生」，
        # 抛出去交给 __main__ 的兜底去弹窗 + 落盘。
        raise RuntimeError(f"端口 {port} 监听失败：{exc}") from exc

    threading.Thread(target=udp_discovery_server, daemon=True).start()

    # 剪贴板同步默认开启：用户不用点任何东西，手机复制的内容就进电脑剪贴板。
    # 起不来（没蓝牙 / 缺库）只打印提示，绝不影响文件传输。
    if _clip_bridge is not None:
        try:
            _clip_bridge.start()
        except Exception as exc:  # noqa: BLE001
            print(f"[提示] 剪贴板同步没启动起来（不影响传文件）：{exc}")

    print(BANNER)
    print(f"  本机地址    http://127.0.0.1:{port}")
    print(f"  局域网地址  http://{LAN_IP}:{port}     <-- 手机 / 其他电脑用这个")
    for extra in all_ips()[1:]:
        print(f"              http://{extra}:{port}     (其他网卡)")
    print(f"  电脑名称    {socket.gethostname()}")
    print(f"  访问密码    {PASSWORD}")
    print(f"  保存目录    {FILE_DIR}")
    print(f"  打开方式    {'独立窗口' if use_window else '浏览器'}")
    print()
    print("  手机与电脑连同一个 WiFi，在浏览器里输入上面的局域网地址即可。")
    print("  首次启动如果弹出「Windows 防火墙」提示，请点「允许访问」。")
    print("  关闭窗口 / 按 Ctrl + C 停止服务。")
    print()

    if use_window:
        # 独立窗口模式：服务后台跑，窗口阻塞；关窗口即停服务
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        time.sleep(0.3)
        try:
            run_window(f"http://127.0.0.1:{port}/", args.theme)
        except Exception as exc:  # noqa: BLE001
            print(f"[提示] 独立窗口启动失败，改用浏览器：{exc}")
            open_browser(f"http://127.0.0.1:{port}/")
            try:
                httpd.serve_forever()
            except KeyboardInterrupt:
                print("\n  已停止。")
        finally:
            try:
                httpd.shutdown()
            except Exception:  # noqa: BLE001
                pass
            httpd.server_close()
    else:
        if not args.no_browser:
            threading.Timer(0.7, lambda: open_browser(f"http://127.0.0.1:{port}/")).start()
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n  已停止。")
        finally:
            httpd.server_close()


def _ensure_streams() -> None:
    """无控制台 exe 里 sys.stdout / sys.stderr 是 None。

    print 本身没事（CPython 见 None 直接返回），但任何第三方库调
    `sys.stdout.fileno()` 或 `.reconfigure()` 就会 AttributeError 崩在启动阶段。
    这里挂一个 devnull 文件对象顶上：接口齐全，行为等同重定向到空设备。
    """
    for name in ("stdout", "stderr"):
        if getattr(sys, name, None) is None:
            try:
                setattr(sys, name, open(os.devnull, "w", encoding="utf-8", errors="replace"))
            except Exception:  # noqa: BLE001
                setattr(sys, name, io.StringIO())


def _report_fatal(detail: str) -> None:
    """启动失败时唯一的说话渠道：写日志 + 原生弹窗。

    隐藏控制台后「双击没反应」是最难查的故障，宁可多弹一个框。
    此时 pywebview / Tk 都还没起来，只能用 user32 的 MessageBoxW。
    """
    log_path = os.path.join(BASE_DIR, "startup-error.log")
    body = (
        f"{APP_NAME} {VERSION} 启动失败\n"
        f"时间：{time.strftime('%Y-%m-%d %H:%M:%S')}\n"
        f"目录：{BASE_DIR}\n\n"
        f"{detail}"
    )
    try:
        with open(log_path, "w", encoding="utf-8") as f:
            f.write(body)
    except Exception:  # noqa: BLE001
        log_path = "（日志没写成，目录可能只读）"

    # 只取最后一行的异常信息当标题下方那句话，完整堆栈留在日志里
    lines = [x.strip() for x in detail.strip().splitlines() if x.strip()]
    brief = lines[-1] if lines else "未知错误"
    if len(brief) > 300:
        brief = brief[:300] + "…"
    msg = (
        "简传 Lite 没能启动。\n\n"
        f"{brief}\n\n"
        f"详情已写入：\n{log_path}\n\n"
        "常见原因：8787 起不来（被别的程序占了全部备用端口）、"
        "安装目录没有写权限、杀毒软件拦住了 pywebview。"
    )
    try:
        # 0x10 = MB_ICONERROR | 0x0 = 默认按钮
        ctypes.windll.user32.MessageBoxW(None, msg, f"{APP_NAME} 启动失败", 0x10)
    except Exception:  # noqa: BLE001
        pass


if __name__ == "__main__":
    _ensure_streams()
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # noqa: BLE001
        pass
    try:
        main()
    except SystemExit:
        raise
    except BaseException:  # noqa: BLE001
        _report_fatal(traceback.format_exc())
        raise
