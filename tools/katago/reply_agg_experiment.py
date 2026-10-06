#!/usr/bin/env python3
"""第二层前瞻的聚合方式实验：应手数量与聚合函数对棋力的影响。

假设：minimax 取"对方 N 个应手的最差价值"，等于对 N 个**含噪估计取最小**，
系统性偏低 → 会把正确的手误判成坏手（现象上就是"漏掉要点 + 尾部变差"）。

做法：改 REPLY_CANDIDATES 与聚合方式 → 重建 bench → 用同一批局面量 A/B → 还原源码。
每轮约 1 分钟。跑完把最优设置**固化进源码**，而不是靠环境变量带进生产代码。

用法：python3 reply_agg_experiment.py
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
PROJ = ROOT / "src" / "WeiqiTV"
ENGINE = PROJ / "core" / "src" / "main" / "kotlin" / "com" / "heizhu" / "weiqi" / "core" / "ai" / "MctsEngine.kt"
BENCH = PROJ / "bench" / "build" / "install" / "bench" / "bin" / "bench"
GRADLE = str(Path.home() / "gradle-9.8.0" / "bin" / "gradle")
NET = PROJ / "app" / "src" / "main" / "assets" / "net" / "pv9.bin"

# (标签, 应手数, 聚合方式)
VARIANTS = [
    ("一层前瞻(无应手)", 0, "min"),
    ("两层·应手2·取最差", 2, "min"),
    ("两层·应手6·取最差", 6, "min"),
    ("两层·应手4·取平均", 4, "avg"),
]


def log(m: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {m}", flush=True)


def set_source(reply_n: int, agg: str) -> None:
    txt = ENGINE.read_text(encoding="utf-8")
    txt = re.sub(r"private const val REPLY_CANDIDATES = \d+",
                 f"private const val REPLY_CANDIDATES = {reply_n}", txt)
    # 聚合方式：把取最差那行换成取平均
    if agg == "avg":
        txt = txt.replace(
            "                if (back.scoreLead < worstForMe) worstForMe = back.scoreLead",
            "                replySum += back.scoreLead; replyCount++",
        ).replace(
            "            val myValue = if (sawReply) worstForMe else -oppEval.scoreLead",
            "            val myValue = if (sawReply && replyCount > 0) replySum / replyCount else -oppEval.scoreLead",
        ).replace(
            "            var worstForMe = Float.MAX_VALUE\n            var sawReply = false",
            "            var replySum = 0f\n            var replyCount = 0\n            var sawReply = false",
        )
    ENGINE.write_text(txt, encoding="utf-8")


def build() -> bool:
    r = subprocess.run([GRADLE, ":bench:installDist", "-q"], cwd=str(PROJ),
                       capture_output=True, text=True, timeout=600)
    if r.returncode != 0:
        log("  构建失败:\n" + (r.stdout + r.stderr)[-800:])
        return False
    return True


def grade(tag: str, positions: int) -> dict:
    env = {**os.environ, "PYTHONPATH": str(ROOT / "tools" / "ml" / "pylibs"),
           "LD_LIBRARY_PATH": f"/usr/lib/wsl/lib:{HERE}/cuda-libs/nvidia/cudnn/lib",
           "WEIQI_NET": str(NET)}
    r = subprocess.run([sys.executable, "grade.py", "grade", "--size", "9", "--difficulty", "master",
                        "--sample-from", "master", "--plies", "60", "--stride", "5",
                        "--positions", str(positions), "--visits", "400"],
                       capture_output=True, text=True, cwd=str(HERE), env=env, timeout=3600)
    (HERE / f"agg-{tag}.log").write_text(r.stdout)
    res = {}
    m = re.search(r"平均每手丢 ([0-9.]+) 目\s+中位 ([0-9.]+)\s+最差 ([0-9.]+)", r.stdout)
    if m:
        res = {"mean": float(m.group(1)), "median": float(m.group(2)), "worst": float(m.group(3))}
    m = re.search(r"中盘\s+\d+ 手\s+平均丢\s+([0-9.]+) 目", r.stdout)
    if m:
        res["midgame"] = float(m.group(1))
    return res


def main() -> int:
    ap_positions = 8
    backup = ENGINE.read_text(encoding="utf-8")
    log(f"已备份 MctsEngine.kt（{len(backup)} 字节）")
    rows = []
    try:
        for label, n, agg in VARIANTS:
            log(f"变体：{label}（应手数={n} 聚合={agg}）")
            set_source(n, agg)
            if not build():
                rows.append((label, {}))
                continue
            res = grade(label.replace("/", "_"), ap_positions)
            log(f"  → {res}")
            rows.append((label, res))
    finally:
        ENGINE.write_text(backup, encoding="utf-8")
        subprocess.run([GRADLE, ":bench:installDist", "-q"], cwd=str(PROJ),
                       capture_output=True, text=True, timeout=600)
        log("已还原源码并重建（生产代码保持原状）")

    lines = ["# 第二层前瞻：应手数量与聚合方式", "",
             "同一批 8 个局面、同一个网络（64×6 蒸馏），唯一变量是应手聚合方式。", "",
             "| 变体 | 平均丢目 | 中位 | 最差 | 中盘 |", "|---|---|---|---|---|"]
    for label, r in rows:
        lines.append(f"| {label} | {r.get('mean','失败')} | {r.get('median','')} | "
                     f"{r.get('worst','')} | {r.get('midgame','')} |")
    ok = [(l, r) for l, r in rows if r.get("mean") is not None]
    if ok:
        best = min(ok, key=lambda x: x[1]["mean"])
        lines += ["", f"**平均丢目最低：{best[0]}（{best[1]['mean']} 目）**"]
        worst_tail = min(ok, key=lambda x: x[1].get("worst", 999))
        lines.append(f"**尾部最好：{worst_tail[0]}（最差 {worst_tail[1].get('worst')} 目）**")
    text = "\n".join(lines)
    (HERE / "REPLY_AGG.md").write_text(text, encoding="utf-8")
    print("\n" + "=" * 60)
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
