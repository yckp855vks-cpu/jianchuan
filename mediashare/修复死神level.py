# -*- coding: utf-8 -*-
"""批量修复死神 27-40 合集的非标 level=50，重编码为 level 4.1（GPU 硬解友好）。
全 GPU 链路：hwaccel cuda 解码 + h264_nvenc 编码，音频 -c:a copy 不重编。
"""
import os
import re
import subprocess
import sys
import time

FFMPEG = r"C:/ffmpeg/bin/ffmpeg.exe"
SRC_DIR = r"G:/[豌豆字幕组&风之圣殿字幕组][死神 千年血战篇][27-40合集][简体][1080P][MP4]/BLEACH Sennen Kessen-hen"
DST_DIR = r"G:/转码1080P/死神27-40 level修复"

os.makedirs(DST_DIR, exist_ok=True)


def has_nvenc():
    r = subprocess.run([FFMPEG, "-hide_banner", "-encoders"], capture_output=True, text=True)
    return "h264_nvenc" in r.stdout


def fix_one(src, dst):
    base = [
        FFMPEG, "-y", "-hide_banner", "-loglevel", "error",
        "-hwaccel", "cuda", "-hwaccel_output_format", "cuda",
        "-i", src,
        "-map", "0:v:0", "-map", "0:a:0",
        "-c:v", "h264_nvenc",
        "-rc", "vbr", "-cq", "21", "-b:v", "0",
        "-bf", "2", "-preset", "p4",
        "-profile:v", "high", "-level", "4.1",
        "-c:a", "copy",
        "-movflags", "+faststart",
        dst,
    ]
    r = subprocess.run(base, capture_output=True)
    if r.returncode != 0:
        err = r.stderr.decode("utf-8", "replace")[-600:]
        return False, err.replace("\n", " ")[:300]
    return True, ""


def main():
    files = sorted([f for f in os.listdir(SRC_DIR) if f.endswith(".mp4")])
    if not files:
        print("未找到 mp4 文件")
        return

    # 27 已经手动测试转好，跳过
    done_names = {"27"}

    print("待处理 %d 集：" % len(files))
    for i, name in enumerate(files, 1):
        m = re.search(r"\[(\d+)\]\[GB\]", name)
        ep = m.group(1) if m else "?"
        dst = os.path.join(DST_DIR, "死神 S01E%s [1080P].mp4" % ep)
        status = "跳过(已测)" if ep in done_names else ""
        print("  [%02d/%d] E%s %s %s" % (i, len(files), ep, name[:20], status))

    print("\n开始转码（跳过 E27）...")
    t_all = time.time()
    ok, fail = 0, 0
    for i, name in enumerate(files, 1):
        m = re.search(r"\[(\d+)\]\[GB\]", name)
        ep = m.group(1) if m else "?"
        if ep in done_names:
            continue
        src = os.path.join(SRC_DIR, name)
        dst = os.path.join(DST_DIR, "死神 S01E%s [1080P].mp4" % ep)
        if os.path.exists(dst) and os.path.getsize(dst) > 0:
            print("[E%s] 已存在，跳过" % ep)
            continue
        t0 = time.time()
        print("[E%s] 转码中... " % ep, flush=True)
        success, err = fix_one(src, dst)
        dt = time.time() - t0
        if success:
            ok += 1
            print("      完成，耗时 %.0f 秒" % dt, flush=True)
        else:
            fail += 1
            print("      失败：%s" % err, flush=True)
            if os.path.exists(dst):
                os.remove(dst)

    print("\n===== 完成 =====")
    print("成功 %d 集，失败 %d 集，总耗时 %.0f 秒" % (ok, fail, time.time() - t_all))


if __name__ == "__main__":
    main()
