# -*- coding: utf-8 -*-
"""竖屏 30s 短片：1080x1920，用于抖音 / 小红书 / 视频号。"""
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_promo as mp
from PIL import Image, ImageDraw, ImageFilter

W, H = 1080, 1920
PH_W, PH_H, RAD = 657, 1430, 34
PX, PY = 211, 120
mp.FRAME_POS = (PX, PY)

ASSETS = mp.ASSETS
SCENES = mp.SCENES
FFMPEG = mp.FFMPEG


def build():
    meta = {}
    # 1 痛点
    bg = Image.new("RGB", (W, H), (0, 0, 0))
    bg.save(os.path.join(ASSETS, "P1_bg.png"))
    formula = "f(x)=x\u00b3\u22123x\u00b2+2x\u22121  \u21d2  f\u2032(x)=3x\u00b2\u22126x+2  \u21d2  x=(3\u00b1\u221a3)/3"
    fl = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(fl)
    f_m = mp.font(mp.F_MATH, 76)
    d.text((40, 900), formula, font=f_m, fill=(255, 255, 255, 235))
    fl.save(os.path.join(ASSETS, "P1_formula.png"))
    a = mp.text_layer((W, H), [("\u5df2\u5230\u5f53\u524d\u65f6\u6bb5\u989d\u5ea6\u4e0a\u9650", mp.font(mp.F_BOLD, 52), 80, 1120, 920)], mp.WHITE)
    b = mp.text_layer((W, H), [("\u4f60\u6709\u6ca1\u6709\u9047\u5230\u8fc7\uff0cAI \u8bb2\u5230\u4e00\u534a\u5c31\u505c\u4e86\uff1f", mp.font(mp.F_REG, 32), 80, 1210, 920)],
                      (150, 150, 150))
    a.save(os.path.join(ASSETS, "P1_textA.png"))
    b.save(os.path.join(ASSETS, "P1_textB.png"))
    meta[1] = dict(kind="still", dur=4.0, bg=(0, 0, 0),
                   layers=["P1_formula.png", "P1_textA.png", "P1_textB.png"])

    # 手机部件
    mask = mp.phone_mask(PH_W, PH_H, RAD)
    mask.save(os.path.join(ASSETS, "phone_mask_v.png"))
    frame = mp.phone_frame((W, H), PH_W, PH_H, RAD)
    frame.save(os.path.join(ASSETS, "phone_frame_v.png"))

    layouts = {
        2: dict(src=2, bg=mp.LIGHT, head=mp.INK, body=(75, 85, 99), dur=5.0, clip="02-preset.mp4", ss=0.0),
        4: dict(src=4, bg=mp.LIGHT, head=mp.INK, body=(75, 85, 99), dur=8.0, clip="04-formula-scroll.mp4", ss=1.0),
        5: dict(src=5, bg=mp.LIGHT, head=mp.INK, body=(75, 85, 99), dur=6.0, clip="05-drawing.mp4", ss=1.0),
        6: dict(src=6, bg=mp.LIGHT, head=mp.INK, body=(75, 85, 99), dur=4.0, clip="06-calculator.mp4", ss=0.5),
    }
    for idx, cfg in layouts.items():
        head, narr = mp.SCENE_TEXT[cfg["src"]]
        Image.new("RGB", (W, H), cfg["bg"]).save(os.path.join(ASSETS, "P%d_bg.png" % idx))
        a = mp.text_layer((W, H), [(head, mp.font(mp.F_BOLD, 58), 80, 1600, 920)], cfg["head"])
        b = mp.text_layer((W, H), [(narr, mp.font(mp.F_REG, 34), 80, 1704, 920)], cfg["body"])
        a.save(os.path.join(ASSETS, "P%d_textA.png" % idx))
        b.save(os.path.join(ASSETS, "P%d_textB.png" % idx))
        meta[idx] = dict(kind="phone", dur=cfg["dur"], bg=cfg["bg"], clip=cfg["clip"],
                         ss=cfg["ss"])

    # 8 落版
    Image.new("RGB", (W, H), mp.BRAND).save(os.path.join(ASSETS, "P8_bg.png"))
    word = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    dw = ImageDraw.Draw(word)
    fw = mp.font(mp.F_LATIN, 150)
    ww = dw.textlength("Smath", font=fw)
    dw.text(((W - ww) / 2, 760), "Smath", font=fw, fill=mp.WHITE)
    word.save(os.path.join(ASSETS, "P8_word.png"))
    sub = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ds = ImageDraw.Draw(sub)
    f1 = mp.font(mp.F_BOLD, 40)
    t1 = "\u6df1\u5ea6\u6c42\u7d22 \u00b7 \u5bf9\u8bdd Agent"
    ds.text(((W - ds.textlength(t1, font=f1)) / 2, 1000), t1, font=f1, fill=(232, 238, 255))
    f2 = mp.font(mp.F_REG, 28)
    t2 = "\u5f00\u6e90 \u00b7 \u514d\u8d39    v1.4.0"
    ds.text(((W - ds.textlength(t2, font=f2)) / 2, 1075), t2, font=f2, fill=(196, 208, 246))
    sub.save(os.path.join(ASSETS, "P8_textA.png"))
    meta[8] = dict(kind="still", dur=3.0, bg=mp.BRAND, layers=["P8_word.png", "P8_textA.png"])
    return meta


