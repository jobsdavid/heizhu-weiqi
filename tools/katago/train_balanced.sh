#!/usr/bin/env bash
# 在**均衡语料**（黑白各半）上重训整组网络 —— 与昨天的配方逐项一致，
# 唯一变量是数据补上了"白方该走"的那一半。
#
# ⚠️ 训练时**不要设 LD_LIBRARY_PATH**。
# 那条 cudnn 路径是给 KataGo 二进制用的；设了它，torch 会去加载那套 cuDNN 子库，
# 结果每次 conv 都抛 CUDNN_STATUS_SUBLIBRARY_LOADING_FAILED（实测 A/B：不设就正常）。
# 这与"生成数据时要设"正好相反 —— 两个环节的环境不同，别图省事一锅端。
set -uo pipefail
cd "$(dirname "$0")"
export PYTHONPATH=$PWD/../ml/pylibs
unset LD_LIBRARY_PATH

DATA=distill-9-all.jsonl

train_one() {   # 名称 宽 块 步数 [样本上限]
  local name=$1 w=$2 b=$3 s=$4 lim=${5:-}
  local extra=""
  [ -n "$lim" ] && extra="--limit $lim"
  rm -f "$name.bin" "$name.json" "$name.pt"
  python3 -u train_pv.py --data "$DATA" --out "$name" --width "$w" --blocks "$b" \
      --steps "$s" $extra > "train-$name.log" 2>&1
  local last
  last="$(grep -E '^\s+step' "train-$name.log" | tail -1)"
  printf "  %-13s 宽%-3s 块%-2s 步%-5s 样本%-6s %s\n" "$name" "$w" "$b" "$s" "${lim:-全部}" \
     "$(echo "$last" | grep -oE 'val 策略 [0-9.]+ 价值 [0-9.]+  top1命中 [0-9.]+%')"
}

echo "=== 在均衡语料上重训（$(wc -l < $DATA) 条）==="
train_one bal-w16b1  16 1 1500
train_one bal-w32b2  32 2 3000
train_one bal-w48b4  48 4 4000
train_one bal-w64b4  64 4 5000
train_one bal-w64b6  64 6 6000
train_one bal-n5000  32 2 3000 5000
train_one bal-n2000  32 2 3000 2000

echo
echo "=== 产物核对（每个网络必须同时有 .bin 与 .json，且长度自洽）==="
python3 - <<'PY'
import json, glob, os
for f in sorted(glob.glob("bal-*.json")):
    base = f[:-5]
    try:
        d = json.load(open(f)); n = os.path.getsize(base + ".bin")
        ok = (n == d["bytes"])
        print(f"  {base:<14} {n:>9} 字节  声明 {d['bytes']:>9}  {'✔' if ok else '✘ 不一致'}")
    except Exception as e:
        print(f"  {base:<14} ✘ 读不到：{e}")
PY
echo "均衡重训完成"
