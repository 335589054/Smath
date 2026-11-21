# -*- coding: utf-8 -*-
"""生成视频封面：16:9（1920×1080）与 4:3（1440×1080）。

风格与宣传片保持一致：深色舞台 + 品牌蓝辉光 + 真机界面 + 大字号白色标题。
截图取自真机录屏（out/footage/05-drawing.mp4 抽帧），保证封面与实际界面一致；
顶部状态栏与底部系统手势条会被裁掉，只留应用界面本身。

用法：
    python tools/promo/make_covers.py
输出：
    dist/Smath-cover-16x9.png
    dist/Smath-cover-4x3.png
"""
import hashlib
import os
import subprocess

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

import make_promo as mp

ROOT = mp.ROOT
FOOT = os.path.join(ROOT, "out", "footage")
CACHE = os.path.join(ROOT, "out", "cover", "frames")
FFMPEG = mp.FFMPEG

NAVY_TOP = (18, 23, 45)
NAVY_BOTTOM = (8, 10, 20)
GLOW = (77, 107, 254)
SUB = (170, 181, 209)
FAINT = (126, 139, 170)
CARD_INK = (24, 30, 52)
CARD_MUTED = (120, 132, 158)

HEAD_LINES = ["把每道题", "讲到懂为止"]
SUB_LINE = "不止给答案，还把思路一步步摊开"
CHIPS = ["先思考再解答", "公式不断行", "无限画布"]
FOOT_LINE = "Smath · 开源 Android 对话智能体 · v1.4.0"
FORMULA = "(1+x+x²+…+x¹⁰)² = 1+2x+3x²+…+11x¹⁰+…+x²⁰"
FORMULA_NOTE = "一行写完，按住可左右滑"

# 截图裁切：去掉系统状态栏与手势条
CROP_TOP, CROP_BOTTOM = 108, 46


def frame(clip, t):
    os.makedirs(CACHE, exist_ok=True)
    out = os.path.join(CACHE, "%s_%s.png" % (clip, str(t).replace(".", "_")))
    if not os.path.isfile(out):
        subprocess.run([FFMPEG, "-hide_banner", "-loglevel", "error", "-y",
                        "-ss", str(t), "-i", os.path.join(FOOT, clip + ".mp4"),
                        "-frames:v", "1", out], check=True)
    im = Image.open(out).convert("RGB")
    return im.crop((0, CROP_TOP, im.width, im.height - CROP_BOTTOM))


def background(w, h, glow_xy, glow_r, glow_a=0.36):
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    ty = (yy / max(1, h - 1))[..., None]
    base = np.array(NAVY_TOP, np.float32) * (1 - ty) + np.array(NAVY_BOTTOM, np.float32) * ty

    d2 = (xx - glow_xy[0]) ** 2 + (yy - glow_xy[1]) ** 2
    g = np.exp(-d2 / (2.0 * glow_r * glow_r))[..., None].astype(np.float32) * glow_a
    img = base * (1 - g) + np.array(GLOW, np.float32) * g

    cx, cy = w / 2.0, h / 2.0
    vd = np.sqrt(((xx - cx) / cx) ** 2 + ((yy - cy) / cy) ** 2)
    vig = np.clip(1.0 - 0.22 * np.clip(vd - 0.35, 0, None) ** 1.6, 0.72, 1.0)[..., None]
    return Image.fromarray(np.clip(img * vig, 0, 255).astype(np.uint8), "RGB").convert("RGBA")


def rounded(img, radius):
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1],
                                        radius=radius, fill=255)
    out = img.convert("RGBA")
    out.putalpha(m)
    return out


