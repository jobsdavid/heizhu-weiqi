#!/usr/bin/env bash
# 一、参数 A/B：同一个网络配不同档位的搜索参数
#     —— 用来确认「候选池越大越强」这条注释是不是写反了。
#     同一网络 + 同一批局面，唯一变量是搜索参数，配对可算。
# 二、档间互殴：用户真正能感知的量级（胜率），skill §15 指定的验收指标。
set -u
cd /home/david/.hermes/workspace/weiqi-tv/tools/katago
export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$PWD/cuda-libs/nvidia/cudnn/lib
export PYTHONPATH=$PWD/../ml/pylibs

# 等前面的网络排序跑完，避免抢 GPU/CPU 把两边都拖慢
while pgrep -f "netrank.sh" > /dev/null; do sleep 20; done
echo "=== 网络排序已结束，开始参数 A/B ==="

for pair in "pv9-w64b6 intermediate" "pv9-w64b6 advanced" "pv9-w48b4 advanced"; do
  set -- $pair
  NET=$1; TIER=$2
  WEIQI_NET="$PWD/$NET.bin" timeout 900 python3 grade.py grade --size 9 --difficulty "$TIER" \
    --sample-from master --plies 200 --stride 5 --positions 300 --games 14 --visits 200 \
    > "ab-$NET-$TIER.log" 2>&1
  cp -f grade-9-$TIER.json "ab-$NET-$TIER.json" 2>/dev/null
  printf "%-14s + %-13s %s\n" "$NET" "$TIER" \
    "$(grep -oE '平均每手丢 [0-9.]+ 目   中位 [0-9.]+   最差 [0-9.]+' "ab-$NET-$TIER.log" | head -1)"
done

echo
echo "=== 档间互殴（用 app 里真正装配的五份权重）==="
A=/home/david/.hermes/workspace/weiqi-tv/src/WeiqiTV/app/src/main/assets/net
for p in "master entry 10" "advanced intermediate 10"; do
  set -- $p
  TA=$1; TB=$2; G=$3
  timeout 3600 python3 match.py --tier-a "$TA" --tier-b "$TB" --games "$G" --parallel 5 \
    --net-a "$A/pv9-$TA.bin" --net-b "$A/pv9-$TB.bin" \
    > "h2h-$TA-vs-$TB.log" 2>&1
  echo "--- $TA vs $TB ---"
  grep -E "胜率|平均黑方目差|耗时" "h2h-$TA-vs-$TB.log" | sed 's/^/   /'
done
echo "参数 A/B 与档间互殴全部完成"
