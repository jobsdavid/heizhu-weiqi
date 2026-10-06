#!/usr/bin/env python3
"""数据量 vs 模型容量：到底哪个是当前瓶颈。

背景：直觉上"数据越多越好"，但小网络很容易在远没到数据上限时就已经吃饱。
两个可测量的维度：

  A. 固定模型（32 通道 × 2 块），数据量 N = 2k/5k/10k/20k/全部
     → 验证集损失随 N 的曲线。**若在某个 N 之后变平，说明数据不再是瓶颈。**
  B. 固定数据（当前全部），模型放大到 64 通道 × 4 块
     → 若大模型明显更好，说明瓶颈在容量；反之说明数据不够。

判读方式：先看 A 的曲线有没有变平；若已变平再看 B —— B 有提升就该换大模型，
B 也没提升才值得继续堆数据（或者去修标签质量）。

产出 SCALING.md。
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
PYLIBS = str(ROOT / "tools" / "ml" / "pylibs")
DATA = HERE / "distill-9-all.jsonl"


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def count() -> int:
    with DATA.open(encoding="utf-8") as fh:
        return sum(1 for _ in fh)


def train(width: int, blocks: int, steps: int, limit: int | None, tag: str) -> dict:
    cmd = [sys.executable, "-u", "train_pv.py", "--data", DATA.name,
           "--width", str(width), "--blocks", str(blocks), "--steps", str(steps),
           "--out", f"scale-{tag}"]
    if limit:
        cmd += ["--limit", str(limit)]
    log(f"训练 {tag}: 宽{width} 块{blocks} 步{steps} 数据上限={limit or '全部'}")
    r = subprocess.run(cmd, capture_output=True, text=True, cwd=str(HERE),
                       env={**os.environ, "PYTHONPATH": PYLIBS})
    (HERE / f"scale-{tag}.log").write_text(r.stdout + "\n" + r.stderr)
    res = {"tag": tag, "width": width, "blocks": blocks, "limit": limit}
    for line in r.stdout.splitlines():
        m = re.search(r"样本 (\d+) 个", line)
        if m:
            res["n"] = int(m.group(1))
        m = re.search(r"val 策略 ([0-9.]+) 价值 ([0-9.]+)\s+top1命中 ([0-9.]+)%", line)
        if m:
            res["val_policy"] = float(m.group(1))
            res["val_value"] = float(m.group(2))
            res["top1"] = float(m.group(3))
    ok = Path(str(HERE / f"scale-{tag}.bin")).exists()
    res["ok"] = ok
    log(f"  → n={res.get('n')} val策略={res.get('val_policy')} top1={res.get('top1')}%")
    return res


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--steps", type=int, default=2500)
    ap.add_argument("--big-steps", type=int, default=4000)
    args = ap.parse_args()

    if not DATA.exists():
        log("没有 distill-9-all.jsonl —— 先跑 overnight.py 或手动合并")
        return 1

    # 需要合并后的全量文件；若存在分片则先合并
    total = count()
    log(f"全量样本 {total}")
    rows = []
    if total < 1000:
        log("样本太少，先攒数据")
        return 1

    # ---- A. 数据量曲线（固定小模型）----
    for n in (2000, 5000, min(10000, total), min(20000, total), total):
        if n <= 0:
            continue
        if any(r.get("limit") == n for r in rows) or any(r.get("n") == n for r in rows):
            continue
        rows.append(train(32, 2, args.steps, None if n >= total else n, f"n{n}"))

    # ---- B. 容量对照（全量数据 + 更大模型）----
    rows.append(train(64, 4, args.big_steps, None, "big64"))

    # ---- 报告 ----
    lines = ["# 数据量 vs 模型容量", "", f"全量样本：{total}", "",
             "## A. 固定 32×2 模型，改变数据量", "",
             "| 样本数 | 宽 | 块 | val 策略损失 | val 价值损失 | top1命中 |",
             "|---|---|---|---|---|---|"]
    small = [r for r in rows if r["width"] == 32]
    small.sort(key=lambda r: r.get("n") or 0)
    for r in small:
        lines.append(f"| {r.get('n')} | {r['width']} | {r['blocks']} | "
                     f"{r.get('val_policy')} | {r.get('val_value')} | {r.get('top1')}% |")
    big = [r for r in rows if r["width"] == 64]
    lines += ["", "## B. 全量数据 + 放大模型（64×4）", "",
              "| 样本数 | 宽 | 块 | val 策略损失 | val 价值损失 | top1命中 |",
              "|---|---|---|---|---|---|"]
    for r in big:
        lines.append(f"| {r.get('n')} | {r['width']} | {r['blocks']} | "
                     f"{r.get('val_policy')} | {r.get('val_value')} | {r.get('top1')}% |")

    lines += ["", "## 怎么读"]
    if len(small) >= 2:
        first, last = small[0], small[-1]
        if first.get("val_policy") and last.get("val_policy"):
            drop = first["val_policy"] - last["val_policy"]
            rel = drop / max(first["val_policy"], 1e-9) * 100
            lines.append(f"- 数据从 {first.get('n')} 增到 {last.get('n')}："
                         f"val 策略损失 {first['val_policy']} → {last['val_policy']}"
                         f"（{rel:.1f}%）")
            if len(small) >= 3:
                prev = small[-2]
                tail = (prev.get("val_policy", 0) - last.get("val_policy", 0)) / max(prev.get("val_policy", 1e-9), 1e-9) * 100
                lines.append(f"- 最后一档增量（{prev.get('n')} → {last.get('n')}）只带来 {tail:.1f}% 改善"
                             f"{'—— **数据已经吃不动了，瓶颈在容量**' if abs(tail) < 3 else '—— 还在明显受益，继续加数据有意义'}")
    if big and small:
        b, s = big[0], small[-1]
        if b.get("val_policy") and s.get("val_policy"):
            diff = (s["val_policy"] - b["val_policy"]) / max(s["val_policy"], 1e-9) * 100
            lines.append(f"- 同数据下放大到 64×4：val 策略损失 {s['val_policy']} → {b['val_policy']}"
                         f"（{diff:+.1f}%）；若明显更低，说明**容量是瓶颈，该换大模型**")

    text = "\n".join(lines)
    (HERE / "SCALING.md").write_text(text, encoding="utf-8")
    print("\n" + "=" * 60)
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
