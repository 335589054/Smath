# -*- coding: utf-8 -*-
"""Smath 宣传片合成：生成舞台素材并用 ffmpeg 渲染分镜、拼接成片。

用法： python make_promo.py
输入： out/footage/*.png|mp4
输出： out/promo/assets/*.png, out/promo/scenes/*.mp4, dist/Smath-promo-53s-1080p.mp4,
      dist/Smath-promo-vertical-30s.mp4
"""
import os
import subprocess
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = r"C:\Users\33558\Desktop\Projects\Apps\Smath"
FOOT = os.path.join(ROOT, "out", "footage")
BASE = os.path.join(ROOT, "out", "promo")
ASSETS = os.path.join(BASE, "assets")
SCENES = os.path.join(BASE, "scenes")
DIST = os.path.join(ROOT, "dist")
FFMPEG = os.path.join(ROOT, "tools", "ffdl", "extract", "imageio_ffmpeg", "binaries",
                      "ffmpeg-win-x86_64-v9.0.2.exe")

F_BOLD = r"C:\Windows\Fonts\msyhbd.ttc"
F_REG = r"C:\Windows\Fonts\msyh.ttc"
F_LATIN = r"C:\Windows\Fonts\seguisb.ttf"
F_MATH = r"C:\Windows\Fonts\cambria.ttc"

BRAND = (77, 107, 254)
LIGHT = (247, 248, 250)
DARK = (15, 17, 21)
INK = (17, 24, 39)
MUTED = (107, 114, 128)
WHITE = (255, 255, 255)
MUTED_DARK = (152, 161, 176)

for d in (ASSETS, SCENES, DIST):
    os.makedirs(d, exist_ok=True)


def font(path, size, index=0):
    return ImageFont.truetype(path, size, index=index)


PUNCT = "，。；：、？！）】"

def wrap(draw, text, fnt, max_w):
    lines, cur = [], ""
    for ch in text:
        trial = cur + ch
        if draw.textlength(trial, font=fnt) <= max_w or not cur:
            cur = trial
        elif ch in PUNCT:
            # 标点不单独换行：贴在前一行末尾（允许极小的视觉溢出）
            lines.append(cur + ch)
            cur = ""
        else:
            lines.append(cur)
            cur = ch
    if cur:
        lines.append(cur)
    return lines


