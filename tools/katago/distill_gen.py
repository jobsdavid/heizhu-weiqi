#!/usr/bin/env python3
"""生成 9 路「蒸馏」数据：局面 → KataGo 的策略分布 + 胜率/目差。

为什么自己写、不用 KataGo 的 selfplay：
  发行包里没有 selfplay 配置（只有 analysis/gtp/match），要走那条路得先 clone
  源码仓库再搭训练环境。而 analysis 引擎已经跑通，标签质量同样来自 KataGo 网络，
  且**局面分布可控** —— 这点更重要。

局面从哪来：本引擎不同种子的自战（合法、便宜），并按 TV 速率封顶模拟次数
  （WEIQI_TV_RATE）让它跑得快。这样训练数据里的局面 = app 真实会遇到的局面分布，
  而不是 KataGo 自己下的"高水平但不像自家孩子"的局面。

标签：KataGo 分析引擎在 --visits 下的
  · 策略 = 根节点各着法的访问分布（归一化）
  · 价值 = 根节点胜率 + 目差（统一换算到「该走棋一方」的视角）

输出 JSONL，一行一个样本：
  {"size":9,"toMove":"B","moves":["E5","F6",...],"policy":{"F6":0.31,...},
   "winrate":0.52,"scoreLead":1.3}
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KATAGO_DIR = ROOT / "tools" / "katago"
BENCH = ROOT / "src" / "WeiqiTV" / "bench" / "build" / "install" / "bench" / "bin" / "bench"
sys.path.insert(0, str(KATAGO_DIR))
from grade import KataGoAnalysis, GTP_LETTERS, xy_to_gtp  # noqa: E402

# TV 实测约 1073 次模拟/秒；按这个速率封顶，开发机上自战才跑得快
TV_RATE = "1073"


class Bench:
    def __init__(self) -> None:
        env = {**os.environ, "WEIQI_TV_RATE": TV_RATE}
        self.p = subprocess.Popen([str(BENCH)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  text=True, bufsize=1, env=env)

    def selfplay(self, size: int, difficulty: str, plies: int, seed: int) -> list[str]:
        assert self.p.stdin and self.p.stdout
        self.p.stdin.write(f"selfplay {size} {difficulty} {plies} {seed}\n")
        self.p.stdin.flush()
        out = self.p.stdout.readline().strip()
        if out.startswith("err"):
            raise RuntimeError(out)
        return [m if m == "pass" else xy_to_gtp(int(m.split(",")[0]), int(m.split(",")[1]), size)
                for m in out.split()]

    def close(self) -> None:
        self.p.terminate()



def katago_selfplay(kg: "KataGoAnalysis", size: int, plies: int, seed: int) -> list[str]:
    """**用 KataGo 自己的落子推进一局**，而不是用我们自己的引擎。

    为什么要换（2026-10-05 定位到的根本性错误）：
    原来取局面用的是 `bench selfplay`（我们自己的引擎）。而我们的引擎在 13 路会崩
    （官子段每手丢 20 目、最差单手 138 目）⇒ **取到的局面本身就是崩局**
    ⇒ 网络学的就是"崩局里的棋" ⇒ 喂多少语料都没用（实测：新语料重训后官子仍是 19.07 目）。
    这就是"垃圾进、垃圾出"。让强引擎（KataGo）自己下，局面质量才有保证。

    ⚠️ 必须带随机性，否则 KataGo 的最佳手是确定性的 ⇒ 60 局全是同一局：
      · 前 4 手在候选里随机挑（保证每局开局不同）
      · 之后按访问分布加权采样（保持棋局质量，又不完全确定）
    """
    import random
    rnd = random.Random(seed)
    moves: list[str] = []
    while len(moves) < plies:
        resp = kg.analyze(moves)
        infos = resp.get("moveInfos") or []
        if not infos:
            break
        top = infos[:8]
        if len(moves) < 4:
            pick = rnd.choice(top)["move"]                      # 开局随机
        else:
            ws = [max(1, int(m.get("visits", 1))) for m in top]
            pick = rnd.choices(top, weights=ws, k=1)[0]["move"]  # 按访问分布采样
        if pick == "pass":
            break
        moves.append(pick)
    return moves


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--size", type=int, default=9)
    ap.add_argument("--games", type=int, default=40, help="自战多少局用于取样")
    ap.add_argument("--plies", type=int, default=50, help="每局取多少手")
    ap.add_argument("--every", type=int, default=2, help="每隔几手取一个局面")
    ap.add_argument("--visits", type=int, default=200, help="KataGo 标签用的访问量")
    ap.add_argument("--out", default="distill-9.jsonl")
    ap.add_argument("--seed0", type=int, default=1000)
    ap.add_argument("--difficulties", default="entry,beginner,intermediate",
                    help="自战用哪些档（低档快，用于覆盖分布）")
    ap.add_argument("--max-lead", type=float, default=15.0,
                    help="丢掉 |scoreLead| 超过该值的局面。理由：这类局面已经分出胜负，"
                         "拿来训练等于教网络「怎么收尸」，对中盘分寸没有帮助；"
                         "实测初版语料 scoreLead 的 σ 达 10~14、极值 ±60，废局面比例不低。")
    ap.add_argument("--proposer", default="bench", choices=["bench", "katago"],
                    help="用谁的对局取局面。bench=我们自己的引擎（快，但它会崩时取到的局面就是崩的，"
                         "13 路实测后半盘每手丢 20 目）；katago=让 KataGo 自己下（慢，局面质量高）。"
                         "⚠️ 局面质量决定网络上限 —— 弱引擎的坏棋教不出好网络。")
    ap.add_argument("--append", action="store_true",
                    help="追加写入已有文件（续跑用）。不加会**截断**，重跑即丢掉已有样本")
    ap.add_argument("--only-color", default="both", choices=["both", "B", "W"],
                    help="只取「某一方该走」的局面。**补数据用**：已有语料缺哪一方就只补哪一方，"
                         "没必要把另一半重跑一遍（本项目实测：旧语料 15786 条缺白方，"
                         "只需补白方，黑方那半直接复用）")
    args = ap.parse_args()

    out_path = KATAGO_DIR / args.out
    bench, kg = Bench(), KataGoAnalysis(max_visits=args.visits, size=args.size)
    diffs = args.difficulties.split(",")
    n_written = n_skip = 0
    t0 = time.time()
    try:
        mode = "a" if args.append else "w"
        with out_path.open(mode, encoding="utf-8") as fh:
            for g in range(args.games):
                diff = diffs[g % len(diffs)]
                if args.proposer == "katago":
                    # 用 KataGo 自己的对局取局面（质量高，慢）；档位由网络容量+搜索决定
                    moves = katago_selfplay(kg, args.size, args.plies, args.seed0 + g)
                else:
                    moves = bench.selfplay(args.size, diff, args.plies, args.seed0 + g)
                # ⚠️ 必须同时覆盖**奇偶两种手数**。
                #
                # 实测（2026-10-02）：只按 `range(0, n, every)` 取样时，every 取偶数
                # （默认 2）就把 ply 全限制在偶数上 ⇒ **每一条样本都是"黑方该走"**。
                # 本管线生成的全部 15786 条样本，toMove 分布是 {'B': 15786} ——
                # 一条"白方该走"的样本都没有。后果：网络对白方完全是分布外，
                # 空盘上白方第一手会下到**一线**，执白每手比执黑多丢 2~9 目
                # （8 个网络无一例外）。
                # 而 app 里 AI **永远执白** —— 孩子看到的"AI 下得像傻瓜"就是这个，
                # 难度阶梯也因此被彻底盖住（对局胜负完全由执黑决定）。
                #
                # 两个偏移各取一遍，代价是样本数翻倍（这点代价换"白方会下棋"，值）。
                #
                # `--only-color` 用于**补数据**：旧语料只缺一方时，只补那一方即可，
                # 没必要把已经有的另一半重跑（本项目黑方 15786 条是好的，直接复用）。
                plies = sorted(set(list(range(0, len(moves), args.every)) +
                                   list(range(1, len(moves), args.every))))
                if args.only_color != "both":
                    want_black = args.only_color == "B"
                    plies = [p for p in plies if (p % 2 == 0) == want_black]
                for ply in plies:
                    prefix = moves[:ply]
                    try:
                        resp = kg.analyze([m for m in prefix if m != "pass"])
                    except Exception as e:
                        n_skip += 1
                        continue
                    infos = resp.get("moveInfos") or []
                    total = sum(m["visits"] for m in infos)
                    if not infos or total <= 0:
                        n_skip += 1
                        continue
                    to_move = "B" if ply % 2 == 0 else "W"
                    # 根节点胜率是黑方视角（分析配置里显式钉了 reportAnalysisWinratesAs=BLACK），
                    # 这里统一换算成「该走棋一方」的视角 —— 训练时网络只需要评估当前行棋方。
                    black_wr = float(resp["rootInfo"]["winrate"])
                    black_lead = float(resp["rootInfo"]["scoreLead"])
                    wr = black_wr if to_move == "B" else 1.0 - black_wr
                    lead = black_lead if to_move == "B" else -black_lead
                    # ⚠️ 过滤已分出胜负的废局面
                    if abs(lead) > args.max_lead:
                        continue
                    fh.write(json.dumps({
                        "size": args.size,
                        "toMove": to_move,
                        # ⚠️ gameId 是训练侧按「局」划分 train/val 的依据：同一局切出的
                        #    局面余弦相似度 ≈0.99，若按样本随机划分 ⇒ 验证集泄漏 ⇒ top1 虚高
                        "gameId": f"{args.size}-{args.seed0 + g}",
                        "moves": prefix,
                        # 丢掉 pass（策略头只覆盖 81 个点），归一化在训练侧做
                        "policy": {m["move"]: m["visits"] / total
                                   for m in infos if m["move"] != "pass"},
                        "winrate": wr,
                        "scoreLead": lead,
                    }, ensure_ascii=False) + "\n")
                    n_written += 1
                el = time.time() - t0
                print(f"  局 {g+1}/{args.games}（{diff}）  样本 {n_written}  跳过 {n_skip}  "
                      f"用时 {el:.0f}s  {n_written/max(el,1e-9):.1f} 样本/s", flush=True)
    finally:
        bench.close(); kg.close()
    print(f"\n  写出 {n_written} 个样本 → {out_path}  （跳过 {n_skip}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