def drop_shadow(size, box, radius, blur=34, offset=(0, 20), alpha=150):
    sh = Image.new("RGBA", size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle([box[0] + offset[0], box[1] + offset[1],
                                          box[2] + offset[0], box[3] + offset[1]],
                                         radius=radius, fill=(3, 6, 18, alpha))
    return sh.filter(ImageFilter.GaussianBlur(blur))


def paste_rotated(canvas, layer, center, angle, shadow_alpha=95, blur=42, offset=(0, 24)):
    """旋转粘贴，并按旋转后的实际轮廓生成投影（不用矩形近似）。"""
    rot = layer.rotate(angle, resample=Image.BICUBIC, expand=True)
    pos = (int(center[0] - rot.width / 2), int(center[1] - rot.height / 2))
    if shadow_alpha:
        alpha = rot.split()[-1].point(lambda v: int(v * shadow_alpha / 255.0))
        sh = Image.new("RGBA", rot.size, (3, 6, 18, 0))
        sh.putalpha(alpha)
        sh = sh.filter(ImageFilter.GaussianBlur(blur))
        canvas.alpha_composite(sh, (pos[0] + offset[0], pos[1] + offset[1]))
    canvas.alpha_composite(rot, pos)


def phone_card(shot, target_h, border=4, radius_ratio=0.052):
    w = int(round(target_h * shot.width / shot.height))
    pic = shot.resize((w - border * 2, target_h - border * 2), Image.LANCZOS)
    card = Image.new("RGBA", (w, target_h), (255, 255, 255, 255))
    card.paste(rounded(pic, int(target_h * radius_ratio)), (border, border))
    return rounded(card, int(target_h * radius_ratio) + border)


def formula_card(w, h, size=40):
    """深色玻璃卡 + 完整一行公式（自动缩字号保证不截断）+ 说明。

    用深色而不是白卡：缩略图尺寸下白卡会抢走标题的注意力，深色卡只作为
    「有数学内容」的质感层，标题依旧是第一视觉。
    """
    pad = 28
    probe = ImageDraw.Draw(Image.new("RGB", (8, 8)))
    f = mp.font(mp.F_MATH, size)
    while size > 20 and probe.textlength(FORMULA, font=f) > w - pad * 2:
        size -= 1
        f = mp.font(mp.F_MATH, size)

    card = Image.new("RGBA", (w, h), (23, 30, 54, 255))
    d = ImageDraw.Draw(card)
    d.rounded_rectangle([1, 1, w - 2, h - 2], radius=23, outline=(72, 94, 158, 255), width=2)
    d.rounded_rectangle([pad, 26, pad + 52, 30], radius=2, fill=GLOW)
    d.text((pad, 48), FORMULA, font=f, fill=(228, 234, 250))
    d.text((pad, h - 58), FORMULA_NOTE, font=mp.font(mp.F_REG, max(20, size - 12)),
           fill=(140, 152, 182))
    return rounded(card, 24)


def text_block(draw, lines, fnt, x, y, fill, max_w, line_h=1.30):
    cy = y
    for text in lines:
        for line in mp.wrap(draw, text, fnt, max_w):
            draw.text((x, cy), line, font=fnt, fill=fill)
        cy += int(fnt.size * line_h)
    return cy


def chips_row(d, items, x, y, fnt_size, pad_x=22, gap=16, height=None):
    f = mp.font(mp.F_REG, fnt_size)
    h = height or int(fnt_size * 2.05)
    cx = x
    for c in items:
        tw = d.textlength(c, font=f)
        d.rounded_rectangle([cx, y, cx + tw + pad_x * 2, y + h], radius=h // 2,
                            fill=(38, 47, 82, 255), outline=(78, 100, 172, 255), width=2)
        d.text((cx + pad_x, y + (h - fnt_size * 1.25) / 2), c, font=f,
               fill=(208, 216, 240))
        cx += tw + pad_x * 2 + gap
    return cx


def wordmark(d, x, y, size):
    f = mp.font(mp.F_LATIN, size)
    d.text((x, y), "Smath", font=f, fill=mp.WHITE)
    ww = d.textlength("Smath", font=f)
    r = size * 0.13
    d.ellipse([x + ww + size * 0.20, y + size * 0.16,
               x + ww + size * 0.20 + r * 2, y + size * 0.16 + r * 2], fill=GLOW)


def build_16x9():
    W, H = 1920, 1080
    img = background(W, H, glow_xy=(1400, 540), glow_r=520)

    shot = frame("05-drawing", 6.0)
    phone = phone_card(shot, 946)
    px, py = 1352, 64
    img.alpha_composite(drop_shadow((W, H), (px, py, px + phone.width, py + phone.height), 54),
                        (0, 0))
    img.alpha_composite(phone, (px, py))

    d = ImageDraw.Draw(img)
    wordmark(d, 130, 88, 54)
    d.rounded_rectangle([132, 194, 132 + 88, 198], radius=2, fill=GLOW)

    y = text_block(d, HEAD_LINES, mp.font(mp.F_BOLD, 112), 128, 232, mp.WHITE, 1120,
                   line_h=1.24)
    d.text((130, y + 18), SUB_LINE, font=mp.font(mp.F_REG, 38), fill=SUB)
    chips_row(d, CHIPS, 130, y + 108, 30)

    card = formula_card(880, 172)
    paste_rotated(img, card, (580, 828), -2.2)

    d = ImageDraw.Draw(img)
    d.text((130, 986), FOOT_LINE, font=mp.font(mp.F_REG, 30), fill=FAINT)
    return img.convert("RGB")


def build_4x3():
    W, H = 1440, 1080
    img = background(W, H, glow_xy=(1090, 470), glow_r=350)

    shot = frame("05-drawing", 6.0)
    phone = phone_card(shot, 806)
    px, py = 986, 118
    img.alpha_composite(drop_shadow((W, H), (px, py, px + phone.width, py + phone.height), 48),
                        (0, 0))
    img.alpha_composite(phone, (px, py))

    d = ImageDraw.Draw(img)
    wordmark(d, 96, 74, 48)
    d.rounded_rectangle([98, 166, 98 + 76, 170], radius=2, fill=GLOW)

    y = text_block(d, HEAD_LINES, mp.font(mp.F_BOLD, 94), 94, 200, mp.WHITE, 760, line_h=1.26)
    y = text_block(d, [SUB_LINE], mp.font(mp.F_REG, 34), 96, y + 16, SUB, 720, line_h=1.5)
    chips_row(d, CHIPS, 96, y + 34, 27, pad_x=20, gap=14)

    card = formula_card(760, 164)
    paste_rotated(img, card, (482, 762), -2.2)

    d = ImageDraw.Draw(img)
    d.text((96, 986), FOOT_LINE, font=mp.font(mp.F_REG, 28), fill=FAINT)
    return img.convert("RGB")


def main():
    hashes = []
    for name, fn in (("Smath-cover-16x9.png", build_16x9), ("Smath-cover-4x3.png", build_4x3)):
        img = fn()
        out = os.path.join(mp.DIST, name)
        img.save(out, "PNG", optimize=True)
        hashes.append((name, hashlib.sha256(open(out, "rb").read()).hexdigest()))
        print("wrote", out, img.size, os.path.getsize(out), "B")

    # 把封面校验和并入 SHA256SUMS-promo.txt（只替换同名的行，保留视频条目）
    sums = os.path.join(mp.DIST, "SHA256SUMS-promo.txt")
    keep = []
    if os.path.isfile(sums):
        for line in open(sums, encoding="utf-8").read().splitlines():
            if line.strip() and not any(line.endswith(n) for n, _ in hashes):
                keep.append(line)
    keep += ["%s  %s" % (h, n) for n, h in hashes]
    with open(sums, "w", encoding="utf-8") as f:
        f.write("\n".join(keep) + "\n")
    print("checksums ->", sums)


if __name__ == "__main__":
    main()
