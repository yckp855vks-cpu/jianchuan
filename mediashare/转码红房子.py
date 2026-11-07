# -*- coding: utf-8 -*-
"""批量把「树下有片红房子」4K HEVC 转成 1080p H.264（NVENC 加速）。

用法：python 转码红房子.py
会跳过已经转好的集数（输出目录里已存在的同名 [1080P].mp4）。
"""
import os
import re
import subprocess
import sys
import time

SRC_DIR = r"C:\下载\树下有片红房子 (2025)(1)"
OUT_DIR = r"G:\转码1080P"
FFMPEG = r"C:\ffmpeg\bin\ffmpeg.exe"

# 按集号排序
def ep_key(name):
    m = re.search(r"S01E(\d+)", name)
    return int(m.group(1)) if m else 999


def main():
    if not os.path.isdir(SRC_DIR):
        print("源目录不存在:", SRC_DIR)
        sys.exit(1)
    os.makedirs(OUT_DIR, exist_ok=True)

    files = sorted([f for f in os.listdir(SRC_DIR) if f.lower().endswith(".mkv")], key=ep_key)
    print("共 %d 集待处理" % len(files))

    done = 0
    for name in files:
        ep = re.search(r"S01E(\d+)", name)
        epno = ep.group(1) if ep else "00"
        dst = os.path.join(OUT_DIR, "树下有片红房子 S01E%s [1080P].mp4" % epno)
        if os.path.exists(dst) and os.path.getsize(dst) > 1024 * 1024:
            print("[跳过] 已存在:", os.path.basename(dst))
            continue
        if os.path.exists(dst):
            os.remove(dst)

        src = os.path.join(SRC_DIR, name)
        print("[%d/%d] 转码 %s ..." % (done + 1, len(files), name))
        t0 = time.time()
        cmd = [
            FFMPEG, "-y", "-hide_banner", "-loglevel", "error",
            "-hwaccel", "cuda", "-hwaccel_output_format", "cuda",
            "-i", src,
            "-map", "0:v:0", "-map", "0:a:0",
            "-vf", "scale_cuda=-2:1080:interp_algo=lanczos",
            "-c:v", "h264_nvenc", "-rc", "vbr", "-cq", "23", "-b:v", "0",
            "-bf", "2", "-preset", "p4", "-profile:v", "high",
            "-c:a", "aac", "-b:a", "160k",
            "-movflags", "+faststart", "-sn", dst,
        ]
        r = subprocess.run(cmd, capture_output=True)
        if r.returncode == 0 and os.path.exists(dst):
            cost = time.time() - t0
            print("    完成 %.0f 秒, %.0f MB" % (cost, os.path.getsize(dst) / 1048576))
        else:
            err = r.stderr.decode("utf-8", "replace")[-400:]
            print("    失败:", err.replace("\n", " ")[:300])
            if os.path.exists(dst):
                os.remove(dst)
        done += 1

    print("全部完成。输出目录:", OUT_DIR)


if __name__ == "__main__":
    main()
