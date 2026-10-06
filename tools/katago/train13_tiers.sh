#!/usr/bin/env bash
# ============================================================
# 13 路五档网络重训（数据量档 + 容量档，照 9 路已验通的配方）
#
# 为什么重训：13 路阶梯上一版是坏的 ——
#   · 初级(2000 样本) 与 中级(3000 样本) 只差 1.5 倍 ⇒ 100 局互殴 52%（z=+0.40）分不开
#   · 高级(8000 样本) 与 大师(8000 样本) **用的是同一份数据**，只剩架构差
#     而「容量差拉不开档」是已实测证否的（64×4 与 64×6 互殴分不开）
#   ⇒ 根因是语料只有 8000 条，切 5 档时没有量级空间。现在把语料做到 20000 条后重训：
#
#     档位          架构    样本数   相邻倍数
#     入门 entry     64×4      500       —
#     初级 beginner  32×2     2000      4.0×
#     中级 intermediate 32×2  5000      2.5×   ← 上一版就是这一对只差 1.5 倍，故拉开到 2.5
#     高级 advanced  64×4    10000      2.0×
#     大师 master    64×6    20000      2.0×
#
#   9 路实测：同架构 2k vs 5k 样本，每手丢目 3.17 vs 1.96（差异可测）；
#   最弱档 vs 最强档 100 局互殴 8%（z=-8.28）⇒ 2 倍以上的数据量差是有效的量级杠杆。
#
# ⚠️ 训练前必须**混洗**：`--limit` 取的是文件开头前 N 条，不混洗就会取到
#    单色切片（语料天然是 BWBWBW… 按手数交替）。混洗用固定种子，可复现。
# ============================================================
set -uo pipefail
cd "$(dirname "$0")"
export PYTHONPATH=$PWD/../ml/pylibs
unset LD_LIBRARY_PATH          # 训练时不能带 KataGo 那套 cudnn（踩过）

MERGED=distill-13-merged.jsonl     # 各 worker 原样合并（未混洗）
ALL=distill-13-all.jsonl           # 混洗后（训练只吃这个）
SEED=20261004
OUT_PREFIX=pv13

echo "══ 1) 合并各 worker 语料 ══"
: > "$MERGED"
for f in distill-13-w*.jsonl; do cat "$f" >> "$MERGED"; done
LINES=$(wc -l < "$MERGED")
echo "  合并 $LINES 条 → $MERGED"
[ "$LINES" -lt 20000 ] && { echo "  ✘ 语料不足 20000（现在 $LINES），配方要求 master 20000 —— 中止"; exit 1; }

echo "══ 2) 混洗（固定种子 $SEED，可复现）+ 逐档切片闸门 ══"
python3 - "$MERGED" "$ALL" "$SEED" <<'PY'
import json, random, sys, collections
src, dst, seed = sys.argv[1], sys.argv[2], int(sys.argv[3])
lines = [l for l in open(src, encoding="utf-8") if l.strip()]
random.seed(seed)
random.shuffle(lines)
with open(dst, "w", encoding="utf-8") as fh:
    fh.writelines(lines)
print(f"  混洗完成 {len(lines)} 条 → {dst}")

# 逐档切片必须黑白均衡（这是 9 路踩过的坑：不混洗时子集变成纯单色，
# 网络对另一方完全失效，而训练日志里一切正常）
ok = True
for tier, lim in (("entry",500),("beginner",2000),("intermediate",5000),
                  ("advanced",10000),("master",20000)):
    c = collections.Counter(json.loads(l)["toMove"] for l in lines[:lim])
    n = sum(c.values()); b = c.get("B", 0)
    good = n == lim and 0.4 < b / n < 0.6
    ok &= good
    print(f"    {tier:<13} 前 {lim:>5} 条：B {b:>5} / W {c.get('W',0):>5}  {'✔' if good else '✘ 不均衡'}")
sys.exit(0 if ok else 1)
PY
[ $? -ne 0 ] && { echo "  ✘ 切片均衡闸门未过 —— 中止（别拿单色切片去训）"; exit 1; }

echo "══ 3) 训练五档 ══"
train() {   # 档位 宽 块 步数 样本数
  local tier=$1 w=$2 b=$3 s=$4 lim=$5
  local out="$OUT_PREFIX-$tier"
  rm -f "$out.bin" "$out.json" "$out.pt"
  python3 -u train_pv.py --data "$ALL" --out "$out" --size 13 --width "$w" --blocks "$b" \
      --steps "$s" --limit "$lim" > "train-$out.log" 2>&1
  if [ ! -s "$out.bin" ]; then
    echo "  ✘ $tier 没产出权重 —— 中止（日志 train-$out.log）"
    tail -3 "train-$out.log" | sed 's/^/      /'
    exit 1
  fi
  printf "  %-13s 宽%-3s 块%-2s 步%-5s 样本%-6s %s\n" "$tier" "$w" "$b" "$s" "$lim" \
    "$(grep -E '^\s+step' "train-$out.log" | tail -1 | grep -oE 'val 策略 [0-9.]+ 价值 [0-9.]+  top1命中 [0-9.]+%')"
}
train entry        64 4 1500   500
train beginner     32 2 3000  2000
train intermediate 32 2 4000  5000
train advanced     64 4 5000 10000
train master       64 6 6000 20000

echo "══ 4) 产物核对（字节 vs 声明）══"
python3 - <<'PY'
import json, glob, os
for f in sorted(glob.glob("pv13-*.json")):
    base = f[:-5]
    if not os.path.exists(base + ".bin"): continue
    d = json.load(open(f)); n = os.path.getsize(base + ".bin")
    print(f"  {base:<20} {n:>9} 字节  声明 {d['bytes']:>9}  {'✔' if n == d['bytes'] else '✘'}")
PY

echo "══ 5) 空盘探针（白方第一手不得落一线）══"
export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$PWD/cuda-libs/nvidia/cudnn/lib
# ⚠️ 必须带 --size 13：
#   verify_symmetry.py 默认按 9 路跑，漏掉这个参数它会对 13 路的网络报
#   「棋盘尺寸不符：81 != 169」，而**旧版脚本把它当成功继续往下走** ——
#   闸门不拦就等于没有（本次实测踩到）。这里改成 fail-closed：
PROBE_LOG=probe13-symmetry.log
python3 verify_symmetry.py --size 13 \
    pv13-entry.bin pv13-beginner.bin pv13-intermediate.bin pv13-advanced.bin pv13-master.bin \
    > "$PROBE_LOG" 2>&1
grep -E "── pv|空盘探针" "$PROBE_LOG" | sed 's/^/  /'
N=$(grep -c "空盘探针" "$PROBE_LOG" 2>/dev/null || echo 0)
if [ "$N" -lt 5 ]; then
  echo "  ✘ 空盘探针只跑出 $N 档读数（应 5 档）—— 闸门没生效，中止"
  tail -4 "$PROBE_LOG" | sed 's/^/      /'
  exit 1
fi
if grep -q "第1线" "$PROBE_LOG"; then
  echo "  ✘ 有网络把第一手下在一线（空盘探针失败）"
  exit 1
fi
echo "  空盘探针 5 档全部健康 ✔"

echo "13 路五档重训完成（下一步：四对相邻档各 100 局互殴）"
