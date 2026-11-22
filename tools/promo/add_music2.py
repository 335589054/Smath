"""给 Smath 2.0 程序化宣传片配乐（复用同一首 BGM）。

取用区间 91.98s 起（段落硬起，落在小节线上），长 48.12s，与画面同长；
响度归一到 -14 LUFS / 真峰值 ≤ -1.2 dBTP，AAC 320k，视频流不重编码。

用法：
    python tools/promo/add_music2.py [--video dist/Smath-promo2-1080p.mp4]
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FFMPEG = os.path.join(ROOT, "tools", "ffdl", "extract", "imageio_ffmpeg", "binaries",
                      "ffmpeg-win-x86_64-v9.0.2.exe")
TRACK = r"C:\Users\33558\Downloads\神椿市協奏中。 - ゆりかご (Instrumental).flac"
START = 91.98
DURATION = 48.1248
TARGET_I = -14.0
TP_LIMIT = 0.87  # ≈ -1.2 dBFS
BITRATE = "320k"
FADE_IN = 0.35
FADE_OUT = 2.8


def run(cmd):
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    res.stdout = (res.stdout or b"").decode("utf-8", "replace")
    res.stderr = (res.stderr or b"").decode("utf-8", "replace")
    return res


def measure(path):
    res = run([FFMPEG, "-hide_banner", "-nostats", "-i", path,
               "-af", "ebur128=peak=true", "-f", "null", "-"])
    text = res.stderr or ""
    block = text[text.rfind("Summary:"):] if "Summary:" in text else text
    i = re.search(r"I:\s*(-?\d+(?:\.\d+)?)\s*LUFS", block)
    tp = re.search(r"Peak:\s*(-?\d+(?:\.\d+)?)\s*dBFS", block)
    if i is None:
        print("loudness parse failed, tail:", block[-400:])
    return (float(i.group(1)) if i else None, float(tp.group(1)) if tp else None)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", default=os.path.join(ROOT, "dist", "Smath-promo2-1080p.mp4"))
    args = ap.parse_args()
    video = os.path.abspath(args.video)
    work = os.path.join(ROOT, "out", "promo2", "music")
    os.makedirs(work, exist_ok=True)
    wav = os.path.join(work, "window.wav")
    m4a = os.path.join(work, "music.m4a")

    # 1. 抽窗口（192k/24bit -> 48k/浮点立体声）
    res = run([FFMPEG, "-y", "-ss", str(START), "-t", str(DURATION), "-i", TRACK,
               "-af", "aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo",
               "-c:a", "pcm_f32le", wav])
    if res.returncode != 0:
        raise SystemExit(res.stderr[-800:])
    i0, tp0 = measure(wav)
    print(f"window loudness I={i0} LUFS peak={tp0} dBFS")

    # 2. 算增益（先按积分响度，再用真峰值兜底）
    gain = TARGET_I - (i0 if i0 is not None else -18.0)
    if tp0 is not None and tp0 + gain > 20 * 0.0 + (-1.2):
        cap = -1.2 - tp0
        if cap < gain:
            print(f"true peak would clip: gain {gain:.2f} -> {cap:.2f} dB")
            gain = cap

    # 3. 渲染：增益 + 淡入淡出 + 限幅
    fade_out_start = max(0.0, DURATION - FADE_OUT)
    chain = (f"volume={gain:.2f}dB,afade=t=in:st=0:d={FADE_IN},"
             f"afade=t=out:st={fade_out_start:.2f}:d={FADE_OUT},"
             f"alimiter=limit={TP_LIMIT}:level=false:attack=5:release=80")
    res = run([FFMPEG, "-y", "-i", wav, "-af", chain, "-c:a", "aac", "-b:a", BITRATE,
               "-ar", "48000", "-ac", "2", m4a])
    if res.returncode != 0:
        raise SystemExit(res.stderr[-800:])
    i1, tp1 = measure(m4a)
    print(f"music loudness I={i1} LUFS peak={tp1} dBTP gain={gain:+.2f} dB")

    # 4. 封装（视频流直接复制）
    tmp = video + ".muxed.mp4"
    res = run([FFMPEG, "-y", "-i", video, "-i", m4a, "-c:v", "copy", "-c:a", "copy",
               "-shortest", "-movflags", "+faststart", tmp])
    if res.returncode != 0:
        raise SystemExit(res.stderr[-800:])
    os.replace(tmp, video)

    probe = run([os.path.join(os.path.dirname(FFMPEG), "ffmpeg-win-x86_64-v9.0.2.exe"),
                 "-hide_banner", "-i", video]).stderr
    print(probe.strip().splitlines()[-3:])
    print(json.dumps({"video": video, "size": os.path.getsize(video),
                      "lufs": i1, "true_peak": tp1, "gain_db": round(gain, 2)}, ensure_ascii=False))


if __name__ == "__main__":
    main()