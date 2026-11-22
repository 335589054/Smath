"""Smath 2.0 宣传片：纯程序化动画（不使用任何截图）。

用法：
    python tools/promo/make_promo2.py --stills 3,10,16,21,27,33,39,45
    python tools/promo/make_promo2.py --video dist/Smath-promo2-1080p.mp4

设计：1920x1080 / 30fps / 48.12s，全部画面由 Pillow 绘制，
分镜按 119.7 BPM 的小节（2.005s）对齐，配乐由 add_music2.py 合入。
"""
from __future__ import annotations

import argparse
import math
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
F_BOLD = r"C:\Windows\Fonts\msyhbd.ttc"
F_REG = r"C:\Windows\Fonts\msyh.ttc"
F_LATIN = r"C:\Windows\Fonts\seguisb.ttf"
F_LATIN_BOLD = r"C:\Windows\Fonts\segoeuib.ttf"
F_MATH = r"C:\Windows\Fonts\cambria.ttc"

W, H = 1920, 1080
FPS = 30
TOTAL = 48.1248
FRAMES = int(round(TOTAL * FPS))

BRAND = (77, 107, 254)
BRAND_DEEP = (44, 66, 190)
INK = (16, 20, 42)
INK_BOTTOM = (8, 10, 20)
CARD = (247, 248, 250)
CARD_INK = (24, 28, 48)
MUTED = (150, 160, 190)


# ---------------------------------------------------------------- helpers
def font(path, size, index=0):
    return ImageFont.truetype(path, size, index=index)


def clamp(v, lo=0.0, hi=1.0):
    return max(lo, min(hi, v))


def ease_out(t):
    t = clamp(t)
    return 1 - (1 - t) ** 3


def ease_in_out(t):
    t = clamp(t)
    return 3 * t * t - 2 * t * t * t


def ease_back(t):
    t = clamp(t)
    c = 1.70158
    return 1 + (c + 1) * (t - 1) ** 3 + c * (t - 1) ** 2


def lerp(a, b, t):
    return a + (b - a) * t


def vgrad(top, bottom):
    t = np.linspace(0.0, 1.0, H, dtype=np.float32)[:, None]
    top = np.array(top, dtype=np.float32)
    bottom = np.array(bottom, dtype=np.float32)
    rows = top[None, :] * (1 - t) + bottom[None, :] * t
    img = np.repeat(rows[:, None, :], W, axis=1)
    return Image.fromarray(img.astype(np.uint8), "RGB")


def radial_glow(radius, color, alpha=70):
    size = radius * 2
    yy, xx = np.mgrid[0:size, 0:size]
    d = np.sqrt((xx - radius) ** 2 + (yy - radius) ** 2) / radius
    a = np.clip(1 - d, 0, 1) ** 2 * alpha
    layer = np.zeros((size, size, 4), dtype=np.uint8)
    layer[..., 0] = color[0]
    layer[..., 1] = color[1]
    layer[..., 2] = color[2]
    layer[..., 3] = a.astype(np.uint8)
    return Image.fromarray(layer, "RGBA")


def dot_grid(step=48, alpha=16):
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for y in range(step, H, step):
        for x in range(step, W, step):
            d.ellipse([x - 1, y - 1, x + 1, y + 1], fill=(255, 255, 255, alpha))
    return layer


def shadowed(card, blur=22, spread=18, alpha=88):
    pad = spread + blur
    w, h = card.size
    canvas = Image.new("RGBA", (w + pad * 2, h + pad * 2), (0, 0, 0, 0))
    alpha_mask = card.split()[-1]
    sh = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    black = Image.new("RGBA", card.size, (0, 0, 0, alpha))
    sh.paste(black, (pad, pad), alpha_mask)
    sh = sh.filter(ImageFilter.GaussianBlur(blur))
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    shadow.paste(sh, (0, 10))
    return shadow, pad


