#!/usr/bin/env python3
"""训练 9 路「策略 + 价值」小网络（蒸馏自 KataGo）。

**按电视 CPU 的算力倒着设计**，不是按论文：
  6 秒预算要能跑上百次前向 → 单次前向必须 ≤30ms → 在弱 ARM CPU 上约 2~3M MAC
  ⇒ 原型取 32 通道 × 2 残差块（约 3 万参数），而不是动辄几十层的大网络。
  参数少不代表没用：网络引导的 MCTS 与"随机 rollout 的 MCTS"是两个量级的东西。

导出：**把 BatchNorm 折进卷积权重**，端侧 Kotlin 只需实现 conv + relu，
  不引任何推理框架（app 是离线的，加 ONNX/TFLite 只会涨体积和失败面）。
  导出物 = weights.bin（float32 平铺）+ manifest.json（每层的形状与顺序）。

用法：
  python3 train_pv.py --data distill-9-all.jsonl --out pv9 --steps 4000 --width 32 --blocks 2
"""
from __future__ import annotations

import argparse
import json
import math
import struct
import sys
import time
from pathlib import Path

import numpy as np
import os
import torch
import torch.nn as nn

# **必须限制线程数**：这么小的网络在 24 核上默认开满线程，
# 实测被线程调度开销拖到 2.7 秒/步（净算时间不到 50ms）。
torch.set_num_threads(4)
import torch.nn.functional as F

KATAGO_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(KATAGO_DIR))
from grade import GTP_LETTERS  # noqa: E402


# ============================================================
# 数据
# ============================================================

GTP_LETTERS = "ABCDEFGHJKLMNOPQRST"


def _replay_board(moves, size: int) -> dict:
    """按围棋规则**重放棋谱**，返回 {(row, col): 'B'/'W'}。

    ⚠️ 为什么必须真的模拟提子（2026-10-05 抓到的真 BUG）：
    原先是"逐手累加落点"，被吃掉的子**不会消失**，于是留下"幽灵子"；
    更糟的是同一点被双方先后下过时，黑子平面和白子平面会**同时标 1**
    （实测 9 路 400 条里有 17 条，4.25%）。网络在这些局面上学的是
    "不存在的棋子分布"，而且"我方/对方"的划分也就错了。
    提子规则本身简单，没有理由不做。

    （不做劫争合法性校验：语料是 KataGo 生成的合法对局，不会出现非法劫。）
    """
    dirs = ((1, 0), (-1, 0), (0, 1), (0, -1))

    def neighbors(p):
        r, c = p
        for dr, dc in dirs:
            nr, nc = r + dr, c + dc
            if 0 <= nr < size and 0 <= nc < size:
                yield (nr, nc)

    def group_libs(p):
        """返回 (同色连通块, 该块的气)。"""
        color = board[p]
        seen, stack, libs = {p}, [p], set()
        while stack:
            q = stack.pop()
            for n in neighbors(q):
                if n not in board:
                    libs.add(n)
                elif board[n] == color and n not in seen:
                    seen.add(n)
                    stack.append(n)
        return seen, libs

    board: dict = {}
    for i, mv in enumerate(moves):
        if mv == "pass":
            continue
        color = "B" if i % 2 == 0 else "W"
        opp = "W" if color == "B" else "B"
        p = (size - int(mv[1:]), GTP_LETTERS.index(mv[0]))
        board[p] = color
        for n in list(neighbors(p)):            # 提掉对方无气的块
            if board.get(n) == opp and n in board:
                grp, libs = group_libs(n)
                if not libs:
                    for q in grp:
                        del board[q]
        if p in board:                          # 自杀（合法前提下罕见，但不能产生幽灵子）
            grp, libs = group_libs(p)
            if not libs:
                for q in grp:
                    del board[q]
    return board


