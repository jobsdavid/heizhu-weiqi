#!/usr/bin/env bash
# 13 路 / 19 路的五档网络训练（与 9 路同一套配方：**弱度靠架构**，数据量按各自语料裁）
#
# 为什么要单开一个脚本：9 路的 train_tier_nets.sh 里限死了 distill-9-all.jsonl 与
# pv9- 前缀，13/19 直接复用会写到同一批文件上（那次"量到一半覆盖被测权重"的坑）。
#
# 产出：pv13-{entry,beginner,intermediate,advanced,master}.{bin,json} 及 19 路同理
set -uo pipefail
cd "$(dirname "$0")"

SIZE_LIST=(13 19)
declare -A TIER_ARCH=( [entry]="64 4" [beginner]="32 2" [intermediate]="32 2" [advanced]="64 4" [master]="64 6" )
declare -A TIER_STEPS=( [entry]=500 [beginner]=937 [intermediate]=3000 [advanced]=3000 [master]=3000 )
declare -A TIER_LIMIT=( [entry]=500 [beginner]=2000 [intermediate]=3000 [advanced]=999999 [master]=999999 )
TIER_ORDER=(entry beginner intermediate advanced master)

for SZ in "${SIZE_LIST[@]}"; do
  echo "══════ ${SZ} 路 ══════"
  # 1) 合并各 worker 的语料（训练器只吃单文件）
  ALL="distill-${SZ}-all.jsonl"
  : > "$ALL"
  for f in distill-${SZ}-w*.jsonl; do cat "$f" >> "$ALL"; done
  LINES=$(wc -l < "$ALL")
  echo "  合并语料：$LINES 条 → $ALL"
  # 2) 合并后**再验一次**颜色与标签（防止边界问题）
  python3 - "$ALL" <<'PY'
import json, sys, collections
# ⚠️ 语料里**没有 ply 字段**：手数是 len(moves)（moves = 已落子列表）。
#    第一版写成 r["ply"] → KeyError → 闸门直接崩 → 两路都被跳过（fail-closed 生效，没白训）。
c = collections.Counter(); bad = 0; n = 0
for l in open(sys.argv[1]):
    if not l.strip(): continue
    r = json.loads(l); n += 1
    mv = len(r["moves"]); c[r["toMove"]] += 1
    if r["toMove"] != ("B" if mv % 2 == 0 else "W"): bad += 1
b = c.get("B", 0)
ok = 0.45 < b / max(n, 1) < 0.55 and bad == 0
print(f"  黑白：B {b} / W {c.get('W',0)}  标签不自治 {bad} 条  {'✔ 通过' if ok else '✘ 不通过'}")
sys.exit(0 if ok else 1)
PY
  [ $? -ne 0 ] && { echo "  ✘ ${SZ} 路语料闸门未过，跳过"; continue; }

  # 3) 五档逐个训（限死在本尺寸的数据上）
  for T in "${TIER_ORDER[@]}"; do
    read -r W B <<< "${TIER_ARCH[$T]}"
    LIM=${TIER_LIMIT[$T]}
    [ "$LIM" -gt "$LINES" ] && LIM=$LINES
    python3 -u train_pv.py --data "$ALL" --out "pv${SZ}-${T}" --size "$SZ" \
      --width "$W" --blocks "$B" --steps "${TIER_STEPS[$T]}" --limit "$LIM" \
      > "train-pv${SZ}-${T}.log" 2>&1
    LAST=$(grep -E '^\s+step' "train-pv${SZ}-${T}.log" | tail -1)
    # ⚠️ train_pv.py 的 --size 默认 9，load_jsonl 会**按尺寸过滤**：
    #    不传 --size，13/19 的样本全部被当"尺寸不符"丢掉 → 0 条 → "need at least one array to stack"。
    #    第一版就是这样白训了 10 份（每条命令立刻失败，日志里才有真话）。
    if [ ! -s "pv${SZ}-${T}.bin" ]; then
      echo "  ✘ ${T} 没产出权重 —— 训练失败，本次中止（日志 train-pv${SZ}-${T}.log）"
      tail -3 "train-pv${SZ}-${T}.log" | sed 's/^/      /'
      exit 1
    fi
    printf "  %-13s 宽%-3s 块%-2s 步%-5s 限%-6s %s\n" "$T" "$W" "$B" \
      "${TIER_STEPS[$T]}" "$LIM" "$(echo "$LAST" | grep -oE 'top1命中 [0-9.]+%|val 策略 [0-9.]+')"
  done

  # 4) 空盘探针（白方第一手不许落一线）—— 与 9 路同一判据
  echo "  ── 空盘探针 ──"
  python3 verify_symmetry.py pv${SZ}-entry.bin pv${SZ}-beginner.bin pv${SZ}-intermediate.bin \
      pv${SZ}-advanced.bin pv${SZ}-master.bin 2>&1 | grep -E "── pv|空盘探针" | sed 's/^/    /'
done
echo "13/19 网络训练完成"