def text_layer(size, blocks, color):
    """blocks: [(text, font, x, y, max_w)] -> 透明图层"""
    img = Image.new("RGBA", size, (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for text, fnt, x, y, max_w in blocks:
        cy = y
        for line in wrap(d, text, fnt, max_w):
            d.text((x, cy), line, font=fnt, fill=color)
            cy += int(fnt.size * 1.45)
    return img


# ---------------------------------------------------------------- 通用部件
def phone_frame(size, w, h, radius):
    """手机外壳投影 + 描边，覆盖整张画布，手机区域左上角在 (x, y)。"""
    img = Image.new("RGBA", size, (0, 0, 0, 0))
    x, y = FRAME_POS
    shadow = Image.new("RGBA", size, (0, 0, 0, 0))
    ds = ImageDraw.Draw(shadow)
    ds.rounded_rectangle([x + 2, y + 26, x + w + 2, y + h + 30], radius=radius + 4,
                         fill=(15, 23, 42, 92))
    shadow = shadow.filter(ImageFilter.GaussianBlur(26))
    img = Image.alpha_composite(img, shadow)
    frame = Image.new("RGBA", size, (0, 0, 0, 0))
    df = ImageDraw.Draw(frame)
    df.rounded_rectangle([x - 2, y - 2, x + w + 2, y + h + 2], radius=radius + 2,
                         fill=(255, 255, 255, 255))
    img = Image.alpha_composite(img, frame)
    return img


def phone_mask(w, h, radius):
    m = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([0, 0, w - 1, h - 1], radius=radius, fill=255)
    return m


# ---------------------------------------------------------------- 场景定义
# kind: 'still' | 'phone'
# still 相关：full（整帧静帧）或元素图层
SCENE_TEXT = {
    1: ("已到当前时段额度上限", "你有没有遇到过，AI 讲到一半就停了？"),
    2: ("先选讲法，再讲题", "数学、英语、知识点整理，学段与解答风格都能调。"),
    3: ("先思考，再解答", "输入一道高中导数题，看它怎么一步步想清楚。"),
    4: ("公式不截断，可以左右滑", "长展开式一行写完，按住就能横向拖动。"),
    5: ("无限画布，随手拖动", "函数图像、几何关系，直接画给你看。"),
    6: ("复数也能算", "模型调用计算器 agent，把结果验算给自己看。"),
    7: ("你的 Key，你的模型", "DeepSeek 官方、ChatGPT、自定义；Key 只留本机。"),
}


def build_landscape():
    W, H = 1920, 1080
    pw, ph, rad = 395, 860, 30
    px, py = 1325, 110
    globals()["FRAME_POS"] = (px, py)
    meta = {}

    # ---- 1 痛点：黑底 + 被截断的公式
    bg = Image.new("RGB", (W, H), (0, 0, 0))
    bg.save(os.path.join(ASSETS, "L1_bg.png"))
    formula = "f(x)=x³−3x²+2x−1   ⇒   f′(x)=3x²−6x+2   ⇒   x=(3±√3)/3   ⇒   单调递增区间 …"
    fl = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    df = ImageDraw.Draw(fl)
    df.text((40, 300), formula, font=font(F_MATH, 116), fill=(255, 255, 255, 235))
    # 右缘渐隐，暗示“被切断”
    fade = Image.new("L", (W, 1), 0)
    for xx in range(W):
        if xx < 1500:
            fade.putpixel((xx, 0), 0)
        else:
            v = int(150 * (xx - 1500) / 420)
            fade.putpixel((xx, 0), min(150, v))
    grad = fade.resize((W, H))
    black = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    gl = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    gl.paste(black, (0, 0), grad)
    fl = Image.alpha_composite(fl, gl)
    fl.save(os.path.join(ASSETS, "L1_formula.png"))

    tA = text_layer((W, H), [("已到当前时段额度上限", font(F_BOLD, 62), 150, 610, 1500)],
                    WHITE)
    tB = text_layer((W, H), [("你有没有遇到过，AI 讲到一半就停了？", font(F_REG, 36), 150, 700, 1500)],
                    (150, 150, 150))
    tA.save(os.path.join(ASSETS, "L1_textA.png"))
    tB.save(os.path.join(ASSETS, "L1_textB.png"))
    meta[1] = dict(kind="still", dur=6.0, bg=(0, 0, 0), layers=["L1_formula.png", "L1_textA.png", "L1_textB.png"])

    # ---- 2-7 手机场景
    mask = phone_mask(pw, ph, rad)
    mask.save(os.path.join(ASSETS, "phone_mask.png"))
    frame = phone_frame((W, H), pw, ph, rad)
    frame.save(os.path.join(ASSETS, "phone_frame.png"))

    layouts = {
        2: dict(bg=LIGHT, head=INK, body=MUTED, dur=6.5, clip="02-preset.mp4", ss=0.0,
                extra=[("Smath", font(F_LATIN, 30), 150, 110, 700)]),
        3: dict(bg=LIGHT, head=INK, body=MUTED, dur=7.5, clip="03-ask-thinking.mp4", ss=1.5,
                extra=[("Smath", font(F_LATIN, 30), 150, 110, 700)]),
        4: dict(bg=LIGHT, head=INK, body=MUTED, dur=8.0, clip="04-formula-scroll.mp4", ss=1.0,
                extra=[("Smath", font(F_LATIN, 30), 150, 110, 700)]),
        5: dict(bg=LIGHT, head=INK, body=MUTED, dur=8.0, clip="05-drawing.mp4", ss=1.0,
                extra=[("Smath", font(F_LATIN, 30), 150, 110, 700)]),
        6: dict(bg=LIGHT, head=INK, body=MUTED, dur=5.0, clip="06-calculator.mp4", ss=0.5,
                extra=[("Smath", font(F_LATIN, 30), 150, 110, 700)]),
        7: dict(bg=DARK, head=WHITE, body=MUTED_DARK, dur=7.0, clip="07-settings.mp4", ss=0.5,
                extra=[("Smath", font(F_LATIN, 30), 150, 110, 700)]),
    }
    for idx, cfg in layouts.items():
        head, narr = SCENE_TEXT[idx]
        Image.new("RGB", (W, H), cfg["bg"]).save(os.path.join(ASSETS, "L%d_bg.png" % idx))
        a = text_layer((W, H), [(head, font(F_BOLD, 66), 150, 430, 900)] + cfg["extra"],
                       cfg["head"])
        b = text_layer((W, H), [(narr, font(F_REG, 34), 150, 560, 900)], cfg["body"])
        a.save(os.path.join(ASSETS, "L%d_textA.png" % idx))
        b.save(os.path.join(ASSETS, "L%d_textB.png" % idx))
        meta[idx] = dict(kind="phone", dur=cfg["dur"], bg=cfg["bg"], clip=cfg["clip"],
                         ss=cfg["ss"], px=px, py=py, pw=pw, ph=ph)

    # ---- 8 落版
    Image.new("RGB", (W, H), BRAND).save(os.path.join(ASSETS, "L8_bg.png"))
    word = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    dw = ImageDraw.Draw(word)
    fw = font(F_LATIN, 190)
    ww = dw.textlength("Smath", font=fw)
    dw.text(((W - ww) / 2, 330), "Smath", font=fw, fill=WHITE)
    word.save(os.path.join(ASSETS, "L8_word.png"))
    sub = text_layer((W, H), [("深度求索 · 对话 Agent", font(F_BOLD, 46), 0, 610, 1600)],
                     (235, 240, 255))
    d2 = ImageDraw.Draw(sub)
    f_sub = font(F_BOLD, 46)
    sw = d2.textlength("深度求索 · 对话 Agent", font=f_sub)
    sub = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d2 = ImageDraw.Draw(sub)
    d2.text(((W - sw) / 2, 600), "深度求索 · 对话 Agent", font=f_sub, fill=(232, 238, 255))
    f_tag = font(F_REG, 34)
    tag = "开源 · 免费    v1.4.0"
    tw = d2.textlength(tag, font=f_tag)
    d2.text(((W - tw) / 2, 690), tag, font=f_tag, fill=(196, 208, 246))
    sub.save(os.path.join(ASSETS, "L8_textA.png"))
    meta[8] = dict(kind="still", dur=5.0, bg=BRAND, layers=["L8_word.png", "L8_textA.png"])
    return meta


def run(cmd):
    p = subprocess.run(cmd, capture_output=True)
    if p.returncode != 0:
        sys.stderr.write(p.stderr.decode("utf-8", "ignore")[-4000:])
        raise SystemExit("ffmpeg failed: %s" % cmd[:3])


def render_landscape(meta):
    W, H = 1920, 1080
    outs = []
    for idx in sorted(meta):
        m = meta[idx]
        out = os.path.join(SCENES, "L%02d.mp4" % idx)
        if m["kind"] == "still":
            cmd = [FFMPEG, "-y", "-hide_banner", "-loglevel", "error",
                   "-f", "lavfi", "-i", "color=c=0x%02X%02X%02X:s=%dx%d:r=30:d=%.2f" % (m["bg"][0], m["bg"][1], m["bg"][2], W, H, m["dur"])]
            for i, layer in enumerate(m["layers"]):
                cmd += ["-loop", "1", "-framerate", "30", "-t", "%.2f" % m["dur"],
                        "-i", os.path.join(ASSETS, layer)]
            fc = []
            prev = "0:v"
            fc.append("[0:v]format=rgba,trim=duration=%.2f,setpts=PTS-STARTPTS[b0]" % m["dur"])
            prev = "b0"
            for i in range(len(m["layers"])):
                st = [0.15, 0.55, 1.05][i] if len(m["layers"]) == 3 else [0.15, 0.75][i]
                fc.append("[%d:v]format=rgba,fade=t=in:st=%.2f:d=0.75:alpha=1[t%d]" % (i + 1, st, i))
                fc.append("[%s][t%d]overlay=0:'34*(1-min(1,max(0,(t-%.2f)/0.75)))'[c%d]" % (prev, i, st, i))
                prev = "c%d" % i
            fc.append("[%s]format=yuv420p[v]" % prev)
            cmd += ["-filter_complex", ";".join(fc), "-map", "[v]",
                    "-t", "%.2f" % m["dur"], "-c:v", "libx264", "-preset", "medium",
                    "-crf", "18", "-pix_fmt", "yuv420p", "-r", "30", out]
            run(cmd)
        else:
            half = m["dur"] / 2.0
            cmd = [FFMPEG, "-y", "-hide_banner", "-loglevel", "error",
                   "-f", "lavfi", "-i", "color=c=0x%02X%02X%02X:s=%dx%d:r=30:d=%.2f" % (m["bg"][0], m["bg"][1], m["bg"][2], W, H, m["dur"]),
                   "-loop", "1", "-framerate", "30", "-t", "%.2f" % m["dur"], "-i", os.path.join(ASSETS, "phone_frame.png"),
                   "-loop", "1", "-framerate", "30", "-t", "%.2f" % m["dur"], "-i", os.path.join(ASSETS, "phone_mask.png"),
                   "-ss", "%.2f" % m["ss"], "-t", "%.2f" % m["dur"], "-i", os.path.join(FOOT, m["clip"]),
                   "-loop", "1", "-framerate", "30", "-t", "%.2f" % m["dur"], "-i", os.path.join(ASSETS, "L%d_textA.png" % idx),
                   "-loop", "1", "-framerate", "30", "-t", "%.2f" % m["dur"], "-i", os.path.join(ASSETS, "L%d_textB.png" % idx)]
            pw, ph = m["pw"], m["ph"]
            fc = [
                "[0:v]format=rgba,trim=duration=%.2f,setpts=PTS-STARTPTS[bg]" % m["dur"],
                "[1:v]format=rgba,trim=duration=%.2f,setpts=PTS-STARTPTS[fr]" % m["dur"],
                "[2:v]format=gray,trim=duration=%.2f,setpts=PTS-STARTPTS[mk]" % m["dur"],
                "[3:v]scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,fps=30,format=rgba[ph]" % (pw, ph, pw, ph),
                "[ph][mk]alphamerge[phm]",
                "[bg][fr]overlay=0:0[b1]",
                "[b1][phm]overlay=%d:%d[b2]" % (m["px"], m["py"]),
                "[4:v]format=rgba,fade=t=in:st=0.20:d=0.75:alpha=1[ta]",
                "[5:v]format=rgba,fade=t=in:st=0.90:d=0.75:alpha=1[tb]",
                "[b2][ta]overlay=0:'32*(1-min(1,max(0,(t-0.20)/0.75)))'[b3]",
                "[b3][tb]overlay=0:'32*(1-min(1,max(0,(t-0.90)/0.75)))',format=yuv420p[v]",
            ]
            cmd += ["-filter_complex", ";".join(fc), "-map", "[v]",
                    "-t", "%.2f" % m["dur"], "-c:v", "libx264", "-preset", "medium",
                    "-crf", "18", "-pix_fmt", "yuv420p", "-r", "30", out]
            run(cmd)
        outs.append(out)
        print("scene", idx, "ok")
    return outs


def concat(outs, target, audio=True):
    lst = os.path.join(SCENES, "list_%s.txt" % os.path.basename(target).replace(".", "_"))
    with open(lst, "w", encoding="utf-8") as f:
        for o in outs:
            f.write("file '%s'\n" % o.replace("\\", "/"))
    if audio:
        run([FFMPEG, "-y", "-hide_banner", "-loglevel", "error",
             "-f", "concat", "-safe", "0", "-i", lst,
             "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo",
             "-shortest", "-c:v", "copy", "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", target])
    else:
        run([FFMPEG, "-y", "-hide_banner", "-loglevel", "error",
             "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", "-movflags", "+faststart", target])
    print("wrote", target, os.path.getsize(target))


if __name__ == "__main__":
    meta = build_landscape()
    outs = render_landscape(meta)
    concat(outs, os.path.join(DIST, "Smath-promo-1080p.mp4"))