def encode(sample: dict, size: int) -> tuple[np.ndarray, np.ndarray, np.ndarray, float]:
    """局面 → (3×size×size 特征, 81 维策略目标, 1 维价值目标(目差), 是否合法掩码)。"""
    plane_b = np.zeros((size, size), dtype=np.float32)
    plane_w = np.zeros((size, size), dtype=np.float32)
    # ⚠️ 提子修复开关（2026-10-05 排查用）：WEIQI_NO_REPLAY=1 时回到旧的"逐手累加"
    # （即保留"幽灵子"缺陷），用于分辨它是否影响了棋力。
    if os.environ.get("WEIQI_NO_REPLAY") == "1":
        for i, mv in enumerate(sample["moves"]):
            if mv == "pass":
                continue
            col = GTP_LETTERS.index(mv[0])
            row = size - int(mv[1:])
            (plane_b if i % 2 == 0 else plane_w)[row, col] = 1.0
    else:
        for (r, c), color in _replay_board(sample["moves"], size).items():
            (plane_b if color == "B" else plane_w)[r, c] = 1.0
    # ⚠️ 视角统一（2026-10-05 关键修正）：输入永远按「我方 / 对方」组织，
    # **不暴露"黑/白"这个标签**。
    #
    # 为什么必须这样：原先输入是 [黑子, 白子, 是否轮到黑]，网络因此"有资格"
    # 学出偏袒某一色的函数 —— 实测偏色 2~4 目且**符号随机**，用数据增强
    # （棋盘 8 对称 + 黑白互换，甚至把换色改成必然）都压不住：增强只约束了
    # 训练样本点，管不住泛化。同档自战因此变成黑胜 25% / 白胜 75%。
    # 改成相对视角后，"黑白互换"对网络而言是**恒等变换** ⇒ 偏色在数学上
    # 不可能发生，不再依赖数据量、不再靠运气。
    #
    # ⚠️ 端侧 PolicyValueNet.evaluate() 必须与此处**逐字对齐**，否则网络输出全错。
    # ⚠️ 价值目标必须换算到「我方视角」——KataGo 的 scoreLead 是**黑方视角**。
    # 实证：某样本 黑−白=+1、toMove=W、scoreLead=−18.86、winrate=0.007，
    # 而 winrate 被显式钉为黑方视角（reportAnalysisWinratesAs=BLACK）⇒ 0.007 表示黑方极劣；
    # 若 scoreLead 是"行棋方(W)视角"，−18.86 就该对应黑大优，与 winrate 矛盾。
    # 只有"黑方视角"能自洽。⚠️ 我曾在 v5 里误写"scoreLead 就是行棋方视角、不必取反"，
    # 结果价值头学的是"黑方目差"，网络仍须区分颜色来解释 ⇒ 偏色照旧（各档 +1.2 目）。
    raw_lead = float(sample["scoreLead"])
    # ⚠️ 价值视角开关（2026-10-05 排查用）：WEIQI_RAW_VALUE=1 时直接用 scoreLead 原值
    # （= 发货版做法：网络输出"黑方视角"目差，而引擎按"行棋方视角"读 ⇒ 历史上是错读的）。
    # 用于分辨"价值目标换算"是否影响了棋力。
    if os.environ.get("WEIQI_RAW_VALUE") == "1":
        v_target = raw_lead
    else:
        v_target = raw_lead if sample["toMove"] == "B" else -raw_lead

    # ⚠️ 视角开关（2026-10-05 排查用）：设 WEIQI_ABS_VIEW=1 回到**绝对视角**
    # [黑子, 白子, 是否轮到黑]，用于分辨"相对视角"是否损害了棋力。
    # 实测背景：同训练量下，绝对视角（旧）每手丢 0.64 目、相对视角（新）1.68 目
    # ⇒ 需要逐项回滚定位到底是"相对视角"还是"提子修复"造成的。
    if os.environ.get("WEIQI_ABS_VIEW") == "1":
        my, opp = plane_b, plane_w
        to_move = np.full((size, size), 1.0 if sample["toMove"] == "B" else 0.0, dtype=np.float32)
    else:
        my, opp = (plane_b, plane_w) if sample["toMove"] == "B" else (plane_w, plane_b)
        to_move = np.ones((size, size), dtype=np.float32)   # 相对视角下恒为"轮到我方"
    feats = np.stack([my, opp, to_move])

    # 策略目标：KataGo 的访问分布，只保留合法点（网络不需要学"不能下的点"）
    tgt = np.zeros(size * size, dtype=np.float32)
    occupied = plane_b + plane_w
    for mv, p in sample["policy"].items():
        # KataGo 的 moveInfos 里**含 pass**（82 个候选中 81 个点 + 停一手）。
        # 网络的策略头只覆盖 81 个点：停一手的判断由引擎自己的收工规则负责，
        # 不需要网络学。丢掉 pass 后重新归一化即可（分布形状不变）。
        if mv == "pass":
            continue
        col = GTP_LETTERS.index(mv[0])
        row = size - int(mv[1:])
        idx = row * size + col
        if occupied[row, col] == 0:
            tgt[idx] = p
    s = tgt.sum()
    if s > 0:
        tgt /= s
    # 掩码要与策略同形状（摊平到 81），否则 masked_fill 会报形状不匹配
    mask = (occupied == 0).astype(np.float32).reshape(-1)
    return feats, tgt, np.array([v_target], dtype=np.float32), mask


