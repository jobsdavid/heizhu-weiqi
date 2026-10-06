#!/usr/bin/env python3
"""app 口径对局基准：并行跑 N 局 `appmatch`（产品真实终局流程），汇总成可判定的一组数字。

为什么要有它：2026-10-05 我用 selfplay 数「连环停手」数错了一整天 —— 那个口径不判终局。
本脚本是**唯一权威判据**，输出的都是产品口径的数字：

    摆烂局率      一方连续停手 ≥3 手（对手仍在落子）的局数占比     目标 0
    被吃光局率    终局时一方几乎无目（|黑净胜| ≥ 90% 棋盘点数）     目标 0
    未终局率      打到 maxPlies 还没结束（说明收工判据不工作）      目标 0
    非法手率      引擎给出非法着法                                  目标 0
    黑白胜率差    同档对局两边胜率之差                              目标 ≤ 10 个点
    平均手数      对局长度（太短=崩盘，太长=不终局）

用法：
    python3 app_baseline.py <size> <tierA> <tierB> <games> [maxPlies] [netA] [netB] [judgeNet]
"""
import json
import os
import subprocess
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

ROOT = Path("/home/david/.hermes/workspace/weiqi-tv")
BENCH = ROOT / "src/WeiqiTV/bench/build/install/bench/bin/bench"
KATAGO = ROOT / "tools/katago"

# 开局随机手数（测试专用）：档位温度已归零 ⇒ 引擎对同一局面恒走同一手，
# 不随机开局的话自战 N 局等于同一局，测不出黑白公平性。默认 6（9 路）。
OPEN_RAND = int(os.environ.get("OPEN_RAND", "6"))

# 贴目覆盖（仅测试用）：默认 None = 走产品默认 Komi.forSize。
# 9 路实测黑方胜率仅 30%，需要用不同贴目扫出平衡点（详见 check_komi 流程）。
KOMI = os.environ.get("KOMI")



def one_game(job):
    """跑一局，解析 JSON。任何异常都必须显式冒出来（不许静默变成 0）。"""
    size, ta, tb, seed, max_plies, open_rand, netA, netB, judge, komi = job
    env = {**os.environ, "PYTHONPATH": str(ROOT / "tools/ml/pylibs")}
    # netA = "-" 表示**不设网络** ⇒ 引擎回退到无网络的随机 rollout 路径。
    # 用途：测"低档不用网络能不能弱到初学者可赢"（这是最弱的实现，也是最符合
    # 「少考虑几步」的形态）。bench 的 loadNet 对不存在的文件会直接抛错，
    # 所以只能靠"不设环境变量"来表达"没有网络"。
    if str(netA) != "-":
        env["WEIQI_NET"] = str(netA)
    else:
        # ⚠️ 必须显式删除：否则会从父进程的 os.environ 继承到（例如 shell 里设了 WEIQI_NET=-），
        # bench 会拿 "-" 当路径去加载并抛错。
        env.pop("WEIQI_NET", None)
    if netB and str(netB) != "-":
        env["WEIQI_NET_B"] = str(netB)
    else:
        env.pop("WEIQI_NET_B", None)   # 同 netA：'-' 表示"没有网络"，不能把 '-' 当路径传下去
    if judge:
        env["WEIQI_JUDGE_NET"] = str(judge)
    #    传 "-" 让 bench 的 toDoubleOrNull() 解析失败 ⇒ 走产品默认贴目。
    # ⚠️ 让子已按产品决定移除（见 Difficulty.kt 的历史注释），bench 的该参数位随之取消。
    komi_arg = f" {komi}" if komi is not None else ""
    p = subprocess.run(
        [str(BENCH)],
        input=f"appmatch {size} {ta} {tb} {seed} {max_plies} {open_rand}{komi_arg}\nquit\n",
        capture_output=True, text=True, env=env, timeout=3600,
    )
    for line in p.stdout.splitlines():
        line = line.strip()
        if line.startswith("{"):
            return json.loads(line)
    raise RuntimeError(f"seed={seed} 没拿到 JSON（stdout={p.stdout[:200]!r} stderr={p.stderr[-300:]!r})")


def safe_one_game(job):
    """单局失败不该废掉整轮。

    ⚠️ 实测（2026-10-06）：bench 进程偶发 JVM 类加载崩溃
    （stderr 出现 `core.game.GameState$WhenMappings` + ClassNotFoundException），
    原来异常直接冒泡 ⇒ **一整档 120 局白跑**（长任务跑了 40 分钟才整体失败）。
    改成"记一局失败 + 继续跑"，并在汇总里显式报出失败数（不静默）。
    """
    try:
        return one_game(job)
    except Exception as e:
        return {"error": f"{e.__class__.__name__}: {str(e)[:200]}"}


