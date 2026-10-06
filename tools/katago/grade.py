#!/usr/bin/env python3
"""用 KataGo 当考官，量化黑猪围棋自研引擎的棋力。

两个阶段，回答两个不同的问题：

  grade  每手掉多少胜率？（棋力画像：弱在开局/中盘/官子？哪一档最差？）
         做法：从本引擎的自战棋谱里取局面 → 本引擎选一手 → 让 KataGo 分别评估
         「最好的一手」和「本引擎这一手」→ 差值就是这一手的损失。

  match  相当于 KataGo 多少访问量？（棋力锚定：给一个可比的刻度）
         做法：本引擎 vs KataGo 在若干访问量档位下对局，找胜率 ≈ 50% 的那一档。

为什么这么做而不是直接下几盘看输赢：「输给 KataGo」是必然的，没有信息量。
真正要的是**弱在哪、弱多少**，才能决定下一步改什么。

约定：坐标在 Python 侧统一用 GTP（A-T + 数字，跳过 I），
      → 本引擎用 (x,y) 下标，x = 列字母序 - 1，y = 行号 - 1。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]          # .../weiqi-tv
KATAGO_DIR = ROOT / "tools" / "katago"
BENCH = Path(os.environ.get(
    "WEIQI_BENCH",
    str(ROOT / "src" / "WeiqiTV" / "bench" / "build" / "install" / "bench" / "bin" / "bench"),
))
NET = KATAGO_DIR / "nets" / "kata1-b18c384nbt.bin.gz"

GTP_LETTERS = "ABCDEFGHJKLMNOPQRST"          # GTP 不含 I


def gtp_to_xy(move: str, size: int) -> tuple[int, int]:
    """GTP → 本引擎坐标。

    GTP 的行号**自下而上、从 1 开始**；本引擎 y 自上而下、从 0 开始。
    所以 y = size - row（不是 size - 1 - row —— 差这一格会让送进 KataGo 的局面整体错位，
    现象是 KataGo 报「非法着法」，看起来像引擎下了非法手，其实是喂错了局面）。
    """
    col = GTP_LETTERS.index(move[0].upper())
    return col, size - int(move[1:])


def xy_to_gtp(x: int, y: int, size: int) -> str:
    return f"{GTP_LETTERS[x]}{size - y}"


def _self_check_conversion() -> None:
    """往返自检：两个方向的换算必须是逆运算，且四角对得上 GTP 惯例。

    这一条是被自己坑了之后补的 —— 当时引擎被冤枉「下了非法手」，
    实际是坐标换算错了，而错误没有任何地方会报出来。
    """
    for size in (9, 13, 19):
        for y in range(size):
            for x in range(size):
                assert gtp_to_xy(xy_to_gtp(x, y, size), size) == (x, y), (size, x, y)
    assert xy_to_gtp(0, 0, 9) == "A9"          # 左上角：最高行
    assert xy_to_gtp(8, 8, 9) == "J1"          # 右下角：最低行，跳过 I
    assert gtp_to_xy("J1", 9) == (8, 8)


_self_check_conversion()


def komi_for_size(size: int) -> float:
    """贴目必须与 app 实际判定口径一致 —— 贴目不同，胜率没有可比性。

    这个换算我差点搞错，所以写清楚依据（不要凭"3¾ 子 = 7.5 目"这种记忆下手）：

      core/rules/Scorer.kt 里 Score 的胜负判据是
        blackMargin(= 黑子+黑空 − 白子−白空) > 2 × Komi.STANDARD
      而 KataGo 在 rules=chinese 下的 komi 就是「飞点（area point）」，
      面积计分里「子差」恰是「点差」的一半（黑+白 = 总点数固定）。
      ⇒ 2 × 3.75 = **7.5**，与 KataGo 的 komi 同口径。

    （曾误判"9 路贴 3.75 太低、黑方 94% 占便宜"：那是拿 3.75 当 area point 去喂
      KataGo 的结果，而 app 的有效贴目是 7.5。测量前先对齐口径，别先下结论。）
    """
    return 7.5


@dataclass
class MoveEval:
    ply: int
    my_move: str            # GTP
    best_move: str          # GTP
    my_winrate: float
    best_winrate: float
    my_score: float         # 目差（player 视角，正数=领先）
    best_score: float

    @property
    def loss(self) -> float:
        return max(0.0, self.best_winrate - self.my_winrate)

    @property
    def loss_points(self) -> float:
        """主指标：这一手比 KataGo 首选差多少目。

        为什么不用胜率当主指标：9 路棋盘小，1 目之差就是巨大胜率差，胜率会**饱和**到
        0% / 100%。一旦饱和，「本引擎掉 0.0%、KataGo 掉 0.0%」—— 任何两手都一样，
        指标失去分辨力。目差是连续的，不会饱和。
        """
        return max(0.0, self.best_score - self.my_score)

    @property
    def phase(self) -> str:
        """9 路约 80 手：前 12 手开局、13~40 中盘、41 手后官子。"""
        if self.ply < 12:
            return "开局"
        if self.ply < 40:
            return "中盘"
        return "官子"


class Bench:
    """本引擎的命令行进程（长驻，逐行问答）。"""

    def __init__(self) -> None:
        self.p = subprocess.Popen(
            [str(BENCH)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            text=True, bufsize=1,
        )

    def ask(self, line: str) -> str:
        assert self.p.stdin and self.p.stdout
        self.p.stdin.write(line + "\n")
        self.p.stdin.flush()
        out = (self.p.stdout.readline() or "").strip()
        if out.startswith("err"):
            raise RuntimeError(f"bench 报错：{out}  ← 命令：{line[:120]}")
        return out

    def genmove(self, size: int, color: str, difficulty: str, seed: int, moves: list[str]) -> str:
        """moves 用 GTP；bench 只认 x,y，所以这里转换后再发。

        （第一版直接把 GTP 传过去，bench 报 NumberFormatException: "E2" ——
          两种表示必须显式分层：对外 GTP、对内 x,y，别混着传。）
        """
        spec = " ".join(
            m if m == "pass" else f"{gtp_to_xy(m, size)[0]},{gtp_to_xy(m, size)[1]}"
            for m in moves
        ) if moves else "-"
        out = self.ask(f"genmove {size} {color} {difficulty} {seed} {spec}")
        if out == "pass":
            return "pass"
        x, y = (int(v) for v in out.split(","))
        return xy_to_gtp(x, y, size)

    def selfplay(self, size: int, difficulty: str, plies: int, seed: int) -> list[str]:
        out = self.ask(f"selfplay {size} {difficulty} {plies} {seed}")
        return [m if m == "pass" else xy_to_gtp(*[int(v) for v in m.split(",")], size)
                for m in out.split()]

    def close(self) -> None:
        try:
            self.ask("quit")
        except Exception:
            pass
        self.p.terminate()


class KataGoAnalysis:
    """KataGo 的分析引擎：一行 JSON 进、一行 JSON 出。"""

    @staticmethod
    def _env() -> dict:
        """LD_LIBRARY_PATH 必须**同时**含 WSL 的 libcuda 与 pip 装的 cuDNN 9。

        踩过两次：
          · 只给 cudnn 目录 → libcuda 找不到 → "no CUDA-capable device is detected"
          · 系统的 libcudnn.so.9 是个指向 8.9.2 的假链接 → "version `libcudnn.so.9' not found"
        """
        return {
            **os.environ,
            "LD_LIBRARY_PATH": f"/usr/lib/wsl/lib:{KATAGO_DIR / 'cuda-libs' / 'nvidia' / 'cudnn' / 'lib'}",
        }

    def __init__(self, max_visits: int, size: int) -> None:
        cfg = KATAGO_DIR / "analysis_example.cfg"
        self.p = subprocess.Popen(
            [str(KATAGO_DIR / "katago"), "analysis",
             "-model", str(NET), "-config", str(cfg),
             # reportAnalysisWinratesAs 显式钉成 BLACK：示例配置里就是 BLACK，
             # 但如果哪天它变了，评测会**静默**地把黑白搞反。自己声明才安全。
             "-override-config",
             f"maxVisits={max_visits},chineseRules=true,reportAnalysisWinratesAs=BLACK"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, bufsize=1, env=self._env(),
        )
        self.size = size
        self._id = 0

    def analyze(self, moves: list[str], turn: int | None = None) -> dict:
        """moves 为 GTP 着法序列（黑先交替）。返回根节点信息与候选着法。"""
        self._id += 1
        qid = str(self._id)
        req = {
            "id": qid,
            # 保留 pass：它消耗一个回合，过滤会让后续执色错位。KataGo 分析接口接受 "pass"。
            "moves": [["B" if i % 2 == 0 else "W", m] for i, m in enumerate(moves)],
            "rules": "chinese",
            "komi": komi_for_size(self.size),
            "boardXSize": self.size,
            "boardYSize": self.size,
            # ⚠️ 不要在这里传 "maxVisits": None —— KataGo 会解析成 0 次搜索，
            # 于是 moveInfos 返回空数组，而且**不报错**，看起来像"没取到样本"。
            # 访问量统一由 -override-config maxVisits=N 控制。
        }
        if turn is not None:
            req["analyzeTurns"] = [turn]
        assert self.p.stdin and self.p.stdout
        self.p.stdin.write(json.dumps(req) + "\n")
        self.p.stdin.flush()
        while True:
            line = self.p.stdout.readline()
            if not line:
                err = (self.p.stderr.read() if self.p.stderr else "") or ""
                raise RuntimeError(f"KataGo 没有输出，已退出。stderr:\n{err[-2000:]}")
            resp = json.loads(line)
            if resp.get("id") == qid:
                return self._check(resp, f"moves={moves} at {turn}")

    @staticmethod
    def _check(resp: dict, ctx: str) -> dict:
        """KataGo 遇到非法请求会回 {"error": ...} 而不是崩，且不带 rootInfo。

        第一版直接读 rootInfo，于是 KeyError 把真正的错误信息吞掉了，
        排查时只能看到"没取到样本"。评测脚本必须把引擎的拒绝原因原样报出来。
        """
        if "error" in resp:
            raise RuntimeError(f"KataGo 拒绝（{ctx}）：{resp['error']} | {resp.get('field','')}")
        return resp

    def _winrate_for(self, resp: dict, player: str) -> float:
        """把黑方视角的胜率换算成 player 视角。

        ⚠️ 我们显式设了 reportAnalysisWinratesAs=BLACK，所以 winrate 永远是**黑方**胜率。
        第一版这里按「轮到下棋的一方」理解，黑白会整体反掉，而且不会报错。
        """
        black_wr = float(resp["rootInfo"]["winrate"])
        return black_wr if player == "B" else 1.0 - black_wr

    def _score_for(self, resp: dict, player: str) -> float:
        """目差也统一按黑方视角给出（与 winrate 同源），再换算到 player。"""
        lead = float(resp["rootInfo"]["scoreLead"])
        return lead if player == "B" else -lead

    def score_of_move(self, moves: list[str], move: str, player: str) -> float:
        return self._score_for(self.analyze(moves + [move]), player)

    def winrate_of_move(self, moves: list[str], move: str, player: str) -> float:
        """查「走完这一手之后」落在 player 头上的胜率。"""
        return self._winrate_for(self.analyze(moves + [move]), player)

    def close(self) -> None:
        self.p.terminate()


def _eval_ply(bench, kg, size, difficulty, args, game, ply, player, moves):
    """评估单个局面；被拒/无法评估时抛异常，由调用方跳过并计数。"""
    mine = bench.genmove(size, player, difficulty, args.seed + ply, game[:ply])
    if mine == "pass":
        return None
    resp = kg.analyze(moves)
    infos = resp.get("moveInfos") or []
    if not infos:
        return None
    best = infos[0]["move"]
    best_wr = kg._winrate_for({"rootInfo": infos[0]}, player)
    best_sc = kg._score_for({"rootInfo": infos[0]}, player)
    if mine == best:
        my_wr, my_sc = best_wr, best_sc
    else:
        my_wr = kg.winrate_of_move(moves, mine, player)
        my_sc = kg.score_of_move(moves, mine, player)
    e = MoveEval(ply, mine, best, my_wr, best_wr, my_sc, best_sc)
    print(f"    第{ply:>3}手 {player} {e.phase}  本引擎 {mine:<4}({my_sc:+5.1f}目)  "
          f"KataGo {best:<5}({best_sc:+5.1f}目)  丢 {e.loss_points:5.1f}目", flush=True)
    return e


def cmd_grade(args: argparse.Namespace) -> int:
    size, difficulty = args.size, args.difficulty.lower()   # core 的难度 id 是小写
    sample_from = (args.sample_from or difficulty).lower()
    print(f"  [参数] 尺寸={size} 被判档={difficulty} 取局面用={sample_from} "
          f"访问量={args.visits} 取样={args.positions} 手")
    bench = Bench()
    kg = KataGoAnalysis(max_visits=args.visits, size=size)
    try:
        # 用本引擎的自战棋谱取局面：这些正是它真实会走出来的局面。
        # sample_from 让「被判档」与「取局面用哪档」解耦 —— 五档在**同一批局面**上比较，
        # 否则各档用的局面互不相同，档间差异里混着局面差异，不可控。
        # 缓存：同一 (尺寸,难度,手数,种子) 的自战棋谱只算一次。
        # 不加缓存的话，五档扫描要重复自战 5 次 —— 大师档 60 手 × 3 秒 = 每档 3 分钟白烧。
        #
        # **多盘取样是让量尺变细的关键**。单盘棋在「首次停一手前」只能取到约 28 个
        # 可用局面 → 标准误约 1.5~2 目，而我要分辨的档位/网络差异只有 0.7~1.3 目，
        # 全部落在噪声里（实测 8 个不同网络的差距 1.27 目，谁都分不出来）。
        # 多盘拼接后 n≈28×N，标准误按 √N 收缩。
        games: list[list[str]] = []
        for gi in range(max(1, args.games)):
            seed = args.seed + gi * 100003          # 每盘换种子，避免取到同一盘棋
            cache = KATAGO_DIR / f"selfplay-{size}-{sample_from}-{args.plies}-{seed}.txt"
            if cache.exists():
                games.append(cache.read_text().split())
            else:
                g = bench.selfplay(size, sample_from, args.plies, seed)
                cache.write_text(" ".join(g))
                games.append(g)
        print(f"  自战棋谱（{difficulty} / {size} 路）：{len(games)} 盘，"
              f"各 {[len(g) for g in games]} 手")
        # **只在首次停一手之前取样**。两个理由：
        #  ① 停一手之后双方已收官，任何一手都"几乎不丢目"，会把均值压低 ——
        #     实测 200 手自战里 28 个样本有 20 个落在官子，于是入门档算出 1.71 目，
        #     比大师档还"好"，纯属样本构成的假象。
        #  ② 各档"会不会停手"不同，会各自跳过不同的点，比出来的差异就成了
        #     样本构成差异而不是棋力差异 —— 这是最隐蔽的一类评测错误。
        evals: list[MoveEval] = []
        skipped: list[tuple[int, str]] = []
        sample_span = 0
        for game in games:
            first_pass = next((i for i, m in enumerate(game) if m == "pass"), len(game))
            sample_span += first_pass
            for ply in range(0, first_pass, args.stride):
                if len(evals) >= args.positions:
                    break
                moves = [m for m in game[:ply] if m != "pass"]
                player = "B" if ply % 2 == 0 else "W"
                try:
                    evals_now = _eval_ply(bench, kg, size, difficulty, args, game, ply, player, moves)
                except Exception as e:
                    # **一个局面被 KataGo 拒绝就跳过，不要毁掉整轮**。
                    # 长自战里出现"我这合法、KataGo 认为非法"是正常的：
                    # 我的引擎用简单劫，KataGo 默认带超级劫 —— 劫争反复时会有分歧。
                    # 第一版直接抛异常，导致整轮分数缺失，而外层脚本会误用上一轮的结果文件。
                    skipped.append((ply, f"{e.__class__.__name__}: {str(e)[:80]}"))
                    continue
                if evals_now is not None:
                    evals.append(evals_now)
            if len(evals) >= args.positions:
                break
        if not evals:
            print("  没取到有效样本"); return 1
        pts = sorted(e.loss_points for e in evals)
        mean = sum(pts) / len(pts)
        agree = sum(1 for e in evals if e.my_move == e.best_move)
        print(f"\n  === {difficulty} / {size} 路 / KataGo {args.visits} 访问量 / {len(evals)} 个样本"
              f"（取自 {len(games)} 盘自战的首次停一手前）===")
        print(f"    平均每手丢 {mean:.2f} 目   中位 {pts[len(pts)//2]:.2f}   最差 {pts[-1]:.2f}")
        print(f"    与 KataGo 首选一致 {agree}/{len(evals)}  ({agree*100//len(evals)}%)")
        if skipped:
            print(f"    ⚠ 跳过 {len(skipped)} 个局面（KataGo 拒绝或无法评估）：{skipped[:3]}")
        print("    分段：")
        for ph in ("开局", "中盘", "官子"):
            sub = [e.loss_points for e in evals if e.phase == ph]
            if sub:
                print(f"      {ph}  {len(sub):>2} 手   平均丢 {sum(sub)/len(sub):5.2f} 目   "
                      f"最差 {max(sub):5.2f} 目")
        # 最差的几手单独列出来 —— 改进方向就在这几手里
        worst = sorted(evals, key=lambda e: -e.loss_points)[:3]
        print("    最差的几手：")
        for e in worst:
            if e.loss_points > 0:
                print(f"      第{e.ply:>3}手 {e.phase}  本引擎 {e.my_move}({e.my_score:+.1f}目)  "
                      f"应走 {e.best_move}({e.best_score:+.1f}目)  丢 {e.loss_points:.1f}目")
        out = KATAGO_DIR / f"grade-{size}-{difficulty}.json"
        out.write_text(json.dumps(
            [{**e.__dict__, "loss_points": e.loss_points, "phase": e.phase} for e in evals],
            ensure_ascii=False, indent=1))
        print(f"    明细 → {out}")
        return 0
    finally:
        bench.close(); kg.close()


def cmd_match(args: argparse.Namespace) -> int:
    print("  （match 阶段待 KataGo GTP 验证通过后实现）")
    return 1


def main() -> int:
    ap = argparse.ArgumentParser(description="用 KataGo 给本引擎当考官")
    sub = ap.add_subparsers(dest="cmd", required=True)

    g = sub.add_parser("grade", help="量每手的胜率损失")
    g.add_argument("--size", type=int, default=9)
    g.add_argument("--difficulty", default="MASTER")
    g.add_argument("--visits", type=int, default=200, help="KataGo 每次分析的访问量")
    g.add_argument("--plies", type=int, default=60, help="自战多少手用于取样")
    g.add_argument("--stride", type=int, default=3, help="每隔几手取一个局面")
    g.add_argument("--positions", type=int, default=15, help="最多评估多少个局面")
    g.add_argument("--sample-from", default=None,
                   help="用哪个难度的自战棋谱取局面（默认与被判档相同）；扫描时统一给 master 以保证可比")
    g.add_argument("--seed", type=int, default=20261001)
    g.add_argument("--games", type=int, default=1,
                   help="用几盘自战取局面。单盘只够取约 28 个局面（标准误 1.5~2 目），"
                        "分辨 1 目级差异必须多盘（√N 收缩）")
    g.set_defaults(func=cmd_grade)

    m = sub.add_parser("match", help="与 KataGo 对局定档")
    m.add_argument("--size", type=int, default=9)
    m.add_argument("--difficulty", default="MASTER")
    m.add_argument("--games", type=int, default=10)
    m.add_argument("--visits", type=int, default=200)
    m.set_defaults(func=cmd_match)

    args = ap.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
