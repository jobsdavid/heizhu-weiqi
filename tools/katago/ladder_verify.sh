#!/usr/bin/env bash
# 五档阶梯验收 —— 用**均衡重训的新网络** + 回避终局缺陷的尺子。
#
# 拟装配（按"数据量 + 容量"两个已被实测证实的量级杠杆排序）：
#   入门 bal-n2000(2k 样本, 32×2) → 初级 bal-w16b1(最小容量)
#   → 中级 bal-w32b2 → 高级 bal-w48b4 → 大师 bal-w64b6
#
# 尺子：对局截到 100 手（回避连环停手）、一律 KataGo 判局面、只采信无连环停手的局。
# 判读：**换色之后还能赢**才算真强于对手；只在执黑时赢 = 只是先手优势。
set -u
cd "$(dirname "$0")"
export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$PWD/cuda-libs/nvidia/cudnn/lib
export PYTHONPATH=$PWD/../ml/pylibs

declare -A NET=(
  [entry]=tier-entry            [beginner]=tier-beginner
  [intermediate]=tier-intermediate [advanced]=tier-advanced [master]=tier-master
)

PAIRS="${*:-entry-beginner beginner-intermediate intermediate-advanced advanced-master entry-master}"
# 每对局数。判定**相邻档**差距需要的样本量比"强弱方向"大得多：
# 实测 10 局/对时，方向对但 z 值都在 ±1 以内（分不开）；要判"相邻档是否真的可分辨"
# 至少 30 局/对。注意本引擎单局约 10 秒，30 局/对仍只要几分钟。
GAMES=${GAMES:-10}

for p in $PAIRS; do
  TA="${p%%-*}"; TB="${p##*-}"
  timeout 3600 python3 match.py --tier-a "$TA" --tier-b "$TB" --games "$GAMES" --parallel 5 \
    --max-plies 100 --judge-visits 600 \
    --net-a "$PWD/${NET[$TA]}.bin" --net-b "$PWD/${NET[$TB]}.bin" \
    > "lad-$TA-vs-$TB.log" 2>&1
  echo "══ $TA（${NET[$TA]}） vs $TB（${NET[$TB]}）· $GAMES 局 ══"
  sed -n '/^=\{10,\}/,$p' "lad-$TA-vs-$TB.log" | sed 's/^/  /'
done
echo "阶梯验收完成"