def render(meta):
    outs = []
    for idx in sorted(meta):
        m = meta[idx]
        out = os.path.join(SCENES, "P%02d.mp4" % idx)
        if m["kind"] == "still":
            cmd = [FFMPEG, "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi",
                   "-i", "color=c=0x%02X%02X%02X:s=%dx%d:r=30:d=%.2f" % (m["bg"][0], m["bg"][1], m["bg"][2], W, H, m["dur"])]
            for layer in m["layers"]:
                cmd += ["-loop", "1", "-framerate", "30", "-t", "%.2f" % m["dur"], "-i", os.path.join(ASSETS, layer)]
            fc = ["[0:v]format=rgba,trim=duration=%.2f,setpts=PTS-STARTPTS[b0]" % m["dur"]]
            prev = "b0"
            for i in range(len(m["layers"])):
                st = [0.10, 0.45, 0.95][i] if len(m["layers"]) == 3 else [0.10, 0.65][i]
                fc.append("[%d:v]format=rgba,fade=t=in:st=%.2f:d=0.70:alpha=1[t%d]" % (i + 1, st, i))
                fc.append("[%s][t%d]overlay=0:'30*(1-min(1,max(0,(t-%.2f)/0.70)))'[c%d]" % (prev, i, st, i))
                prev = "c%d" % i
            fc.append("[%s]format=yuv420p[v]" % prev)
            cmd += ["-filter_complex", ";".join(fc), "-map", "[v]", "-t", "%.2f" % m["dur"],
                    "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
                    "-r", "30", out]
            mp.run(cmd)
        else:
            cmd = [FFMPEG, "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi",
                   "-i", "color=c=0x%02X%02X%02X:s=%dx%d:r=30:d=%.2f" % (m["bg"][0], m["bg"][1], m["bg"][2], W, H, m["dur"]),
                   "-loop", "1", "-framerate", "30", "-t", "%.2f" % m["dur"], "-i", os.path.join(ASSETS, "phone_frame_v.png"),
                   "-loop", "1", "-framerate", "30", "-t", "%.2f" % m["dur"], "-i", os.path.join(ASSETS, "phone_mask_v.png"),
                   "-ss", "%.2f" % m["ss"], "-t", "%.2f" % m["dur"], "-i", os.path.join(mp.FOOT, m["clip"]),
                   "-loop", "1", "-framerate", "30", "-t", "%.2f" % m["dur"], "-i", os.path.join(ASSETS, "P%d_textA.png" % idx),
                   "-loop", "1", "-framerate", "30", "-t", "%.2f" % m["dur"], "-i", os.path.join(ASSETS, "P%d_textB.png" % idx)]
            fc = [
                "[0:v]format=rgba,trim=duration=%.2f,setpts=PTS-STARTPTS[bg]" % m["dur"],
                "[1:v]format=rgba,trim=duration=%.2f,setpts=PTS-STARTPTS[fr]" % m["dur"],
                "[2:v]format=gray,trim=duration=%.2f,setpts=PTS-STARTPTS[mk]" % m["dur"],
                "[3:v]scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,fps=30,format=rgba[ph]" % (PH_W, PH_H, PH_W, PH_H),
                "[ph][mk]alphamerge[phm]",
                "[bg][fr]overlay=0:0[b1]",
                "[b1][phm]overlay=%d:%d[b2]" % (PX, PY),
                "[4:v]format=rgba,fade=t=in:st=0.15:d=0.65:alpha=1[ta]",
                "[5:v]format=rgba,fade=t=in:st=0.75:d=0.65:alpha=1[tb]",
                "[b2][ta]overlay=0:'30*(1-min(1,max(0,(t-0.15)/0.70)))'[b3]",
                "[b3][tb]overlay=0:'30*(1-min(1,max(0,(t-0.75)/0.70)))',format=yuv420p[v]",
            ]
            cmd += ["-filter_complex", ";".join(fc), "-map", "[v]", "-t", "%.2f" % m["dur"],
                    "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
                    "-r", "30", out]
            mp.run(cmd)
        outs.append(out)
        print("v-scene", idx, "ok")
    return outs


if __name__ == "__main__":
    meta = build()
    outs = render(meta)
    mp.concat(outs, os.path.join(mp.DIST, "Smath-promo-vertical-30s.mp4"))