def _random_symmetry(f: np.ndarray, t: np.ndarray, m: np.ndarray, size: int, k: int):
    """棋盘的正方形对称变换（8 种：4 旋转 × 2 翻折），三个张量同步变换。"""
    if k == 0:
        return f, t, m
    tt = t.reshape(size, size)
    mm = m.reshape(size, size)
    if k & 1:                       # 左右翻
        f = f[:, :, ::-1]; tt = tt[:, ::-1]; mm = mm[:, ::-1]
    if k & 2:                       # 上下翻
        f = f[:, ::-1, :]; tt = tt[::-1, :]; mm = mm[::-1, :]
    if k & 4:                       # 转置（沿主对角翻）
        f = f.transpose(0, 2, 1); tt = tt.T; mm = mm.T
    return np.ascontiguousarray(f), np.ascontiguousarray(tt).reshape(-1), np.ascontiguousarray(mm).reshape(-1)


def augment(f: np.ndarray, t: np.ndarray, v: np.ndarray, m: np.ndarray, size: int, rng: np.random.Generator):
    """数据增强：随机「棋盘对称变换」+ 50% 概率「黑白互换」。

    ## 为什么必须做（2026-10-05，一整天的排查结论）
    9 路线上实测：同一个网络在「轮到黑棋」的局面下每手丢 1.35 目、在「轮到白棋」的
    局面下丢 4.45 目（差 3.10 目）—— 网络**执白明显更差**。而真机对局的统计更狠：
    同档自战黑方胜率只有 23%、白方 74%。娃无论执黑执白，一半的局面从一开始就不公平。

    试过的错路（都记下来免得再走）：
      · 换更强的网络当「终局裁判」→ 略好但没解决
      · 低档加「落后方不许停手」→ 适得其反（废局 10 → 40 局，已回滚）
      · 按数据量压训练遍数重训 → **时好时坏、不单调**
        （16×1 @1000 对称、@3000 偏 +3.77、@8000 又对称）
        ⇒ 偏色**不是**数据量的函数，而是训练的随机性 —— 靠重训碰运气。

    真正的解法是把这个对称性做成**硬约束**：围棋的规则本身是黑白对称的，
    棋盘也有 8 种对称。让每个训练样本随机做这些变换（含黑白互换），网络就
    **不可能**学出"偏袒某一色"的函数 —— 这是 AlphaGo/Leela 的标准做法。

    ⚠️ 黑白互换时：特征的两个颜色平面要交换、行棋方平面取反、**目差目标取反**
    （视角变了）；而**策略目标与掩码不变**（落子点是同一个点）。
    ⚠️ 空间变换时：特征/策略/掩码必须用**同一个** k 变换，否则标签会错位。
    """
    k = int(rng.integers(0, 8))
    f, t, m = _random_symmetry(f, t, m, size, k)

    # ── 绝对视角下必须补上「黑白互换」，否则颜色对称性没有任何约束 ──────────────
    # ⚠️ 这段的前提与上一段注释相反，别搞混：
    #   · 相对视角（默认，"我方/对方/恒 1"）：换色对网络是恒等变换 ⇒ 什么也不用做。
    #   · 绝对视角（WEIQI_ABS_VIEW=1，[黑子, 白子, 是否轮到黑]）：网络**有资格**
    #     学出偏袒某色的函数，必须有数据侧的硬约束。
    #
    # 踩过的坑（2026-10-05）：`augment()` 里原本有换色，后来视角统一成相对视角时
    # 被删掉了。但训练脚本仍支持 WEIQI_ABS_VIEW=1，于是"绝对视角 + 无换色增强"
    # 这个组合静默存在 —— 用它训出来的网络必然偏色。实测：kt9-master 同网自战
    # 黑 20/32 = 62%、kt13-master 黑 18/32 = 56%（而用旧增强训的发货版 9 路是 16:16）。
    #
    # 变换的正确写法：颜色平面互换、行棋方平面取反、**价值目标取反**
    # （价值是"行棋方目差"，换色后行棋方换成对手 ⇒ 符号翻转），
    # 而策略目标与掩码不变（落子点是同一个交叉点）。
    if os.environ.get("WEIQI_ABS_VIEW") == "1":
        f = f[[1, 0, 2]].copy()      # [黑子, 白子] → [白子, 黑子]
        f[2] = 1.0 - f[2]            # 行棋方平面取反
        v = -v                       # 价值目标取反
    return f, t, v, m


