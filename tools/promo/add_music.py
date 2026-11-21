# -*- coding: utf-8 -*-
"""给宣传片配乐：把 FLAC 母带按音乐结构裁切 → 淡入淡出 → 响度归一 → 封装进成片。

设计要点（保证音质）：
- 音源 192 kHz / 24 bit，解码后在 48 kHz 双精度浮点域处理，全程不落 16 bit。
- 只做「裁切 + 淡入淡出 + 静态增益 + 真峰值限制」，不做均衡/压缩/降噪等破坏性处理。
- 裁切点对齐 120 BPM 的小节线（beat≈0.5013s，bar≈2.0052s，首拍偏移≈0.055s），
  结尾落在段落收束处，避免在半句中间硬切。
- 目标响度 -14 LUFS（YouTube/在线平台标准），真峰值 ≤ -1 dBTP；先测量再增益，
  只有超过峰值上限的个别瞬态会被限制器轻微压住。
- 视频流 -c:v copy，重新封装不重编码，画质零损失。

用法：
    python tools/promo/add_music.py
输入：
    dist/Smath-promo-1080p.mp4, dist/Smath-promo-vertical-30s.mp4
    C:\\Users\\33558\\Downloads\\神椿市協奏中。 - ゆりかご (Instrumental).flac
输出：
    dist/Smath-promo-1080p.mp4, dist/Smath-promo-vertical-30s.mp4（覆盖，原无声版备份到 out/promo/silent/）
    dist/SHA256SUMS-promo.txt
"""
import hashlib
import json
import os
import re
import shutil
import subprocess

ROOT = r"C:\Users\33558\Desktop\Projects\Apps\Smath"
DIST = os.path.join(ROOT, "dist")
WORK = os.path.join(ROOT, "out", "promo", "music")
FFMPEG = os.path.join(ROOT, "tools", "ffdl", "extract", "imageio_ffmpeg", "binaries",
                      "ffmpeg-win-x86_64-v9.0.2.exe")
TRACK = r"C:\Users\33558\Downloads\神椿市協奏中。 - ゆりかご (Instrumental).flac"

TARGET_I = -14.0      # LUFS
TP_LIMIT = 0.87       # 线性幅度 ≈ -1.2 dBFS，给 AAC 编码的过采样峰值留余量
BITRATE = "320k"

# 分镜与配乐的结构对应（见 docs/promotion.md）
# 主片：0-7s 痛点（音乐在安静的段落里淡入）→ 16s 副歌进入 → 34s 段落停顿 → 36s 副歌
#       → 52s 段落收束，随落版淡出
# 竖屏：92.0s 段落强起（硬切点前 20ms）→ 18s 高潮段 → 落版淡出
JOBS = [
    {
        "name": "main",
        "video": "Smath-promo-1080p.mp4",
        "start": 24.117,      # 小节线（0.055 + 12 × 2.0052）
        "fade_in": 0.5,
        "fade_out": 2.4,
        "note": "副歌 16s 进入 / 34s 停顿 / 落版随段落收束淡出",
    },
    {
        "name": "vertical",
        "video": "Smath-promo-vertical-30s.mp4",
        "start": 91.98,       # 段落硬起前 20ms
        "fade_in": 0.12,
        "fade_out": 2.0,
        "note": "开场即段落强起，高潮段覆盖落版",
    },
]


def run(args):
    p = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if p.returncode != 0:
        raise RuntimeError("ffmpeg failed (%d):\n%s\n%s" % (p.returncode, " ".join(args), p.stderr[-4000:]))
    return p.stderr


def probe_duration(path):
    err = run([FFMPEG, "-hide_banner", "-i", path, "-f", "null", "-"])
    m = re.search(r"Duration: (\d+):(\d+):(\d+\.\d+)", err)
    if not m:
        raise RuntimeError("no duration for " + path)
    h, mi, s = m.groups()
    return int(h) * 3600 + int(mi) * 60 + float(s)


def measure(path):
    """返回 (integrated LUFS, true peak dBTP)。"""
    err = run([FFMPEG, "-hide_banner", "-nostats", "-i", path,
               "-af", "ebur128=peak=true", "-f", "null", "-"])
    tail = err[err.rfind("Summary:"):] if "Summary:" in err else err
    i = re.search(r"I:\s+(-?\d+\.\d+)\s+LUFS", tail)
    tp = re.search(r"True peak:\s*\n\s*Peak:\s+(-?\d+\.\d+)\s+dBFS", tail)
    if not i or not tp:
        raise RuntimeError("unparsable loudness report:\n" + tail[-2000:])
    return float(i.group(1)), float(tp.group(1))


