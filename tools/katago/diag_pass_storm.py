#!/usr/bin/env python3
"""诊断：连环停手局面里，双方每一步的**静态收益**各是多少。

为什么只做这一件事：收工判据 `anyMoveGainsOver` 判的是
「我落一手后，我的（子数 + 围空）是否 > 上一手 + 2」。所以只要量出
"吃子方每步静态收益"与"停手方每步可得的收益"，就能判定病是出在**阈值**上
还是出在**候选生成**上 —— 不用猜，也不用改产品代码。

做法：拿一条已经记录下来的连环停手棋谱，用评测台的 `score` 命令
（与引擎内部同一个 Scorer）逐手算出黑方净胜，从而得到双方各自的盘面总量与每步增量。

用法： python3 diag_pass_storm.py [棋谱json] [局号]
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from grade import gtp_to_xy  # noqa: E402

PROJ = HERE.parent.parent / "src" / "WeiqiTV"
BENCH = PROJ / "bench" / "build" / "install" / "bench" / "bin" / "bench"
SIZE = 9


def spec_of(moves: list[str]) -> str:
    return " ".join(
        "pass" if m == "pass" else "%d,%d" % gtp_to_xy(m, SIZE) for m in moves
    ) or "-"


class Bench:
    def __init__(self) -> None:
        import os
        env = {**os.environ, "PYTHONPATH": str(PROJ.parent.parent / "tools" / "ml" / "pylibs")}
        self.p = subprocess.Popen([str(BENCH)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  text=True, bufsize=1, env=env)

    def score(self, moves: list[str]) -> tuple[int, float]:
        assert self.p.stdin and self.p.stdout
        self.p.stdin.write(f"score {SIZE} {spec_of(moves)}\n")
        self.p.stdin.flush()
        out = (self.p.stdout.readline() or "").strip()
        m = re.search(r"blackMargin=(-?\d+) komi=([0-9.]+)", out)
        if not m:
            raise RuntimeError(f"数子失败：{out[:80]}")
        return int(m.group(1)), float(m.group(2))

    def close(self) -> None:
        self.p.terminate()


def main() -> int:
    f = Path(sys.argv[1]) if len(sys.argv) > 1 else HERE / "match-master-vs-entry.json"
    idx = int(sys.argv[2]) if len(sys.argv) > 2 else 3
    games = json.loads(f.read_text())
    g = games[idx - 1]
    moves = g["moves_full"]
    a_black = g["a_black"]
    print(f"{f.name} 第 {idx} 局：手数 {len(moves)}，a_black={a_black}，黑方目差 {g['lead']:+.0f}\n")

    b = Bench()
    try:
        # 空盘基准：用来解释 margin 的口径
        m0, komi = b.score([])
        print(f"空盘：blackMargin={m0} komi={komi}\n")

        rows = []
        for ply in range(len(moves) + 1):
            margin, _ = b.score(moves[:ply])
            rows.append(margin)
        print("逐手盘面（黑方净胜子数）与每步增量：")
        print(f"  {'手':>4} {'执子':>4} {'着法':>5} {'黑净胜':>7} {'本步黑增量':>10} {'本步白增量':>10}")
        for ply in range(len(moves)):
            color = "黑" if ply % 2 == 0 else "白"
            delta_margin = rows[ply + 1] - rows[ply]
            # 黑净胜增加的分为黑所得，减少的分为白所得
            black_gain = delta_margin
            white_gain = -delta_margin
            if abs(black_gain) > 0 or moves[ply] == "pass":
                print(f"  {ply:>4} {color:>4} {moves[ply]:>5} {rows[ply+1]:>7} "
                      f"{black_gain:>10} {white_gain:>10}")
        print(f"\n  空盘 → 终局：黑方净胜从 {rows[0]} 变到 {rows[-1]}（共 {rows[-1]-rows[0]:+d}）")
    finally:
        b.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
