#!/usr/bin/env bash
# ============================================================
# 蒸馏数据生成（可续跑 / 可无人值守）
#
# 三条从踩坑来的规矩：
#   1. **追加**写入，不截断。第一版用 `: > "$out"` 截断文件，重跑就把已有样本全丢了。
#   2. 续跑时按"该文件已有样本数"推算已跑过多少局，种子接着往后排，不重复取局面。
#   3. 单个 worker 失败**不中断整批**（否则一夜白跑）；本轮结束后继续下一轮。
#
# 4 个 worker 是实测的最优：GPU 已经 97% 满载，加到 8 个不会再快。
#
# 用法：TARGET=60000 ./gen_all.sh
# ============================================================
set -uo pipefail
cd "$(dirname "$0")"
export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$PWD/cuda-libs/nvidia/cudnn/lib
export PYTHONPATH=$PWD/../ml/pylibs

WORKERS=${WORKERS:-4}
TARGET=${TARGET:-60000}          # 总样本目标
PLIES=${PLIES:-50}
EVERY=${EVERY:-2}
VISITS=${VISITS:-150}
DIFFS=${DIFFS:-entry,beginner,intermediate,advanced}
GAMES_PER_ROUND=${GAMES_PER_ROUND:-120}
MAX_ROUNDS=${MAX_ROUNDS:-20}

# 每局样本数。**注意是 2 倍**：distill_gen 现在同时取奇偶两种手数
# （原先只取偶数手 ⇒ 全部样本都是"黑方该走"，网络对白方完全失效，见该文件注释）。
# 这个数用于"续跑时按已有样本数推算跑过多少局" —— 算错了会让种子重复取到同一批局面。
PER_GAME=$(( PLIES / EVERY * 2 ))
if [ "$PER_GAME" -lt 1 ]; then PER_GAME=1; fi

count_samples() {
  cat distill-9-w*.jsonl 2>/dev/null | wc -l
}

for round in $(seq 1 "$MAX_ROUNDS"); do
  have=$(count_samples)
  if [ "$have" -ge "$TARGET" ]; then
    echo "=== 已有 $have 个样本，达标（目标 $TARGET）==="
    break
  fi
  echo "=== 第 $round 轮：已有 $have / $TARGET ==="

  pids=(); labels=()
  for w in $(seq 0 $((WORKERS-1))); do
    out="distill-9-w${w}.jsonl"
    n_prev=$(wc -l < "$out" 2>/dev/null || echo 0)
    games_prev=$(( n_prev / PER_GAME ))
    seed0=$((1000 + w * 100000 + games_prev ))
    python3 distill_gen.py --games "$GAMES_PER_ROUND" --plies "$PLIES" --every "$EVERY" \
        --visits "$VISITS" --difficulties "$DIFFS" --seed0 "$seed0" --out "$out" --append \
        >> "distill-w${w}.log" 2>&1 &
    pids+=($!)
    labels+=("w$w")
    echo "  worker $w  种子起点 $seed0  该文件已有 $n_prev 样本"
  done
  for i in "${!pids[@]}"; do
    if wait "${pids[$i]}"; then echo "  ${labels[$i]} 完成"; else echo "  ${labels[$i]} 失败（继续下一轮）"; fi
  done
  echo "  本轮结束：样本 $(count_samples) / $TARGET"
done

echo "=== 合并 ==="
cat distill-9-w*.jsonl > distill-9-all.jsonl
echo "  总样本 $(wc -l < distill-9-all.jsonl)"