def rounded_card(lines, width, pad_x=26, pad_y=20, radius=20, fill=CARD,
                 ink=CARD_INK, sub=None, sub_color=MUTED, border=None, border_w=0):
    """lines: [(text, font, color, spacing)]"""
    tmp = Image.new("RGBA", (10, 10))
    d = ImageDraw.Draw(tmp)
    heights, widths = [], []
    for text, fnt, _color, _gap in lines:
        box = d.textbbox((0, 0), text, font=fnt)
        heights.append(box[3] - box[1])
        widths.append(box[2] - box[0])
    height = pad_y * 2 + sum(heights) + sum(l[3] for l in lines[:-1]) if lines else pad_y * 2
    height = max(2.0, float(height))
    width = max(2.0, float(width))
    card = Image.new("RGBA", (int(width), int(height)), (0, 0, 0, 0))
    d = ImageDraw.Draw(card)
    if fill is not None:
        d.rounded_rectangle([0, 0, card.size[0] - 1, card.size[1] - 1], radius=radius, fill=fill)
    if border is not None and border_w:
        d.rounded_rectangle([0, 0, card.size[0] - 1, card.size[1] - 1], radius=radius,
                            outline=border, width=border_w)
    y = pad_y
    for (text, fnt, color, gap), th in zip(lines, heights):
        d.text((pad_x, y), text, font=fnt, fill=color)
        y += th + gap
    return card


def paste_alpha(base, layer, xy, alpha=1.0, scale=1.0):
    if alpha <= 0.003:
        return
    if scale != 1.0:
        w = max(1, int(layer.size[0] * scale))
        h = max(1, int(layer.size[1] * scale))
        layer = layer.resize((w, h), Image.LANCZOS)
    if alpha < 0.999:
        a = layer.split()[-1].point(lambda v: int(v * alpha))
        layer = layer.copy()
        layer.putalpha(a)
    base.alpha_composite(layer, (int(xy[0]), int(xy[1])))


def bezier_points(p0, p1, n=26):
    dx = max(60.0, abs(p1[0] - p0[0]))
    c1 = (p0[0] + dx * 0.45, p0[1])
    c2 = (p1[0] - dx * 0.45, p1[1])
    pts = []
    for i in range(n + 1):
        t = i / n
        x = (1 - t) ** 3 * p0[0] + 3 * (1 - t) ** 2 * t * c1[0] + 3 * (1 - t) * t * t * c2[0] + t ** 3 * p1[0]
        y = (1 - t) ** 3 * p0[1] + 3 * (1 - t) ** 2 * t * c1[1] + 3 * (1 - t) * t * t * c2[1] + t ** 3 * p1[1]
        pts.append((x, y))
    return pts


def draw_edge(draw, p0, p1, progress, color=(77, 107, 254, 210), width=4):
    pts = bezier_points(p0, p1)
    upto = max(1, int(len(pts) * clamp(progress)))
    for i in range(upto - 1):
        draw.line([pts[i], pts[i + 1]], fill=color, width=width)


# ---------------------------------------------------------------- assets
BG = vgrad(INK, INK_BOTTOM)
GLOW_A = radial_glow(560, BRAND, 92)
GLOW_B = radial_glow(420, (150, 90, 240), 54)
GRID = dot_grid()

F_HEAD = font(F_BOLD, 96)
F_HEAD_BIG = font(F_BOLD, 128)
F_SUB = font(F_REG, 40)
F_CAPTION = font(F_BOLD, 52)
F_NODE = font(F_REG, 30)
F_NODE_SM = font(F_REG, 24)
F_PILL = font(F_BOLD, 32)
F_WORDMARK = font(F_LATIN_BOLD, 40)
F_FORMULA = font(F_MATH, 64)
F_MERGE = font(F_BOLD, 30)


