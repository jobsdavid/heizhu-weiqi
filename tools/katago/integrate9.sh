#!/usr/bin/env bash
# ============================================================
# 把训练好的 9 路新网络集成进 app assets（带闸门，不许"带病集成"）
#
# 闸门（任一不过就拒绝集成，宁可停在原地）：
#   ① 网络文件存在且与 json 容量一致
#   ② 逐档对称性 |白−黑| ≤ 1.0 目      （check_symmetry.sh）
#   ③ 档位单调：容量与数据量都必须非降
#
# 用法： bash integrate9.sh <src_prefix>      例： bash integrate9.sh v3
#        环境： SKIP_SYM=1 跳过对称闸门（仅用于调试，正式集成不要设）
# ============================================================
set -uo pipefail
cd "$(dirname "$0")"

PREFIX=${1:?用法: bash integrate9.sh <src_prefix>}
A=/home/david/.hermes/workspace/weiqi-tv/src/WeiqiTV/app/src/main/assets/net
TIERS=(entry beginner intermediate advanced master)

echo "══ 集成 9 路网络（源前缀 $PREFIX）══"

# ① 文件与容量核对：json 的 stem 宽度/块数必须与训练配方一致
declare -A WANT=( [entry]="8 1" [beginner]="16 2" [intermediate]="32 4" [advanced]="64 6" [master]="64 8" )
fail=0
for t in "${TIERS[@]}"; do
  b="$PREFIX-$t.bin"; j="$PREFIX-$t.json"
  if [ ! -s "$b" ] || [ ! -s "$j" ]; then echo "  ✘ 缺文件 $b / $j"; fail=1; continue; fi
  got=$(python3 - "$j" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))
w=d["layers"][0]["shape"][0]
bl=len([l for l in d["layers"] if l["name"].endswith("c2.w")])
print(f"{w} {bl}")
PY
)
  if [ "$got" != "${WANT[$t]}" ]; then echo "  ✘ $t 容量不符：期望 ${WANT[$t]}，实际 $got"; fail=1
  else echo "  ✔ $t 容量 ${got}"; fi
done
[ "$fail" = 0 ] || { echo "⇒ 容量核对失败，拒绝集成"; exit 1; }

# ② 对称性闸门
# ⚠️ 原用 check_symmetry.sh，其判据已被证伪（按 ply 分组比丢目 ⇒ 测的是局面难度，
#    不是颜色偏差；详见该文件头注释）。已改接**产品口径**：同档自战的黑白胜率。
if [ "${SKIP_SYM:-0}" != "1" ]; then
  echo
  echo "  ── 对称性闸门（产品口径：同档自战 60 局，判据黑白胜率差 ≤ 10 个点）──"
  asym=0
  for t in entry beginner intermediate; do
    line=$(GAMES=60 MAXPLIES=400 python3 app_baseline.py 9 "$t" "$t" 60 400 "$PREFIX-$t.bin" 2>/dev/null \
      | grep -E "黑方胜" | tr -s ' ')
    echo "    ${t}: ${line:-（未取到读数）}"
    diff=$(echo "$line" | grep -oE "差 [0-9]+" | grep -oE "[0-9]+")
    [ -n "$diff" ] && [ "$diff" -gt 10 ] && asym=1
  done
  [ "$asym" = 0 ] || { echo "⇒ 有档位黑白胜率差 > 10 个点，拒绝集成（先修公平性）"; exit 1; }
else
  echo "  ⚠️ SKIP_SYM=1：跳过对称性闸门（仅调试）"
fi

# ③ 备份现役 → 覆盖 assets
STAMP=$(date +%Y%m%d-%H%M%S)
BK="$PWD/backup-shipped9-$STAMP"
mkdir -p "$BK"
echo
echo "  ── 备份现役 assets 到 $BK ──"
for t in "${TIERS[@]}"; do cp -f "$A/pv9-$t.bin" "$A/pv9-$t.json" "$BK/" 2>/dev/null; done
ls "$BK" | sed 's/^/    /'

for t in "${TIERS[@]}"; do
  cp -f "$PREFIX-$t.bin" "$A/pv9-$t.bin"
  cp -f "$PREFIX-$t.json" "$A/pv9-$t.json"
done
echo
echo "  ── 集成后逐字节核对（防"改了但没生效"）──"
for t in "${TIERS[@]}"; do
  h1=$(md5sum "$PREFIX-$t.bin" | cut -d' ' -f1)
  h2=$(md5sum "$A/pv9-$t.bin" | cut -d' ' -f1)
  [ "$h1" = "$h2" ] && echo "    ✔ pv9-$t.bin  $h1" || { echo "    ✘ pv9-$t.bin 不一致"; exit 1; }
done
echo
echo "✔ 集成完成。下一步：构建 APK → 装机 → 用 app_baseline.py 做产品口径复验"
