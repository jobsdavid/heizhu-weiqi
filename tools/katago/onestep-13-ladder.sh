#!/usr/bin/env bash
# ============================================================
# 13 路「高级 ↔ 大师」一次到位：语料够了自动往下走，不再小步试。
#
# 跨度设计（关键）：全链相邻 ≥2.5 倍，最大的一对拉到 5 倍。
#   entry 100 / beginner 960 / intermediate 2400 / advanced 6000 / master 30000
#   倍数：9.6 / 2.5 / 2.5 / 5.0   —— 原来最后两对都只有 2 倍，实测 49% 分不开。
#
# 依据（全部是本项目已有实测，不是推的）：
#   · 相邻两档数据量差 ≥2.5 倍才可分辨：同架构 2k vs 5k → 100 局 13% vs 87%（z=-4.02）；
#     13 路 2000 vs 5000 → 20%（z=-6.00）。
#   · 低档不是「低于某个绝对样本数就无效」：13 路入门档 **16×1 @100 样本 @150 步**，
#     对初级（2000 样本）100 局打 20%（z=-5.99），干净分开；9 路入门档同样 100 样本。
#   · 但只压数据不够：低档 64×4 @500 样本 反而赢 32×2 @2000（56%，z=+1.20）
#     ⇒ 容量必须与数据同向，低档要「容量 + 数据 + 步数」三重弱化。
#   · 容量保持 64×6：通道数再大，13 路在电视上能算的前向次数就太少（物理上限）。
#
# 三条必须守住的：
#   ① **前 20096 条的原有顺序不能动** —— entry/beginner/intermediate/advanced
#      用的是 distill-13-all.jsonl 的**前 N 条**，重混洗会把它们的语料换掉，
#      前三对的验证结论就全部作废。所以只把**新增段**混洗后追加到末尾。
#   ② 每个阶段 fail-closed：产物不存在/为空/闸门不过 → 立刻退出，不当成成功。
#   ③ 用「互殴 100 局 + |z| > 2」判，不看丢目（丢目在弱网小样本上自相矛盾）。
# ============================================================
set -uo pipefail
cd "$(dirname "$0")"
export PYTHONPATH=$PWD/../ml/pylibs

TARGET=30000
BASE=(5005 5009 5100 4982)      # 各 worker 的**原有**行数（新增段的分界，不能猜）

echo "══ 1) 等语料到 $TARGET 条 ══"
while :; do
  n=$(cat distill-13-w*.jsonl | wc -l)
  if [ "$n" -ge "$TARGET" ]; then echo "  语料 $n 条，达标 → 冻结生成"; break; fi
  echo "  当前 $n / $TARGET  $(date +%H:%M)"
  sleep 60
done
pkill -f "distill_gen[.]py" 2>/dev/null; sleep 3
echo "  残留生成进程: $(pgrep -cf 'distill_gen[.]py' || echo 0)"

echo "══ 2) 重建语料集：原有段原样 + 新增段混洗追加 ══"
cp -f distill-13-all.jsonl distill-13-all.prev20096.jsonl
: > /tmp/new-seg.jsonl
for w in 0 1 2 3; do
  tail -n +$(( ${BASE[$w]} + 1 )) "distill-13-w$w.jsonl" >> /tmp/new-seg.jsonl
done
NEW=$(wc -l < /tmp/new-seg.jsonl)
if [ "$NEW" -lt 5000 ]; then echo "  ✘ 新增段只有 $NEW 条，异常 —— 中止"; exit 1; fi
python3 - <<'PY'
import random
lines = [l for l in open("/tmp/new-seg.jsonl") if l.strip()]
random.seed(20261005)
random.shuffle(lines)
open("/tmp/new-seg-shuf.jsonl", "w").write("".join(lines))
print(f"  新增段 {len(lines)} 条已混洗（固定种子，可复现）")
PY
cat distill-13-all.prev20096.jsonl /tmp/new-seg-shuf.jsonl > distill-13-all.jsonl

python3 - <<'PY'
import json, collections
lines = [l for l in open("distill-13-all.jsonl") if l.strip()]
prev = [l for l in open("distill-13-all.prev20096.jsonl") if l.strip()]
ok = True
print(f"  语料集共 {len(lines)} 条")
for name, lim in [("entry",100),("beginner",960),("intermediate",2400),
                  ("advanced",6000),("master",30000)]:
    c = collections.Counter(json.loads(l)["toMove"] for l in lines[:lim])
    n = sum(c.values()); b = c.get("B",0)
    good = n == lim and 0.4 < b/n < 0.6
    ok &= good
    print(f"    {name:<13} 前 {lim:>6} 条  B {b:>6} W {c.get('W',0):>6}  {'✔' if good else '✘ 不平衡'}")
same = lines[:20096] == prev
print(f"  前 20096 条与备份逐行一致: {'✔' if same else '✘ 不一致（前三对的语料被改了！）'}")
ok &= same
raise SystemExit(0 if ok else 1)
PY
if [ $? -ne 0 ]; then echo "  ✘ 语料闸门未过 —— 中止"; exit 1; fi

echo "══ 3) 重训大师：64×6 @ 30000 样本（照 9 路验通的配方）══"
unset LD_LIBRARY_PATH
rm -f pv13-master.bin pv13-master.json pv13-master.pt
python3 -u train_pv.py --data distill-13-all.jsonl --out pv13-master --size 13 \
    --width 64 --blocks 6 --steps 6000 --limit 30000 > train-pv13-master-onestep.log 2>&1
if [ ! -s pv13-master.bin ]; then
  echo "  ✘ 大师没产出权重 —— 中止"; tail -5 train-pv13-master-onestep.log; exit 1
fi
grep -E '^[[:space:]]+step' train-pv13-master-onestep.log | tail -1 | sed 's/^/  /'
python3 - <<'PY'
import json, os
d = json.load(open("pv13-master.json")); n = os.path.getsize("pv13-master.bin")
w = [l for l in d["layers"] if l["name"]=="stem.w"][0]["shape"][0]
b = len([l for l in d["layers"] if l["name"].endswith(".c1.w")])
print(f"  大师 宽{w} 块{b} {n} 字节  声明 {d['bytes']}  {'✔' if n==d['bytes'] else '✘'}")
PY

echo "══ 4) 五档空盘探针 ══"
export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$PWD/cuda-libs/nvidia/cudnn/lib
python3 verify_symmetry.py --size 13 pv13-entry.bin pv13-beginner.bin pv13-intermediate.bin \
    pv13-advanced.bin pv13-master.bin 2>&1 | grep -E "── pv|空盘探针" | sed 's/^/  /'

echo "══ 5) 四对相邻档全验（各 100 局）══"
TAG=sz13m PARALLEL=20 TIMEOUT=7200 GAMES=100 bash verify13_ladder.sh
echo "13 路一次到位流水线完成"
