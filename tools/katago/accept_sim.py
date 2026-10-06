#!/usr/bin/env python3
"""产品级对局验收：按**产品真实配置**（档位 + 该档的让子数）打整盘棋，并把棋谱打出来。

为什么要它（而不是只看位置级指标）：
  位置级指标只测"单手的期望目损"，它看不见**整盘棋**里才会暴露的问题：
  停手时机、收官崩盘、让子是否真的体现在起始局面上、终局判定是否正确。
  验收必须落到"完整一局"上。

用法：
  python3 accept_sim.py record --size 9 --tier entry        # 打一盘并打印棋谱
  python3 accept_sim.py record --size 9 --all-tiers         # 五个档各打一盘，并排对比
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

K = Path(__file__).resolve().parent
BENCH = K.parent.parent / "src/WeiqiTV/bench/build/install/bench/bin/bench"
NETDIR = K.parent.parent / "src/WeiqiTV/app/src/main/assets/net"
LETTERS = "ABCDEFGHJKLMNOPQRST"


def gtp(idx: int, size: int) -> str:
    if idx < 0:
        return "pass"
    return f"{LETTERS[idx % size]}{size - idx // size}"


def play_one(size: int, tier: str, seed: int, max_plies: int = 400,
             net: Path | None = None, open_rand: int = 0) -> dict:
    """按产品配置打一盘：两侧同网同预算，受让方（白）拿该档的让子数。

    ⚠️ `open_rand` 默认 **0**（不随机开局）。
    评测台为了"避免自战 24 局完全相同"会随机前 6 手，但那是**评测用的扰动**：
    它会让开局落下几颗一线废子，拿它当"产品真实表现"是污染。
    验收要看真实行棋 ⇒ 从空盘（含让子）开始。
    """
    env = {**os.environ, "PYTHONPATH": str(K / "../ml/pylibs")}
    env["WEIQI_NET"] = str(net or (NETDIR / f"pv{size}-{tier}.bin"))
    cmd = f"appmatch {size} {tier} {tier} {seed} {max_plies} {open_rand}\nquit\n"
    p = subprocess.run([str(BENCH)], input=cmd, capture_output=True, text=True, env=env, timeout=3600)
    for line in p.stdout.splitlines():
        if line.strip().startswith("{"):
            return json.loads(line)
    raise RuntimeError(f"没拿到 JSON：stdout={p.stdout[:200]!r} stderr={p.stderr[-300:]!r}")


def print_record(g: dict) -> None:
    size = g["size"]
    idxs, cols = g["moveIdx"], g["moveColors"]
    print(f"  ── {size} 路 · {g['tierA']} · seed={g['seed']} ──")
    te = g.get("trueEyeFills", 0)
    en = g.get("enclosedFills", 0)
    pp = g.get("postPassPlies", 0)
    flag = "  ⚠️ 填真眼＝真错误" if te > 0 else ""
    print(f"     手数 {g['plies']}｜停手 {g['passes']} 次（最长连停 {g['maxConsecPass']}）"
          f"｜非法手 {g['illegal']}｜终局 {g['ended']}")
    print(f"     填真眼 {te} 手｜四邻全自己（含补断，合法）{en} 手"
          f"｜双方停手后仍在下 {pp} 手{flag}")
    # 棋谱：每行 10 手
    seq = [f"{i+1}.{c}{gtp(x, size)}" for i, (x, c) in enumerate(zip(idxs, cols))]
    for s in range(0, len(seq), 10):
        print("       " + " ".join(seq[s:s + 10]))
    m = g["blackMargin"]
    print(f"     黑净胜 {m:+d} 子（贴目 {g['komi']}）⇒ 胜方：{g['winner']}"
          f"（A/B 中 {'A 执黑' if g['aBlack'] else 'B 执黑'}）")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["record"])
    ap.add_argument("--size", type=int, default=9)
    ap.add_argument("--tier", default="entry")
    ap.add_argument("--all-tiers", action="store_true")
    ap.add_argument("--seed", type=int, default=1001)
    ap.add_argument("--net", default=None, help="覆盖网络（默认用装机网络）")
    a = ap.parse_args()

    if not BENCH.exists():
        raise SystemExit(f"bench 未构建：{BENCH}")
    tiers = [d.id for d in ("entry", "beginner", "intermediate", "advanced", "master")] if a.all_tiers else [a.tier]
    for t in tiers:
        g = play_one(a.size, t, a.seed, net=Path(a.net) if a.net else None)
        print_record(g)
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