def wordmark():
    layer = Image.new("RGBA", (420, 80), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.text((0, 8), "Smath", font=F_WORDMARK, fill=(255, 255, 255, 235))
    d.ellipse([150, 44, 166, 60], fill=BRAND)
    return layer


WORDMARK = wordmark()


def caption_block(head, sub=None):
    lines = [(head, F_CAPTION, (255, 255, 255, 240), 16)]
    if sub:
        lines.append((sub, font(F_REG, 34), MUTED + (235,), 0))
    return rounded_card(lines, 960, pad_x=36, pad_y=34, radius=20, fill=(255, 255, 255, 16),
                        border=(255, 255, 255, 34), border_w=1)


NODE_DEFS = [
    {"id": "n1", "text": "解 x³ - 6x² + 11x - 6 = 0", "x": 170, "y": 408},
    {"id": "n2", "text": "先试有理根", "x": 742, "y": 214},
    {"id": "n3", "text": "换卡尔达诺公式", "x": 742, "y": 566},
    {"id": "n4", "text": "因式分解 (x-1)(x-2)(x-3)", "x": 1196, "y": 388},
]
NODE_W = 430


def node_card(text, index):
    """带序号徽标的节点卡，宽度按文字自适应。"""
    probe = ImageDraw.Draw(Image.new("RGBA", (10, 10)))
    tw = probe.textlength(text, font=F_NODE)
    width = int(22 + 36 + 12 + tw + 22)
    height = 78
    card = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    d = ImageDraw.Draw(card)
    d.rounded_rectangle([0, 0, width - 1, height - 1], radius=18, fill=CARD)
    d.ellipse([22, 24, 52, 54], fill=(77, 107, 254, 40))
    num = str(index)
    nw = d.textlength(num, font=font(F_BOLD, 20))
    d.text((37 - nw / 2, 26), num, font=font(F_BOLD, 20), fill=BRAND_DEEP + (255,))
    d.text((70, 20), text, font=F_NODE, fill=CARD_INK)
    return card


NODES = {}
for _i, _nd in enumerate(NODE_DEFS, start=1):
    _img = node_card(_nd["text"], _i)
    _sh, _pad = shadowed(_img)
    NODES[_nd["id"]] = {"img": _img, "shadow": _sh, "pad": _pad,
                        "w": _img.size[0], "h": _img.size[1]}


def tidy_positions():
    """整理后的网格位置（两列竖排）。"""
    return {
        "n1": (210, 300),
        "n2": (210, 470),
        "n3": (210, 640),
        "n4": (980, 470),
    }


# ---------------------------------------------------------------- scenes
def scene_pain(img, t, local):
    """0 - 8.02s：线性对话的窒息感。"""
    d = ImageDraw.Draw(img)
    stem_x = 1090
    seq = ["问：这道题怎么下笔？", "答：先看条件……", "问：换个思路呢？"]
    d.line([(stem_x, 130), (stem_x, 720)], fill=(110, 122, 165, 110), width=3)
    for i, text in enumerate(seq):
        y = 168 + i * 158 - local * 10
        appear = ease_out((local - i * 0.5) / 0.6)
        if appear <= 0:
            continue
        card = rounded_card([(text, font(F_REG, 28), (156, 166, 196), 0)], 600, pad_x=26, pad_y=20,
                            radius=16, fill=(255, 255, 255, 14), border=(255, 255, 255, 30), border_w=1)
        paste_alpha(img, card, (stem_x - 640, y), alpha=appear * 0.95)
        d.line([(stem_x - 40, y + 38), (stem_x - 12, y + 38)], fill=(120, 132, 172, 120), width=2)
        d.ellipse([stem_x - 8, y + 32, stem_x + 4, y + 44], fill=(130, 142, 185, 190))
    # 试图分叉的小枝桠：长出去，再被拉回主线
    for k in range(2):
        yy = 620 + k * 70
        grow = clamp((local - 1.1 - k * 0.7) / 0.9)
        if grow <= 0:
            continue
        pull = ease_in_out(clamp((local - 3.6 - k * 0.6) / 1.0))
        reach = 200 * math.sin(math.pi * min(1.0, grow)) * (1 - pull)
        tip = (stem_x + reach + 8, yy - 26 * (1 - pull))
        if reach > 10:
            d.line([(stem_x, yy), tip], fill=(126, 138, 178, 150), width=3)
            d.ellipse([tip[0] - 5, tip[1] - 5, tip[0] + 5, tip[1] + 5], fill=(126, 138, 178, 170))
    cap = caption_block("对话，只能一条线往前走", "想问个「如果换个思路呢」，只能重开一段")
    paste_alpha(img, cap, (150, 760), alpha=ease_out(local / 0.8))


def scene_title(img, t, local):
    """8.02 - 12.03s：重新定义会话。"""
    d = ImageDraw.Draw(img)
    a1 = ease_out(local / 0.7)
    a2 = ease_out((local - 0.5) / 0.8)
    head = rounded_card([("重新定义会话", F_HEAD_BIG, (255, 255, 255, 246), 0)], 1180, pad_x=0, pad_y=0,
                        radius=0, fill=None)
    lay = Image.new("RGBA", (1400, 260), (0, 0, 0, 0))
    dl = ImageDraw.Draw(lay)
    dl.text((0, 0), "重新定义会话", font=F_HEAD_BIG, fill=(255, 255, 255, 248))
    # 关键词描出品牌蓝
    w_pre = dl.textlength("重新定义", font=F_HEAD_BIG)
    dl.text((w_pre, 0), "会话", font=F_HEAD_BIG, fill=BRAND + (255,))
    y = 380 - (1 - a1) * 26
    paste_alpha(img, lay, (150, y), alpha=a1)
    sub = Image.new("RGBA", (1500, 120), (0, 0, 0, 0))
    ds = ImageDraw.Draw(sub)
    ds.text((2, 0), "打破线性对话模式：每一次追问，都长成一张图", font=F_SUB, fill=MUTED + (245,))
    paste_alpha(img, sub, (152, y + 210 + (1 - a2) * 18), alpha=a2)
    # 一条直线在此处裂开
    split = ease_in_out(clamp((local - 1.4) / 1.4))
    cx, cy = W / 2, 800
    d.line([(220, cy), (cx - 120 * split, cy)], fill=(120, 130, 170, 150), width=4)
    d.line([(cx + 120 * split, cy), (W - 220, cy)], fill=(120, 130, 170, 150), width=4)
    if split > 0.02:
        for sgn in (-1, 1):
            d.line([(cx, cy), (cx + sgn * 120 * split, cy)], fill=BRAND + (200,), width=4)


def graph_positions(t):
    """S4/S5/S6：节点位置随动画变化，返回 {id: (x, y)}。"""
    pos = {nd["id"]: (nd["x"], nd["y"]) for nd in NODE_DEFS}
    # S5：合并 —— n2 / n3 稍向内收
    if t > 18.05:
        p = ease_in_out(clamp((t - 18.05) / 1.2))
        pos["n2"] = (lerp(pos["n2"][0], 690, p), lerp(pos["n2"][1], 230, p))
        pos["n3"] = (lerp(pos["n3"][0], 690, p), lerp(pos["n3"][1], 560, p))
    # S6：拖动 n4 再一键整理
    if 24.2 < t < 27.0:
        p = ease_in_out(clamp((t - 24.2) / 1.2))
        pos["n4"] = (lerp(pos["n4"][0], 1180, p), lerp(pos["n4"][1], 620, p))
    if t >= 27.0:
        tidy = tidy_positions()
        p = ease_in_out(clamp((t - 27.0) / 1.1))
        for k in ("n1", "n2", "n3", "n4"):
            cur = pos[k]
            pos[k] = (lerp(cur[0], tidy[k][0], p), lerp(cur[1], tidy[k][1], p))
    return pos


def draw_graph(img, t, appear_times, edge_times, sel_ids=(), merge_at=None, tidy_flash=None):
    d = ImageDraw.Draw(img)
    pos = graph_positions(t)

    def anchor_out(nid):
        p = pos[nid]
        return (p[0] + NODES[nid]["w"], p[1] + NODES[nid]["h"] / 2)

    def anchor_in(nid):
        p = pos[nid]
        return (p[0], p[1] + NODES[nid]["h"] / 2)

    # 连线
    for (parent, child), t0 in edge_times.items():
        if t < t0:
            continue
        draw_edge(d, anchor_out(parent), anchor_in(child), clamp((t - t0) / 0.55))
    if merge_at is not None and t >= merge_at:
        for parent in ("n2", "n3"):
            draw_edge(d, anchor_out(parent), anchor_in("n4"), clamp((t - merge_at) / 0.5),
                      color=(158, 120, 255, 220), width=4)
    # 节点
    for nd in NODE_DEFS:
        t0 = appear_times.get(nd["id"])
        if t0 is None or t < t0:
            continue
        a = ease_out((t - t0) / 0.5)
        sc = lerp(0.88, 1.0, ease_back((t - t0) / 0.55))
        x, y = pos[nd["id"]]
        node = NODES[nd["id"]]
        paste_alpha(img, node["shadow"], (x - node["pad"], y - node["pad"]), alpha=a * 0.85, scale=sc)
        paste_alpha(img, node["img"], (x, y), alpha=a, scale=sc)
        if nd["id"] in sel_ids:
            pulse = 0.55 + 0.45 * math.sin(t * 6.0)
            d.rounded_rectangle([x - 6, y - 6, x + node["w"] + 6, y + node["h"] + 6], radius=24,
                                outline=(77, 107, 254, int(120 + 110 * pulse)), width=4)
    if tidy_flash:
        paste_alpha(img, tidy_flash, (0, 0), alpha=0.5)


def scene_graph(img, t, local):
    """12.03 - 30.08s：拓扑生长 / 合并 / 拖动 / 一键整理。"""
    appear = {"n1": 12.3, "n2": 13.3, "n3": 14.3, "n4": 15.4}
    edges = {("n1", "n2"): 13.45, ("n1", "n3"): 14.45,
             ("n2", "n4"): 19.5, ("n3", "n4"): 19.6}
    sel = ()
    if 18.05 <= t < 19.5:
        sel = ("n2", "n3")
    draw_graph(img, t, appear, edges, sel_ids=sel, merge_at=19.4 if t >= 19.4 else None)
    if 12.1 <= t < 18.2:
        a = ease_out((t - 12.1) / 0.6) * (1 - ease_out(clamp((t - 17.5) / 0.6)))
        cap = caption_block("每条思路都留得下来", "一次问答长成一个节点，枝杈自己看得见")
        paste_alpha(img, cap, (150, 800), alpha=a)
    # 选中计数提示
    if 18.1 <= t < 19.9:
        a = ease_out((t - 18.1) / 0.4) * (1 - ease_out(clamp((t - 19.5) / 0.3)))
        tip = rounded_card([("已选 2 个节点 · 合并建分支", font(F_BOLD, 30), BRAND + (255,), 0)], 560,
                           pad_x=26, pad_y=16, radius=16, fill=(255, 255, 255, 16),
                           border=(77, 107, 254, 120), border_w=1)
        paste_alpha(img, tip, (700, 890), alpha=a)
    if 19.6 <= t < 22.0:
        a = ease_out((t - 19.6) / 0.5)
        tip = rounded_card([("两条思路合并成一条新线索", font(F_BOLD, 30), (206, 186, 255, 255), 0)], 640,
                           pad_x=26, pad_y=16, radius=16, fill=(60, 40, 100, 190),
                           border=(158, 120, 255, 160), border_w=1)
        paste_alpha(img, tip, (640, 890), alpha=a)
    # 拖动提示
    if 24.2 <= t < 27.0:
        a = ease_out((t - 24.2) / 0.5) * (1 - ease_out(clamp((t - 26.6) / 0.4)))
        tip = rounded_card([("直接拖动，手指在哪节点就在哪", font(F_BOLD, 30), (255, 255, 255, 235), 0)], 660,
                           pad_x=26, pad_y=16, radius=16, fill=(255, 255, 255, 16),
                           border=(255, 255, 255, 40), border_w=1)
        paste_alpha(img, tip, (630, 890), alpha=a)
    if 26.9 <= t < 30.08:
        a = ease_out((t - 26.9) / 0.5)
        tip = rounded_card([("一键整理画布", font(F_BOLD, 32), (255, 255, 255, 255), 0)], 360,
                           pad_x=30, pad_y=18, radius=40, fill=(77, 107, 254, 240),
                           border=(255, 255, 255, 90), border_w=1)
        paste_alpha(img, tip, (780, 890), alpha=a)


def auto_card(text, fnt, fill=CARD, ink=CARD_INK, pad_x=32, pad_y=20, radius=18, max_w=900):
    probe = ImageDraw.Draw(Image.new("RGBA", (10, 10)))
    tw = probe.textlength(text, font=fnt)
    w = int(min(max_w, tw + pad_x * 2))
    h = int(pad_y * 2 + fnt.size * 1.35)
    card = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    dd = ImageDraw.Draw(card)
    dd.rounded_rectangle([0, 0, w - 1, h - 1], radius=radius, fill=fill)
    dd.text((pad_x, h / 2), text, font=fnt, fill=ink, anchor="lm")
    return card


def scene_tools(img, t, local):
    """30.08 - 36.09s：多步工具调用。"""
    d = ImageDraw.Draw(img)
    step1 = ease_out((local - 0.05) / 0.5)
    step2 = ease_out((local - 0.85) / 0.5)
    step3 = ease_out((local - 1.6) / 0.5)
    head = Image.new("RGBA", (1500, 130), (0, 0, 0, 0))
    dh = ImageDraw.Draw(head)
    dh.text((0, 0), "会算，会画，还会自己验算", font=F_HEAD, fill=(255, 255, 255, 246))
    paste_alpha(img, head, (150, 150), alpha=ease_out(local / 0.7))
    sub = Image.new("RGBA", (1400, 60), (0, 0, 0, 0))
    ds = ImageDraw.Draw(sub)
    ds.text((2, 0), "思考过程中调用工具，拿到真实结果再接着写", font=font(F_REG, 32), fill=MUTED + (230,))
    paste_alpha(img, sub, (152, 290), alpha=ease_out((local - 0.3) / 0.6))
    f1 = auto_card("(1+i)^(1+i)", font(F_MATH, 58))
    paste_alpha(img, f1, (150, 360), alpha=step1, scale=lerp(0.92, 1.0, step1))
    r1 = auto_card("0.27396 + 0.58370i", font(F_MATH, 44))
    paste_alpha(img, r1, (830, 372), alpha=step2, scale=lerp(0.92, 1.0, step2))
    f2 = auto_card("det [[1,2],[3,4]]", font(F_MATH, 58))
    paste_alpha(img, f2, (150, 560), alpha=step3, scale=lerp(0.92, 1.0, step3))
    r2 = auto_card("-2", font(F_MATH, 58), ink=BRAND + (255,))
    paste_alpha(img, r2, (830, 572), alpha=ease_out((local - 3.6) / 0.5))
    if step2 > 0.2:
        d.line([(640, 412), (800, 412)], fill=(150, 160, 190, 180), width=4)
        d.polygon([(800, 412), (778, 400), (778, 424)], fill=(150, 160, 190, 200))
    if step3 > 0.2:
        d.line([(640, 612), (800, 612)], fill=(150, 160, 190, 180), width=4)
        d.polygon([(800, 612), (778, 600), (778, 624)], fill=(150, 160, 190, 200))
    # 右侧：绘图 Agent 的小画布
    pa = ease_out((local - 1.0) / 0.7)
    if pa > 0:
        pw, ph = 400, 320
        panel = Image.new("RGBA", (pw, ph), (0, 0, 0, 0))
        dp = ImageDraw.Draw(panel)
        dp.rounded_rectangle([0, 0, pw - 1, ph - 1], radius=20, fill=(18, 23, 46, 235),
                             outline=(64, 84, 150, 200), width=2)
        ox, oy = 46, ph - 70
        dp.line([(ox, 30), (ox, oy)], fill=(70, 84, 130, 220), width=2)
        dp.line([(ox, oy), (pw - 26, oy)], fill=(70, 84, 130, 220), width=2)
        pts = []
        for i in range(0, 181):
            x = i / 180
            v = math.sin(x * math.pi * 2 - 0.4)
            pts.append((ox + x * (pw - 76), oy - v * 48))
        dp.line(pts, fill=BRAND + (255,), width=4, joint="curve")
        dp.text((24, 16), "绘图 Agent", font=font(F_BOLD, 22), fill=(150, 165, 210, 235))
        dp.text((pw - 116, 16), "y = sin x", font=font(F_MATH, 24), fill=(120, 140, 190, 220))
        paste_alpha(img, panel, (1450, 330), alpha=pa, scale=lerp(0.94, 1.0, pa))


def scene_features(img, t, local):
    """36.09 - 42.11s：功能矩阵 + 长公式横向拖动。"""
    d = ImageDraw.Draw(img)
    head = Image.new("RGBA", (1600, 130), (0, 0, 0, 0))
    dh = ImageDraw.Draw(head)
    dh.text((0, 0), "2.0 还有这些", font=F_HEAD, fill=(255, 255, 255, 246))
    paste_alpha(img, head, (150, 150), alpha=ease_out(local / 0.6))
    pills = ["多步工具调用", "线性代数计算器", "公式横向滑动", "会话导出 Markdown", "检查更新", "截屏悬浮球"]
    for i, text in enumerate(pills):
        a = ease_out((local - 0.4 - i * 0.28) / 0.45)
        if a <= 0:
            continue
        col = i % 3
        row = i // 3
        x = 150 + col * 560
        y = 330 + row * 130
        on = i < 4
        fill = (77, 107, 254, 210) if on else (255, 255, 255, 16)
        pill = rounded_card([(text, F_PILL, (255, 255, 255, 245), 0)], 500, pad_x=30, pad_y=20, radius=40,
                            fill=fill, border=(255, 255, 255, 60), border_w=1)
        paste_alpha(img, pill, (x, y), alpha=a, scale=lerp(0.94, 1.0, a))
    # 长公式横向滑动演示
    if local > 2.0:
        a = ease_out((local - 2.0) / 0.6)
        plate = Image.new("RGBA", (1620, 190), (0, 0, 0, 0))
        dp = ImageDraw.Draw(plate)
        dp.rounded_rectangle([0, 0, 1619, 189], radius=22, fill=(255, 255, 255, 18),
                             outline=(255, 255, 255, 40), width=1)
        paste_alpha(img, plate, (150, 640), alpha=a)
        formula = "(1+x+x²+…+x¹⁰)² = 1+2x+3x²+…+11x¹⁰+…+x²⁰"
        fnt = font(F_MATH, 62)
        fw = int(ImageDraw.Draw(Image.new("RGBA", (10, 10))).textlength(formula, font=fnt))
        strip = Image.new("RGBA", (fw + 40, 120), (0, 0, 0, 0))
        ds = ImageDraw.Draw(strip)
        ds.text((20, 10), formula, font=fnt, fill=(255, 255, 255, 240))
        slide = ease_in_out(clamp((local - 2.6) / 2.2))
        max_shift = fw + 40 - 1560
        crop_x = int(slide * max_shift)
        part = strip.crop((crop_x, 0, min(fw + 40, crop_x + 1560), 120))
        paste_alpha(img, part, (180, 675), alpha=a)
        # 手指/滑动提示
        if 0.2 < slide < 0.98:
            hx = 180 + 1560 - 80
            d.ellipse([hx - 26, 810, hx + 26, 862], fill=(255, 255, 255, 60))


def scene_end(img, t, local):
    """42.11 - 48.12s：落版。"""
    d = ImageDraw.Draw(img)
    a = ease_out(local / 0.8)
    logo = Image.new("RGBA", (220, 220), (0, 0, 0, 0))
    dl = ImageDraw.Draw(logo)
    dl.rounded_rectangle([0, 0, 219, 219], radius=58, fill=BRAND + (255,))
    dl.text((110, 112), "S", font=font(F_LATIN_BOLD, 140), fill=(255, 255, 255, 255), anchor="mm")
    paste_alpha(img, logo, (W / 2 - 110, 300 - (1 - a) * 20), alpha=a, scale=lerp(0.9, 1.0, a))
    lay = Image.new("RGBA", (1200, 260), (0, 0, 0, 0))
    dl = ImageDraw.Draw(lay)
    title = "Smath 2.0"
    tw = dl.textlength(title, font=font(F_LATIN_BOLD, 96))
    dl.text(((1200 - tw) / 2, 0), title, font=font(F_LATIN_BOLD, 96), fill=(255, 255, 255, 250))
    sub = "重新定义会话 · 打破线性对话模式"
    sw = dl.textlength(sub, font=font(F_REG, 40))
    dl.text(((1200 - sw) / 2, 130), sub, font=font(F_REG, 40), fill=MUTED + (240,))
    paste_alpha(img, lay, (W / 2 - 600, 590), alpha=ease_out((local - 0.5) / 0.7))
    foot = "开源 · Android 对话智能体"
    fl = Image.new("RGBA", (900, 80), (0, 0, 0, 0))
    df = ImageDraw.Draw(fl)
    fw2 = df.textlength(foot, font=font(F_REG, 32))
    df.text(((900 - fw2) / 2, 0), foot, font=font(F_REG, 32), fill=(120, 130, 160, 235))
    paste_alpha(img, fl, (W / 2 - 450, 830), alpha=ease_out((local - 1.2) / 0.7))


SCENES = [
    (0.0, scene_pain),
    (8.02, scene_title),
    (12.03, scene_graph),
    (30.08, scene_tools),
    (36.09, scene_features),
    (42.11, scene_end),
]


def render_frame(t):
    img = BG.copy().convert("RGBA")
    # 缓慢漂移的辉光
    gx = W * 0.68 + math.sin(t * 0.25) * 90
    gy = H * 0.42 + math.cos(t * 0.21) * 60
    paste_alpha(img, GLOW_A, (gx - GLOW_A.size[0] / 2, gy - GLOW_A.size[1] / 2), alpha=0.9)
    paste_alpha(img, GLOW_B, (W * 0.22 - GLOW_B.size[0] / 2 + math.sin(t * 0.17) * 60,
                             H * 0.75 - GLOW_B.size[1] / 2), alpha=0.75)
    img.alpha_composite(GRID)
    # 顶部字标
    paste_alpha(img, WORDMARK, (140, 84), alpha=0.9)

    cur = SCENES[0]
    for start, fn in SCENES:
        if t >= start:
            cur = (start, fn)
    start, fn = cur
    local = t - start
    fn(img, t, local)

    # 底部进度条
    d = ImageDraw.Draw(img)
    prog = clamp(t / TOTAL)
    d.line([(140, H - 70), (W - 140, H - 70)], fill=(255, 255, 255, 26), width=3)
    d.line([(140, H - 70), (140 + (W - 280) * prog, H - 70)], fill=BRAND + (220,), width=3)
    return img.convert("RGB")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--stills", type=str, default="")
    ap.add_argument("--video", type=str, default="")
    ap.add_argument("--ffmpeg", type=str, default="")
    args = ap.parse_args()

    if args.stills:
        os.makedirs(os.path.join(ROOT, "out", "promo2", "stills"), exist_ok=True)
        for spec in args.stills.split(","):
            t = float(spec)
            img = render_frame(t)
            path = os.path.join(ROOT, "out", "promo2", "stills", f"t{int(t * 100):04d}.png")
            img.save(path)
            print("still", path)
        return

    if not args.video:
        print("nothing to do")
        return
    ffmpeg = args.ffmpeg or os.path.join(ROOT, "tools", "ffdl", "extract", "imageio_ffmpeg", "binaries",
                                         "ffmpeg-win-x86_64-v9.0.2.exe")
    os.makedirs(os.path.dirname(os.path.abspath(args.video)), exist_ok=True)
    cmd = [ffmpeg, "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS),
           "-i", "-", "-an", "-c:v", "libx264", "-preset", "medium", "-crf", "18",
           "-pix_fmt", "yuv420p", "-movflags", "+faststart", args.video]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for i in range(FRAMES):
        img = render_frame(i / FPS)
        proc.stdin.write(img.tobytes())
        if i % 120 == 0:
            print(f"frame {i}/{FRAMES}", flush=True)
    proc.stdin.close()
    proc.wait()
    print("done", args.video)


if __name__ == "__main__":
    main()