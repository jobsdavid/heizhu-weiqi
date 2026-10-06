#!/usr/bin/env python3
"""配对位置级棋力测量：在**同一批干净局面**上比较多个网络/配置。

为什么需要它（2026-10-05 的诊断结论）：
  1. 用对局胜率当判据，分辨率是 Δp = 1/√n。要分辨 5 个百分点需要 n≈780 局/档
     （(1.96+0.84)²×0.25/0.05²）。我们过去一律在 30~40 局上做判断，
     功效下限 ≈110 ELO ⇒ "非单调"其实大多是"测不到"。
  2. grade.py 的"每手丢目"口径本身是对的，但它**取局面的来源是我们自己的引擎自战**。
     旧语料/自战里含崩局（|目差| 可达 40+ 目），于是这个指标的方差被局面难度主导，
     反而掩盖了配置差异。
  3. 因此本工具做两件事：
     · 局面改从**干净的 KataGo 自战语料**取（|scoreLead| ≤ 阈值、手数覆盖全程）
     · **配对比较**：同一批局面、同一随机入口，逐个比对不同配置 ⇒ 局面难度这一项被差分掉
       （paired design：方差只剩"配置×局面"的交互项）

用法：
  python3 posloss.py --size 13 --data distill-13-katago.jsonl --positions 120 --visits 400 \
      --tier master \
      --config shipped:/path/pv13-master.bin \
      --config trained:kt13-master.bin

输出：每个配置的平均丢目、与基线配置的**配对差**及其 bootstrap 95% 区间、分段（开局/中盘/官子）。
"""
from __future__ import annotations

import argparse
import json
import os
import random
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from grade import Bench, KataGoAnalysis, KATAGO_DIR, komi_for_size   # noqa: E402


def phase_of(ply: int) -> str:
    """按手数分段。9 路一局约 120 手、13 路约 260 手，这里用比例式分段。"""
    if ply < 30:
        return "开局"
    if ply < 100:
        return "中盘"
    return "官子"


