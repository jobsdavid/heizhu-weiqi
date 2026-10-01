#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 Android TV 应用图标与首页横幅（黑猪围棋）。

## 依赖
需要 Pillow（装在项目 venv 里）：

    python3 -m venv ~/.hermes/cache/scratch/venv-assets
    ~/.hermes/cache/scratch/venv-assets/bin/pip install Pillow

## 为什么横幅必须有文字
Android TV 的启动器只显示这张 320x180 的横幅图，**下方不会另排应用名**。
如果横幅上没字，用户在电视主页上就只能看到一张棋盘图，认不出这是什么应用。

运行：~/.hermes/cache/scratch/venv-assets/bin/python tools/gen_assets.py
"""
import os
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"

WOOD_TOP = (234, 203, 158)
WOOD_BOTTOM = (198, 156, 108)
LINE_COLOR = (122, 88, 54)
BLACK_STONE = (30, 30, 34)
WHITE_STONE = (246, 245, 239)
TITLE_COLOR = (68, 42, 22)
SUBTITLE_COLOR = (124, 90, 54)

# 中文字体候选路径 —— 环境不同位置不同，逐个探测
FONT_CANDIDATES = [
    Path.home() / ".local/share/fonts/wqy-zenhei.ttc",
    Path("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"),
    Path.home() / ".local/share/fonts/NotoSerifCJK-Regular.ttc",
    Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
    Path("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"),
]


def find_font() -> Path:
    for candidate in FONT_CANDIDATES:
        if candidate.exists():
            return candidate
    raise SystemExit(
        "找不到中文字体。请安装 fonts-wqy-zenhei，或把字体路径加进 FONT_CANDIDATES。"
    )


FONT_PATH = find_font()


def load_font(size: int):
    try:
        return ImageFont.truetype(str(FONT_PATH), size, index=0)
    except Exception:
        return ImageFont.truetype(str(FONT_PATH), size)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def paint_wood(img: Image.Image) -> None:
    draw = ImageDraw.Draw(img)
    w, h = img.size
    for y in range(h):
        draw.line([(0, y), (w, y)], fill=lerp(WOOD_TOP, WOOD_BOTTOM, y / max(1, h - 1)))


def draw_board_grid(draw, x0, y0, x1, y1, n, color, width=2):
    """在 (x0,y0)-(x1,y1) 区域内画 n 路棋盘网格"""
    step_x = (x1 - x0) / (n - 1)
    step_y = (y1 - y0) / (n - 1)
    for i in range(n):
        x = x0 + i * step_x
        y = y0 + i * step_y
        draw.line([(x, y0), (x, y1)], fill=color, width=width)
        draw.line([(x0, y), (x1, y)], fill=color, width=width)


def draw_stone(draw, cx, cy, r, base, highlight):
    """带落影与高光的棋子"""
    draw.ellipse([cx - r, cy - r + r * 0.13, cx + r, cy + r + r * 0.13], fill=(150, 115, 78))
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=base)
    hr = r * 0.45
    hx, hy = cx - r * 0.28, cy - r * 0.32
    draw.ellipse([hx - hr, hy - hr, hx + hr, hy + hr], fill=highlight)


# ---------------------------------------------------------------- 横幅 320x180

def build_banner(w=320, h=180):
    # 4x 超采样再缩小，得到干净的抗锯齿边缘
    S = 4
    img = Image.new("RGB", (w * S, h * S))
    paint_wood(img)
    draw = ImageDraw.Draw(img)
    W, H = w * S, h * S

    # 右侧的棋盘局部（淡色，作为背景纹理）
    # 起点定在 0.60 而不是 0.55：标题「黑猪围棋」四字约到 0.58 处，
    # 棋盘线再往左就会和文字打架。
    draw_board_grid(
        draw,
        x0=W * 0.60, y0=H * 0.06, x1=W * 1.02, y1=H * 1.02,
        n=5, color=(178, 138, 94), width=int(1.6 * S),
    )

    # 两颗棋子：黑（黑猪大人）与白（黑猪勇士）。
    # 位置必须避开标题文字 —— 第一版棋子压在「棋」字上，所以整体右移。
    draw_stone(draw, W * 0.765, H * 0.37, H * 0.122, BLACK_STONE, (76, 76, 84))
    draw_stone(draw, W * 0.905, H * 0.70, H * 0.122, WHITE_STONE, (255, 255, 252))

    # 标题。字号收到 42，四字总宽约 168px（32px 图宽的一半），左侧留白充足
    title_font = load_font(42 * S)
    draw.text((W * 0.055, H * 0.30), "黑猪围棋", font=title_font, fill=TITLE_COLOR)

    # 副标题
    sub_font = load_font(14 * S)
    draw.text((W * 0.062, H * 0.64), "和黑猪大人下棋", font=sub_font, fill=SUBTITLE_COLOR)

    return img.resize((w, h), Image.LANCZOS)


# ---------------------------------------------------------------- 图标

def build_icon(size):
    S = 4
    img = Image.new("RGB", (size * S, size * S), (0, 0, 0))
    draw = ImageDraw.Draw(img)
    W = size * S

    # 圆角木色底
    radius = int(W * 0.22)
    for y in range(W):
        t = y / max(1, W - 1)
        draw.line([(0, y), (W, y)], fill=lerp(WOOD_TOP, WOOD_BOTTOM, t))
    mask = Image.new("L", (W, W), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, W - 1, W - 1], radius=radius, fill=255)
    img.putalpha(mask)

    overlay = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)

    # 交叉的棋盘线
    lw = max(2, int(W * 0.014))
    od.line([(W * 0.28, W * 0.16), (W * 0.28, W * 0.84)], fill=LINE_COLOR + (235,), width=lw)
    od.line([(W * 0.72, W * 0.16), (W * 0.72, W * 0.84)], fill=LINE_COLOR + (235,), width=lw)
    od.line([(W * 0.16, W * 0.28), (W * 0.84, W * 0.28)], fill=LINE_COLOR + (235,), width=lw)
    od.line([(W * 0.16, W * 0.72), (W * 0.84, W * 0.72)], fill=LINE_COLOR + (235,), width=lw)

    # 一黑一白两颗子
    r = W * 0.195
    def stone_rgba(d, cx, cy, rr, base, hl):
        d.ellipse([cx - rr, cy - rr + rr * 0.13, cx + rr, cy + rr + rr * 0.13],
                  fill=(150, 115, 78, 255))
        d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=base + (255,))
        hr = rr * 0.45
        d.ellipse([cx - rr * 0.28 - hr, cy - rr * 0.32 - hr,
                   cx - rr * 0.28 + hr, cy - rr * 0.32 + hr], fill=hl + (255,))

    stone_rgba(od, W * 0.28, W * 0.28, r, BLACK_STONE, (74, 74, 82))
    stone_rgba(od, W * 0.72, W * 0.72, r, WHITE_STONE, (255, 255, 252))

    img = Image.alpha_composite(img, overlay)
    # 必须保留 alpha 通道：圆角之外要真透明。
    # 第一版误写成 convert("RGB")，透明区被填成黑色，方形底板上会露出四个黑角。
    return img.resize((size, size), Image.LANCZOS)


# ---------------------------------------------------------------- 入口

def main():
    print(f"使用字体：{FONT_PATH}")

    target = RES / "drawable-xhdpi" / "banner.png"
    target.parent.mkdir(parents=True, exist_ok=True)
    build_banner().save(target)
    print(f"  横幅 {target.relative_to(ROOT)}  (320x180)")

    for folder, size in (
        ("mipmap-mdpi", 48),
        ("mipmap-hdpi", 72),
        ("mipmap-xhdpi", 96),
        ("mipmap-xxhdpi", 144),
        ("mipmap-xxxhdpi", 192),
    ):
        p = RES / folder / "ic_launcher.png"
        p.parent.mkdir(parents=True, exist_ok=True)
        build_icon(size).save(p)
        print(f"  图标 {p.relative_to(ROOT)}  ({size}x{size})")

    print("完成。")


if __name__ == "__main__":
    main()