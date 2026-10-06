#!/usr/bin/env bash
# 按实测强弱重配五档网络 —— **先模拟测量，不动 app 资源**。
#
# 依据（tools/katago/paired_rank.py 的配对结论）：
#   pv9-hi 4.68 < pv9-w64b4 4.14 < pv9-w16b1 3.69 < pv9-w32b2 3.47 ≲ pv9-w48b4 3.66
#     ≲ scale-n2000 3.17 < pv9-w64b6 2.23 ≈ scale-n5000 1.96
# 其中「相邻可分」的只有 3 处（hi/w64b4、n2000/w64b6、w64b4/hi），中间四个是一团。
#
# 本轮的配法：**只按实测序取每档的网络**（不再掺搜索参数的选择），
# 大师取 scale-n5000 —— 它与 pv9-w64b6 并列最强，但只有 314KB（现在的 1952KB 的 1/6），
# 电视上前向更快。
#
# 跑法：用 --difficulty <档> + WEIQI_NET=<该档的网络> 复现"装配后的那一档"，
# 五档在同一批缓存自战局面上评，最后用 paired_tiers.py 做配对验收。
set -u
cd /home/david/.hermes/workspace/weiqi-tv/tools/katago
export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$PWD/cuda-libs/nvidia/cudnn/lib
export PYTHONPATH=$PWD/../ml/pylibs

declare -A NET=(
  [entry]=pv9-hi
  [beginner]=pv9-w64b4
  [intermediate]=pv9-w16b1
  [advanced]=scale-n2000
  [master]=scale-n5000
)

echo "拟装配："
for d in entry beginner intermediate advanced master; do
  printf "  %-13s ← %-14s (%s KB)\n" "$d" "${NET[$d]}" "$(( $(stat -c%s "${NET[$d]}.bin") / 1024 ))"
done
echo
for d in entry beginner intermediate advanced master; do
  WEIQI_NET="$PWD/${NET[$d]}.bin" timeout 900 python3 grade.py grade --size 9 --difficulty "$d" \
    --sample-from master --plies 200 --stride 5 --positions 300 --games 14 --visits 200 \
    > "prop-$d.log" 2>&1
  cp -f "grade-9-$d.json" "prop-$d.json" 2>/dev/null
  printf "  %-13s %s\n" "$d" "$(grep -oE '平均每手丢 [0-9.]+ 目   中位 [0-9.]+   最差 [0-9.]+' "prop-$d.log" | head -1)"
done
echo
python3 paired_tiers.py prop-entry.json prop-beginner.json prop-intermediate.json prop-advanced.json prop-master.json
echo "拟装配测量完成"
