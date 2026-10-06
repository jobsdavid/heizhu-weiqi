#!/usr/bin/env bash
# 重训五档，目标：**既黑白对称、又保持阶梯分开**。
#
# 背景（2026-10-02 实测）：上一版 tier 组虽然阶梯漂亮（五段全显著），但**黑白不对称**
# （+1.53 ~ +3.37 目，只有 master 干净）—— 也就是"AI 执白吃亏"那个病只治轻、没治好。
# 假设：**过拟合**。小样本档的训练回合太多（入门 1500 步 / 500 条 = 192 遍），
# 网络把那一小撮棋的"黑先白后"习惯当规律背下来了。
#
# 这轮只改一件事：**把回合数按数据量压到约 30 遍**（步数 = 数据量 × 30 / batch 64），
# 并把入门档的网络换成正常架构（64×4）—— 顺便解决真机上"入门不思考"：
# 小网络前向瞬时完成，界面上等不到"思考中"；弱仍然靠"数据极少"实现。
#
# 跑完先过**对称闸门**（不过就停，不跑阶梯 —— 省时间）。
set -uo pipefail
cd "$(dirname "$0")"
export PYTHONPATH=$PWD/../ml/pylibs
unset LD_LIBRARY_PATH

DATA=distill-9-all.jsonl
steps_for() {   # 数据量 → 步数（≈30 遍，区间 250~6000）
  local n=$1
  local s=$(( n * 30 / 64 ))
  [ "$s" -lt 250 ] && s=250
  [ "$s" -gt 6000 ] && s=6000
  echo "$s"
}

echo "=== 重训五档（回合数按数据量压到 ~30 遍）==="
printf "  %-13s %-8s %-6s %-6s %s\n" 档位 样本 宽 块 步数
while read -r tier w b lim; do
  s=$(steps_for "$lim")
  rm -f "tier-$tier.bin" "tier-$tier.json" "tier-$tier.pt"
  python3 -u train_pv.py --data "$DATA" --out "tier-$tier" --width "$w" --blocks "$b" \
      --steps "$s" --limit "$lim" > "train-tier-$tier.log" 2>&1
  printf "  %-13s %-8s %-6s %-6s %s  %s\n" "$tier" "$lim" "$w" "$b" "$s" \
    "$(grep -E '^\s+step' "train-tier-$tier.log" | tail -1 | grep -oE 'val 策略 [0-9.]+ 价值 [0-9.]+  top1命中 [0-9.]+%')"
done <<'EOF'
entry        64 4 500
beginner     32 2 2000
intermediate 32 2 3000
advanced     64 4 10000
master       64 6 30000
EOF

echo
echo "=== 对称闸门（不过就停，不浪费阶梯那 40 分钟）==="
export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$PWD/cuda-libs/nvidia/cudnn/lib
bash symmetry_aggregate.sh tier-master tier-advanced tier-intermediate tier-beginner tier-entry | tee /tmp/retrain-symmetry.log
if grep -qE "✘|△" /tmp/retrain-symmetry.log; then
  echo
  echo "✘ 对称仍未过 —— 停在闸门这里，不去跑阶梯。"
  echo "  下一步该查的是：① 是不是过拟合（看训练日志 train/val 差距）② 要不要给训练加正则"
  exit 1
fi

echo
echo "=== 阶梯复测（五段，各 100 局）==="
declare -A NET=( [entry]=tier-entry [beginner]=tier-beginner
                 [intermediate]=tier-intermediate [advanced]=tier-advanced [master]=tier-master )
for p in entry-beginner beginner-intermediate intermediate-advanced advanced-master entry-master; do
  TA="${p%%-*}"; TB="${p##*-}"
  timeout 3600 python3 match.py --tier-a "$TA" --tier-b "$TB" --games 100 --parallel 5 \
    --max-plies 100 --judge-visits 600 \
    --net-a "$PWD/${NET[$TA]}.bin" --net-b "$PWD/${NET[$TB]}.bin" > "lad3-$TA-vs-$TB.log" 2>&1
  printf "  %-30s %s\n" "$p" "$(grep -oE '总胜率 [0-9]+%（z=[-+0-9.]+）' "lad3-$TA-vs-$TB.log" | head -1)"
done
echo "重训 + 双闸门完成"
