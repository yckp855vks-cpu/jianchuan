# -*- coding: utf-8 -*-
"""MediaShare 打包入口：一个 EXE 同时承载 GUI 控制台和 HTTP 服务器。

PyInstaller 打包后：
    MediaShare.exe                  → 启动 GUI 控制台
    MediaShare.exe --run-server     → 启动 HTTP 服务器（由控制台作为子进程拉起）
    MediaShare.exe --selftest       → GUI 自检
"""

import os
import sys


def _fix_paths():
    """frozen 模式下把 HERE 指向 EXE 所在目录（可写），资源从 _MEIPASS 读。"""
    if getattr(sys, "frozen", False):
        exe_dir = os.path.dirname(os.path.abspath(sys.executable))
        # 让子模块的 HERE 指向 EXE 目录，config.json 读写在那边
        os.environ.setdefault("MEDIASHARE_EXE_DIR", exe_dir)
        os.environ.setdefault("MEDIASHARE_BUNDLE_DIR", sys._MEIPASS)


def main():
    _fix_paths()

    # ---- 服务器模式 ----
    if "--run-server" in sys.argv:
        sys.argv.remove("--run-server")
        # --windowed 打包后 sys.stdout 是 None。尝试接到管道（父进程用 PIPE 捕获），
        # 失败就退到 devnull——反正控制台靠端口轮询检测状态，不依赖 stdout。
        import io
        if sys.stdout is None:
            try:
                sys.stdout = io.TextIOWrapper(
                    open(1, "wb", buffering=0), encoding="utf-8", errors="replace", line_buffering=True)
            except OSError:
                sys.stdout = io.TextIOWrapper(open(os.devnull, "wb"), encoding="utf-8")
        if sys.stderr is None:
            try:
                sys.stderr = io.TextIOWrapper(
                    open(2, "wb", buffering=0), encoding="utf-8", errors="replace", line_buffering=True)
            except OSError:
                sys.stderr = io.TextIOWrapper(open(os.devnull, "wb"), encoding="utf-8")
        import server
        server.main()
        return

    # ---- GUI 控制台模式（默认） ----
    import 控制台
    控制台.main()


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception:
        import traceback
        err = traceback.format_exc()
        # 写日志方便排查
        try:
            log = os.path.join(
                os.environ.get("MEDIASHARE_EXE_DIR", os.path.dirname(os.path.abspath(__file__))),
                "MediaShare错误.log",
            )
            with open(log, "w", encoding="utf-8") as f:
                f.write(err)
        except Exception:
            pass
        print(err)
        sys.exit(1)