def stream_md5(path, spec):
    err = run([FFMPEG, "-hide_banner", "-loglevel", "info", "-i", path,
               "-map", spec, "-c", "copy", "-f", "md5", "-"])
    m = re.search(r"MD5=([0-9a-f]{32})", err)
    return m.group(1) if m else "?"


def render_audio(job, out_path, duration, gain_db):
    fade_out_start = max(0.0, duration - job["fade_out"])
    chain = ",".join([
        "aresample=48000",
        "aformat=sample_fmts=flt:channel_layouts=stereo",
        "volume=%.3fdB" % gain_db,
        "afade=t=in:st=0:d=%.3f" % job["fade_in"],
        "afade=t=out:st=%.3f:d=%.3f" % (fade_out_start, job["fade_out"]),
        "alimiter=limit=%.3f:level=false:attack=5:release=80" % TP_LIMIT,
    ])
    run([FFMPEG, "-hide_banner", "-loglevel", "error", "-y",
         "-ss", "%.3f" % job["start"], "-t", "%.3f" % duration,
         "-i", TRACK, "-vn", "-ac", "2", "-ar", "48000",
         "-af", chain, "-c:a", "aac", "-b:a", BITRATE, "-movflags", "+faststart",
         out_path])
    return chain


def mux(video_in, audio_in, out_path):
    run([FFMPEG, "-hide_banner", "-loglevel", "error", "-y",
         "-i", video_in, "-i", audio_in,
         "-map", "0:v:0", "-map", "1:a:0",
         "-c:v", "copy", "-c:a", "copy",
         "-shortest", "-movflags", "+faststart", out_path])


def main():
    os.makedirs(WORK, exist_ok=True)
    silent_dir = os.path.join(WORK, "silent")
    os.makedirs(silent_dir, exist_ok=True)
    if not os.path.isfile(TRACK):
        raise SystemExit("音源不存在: " + TRACK)

    report = []
    for job in JOBS:
        src = os.path.join(DIST, job["video"])
        if not os.path.isfile(src):
            raise SystemExit("成片不存在: " + src)
        duration = probe_duration(src)
        vmd5_before = stream_md5(src, "0:v:0")

        work_mp4 = os.path.join(WORK, job["name"] + ".m4a")
        # 先按窗口裁出来测响度，再决定增益（避免用整首歌的响度）
        raw_wav = os.path.join(WORK, job["name"] + "_raw.wav")
        run([FFMPEG, "-hide_banner", "-loglevel", "error", "-y",
             "-ss", "%.3f" % job["start"], "-t", "%.3f" % duration,
             "-i", TRACK, "-vn", "-ac", "2", "-ar", "48000",
             "-c:a", "pcm_f32le", raw_wav])
        i0, tp0 = measure(raw_wav)
        gain = TARGET_I - i0

        # 渲染 + 迭代校正响度（限制器会吃掉一点，最多两轮）
        for attempt in range(3):
            render_audio(job, work_mp4, duration, gain)
            got_i, got_tp = measure(work_mp4)
            if abs(got_i - TARGET_I) <= 0.15:
                break
            gain += TARGET_I - got_i

        backup = os.path.join(silent_dir, job["video"])
        if not os.path.isfile(backup):
            shutil.copy2(src, backup)

        tmp_out = os.path.join(DIST, job["video"] + ".tmp.mp4")
        mux(src, work_mp4, tmp_out)
        vmd5_after = stream_md5(tmp_out, "0:v:0")
        os.replace(tmp_out, src)

        out_dur = probe_duration(src)
        report.append({
            "name": job["name"],
            "file": src,
            "duration": round(out_dur, 3),
            "window": "%.3f–%.3f s" % (job["start"], job["start"] + duration),
            "gain_db": round(gain, 2),
            "source_I_LUFS": round(i0, 2),
            "final_I_LUFS": round(got_i, 2),
            "final_true_peak_dBTP": round(got_tp, 2),
            "video_stream_unchanged": vmd5_before == vmd5_after,
            "fade_in_s": job["fade_in"],
            "fade_out_s": job["fade_out"],
            "note": job["note"],
            "size": os.path.getsize(src),
            "sha256": hashlib.sha256(open(src, "rb").read()).hexdigest(),
        })

    sums = os.path.join(DIST, "SHA256SUMS-promo.txt")
    with open(sums, "w", encoding="utf-8") as f:
        for r in report:
            f.write("%s  %s\n" % (r["sha256"], os.path.basename(r["file"])))

    print(json.dumps(report, ensure_ascii=False, indent=2))
    print("\nSHA256SUMS-promo.txt 已更新:", sums)


if __name__ == "__main__":
    main()