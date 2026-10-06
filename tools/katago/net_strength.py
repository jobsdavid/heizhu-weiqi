#!/usr/bin/env python3
"""量化一族网络的真实棋力，用于给难度档配网络。

为什么不用训练日志里的 val 指标：**它和实际棋力可以完全脱钩**。
实测 8 个网络的 val 策略损失全在 2.44~2.58 之间（几乎无区别），
而引擎级实测的棋力差一倍以上（64×4 丢 8.80 目 vs 64×6 丢 3.81 目）。
所以排序只能用引擎级指标：同一批局面、同一搜索设置，唯一变量是网络。

结果：每个网络单独存 net-strength-<名字>.json，最后打印汇总表。
"""
from __future__ import annotations

import json
import os
import statistics as st
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
NETS = [
    "pv9-w16b1", "pv9-w32b2", "scale-n2000", "scale-n5000",
    "pv9-w48b4", "pv9-w64b4", "pv9-w64b6", "pv9-hi",
]
# 全部固定在这一档 + 这一批局面上，唯一变量是网络
GRADE_ARGS = [
    "grade", "--size", "9", "--difficulty", "master",
    "--sample-from", "master", "--plies", "200", "--stride", "5",
    "--positions", "28", "--visits", "200",
]


def log(m: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {m}", flush=True)


def measure(name: str) -> dict | None:
    binp = HERE / f"{name}.bin"
    if not binp.exists():
        log(f"  {name}: 缺文件，跳过")
        return None
    # grade.py 固定把结果写到 grade-9-<difficulty>.json，会被下一个网络覆盖 ——
    # 所以先删掉旧的，跑完立刻解析并存档（绝不用可能过期的文件）
    stale = HERE / "grade-9-master.json"
    if stale.exists():
        stale.unlink()
    t0 = time.time()
    env = {**os.environ, "WEIQI_NET": str(binp)}
    r = subprocess.run([sys.executable, "-u", "grade.py", *GRADE_ARGS],
                       cwd=HERE, env=env, capture_output=True, text=True, timeout=900)
    if not stale.exists():
        log(f"  {name}: 失败（未产出结果文件，退出码={r.returncode}）"
            f" 末尾：{(r.stdout or r.stderr or '').strip().splitlines()[-1][:90] if (r.stdout or r.stderr) else ''}")
        return None
    evals = json.load(open(stale))
    (HERE / f"net-strength-{name}.json").write_text(json.dumps(evals, ensure_ascii=False))
    losses = [e["loss_points"] for e in evals]
    res = {
        "net": name, "n": len(losses),
        "mean": round(st.mean(losses), 2), "median": round(st.median(losses), 2),
        "worst": round(max(losses), 2),
        "mid": round(st.mean([e["loss_points"] for e in evals if e["phase"] == "中盘"] or [0]), 2),
        "secs": round(time.time() - t0),
    }
    log(f"  {name:16s} 平均 {res['mean']:5.2f}  中位 {res['median']:5.2f}  "
        f"最差 {res['worst']:5.2f}  中盘 {res['mid']:5.2f}  ({res['secs']}s)")
    return res


def main() -> int:
    out = []
    for n in NETS:
        r = measure(n)
        if r:
            out.append(r)
    (HERE / "net-strength.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
    print()
    print("=" * 72)
    print(f"{'网络':<18}{'平均丢目':>9}{'中位':>8}{'最差':>9}{'中盘':>8}")
    for r in sorted(out, key=lambda x: -x["mean"]):
        print(f"{r['net']:<18}{r['mean']:>9.2f}{r['median']:>8.2f}{r['worst']:>9.2f}{r['mid']:>8.2f}")
    print("（按平均丢目从差到好排；数字越大＝该网络越弱）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
