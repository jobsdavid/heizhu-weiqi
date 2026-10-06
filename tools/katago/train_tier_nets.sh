#!/usr/bin/env bash
# 五档网络：按「数据量 × 容量」两个**量级杠杆**给每档一个确定的差。
#
# 为什么这样做：实测发现网络容量/训练量的差距很小（30k 数据下 32×2 与 64×6 的
# 每手丢目只差 0.4 目，连"高级 vs 大师"都分不开）。而**数据量**是量级差
# （本项目早前实测：同一架构 2k vs 15k 样本，棋力差距明显）。
# 所以阶梯这样配：越低档 → 数据越少 + 容量越小，每档都差一档量级。
#
# ⚠️ 数据文件必须**先混洗**（已做，固定种子）：`--limit` 取的是文件开头前 N 条，
# 不混洗的话子集又变成纯单色（踩过，见 §13.7）。
set -uo pipefail
cd "$(dirname "$0")"
export PYTHONPATH=$PWD/../ml/pylibs
unset LD_LIBRARY_PATH          # 训练时不能带 KataGo 那套 cudnn（见 §13.7）

DATA=distill-9-all.jsonl

train() {   # 档位 宽 块 步数 样本数
  local tier=$1 w=$2 b=$3 s=$4 lim=$5
  local out="tier-$tier"
  rm -f "$out.bin" "$out.json" "$out.pt"
  python3 -u train_pv.py --data "$DATA" --out "$out" --width "$w" --blocks "$b" \
      --steps "$s" --limit "$lim" > "train-$out.log" 2>&1
  printf "  %-13s 宽%-3s 块%-2s 步%-5s 样本%-6s %s\n" "$tier" "$w" "$b" "$s" "$lim" \
    "$(grep -E '^\s+step' "train-$out.log" | tail -1 | grep -oE 'val 策略 [0-9.]+ 价值 [0-9.]+  top1命中 [0-9.]+%')"
}

echo "=== 子集均衡闸门（--limit 取的是文件开头，必须黑白都有）==="
python3 - <<'PY'
import json, itertools, collections
lines = open("distill-9-all.jsonl").read().strip().split("\n")
for n in (500, 2000, 5000, 10000):
    c = collections.Counter(json.loads(l)["toMove"] for l in itertools.islice(lines, n))
    ok = c.get("B", 0) > 0 and c.get("W", 0) > 0 and min(c.values()) / n > 0.3
    print(f"  前 {n:>6} 条：{dict(c)}  {'✔' if ok else '✘ 不均衡'}")
PY

echo
echo "=== 训练五档（数据量递减 + 容量递减）==="
train master       64 6 6000 30000
train advanced     64 4 5000 10000
train intermediate 48 4 4000  5000
train beginner     32 2 3000  2000
train entry        16 1 1500   500

echo
echo "=== 产物核对 ==="
python3 - <<'PY'
import json, glob, os
for f in sorted(glob.glob("tier-*.json")):
    base = f[:-5]
    d = json.load(open(f)); n = os.path.getsize(base + ".bin")
    print(f"  {base:<18} {n:>9} 字节  声明 {d['bytes']:>9}  {'✔' if n == d['bytes'] else '✘'}")
PY
echo "五档网络训练完成"
