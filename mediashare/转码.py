#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把电脑上的 4K / 10bit 片源转成 1080p H.264，放进共享目录给投影仪看。

为什么需要转码：Q3 Neo 这台投影仪的 MediaCodec 视频硬解整个是坏的，
H.264 和 HEVC 走硬解都直接失败，只能软解；而 4K Main10 软解扛不住，
所以 4K 的片子必须先降到 1080p。

用法:
    python 转码.py              # 按 config.json 里的 roots 处理
    python 转码.py "G:\\某个目录" # 处理指定目录
"""

import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "config.json")
FFMPEG = r"C:\ffmpeg\bin\ffmpeg.exe"
FFPROBE = r"C:\ffmpeg\bin\ffprobe.exe"
OUT_DIR = r"G:\转码1080P"

VIDEO_EXT = (".mp4", ".mkv", ".avi", ".mov", ".ts", ".m2ts", ".flv", ".wmv", ".rmvb", ".webm")


def load_roots():
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            cfg = json.load(f)
    except Exception as exc:
        print("读 config.json 失败:", exc)
        return []
    roots = []
    for item in cfg.get("roots") or []:
        if isinstance(item, dict) and item.get("path"):
            roots.append(item["path"])
        elif isinstance(item, str):
            roots.append(item)
    if not roots and cfg.get("root"):
        roots.append(cfg["root"])
    return roots


def probe(path):
    """返回 (宽, 高, 编码, profile)，探测失败返回 None。"""
    try:
        out = subprocess.run(
            [FFPROBE, "-v", "quiet", "-print_format", "json",
             "-select_streams", "v:0", "-show_streams", path],
            capture_output=True, timeout=60)
        data = json.loads(out.stdout.decode("utf-8", "replace"))
        st = data["streams"][0]
        return int(st.get("width") or 0), int(st.get("height") or 0), \
            (st.get("codec_name") or ""), (st.get("profile") or "")
    except Exception:
        return None


def need_transcode(w, h, codec, profile):
    if h > 1080 or w > 1920:
        return True
    if "10" in (profile or ""):      # Main10 / Main12：10bit 片子投影仪扛不住
        return True
    return False


def run(cmd):
    print("  $", " ".join(cmd[:3]), "...")
    return subprocess.run(cmd, capture_output=True)


def _has_nvenc():
    """探测这台机器有没有 NVIDIA NVENC 硬件编码器（有 GPU 就快很多）。"""
    try:
        out = subprocess.run(
            [FFMPEG, "-hide_banner", "-encoders"], capture_output=True, timeout=15)
        return b"h264_nvenc" in out.stdout
    except Exception:
        return False


def _video_encoder():
    """优先 NVENC 硬件编码，否则退回 libx264 CPU 软编。"""
    if _has_nvenc():
        # NVENC：-rc vbr -cq 23 -b:v 0（恒质量），bf 2 控制 B 帧深度。
        # 注意：h264_nvenc 不能手动指定 -level（会报 Invalid Level），让它自动选。
        return ("h264_nvenc",
                ["-rc", "vbr", "-cq", "23", "-b:v", "0", "-bf", "2",
                 "-preset", "p4", "-profile:v", "high"])
    return ("libx264",
            ["-preset", "veryfast", "-crf", "23",
             "-profile:v", "high", "-level", "4.1"])


def transcode(src, dst):
    enc, enc_args = _video_encoder()
    base = [
        FFMPEG, "-y", "-hide_banner", "-loglevel", "error",
        "-i", src,
        "-map", "0:v:0", "-map", "0:a:0",
        "-vf", "scale=-2:1080:flags=lanczos,format=yuv420p",
        "-c:v", enc,
    ] + enc_args + [
        "-c:a", "aac", "-b:a", "160k",
        "-movflags", "+faststart",
    ]
    # 先带字幕，失败就去掉字幕重来
    with_sub = base + ["-map", "0:s:0?", "-c:s", "mov_text", dst]
    r = subprocess.run(with_sub, capture_output=True)
    if r.returncode == 0 and os.path.exists(dst):
        return True
    r = subprocess.run(base + ["-sn", dst], capture_output=True)
    if r.returncode != 0:
        err = r.stderr.decode("utf-8", "replace")[-800:]
        print("    转码失败:", err.replace("\n", " ")[:400])
        if os.path.exists(dst):
            os.remove(dst)
        return False
    return True


def main():
    targets = []
    if len(sys.argv) > 1:
        targets.append(sys.argv[1])
    else:
        targets = load_roots()

    if not os.path.exists(FFMPEG):
        print("找不到 ffmpeg:", FFMPEG)
        sys.exit(1)

    os.makedirs(OUT_DIR, exist_ok=True)
    queue = []
    for root in targets:
        if not os.path.isdir(root):
            continue
        for dirpath, _, files in os.walk(root):
            if OUT_DIR.lower() in dirpath.lower():
                continue
            for name in files:
                if name.lower().endswith(VIDEO_EXT):
                    queue.append(os.path.join(dirpath, name))

    print("扫描到 %d 个视频文件，开始逐个检查…" % len(queue))
    done = 0
    for src in queue:
        info = probe(src)
        if not info:
            print("  跳过（探测失败）:", os.path.basename(src))
            continue
        w, h, codec, profile = info
        if not need_transcode(w, h, codec, profile):
            continue
        base = os.path.splitext(os.path.basename(src))[0]
        # 文件名里的压制组标签清一清，投影仪上看着清爽
        base = re.sub(r"\[[^\]]*\]", " ", base)
        base = re.sub(r"\s+", " ", base).strip(" -")
        dst = os.path.join(OUT_DIR, base + " [1080P].mp4")
        if os.path.exists(dst) and os.path.getsize(dst) > 1024 * 1024:
            print("  已存在，跳过:", os.path.basename(dst))
            continue
        if os.path.exists(dst):
            os.remove(dst)     # 上次转码中断留下的残file，重来
        print("[%d] %s  (%dx%d %s %s)" % (done + 1, os.path.basename(src), w, h, codec, profile))
        t0 = time.time()
        if transcode(src, dst):
            cost = time.time() - t0
            print("    完成 -> %s  (%.0f 秒, %.0f MB)" % (
                os.path.basename(dst), cost, os.path.getsize(dst) / 1048576))
        done += 1
    print("全部完成，输出目录:", OUT_DIR)


if __name__ == "__main__":
    main()
