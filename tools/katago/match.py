#!/usr/bin/env python3
"""档位互殴评测台：让两个难度档（或两个网络）互相对局，量胜率。

为什么需要它：`grade.py` 量的是"每手丢多少目"，而当局面上所有选择都只差 1~2 目时，
这个指标**分辨不出档位强弱**（实测五档差距 0.68 目，全在噪声里）。而
"入门 vs 中级、入门该输 70%"这种量级才是**孩子真正能感受到的差别**。

判定方式：双方下到两次停一手结束，终局用 KataGo 高访问量分析估分作裁判 ——
不用我自己的数子器，因为它不判死子，收官阶段会误判。

用法：
  python3 match.py --tier-a master --tier-b entry --games 10
  python3 match.py --tier-a master --tier-b master --net-b pv9-hi.bin --games 10   # 两个网络对打
"""
from __future__ import annotations

import argparse
import json
import os
import re
import statistics as st
import subprocess
import sys
import time
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
PROJ = ROOT / "src" / "WeiqiTV"
BENCH = PROJ / "bench" / "build" / "install" / "bench" / "bin" / "bench"
DEFAULT_NET = PROJ / "app" / "src" / "main" / "assets" / "net" / "pv9.bin"

sys.path.insert(0, str(HERE))
from grade import GTP_LETTERS, KataGoAnalysis, gtp_to_xy, xy_to_gtp  # noqa: E402

MAX_MOVES = 260


