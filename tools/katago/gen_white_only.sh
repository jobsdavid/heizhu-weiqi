#!/usr/bin/env bash
# 只补「白方该走」的蒸馏样本，然后与已有的黑方语料合并成均衡集。
#
# 为什么只补一半：昨天那批 15786 条**全是黑方该走**（采样步长为偶数导致），
# 但黑方那半本身是好的、标签也是对的 —— 直接复用，没必要重跑（重跑 = 白烧 GPU）。
# 真正缺的是"轮到白棋"的局面，而 app 里 AI 永远执白。
#
# 产出：distill-9-all.jsonl（均衡），并在末尾做**颜色分布 + 标签自洽**的校验。
set -uo pipefail
cd "$(dirname "$0")"
export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$PWD/cuda-libs/nvidia/cudnn/lib
export PYTHONPATH=$PWD/../ml/pylibs

WORKERS=${WORKERS:-4}
GAMES=${GAMES:-160}          # 每个 worker 的局数（每局 25 个白方样本 → 4×160×25 = 16000）
PLIES=${PLIES:-50}
EVERY=${EVERY:-2}
VISITS=${VISITS:-150}
DIFFS=${DIFFS:-entry,beginner,intermediate,advanced}

echo "=== 只生成「白方该走」样本：$WORKERS 个 worker × $GAMES 局 ==="
pids=(); labels=()
for w in $(seq 0 $((WORKERS-1))); do
  out="distill-9-white-w${w}.jsonl"
  seed0=$((7000 + w * 100000))
  python3 distill_gen.py --games "$GAMES" --plies "$PLIES" --every "$EVERY" \
      --visits "$VISITS" --difficulties "$DIFFS" --only-color W \
      --seed0 "$seed0" --out "$out" >> "distill-white-w${w}.log" 2>&1 &
  pids+=($!); labels+=("w$w")
  echo "  worker $w  种子起点 $seed0 → $out"
done
for i in "${!pids[@]}"; do
  if wait "${pids[$i]}"; then echo "  ${labels[$i]} 完成"; else echo "  ${labels[$i]} 失败"; fi
done

echo
echo "=== 合并：昨日的黑方语料 + 本次的白方语料 ==="
cat archive-allB/distill-9-w*.jsonl distill-9-white-w*.jsonl > distill-9-all.jsonl
echo "  写出 distill-9-all.jsonl"

echo
echo "=== 闸门：颜色分布 + 标签自洽（缺一条就不许拿去训练）==="
python3 - <<'PY'
import json, collections
c = collections.Counter(); bad = []; n = 0
for ln in open("distill-9-all.jsonl"):
    ln = ln.strip()
    if not ln:
        continue
    s = json.loads(ln); n += 1
    tm = s["toMove"]; mv = len(s.get("moves", []))
    c[tm] += 1
    if tm != ("B" if mv % 2 == 0 else "W"):
        bad.append((mv, tm))
print(f"  总样本 {n}   toMove 分布 = {dict(c)}")
print(f"  标签与前缀奇偶不符 = {len(bad)} 条")
ok = c.get("B", 0) > 0 and c.get("W", 0) > 0 and not bad
if c.get("W", 0) < 0.4 * n or c.get("B", 0) < 0.4 * n:
    print("  ✘ 两色严重失衡（任一色不足 40%）—— 训练出来的网络仍会偏一侧")
    ok = False
print("  ✔ 通过：黑白都有且自洽，可以拿去训练" if ok else "  ✘ 未通过，先别训练")
PY
echo "白方补数完成"
