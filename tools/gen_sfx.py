#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
合成对局音效，输出到 app/src/main/res/raw/。

## 为什么程序合成而不是找素材
CC0 的木质敲盘声音效不易凑齐风格统一的整套（多是钢琴、木鱼、电子音混在一起）。
合成则完全无版权风险、体积可控，而且六种音效的音色天然一致 —— 都出自同一个
「木质敲击」模型，只是频率、衰减、叠音数不同。

## 音色模型
落子声 = 白噪声激励 + 二阶谐振器（模拟木头的共振）+ 指数衰减包络。
这是物理建模里最简单的「敲击声」模型：噪声负责瞬态的「啪」，
谐振器负责木头的音高感，包络决定「干」还是「余音长」。

输出 16-bit PCM WAV（44.1kHz 单声道）。选 WAV 而不是 OGG 是为了免掉编码器依赖 ——
六个音效加起来不到 100KB，不值得为压缩去装 ffmpeg。

运行：python3 tools/gen_sfx.py
"""
import math
import random
import struct
import wave
from pathlib import Path

SR = 44100
ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "app" / "src" / "main" / "res" / "raw"


# ---------------------------------------------------------------- 基础构件

def resonator(freq: float, pole: float, n: int, rng: random.Random) -> list[float]:
    """
    二阶谐振器，用白噪声激励。

    y[n] = 2·r·cos(ω₀)·y[n-1] − r²·y[n-2] + x[n]

    pole 越接近 1，共振越尖锐、余音越长。0.995 左右是「木头」的听感。
    """
    w0 = 2.0 * math.pi * freq / SR
    a1 = 2.0 * pole * math.cos(w0)
    a2 = -pole * pole
    out = [0.0] * n
    y1 = y2 = 0.0
    for i in range(n):
        x = rng.uniform(-1.0, 1.0)
        y = a1 * y1 + a2 * y2 + x
        y2, y1 = y1, y
        out[i] = y
    return out


def exponential_envelope(n: int, attack_s: float, tau_s: float) -> list[float]:
    """快起慢落的包络：attack 段线性上升，之后指数衰减。"""
    attack = max(1, int(attack_s * SR))
    tau = max(1e-4, tau_s) * SR
    env = [0.0] * n
    for i in range(n):
        if i < attack:
            env[i] = i / attack
        else:
            env[i] = math.exp(-(i - attack) / tau)
    return env


def normalize(samples: list[float], peak: float = 0.85) -> list[float]:
    m = max((abs(v) for v in samples), default=0.0)
    if m < 1e-9:
        return samples
    k = peak / m
    return [v * k for v in samples]


def mix_into(dest: list[float], src: list[float], offset: int, gain: float = 1.0) -> None:
    for i, v in enumerate(src):
        j = offset + i
        if 0 <= j < len(dest):
            dest[j] += v * gain


def fade_edges(samples: list[float], fade_ms: float = 6.0) -> list[float]:
    """首尾各做一次淡入淡出，消除爆音。"""
    n = len(samples)
    f = min(int(fade_ms * SR / 1000), n // 2)
    out = list(samples)
    for i in range(f):
        k = i / f
        out[i] *= k
        out[n - 1 - i] *= k
    return out


def write_wav(path: Path, samples: list[float]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    data = b"".join(struct.pack("<h", int(max(-1.0, min(1.0, v)) * 32767)) for v in samples)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(data)
    print(f"  {path.name}  {len(samples) / SR * 1000:.0f} ms  {len(data) // 1024} KB")


# ---------------------------------------------------------------- 音效

def stone_place(rng: random.Random) -> list[float]:
    """己方落子：干脆的「啪」。"""
    n = int(0.17 * SR)
    body = resonator(freq=1150.0, pole=0.9962, n=n, rng=rng)
    env = exponential_envelope(n, attack_s=0.0012, tau_s=0.026)
    mixed = [body[i] * env[i] for i in range(n)]
    return fade_edges(normalize(mixed, 0.88))


def stone_ai(rng: random.Random) -> list[float]:
    """对手落子：同音色但更闷更轻，听感上区分敌我。"""
    n = int(0.15 * SR)
    body = resonator(freq=880.0, pole=0.9948, n=n, rng=rng)
    env = exponential_envelope(n, attack_s=0.0018, tau_s=0.022)
    mixed = [body[i] * env[i] for i in range(n)]
    return fade_edges(normalize(mixed, 0.60))


def stone_capture(rng: random.Random) -> list[float]:
    """提子：几颗子连续被拿走，比落子更长、更「碎」。"""
    n = int(0.42 * SR)
    buf = [0.0] * n
    # 4 次敲击，间隔 34~62ms 不等，模拟棋子被逐个提掉
    offsets_ms = [0, 38, 62, 105]
    gains = [1.0, 0.82, 0.9, 0.7]
    for off_ms, gain in zip(offsets_ms, gains):
        seg_n = n - int(off_ms * SR / 1000)
        seg_n = min(seg_n, int(0.20 * SR))
        body = resonator(freq=1250.0 + rng.uniform(-140, 140), pole=0.9958, n=seg_n, rng=rng)
        env = exponential_envelope(seg_n, attack_s=0.0010, tau_s=0.020)
        seg = [body[i] * env[i] for i in range(seg_n)]
        mix_into(buf, seg, int(off_ms * SR / 1000), gain)
    return fade_edges(normalize(buf, 0.92))


def undo_sound() -> list[float]:
    """悔棋：频率快速下滑的短音，听感上是「收回」。"""
    n = int(0.22 * SR)
    out = [0.0] * n
    phase = 0.0
    for i in range(n):
        t = i / n
        freq = 900.0 - 520.0 * (t ** 0.65)     # 900Hz 滑到 380Hz
        phase += 2.0 * math.pi * freq / SR
        env = math.exp(-3.2 * t) * min(1.0, i / 200.0)
        out[i] = math.sin(phase) * env * 0.55
    return fade_edges(normalize(out, 0.72))


def illegal_sound() -> list[float]:
    """非法落子：低沉短促的「嘟」，明确表示「不行」。"""
    n = int(0.13 * SR)
    out = [0.0] * n
    for i in range(n):
        t = i / n
        # 方波 + 少量二次谐波，听感比正弦更「硬」
        s = 1.0 if math.sin(2 * math.pi * 210.0 * i / SR) >= 0 else -1.0
        s += 0.3 * math.sin(2 * math.pi * 420.0 * i / SR)
        env = min(1.0, i / 120.0) * math.exp(-4.5 * t)
        out[i] = s * env * 0.4
    return fade_edges(normalize(out, 0.66), fade_ms=4.0)


def _arpeggio(freqs: list[float], note_ms: float) -> list[float]:
    per = int(note_ms * SR / 1000)
    n = per * len(freqs)
    out = [0.0] * n
    for idx, freq in enumerate(freqs):
        start = idx * per
        for i in range(per):
            t = i / per
            phase = 2.0 * math.pi * freq * i / SR
            # 基频 + 三次谐波，比纯正弦更像「音阶提示音」
            s = math.sin(phase) + 0.22 * math.sin(3 * phase)
            env = min(1.0, i / 260.0) * math.exp(-2.4 * t)
            out[start + i] += s * env * 0.42
    return fade_edges(normalize(out, 0.80))


def win_sound() -> list[float]:
    """获胜：上行大三和弦琶音。"""
    return _arpeggio([523.25, 659.25, 783.99, 1046.50], note_ms=130)   # C5 E5 G5 C6


def lose_sound() -> list[float]:
    """落败：下行小调琶音，克制不刺耳。"""
    return _arpeggio([659.25, 554.37, 440.00], note_ms=165)            # E5 C#5 A4


def main() -> None:
    rng = random.Random(20261001)   # 固定种子，保证每次生成结果一致
    print(f"输出目录：{OUT_DIR.relative_to(ROOT)}")
    write_wav(OUT_DIR / "stone_place.wav", stone_place(rng))
    write_wav(OUT_DIR / "stone_ai.wav", stone_ai(rng))
    write_wav(OUT_DIR / "stone_capture.wav", stone_capture(rng))
    write_wav(OUT_DIR / "undo.wav", undo_sound())
    write_wav(OUT_DIR / "illegal.wav", illegal_sound())
    write_wav(OUT_DIR / "win.wav", win_sound())
    write_wav(OUT_DIR / "lose.wav", lose_sound())
    print("完成。")


if __name__ == "__main__":
    main()
