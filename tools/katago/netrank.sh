#!/usr/bin/env bash
# 网络单独排序：**固定档位参数**（统一用 master 的搜索设置），只换网络，
# 在同一批缓存自战局面上逐手评估。这样出来的差异只归因于网络本身。
#
# 为什么必须固定参数：五档的差异 = 网络强弱 + 参数强弱，混在一起量出来的
# 是"档位差"，无法判断该动哪个旋钮。要修阶梯，得先知道网络自己的强弱序。
#
# 判据不用均值比大小（噪声 1 目级），而是把每档明细存下来做**配对检验**
# （同一局面上的逐手差），见 paired_rank.py。
set -u
cd /home/david/.hermes/workspace/weiqi-tv/tools/katago
export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$PWD/cuda-libs/nvidia/cudnn/lib
export PYTHONPATH=$PWD/../ml/pylibs

NETS="pv9-w64b6 pv9-w32b2 pv9-w48b4 pv9-w64b4 scale-n2000 scale-n5000 pv9-w16b1 pv9-hi"
for n in $NETS; do
  WEIQI_NET="$PWD/$n.bin" timeout 900 python3 grade.py grade --size 9 --difficulty master \
    --sample-from master --plies 200 --stride 5 --positions 300 --games 14 --visits 200 \
    > "netrank-$n.log" 2>&1
  cp -f grade-9-master.json "netrank-$n.json" 2>/dev/null
  printf "%-14s %s\n" "$n" "$(grep -oE '平均每手丢 [0-9.]+ 目   中位 [0-9.]+   最差 [0-9.]+' "netrank-$n.log" | head -1)"
done
echo "网络排序完成"
