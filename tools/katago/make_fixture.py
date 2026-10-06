#!/usr/bin/env python3
"""造「Kotlin 前向 vs PyTorch 前向」的对齐夹具。

为什么必须做这件事：端侧推理是手写的 conv/relu，张量布局（NCHW 的 C 序展开）
或权重偏移只要差一点，**不会报任何错**，只会让网络输出垃圾 —— 而垃圾网络接进
MCTS 之后，表现是"棋力没提升"，根本看不出是移植错了还是本来就不行。
所以先用固定局面把两侧输出对到浮点容差以内，再谈效果。

产出（放进 core 的测试资源）：
  pv9_manifest.json   层名/形状/偏移
  pv9_weights.bin     平铺 float32
  pv9_expected.json   3 个固定局面的 期望策略(81) + 期望目差 + 棋盘 cells + toMove
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import torch

HERE = Path(__file__).resolve().parent

# **必须限制线程数**：这么小的网络在 24 核上默认开满线程，
# 实测被线程调度开销拖到 2.7 秒/步（净算时间不到 50ms）。
torch.set_num_threads(4)
sys.path.insert(0, str(HERE))
from grade import GTP_LETTERS                     # noqa: E402
from train_pv import load_jsonl, PolicyValueNet, export_for_kotlin  # noqa: E402

RES_DIR = HERE.parent.parent / "src" / "WeiqiTV" / "core" / "src" / "test" / "resources" / "net"


def cells_of(sample: dict, size: int) -> list[int]:
    """局面 → 0/1/2 的棋盘数组（行优先，y 自上而下），与 Kotlin 侧一致。"""
    cells = [0] * (size * size)
    for i, mv in enumerate(sample["moves"]):
        if mv == "pass":
            continue
        col = GTP_LETTERS.index(mv[0])
        row = size - int(mv[1:])
        cells[row * size + col] = 1 if i % 2 == 0 else 2
    return cells


def main() -> int:
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--steps", type=int, default=150)
    ap.add_argument("--limit", type=int, default=1500)
    ap.add_argument("--size", type=int, default=9)
    ap.add_argument("--width", type=int, default=32)
    ap.add_argument("--blocks", type=int, default=2)
    args = ap.parse_args()
    size, width, blocks = args.size, args.width, args.blocks
    data = HERE / "distill-9-all.jsonl"
    if not data.exists():
        data = HERE / "distill-9-w0.jsonl"
    print(f"  读数据 {data.name} …", flush=True)
    X, P, V, M = load_jsonl(data, size, limit=args.limit)
    print(f"  用 {len(X)} 个样本训练夹具网络（{width} 通道 × {blocks} 块）", flush=True)

    torch.manual_seed(0)
    net = PolicyValueNet(size, width, blocks)
    opt = torch.optim.Adam(net.parameters(), lr=2e-3)
    tx = torch.tensor(X); tp = torch.tensor(P); tv = torch.tensor(V / 10.0); tm = torch.tensor(M)
    import torch.nn.functional as F
    for step in range(args.steps):
        sel = torch.randint(0, len(tx), (64,))
        pp, pv = net(tx[sel])
        logp = F.log_softmax(pp.masked_fill(tm[sel] <= 0, -1e9), dim=1)
        loss = -(tp[sel] * logp).sum(dim=1).mean() + F.mse_loss(pv, tv[sel])
        opt.zero_grad(); loss.backward(); opt.step()
        if step % 50 == 0:
            print(f"    step {step} loss {loss.item():.3f}", flush=True)
    print(f"  训练完成，loss={loss.item():.3f}", flush=True)

    RES_DIR.mkdir(parents=True, exist_ok=True)
    manifest = export_for_kotlin(net, RES_DIR / "pv9_weights.bin", k_winrate=0.5)
    (RES_DIR / "pv9_manifest.json").write_text(json.dumps(manifest, indent=1))
    print(f"  权重 {manifest['bytes']/1024:.0f} KB  层数 {len(manifest['layers'])}")

    # 取 3 个不同阶段的局面做夹具
    samples = []
    with data.open(encoding="utf-8") as fh:
        for line in fh:
            try:
                s = json.loads(line)
            except Exception:
                continue
            if s.get("size") == size:
                samples.append(s)
    picks = [samples[0], samples[len(samples) // 2], samples[-1]]

    cases = []
    net.eval()
    with torch.no_grad():
        for s in picks:
            cells = cells_of(s, size)
            x = np.zeros((1, 3, size, size), dtype=np.float32)
            for i, c in enumerate(cells):
                y, xx = divmod(i, size)
                if c == 1: x[0, 0, y, xx] = 1.0
                if c == 2: x[0, 1, y, xx] = 1.0
            x[0, 2, :, :] = 1.0 if s["toMove"] == "B" else 0.0
            pp, pv = net(torch.tensor(x))
            # 与 Kotlin 完全相同的后处理：掩码 + softmax 归一化
            logits = pp[0].numpy().astype(np.float64)
            mask = np.array([1.0 if c == 0 else 0.0 for c in cells])
            e = np.exp(logits) * mask
            tot = e.sum()
            pol = (e / tot) if tot > 0 else e
            cases.append({
                "toMove": s["toMove"],
                "cells": cells,
                "policy": [float(v) for v in pol],
                "scoreLead": float(pv[0, 0].item() * 10.0),
            })
    (RES_DIR / "pv9_expected.json").write_text(json.dumps({"size": size, "cases": cases}, indent=1))
    print(f"  夹具 3 个局面已写出 → {RES_DIR}")
    print(f"    例：{cases[0]['toMove']} 走  scoreLead={cases[0]['scoreLead']:+.2f}  "
          f"策略最大 {max(cases[0]['policy']):.3f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
