#!/usr/bin/env bash
# 黑白对称性的**聚合判据**：同一网络、同一批局面，按"轮到谁"分组比每手丢目。
#
# 这是当初发现缺陷的那条判据（旧网络：白方该走比黑方该走多丢 2~9 目）。
# 它比"镜像着法逐点相等"更实在 —— 后者受网络容量与训练分布的残余不对称影响，
# 正常网络也做不到逐点相等，容易变成永远红的假闸门。
#
# 判据：|白方丢目 − 黑方丢目| 要明显小于旧网络（旧网络 2~9 目），目标 ≤ 1.0 目。
set -u
cd "$(dirname "$0")"
export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$PWD/cuda-libs/nvidia/cudnn/lib
export PYTHONPATH=$PWD/../ml/pylibs

NETS="${*:-bal-w64b6 bal-w64b4 bal-w32b2}"
for n in $NETS; do
  WEIQI_NET="$PWD/$n.bin" timeout 900 python3 grade.py grade --size 9 --difficulty master \
    --sample-from master --plies 200 --stride 5 --positions 300 --games 14 --visits 200 \
    > "sym-$n.log" 2>&1
  cp -f grade-9-master.json "sym-$n.json" 2>/dev/null
done
python3 - <<'PY'
import json, statistics as st, glob, os
print(f"{'网络':<16}{'黑方该走':>10}{'白方该走':>10}{'差值':>9}   判定")
for f in sorted(glob.glob("sym-*.json")):
    d = json.load(open(f))
    b = [e["loss_points"] for e in d if e["ply"] % 2 == 0]
    w = [e["loss_points"] for e in d if e["ply"] % 2 == 1]
    diff = st.mean(w) - st.mean(b)
    verdict = "✔ 对称" if abs(diff) <= 1.0 else ("△ 仍偏" if abs(diff) <= 2.5 else "✘ 明显不对称")
    print(f"{f[4:-5]:<16}{st.mean(b):>10.2f}{st.mean(w):>10.2f}{diff:>+9.2f}   {verdict}")
print("\n  参考（旧网络，同一判据）：entry +3.99  intermediate +5.11  pv9-hi +8.47  w64b6 +4.22")
PY
