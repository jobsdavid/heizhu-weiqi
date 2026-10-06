#!/usr/bin/env bash
# ============================================================
# ⚠️ 已废弃 —— 本脚本的判据无效，不要再用它判断“网络偏色”！
#
# 原因（2026-10-05 实测）：它按 `ply` 奇偶把局面分成“黑方该走 / 白方该走”两组比丢目，
# 而这两组**本来就是不同的局面**（难度不同）⇒ 测出来的是局面难度差，不是颜色偏差。
# 一个“理论上不可能偏色”的相对视角网络，被它测出 +1.8~2.3 目，指标本身的缺陷就此暴露。
#
# 正确的判据：
#   ① 网络层对称性 → 用**镜像检验**（颜色对调 + 行棋方对调 ⇒ 相对视角下是同一输入，
#      网络输出必须完全相同）：tools/katago 的 netcheck + 一段脚本即可
#   ② 产品层公平性 → 用**同档自战的黑白胜率**（app_baseline.py，60 局以上）
#
# 保留本文件只为历史参考与复现旧读数，**不要**把它接进任何验收流程。
# ============================================================
echo "⚠️ check_symmetry.sh 的判据已废弃（详见文件头注释），请改用："
echo "   · 网络层：镜像检验（netcheck，颜色对调 + 行棋方对调 ⇒ 输出应完全相同）"
echo "   · 产品层：app_baseline.py 同档自战的黑白胜率（60 局以上）"
echo
read -r -p "仍要运行历史脚本？(y/N) " ans
[ "$ans" = "y" ] || exit 1

set -uo pipefail
cd "$(dirname "$0")"
export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$PWD/cuda-libs/nvidia/cudnn/lib
export PYTHONPATH=$PWD/../ml/pylibs

SIZE=${SIZE:-$1}; shift
[[ "${SIZE:-}" =~ ^[0-9]+$ ]] || { echo "用法: bash check_symmetry.sh <size> <net...>"; exit 1; }
POS=${POS:-300}
GAMES=${GAMES:-14}

echo "══ ${SIZE} 路网络对称性闸门（判据 |白−黑| ≤ 1.0 目）══"
for net in "$@"; do
  [ -s "$net" ] || { echo "  ✘ 缺网络 $net"; exit 1; }
  name=$(basename "$net" .bin)
  WEIQI_NET="$(realpath "$net")" timeout 900 python3 grade.py grade --size "$SIZE" --difficulty master \
      --sample-from master --plies 200 --stride 5 --positions "$POS" --games "$GAMES" --visits 200 \
      > "symchk-$name.log" 2>&1
  if [ ! -s "grade-$SIZE-master.json" ]; then echo "  ✘ $name 没产出 grade json"; exit 1; fi
  cp -f "grade-$SIZE-master.json" "symchk-$name.json"
  printf "  %-18s %s\n" "$name" "$(grep -E '平均每手丢' "symchk-$name.log" | head -1)"
done

python3 - "$SIZE" "$@" <<'PY'
import json, statistics as st, sys, os
size, nets = sys.argv[1], sys.argv[2:]
print()
print(f"  {'网络':<18}{'黑方该走':>10}{'白方该走':>10}{'差值':>9}   判定")
bad = 0
for net in nets:
    name = os.path.basename(net)[:-4]
    f = f"symchk-{name}.json"
    d = json.load(open(f))
    b = [e["loss_points"] for e in d if e["ply"] % 2 == 0]
    w = [e["loss_points"] for e in d if e["ply"] % 2 == 1]
    if not b or not w:
        print(f"  {name:<18} 缺一侧数据"); bad += 1; continue
    diff = st.mean(w) - st.mean(b)
    v = "✔ 对称" if abs(diff) <= 1.0 else ("△ 仍偏" if abs(diff) <= 2.5 else "✘ 明显不对称")
    if abs(diff) > 1.0:
        bad += 1
    print(f"  {name:<18}{st.mean(b):>10.2f}{st.mean(w):>10.2f}{diff:>+9.2f}   {v}")
print()
print(f"  ⇒ {len(nets)-bad}/{len(nets)} 档通过；{'✔ 全部对称' if bad==0 else f'✘ {bad} 档未过'}")
sys.exit(0 if bad == 0 else 1)
PY