def main():
    size = int(sys.argv[1])
    ta, tb = sys.argv[2], sys.argv[3]
    games = int(sys.argv[4])
    max_plies = int(sys.argv[5]) if len(sys.argv) > 5 else 400
    netA = Path(sys.argv[6]) if len(sys.argv) > 6 else KATAGO / f"pv{size}-{ta}.bin"
    netB = Path(sys.argv[7]) if len(sys.argv) > 7 else (KATAGO / f"pv{size}-{tb}.bin" if tb != ta else None)
    judge = Path(sys.argv[8]) if len(sys.argv) > 8 else None

    print(f"══ {size} 路 {ta} vs {tb}，{games} 局（产品口径 / GameState 真实终局）══")
    print(f"   网络 {netA.name}" + (f" vs {netB.name}" if netB else "（同）") + (f"  裁判 {judge.name}" if judge else ""))

    jobs = [(size, ta, tb, 1000 + i, max_plies, OPEN_RAND, netA, netB, judge, KOMI)
            for i in range(games)]
    with ProcessPoolExecutor(max_workers=min(10, games)) as ex:
        results = list(ex.map(safe_one_game, jobs))

    errors = [r for r in results if "error" in r]
    results = [r for r in results if "error" not in r]
    if errors:
        print(f"  ⚠️ 失败局数 {len(errors)}/{len(errors) + len(results)}"
              f"（已跳过，未计入统计）首个原因：{errors[0]['error'][:160]}")
    if not results:
        print("  全部对局失败，无法统计"); return 1

    n = len(results)
    cells = size * size
    storm = [r for r in results if r["maxConsecPass"] >= 3]
    eaten = [r for r in results if r["ended"] and abs(r["blackMargin"]) >= 0.9 * cells]
    unfinished = [r for r in results if not r["ended"]]
    illegal = [r for r in results if r["illegal"]]
    ended = [r for r in results if r["ended"]]

    # 黑白胜率（按执色分组，而不是按 A/B —— 要看的是"颜色公平性"）
    black_win = sum(1 for r in ended if (r["winner"] == "A") == r["aBlack"] and r["winner"] != "none")
    white_win = sum(1 for r in ended if (r["winner"] == "B") == r["aBlack"] and r["winner"] != "none")
    a_win = sum(1 for r in ended if r["winner"] == "A")
    b_win = sum(1 for r in ended if r["winner"] == "B")

    # 按「谁执黑」拆分 —— 这是区分两类问题的关键：
    #   · 黑方吃亏（komi 等规则因素）：A 执黑和 B 执黑时，执黑方都低
    #   · 某个网络吃亏（网络/搜索不对称）：只有一方执黑时低
    # 两者在"整体黑白胜率"上表现完全一样，只有拆开才看得出来。
    a_black_games = [r for r in ended if r["aBlack"]]
    b_black_games = [r for r in ended if not r["aBlack"]]
    a_win_black = sum(1 for r in a_black_games if r["winner"] == "A")
    b_win_black = sum(1 for r in b_black_games if r["winner"] == "B")

    def pct(x, d):
        return f"{x}/{d} = {x/d*100:.0f}%" if d else "n/a"

    print()
    print(f"  总局数            {n}")
    print(f"  摆烂局(连停≥3)    {pct(len(storm), n)}           ← 目标 0")
    print(f"  被吃光局          {pct(len(eaten), n)}           ← 目标 0")
    print(f"  未终局(打到上限)  {pct(len(unfinished), n)}           ← 目标 0")
    print(f"  非法手            {pct(len(illegal), n)}           ← 目标 0")
    print(f"  可采信局数        {len(ended)}")
    print(f"  黑方胜 {pct(black_win, len(ended))}   白方胜 {pct(white_win, len(ended))}"
          f"    差 {abs(black_win-white_win)/max(len(ended),1)*100:.0f} 个点   ← 目标 ≤10")
    print(f"  A 胜 {a_win} / B 胜 {b_win}")
    print(f"  A 执黑时 A 胜 {pct(a_win_black, len(a_black_games))}   "
          f"B 执黑时 B 胜 {pct(b_win_black, len(b_black_games))}")
    print(f"     ⇒ 两者都低 = 黑方吃亏（规则/贴目）；一高一低 = 某个网络吃亏")
    if ended:
        avg_plies = sum(r["plies"] for r in results) / n
        print(f"  平均手数          {avg_plies:.0f}")
        margins = sorted(r["blackMargin"] for r in ended)
        print(f"  黑净胜中位/极值   {margins[len(margins)//2]} / {margins[0]} ~ {margins[-1]}")
    # 未终局的局面必须能看见（这是"收工判据失效"的直接证据，不能只报个百分比）
    if unfinished:
        print()
        print(f"  ⚠️ 未终局明细（{len(unfinished)} 局）：")
        for r in unfinished[:6]:
            print(f"     种子{r['seed']} 手数{r['plies']} 停手共{r['passes']}次 "
                  f"最长连停{r['maxConsecPass']}")
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
