#!/usr/bin/env bash
# 为 13 路 / 19 路生成蒸馏语料（**均衡：黑白各半** —— 9 路那次就是栽在"只有黑方"）。
#
# 为什么要重跑一套：网络的价值头是 `nn.Linear(8*size*size, 64)` —— **按尺寸参数化**，
# 9 路的权重喂给 13 路棋盘就是尺寸不符，所以每个尺寸都得有自己的一份语料 + 一组权重。
#
# 目标：13 路约 8000 条、19 路约 6000 条（19 路每样本更慢，先要"能用"，不追极致棋力）。
# 每份生成完立刻过闸门：两色各占比、标签与前缀奇偶一致。
set -uo pipefail
cd "$(dirname "$0")"
export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$PWD/cuda-libs/nvidia/cudnn/lib
export PYTHONPATH=$PWD/../ml/pylibs

WORKERS=4
VISITS=${VISITS:-120}

gen_size() {   # 尺寸 目标样本数
  local size=$1 target=$2
  local per_worker=$(( target / WORKERS ))
  echo "=== ${size} 路：目标 ${target} 条（每 worker ${per_worker} 条，KataGo ${VISITS} 访问量）==="
  pids=()
  for w in $(seq 0 $((WORKERS-1))); do
    local out="distill-${size}-w${w}.jsonl"
    local have
    have=$(wc -l < "$out" 2>/dev/null || echo 0)
    local need=$(( per_worker - have ))
    if [ "$need" -le 0 ]; then
      echo "  worker $w 已有 ${have} 条，达标，跳过"
      continue
    fi
    local games=$(( (need + 49) / 50 ))          # 每局 50 条（两个行棋方各 25）
    # ⚠️ 种子必须按「该文件已经跑过多少局」前移。
    #    distill_gen 的局面种子是 seed0+g；seed0 固定时重跑会把**已有的局面再采一遍**
    #    —— 样本量看着涨，实际全是重复（9 路的 gen_all.sh 有这层偏移，本脚本原先漏了）。
    local games_prev=$(( have / 50 ))
    local seed0=$(( size * 100000 + w * 10000 + games_prev ))
    python3 distill_gen.py --size "$size" --games "$games" --plies 50 --every 2 \
        --visits "$VISITS" --difficulties beginner,intermediate,advanced,master \
        --seed0 "$seed0" --out "$out" --append >> "distill-${size}-w${w}.log" 2>&1 &
    pids+=($!)
    echo "  worker $w 已有 ${have} 条 → 补 ${games} 局（种子起点 ${seed0}）"
  done
  if [ "${#pids[@]}" -gt 0 ]; then
    for p in "${pids[@]}"; do wait "$p" || echo "  警告：某个 worker 失败（继续）"; done
  fi

  echo "  ── 闸门：两色占比 + 标签自洽 ──"
  python3 - "$size" <<'PY'
import json, glob, sys, collections
size = sys.argv[1]
c = collections.Counter(); bad = 0; n = 0
for f in glob.glob(f"distill-{size}-w*.jsonl"):
    for ln in open(f):
        ln = ln.strip()
        if not ln: continue
        s = json.loads(ln); n += 1
        tm = s["toMove"]; mv = len(s.get("moves", []))
        c[tm] += 1
        if tm != ("B" if mv % 2 == 0 else "W"): bad += 1
        assert s["size"] == int(size), f"尺寸不符：{s['size']} != {size}"
print(f"    {size} 路：总 {n} 条  toMove={dict(c)}  标签不自治 {bad} 条")
ok = c.get("B", 0) > 0 and c.get("W", 0) > 0 and bad == 0 and min(c.values()) / max(n, 1) > 0.4
print("    " + ("✔ 通过" if ok else "✘ 未通过（两色失衡或标签错）"))
sys.exit(0 if ok else 1)
PY
}

gen_size 13 "${TARGET13:-8000}"
gen_size 19 "${TARGET19:-6000}"
echo "13/19 语料生成完成"
