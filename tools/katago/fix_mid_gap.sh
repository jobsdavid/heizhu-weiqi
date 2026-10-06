#!/usr/bin/env bash
# 只调「中级」这一档，把 中级↔高级 那段拉开；测它两侧那两对（各 100 局）。
#
# 背景：100 局/对实测 5 段里 4 段成立，唯独 中级 vs 高级 51%/49% 分不开。
# 不猜原因，直接把中级的量级降一档（数据 5k→3k、容量 48×4→32×2），
# 再用同一个尺子重测两侧，看两段是否都显著。
set -uo pipefail
cd "$(dirname "$0")"
export PYTHONPATH=$PWD/../ml/pylibs
unset LD_LIBRARY_PATH

echo "=== 重训「中级」（3k 样本 · 32×2）==="
rm -f tier2-intermediate.bin tier2-intermediate.json tier2-intermediate.pt
python3 -u train_pv.py --data distill-9-all.jsonl --out tier2-intermediate \
  --width 32 --blocks 2 --steps 3000 --limit 3000 > train-tier2-intermediate.log 2>&1
grep -E '^\s+step' train-tier2-intermediate.log | tail -1

export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$PWD/cuda-libs/nvidia/cudnn/lib
echo
echo "=== 用同一把尺子重测两段（各 100 局）==="
declare -A NET=( [entry]=tier-entry [beginner]=tier-beginner
                 [intermediate]=tier2-intermediate [advanced]=tier-advanced [master]=tier-master )
for p in beginner-intermediate intermediate-advanced; do
  TA="${p%%-*}"; TB="${p##*-}"
  timeout 3600 python3 match.py --tier-a "$TA" --tier-b "$TB" --games 100 --parallel 5 \
    --max-plies 100 --judge-visits 600 \
    --net-a "$PWD/${NET[$TA]}.bin" --net-b "$PWD/${NET[$TB]}.bin" \
    > "lad2-$TA-vs-$TB.log" 2>&1
  echo "══ $TA vs $TB ══"
  sed -n '/^=\{10,\}/,$p' "lad2-$TA-vs-$TB.log" | sed 's/^/  /'
done
echo "中级调档验证完成"
