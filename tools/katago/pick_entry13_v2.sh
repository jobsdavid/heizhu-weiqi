#!/usr/bin/env bash
# ============================================================
# 入门档候选筛选 · 第二轮（只动网络，不动档位参数）
#
# 第一轮实测（entry 视角胜率，vs 初级 32×2@2000）：
#   64×4 @500 样本 @500 步 → 61%（z=+2.20）  网络太大，入门反而更强
#   32×2 @300 样本 @300 步 → 47%（z=-0.50）  同容量，打平
#   16×1 @200 样本 @300 步 → 41%（z=-1.80）  方向对，未到线
# ⇒ 单调趋势「网络越小越弱」成立，继续压：16×1 再减样本、以及 8×1。
#
# 判据：entry 视角 z < -2.5 才算拉开（留余量，避免刚好过线重跑翻盘）。
# ⚠️ 上一版脚本的坑：grep 写的是 `entry 总胜率`，而汇总表的格式是
#    `entry-beginner  总胜率 …` —— 读不到就误报 z=0。这里按汇总表格式抓。
# ============================================================
set -u
cd "$(dirname "$0")"
export PYTHONPATH=$PWD/../ml/pylibs
MODEL=distill-13-all.jsonl

CANDS=(
  "16x1-100s-150st  16 1 150  100"
  "8x1-200s-300st    8 1 300  200"
)

best_z=99; best_name=""
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

  # 空盘探针：白方第一手不得落一线（不过就换下一个候选，别把废网装进包）
  export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$PWD/cuda-libs/nvidia/cudnn/lib
  probe=$(python3 verify_symmetry.py --size 13 pv13-entry.bin 2>&1 | grep -E "空盘探针" | head -1)
  echo "  $probe"
  unset LD_LIBRARY_PATH
  case "$probe" in *"第1线"*) echo "  ✘ 空盘探针落一线，跳过"; continue ;; esac

  TAG="e2-$name" bash verify13_ladder.sh entry-beginner > "ladder2-$name.log" 2>&1
  line=$(grep -E "entry-beginner" "ladder2-$name.log" | head -1)
  z=$(printf '%s' "$line" | grep -oE 'z=[-+0-9.]+' | head -1 | sed 's/z=//')
  echo "  $line"
  [ -z "$z" ] && { echo "  ✘ 没读到 z（看 ladder2-$name.log）"; continue; }
  python3 -c "import sys; sys.exit(0 if abs($z) < abs($best_z) else 1)" && { best_z=$z; best_name=$name; }
  if python3 -c "import sys; sys.exit(0 if $z < -2.5 else 1)"; then
    echo "  ✔ 拉开了（z=$z）—— 采用 $name"
    exit 0
  fi
  echo "  ✘ 仍未到 z<-2.5（现在是 $z）"
done

echo
echo "══ 两个候选都没到 z<-2.5；最小的是 $best_name（entry 视角 z=$best_z）══"
