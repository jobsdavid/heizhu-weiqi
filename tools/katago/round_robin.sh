#!/usr/bin/env bash
# 五档全循环互殴（10 对），用**回避终局缺陷**的尺子量真实强弱序。
#
# 为什么截到 100 手：引擎的终局判据有缺陷（连环停手被吃光，净胜能到 ±80 目），
# 而那批局会把"谁更强"彻底盖住（实测：胜负完全由执黑决定）。截在终局之前，
# 再由 KataGo 判局面，量的就是纯棋力。
#
# 判读口径：**换色之后还能赢**才算真强于对手；只在执黑时赢，说明只是先手优势。
set -u
cd /home/david/.hermes/workspace/weiqi-tv/tools/katago
export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$PWD/cuda-libs/nvidia/cudnn/lib
export PYTHONPATH=$PWD/../ml/pylibs
N=/home/david/.hermes/workspace/weiqi-tv/src/WeiqiTV/app/src/main/assets/net

PAIRS="master-entry master-beginner master-intermediate master-advanced
advanced-intermediate advanced-beginner advanced-entry
intermediate-beginner intermediate-entry beginner-entry"

for p in $PAIRS; do
  TA="${p%%-*}"; TB="${p##*-}"
  timeout 1800 python3 match.py --tier-a "$TA" --tier-b "$TB" --games 10 --parallel 5 \
    --max-plies 100 --judge-visits 600 \
    --net-a "$N/pv9-$TA.bin" --net-b "$N/pv9-$TB.bin" \
    > "rr-$TA-vs-$TB.log" 2>&1
  echo "══ $TA vs $TB ══"
  sed -n '/^=\{10,\}/,$p' "rr-$TA-vs-$TB.log" | sed 's/^/  /'
done
echo "全循环完成"
