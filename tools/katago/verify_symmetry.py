#!/usr/bin/env python3
"""黑白对称性验收 —— 这次数据修复的核心判据。

为什么必须有这条：上一批网络的训练语料**只有"黑方该走"**，网络对白方完全分布外，
而 app 里 AI 永远执白。验收不能靠"重训完看起来挺顺"，必须是可复算的两条：

  ① **镜像对称（主判据）**：构造一对"黑白互换 + 中心对称"的局面，由**同一方**行棋，
     引擎应走出中心对称的着法。
     ⚠️ 构造要点：换色镜像要求**黑白子数相等**（否则不是合法局面），所以局面取偶数手、
     且都让**黑方**行棋；序列按 b1,w1,b2,w2… 交替摆，镜像侧把相邻两手两两交换再旋转。

  ② **空盘行棋方探针（辅助判据）**：空盘上让白方先走本身不是合法局面，但它是
     网络"白方分布外"的直接探针 —— 坏的时候白方会落到**一线**（实测 3,0 = D1）。

用法： python3 verify_symmetry.py [网络.bin ...]        # 默认 bal-*.bin
      python3 verify_symmetry.py --tier master pv9-hi.bin
"""
from __future__ import annotations

import argparse
import glob
import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROJ = HERE.parent.parent / "src" / "WeiqiTV"
BENCH = PROJ / "bench" / "build" / "install" / "bench" / "bin" / "bench"
SIZE = 9  # 默认 9 路；13/19 路的网络必须用 --size 覆盖（否则喂 9×9 盘面给 13×13 网络，
#           报 "棋盘尺寸不符：81 != 169"，而探针自己崩掉 = 闸门静默，比不做还危险）
LETTERS = "ABCDEFGHJKLMNOPQRST"[:SIZE]  # GTP 坐标字母跳过 I（13 路取到 N、19 路取到 T）


def line_of(x: int, y: int) -> int:
    return min(x, y, SIZE - 1 - x, SIZE - 1 - y) + 1


def gtp(x: int, y: int) -> str:
    return f"{LETTERS[x]}{y + 1}"


def rot(x: int, y: int) -> tuple[int, int]:
    return SIZE - 1 - x, SIZE - 1 - y


class Bench:
    def __init__(self, net: str) -> None:
        env = {**os.environ, "WEIQI_NET": net,
               "PYTHONPATH": str(HERE.parent / "ml" / "pylibs")}
        env.pop("LD_LIBRARY_PATH", None)   # 见 train_balanced.sh 的说明
        self.p = subprocess.Popen([str(BENCH)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  text=True, bufsize=1, env=env, stderr=subprocess.DEVNULL)

    def gen(self, spec: str, color: str, tier: str, seed: int = 7) -> str:
        assert self.p.stdin and self.p.stdout
        self.p.stdin.write(f"genmove {SIZE} {color} {tier} {seed} {spec}\n")
        self.p.stdin.flush()
        return (self.p.stdout.readline() or "").strip()

    def close(self) -> None:
        self.p.terminate()


# 局面用"交替着法序列"给出（b1,w1,b2,w2,…），偶数手 ⇒ 黑白子数相等 ⇒ 黑方行棋
POSES: list[list[tuple[int, int]]] = [
    [(4, 4), (6, 6), (2, 2), (2, 6)],                  # 双方各两子，散开
    [(4, 4), (5, 4), (4, 5), (6, 3), (3, 6), (5, 5)],  # 双方各三子，有接触
    [(2, 2), (6, 6), (2, 6), (6, 2), (4, 4), (4, 5), (3, 3), (5, 5)],
]


def mirror_spec(spec: list[tuple[int, int]]) -> list[tuple[int, int]]:
    """换色 + 中心对称后的着法序列：相邻两手两两交换再旋转。

    原序列 b1,w1,b2,w2…；镜像后黑子应落在原白子的旋转位置、白子落在原黑子的旋转位置，
    而序列又是从黑开始交替 → 把 (b_i, w_i) 两两交换顺序即可。
    """
    out: list[tuple[int, int]] = []
    for i in range(0, len(spec), 2):
        b, w = spec[i], spec[i + 1]
        out.append(rot(*w))
        out.append(rot(*b))
    return out


def check(net: str, tier: str) -> bool:
    name = Path(net).stem
    ok = True
    print(f"  ── {name}")
    with_bench = Bench(net)
    try:
        # ① 镜像对称（主判据）
        for i, pose in enumerate(POSES, 1):
            a_spec = " ".join(f"{x},{y}" for x, y in pose)
            b_spec = " ".join(f"{x},{y}" for x, y in mirror_spec(pose))
            ma = with_bench.gen(a_spec, "B", tier)
            mb = with_bench.gen(b_spec, "B", tier)
            expect = "pass" if ma == "pass" else ",".join(str(v) for v in rot(*(int(t) for t in ma.split(","))))
            same = expect == mb
            print(f"     镜像{i}：原局面 黑走→{ma:<6} 镜像局面 黑走→{mb:<6} "
                  f"{'✔ 对称' if same else '✘ 不对称（应为 ' + expect + '）'}")
            ok &= same

        # ② 空盘探针（辅助判据）
        mw = with_bench.gen("-", "W", tier)
        mb = with_bench.gen("-", "B", tier)
        def desc(m: str) -> str:
            if m == "pass":
                return "停一手"
            x, y = (int(v) for v in m.split(","))
            return f"{gtp(x, y)}({m}) 第{line_of(x, y)}线"
        illegal_edge = mw != "pass" and line_of(*(int(v) for v in mw.split(","))) == 1
        print(f"     空盘探针：白先→{desc(mw):<20} 黑先→{desc(mb)}"
              + ("   ✘ 白方落一线" if illegal_edge else ""))
        ok &= not illegal_edge
    finally:
        with_bench.close()
    return ok


def main() -> int:
    global SIZE, LETTERS
    ap = argparse.ArgumentParser()
    ap.add_argument("nets", nargs="*", help="默认检查 bal-*.bin")
    ap.add_argument("--tier", default="master")
    ap.add_argument("--size", type=int, default=9,
                    help="棋盘尺寸；网络的尺寸必须与之一致（13/19 路的网络必须传）")
    a = ap.parse_args()
    SIZE = a.size
    LETTERS = "ABCDEFGHJKLMNOPQRST"[:SIZE]
    nets = a.nets or sorted(glob.glob(str(HERE / "bal-*.bin")))
    if not nets:
        print("没有可检查的网络（给文件名或先跑 train_balanced.sh）")
        return 1
    print(f"黑白对称性验收：{len(nets)} 个网络，档位参数={a.tier}\n")
    bad = [n for n in nets if not check(n, a.tier)]
    print()
    if bad:
        print(f"✘ {len(bad)}/{len(nets)} 未通过：{', '.join(Path(b).stem for b in bad)}")
        return 1
    print(f"✔ {len(nets)}/{len(nets)} 通过：黑白对称")
    return 0


if __name__ == "__main__":
    sys.exit(main())
