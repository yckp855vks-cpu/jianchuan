#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
公网隧道看护：启动 cloudflared 快速隧道，盯着输出，
一出现 trycloudflare.com 网址就大字展示、写进 公网地址.txt、复制到剪贴板。
"""
import json
import os
import re
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
CFD = os.path.join(os.path.expandvars("%USERPROFILE%"), "Documents",
                   "cloudflared-windows-amd64.exe")
URL_RE = re.compile(r"https://[a-zA-Z0-9-]+\.trycloudflare\.com")
URL_FILE = os.path.join(ROOT, "公网地址.txt")


def read_password():
    try:
        with open(os.path.join(ROOT, "data", "config.json"), encoding="utf-8") as fh:
            return (json.load(fh).get("password") or "").strip()
    except Exception:  # noqa: BLE001
        return ""


def copy_clipboard(text):
    try:
        subprocess.run(
            ["powershell", "-NoProfile", "-Command", "Set-Clipboard -Value '" + text + "'"],
            capture_output=True, timeout=8,
        )
        return True
    except Exception:  # noqa: BLE001
        return False


def announce(url):
    pw = read_password()
    bar = "  " + "=" * 64
    print("\n" + bar)
    print("   公网地址（手机在外面用浏览器打开这个）：")
    print("   >>>  %s" % url)
    if pw:
        print("   访问密码：%s" % pw)
    print(bar)
    print("   网址已保存到同目录的「公网地址.txt」，关掉本窗口公网即断开。\n")
    try:
        with open(URL_FILE, "w", encoding="utf-8") as fh:
            fh.write("公网地址：" + url + "\n访问密码：" + (pw or "见启动窗口") + "\n")
    except OSError:
        pass
    if copy_clipboard(url):
        print("   （网址已自动复制到剪贴板）\n")


def main():
    if not os.path.isfile(CFD):
        print("[X] 没找到 cloudflared：%s" % CFD)
        print("    如果穿透程序在别的位置，请用记事本修改本文件第 12 行的路径。")
        return 1

    print("  正在建立公网隧道，一般 3~10 秒。下面是 cloudflared 的日志……\n")
    proc = subprocess.Popen(
        [CFD, "tunnel", "--url", "http://127.0.0.1:8787", "--no-autoupdate"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", bufsize=1,
    )
    url = None
    try:
        for line in proc.stdout:
            clean = line.rstrip()
            if not clean:
                continue
            print("    " + clean)
            if url is None:
                m = URL_RE.search(clean)
                if m:
                    url = m.group(0)
                    announce(url)
    except KeyboardInterrupt:
        print("\n  手动停止。")
    finally:
        try:
            proc.terminate()
        except Exception:  # noqa: BLE001
            pass
    return 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # noqa: BLE001
        pass
    sys.exit(main())
