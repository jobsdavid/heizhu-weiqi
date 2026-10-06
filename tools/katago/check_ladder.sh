#!/usr/bin/env bash
# ============================================================
# 相邻档「阶梯」闸门（判据：高档必须显著强于低档，|z| > 2）
#
# 判据口径：**产品真实对局流程**（走 GameState 终局），不是评测台裸自战。
# 每对局数由 GAMES 控制；A/B 执色按 seed 奇偶交替，排除颜色偏差。
#
# 用法： bash check_ladder.sh <size> <net_low.bin> <net_high.bin> [<net3.bin> ...]
#        环境： GAMES=24 MAXPLIES=400
# ============================================================
set -uo pipefail
cd "$(dirname "$0")"

SIZE=$1; shift
GAMES=${GAMES:-24}
MAXPLIES=${MAXPLIES:-400}
[ $# -ge 2 ] || { echo "用法: bash check_ladder.sh <size> <net...>（按档位从低到高）"; exit 1; }

echo "══ ${SIZE} 路阶梯闸门（产品口径 · 相邻档互殴 · 每对 ${GAMES} 局）══"
echo "   判据：高档胜率必须显著高于低档（|z| > 2）"
echo

prev=""
for net in "$@"; do
  [ -s "$net" ] || { echo "  ✘ 缺网络 $net"; exit 1; }
  if [ -n "$prev" ]; then
    lo=$(basename "$prev" .bin); hi=$(basename "$net" .bin)
    echo "  ── $lo  vs  $hi ──"
    GAMES=$GAMES MAXPLIES=$MAXPLIES python3 app_baseline.py "$SIZE" "$lo" "$hi" "$GAMES" "$MAXPLIES" "$prev" "$net" 2>&1 \
      | grep -E "A 胜|黑方胜|摆烂|可采信" | sed 's/^/    /'
    echo
  fi
  prev="$net"
done

echo "  ⚠️ 判读：A 是低档、B 是高档。B 胜率应显著高于 A。"
echo "     若两者接近（差 < 10 个点）⇒ 该相邻对**分不开**，档位设置失败。"
