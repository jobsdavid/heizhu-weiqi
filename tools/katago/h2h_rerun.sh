#!/usr/bin/env bash
# 重跑档间互殴（开局已修），并用闸门统计"连环停手"的出现率。
#
# 为什么要量化出现率：连环停手（一方连停 7~12 手、另一方把棋盘吃光）是**产品级**缺陷 ——
# 孩子看到的是一台在耍赖/坏掉的 AI。跑一次只有一两局说明不了普遍程度，
# 需要给出"多少局里出现几局"这种可直接判断严重性的数字。
set -u
cd /home/david/.hermes/workspace/weiqi-tv/tools/katago
export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$PWD/cuda-libs/nvidia/cudnn/lib
export PYTHONPATH=$PWD/../ml/pylibs
A=/home/david/.hermes/workspace/weiqi-tv/src/WeiqiTV/app/src/main/assets/net

for p in "master entry" "advanced intermediate" "entry beginner"; do
  set -- $p; TA=$1; TB=$2
  timeout 2400 python3 match.py --tier-a "$TA" --tier-b "$TB" --games 12 --parallel 5 \
    --net-a "$A/pv9-$TA.bin" --net-b "$A/pv9-$TB.bin" \
    > "h2h2-$TA-vs-$TB.log" 2>&1
  echo "--- $TA vs $TB ---"
  grep -E "胜率|平均黑方目差|耗时|执黑时|执白时" "h2h2-$TA-vs-$TB.log" | sed 's/^/   /'
done

echo
echo "════ 连环停手统计：某一方「连续自己的回合都停手」的最长次数 ════"
python3 - <<'PY'
import json, glob
tot = 0
rows = []
for f in sorted(glob.glob("match-*-vs-*.json")):
    try:
        d = json.load(open(f))
    except Exception:
        continue
    for i, g in enumerate(d, 1):
        mv = g.get("moves_full") or []
        if not g.get("ok") or not mv:
            continue
        tot += 1
        # 按执色分别统计：连续属于自己的回合里都停手，最长连续几次
        worst = 0
        for color in ("B", "W"):
            run = best = 0
            for p, m in enumerate(mv):
                if (p % 2 == 0) == (color == "B"):
                    if m == "pass":
                        run += 1
                        best = max(best, run)
                    else:
                        run = 0
            worst = max(worst, best)
        if worst >= 3:
            rows.append((worst, f, i, len(mv), sum(1 for m in mv if m == "pass"), g["lead"]))
for worst, f, i, n, ps, lead in sorted(rows, reverse=True):
    print(f"  {f.replace('match-','').replace('.json',''):<34} 局{i:>2}: "
          f"连停 {worst:>2} 次（总停手 {ps:>2}）手数 {n:>3} 黑方目差 {lead:+.0f}")
print(f"\n  合计 {tot} 局，其中 {len(rows)} 局出现「同一方连停 ≥3 次」"
      f"（{len(rows)*100//max(tot,1)}%）")
PY
echo "互殴复测完成"

