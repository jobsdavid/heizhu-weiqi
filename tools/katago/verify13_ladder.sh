#!/usr/bin/env bash
# ============================================================
# 13 路五档阶梯验收：四对相邻档各 100 局互殴。
#
# 判读口径（与 9 路一致）：
#   · 尺子用**档间互殴胜率**，不是丢目（网络把每档都教得"每手只差 1~2 目"时，
#     丢目指标失去分辨力）
#   · 必须 100 局/对：10 局/对时方向可能对但 z 都在 ±1 内，分不开相邻档
#   · 「换色后仍能赢」才算真强于对手；只在执黑时赢 = 只是先手优势
#   · 判据：z 绝对值 > 2（约 95% 置信）才算这一对"可分辨"
#
# 用法： bash verify13_ladder.sh [对局列表...]      默认四对相邻档
#        GAMES=100 JUDGE=600 bash verify13_ladder.sh
# ============================================================
set -u
cd "$(dirname "$0")"
export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$PWD/cuda-libs/nvidia/cudnn/lib
export PYTHONPATH=$PWD/../ml/pylibs

PAIRS="${*:-entry-beginner beginner-intermediate intermediate-advanced advanced-master}"
GAMES=${GAMES:-100}
JUDGE=${JUDGE:-600}
MAXPLIES=${MAXPLIES:-200}
# 并行局数。默认 5；但含**大师档**的对（每手撞 15 秒上限）在 13 路上单对要 ~57 分钟，
# 24 核机器可以把并行提到 10 把墙钟时间砍半。
PARALLEL=${PARALLEL:-5}
# 每对的超时。⚠️ 必须按**最慢的一对**（含大师档）设 —— 原来写 3600（按 9 路估的）
# 会让 13 路"高级↔大师"跑到 34/100 就被掐断、拿不到结论（实测踩到）。
TIMEOUT=${TIMEOUT:-7200}
SIZE=13
TAG=${TAG:-sz13v}

printf '%-24s %s\n' "对局" "结果"
for p in $PAIRS; do
  TA="${p%%-*}"; TB="${p##*-}"
  LOG="$TAG-$TA-vs-$TB.log"
  timeout "$TIMEOUT" python3 match.py --size "$SIZE" --tier-a "$TA" --tier-b "$TB" \
    --games "$GAMES" --parallel "$PARALLEL" --max-plies "$MAXPLIES" --judge-visits "$JUDGE" \
    --net-a "$PWD/pv13-${TA}.bin" --net-b "$PWD/pv13-${TB}.bin" > "$LOG" 2>&1
  R=$(grep -oE '总胜率 [0-9]+%（z=[-+0-9.]+）' "$LOG" | head -1)
  printf '%-24s %s\n' "$p" "${R:-（无结论，看 $LOG）}"
done

echo
echo "── 判据：每对 |z| > 2 才算这一对可分辨；四对全过 = 阶梯成立 ──"
for p in $PAIRS; do
  TA="${p%%-*}"; TB="${p##*-}"
  Z=$(grep -oE 'z=[-+0-9.]+' "$TAG-$TA-vs-$TB.log" 2>/dev/null | head -1 | sed 's/z=//')
  if [ -z "$Z" ]; then echo "  ✘ $p：没读到 z"; continue; fi
  OK=$(python3 -c "print('✔' if abs($Z) > 2 else '✘')")
  echo "  $OK $p  z=$Z"
done
echo "13 路阶梯验收完成"
