#!/usr/bin/env python3
"""五档阶梯的**配对**验收：单调性 + 相邻档是否超出噪声。

为什么不能用"各自的平均丢目比大小"：
  网络之间的真实差距在 1 目级，而单次测量的标准误也是 1 目级。本项目实测，
  拿均值比大小先后把同一个网络判成"最差"和"最弱"（两次结论互相矛盾），
  而换成同一局面的配对差后，标准误掉到 0.098 目，方向立刻清楚。

验收三条（缺一条都不算做完）：
  1. 每档都有可读数据（均值 + 中位 + 最差个案）
  2. **单调**：越难的档越好（配对差方向一致）
  3. **相邻档的差异超出噪声**：配对 t 检验 p < 0.05

用法： python3 paired_tiers.py            # 默认读 grade-9-<档>.json
       python3 paired_tiers.py a.json b.json c.json d.json e.json   # 按 入门/初级/中级/高级/大师 顺序覆盖
前提： 五档必须评的是**同一批局面**（同一缓存棋谱 + 同一 stride），脚本会先断言。
"""
from __future__ import annotations

import json
import math
import statistics as st
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
TIERS = ["entry", "beginner", "intermediate", "advanced", "master"]
CN = {"entry": "入门", "beginner": "初级", "intermediate": "中级",
      "advanced": "高级", "master": "大师"}


def p_two(t: float, n: int) -> float:
    try:
        from scipy import stats as sps
        return 2 * sps.t.sf(abs(t), n - 1)
    except Exception:
        return math.erfc(abs(t) / math.sqrt(2))


def paired(a: list[dict], b: list[dict]) -> tuple[float, float, float, int]:
    """返回 (配对差均值, 标准误, t, n)。正 = a 比 b 丢得多 = a 更弱。"""
    d = [x["loss_points"] - y["loss_points"] for x, y in zip(a, b)]
    n = len(d)
    md = st.mean(d)
    se = st.stdev(d) / math.sqrt(n) if n > 1 else float("inf")
    return md, se, (md / se if se else float("inf")), n


def main() -> int:
    override = [Path(a) for a in sys.argv[1:]]
    if override and len(override) != len(TIERS):
        print(f"✘ 覆盖文件必须正好 {len(TIERS)} 个（按 {', '.join(TIERS)} 顺序），收到 {len(override)} 个")
        return 1

    data = {}
    for i, t in enumerate(TIERS):
        f = override[i] if override else HERE / f"grade-9-{t}.json"
        if not f.is_file():
            print(f"✘ 缺 {f} —— 先把五档都测一遍")
            return 1
        if override:
            print(f"  {CN[t]} ← {f.name}")
        data[t] = json.loads(f.read_text())

    # 配对前提：五档评的是同一批局面
    ref = [e["ply"] for e in data["master"]]
    for t in TIERS:
        if [e["ply"] for e in data[t]] != ref:
            print(f"✘ {t} 的局面序列与 master 不一致 —— 不能配对，先查取样参数是否一致")
            return 1
    print(f"配对前提成立：五档各 {len(ref)} 个局面（同一批）\n")

    print("一、每档的可读数据")
    print(f"  {'档位':<6}{'平均丢目':>10}{'中位':>8}{'最差':>9}{'样本':>7}")
    for t in TIERS:
        pts = sorted(e["loss_points"] for e in data[t])
        print(f"  {CN[t]:<6}{st.mean(pts):>10.2f}{pts[len(pts)//2]:>8.2f}{pts[-1]:>9.2f}{len(pts):>7}")

    print("\n二、单调性（越难越好 = 丢目应依次下降）")
    print(f"  {'相邻档':<20}{'配对差':>9}{'标准误':>9}{'t':>8}{'p':>11}   判定")
    monotone = True
    separable = True
    for a, b in zip(TIERS, TIERS[1:]):
        md, se, t, n = paired(data[a], data[b])
        p = p_two(t, n)
        good = md > 0                      # a 更弱 = 方向正确
        ok = good and p < 0.05
        monotone &= good
        separable &= ok
        if good and p >= 0.05:
            verdict = "方向对，但落在噪声里 ✘"
        elif good:
            verdict = "✔ 正确且显著"
        else:
            verdict = "✘ 方向反了"
        print(f"  {CN[a]} → {CN[b]:<12}{md:>9.3f}{se:>9.3f}{t:>8.2f}{p:>11.5f}   {verdict}")

    md, se, t, n = paired(data["entry"], data["master"])
    print(f"\n  总跨度（入门 → 大师）：{md:+.3f} ± {se:.3f} 目  p={p_two(t, n):.5f}"
          f"  {'✔ 显著' if p_two(t, n) < 0.05 else '✘ 不显著'}")

    print("\n三、结论")
    if monotone and separable:
        print("  ✔ 阶梯成立：单调，且相邻档都超出噪声")
    elif monotone:
        print("  ⚠ 方向全对，但有相邻档分不开 —— 孩子选相邻两档其实感觉不到差别")
        print("    可选：把分不开的档合并；或给这些档换差距更大的网络；或引入让子（量级杠杆）")
    else:
        print("  ✘ 阶梯不成立：有档位配反了，必须先按配对序重配网络再测")
    return 0 if (monotone and separable) else 1


if __name__ == "__main__":
    sys.exit(main())
