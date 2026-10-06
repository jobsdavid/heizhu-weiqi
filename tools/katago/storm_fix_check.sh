#!/usr/bin/env bash
# 验证"连环停手"是否被收工判据的修正解决（阈值 2 → 1）。
#
# 判据（修前实测：38 局里 12 局 = 31% 出现、净胜 ±80 目）：
#   同一方「连续自己的回合都停手」最长次数 ≥3 的局数应为 0；
#   目差也不该再出现 ±60 目那种"一方被吃光"的量级。
# 刻意**不设 --max-plies**：修好之后就该能自然下到终局，不再需要回避。
set -u
cd "$(dirname "$0")"
export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$PWD/cuda-libs/nvidia/cudnn/lib
export PYTHONPATH=$PWD/../ml/pylibs

declare -A NET=( [entry]=bal-n2000 [beginner]=bal-w16b1 [intermediate]=bal-w32b2
                 [advanced]=bal-w48b4 [master]=bal-w64b6 )

for p in ${*:-master-entry advanced-intermediate}; do
  TA="${p%%-*}"; TB="${p##*-}"
  timeout 3600 python3 match.py --tier-a "$TA" --tier-b "$TB" --games 12 --parallel 5 \
    --judge-visits 600 \
    --net-a "$PWD/${NET[$TA]}.bin" --net-b "$PWD/${NET[$TB]}.bin" \
    > "stormfix-$TA-vs-$TB.log" 2>&1
  echo "══ $TA（${NET[$TA]}） vs $TB（${NET[$TB]}）· 全长对局 ══"
  sed -n '/^=\{10,\}/,$p' "stormfix-$TA-vs-$TB.log" | sed 's/^/  /'
done

echo
echo "════ 连环停手统计（本轮刚跑的那几对，全部局含被筛掉的）════"
python3 - <<'PY'
import json, glob, os, re
tot = storm = extreme = 0
for f in sorted(glob.glob("match-*-vs-*.json")):
    if not os.path.exists("stormfix-" + os.path.basename(f).replace("match-", "", 1).replace(".json", ".log")):
        continue
    for g in json.load(open(f)):
        if not g.get("ok"):
            continue
        tot += 1
        if (g.get("max_pass_run") or 0) >= 3:
            storm += 1
        if abs(g.get("lead", 0)) >= 60:
            extreme += 1
print(f"  共 {tot} 局：连停≥3 次 {storm} 局（{storm*100//max(tot,1)}%）"
      f"；目差 ≥60 目 {extreme} 局（{extreme*100//max(tot,1)}%）")
print("  修前对照：连停≥3 次 31%，目差常见 ±72~±81")
PY
echo "连环停手验证完成"