def load_jsonl(path: Path, size: int, limit: int | None) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """读取语料。每个原始样本额外产出一个「对称+换色」增强样本（数据量 ×2）。

    增强默认开启 —— 它是"网络黑白对称"的硬约束，关掉就会退回到偏色的老毛病。
    需要对照实验时用 WEIQI_AUGMENT=0 关闭。
    ⚠️ `limit` 按**原始样本数**计（先切片再增强），保证各档的样本量语义不变。
    """
    use_aug = os.environ.get("WEIQI_AUGMENT", "1") != "0"
    rng = np.random.default_rng(20261005)      # 固定种子：可复现
    X, P, V, M = [], [], [], []
    G: list[str] = []        # gameId：训练/验证必须按「局」划分，见 main() 的说明
    n_raw = 0
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            if limit and n_raw >= limit:
                break
            try:
                s = json.loads(line)
            except Exception:
                continue
            if s.get("size") != size:
                continue
            n_raw += 1
            # 老语料没有 gameId ⇒ 用行号兜底（等价于按样本划分，会打印警告）
            gid = s.get("gameId") or f"legacy-{n_raw}"
            f, p, v, m = encode(s, size)
            X.append(f); P.append(p); V.append(v); M.append(m); G.append(gid)
            if use_aug:
                fa, pa, va, ma = augment(f.copy(), p.copy(), v.copy(), m.copy(), size, rng)
                X.append(fa); P.append(pa); V.append(va); M.append(ma); G.append(gid)
    return (np.stack(X), np.stack(P), np.stack(V), np.stack(M), np.array(G))


# ============================================================
# 网络
# ============================================================

class Block(nn.Module):
    def __init__(self, c: int):
        super().__init__()
        self.c1, self.b1 = nn.Conv2d(c, c, 3, padding=1, bias=False), nn.BatchNorm2d(c)
        self.c2, self.b2 = nn.Conv2d(c, c, 3, padding=1, bias=False), nn.BatchNorm2d(c)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        y = F.relu(self.b1(self.c1(x)))
        y = self.b2(self.c2(y))
        return F.relu(x + y)