def log(m: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {m}", flush=True)


class Player:
    """一个档位 + 一个网络 = 一方。任务台进程长驻，逐行问答。"""

    def __init__(self, tier: str, net: Path | None, size: int) -> None:
        env = {**os.environ, "PYTHONPATH": str(ROOT / "tools" / "ml" / "pylibs")}
        if net:
            env["WEIQI_NET"] = str(net)
        self.p = subprocess.Popen([str(BENCH)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  text=True, bufsize=1, env=env)
        self.tier, self.size = tier, size

    def move(self, color: str, moves: list[str], seed: int) -> str:
        spec = " ".join(
            m if m == "pass" else f"{gtp_to_xy(m, self.size)[0]},{gtp_to_xy(m, self.size)[1]}"
            for m in moves
        ) or "-"
        assert self.p.stdin and self.p.stdout
        self.p.stdin.write(f"genmove {self.size} {color} {self.tier} {seed} {spec}\n")
        self.p.stdin.flush()
        out = (self.p.stdout.readline() or "").strip()
        if out.startswith("err"):
            raise RuntimeError(f"bench 报错（{self.tier}）：{out[:120]}")
        if out == "pass":
            return "pass"
        x, y = (int(v) for v in out.split(","))
        return xy_to_gtp(x, y, self.size)

    def subprocess_check(self, cmd: str) -> str:
        """再开一个短命令问一次（用于数子兜底）。"""
        assert self.p.stdin and self.p.stdout
        self.p.stdin.write(cmd + "\n")
        self.p.stdin.flush()
        return (self.p.stdout.readline() or "").strip()

    def close(self) -> None:
        self.p.terminate()


def play_one(job: dict) -> dict:
    """下完一整局，返回胜负与目差。在独立进程里跑，便于并行。"""
    size = job["size"]
    a_black = job["a_black"]
    max_plies = job.get("max_plies", MAX_MOVES)
    a = Player(job["tier_a"], Path(job["net_a"]) if job["net_a"] else None, size)
    b = Player(job["tier_b"], Path(job["net_b"]) if job["net_b"] else None, size)
    kg = KataGoAnalysis(max_visits=job["judge_visits"], size=size)
    moves: list[str] = []
    try:
        for turn in range(max_plies):
            color = "B" if turn % 2 == 0 else "W"
            is_a = (color == "B") == a_black
            side = a if is_a else b
            seed = job["seed"] * 1000 + turn
            # **开局随机化**：大师档 temperature=0 ⇒ 对局是确定性的，
            # 不加这一步会让"多局"退化成同一盘棋的多个副本（实测四局目差一模一样）。
            # 前几手从"必胜点/边角"里随机开场，是引擎对局的常规做法。
            if turn < job["opening_moves"]:
                mv = job["opening"][turn] if turn < len(job["opening"]) else None
                if mv is None:
                    # ⚠️ 开局随机点**必须排除已经下过的点**。
                    #
                    # 实测（2026-10-02）：不排除时，开局 4 手会出现同一个点下两次
                    # （第 5 局 F5 重复、第 7 局 C6 重复），整局在复盘棋谱时报
                    # `illegal move` 直接作废 —— 10 局里废掉 2 局，而失败原因
                    # 看起来像"引擎下了非法手"，排查方向会被彻底带偏。
                    #
                    # 做法：按种子取一个起点，然后**向前找第一个没下过的点**。
                    # 不用"过滤后再 choice"——那会让同一个种子产出不同的开局，
                    # 已跑过的对局就无法复现了。
                    cands = [f"{GTP_LETTERS[x]}{y+1}" for x in range(2, 7) for y in range(2, 7)]
                    rnd = __import__("random").Random(hash((job["seed"], turn)) & 0xFFFF)
                    start = rnd.randrange(len(cands))
                    mv = next(
                        (cands[(start + k) % len(cands)] for k in range(len(cands))
                         if cands[(start + k) % len(cands)] not in moves),
                        None,
                    )
                    if mv is None:
                        raise RuntimeError("开局候选点已用尽（棋盘太小或开局手数太多）")
            else:
                mv = side.move(color, moves, seed)
            moves.append(mv)
            if len(moves) >= 2 and moves[-1] == "pass" and moves[-2] == "pass":
                break
        # **主裁判用本引擎自己的数子器**（确定、无外部依赖、双方一致）。
        #
        # 为什么不再用 KataGo 当主裁判：实测出现「四局不同棋谱，目差全是 +75.5 目」——
        # 真实对局不可能这样，说明判分环节本身有问题；而且 KataGo 还会因超级劫分歧
        # 直接拒绝棋谱。阶梯比较只需要**一致、可复现**的判据，本引擎数子器完全够用。
        # ⚠️ 已知局限：本数子器不判死子（见 core 的 Scorer）。终局盘面若有未提的死子会判偏，
        # 而这条局限是**双方一致**的，用于档位对比不影响结论。
        #
        # ⚠️ **绝不要把 pass 过滤掉**：停一手也消耗一个回合，过滤它会让后面所有手的
        # 执色整体错一格。这一课今晚上了三次。score 命令要 x,y（bench 内部坐标），不是 GTP。
        spec = " ".join(
            "pass" if m == "pass" else f"{gtp_to_xy(m, size)[0]},{gtp_to_xy(m, size)[1]}"
            for m in moves
        )
        raw = a.subprocess_check(f"score {size} {spec}")
        mm = re.search(r"blackMargin=(-?\d+) komi=([0-9.]+) winner=([BW])", raw)
        if not mm:
            raise RuntimeError(f"数子失败：{raw[:80]}")
        margin = int(mm.group(1))
        self_black_wins = mm.group(3) == "B"
        # 先用 KataGo 判（**懂死子**），失败才用本引擎数子兜底并标记。
        #
        # 为什么必须这样：实测同一局出现过两个裁判给出**相反胜负** ——
        # 本引擎数子器不判死子，会把盘上已死的一方算成活棋。用错裁判＝结论反转，
        # 这比"没有结论"危险得多。所以数子器的结果一律带 judge=self 标记。
        kata_note = ""
        try:
            resp = kg.analyze(moves)
            lead_k = float(resp["rootInfo"]["scoreLead"])
            winner_black = lead_k > 0
            judge = "kata"
            kata_note = f" kata={lead_k:+.1f}目 引擎数子={margin:+d}子"
        except Exception as e:
            winner_black = self_black_wins
            judge = "self"
            kata_note = f" ⚠KataGo拒绝，退回引擎数子={margin:+d}子（不判死子，结论存疑）"
        a_won = (winner_black == a_black)
        # 最长"同一方连续自己的回合都停手"次数 —— 连环停手会让目差失去意义
        # （实测 31% 的对局出现，净胜可达 +81/81），所以要随结果一起存下来，
        # 让闸门能筛掉这批废数据。
        worst_pass_run = 0
        for want_black in (True, False):
            run = best = 0
            for p, m in enumerate(moves):
                if (p % 2 == 0) == want_black:
                    run = run + 1 if m == "pass" else 0
                    best = max(best, run)
            worst_pass_run = max(worst_pass_run, best)
        return {"a_won": a_won, "lead": float(margin), "moves": len(moves),
                "a_black": a_black, "ok": True, "judge": judge, "note": kata_note,
                # 只有 KataGo 判过的局才可采信：本引擎数子器不判死子，实测会把胜负判反
                "usable": judge == "kata",
                "max_pass_run": worst_pass_run,
                "kata_lead": (float(kata_note.split("kata=")[1].split("目")[0])
                              if "kata=" in kata_note else None),
                # 存完整着法：只存手数的话，出了"全歼"这种怪结果没法复盘
                "moves_full": moves}
        winner_black = lead > 0
        a_won = (winner_black == a_black)
        return {"a_won": a_won, "lead": lead, "moves": len(moves), "a_black": a_black, "ok": True}
    except Exception as e:
        return {"ok": False, "error": f"{e.__class__.__name__}: {str(e)[:100]}", "moves": len(moves)}
    finally:
        a.close(); b.close(); kg.close()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--size", type=int, default=9)
    ap.add_argument("--tier-a", default="master")
    ap.add_argument("--tier-b", default="entry")
    ap.add_argument("--net-a", default=str(DEFAULT_NET))
    ap.add_argument("--net-b", default=str(DEFAULT_NET))
    ap.add_argument("--games", type=int, default=10)
    ap.add_argument("--parallel", type=int, default=4, help="并行局数（24 核可开 4~6）")
    ap.add_argument("--judge-visits", type=int, default=800)
    ap.add_argument("--max-plies", type=int, default=MAX_MOVES,
                    help="对局手数上限。设成小于 260 可以把对局截在终局之前 —— "
                         "回避引擎的终局缺陷（连环停手会把目差变成 ±80 目，数据即废）")
    ap.add_argument("--seed", type=int, default=777)
    ap.add_argument("--opening-moves", type=int, default=4,
                    help="开局随机手数（打破 temperature=0 造成的对局确定性）")
    args = ap.parse_args()

    jobs = []
    for g in range(args.games):
        jobs.append({
            "size": args.size,
            "tier_a": args.tier_a, "tier_b": args.tier_b,
            "net_a": args.net_a, "net_b": args.net_b,
            "a_black": g % 2 == 0,          # 交替执黑，消掉先手优势
            "seed": args.seed + g,
            "judge_visits": args.judge_visits,
            "opening_moves": args.opening_moves,
            "max_plies": args.max_plies,
            "opening": [],
        })

    log(f"对局：{args.tier_a} vs {args.tier_b}，{args.games} 局，并行 {args.parallel}")
    t0 = time.time()
    results = []
    with ProcessPoolExecutor(max_workers=args.parallel) as ex:
        for i, r in enumerate(ex.map(play_one, jobs), 1):
            results.append(r)
            if r.get("ok"):
                log(f"  第 {i} 局：{'A 胜' if r['a_won'] else 'B 胜'}  "
                    f"（{r['moves']} 手，{'A 执黑' if r['a_black'] else 'A 执白'}，"
                    f"裁判={r.get('judge')}）{r.get('note','')}")
            else:
                log(f"  第 {i} 局：失败（{r.get('error')}，已下 {r.get('moves')} 手）")

    ok = [r for r in results if r.get("ok")]
    if not ok:
        log("没有一局正常完成")
        return 1
    # **可采信** = ① KataGo 判的（本引擎数子器不判死子，实测会把胜负判反）
    #            ② 没有连环停手（≥3 次会让目差变成 ±80 目，失去意义）
    usable = [r for r in ok if r.get("usable") and (r.get("max_pass_run") or 0) < 3]
    dropped = len(ok) - len(usable)
    if not usable:
        log(f"没有一局可采信（{len(ok)} 局全部被筛掉：自判或连环停手）")
        return 1
    a_wins = sum(1 for r in usable if r["a_won"])
    a_black_wins = sum(1 for r in usable if r["a_black"] and r["a_won"])
    a_black_n = sum(1 for r in usable if r["a_black"])
    a_white_wins = sum(1 for r in usable if not r["a_black"] and r["a_won"])
    a_white_n = sum(1 for r in usable if not r["a_black"])
    leads = [r["lead"] for r in usable]
    kata_leads = [r["kata_lead"] for r in usable if r.get("kata_lead") is not None]

    print()
    print("=" * 60)
    print(f"{args.tier_a} vs {args.tier_b}（{args.size} 路，{len(usable)} 局可采信"
          + (f"，筛掉 {dropped} 局：自判或连环停手" if dropped else "") + "）")
    print(f"  {args.tier_a} 胜率：{a_wins}/{len(usable)} = {a_wins/len(usable)*100:.0f}%")
    if a_black_n:
        print(f"    执黑时 {a_black_wins}/{a_black_n}")
    if a_white_n:
        print(f"    执白时 {a_white_wins}/{a_white_n}")
    if kata_leads:
        print(f"  平均黑方目差（KataGo 判）：{st.mean(kata_leads):+.1f} 目"
              f"（{min(kata_leads):+.0f} ~ {max(kata_leads):+.0f}）")
    print(f"  平均黑方目差（本引擎数子）：{st.mean(leads):+.1f} 目")
    # 判读提示：真正说明"谁更强"的，是**换色之后还能不能赢**，而且要看统计显著性 ——
    # 小样本下"3/9 赢"和"0/9 赢"完全是两回事，用布尔值判"赢没赢"会得出相反结论。
    if a_black_n and a_white_n:
        wr = a_wins / len(usable)
        z = (wr - 0.5) * (len(usable) ** 0.5) / 0.5          # 相对 50% 的粗略 z
        print(f"  {args.tier_a} 总胜率 {wr*100:.0f}%（z={z:+.2f}）"
              + ("  ← 显著高于 50%" if z > 1.96 else
                 "  ← 显著低于 50%" if z < -1.96 else "  ← 与 50% 分不开（样本不足或两档实力接近）"))
        if len(usable) < 16:
            print(f"  ⚠️ 只有 {len(usable)} 局可采信，判定 5 档相邻差距需要更多局（≥20 局/对）")
    print(f"  耗时 {(time.time()-t0)/60:.1f} 分钟")
    (HERE / f"match-{args.tier_a}-vs-{args.tier_b}.json").write_text(
        json.dumps(results, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
