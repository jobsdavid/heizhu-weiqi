#!/usr/bin/env bash
# ============================================================
# 13 路（可指定尺寸）网络的**强度 + 黑白对称性**聚合判据。
#
# 一次跑出两件必须的东西（原来分开在两处、且都写死了 9 路）：
#   ① 强度：同一批局面上的平均/中位/最差每手丢目 —— 用来**筛选**候选网络
#      （比 100 局互殴快约 5 倍：一个网络 ~1.5 分钟 vs 一对 ~7.5 分钟）
#   ② 黑白对称性：按「轮到谁」分组比每手丢目，|白 − 黑| ≤ 1.0 目 才算对称。
#      ⚠️ 每换一组权重都必须过这一条 —— 旧网络是 +2~+9 目（白方被训废）。
#
# 用法： bash grade13.sh <net1> [net2 ...]            默认尺寸 13
#        SIZE=9 bash grade13.sh pv9-entry pv9-beginner
# ============================================================
set -u
cd "$(dirname "$0")"
export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$PWD/cuda-libs/nvidia/cudnn/lib
export PYTHONPATH=$PWD/../ml/pylibs
SIZE=${SIZE:-13}

for n in "$@"; do
  WEIQI_NET="$PWD/$n.bin" timeout 1200 python3 grade.py grade --size "$SIZE" --difficulty master \
    --sample-from master --plies 200 --stride 5 --positions 300 --games 14 --visits 200 \
    > "g13-$n.log" 2>&1
  cp -f "grade-${SIZE}-master.json" "g13-$n.json" 2>/dev/null
  printf "  %-18s %s\n" "$n" "$(grep -oE '平均每手丢 [0-9.]+ 目   中位 [0-9.]+   最差 [0-9.]+' "g13-$n.log" | head -1)"
done

python3 - "$SIZE" "$@" <<'PY'
import json, sys, statistics as st
size, nets = sys.argv[1], sys.argv[2:]
print(f"\n{'网络':<18}{'平均丢目':>9}{'黑方该走':>10}{'白方该走':>10}{'黑白差':>9}   判定")
for n in nets:
    try:
        d = json.load(open(f"g13-{n}.json"))
    except Exception:
        print(f"{n:<18}  （没有读数）"); continue
    loss = [e["loss_points"] for e in d]
    b = [e["loss_points"] for e in d if e["ply"] % 2 == 0]
    w = [e["loss_points"] for e in d if e["ply"] % 2 == 1]
    diff = st.mean(w) - st.mean(b)
    v = "✔ 对称" if abs(diff) <= 1.0 else ("△ 仍偏" if abs(diff) <= 2.5 else "✘ 明显不对称")
    print(f"{n:<18}{st.mean(loss):>9.2f}{st.mean(b):>10.2f}{st.mean(w):>10.2f}{diff:>+9.2f}   {v}")
print(f"\n  口径：{size} 路、同一批局面（master 参数、300 局面、KataGo 200 访问量判每手丢目）")
PY
