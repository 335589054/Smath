# -*- coding: utf-8 -*-
"""生成 1080x1080 社交方图（GitHub Social Preview / 公众号头图）。"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_promo as mp
from PIL import Image, ImageDraw

W = H = 1080
img = Image.new("RGB", (W, H), mp.BRAND)
d = ImageDraw.Draw(img)

fw = mp.font(mp.F_LATIN, 190)
ww = d.textlength("Smath", font=fw)
d.text(((W - ww) / 2, 330), "Smath", font=fw, fill=mp.WHITE)

d.line([(W / 2 - 70, 590), (W / 2 + 70, 590)], fill=(190, 202, 246), width=2)

f1 = mp.font(mp.F_BOLD, 44)
t1 = "深度求索 · 对话 Agent"
d.text(((W - d.textlength(t1, font=f1)) / 2, 640), t1, font=f1, fill=(232, 238, 255))

f2 = mp.font(mp.F_REG, 32)
t2 = "会讲题 · 会画图 · 会计算"
d.text(((W - d.textlength(t2, font=f2)) / 2, 726), t2, font=f2, fill=(214, 223, 250))

f3 = mp.font(mp.F_REG, 28)
t3 = "开源 · 免费    v1.4.0"
d.text(((W - d.textlength(t3, font=f3)) / 2, 900), t3, font=f3, fill=(196, 208, 246))

out = os.path.join(mp.DIST, "Smath-social-1080.png")
img.save(out, "PNG")
print("wrote", out)