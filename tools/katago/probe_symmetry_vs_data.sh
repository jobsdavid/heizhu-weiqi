#!/usr/bin/env bash
# 找「不偏色」的最小数据量：入门档的弱化应该靠**容量**（极小网络），
# 而不是靠"极少数据"—— 实测 500 样本会让网络漂出黑白不对称（|白-黑| 达 5.44 目）。
# 这里一次训几个变体，逐个量对称性，找到第一个 |白-黑| ≤ 1 目的配置。
set -uo pipefail
cd "$(dirname "$0")"
export PYTHONPATH=$PWD/../ml/pylibs
unset LD_LIBRARY_PATH

DATA=distill-9-all.jsonl

train_and_check() {   # 名字 宽 块 样本 步数
  local name=$1 w=$2 b=$3 lim=$4 steps=$5
  rm -f "probe-$name.bin" "probe-$name.json" "probe-$name.pt"
  python3 -u train_pv.py --data "$DATA" --out "probe-$name" --size 9 \
      --width "$w" --blocks "$b" --steps "$steps" --limit "$lim" > "probe-$name.train.log" 2>&1
  [ -s "probe-$name.bin" ] || { echo "  ✘ $name 没产出"; return; }
  printf "  %-16s 宽%-3s 块%-2s 样本%-6s 步%-5s  " "$name" "$w" "$b" "$lim" "$steps"
  grep -E '^[[:space:]]+step' "probe-$name.train.log" | tail -1 | grep -oE 'val 策略 [0-9.]+ 价值 [0-9.]+  top0?1?命中 [0-9.]+%' | tr -d '\n'
  echo
}

echo "══ 训练变体（入门档：容量 16x1 固定，只改数据量） ══"
train_and_check e-16x1-n1000   16 1 1000 468
train_and_check e-16x1-n3000   16 1 3000 1406
train_and_check e-16x1-n8000   16 1 8000 3750
train_and_check e-32x2-n3000   32 2 3000 1406

echo
echo "══ 逐个体量对称性（判据 |白−黑| ≤ 1 目）══"
export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$PWD/cuda-libs/nvidia/cudnn/lib
for n in e-16x1-n1000 e-16x1-n3000 e-16x1-n8000 e-32x2-n3000; do
  [ -s "probe-$n.bin" ] || continue
  WEIQI_NET="$PWD/probe-$n.bin" timeout 900 python3 grade.py grade --size 9 --difficulty master \
      --sample-from master --plies 200 --stride 5 --positions 300 --games 14 --visits 200 \
      > "probe-$n.grade.log" 2>&1
  cp -f grade-9-master.json "probe-$n.json" 2>/dev/null
done
python3 - <<'PY'
import json, statistics as st, glob, os
print(f"  {'变体':<16}{'黑方该走':>10}{'白方该走':>10}{'差值':>9}   判定")
for f in sorted(glob.glob("probe-*.json")):
    d = json.load(open(f))
    b = [e["loss_points"] for e in d if e["ply"] % 2 == 0]
    w = [e["loss_points"] for e in d if e["ply"] % 2 == 1]
    if not b or not w: continue
    diff = st.mean(w) - st.mean(b)
    v = "✔ 对称" if abs(diff) <= 1.0 else ("△ 仍偏" if abs(diff) <= 2.5 else "✘ 明显不对称")
    print(f"  {f[6:-5]:<16}{st.mean(b):>10.2f}{st.mean(w):>10.2f}{diff:>+9.2f}   {v}")
PY
