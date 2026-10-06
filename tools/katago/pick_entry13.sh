#!/usr/bin/env bash
# ============================================================
# 入门（entry）档候选筛选 —— 用**权威口径**逐个验，不是猜参数。
#
# 背景（为什么需要这个脚本）：
#   13 路低端的「数据量」杠杆不敏感：入门 500 样本 vs 初级 2000 样本，
#   100 局互殴只有 47%（z=-0.60，噪声内）；把入门换成 64×4 反而更强（56%，z=+1.20）。
#   逐项核对后确认原因：网络路径下入门与初级能实际起作用只有
#     · netTopK 6 vs 8（候选 10 vs 12）
#     · temperature 1.2 vs 0.7
#   而这两项正是已实测「不单调、分不开档」的噪声旋钮。localRadius 在网络路径里
#   只过滤 4 个启发式兜底候选，影响极小。
#   ⇒ 低端只能靠**网络本身**拉差，且要差得够狠（小容量 + 少样本 + 少步数 = 三重弱化）。
#
# 已实测的对照（旧版 13 路，能分开的那一次）：
#   入门 64×4 @500 样本 @500 步（只训 1 遍，欠拟合） vs 初级 32×2 @2000 @937 步
#   → entry 36%（z=-2.71）✔
#   所以候选里必须有「回到那次配方」这一项。
#
# 判据：entry 视角的 z < -2.5 才算这一对拉开（比 |z|>2 留一点余量，
#       避免"刚好过线、重跑就翻"的那种假达标）。
# 用法： bash pick_entry13.sh
# ============================================================
set -u
cd "$(dirname "$0")"
export PYTHONPATH=$PWD/../ml/pylibs
MODEL=distill-13-all.jsonl

# 候选：名字 宽 块 步数 样本     （由强到弱，全部是同"数据量+容量+步数"三重弱化）
CANDS=(
  "back-64x4-500s-500st  64 4  500  500"   # 回到那次能分开的配方
  "32x2-300s-300st       32 2  300  300"
  "16x1-200s-300st       16 1  300  200"
)

best_z=0; best_name=""
for spec in "${CANDS[@]}"; do
  set -- $spec
  name=$1; w=$2; b=$3; s=$4; lim=$5
  echo "══════ 候选 $name（宽$w 块$b 步$s 样本$lim）══════"

  unset LD_LIBRARY_PATH
  rm -f pv13-entry.bin pv13-entry.json pv13-entry.pt
  python3 -u train_pv.py --data "$MODEL" --out pv13-entry --size 13 \
      --width "$w" --blocks "$b" --steps "$s" --limit "$lim" > "train-entry-$name.log" 2>&1
  if [ ! -s pv13-entry.bin ]; then echo "  ✘ 训练没产出权重，跳过"; continue; fi
  grep -E '^[[:space:]]+step' "train-entry-$name.log" | tail -1 | sed 's/^/  /'

  export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$PWD/cuda-libs/nvidia/cudnn/lib
  python3 verify_symmetry.py --size 13 pv13-entry.bin 2>&1 | grep -E "空盘探针" | sed 's/^/  /'
  unset LD_LIBRARY_PATH

  TAG="e-$name" bash verify13_ladder.sh entry-beginner > "ladder-$name.log" 2>&1
  z=$(grep -oE 'entry 总胜率 [0-9]+%（z=[-+0-9.]+）' "ladder-$name.log" | grep -oE 'z=[-+0-9.]+' | head -1 | sed 's/z=//')
  wr=$(grep -oE 'entry 总胜率 [0-9]+%' "ladder-$name.log" | head -1)
  echo "  $wr   z=$z"
  if [ -n "$z" ]; then
    # 记录绝对值最大的那个（即使都没过线，也留最好的）
    if python3 -c "import sys; sys.exit(0 if abs($z) > abs($best_z) else 1)"; then best_z=$z; best_name=$name; fi
    if python3 -c "import sys; sys.exit(0 if $z < -2.5 else 1)"; then
      echo "  ✔ 这一档拉拉开了（z=$z），采用 $name"
      exit 0
    fi
    echo "  ✘ 仍未拉开（z=$z），试下一个候选"
  fi
done

echo
echo "══ 三个候选都没到 z<-2.5。绝对差最大的是 $best_name（z=$best_z）══"
echo "   如仍不合格，下一步是把入门再压弱（样本 100 以下）或改产品定义（见汇报）"
