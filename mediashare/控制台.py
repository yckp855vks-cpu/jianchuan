# -*- coding: utf-8 -*-
"""MediaShare 图形控制台：管理共享目录、启停服务、看日志。

用法：
    双击「启动控制台.bat」，或 python 控制台.py
    自检：python 控制台.py --selftest
"""

import json
import os
import queue
import re
import socket
import subprocess
import sys
import threading
import time
import traceback


def _find_tk_python():
    """这台机器的自带 python 没编 tkinter，这里找一个能 import tkinter 的解释器。"""
    dirs = []
    local = os.environ.get("LOCALAPPDATA")
    if local:
        dirs.append(os.path.join(local, "Programs", "Python"))
    dirs += [r"C:\Python", r"C:\Program Files\Python"]
    home = os.environ.get("USERPROFILE", "")
    if home:
        dirs.append(os.path.join(home, ".workbuddy", "binaries", "python", "versions"))

    exes = []
    for base in dirs:
        try:
            names = sorted(os.listdir(base), reverse=True)
        except OSError:
            continue
        for n in names:
            exe = os.path.join(base, n, "python.exe")
            if os.path.isfile(exe) and os.path.normcase(exe) != os.path.normcase(sys.executable):
                exes.append(exe)

    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0
    for exe in exes:
        try:
            r = subprocess.run([exe, "-c", "import tkinter"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                               timeout=12, creationflags=flags)
        except Exception:
            continue
        if r.returncode == 0:
            return exe
    return ""


# 当前解释器没有 tkinter 时，自动换一个能跑 GUI 的解释器重启自己（打包后跳过）
if __name__ == "__main__" and not os.environ.get("MEDIASHARE_NO_REEXEC") and not getattr(sys, "frozen", False):
    try:
        import tkinter  # noqa: F401
    except Exception:
        _alt = _find_tk_python()
        if _alt:
            _env = dict(os.environ, MEDIASHARE_NO_REEXEC="1")
            try:
                sys.exit(subprocess.call([_alt, os.path.abspath(__file__)] + sys.argv[1:],
                                         env=_env))
            except Exception:
                pass
        print("[错误] 当前 Python 没有 tkinter，也没在常见位置找到带 tkinter 的 Python 3。")
        print("       装一个 python.org 的完整安装包（勾选 tcl/tk）即可。")
        sys.exit(1)

import tkinter as tk
from tkinter import filedialog, ttk

# frozen 时 HERE 指向 EXE 所在目录（config.json 读写在那边），资源在 _MEIPASS
if getattr(sys, "frozen", False):
    HERE = os.environ.get("MEDIASHARE_EXE_DIR", os.path.dirname(os.path.abspath(sys.executable)))
    _BUNDLE = os.environ.get("MEDIASHARE_BUNDLE_DIR", sys._MEIPASS)
else:
    HERE = os.path.dirname(os.path.abspath(__file__))
    _BUNDLE = HERE

# 皮肤背景图与二维码需要 PIL；缺失时优雅降级（纯色背景、无二维码）
try:
    from PIL import Image, ImageTk, ImageFilter, ImageEnhance, ImageDraw
    _HAS_PIL = True
except Exception:
    _HAS_PIL = False

try:
    import qrcode
    _HAS_QR = True
except Exception:
    _HAS_QR = False

SKIN_PATH = os.path.join(_BUNDLE, "skin.jpg")
ICON_PATH = os.path.join(_BUNDLE, "app_icon.ico")
CONFIG_PATH = os.path.join(HERE, "config.json")
SERVER_NAME = "server.py"

# 配色跟网页保持一致
BG = "#0a0d13"
CARD = "#141b26"
CARD_ODD = "#111823"
CARD_HOVER = "#1b2634"
BORDER = "#1e2938"
LOG_BG = "#080c12"
ACCENT = "#22d3ee"
ACCENT_HOVER = "#67e8f9"
TEXT = "#e8eef7"
MUTED = "#7f8ea3"
DANGER = "#f87171"
WARN = "#fbbf24"

FONT = "Microsoft YaHei UI"
MONO = "Consolas"

ADDR_RE = re.compile(r"http://(?:\d{1,3}\.){3}\d{1,3}:\d{2,5}")
MAX_LOG_LINES = 2000


def emit(text):
    """pythonw 下没有 stdout，这里静默跳过。"""
    try:
        if sys.stdout is not None:
            sys.stdout.write(text + "\n")
            sys.stdout.flush()
    except Exception:
        pass


def read_config_raw():
    if not os.path.exists(CONFIG_PATH):
        return {}
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def normalize_roots(data):
    """把 roots / 老的单 root 统一成 [{name, path}]。"""
    roots = []
    items = data.get("roots")
    if isinstance(items, list):
        for it in items:
            if isinstance(it, str) and it.strip():
                p = it.strip()
                roots.append({"name": os.path.basename(p.rstrip("\\/")) or p, "path": p})
            elif isinstance(it, dict) and it.get("path"):
                p = str(it["path"])
                name = str(it.get("name") or "").strip()
                roots.append({"name": name or os.path.basename(p.rstrip("\\/")) or p, "path": p})
    if not roots and data.get("root"):
        p = str(data["root"])
        name = str(data.get("name") or "").strip()
        roots.append({"name": name or os.path.basename(p.rstrip("\\/")) or p, "path": p})
    return roots


def save_config(roots, port, title):
    """写回 config.json，保持原有键名结构；先写临时文件再替换。"""
    data = read_config_raw()
    data.pop("root", None)
    data.pop("name", None)
    data["roots"] = [{"name": r["name"], "path": r["path"]} for r in roots]
    data["port"] = int(port)
    data["title"] = str(title)
    data.setdefault("host", "0.0.0.0")
    data.setdefault("quiet", False)
    tmp = CONFIG_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    os.replace(tmp, CONFIG_PATH)


def parse_addresses(text):
    """从服务输出里取出所有 http://ip:port，去重保序。"""
    found = []
    for url in ADDR_RE.findall(text or ""):
        if url not in found:
            found.append(url)
    return found


def local_ips():
    """拿到本机所有局域网 IPv4 地址（跳过 127.0.0.1）。"""
    ips = []
    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            ip = info[4][0]
            if ip != "127.0.0.1" and ip not in ips:
                ips.append(ip)
    except Exception:
        pass
    # 兜底：用 UDP 连一下拿出口 IP
    if not ips:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            if ip != "127.0.0.1":
                ips.append(ip)
        except Exception:
            pass
    return ips


def port_in_use(port, host="127.0.0.1", timeout=0.4):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        return s.connect_ex((host, int(port))) == 0
    except Exception:
        return False
    finally:
        s.close()


def dark_titlebar(root):
    """把系统标题栏也压成深色，跟内容区连成一片。"""
    if os.name != "nt":
        return
    try:
        import ctypes

        root.update_idletasks()
        hwnd = ctypes.windll.user32.GetParent(root.winfo_id()) or root.winfo_id()
        on = ctypes.c_int(1)
        for attr in (20, 19):  # DWMWA_USE_IMMERSIVE_DARK_MODE
            ctypes.windll.dwmapi.DwmSetWindowAttribute(
                hwnd, attr, ctypes.byref(on), ctypes.sizeof(on))
        # Win11 才能改标题栏颜色，失败也不影响
        caption = ctypes.c_int(0x00130D0A)   # COLORREF #0a0d13
        text = ctypes.c_int(0x00F7EEE8)      # COLORREF #e8eef7
        for attr, val in ((35, caption), (36, text)):
            ctypes.windll.dwmapi.DwmSetWindowAttribute(
                hwnd, attr, ctypes.byref(val), ctypes.sizeof(val))
    except Exception:
        pass


def font(size=10, bold=False):
    return (FONT, size, "bold") if bold else (FONT, size)


class FlatButton(tk.Button):
    """扁平色块按钮，自己处理悬停变色。"""

    KINDS = {
        "primary": (ACCENT, "#04161c", ACCENT_HOVER),
        "danger": ("#e5484d", "#fff5f5", "#f2636a"),
        "soft": ("#1b2432", TEXT, "#26313f"),
        "aqua": ("#10262e", ACCENT, "#16323c"),
    }

    def __init__(self, parent, text, command, kind="soft", size=10, bold=False,
                 padx=14, pady=7, **kw):
        self._kind = kind
        bg, fg, hover = self.KINDS[kind]
        super().__init__(parent, text=text, command=command, bg=bg, fg=fg,
                         activebackground=hover, activeforeground=fg,
                         disabledforeground="#4b5a6d", relief="flat", bd=0,
                         highlightthickness=0, font=font(size, bold),
                         cursor="hand2", padx=padx, pady=pady, **kw)
        self._hover = hover
        self.bind("<Enter>", self._on_enter)
        self.bind("<Leave>", self._on_leave)

    def _on_enter(self, _e=None):
        if str(self["state"]) != "disabled":
            self.configure(bg=self._hover)

    def _on_leave(self, _e=None):
        if str(self["state"]) != "disabled":
            self.configure(bg=self.KINDS[self._kind][0])

    def set_kind(self, kind, text=None):
        self._kind = kind
        bg, fg, hover = self.KINDS[kind]
        self._hover = hover
        self.configure(bg=bg, fg=fg, activebackground=hover, activeforeground=fg)
        if text is not None:
            self.configure(text=text)

    def set_text(self, text):
        self.configure(text=text)


class MessageDialog(tk.Toplevel):
    """深色提示/确认框，返回 True/False。"""

    def __init__(self, parent, title, message, ok="知道了", cancel=None, danger=False):
        super().__init__(parent)
        self.result = False
        self.configure(bg=BG)
        self.title(title)
        self.resizable(False, False)
        self.transient(parent)
        self.grab_set()

        wrap = tk.Frame(self, bg=BG, padx=26, pady=22)
        wrap.pack(fill="both", expand=True)

        tk.Label(wrap, text=title, bg=BG, fg=TEXT, font=font(13, True),
                 anchor="w").pack(fill="x")
        tk.Label(wrap, text=message, bg=BG, fg=MUTED, font=font(10),
                 justify="left", anchor="w", wraplength=380).pack(fill="x", pady=(10, 18))

        row = tk.Frame(wrap, bg=BG)
        row.pack(fill="x")
        FlatButton(row, ok, self._ok, kind="danger" if danger else "primary",
                   bold=True).pack(side="right")
        if cancel:
            FlatButton(row, cancel, self._cancel, kind="soft").pack(side="right", padx=(0, 8))

        self.bind("<Return>", lambda e: self._ok())
        self.bind("<Escape>", lambda e: self._cancel())
        self._center(parent)
        self.wait_window(self)

    def _center(self, parent):
        self.update_idletasks()
        w, h = self.winfo_width(), self.winfo_height()
        px, py = parent.winfo_rootx(), parent.winfo_rooty()
        pw, ph = parent.winfo_width(), parent.winfo_height()
        x = px + max(0, (pw - w) // 2)
        y = py + max(0, (ph - h) // 3)
        self.geometry(f"+{x}+{y}")
        # Toplevel 的父句柄要等窗口真出现才有，稍后再刷一次
        self.after(30, lambda: dark_titlebar(self))

    def _ok(self):
        self.result = True
        self.destroy()

    def _cancel(self):
        self.result = False
        self.destroy()


class DirDialog(tk.Toplevel):
    """添加/编辑共享目录。"""

    def __init__(self, parent, title, name="", path=""):
        super().__init__(parent)
        self.result = None
        self.configure(bg=BG)
        self.title(title)
        self.resizable(False, False)
        self.transient(parent)
        self.grab_set()

        wrap = tk.Frame(self, bg=BG, padx=26, pady=22)
        wrap.pack(fill="both", expand=True)
        tk.Label(wrap, text=title, bg=BG, fg=TEXT, font=font(13, True),
                 anchor="w").pack(fill="x", pady=(0, 16))

        tk.Label(wrap, text="显示名（投影仪上看到的名字）", bg=BG, fg=MUTED,
                 font=font(9), anchor="w").pack(fill="x")
        self.var_name = tk.StringVar(value=name)
        e1 = ttk.Entry(wrap, textvariable=self.var_name, style="Dark.TEntry",
                       font=font(10), width=44)
        e1.pack(fill="x", pady=(5, 14), ipady=2)

        tk.Label(wrap, text="文件夹路径", bg=BG, fg=MUTED,
                 font=font(9), anchor="w").pack(fill="x")
        path_row = tk.Frame(wrap, bg=BG)
        path_row.pack(fill="x", pady=(5, 0))
        self.var_path = tk.StringVar(value=path)
        e2 = ttk.Entry(path_row, textvariable=self.var_path, style="Dark.TEntry",
                       font=font(10), width=36)
        e2.pack(side="left", fill="x", expand=True, ipady=2)
        FlatButton(path_row, "浏览", self._browse, kind="soft", padx=12,
                   pady=5).pack(side="left", padx=(8, 0))

        btns = tk.Frame(wrap, bg=BG)
        btns.pack(fill="x", pady=(20, 0))
        FlatButton(btns, "保存", self._ok, kind="primary", bold=True).pack(side="right")
        FlatButton(btns, "取消", self._cancel, kind="soft").pack(side="right", padx=(0, 8))

        self.bind("<Return>", lambda e: self._ok())
        self.bind("<Escape>", lambda e: self._cancel())
        e1.focus_set()
        e1.icursor("end")
        self.update_idletasks()
        self._center(parent)
        self.wait_window(self)

    def _center(self, parent):
        w, h = self.winfo_width(), self.winfo_height()
        x = parent.winfo_rootx() + max(0, (parent.winfo_width() - w) // 2)
        y = parent.winfo_rooty() + max(0, (parent.winfo_height() - h) // 3)
        self.geometry(f"+{x}+{y}")
        self.after(30, lambda: dark_titlebar(self))

    def _browse(self):
        chosen = filedialog.askdirectory(parent=self, title="选择要共享的文件夹",
                                         initialdir=self.var_path.get() or HERE)
        if chosen:
            self.var_path.set(os.path.normpath(chosen))
            if not self.var_name.get().strip():
                self.var_name.set(os.path.basename(os.path.normpath(chosen)))

    def _ok(self):
        path = self.var_path.get().strip().strip('"')
        if not path:
            MessageDialog(self, "缺少路径", "请先选一个文件夹，或者手动填写路径。")
            return
        path = os.path.normpath(path)
        name = self.var_name.get().strip() or os.path.basename(path) or path
        self.result = {"name": name, "path": path}
        self.destroy()

    def _cancel(self):
        self.result = None
        self.destroy()


class ConsoleApp:
    def __init__(self, root, selftest=False):
        self.root = root
        self.selftest = selftest
        self.proc = None
        self.queue = queue.Queue()
        self.reader = None
        self.addresses = []
        self.roots = []
        self._hover = -1
        self._stopping = False
        self._fail_notified = False
        self._accept_output = False
        self._toast_job = None

        cfg = read_config_raw()
        self.roots = normalize_roots(cfg)

        self._setup_window()
        self._setup_style()
        self._build_ui()
        self.load_cfg_into_ui(cfg)
        self._refresh_rows()
        self._set_running(False)
        self._render_addresses()

        self.root.protocol("WM_DELETE_WINDOW", self._on_close)
        self.root.after(120, self._drain_queue)
        self.root.after(400, self._poll_process)

    # ---------- 外观 ----------
    def _setup_window(self):
        self.root.title("MediaShare 控制台")
        self.root.configure(bg=BG)
        sw = self.root.winfo_screenwidth()
        sh = self.root.winfo_screenheight()
        w = min(1100, max(940, sw - 60))
        h = min(760, max(640, sh - 90))
        x = max(0, (sw - w) // 2)
        y = max(0, (sh - h) // 2 - 16)
        self.root.geometry(f"{w}x{h}+{x}+{y}")
        self.root.minsize(940, 640)
        self.root.title("MediaShare 控制台")
        try:
            if os.path.isfile(ICON_PATH):
                self.root.iconbitmap(ICON_PATH)
        except Exception:
            pass
        dark_titlebar(self.root)
        self._bg_canvas = None
        self._bg_photo = None
        self._build_background()
        self.root.bind("<Configure>", self._on_resize)

    def _setup_style(self):
        st = ttk.Style(self.root)
        try:
            st.theme_use("clam")
        except tk.TclError:
            pass

        st.configure(".", background=BG, foreground=TEXT, font=font(10))

        st.configure("Dark.TEntry", fieldbackground="#0d141d", foreground=TEXT,
                     bordercolor="#22303f", lightcolor="#22303f", darkcolor="#22303f",
                     insertcolor=ACCENT, relief="flat", padding=(9, 6),
                     borderwidth=1, font=font(10))
        st.map("Dark.TEntry",
               bordercolor=[("focus", ACCENT), ("disabled", "#1a2431")],
               lightcolor=[("focus", ACCENT)],
               darkcolor=[("focus", ACCENT)],
               fieldbackground=[("disabled", "#0b1118")],
               foreground=[("disabled", "#55647a")])

        st.configure("Dark.Treeview", background=CARD, fieldbackground=CARD,
                     foreground=TEXT, rowheight=32, borderwidth=0,
                     font=font(10), relief="flat")
        st.configure("Dark.Treeview.Heading", background="#101722",
                     foreground=MUTED, relief="flat", borderwidth=0,
                     font=font(9, True), padding=(10, 8))
        st.map("Dark.Treeview.Heading", background=[("active", "#16202d")])
        # 选中/悬停全部用行 tag 控制，清掉主题默认映射避免互相打架
        st.map("Dark.Treeview", background=[], foreground=[])
        st.layout("Dark.Treeview", [("Treeview.treearea", {"sticky": "nswe"})])

        st.configure("Dark.Vertical.TScrollbar", background="#2a3646",
                     troughcolor=CARD, bordercolor=CARD, darkcolor=CARD,
                     lightcolor=CARD, arrowcolor=MUTED, arrowsize=12, width=10,
                     relief="flat")
        st.map("Dark.Vertical.TScrollbar",
               background=[("active", "#3b4a5e"), ("pressed", "#46586f")])
        st.configure("Log.Vertical.TScrollbar", background="#22303f",
                     troughcolor=LOG_BG, bordercolor=LOG_BG, darkcolor=LOG_BG,
                     lightcolor=LOG_BG, arrowcolor=MUTED, arrowsize=12, width=10,
                     relief="flat")
        st.map("Log.Vertical.TScrollbar",
               background=[("active", "#33445a"), ("pressed", "#3d5169")])

    # ---------- 皮肤背景（毛玻璃） ----------
    def _build_background(self):
        """铺一张模糊+压暗的皮肤图当背景，营造毛玻璃氛围。"""
        self._bg_canvas = tk.Canvas(self.root, bg=BG, highlightthickness=0, bd=0)
        self._bg_canvas.place(x=0, y=0, relwidth=1, relheight=1)
        self._bg_to_bottom()
        self._render_background()

    def _bg_to_bottom(self):
        """把背景画布压到最底层（避开 Canvas.lower 与 tag_lower 的命名冲突）。"""
        try:
            self._bg_canvas.tk.call("lower", self._bg_canvas._w)
        except Exception:
            pass

    def _render_background(self):
        """把皮肤图高斯模糊、压暗，铺满画布。窗口变化时重绘。"""
        if not (_HAS_PIL and self._bg_canvas):
            return
        w = max(1, self.root.winfo_width())
        h = max(1, self.root.winfo_height())
        if w <= 1 or h <= 1:
            return
        cache = getattr(self, "_bg_cache", None)
        if cache and cache[0] == (w, h):
            return
        try:
            if os.path.isfile(SKIN_PATH):
                im = Image.open(SKIN_PATH).convert("RGB")
                # 封面裁剪到窗口宽高比，避免拉伸变形
                tw, th = im.size
                target = w / h
                cur = tw / th
                if cur > target:
                    nw = int(th * target)
                    x0 = (tw - nw) // 2
                    im = im.crop((x0, 0, x0 + nw, th))
                else:
                    nh = int(tw / target)
                    y0 = (th - nh) // 2
                    im = im.crop((0, y0, tw, y0 + nh))
                im = im.resize((w, h), Image.LANCZOS)
                # 高斯模糊 + 轻度压暗，做出毛玻璃底，同时让皮肤少女清晰可辨
                im = im.filter(ImageFilter.GaussianBlur(10))
                im = ImageEnhance.Brightness(im).enhance(0.82)
                im = ImageEnhance.Color(im).enhance(0.95)
                overlay = Image.new("RGB", (w, h), (8, 11, 18))
                im = Image.blend(im, overlay, 0.10)
                self._bg_photo = ImageTk.PhotoImage(im)
                self._bg_cache = ((w, h),)
            else:
                self._bg_photo = None
        except Exception:
            self._bg_photo = None
        self._bg_canvas.delete("all")
        if self._bg_photo is not None:
            self._bg_canvas.create_image(0, 0, image=self._bg_photo, anchor="nw")
        self._bg_to_bottom()

    def _on_resize(self, _e=None):
        self._render_background()

    def _card(self, parent):
        return tk.Frame(parent, bg=CARD, highlightbackground=BORDER,
                        highlightthickness=1, bd=0)

    def _section_title(self, parent, text):
        row = tk.Frame(parent, bg=CARD)
        tk.Frame(row, bg=ACCENT, width=3, height=14).pack(side="left", padx=(0, 8))
        tk.Label(row, text=text, bg=CARD, fg=TEXT, font=font(11, True)).pack(side="left")
        return row

    # ---------- 布局 ----------
    def _build_ui(self):
        header = tk.Frame(self.root, bg=BG)
        header.pack(fill="x", padx=22, pady=(18, 14))

        brand = tk.Frame(header, bg=BG)
        brand.pack(side="left")
        # 动漫少女头像 + 标题并排
        brand_row = tk.Frame(brand, bg=BG)
        brand_row.pack(anchor="w")
        self._build_avatar(brand_row)
        title_col = tk.Frame(brand_row, bg=BG)
        title_col.pack(side="left", padx=(12, 0))
        tk.Label(title_col, text="✦ MediaShare 控制台", bg=BG, fg=ACCENT,
                 font=font(20, True)).pack(anchor="w")
        tk.Label(title_col, text="掌控你的私有影音结界 · MediaShare", bg=BG, fg=MUTED,
                 font=font(9)).pack(anchor="w", pady=(3, 0))

        pill = tk.Frame(header, bg=CARD, highlightbackground=BORDER,
                        highlightthickness=1)
        pill.pack(side="right", pady=6)
        self.lbl_dot = tk.Label(pill, text="●", bg=CARD, fg="#3f4c5c",
                                font=font(10))
        self.lbl_dot.pack(side="left", padx=(14, 6), pady=7)
        self.lbl_status = tk.Label(pill, text="已停止", bg=CARD, fg=MUTED,
                                   font=font(10))
        self.lbl_status.pack(side="left", padx=(0, 16), pady=7)

        # 启停按钮固定放标题栏右侧：底部「访问地址」卡片高度不够时，
        # pack 会把最后创建的按钮裁掉（曾导致「运行中却找不到停止按钮」）。
        self.btn_start = FlatButton(header, "▶ 启动服务", self._toggle_service,
                                    kind="primary", size=11, bold=True,
                                    padx=20, pady=8)
        self.btn_start.pack(side="right", padx=(0, 12), pady=6)

        # 日志先占住底部高度，否则空间不够时会被挤扁
        self._build_log()

        body = tk.Frame(self.root, bg=BG)
        body.pack(fill="both", expand=True, padx=22)

        body.columnconfigure(0, weight=1)
        body.columnconfigure(1, weight=0, minsize=350)
        body.rowconfigure(0, weight=1)

        self._build_dirs(body)
        side = tk.Frame(body, bg=BG)
        side.grid(row=0, column=1, sticky="nsew", padx=(14, 0))
        self._build_params(side)
        self._build_address(side)

    def _build_avatar(self, parent):
        """标题栏左侧放一个动漫少女头像（皮肤）。"""
        if not _HAS_PIL or not os.path.isfile(SKIN_PATH):
            return
        try:
            im = Image.open(SKIN_PATH).convert("RGB")
            tw, th = im.size
            # 取脸部特写（眼睛+红发夹区域）
            im = im.crop((int(tw * 0.28), int(th * 0.16), int(tw * 0.72), int(th * 0.52)))
            im = im.resize((56, 56), Image.LANCZOS)
            # 圆形头像
            mask = Image.new("L", (56, 56), 0)
            from PIL import ImageDraw as _ID
            _ID.Draw(mask).ellipse((0, 0, 56, 56), fill=255)
            avatar = Image.new("RGBA", (56, 56), (0, 0, 0, 0))
            avatar.paste(im, (0, 0), mask)
            self._avatar_photo = ImageTk.PhotoImage(avatar)
            tk.Label(parent, image=self._avatar_photo, bg=BG, bd=0,
                     highlightthickness=0).pack(side="left")
        except Exception:
            pass

    def _build_dirs(self, parent):
        card = self._card(parent)
        card.grid(row=0, column=0, sticky="nsew")
        card.columnconfigure(0, weight=1)
        card.rowconfigure(2, weight=1)

        head = tk.Frame(card, bg=CARD)
        head.grid(row=0, column=0, sticky="ew", padx=16, pady=(14, 0))
        self._section_title(head, "共享目录").pack(side="left")
        tk.Label(head, text="双击行可改名", bg=CARD, fg=MUTED,
                 font=font(9)).pack(side="right")

        self.lbl_dir_hint = tk.Label(card, text="", bg=CARD, fg=MUTED,
                                     font=font(9), anchor="w")
        self.lbl_dir_hint.grid(row=1, column=0, sticky="ew", padx=17, pady=(6, 8))

        tree_wrap = tk.Frame(card, bg=CARD)
        tree_wrap.grid(row=2, column=0, sticky="nsew", padx=16, pady=(0, 12))
        tree_wrap.columnconfigure(0, weight=1)
        tree_wrap.rowconfigure(0, weight=1)

        self.tree = ttk.Treeview(tree_wrap, columns=("name", "path"),
                                 show="headings", selectmode="browse",
                                 style="Dark.Treeview")
        self.tree.heading("name", text="显示名", anchor="w")
        self.tree.heading("path", text="路径", anchor="w")
        self.tree.column("name", width=190, minwidth=120, anchor="w", stretch=False)
        self.tree.column("path", width=460, minwidth=220, anchor="w", stretch=True)
        self.tree.grid(row=0, column=0, sticky="nsew")

        sb = ttk.Scrollbar(tree_wrap, orient="vertical", command=self.tree.yview,
                           style="Dark.Vertical.TScrollbar")
        sb.grid(row=0, column=1, sticky="ns", padx=(6, 0))
        self.tree.configure(yscrollcommand=sb.set)

        self.tree.tag_configure("row_even", background=CARD, foreground=TEXT)
        self.tree.tag_configure("row_odd", background=CARD_ODD, foreground=TEXT)
        self.tree.tag_configure("row_even_miss", background=CARD, foreground=DANGER)
        self.tree.tag_configure("row_odd_miss", background=CARD_ODD, foreground=DANGER)
        self.tree.tag_configure("row_hover", background=CARD_HOVER, foreground="#ffffff")
        self.tree.tag_configure("row_hover_miss", background="#241a20", foreground="#fca5a5")
        self.tree.tag_configure("row_sel", background="#0e3a44", foreground=ACCENT)
        self.tree.tag_configure("row_sel_miss", background="#0e3a44", foreground="#fca5a5")

        self.tree.bind("<Double-1>", self._on_tree_double)
        self.tree.bind("<Delete>", lambda e: self._delete_dir())
        self.tree.bind("<Motion>", self._on_tree_motion)
        self.tree.bind("<Leave>", self._on_tree_leave)
        self.tree.bind("<<TreeviewSelect>>", lambda e: self._apply_row_tags())

        bar = tk.Frame(card, bg=CARD)
        bar.grid(row=3, column=0, sticky="ew", padx=16, pady=(0, 14))
        self.btn_add = FlatButton(bar, "添加目录", self._add_dir, kind="aqua",
                                  bold=True)
        self.btn_add.pack(side="left")
        self.btn_del = FlatButton(bar, "删除选中", self._delete_dir, kind="soft")
        self.btn_del.pack(side="left", padx=(8, 0))
        self.btn_organize = FlatButton(bar, "智能整理", self._organize, kind="soft")
        self.btn_organize.pack(side="left", padx=(8, 0))
        self.btn_import = FlatButton(bar, "从手机导入", self._import_phone, kind="soft")
        self.btn_import.pack(side="left", padx=(8, 0))
        self.btn_up = FlatButton(bar, "上移", lambda: self._move(-1), kind="soft",
                                 padx=12)
        self.btn_down = FlatButton(bar, "下移", lambda: self._move(1), kind="soft",
                                   padx=12)
        self.btn_down.pack(side="right")
        self.btn_up.pack(side="right", padx=(0, 8))

    def _build_params(self, parent):
        card = self._card(parent)
        card.pack(fill="x", pady=(0, 12))

        inner = tk.Frame(card, bg=CARD)
        inner.pack(fill="x", padx=16, pady=14)
        self._section_title(inner, "服务参数").pack(anchor="w", pady=(0, 12))

        tk.Label(inner, text="端口", bg=CARD, fg=MUTED,
                 font=font(9), anchor="w").pack(fill="x")
        self.var_port = tk.StringVar(value="8848")
        self.ent_port = ttk.Entry(inner, textvariable=self.var_port,
                                  style="Dark.TEntry", font=font(10))
        self.ent_port.pack(fill="x", pady=(5, 12), ipady=1)

        tk.Label(inner, text="页面标题", bg=CARD, fg=MUTED,
                 font=font(9), anchor="w").pack(fill="x")
        self.var_title = tk.StringVar(value="MediaShare")
        self.ent_title = ttk.Entry(inner, textvariable=self.var_title,
                                   style="Dark.TEntry", font=font(10))
        self.ent_title.pack(fill="x", pady=(5, 2), ipady=1)

    def _build_address(self, parent):
        card = self._card(parent)
        card.pack(fill="both", expand=True)

        inner = tk.Frame(card, bg=CARD)
        inner.pack(fill="both", expand=True, padx=16, pady=14)
        self._section_title(inner, "访问地址 · 手机扫码即达").pack(anchor="w")

        self.addr_box = tk.Frame(inner, bg=CARD)
        # 不要 expand：吃光剩余空间会把后面的二维码 / 提示挤出可视区
        self.addr_box.pack(fill="x", pady=(10, 0))

        # 二维码（服务启动后生成第一个地址的码）
        self.qr_box = tk.Frame(inner, bg=CARD)
        self.qr_box.pack(anchor="center", pady=(10, 0))
        self._qr_photo = None

        self.lbl_toast = tk.Label(inner, text="", bg=CARD, fg=ACCENT, font=font(9),
                                  anchor="w")
        self.lbl_toast.pack(fill="x")
        # 启停按钮已移到标题栏（self.btn_start），此处不再放按钮：
        # addr_box 用了 expand=True，空间不足时最后 pack 的按钮会被 Tk 裁掉。

    def _build_log(self):
        card = self._card(self.root)
        card.pack(side="bottom", fill="x", padx=22, pady=(14, 18))
        card.configure(height=196)
        card.pack_propagate(False)

        head = tk.Frame(card, bg=CARD)
        head.pack(fill="x", padx=16, pady=(12, 8))
        self._section_title(head, "运行日志").pack(side="left")
        FlatButton(head, "清空", self._clear_log, kind="soft", size=9,
                   padx=10, pady=3).pack(side="right")
        tk.Label(head, text="实时输出 · 最多 2000 行", bg=CARD, fg=MUTED,
                 font=font(9)).pack(side="right", padx=(0, 10))

        text_wrap = tk.Frame(card, bg=CARD)
        text_wrap.pack(fill="both", expand=True, padx=16, pady=(0, 14))
        text_wrap.columnconfigure(0, weight=1)
        text_wrap.rowconfigure(0, weight=1)

        self.log = tk.Text(text_wrap, bg=LOG_BG, fg="#c3cfdd", relief="flat", bd=0,
                           highlightthickness=0, wrap="none", font=(MONO, 9),
                           padx=12, pady=9, state="disabled", cursor="arrow",
                           selectbackground="#1d3b47", selectforeground=ACCENT,
                           insertbackground=ACCENT, spacing1=1, spacing3=1)
        self.log.grid(row=0, column=0, sticky="nsew")
        sb = ttk.Scrollbar(text_wrap, orient="vertical", command=self.log.yview,
                           style="Log.Vertical.TScrollbar")
        sb.grid(row=0, column=1, sticky="ns", padx=(6, 0))
        self.log.configure(yscrollcommand=sb.set)

        self.log.tag_configure("dim", foreground="#4a586b")
        self.log.tag_configure("warn", foreground=WARN)
        self.log.tag_configure("meta", foreground=ACCENT)
        self.log.tag_configure("req", foreground="#8fa0b5")
        self.log.tag_configure("norm", foreground="#c3cfdd")

        self._append_log("控制台就绪。配置共享目录后点「启动服务」。")

    # ---------- 目录操作 ----------
    def _persist_dirs(self):
        """把当前目录列表立即写回 config.json；服务运行中则提示重启生效。"""
        try:
            save_config(self.roots, self._read_port() or 8848,
                        self.var_title.get().strip() or "MediaShare")
        except Exception as exc:
            self._append_log(f"[控制台] 写入配置失败：{exc}")
            return
        if self.proc is not None:
            self._show_toast("目录已保存 · 重启服务后生效")
            self._append_log("[控制台] 目录已保存，正在运行的服务需重启后才会读到新目录")

    def _set_dir_hint(self):
        missing = sum(1 for r in self.roots if r.get("_missing"))
        if missing:
            self.lbl_dir_hint.configure(
                text=f"{missing} 个路径不存在，启动时会被跳过（红色行）", fg=DANGER)
        else:
            self.lbl_dir_hint.configure(
                text="红色行 = 路径不存在；列表顺序决定投影仪上的排列", fg=MUTED)

    def _refresh_rows(self):
        sel_idx = self._selected_indices()
        for iid in self.tree.get_children():
            self.tree.delete(iid)
        for i, r in enumerate(self.roots):
            r["_missing"] = not os.path.isdir(r["path"])
            self.tree.insert("", "end", iid=f"r{i}",
                             values=(r["name"], r["path"]))
        self._apply_row_tags()
        for i in sel_idx:
            if 0 <= i < len(self.roots):
                self.tree.selection_add(f"r{i}")
        self._set_dir_hint()

    def _apply_row_tags(self):
        if not getattr(self, "tree", None):
            return
        sel = set(self.tree.selection())
        for i, r in enumerate(self.roots):
            iid = f"r{i}"
            if not self.tree.exists(iid):
                continue
            odd = i % 2 == 1
            miss = bool(r.get("_missing"))
            if iid in sel:
                tag = "row_sel_miss" if miss else "row_sel"
            elif i == self._hover:
                tag = "row_hover_miss" if miss else "row_hover"
            else:
                tag = ("row_odd_miss" if odd else "row_even_miss") if miss else \
                      ("row_odd" if odd else "row_even")
            self.tree.item(iid, tags=(tag,))

    def _selected_indices(self):
        out = []
        for iid in self.tree.selection():
            try:
                out.append(int(str(iid)[1:]))
            except (ValueError, IndexError):
                pass
        return out

    def _on_tree_motion(self, event):
        iid = self.tree.identify_row(event.y)
        idx = -1
        if iid:
            try:
                idx = int(str(iid)[1:])
            except ValueError:
                idx = -1
        if idx != self._hover:
            self._hover = idx
            self._apply_row_tags()

    def _on_tree_leave(self, _e=None):
        if self._hover != -1:
            self._hover = -1
            self._apply_row_tags()

    def _add_dir(self):
        chosen = filedialog.askdirectory(parent=self.root, title="选择要共享的文件夹",
                                         initialdir=HERE)
        if not chosen:
            return
        path = os.path.normpath(chosen)
        dlg = DirDialog(self.root, "添加共享目录",
                        name=os.path.basename(path) or path, path=path)
        if not dlg.result:
            return
        self.roots.append(dlg.result)
        self._refresh_rows()
        self.tree.selection_set(f"r{len(self.roots) - 1}")
        self.tree.see(f"r{len(self.roots) - 1}")
        self._append_log(f"[控制台] 添加目录：{dlg.result['name']} -> {dlg.result['path']}")
        self._persist_dirs()

    def _delete_dir(self):
        idxs = self._selected_indices()
        if not idxs:
            self._show_toast("先选中一行再删除")
            return
        idx = idxs[0]
        name = self.roots[idx]["name"]
        ok = MessageDialog(self.root, "删除共享目录",
                           f"从列表移除「{name}」？\n磁盘上的文件不会被删除。",
                           ok="移除", cancel="取消", danger=True).result
        if not ok:
            return
        self.roots.pop(idx)
        self._refresh_rows()
        self._append_log(f"[控制台] 已移除目录：{name}")
        self._persist_dirs()

    def _move(self, delta):
        idxs = self._selected_indices()
        if not idxs:
            self._show_toast("先选中一行再移动")
            return
        idx = idxs[0]
        new = idx + delta
        if new < 0 or new >= len(self.roots):
            return
        self.roots[idx], self.roots[new] = self.roots[new], self.roots[idx]
        self._refresh_rows()
        self.tree.selection_set(f"r{new}")
        self.tree.see(f"r{new}")
        self._persist_dirs()

    def _on_tree_double(self, event):
        iid = self.tree.identify_row(event.y)
        if not iid:
            return
        try:
            idx = int(str(iid)[1:])
        except ValueError:
            return
        r = self.roots[idx]
        dlg = DirDialog(self.root, "编辑共享目录", name=r["name"], path=r["path"])
        if dlg.result:
            self.roots[idx] = dlg.result
            self._refresh_rows()
            self.tree.selection_set(f"r{idx}")
            self._append_log(f"[控制台] 修改目录：{dlg.result['name']}")
            self._persist_dirs()

    # ---------- 智能整理 ----------
    def _organize(self):
        """调用服务器的 /api/organize 接口，把同系列视频归入子文件夹。"""
        if self.proc is None:
            MessageDialog(self.root, "服务未启动", "先启动服务再使用智能整理。")
            return
        ok = MessageDialog(
            self.root, "智能整理",
            "扫描共享目录，把同系列的视频自动归入子文件夹。\n"
            "例如：死神01.mp4 ~ 死神10.mp4 → 归入「死神」文件夹。\n\n"
            "先预览再执行，确定吗？",
            ok="预览", cancel="取消").result
        if not ok:
            return
        port = self._read_port() or 8848
        threading.Thread(target=self._organize_worker, args=(port,), daemon=True).start()

    def _organize_worker(self, port):
        import urllib.request
        try:
            url = f"http://127.0.0.1:{port}/api/organize?dry=1"
            req = urllib.request.Request(url, method="POST", data=b"")
            with urllib.request.urlopen(req, timeout=30) as resp:
                data = json.loads(resp.read().decode("utf-8"))
        except Exception as exc:
            self.root.after(0, lambda: MessageDialog(
                self.root, "整理失败", f"无法连接服务器：{exc}"))
            return

        moves = data.get("moves", [])
        if not moves:
            self.root.after(0, lambda: MessageDialog(
                self.root, "智能整理", "没有发现需要整理的系列。"))
            return

        preview = "\n".join(f"  {m['file']} → {m['series']}/" for m in moves[:15])
        if len(moves) > 15:
            preview += f"\n  … 还有 {len(moves) - 15} 个"

        def _confirm():
            ok2 = MessageDialog(
                self.root, "确认整理",
                f"将移动 {len(moves)} 个文件：\n{preview}\n\n执行吗？",
                ok="执行", cancel="取消").result
            if ok2:
                threading.Thread(target=self._organize_exec, args=(port,), daemon=True).start()
        self.root.after(0, _confirm)

    def _organize_exec(self, port):
        import urllib.request
        try:
            url = f"http://127.0.0.1:{port}/api/organize"
            req = urllib.request.Request(url, method="POST", data=b"")
            with urllib.request.urlopen(req, timeout=60) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            count = data.get("count", 0)
            self.root.after(0, lambda: (
                self._append_log(f"[控制台] 智能整理完成，移动了 {count} 个文件"),
                MessageDialog(self.root, "整理完成", f"成功移动 {count} 个文件到系列文件夹。")
            ))
        except Exception as exc:
            self.root.after(0, lambda: MessageDialog(
                self.root, "整理失败", str(exc)))

    # ---------- 从手机导入 ----------
    def _import_phone(self):
        """检测 USB 连接的手机存储，导入视频到共享目录。"""
        # 扫描可能的手机存储盘符（MTP 映射的盘或可移动磁盘）
        phone_paths = self._detect_phone_storage()
        if not phone_paths:
            # 现代手机多为 MTP 连接，不映射成盘符，扫不到。
            # 让用户手动选择手机文件夹（或任何放视频的文件夹）导入。
            ask = MessageDialog(
                self.root, "没检测到手机存储",
                "自动扫描没找到 USB 手机盘符（现在手机多为 MTP 连接，"
                "电脑上不显示成盘符）。\n\n"
                "可以手动选择一个文件夹来导入里面的视频：\n"
                "比如手机用数据线接电脑后，在「此电脑」里打开手机、"
                "找到视频所在文件夹；或先把手视频拷到电脑某个文件夹再选它。",
                ok="手动选文件夹", cancel="取消")
            if not ask.result:
                return
            chosen = filedialog.askdirectory(
                parent=self.root, title="选择要导入视频的文件夹",
                initialdir=HERE)
            if not chosen:
                return
            self._scan_and_import(os.path.normpath(chosen))
            return

        if len(phone_paths) == 1:
            self._scan_and_import(phone_paths[0])
        else:
            # 多个可选路径，列出让用户选
            names = "\n".join(f"  {p}" for p in phone_paths[:8])
            MessageDialog(self.root, "选择手机存储",
                          f"检测到多个可能的手机存储：\n{names}\n\n"
                          f"默认使用第一个：{phone_paths[0]}")
            self._scan_and_import(phone_paths[0])

    def _detect_phone_storage(self):
        """检测 USB 连接的手机存储路径。"""
        import string
        found = []
        # 方法1：扫描所有盘符，找 DCIM / Camera / Movies 等手机特征目录
        for letter in string.ascii_uppercase:
            drive = f"{letter}:\\"
            if not os.path.isdir(drive):
                continue
            # 检查是否有手机典型目录
            for marker in ("DCIM", "Camera", "Movies", "Download", "Pictures", "WhatsApp"):
                if os.path.isdir(os.path.join(drive, marker)):
                    found.append(drive)
                    break
        # 方法2：检查 MTP 虚拟路径（Windows 资源管理器风格）
        # MTP 路径通常不在常规盘符下，检查可移动磁盘
        import ctypes
        try:
            bitmask = ctypes.windll.kernel32.GetLogicalDrives()
            for i in range(26):
                if bitmask & (1 << i):
                    drive = f"{chr(65 + i)}:\\"
                    dtype = ctypes.windll.kernel32.GetDriveTypeW(drive)
                    if dtype == 2 and drive not in found:  # DRIVE_REMOVABLE
                        # 可移动磁盘也可能是手机
                        try:
                            items = os.listdir(drive)
                            if any(x in items for x in ("DCIM", "Camera", "Movies", "Download")):
                                found.append(drive)
                        except OSError:
                            pass
        except Exception:
            pass
        return found

    def _scan_and_import(self, phone_root):
        """扫描手机存储里的视频文件，导入到「手机上传」目录。"""
        VIDEO_EXTS = {".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".ts",
                      ".m2ts", ".webm", ".rmvb", ".3gp", ".m4v"}
        videos = []
        scan_dirs = ["DCIM", "Camera", "Movies", "Download", "Pictures", "WhatsApp", ""]

        for sd in scan_dirs:
            scan_path = os.path.join(phone_root, sd) if sd else phone_root
            if not os.path.isdir(scan_path):
                continue
            for dirpath, dirnames, filenames in os.walk(scan_path):
                # 限制扫描深度
                depth = dirpath[len(scan_path):].count(os.sep)
                if depth > 3:
                    dirnames.clear()
                    continue
                for fn in filenames:
                    if os.path.splitext(fn)[1].lower() in VIDEO_EXTS:
                        full = os.path.join(dirpath, fn)
                        try:
                            st = os.stat(full)
                        except OSError:
                            continue
                        videos.append({"name": fn, "path": full, "size": st.st_size})
                if len(videos) >= 200:
                    break
            if len(videos) >= 200:
                break

        if not videos:
            MessageDialog(self.root, "没有视频", "手机存储里没找到视频文件。")
            return

        # 确认导入
        total_mb = sum(v["size"] for v in videos) / (1024 * 1024)
        preview = "\n".join(f"  {v['name']}" for v in videos[:10])
        if len(videos) > 10:
            preview += f"\n  … 还有 {len(videos) - 10} 个"

        ok = MessageDialog(
            self.root, "确认导入",
            f"找到 {len(videos)} 个视频（共 {total_mb:.0f} MB）：\n{preview}\n\n"
            f"导入到「手机上传」目录？",
            ok="导入", cancel="取消").result
        if not ok:
            return

        threading.Thread(target=self._do_import, args=(videos,), daemon=True).start()

    def _do_import(self, videos):
        """复制视频文件到「手机上传」目录。"""
        # 确定目标目录
        upload_dir = os.path.join(HERE, "手机上传")
        os.makedirs(upload_dir, exist_ok=True)

        total = len(videos)
        for i, v in enumerate(videos):
            dst = os.path.join(upload_dir, v["name"])
            # 重名处理
            if os.path.exists(dst):
                base, ext = os.path.splitext(v["name"])
                n = 1
                while os.path.exists(dst):
                    dst = os.path.join(upload_dir, f"{base}_{n}{ext}")
                    n += 1
            try:
                import shutil
                shutil.copy2(v["path"], dst)
                self.root.after(0, lambda i=i, n=v["name"]: self._append_log(
                    f"[导入] {i + 1}/{total} {n}"))
            except Exception as exc:
                self.root.after(0, lambda e=str(exc), n=v["name"]: self._append_log(
                    f"[导入失败] {n}: {e}"))

        self.root.after(0, lambda: (
            self._append_log(f"[控制台] 导入完成，共 {total} 个文件"),
            MessageDialog(self.root, "导入完成",
                          f"成功导入 {total} 个视频到「手机上传」目录。\n投影仪上刷新即可看到。")
        ))

    # ---------- 启停 ----------
    def _toggle_service(self):
        if self.proc is not None:
            self.stop_service()
        else:
            self.start_service()

    def _read_port(self):
        raw = self.var_port.get().strip()
        try:
            port = int(raw)
        except ValueError:
            return None
        return port if 1 <= port <= 65535 else None

    def start_service(self):
        if self.proc is not None:
            return
        port = self._read_port()
        if port is None:
            MessageDialog(self.root, "端口不对",
                          "端口要填 1 - 65535 之间的整数，比如 8848。")
            self.ent_port.focus_set()
            return

        title = self.var_title.get().strip() or "MediaShare"

        usable = [r for r in self.roots if os.path.isdir(r["path"])]
        missing = [r for r in self.roots if not os.path.isdir(r["path"])]
        if not usable:
            MessageDialog(self.root, "没有可用目录",
                          "列表里没有一个路径是存在的。\n先「添加目录」，或者双击红色行修正路径。")
            return
        if missing:
            names = "\n".join(f"· {r['name']}" for r in missing[:6])
            more = "" if len(missing) <= 6 else f"\n… 还有 {len(missing) - 6} 个"
            ok = MessageDialog(
                self.root, "有目录不存在",
                f"下面这些路径现在访问不到，服务启动时会跳过：\n{names}{more}\n\n继续启动吗？",
                ok="继续", cancel="返回修改").result
            if not ok:
                return

        if port_in_use(port):
            # 可能是上次残留的 MediaShare 服务器没退干净，先问要不要清掉
            ok = MessageDialog(self.root, "端口被占用",
                f"{port} 端口已被占用，可能是上次的 MediaShare 没退干净。\n\n"
                "要自动清掉残留进程并继续启动吗？",
                ok="清掉并启动", cancel="取消").result
            if not ok:
                self.ent_port.focus_set()
                self.ent_port.selection_range(0, "end")
                return
            self._kill_port_holders(port)
            import time
            time.sleep(0.5)
            if port_in_use(port):
                MessageDialog(self.root, "端口仍被占用",
                    f"清不掉 {port} 上的进程，可能被其他软件占了。\n"
                    f"把端口改成别的（比如 8849）再启动。")
                self.ent_port.focus_set()
                self.ent_port.selection_range(0, "end")
                return

        try:
            save_config(self.roots, port, title)
        except Exception as exc:
            MessageDialog(self.root, "配置写入失败", f"config.json 写不进去：\n{exc}")
            return
        self._append_log(f"[控制台] 已写入 config.json（端口 {port}，{len(self.roots)} 个目录）")

        flags = 0
        if os.name == "nt":
            flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        try:
            if getattr(sys, "frozen", False):
                # 打包模式：同一个 EXE 用 --run-server 参数跑服务器
                cmd = [sys.executable, "--run-server"]
            else:
                cmd = [sys.executable, "-u", SERVER_NAME]
            self.proc = subprocess.Popen(
                cmd,
                cwd=HERE,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
                bufsize=1,
                creationflags=flags,
                env={**os.environ, "PYTHONUNBUFFERED": "1"},
            )
        except Exception as exc:
            self.proc = None
            MessageDialog(self.root, "启动失败", f"拉不起子进程：\n{exc}")
            return

        self._fail_notified = False
        self._stopping = False
        self.addresses = []
        self._render_addresses()
        self._set_running(True)
        self._append_log(f"[控制台] 已启动 server.py，端口 {port}")

        self._accept_output = True
        self.reader = threading.Thread(target=self._read_output,
                                       args=(self.proc,), daemon=True)
        self.reader.start()
        # 不依赖子进程 stdout（--windowed 模式下输出全丢），自己轮询端口就绪后构建地址
        threading.Thread(target=self._wait_ready, args=(port,), daemon=True).start()

    def _wait_ready(self, port, timeout=15):
        """轮询端口直到服务器就绪，然后构建访问地址并更新界面。"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.proc is None or self.proc.poll() is not None:
                return  # 子进程已退出
            if port_in_use(port):
                # 服务器就绪，构建地址
                addrs = [f"http://{ip}:{port}" for ip in local_ips()]
                if not addrs:
                    addrs = [f"http://127.0.0.1:{port}"]
                self.root.after(0, self._on_ready, addrs)
                return
            time.sleep(0.5)
        # 超时了也显示出来
        self.root.after(0, lambda: self._append_log(
            f"[控制台] 等待服务器就绪超时（{timeout}秒），可能启动异常"))

    def _on_ready(self, addrs):
        if self.proc is None:
            return
        self.addresses = addrs
        self._render_addresses()
        self._update_status_running()
        self._append_log("[控制台] 服务器已就绪")
        for a in addrs:
            self._append_log(f"    {a}")

    def _read_output(self, proc):
        try:
            for line in proc.stdout:
                self.queue.put(line.rstrip("\r\n"))
        except Exception as exc:
            self.queue.put(f"[读取输出失败] {exc}")
        finally:
            self.queue.put(None)

    def _kill_port_holders(self, port):
        """强制杀掉占用指定端口的进程（用于清理上次残留的服务器）。"""
        if os.name != "nt":
            return
        try:
            out = subprocess.check_output(
                ["netstat", "-ano"], text=True, errors="replace", timeout=5)
        except Exception:
            return
        pids = set()
        for line in out.splitlines():
            if f":{port}" in line and "LISTENING" in line:
                parts = line.split()
                if parts:
                    try:
                        pids.add(int(parts[-1]))
                    except ValueError:
                        pass
        for pid in pids:
            try:
                subprocess.run(["taskkill", "/PID", str(pid), "/F"],
                               capture_output=True, timeout=5)
                self._append_log(f"[控制台] 已清理残留进程 PID {pid}（端口 {port}）")
            except Exception:
                pass

    def stop_service(self):
        p = self.proc
        if p is None:
            return
        self._accept_output = False
        self._stopping = True
        self._append_log("[控制台] 正在停止服务...")
        try:
            p.terminate()
        except Exception:
            pass
        try:
            p.wait(timeout=2)
        except subprocess.TimeoutExpired:
            self._append_log("[控制台] 2 秒未退出，强制结束")
            try:
                p.kill()
            except Exception:
                pass
            try:
                p.wait(timeout=3)
            except Exception:
                pass
        except Exception:
            pass
        self._cleanup_proc()
        self.addresses = []
        self._render_addresses()
        self._set_running(False)
        self._append_log("[控制台] 服务已停止")
        self._stopping = False

    def _cleanup_proc(self):
        p = self.proc
        self.proc = None
        if p is None:
            return
        try:
            if p.stdout:
                p.stdout.close()
        except Exception:
            pass
        try:
            p.wait(timeout=1)
        except Exception:
            pass

    def _poll_process(self):
        if not self.root.winfo_exists():
            return
        p = self.proc
        if p is not None and p.poll() is not None:
            code = p.returncode
            self._accept_output = False
            self._append_log(f"[控制台] 子进程已退出，返回码 {code}")
            self._cleanup_proc()
            self.addresses = []
            self._render_addresses()
            self._set_running(False)
            if not self._stopping and code not in (0, None) and not self._fail_notified:
                self._fail_notified = True
                MessageDialog(self.root, "服务异常退出",
                              f"server.py 返回码 {code}，具体原因看下面的日志。\n"
                              "常见原因是端口被占用或共享目录不可读。")
        self.root.after(400, self._poll_process)

    # ---------- 输出处理 ----------
    def _drain_queue(self):
        if not self.root.winfo_exists():
            return
        lines = []
        while True:
            try:
                item = self.queue.get_nowait()
            except queue.Empty:
                break
            if item is None:
                continue
            lines.append(item)
        if lines:
            new_addrs = []
            for line in lines:
                self._append_log(line)
                if self._accept_output:
                    for url in parse_addresses(line):
                        if url not in self.addresses and url not in new_addrs:
                            new_addrs.append(url)
            if new_addrs:
                self.addresses.extend(new_addrs)
                self._render_addresses()
                self._update_status_running()
        self.root.after(120, self._drain_queue)

    def _log_tag(self, line):
        if "http://" in line and line.startswith("    "):
            return "meta"
        if "[控制台]" in line:
            return "meta"
        if "[警告]" in line or "[错误]" in line or "[warn]" in line or "Traceback" in line:
            return "warn"
        if '"GET ' in line or '"HEAD ' in line or '"POST ' in line:
            return "req"
        if line.startswith("  ") or set(line) <= set("─ "):
            return "dim"
        return "norm"

    def _append_log(self, line):
        log = self.log
        log.configure(state="normal")
        log.insert("end", line + "\n", self._log_tag(line))
        # 超出上限就删掉最前面的
        total = int(log.index("end-1c").split(".")[0])
        if total > MAX_LOG_LINES:
            log.delete("1.0", f"{total - MAX_LOG_LINES}.0")
        log.see("end")
        log.configure(state="disabled")

    def _clear_log(self):
        self.log.configure(state="normal")
        self.log.delete("1.0", "end")
        self.log.configure(state="disabled")

    # ---------- 状态与地址 ----------
    def _render_addresses(self):
        for w in self.addr_box.winfo_children():
            w.destroy()
        # 清掉旧二维码
        for w in getattr(self, "qr_box", tk.Frame()).winfo_children():
            w.destroy()
        if not self.addresses:
            tk.Label(self.addr_box, text="服务未启动", bg=CARD, fg=MUTED,
                     font=font(10), anchor="w").pack(fill="x", pady=(2, 0))
            tk.Label(self.qr_box, text="启动服务后这里会出现二维码", bg=CARD, fg=MUTED,
                     font=font(9)).pack(pady=4)
            return
        for url in self.addresses:
            lbl = tk.Label(self.addr_box, text=url, bg=CARD, fg=ACCENT,
                           font=(MONO, 14, "bold"), anchor="w", cursor="hand2",
                           padx=0, pady=3)
            lbl.pack(fill="x")
            lbl.bind("<Button-1>", lambda e, u=url: self._copy_url(u))
            lbl.bind("<Enter>", lambda e, w=lbl: w.configure(fg=ACCENT_HOVER))
            lbl.bind("<Leave>", lambda e, w=lbl: w.configure(fg=ACCENT))
        self._render_qr(self.addresses[0])
        tk.Label(self.addr_box, text="点击地址即可复制 · 手机扫右侧二维码直接打开",
                 bg=CARD, fg=MUTED, font=font(9), anchor="w").pack(fill="x", pady=(6, 0))

    def _render_qr(self, url):
        """生成地址二维码，供手机扫码打开媒体库。"""
        if not (_HAS_PIL and _HAS_QR):
            return
        try:
            qr = qrcode.QRCode(version=1, error_correction=qrcode.constants.ERROR_CORRECT_M,
                               box_size=8, border=2)
            qr.add_data(url)
            qr.make(fit=True)
            img = qr.make_image(fill_color="#22d3ee", back_color="#0a0d13").convert("RGB")
            img = img.resize((120, 120), Image.LANCZOS)
            self._qr_photo = ImageTk.PhotoImage(img)
            tk.Label(self.qr_box, image=self._qr_photo, bg=CARD, bd=0).pack(pady=2)
        except Exception:
            try:
                tk.Label(self.qr_box, text="二维码生成失败", bg=CARD, fg=MUTED,
                         font=font(9)).pack(pady=4)
            except Exception:
                pass

    def _copy_url(self, url):
        self.root.clipboard_clear()
        self.root.clipboard_append(url)
        self.root.update_idletasks()
        self._show_toast(f"已复制  {url}")
        self._append_log(f"[控制台] 已复制到剪贴板：{url}")

    def _show_toast(self, text):
        self.lbl_toast.configure(text=text)
        if self._toast_job:
            try:
                self.root.after_cancel(self._toast_job)
            except Exception:
                pass
        self._toast_job = self.root.after(1800, lambda: self.lbl_toast.configure(text=""))

    def _update_status_running(self):
        if not self.addresses:
            return
        primary = self.addresses[0]
        short = primary.split("//", 1)[-1]
        self.lbl_dot.configure(fg=ACCENT)
        self.lbl_status.configure(text=f"运行中 · {short}", fg=ACCENT)
        self.root.title(f"MediaShare 控制台 · 运行中 {short}")

    def _set_running(self, running):
        if running:
            self.btn_start.set_kind("danger", "■ 停止服务")
            self.lbl_dot.configure(fg=WARN)
            self.lbl_status.configure(text="启动中…", fg=WARN)
            self.root.title("MediaShare 控制台 · 启动中")
            self.ent_port.configure(state="disabled")
            self.ent_title.configure(state="disabled")
            # 目录管理按钮保持可用：添加/删除/排序只改 config，运行中也可操作，
            # 改完提示「重启服务生效」，不再因为禁用按钮而「点了没反应」。
        else:
            self.btn_start.set_kind("primary", "▶ 启动服务")
            self.lbl_dot.configure(fg="#3f4c5c")
            self.lbl_status.configure(text="已停止", fg=MUTED)
            self.root.title("MediaShare 控制台")
            self.ent_port.configure(state="normal")
            self.ent_title.configure(state="normal")

    # ---------- 关窗 ----------
    def _on_close(self):
        try:
            if self.proc is not None:
                self.stop_service()
            # 兜底：再杀一次端口上的残留，防止关窗后服务器变孤儿
            port = self._read_port()
            if port:
                self._kill_port_holders(port)
        finally:
            self.root.destroy()

    def load_cfg_into_ui(self, cfg):
        if "port" in cfg:
            self.var_port.set(str(cfg["port"]))
        if "title" in cfg:
            self.var_title.set(str(cfg["title"]))


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    selftest = "--selftest" in argv

    root = tk.Tk()
    app = ConsoleApp(root, selftest=selftest)
    root.update_idletasks()
    dark_titlebar(root)
    if selftest:
        root.update_idletasks()
        emit("SELFTEST OK")
        root.destroy()
        return 0

    root.mainloop()
    return 0


def _crash(exc_text):
    """pythonw 没有控制台，异常同时写日志文件。"""
    path = os.path.join(HERE, "控制台错误.log")
    try:
        with open(path, "w", encoding="utf-8") as f:
            f.write(exc_text)
    except Exception:
        pass
    emit(exc_text)
    try:
        r = tk.Tk()
        r.withdraw()
        MessageDialog(r, "控制台启动失败",
                      f"{exc_text.strip().splitlines()[-1]}\n\n详细信息已写入 控制台错误.log")
        r.destroy()
    except Exception:
        pass


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except Exception:
        _crash(traceback.format_exc())
        sys.exit(1)