def sample_positions(files: list[Path], size: int, n: int, max_lead: float, seed: int) -> list[dict]:
    """从干净语料里**分层抽样**局面。

    分层理由：不控制的话，语料里中后盘局面天然更多（一局 200 手、每 3 手取一个 ⇒
    前 30 手只有 10 个样本），而"官子每手丢目"与"中盘每手丢目"是完全不同性质的量，
    混在一起比配置会变成比样本构成。这里按手数分箱等量抽。
    """
    rows: list[dict] = []
    for f in files:
        if not f.exists():
            print(f"  ⚠️ 语料不存在，跳过：{f}")
            continue
        for line in f.open(encoding="utf-8"):
            try:
                s = json.loads(line)
            except Exception:
                continue
            if s.get("size") != size:
                continue
            if abs(float(s.get("scoreLead", 0.0))) > max_lead:
                continue
            rows.append(s)
    if not rows:
        raise SystemExit("没有可用局面（检查 --data / --size / --max-lead）")

    rnd = random.Random(seed)
    rnd.shuffle(rows)
    # 按手数四段分箱，等量取
    bins: dict[str, list[dict]] = {"0-30": [], "30-60": [], "60-120": [], "120+": []}
    for r in rows:
        p = len(r.get("moves") or [])
        k = "0-30" if p < 30 else "30-60" if p < 60 else "60-120" if p < 120 else "120+"
        bins[k].append(r)
    per = max(1, n // 4)
    out: list[dict] = []
    for k, bucket in bins.items():
        out.extend(bucket[:per])
    out = out[:n]
    print(f"  取局面 {len(out)} 个（语料候选 {len(rows)} 条，分箱 "
          f"{ {k: len(v) for k, v in bins.items()} }，每箱上限 {per}）")
    return out


def paired_bootstrap(a: list[float], b: list[float], iters: int = 10000, seed: int = 7) -> tuple[float, float, float]:
    """配对差的 bootstrap 95% 区间（a − b）。"""
    assert len(a) == len(b) and a
    rnd = random.Random(seed)
    n = len(a)
    diffs = [x - y for x, y in zip(a, b)]
    point = sum(diffs) / n
    samples = []
    for _ in range(iters):
        s = 0.0
        for _ in range(n):
            s += diffs[rnd.randrange(n)]
        samples.append(s / n)
    samples.sort()
    return point, samples[int(0.025 * iters)], samples[int(0.975 * iters)]


def main() -> int:
    ap = argparse.ArgumentParser(description="配对位置级棋力测量")
    ap.add_argument("--size", type=int, default=13)
    ap.add_argument("--data", action="append", required=True, help="干净语料 jsonl（可多次）")
    ap.add_argument("--positions", type=int, default=120)
    ap.add_argument("--visits", type=int, default=400, help="KataGo 每次分析的访问量（题面口径）")
    ap.add_argument("--tier", default="master", help="本引擎的搜索档位（两侧一致，只变网络）")
    ap.add_argument("--config", action="append", required=True, help="名字:网络文件路径（可多次）")
    ap.add_argument("--max-lead", type=float, default=15.0, help="只取 |目差| 不超过该值的局面")
    ap.add_argument("--seed", type=int, default=20261005)
    args = ap.parse_args()

    cfgs = []
    for spec in args.config:
        name, _, path = spec.partition(":")
        if not path or not Path(path).exists():
            raise SystemExit(f"--config 格式应为 名字:路径，且文件须存在：{spec}")
        cfgs.append((name, Path(path)))
    if len(cfgs) < 1:
        raise SystemExit("至少一个 --config")

    print(f"══ 配对位置级测量：{args.size} 路 · 档位 {args.tier} · KataGo {args.visits} 访问量 ══")
    print(f"   配置：{', '.join(f'{n}({p.name})' for n, p in cfgs)}")
    pos = sample_positions([Path(d) for d in args.data], args.size, args.positions, args.max_lead, args.seed)
    if not pos:
        return 1

    kg = KataGoAnalysis(max_visits=args.visits, size=args.size)
    # 题面：每个局面的 KataGo 最佳手与其分数（只算一次，供所有配置共用）
    refs: list[dict] = []
    for r in pos:
        moves = [m for m in r["moves"] if m != "pass"]
        turn = r["toMove"]
        try:
            resp = kg.analyze(moves)
        except Exception as e:
            print(f"    ⚠️ 局面被 KataGo 拒绝，跳过：{e}")
            continue
        infos = resp.get("moveInfos") or []
        if not infos:
            continue
        best = infos[0]["move"]
        best_sc = kg._score_for({"rootInfo": infos[0]}, turn)
        refs.append({"moves": moves, "turn": turn, "ply": len(moves),
                     "best": best, "best_sc": best_sc})
    print(f"  题面就绪：{len(refs)} 个局面")
    if not refs:
        return 1

    results: dict[str, dict[int, float]] = {}
    for name, path in cfgs:
        os.environ["WEIQI_NET"] = str(path)
        bench = Bench()
        losses: dict[int, float] = {}
        agree = 0
        try:
            for i, ref in enumerate(refs):
                try:
                    mine = bench.genmove(args.size, ref["turn"], args.tier, args.seed + i, ref["moves"])
                except Exception as e:
                    print(f"    ⚠️ {name} 第{i}个局面引擎报错，跳过：{e}")
                    continue
                if mine == "pass":
                    continue
                if mine == ref["best"]:
                    my_sc, agree = ref["best_sc"], agree + 1
                else:
                    my_sc = kg.score_of_move(ref["moves"], mine, ref["turn"])
                losses[i] = ref["best_sc"] - my_sc     # 丢目（正数=比最佳手差）
        finally:
            bench.p.terminate()
        results[name] = losses
        print(f"  [{name}] 完成 {len(losses)} 个局面")
    kg.close()

    print()
    print("  ── 每配置的平均丢目（同一批局面）──")
    ids = sorted(set.intersection(*[set(v.keys()) for v in results.values()])) if results else []
    print(f"  共同可比局面数：{len(ids)}")
    if not ids:
        return 1

    def trimmed(xs: list[float], frac: float = 0.05) -> float:
        """截尾均值：丢掉最差 frac 比例的样本再平均。

        为什么要它：极端局面（例如某个 86 目的崩坏点）会把算术均值抬高约 0.6 目，
        而它对所有配置是同一个值 ⇒ 它只增加噪声、不提供配置间信息。
        截尾后配置差异更清楚（保留均值列以便与历史数字对齐）。
        """
        if len(xs) < 20:
            return statistics.mean(xs)
        s = sorted(xs)
        k = max(1, int(len(s) * frac))
        return statistics.mean(s[:-k])

    base = cfgs[0][0]
    # 尾部统计比均值更重要（2026-10-05 的教训）：
    #   9 路最强网 中位 0.68 目、均值 4.07 目、最差 51.83 目
    #   ⇒ 引擎"通常下得没问题"，但偶发 50 目以上的崩盘，而正是它决定胜负
    #     （与"结局目差 σ≈22 目"互相印证）。所以要比的是尾部，不是均值。
    def tail_frac(xs: list[float], thr: float = 10.0) -> tuple[int, float]:
        n = sum(1 for x in xs if x > thr)
        return n, n / len(xs)

    print(f"  {'配置':<12}{'平均丢目':>10}{'截尾5%':>9}{'中位':>8}{'最差':>8}{'>10目占比':>11}{'与基线配对差':>26}")
    for name, _ in cfgs:
        vals = [results[name][i] for i in ids]
        nt, ft = tail_frac(vals)
        line = (f"  {name:<12}{statistics.mean(vals):>10.2f}{trimmed(vals):>9.2f}"
                f"{statistics.median(vals):>8.2f}{max(vals):>8.2f}{f'{nt} ({ft*100:.0f}%)':>11}")
        if name != base:
            bvals = [results[base][i] for i in ids]
            d, lo, hi = paired_bootstrap(vals, bvals)
            star = "**" if (lo > 0 or hi < 0) else "  "
            line += f"{d:>+14.2f}  [{lo:+.2f}, {hi:+.2f}] {star}"
        print(line)
    print("    （配对差 <0 表示比基线好；区间不含 0 才算真的不同 —— 标 ** 的）")

    print()
    print("  ── 分段（平均丢目）──")
    print(f"  {'配置':<12}{'开局(<30)':>12}{'中盘(30-99)':>14}{'官子(>=100)':>14}")
    for name, _ in cfgs:
        parts = {"开局": [], "中盘": [], "官子": []}
        for i in ids:
            parts[phase_of(refs[i]["ply"])].append(results[name][i])
        f = lambda xs: f"{statistics.mean(xs):.2f}" if xs else "—"
        print(f"  {name:<12}{f(parts['开局']):>12}{f(parts['中盘']):>14}{f(parts['官子']):>14}")

    # ── 按执色拆分 ─────────────────────────────────────────────────────────
    # 为什么必须拆：网络输入是**绝对视角**（[黑子, 白子, 是否轮到黑]），
    # 这种编码本身不对称，靠训练时的"必然换色增强"才获得颜色对称性。
    # 若某批网络漏了/只有一半换色增强，它就会"执白比执黑下得好" ——
    # 而两侧用同一个网络做自战测公平性时，这恰好表现为白方胜率显著高于 50%。
    # 所以这一栏是"颜色偏置"的直接证据（对局层面的表现是胜率，这里看丢目）。
    print()
    print("  ── 按执色拆分（平均丢目）—— 检颜色偏置 ──")
    print(f"  {'配置':<12}{'轮到黑(执黑)':>14}{'轮到白(执白)':>14}{'差(黑−白)':>12}")
    for name, _ in cfgs:
        bl, wh = [], []
        for i in ids:
            (bl if refs[i]["turn"] == "B" else wh).append(results[name][i])
        if bl and wh:
            d = statistics.mean(bl) - statistics.mean(wh)
            print(f"  {name:<12}{statistics.mean(bl):>14.2f}{statistics.mean(wh):>14.2f}{d:>+12.2f}")
        else:
            print(f"  {name:<12}{'—':>14}{'—':>14}{'—':>12}")
    print("    （差 >0 = 执黑时丢得更多 ⇒ 网络执黑更差 ⇒ 存在颜色偏置）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
