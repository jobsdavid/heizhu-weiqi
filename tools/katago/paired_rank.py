#!/usr/bin/env python3
"""把 netrank-*.json（同一批局面、同一套搜索参数、只换网络）做成**配对**排序。

为什么必须配对：网络之间的真实差距在 1 目级，而单次测量的标准误也是 1 目级 ——
拿各自的均值比大小，结论全在噪声里（上一轮就是这么把 64×4 判成"最好"的，
它其实是那一批里最差的）。配对做差能消掉"局面难度"这个共同项，
标准误掉到 0.1 目量级，方向立刻清楚。

用法： python3 paired_rank.py            # 用 netrank-*.json
      python3 paired_rank.py a.json b.json ...   # 指定文件
"""
from __future__ import annotations

import json
import math
import statistics as st
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def p_two(t: float, n: int) -> float:
    try:
        from scipy import stats as sps
        return 2 * sps.t.sf(abs(t), n - 1)
    except Exception:
        return math.erfc(abs(t) / math.sqrt(2))


def load(paths: list[Path]) -> dict[str, list[dict]]:
    out = {}
    for p in paths:
        name = p.stem.replace("netrank-", "").replace(".json", "")
        out[name] = json.loads(p.read_text())
    return out


def main() -> int:
    paths = [Path(a) for a in sys.argv[1:]] or sorted(HERE.glob("netrank-*.json"))
    data = load(paths)
    if not data:
        print("没有可用的样本文件（netrank-*.json）")
        return 1

    # 配对前提：所有文件评的是同一批局面（ply 序列一致）
    ref_name, ref = next(iter(data.items()))
    for name, s in data.items():
        plies = [e["ply"] for e in s]
        if plies != [e["ply"] for e in ref]:
            print(f"✘ {name} 的局面序列与 {ref_name} 不一致，不能配对比较 —— 先查取样参数")
            return 1
    print(f"配对前提成立：{len(data)} 个网络，各 {len(ref)} 个局面（同一批）\n")

    mean = {k: st.mean(e["loss_points"] for e in v) for k, v in data.items()}
    order = sorted(mean, key=lambda k: mean[k])

    print("按平均丢目（弱 → 强）：")
    for i, k in enumerate(order, 1):
        print(f"  {i:>2}. {k:<15} {mean[k]:6.2f} 目")

    # 以最强网络为基准，逐个做配对检验（方向：正 = 比基准弱）
    base = order[0]
    print(f"\n与最强（{base}）的配对检验（正 = 更弱）：")
    print(f"  {'网络':<15}{'配对差':>9}{'标准误':>9}{'t':>8}{'p':>11}   结论")
    worst_vs_best = None
    for k in order[1:]:
        d = [a["loss_points"] - b["loss_points"] for a, b in zip(data[k], data[base])]
        n = len(d)
        md, se = st.mean(d), st.stdev(d) / math.sqrt(n)
        t = md / se if se else float("inf")
        p = p_two(t, n)
        verdict = "✔ 明显更弱" if p < 0.05 else "— 与最强分不开"
        print(f"  {k:<15}{md:>9.3f}{se:>9.3f}{t:>8.2f}{p:>11.5f}   {verdict}")
        if k == order[-1]:
            worst_vs_best = (md, se, p)

    # 相邻名次是否可分辨 —— 阶梯能不能按这个序建，就看这一列
    print("\n相邻名次之间是否真的分得开（阶梯可建性的判据）：")
    separable = True
    for a, b in zip(order, order[1:]):
        d = [x["loss_points"] - y["loss_points"] for x, y in zip(data[a], data[b])]
        n = len(d)
        md, se = st.mean(d), st.stdev(d) / math.sqrt(n)
        t = md / se if se else float("inf")
        p = p_two(t, n)
        ok = p < 0.05
        separable &= ok
        print(f"  {a:<15} → {b:<15} {md:>7.3f} 目  p={p:.5f}  {'✔ 可分辨' if ok else '✘ 分不开'}")
    print()
    if separable:
        print("⇒ 全部相邻名次都可分辨，可以按这个序装配阶梯")
    else:
        print("⇒ 有相邻名次分不开：按这个序装配，那几档之间仍可能是噪声")
        print("   （可接受的下限：把分不开的网络合并到同一档，或换差距更大的网络）")
    if worst_vs_best:
        md, se, p = worst_vs_best
        print(f"\n最弱 vs 最强（阶梯的总跨度）：{md:+.3f} ± {se:.3f} 目  p={p:.5f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