class PolicyValueNet(nn.Module):
    def __init__(self, size: int, width: int, blocks: int):
        super().__init__()
        self.size = size
        self.stem = nn.Conv2d(3, width, 3, padding=1, bias=False)
        self.stem_bn = nn.BatchNorm2d(width)
        self.blocks = nn.ModuleList([Block(width) for _ in range(blocks)])
        # 策略头：1×1 卷积到 1 通道 = 每个点的对数几率
        self.p_conv = nn.Conv2d(width, 16, 1, bias=False)
        self.p_bn = nn.BatchNorm2d(16)
        self.p_out = nn.Conv2d(16, 1, 1)
        # 价值头：池化 + 全连接，输出 [胜率, 目差]
        self.v_conv = nn.Conv2d(width, 8, 1, bias=False)
        self.v_bn = nn.BatchNorm2d(8)
        self.v_fc1 = nn.Linear(8 * size * size, 64)
        # **单输出**，目标 = scoreLead/10。
        # 原来写 2 输出（想同时出胜率和目差），但目标只有 1 维，PyTorch 会静默广播 ——
        # 两个输出学同一个值，白占参数还看不出错。目差是连续量、不饱和，比胜率好训。
        self.v_fc2 = nn.Linear(64, 1)

    def forward(self, x: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        x = F.relu(self.stem_bn(self.stem(x)))
        for b in self.blocks:
            x = b(x)
        p = F.relu(self.p_bn(self.p_conv(x)))
        p = self.p_out(p).flatten(1)                       # (N, size*size)
        v = F.relu(self.v_bn(self.v_conv(x))).flatten(1)
        v = F.relu(self.v_fc1(v))
        v = self.v_fc2(v)                                  # (N, 2)
        return p, v


# ============================================================
# 导出：BN 折进卷积 → 平铺权重给 Kotlin
# ============================================================

def fold_conv_bn(conv: nn.Conv2d, bn: nn.BatchNorm2d) -> tuple[np.ndarray, np.ndarray]:
    """把 BN 折进前面的卷积，得到等价的 (W, b)。端侧就只剩 conv + relu。"""
    w = conv.weight.detach().cpu().numpy().astype(np.float32)
    gamma = bn.weight.detach().cpu().numpy().astype(np.float32)
    beta = bn.bias.detach().cpu().numpy().astype(np.float32)
    mean = bn.running_mean.cpu().numpy().astype(np.float32)
    var = bn.running_var.cpu().numpy().astype(np.float32)
    scale = gamma / np.sqrt(var + bn.eps)
    w_folded = w * scale.reshape(-1, 1, 1, 1)
    b_folded = beta - mean * scale
    return w_folded.astype(np.float32), b_folded.astype(np.float32)


def export_for_kotlin(net: PolicyValueNet, path: Path, k_winrate: float = 0.5,
                      view: str | None = None) -> dict:
    """导出给端侧。`view` 会写进 manifest，端侧据此构造输入平面。

    ⚠️ 必须与训练时的 `encode()` 保持一致（`WEIQI_ABS_VIEW=1` ⇒ absolute）：
    端侧若用错视角构造输入，网络输出会全错 —— 而且不报错，只是棋力变差。
    """
    layers: list[dict] = []
    blob = bytearray()

    def add(name: str, arr: np.ndarray) -> None:
        a = np.ascontiguousarray(arr, dtype=np.float32)
        layers.append({"name": name, "shape": list(a.shape), "offset": len(blob) // 4})
        blob.extend(a.tobytes())

    w, b = fold_conv_bn(net.stem, net.stem_bn)
    add("stem.w", w); add("stem.b", b)
    for i, blk in enumerate(net.blocks):
        w1, b1 = fold_conv_bn(blk.c1, blk.b1)
        w2, b2 = fold_conv_bn(blk.c2, blk.b2)
        add(f"block{i}.c1.w", w1); add(f"block{i}.c1.b", b1)
        add(f"block{i}.c2.w", w2); add(f"block{i}.c2.b", b2)
    w, b = fold_conv_bn(net.p_conv, net.p_bn)
    add("pconv.w", w); add("pconv.b", b)
    add("pout.w", net.p_out.weight.detach().cpu().numpy().astype(np.float32))
    add("pout.b", net.p_out.bias.detach().cpu().numpy().astype(np.float32))
    w, b = fold_conv_bn(net.v_conv, net.v_bn)
    add("vconv.w", w); add("vconv.b", b)
    add("vfc1.w", net.v_fc1.weight.detach().cpu().numpy().astype(np.float32))
    add("vfc1.b", net.v_fc1.bias.detach().cpu().numpy().astype(np.float32))
    add("vfc2.w", net.v_fc2.weight.detach().cpu().numpy().astype(np.float32))
    add("vfc2.b", net.v_fc2.bias.detach().cpu().numpy().astype(np.float32))

    path.write_bytes(bytes(blob))
    if view is None:
        view = "absolute" if os.environ.get("WEIQI_ABS_VIEW") == "1" else "relative"
    manifest = {
        "size": net.size, "input_planes": 3, "layers": layers, "bytes": len(blob),
        # 端侧：scoreLead = value_out * 10；winrate = sigmoid(k_winrate * scoreLead)
        "value_scale": 10.0, "k_winrate": k_winrate,
        # 输入视角：relative = [我方子, 对方子, 恒1]（对称）；absolute = [黑子, 白子, 轮到黑]
        "view": view,
    }
    (path.with_suffix(".json")).write_text(json.dumps(manifest, indent=1))
    return manifest


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="distill-9-all.jsonl")
    ap.add_argument("--size", type=int, default=9)
    ap.add_argument("--out", default="pv9")
    ap.add_argument("--width", type=int, default=32)
    ap.add_argument("--blocks", type=int, default=2)
    ap.add_argument("--steps", type=int, default=3000)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--limit", type=int, default=None, help="只用前 N 个样本（冒烟用）")
    ap.add_argument("--val-frac", type=float, default=0.05)
    args = ap.parse_args()

    data_path = KATAGO_DIR / args.data
    t0 = time.time()
    X, P, V, M, G = load_jsonl(data_path, args.size, args.limit)
    # 从数据里拟合「目差 → 胜率」的斜率 K：winrate ≈ sigmoid(K * scoreLead)。
    # 端侧拿到的是网络预测的目差，而 MCTS 需要的是胜率（它按胜负回传），
    # 所以这个换算必须有依据，不能拍一个常数。
    k_fit = 0.5
    try:
        import json as _json
        wr, lead = [], []
        for line in data_path.open(encoding="utf-8"):
            try: s = _json.loads(line)
            except Exception: continue
            if s.get("size") != args.size: continue
            w = min(max(float(s["winrate"]), 1e-3), 1 - 1e-3)
            wr.append(math.log(w / (1 - w)))          # logit(胜率)
            lead.append(float(s["scoreLead"]))
            if len(wr) >= 20000: break
        if len(wr) >= 50:
            import numpy as _np
            A = _np.array(lead); B = _np.array(wr)
            k_fit = float((A @ B) / max(A @ A, 1e-9))  # 过原点的最小二乘
            print(f"  拟合「目差→胜率」斜率 K = {k_fit:.3f}（用 {len(wr)} 条样本）")
    except Exception as e:
        print(f"  斜率拟合失败，用默认 {k_fit}: {e}")
    if len(X) == 0:
        print(f"  没有可用样本（{data_path}）"); return 1
    print(f"  样本 {len(X)} 个  载入耗时 {time.time()-t0:.1f}s")

    dev = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"  设备 {dev}")
    # 价值目标标准化：目差量级 ÷ 10，训练更稳
    Vs = V / 10.0
    # ⚠️ 必须按「局」划分 train/val（2026-10-05 实测出来的问题）：
    #    我们是从同一局按手数切片取局面的，相邻两个局面只差 1 手棋、余弦相似度 ≈0.99。
    #    若按样本随机划分，验证集的每个局面在训练集里都有个近乎相同的近邻
    #    ⇒ **验证集泄漏** ⇒ 验证 top1 虚高（曾出现 top1 漂亮单调、而真实棋力不单调的矛盾）。
    uniq_g = np.unique(G)
    _perm = np.random.default_rng(0).permutation(len(uniq_g))
    n_val_g = max(1, int(round(len(uniq_g) * args.val_frac)))
    val_g = set(uniq_g[_perm[:n_val_g]].tolist())
    _isval = np.array([g in val_g for g in G])
    va, tr = np.where(_isval)[0], np.where(~_isval)[0]
    if len(uniq_g) == len(G):
        print("  ⚠️ 语料没有 gameId ⇒ 退化为「按样本」划分，验证集可能泄漏（请重新生成语料）")
    print(f"  划分（按局）：训练 {len(tr)} 样本 / {len(uniq_g)-n_val_g} 局，"
          f"验证 {len(va)} 样本 / {n_val_g} 局")
    tx = torch.tensor(X[tr], device=dev); tp = torch.tensor(P[tr], device=dev)
    tv = torch.tensor(Vs[tr], device=dev); tm = torch.tensor(M[tr], device=dev)
    vx = torch.tensor(X[va], device=dev); vp = torch.tensor(P[va], device=dev)
    vv = torch.tensor(Vs[va], device=dev); vm = torch.tensor(M[va], device=dev)

    net = PolicyValueNet(args.size, args.width, args.blocks).to(dev)
    n_param = sum(p.numel() for p in net.parameters())
    print(f"  网络 {args.width} 通道 × {args.blocks} 块  参数 {n_param:,}")
    opt = torch.optim.Adam(net.parameters(), lr=args.lr)

    def loss_fn(pred_p, pred_v, tgt_p, tgt_v, mask):
        logp = F.log_softmax(pred_p.masked_fill(mask <= 0, -1e9), dim=1)
        l_pol = -(tgt_p * logp).sum(dim=1).mean()
        l_val = F.mse_loss(pred_v, tgt_v)
        return l_pol + l_val, l_pol.item(), l_val.item()

    bs = args.batch
    for step in range(1, args.steps + 1):
        sel = torch.randint(0, len(tx), (bs,), device=dev)
        pred_p, pred_v = net(tx[sel])
        loss, lp, lv = loss_fn(pred_p, pred_v, tp[sel], tv[sel], tm[sel])
        opt.zero_grad(); loss.backward(); opt.step()
        if step % max(1, args.steps // 10) == 0 or step == 1:
            with torch.no_grad():
                vp_p, vp_v = net(vx)
                _, vlp, vlv = loss_fn(vp_p, vp_v, vp, vv, vm)
                # 策略 top-1 命中率：网络首选是否等于 KataGo 首选
                pp = vp_p.masked_fill(vm <= 0, -1e9).argmax(dim=1)
                hit = (pp == vp.argmax(dim=1)).float().mean().item()
            print(f"    step {step:>5}  train loss {loss.item():.3f} (策略 {lp:.3f} 价值 {lv:.3f})"
                  f"   val 策略 {vlp:.3f} 价值 {vlv:.3f}  top1命中 {hit*100:.1f}%"
                  f"  {time.time()-t0:.0f}s", flush=True)

    out_base = KATAGO_DIR / args.out
    torch.save(net.state_dict(), str(out_base) + ".pt")
    manifest = export_for_kotlin(net, out_base.with_suffix(".bin"), k_fit)
    print(f"\n  已保存 {out_base}.pt 与 {out_base}.bin（{manifest['bytes']/1024:.0f} KB）+ manifest")
    print(f"  端侧只需 conv+relu（BN 已折进权重），层数 {len(manifest['layers'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
