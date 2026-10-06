#!/usr/bin/env python3
"""五档标定：用同一批局面测五档（带网络），检查是否单调有序。

用户的靶心是「每个难度更准确」。可验收的定义：
  ① 单调：入门 > 初级 > 中级 > 高级 > 大师 的**平均丢目依次下降**
  ② 相邻档要有可测差异（不是"五档一模一样"）
  ③ 各档最差单手有界（不出现 20 目级的随机大漏着）

关键：五档必须跑**同一批局面**（--sample-from master 复用缓存的自战棋谱），
否则档间差异里混着局面差异，判不出是档位在起作用还是局面不同。

输出 TIERS.md（表格 + 判定）并把每档明细留成 tier-<档>.json。
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import statistics as st
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
PROJ = ROOT / "src" / "WeiqiTV"
NATIVE_NET = PROJ / "app" / "src" / "main" / "assets" / "net" / "pv9.bin"

DIFFS = [("entry", "入门"), ("beginner", "初级"), ("intermediate", "中级"),
         ("advanced", "高级"), ("master", "大师")]


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def grade(difficulty: str, net: Path | None, positions: int, visits: int,
          plies: int = 200, stride: int = 5) -> dict:
    env = {**os.environ,
           "PYTHONPATH": str(ROOT / "tools" / "ml" / "pylibs"),
           "LD_LIBRARY_PATH": f"/usr/lib/wsl/lib:{HERE}/cuda-libs/nvidia/cudnn/lib"}
    if net:
        env["WEIQI_NET"] = str(net)
    r = subprocess.run(
        [sys.executable, "grade.py", "grade", "--size", "9", "--difficulty", difficulty,
         "--sample-from", "master", "--plies", str(plies), "--stride", str(stride),
         "--positions", str(positions), "--visits", str(visits)],
        capture_output=True, text=True, cwd=str(HERE), env=env, timeout=7200)
    (HERE / f"tier-{difficulty}.log").write_text(r.stdout + "\n" + r.stderr)
    src = HERE / f"grade-9-{difficulty}.json"
    # **硬闸门：绝不复用上一轮的产物**。
    # 今晚栽过两次同一类坑：grade 崩了（例如 KataGo 拒绝某个局面），
    # 外层脚本没检查就 cp 结果文件，于是"新的一轮"打印出与上一轮完全相同的数字，
    # 看起来像结论，实际是过期数据。判据：退出码 + 产物必须是刚生成的。
    fresh = src.exists() and (time.time() - src.stat().st_mtime) < 600
    if r.returncode != 0 or not fresh:
        log(f"  ✘ {difficulty}：grade 失败或产物过期"
            f"（退出码={r.returncode} 产物新鲜={fresh}），本档标记失败、不采信")
        return {}
    if src.exists():
        shutil.copy(src, HERE / f"tier-{difficulty}.json")
        data = json.loads(src.read_text())
        pts = [e["loss_points"] for e in data]
        srt = sorted(pts)
        phases = {}
        for e in data:
            phases.setdefault(e["phase"], []).append(e["loss_points"])
        return {
            "mean": sum(pts) / len(pts), "median": srt[len(srt) // 2], "worst": srt[-1],
            "n": len(pts),
            "phases": {k: sum(v) / len(v) for k, v in phases.items()},
            "agree": sum(1 for e in data if e["my_move"] == e["best_move"]),
        }
    return {}


def main() -> int:
    ap = argparse.ArgumentParser()
    # 每档 40 个局面：12 个样本的标准误约 2.5~3 目，判不出 1 目级的档位差异；
    # 40 个能把标准误压到约 1.3 目，才谈得上"阶梯是否成立"。
    ap.add_argument("--positions", type=int, default=40)
    ap.add_argument("--plies", type=int, default=200, help="自战多少手用于取样（40 局面需 200 手）")
    ap.add_argument("--stride", type=int, default=5)
    ap.add_argument("--visits", type=int, default=400)
    ap.add_argument("--net", default=str(NATIVE_NET))
    ap.add_argument("--no-net", action="store_true", help="测原路径（对照）")
    args = ap.parse_args()

    net = None if args.no_net else Path(args.net)
    if net and not net.is_file():
        log(f"**找不到网络权重 {net}**（管线还没部署？）")
        return 1
    log(f"标定开始：{'无网络（原路径）' if net is None else net.name}  "
        f"{args.positions} 个局面/档  KataGo {args.visits} 访问量")

    results = {}
    for key, name in DIFFS:
        log(f"测 {name}（{key}）")
        r = grade(key, net, args.positions, args.visits, args.plies, args.stride)
        results[name] = r
        if r:
            log(f"  平均 {r['mean']:.2f} 目  中位 {r['median']:.2f}  最差 {r['worst']:.2f}  "
                f"一致 {r['agree']}/{r['n']}")

    # ---- 报告 ----
    lines = [f"# 五档标定（{'无网络' if net is None else '网络引导'}）", ""]
    lines.append(f"局面：每档 {args.positions} 个（同一批，取自大师自战）  "
                 f"KataGo 标签 {args.visits} 访问量")
    lines.append("")
    lines.append("| 档位 | 平均丢目 | 中位 | 最差 | 开局 | 中盘 | 官子 | 与KataGo首选一致 |")
    lines.append("|---|---|---|---|---|---|---|---|")
    for _, name in DIFFS:
        r = results.get(name, {})
        if not r:
            lines.append(f"| {name} | 失败 | | | | | | |")
            continue
        ph = r.get("phases", {})
        lines.append(
            f"| {name} | {r['mean']:.2f} | {r['median']:.2f} | {r['worst']:.2f} | "
            f"{ph.get('开局', float('nan')):.2f} | {ph.get('中盘', float('nan')):.2f} | "
            f"{ph.get('官子', float('nan')):.2f} | {r['agree']}/{r['n']} |")

    means = [(name, results[name]["mean"]) for _, name in DIFFS if results.get(name)]
    lines.append("")
    if len(means) == len(DIFFS):
        mono = all(means[i][1] <= means[i + 1][1] for i in range(len(means) - 1))
        lines.append("按平均丢目从小到大： " + " < ".join(f"{n}({m:.2f})" for n, m in sorted(means, key=lambda x: x[1])))
        lines.append("")
        lines.append(f"**单调性（入门 → 大师 依次变好）：{'满足 ✔' if mono else '不满足 ✘'}**")
        # 相邻档差异
        pairs = []
        for i in range(len(DIFFS) - 1):
            a, b = results.get(DIFFS[i][1]), results.get(DIFFS[i + 1][1])
            if a and b:
                pairs.append(f"{DIFFS[i][1]}→{DIFFS[i+1][1]} {b['mean']-a['mean']:+.2f} 目")
        lines.append("")
        lines.append("相邻档差异：" + "；".join(pairs))
    else:
        lines.append("**有档位没跑出结果，无法判定单调性**")

    text = "\n".join(lines)
    (HERE / "TIERS.md").write_text(text, encoding="utf-8")
    print("\n" + "=" * 60)
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
