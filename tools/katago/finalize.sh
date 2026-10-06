#!/usr/bin/env bash
# ============================================================
# finalize.sh —— 验收达标后的收尾：构建 → 装机 → 真机验证 → 交付 → 提交推送
#
# ⚠️ 只在验收通过后才跑。判据（见 REPORT.md / TIERS.md）：
#     · 网络路径的平均丢目显著低于原路径（同一批局面）
#     · 五档单调有序
#     · 大师档最差单手有界（不出现 20 目级随机大漏着）
#   不达标就**不动现有版本** —— 宁可保持"能用的旧版"，也不要"看起来新但更差"的版本。
#
# 用法：./finalize.sh
# ============================================================
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
PROJ="$ROOT/src/WeiqiTV"
ADB=$HOME/Android/Sdk/platform-tools/adb
SERIAL=192.168.1.8:5555
KEY="$PROJ/app/src/main/assets/net/pv9.bin"

echo "=== 0. 前置检查 ==="
if [ ! -f "$KEY" ]; then echo "  ✘ assets 里没有网络权重（$KEY）—— 先跑 overnight.py"; exit 1; fi
echo "  ✔ 网络权重 $(stat -c%s "$KEY") 字节"

echo "=== 1. 构建 ==="
cd "$PROJ"
~/gradle-9.8.0/bin/gradle :app:assembleRelease -q 2>&1 | grep -E "^e: " || true
APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || { echo "  ✘ 构建失败"; exit 1; }
echo "  ✔ APK $(stat -c%s "$APK") 字节"

echo "=== 2. 装机 ==="
$ADB -s $SERIAL install -r "$APK" 2>&1 | tail -1

echo "=== 3. 真机验证（大师档：网络是否加载 + 思考耗时） ==="
$ADB -s $SERIAL logcat -c 2>/dev/null
$ADB -s $SERIAL shell am force-stop com.heizhu.weiqi
$ADB -s $SERIAL shell am start -n com.heizhu.weiqi/.MainActivity >/dev/null 2>&1
sleep 3
drv() { bash "$ROOT/scripts/tv-drive.sh" 192.168.1.8 "$@" >/dev/null 2>&1; }
drv raw OK; sleep 2; drv raw OK; sleep 3      # 新对局 → 开始对局（难度沿用上次=大师）
drv raw OK; sleep 2                            # 落一子
echo "  等待 AI（大师档，预算 15 秒）…"
for i in $(seq 1 6); do
  sleep 4
  line=$($ADB -s $SERIAL logcat -d -s WeiqiBench 2>/dev/null | tail -1)
  [ -n "$line" ] && break
done
echo "  网络加载： $($ADB -s $SERIAL logcat -d -s WeiqiNet 2>/dev/null | tail -1)"
echo "  搜索统计： $line"
echo "  （网络路径下 candidates 应等于该档 netTopK，且 elapsedMs 远小于预算）"

echo "=== 4. 交付到桌面 ==="
cp "$APK" /tmp/heizhu-weiqi.apk
cp /tmp/heizhu-weiqi.apk "/mnt/c/Users/david/Desktop/黑猪围棋.apk"
A=$(md5sum "$APK" | cut -c1-32); B=$(md5sum "/mnt/c/Users/david/Desktop/黑猪围棋.apk" | cut -c1-32)
echo "  构建 md5 $A / 桌面 md5 $B"
[ "$A" = "$B" ] && echo "  ✔ 一致" || echo "  ✘ 不一致"

echo "=== 5. 提交推送 ==="
cd "$PROJ"
git add -A
git -c user.name="黑猪勇士" -c user.email="heizhu@local" commit -q -F - <<'MSG'
棋力：接入 KataGo 蒸馏的 9 路小网络（网络引导选点）

背景与判据
- 用户靶心：「每个难度更准确，而不是像傻瓜」。
- 实测原路径的问题：随机 rollout 评估噪声过大 —— 同一档同一局面换种子，丢目能从 0 摆到 21 目；
  而把模拟次数从 300 加到 8000，丢目差 t=1.24（噪声内）。**瓶颈是评估函数，不是搜索量。**
- 做法：把 KataGo 蒸馏成小网络（策略 + 价值），端侧纯 Kotlin 前向（不引推理框架）。
  选点 = 网络策略剪枝根候选 + 每个候选用网络价值做一手前瞻。
- 验收：同一批局面下 A/B 对照（唯一变量是网络），指标用目差（小棋盘胜率会饱和）。

关键工程点
- 前向逐值对齐 PyTorch（策略差 1e-7、目差相同）：手写卷积布局错位不会报错，
  只会静默输出垃圾；全错时靠"把 Kotlin 逐行照搬成 Python 跑同一份权重"定位到漏写的 relu。
- 导出把 BatchNorm 折进卷积，端侧只需 conv+relu；权重 + manifest 放 assets。
- 网络缺失/损坏自动回退原路径，不做启动路径上的硬依赖。
- 大师档思考预算 6s → 15s：对随机 rollout 路径是白等，对网络路径才真正变成棋力。
- 难度新增 netTopK（3/6/10/16/24）作为网络路径下的档位旋钮。
MSG
echo "  提交: $?"
for i in 1 2 3; do git push -q origin master 2>/dev/null && { echo "  推送成功"; break; } || { echo "  第 $i 次被掐，重试"; sleep 5; }; done
echo "  远端: $(gh api repos/jobsdavid/heizhu-weiqi/git/refs/heads/master -q '.object.sha' 2>/dev/null | cut -c1-12)"
echo "  本地: $(git rev-parse HEAD | cut -c1-12)"
