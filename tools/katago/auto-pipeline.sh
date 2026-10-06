#!/usr/bin/env bash
# ============================================================================
# 黑猪围棋 · 端到端全自动流水线（2026-10-05 夜）
#
# 背景与结论（为什么要这样做）：
#   ① 语料质量是本项目的真正瓶颈：旧语料用**我们自己的引擎**自战取局面，
#      而它后半盘会崩 ⇒ 取到的局面就是崩局 ⇒ "垃圾进垃圾出"。
#      实测：13 路旧语料 100% 落在前 50 手，官子段网络从没见过。
#      修法：改用 **KataGo 自己下**取局面（--proposer katago），并覆盖到 300 手。
#   ② 档位梯度此前一直做不出来（容量/数据量/搜索三个轴实测都不单调）。
#      修法：用**训练量**做梯度 —— 同架构、同数据，只差训练步数；
#      实测 top1 命中率随步数单调上升（37.0% → 45.3% → …）。
#
# 本脚本按顺序做完：等语料 → 训 9路/13路 各五档 → 验证 → 集成 → 编译 → 装机 → 写报告
# 每一步的失败都记录进报告，不静默跳过（失败也要留下证据）。
# ============================================================================
set -uo pipefail
cd "$(dirname "$0")"
export PYTHONPATH=$PWD/../ml/pylibs
K=$PWD
A=/home/david/.hermes/workspace/weiqi-tv/src/WeiqiTV/app/src/main/assets/net
APP=/home/david/.hermes/workspace/weiqi-tv/src/WeiqiTV
ADB=~/Android/Sdk/platform-tools/adb
REPORT="$K/PIPELINE-REPORT.md"
: > "$REPORT"

log() { echo "$*" | tee -a "$REPORT"; }
hr()  { echo "" | tee -a "$REPORT"; }

ed() { export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$K/cuda-libs/nvidia/cudnn/lib; }
ld() { unset LD_LIBRARY_PATH; }        # ⚠️ PyTorch 训练必须 unset（今天踩过：设了反而崩）

log "# 全自动流水线报告（$(date '+%F %T')）"
hr

# ── 0. 等语料生成完 ────────────────────────────────────────────────────────
log "## 0. 等待高质量语料（KataGo 自战取局面）"
for f in distill-9-katago.jsonl distill-13-katago.jsonl; do
  while pgrep -f "distill_gen.py.*$f" >/dev/null 2>&1; do sleep 60; done
  n=$(wc -l < "$f" 2>/dev/null || echo 0)
  log "- $f：$n 条"
done
hr

# ── 1. 训练：用「训练量梯度」做档位（同架构、同数据，只差步数）────────────────
# 步数按等比放大，覆盖"欠拟合 → 充分"的区间；实测 top1 随步数单调上升。
train_size() {   # 尺寸
  local size=$1
  local data="distill-${size}-katago.jsonl"
  local w=64 b=5
  [ "$size" = "13" ] && w=64 && b=5
  log "### ${size} 路五档（架构 ${w}×${b}，语料 $data）"
  for pair in "entry 200" "beginner 600" "intermediate 1500" "advanced 4000" "master 12000"; do
    local t=${pair%% *} s=${pair##* }
    local out="kt${size}-$t"
    rm -f "$out.bin" "$out.json" "$out.pt"
    ( ld; env WEIQI_ABS_VIEW=1 WEIQI_RAW_VALUE=1 WEIQI_AUGMENT=1 \
      python3 -u train_pv.py --data "$data" --out "$out" --size "$size" \
        --width "$w" --blocks "$b" --steps "$s" --limit 999999 > "$out.log" 2>&1 )
    if [ -s "$out.bin" ]; then
      log "- $t（${s} 步）：$(grep -E '^[[:space:]]+step' "$out.log" | tail -1 | grep -oE 'top1命中 [0-9.]+%')"
    else
      log "- $t（${s} 步）：**训练失败** —— $(tail -2 "$out.log" | tr '\n' ' ')"
    fi
  done
  hr
}
log "## 1. 重训五档（训练量梯度）"
train_size 9
train_size 13

# ── 2. 验证：对 KataGo 丢目（分段）+ 醉汉测试 ───────────────────────────────
log "## 2. 验证"
ed
for size in 9 13; do
  log "### ${size} 路：逐档对 KataGo 丢目（越小越强）"
  for t in entry beginner intermediate advanced master; do
    [ -s "kt${size}-$t.bin" ] || { log "- $t：无网络，跳过"; continue; }
    WEIQI_NET="$K/kt${size}-$t.bin" python3 grade.py grade --size "$size" \
      --difficulty master --sample-from master --plies 200 --stride 5 \
      --positions 100 --games 6 --visits 200 > "/tmp/v-${size}-$t.log" 2>&1
    log "- $t：$(grep -E '平均每手丢' "/tmp/v-${size}-$t.log" | head -1 | tr -s ' ')"
    grep -E "^ +(开局|中盘|官子)" "/tmp/v-${size}-$t.log" | sed 's/^/    /' >> "$REPORT"
  done
  hr
done

log "### 醉汉测试（不会下棋的对手能否赢）"
for size in 9 13; do
  for t in entry beginner intermediate advanced; do
    [ -s "kt${size}-$t.bin" ] || continue
    log "- ${size}路 $t：$(GAMES=12 MAXPLIES=250 python3 app_baseline.py "$size" drunkard "$t" 12 250 \
      "$K/kt${size}-$t.bin" "$K/kt${size}-$t.bin" 2>/dev/null | grep -E 'A 胜|被吃光|未终局' | tr '\n' ' ' | tr -s ' ')"
  done
done
hr

# ── 3. 集成 + 编译 + 装机 ───────────────────────────────────────────────────
log "## 3. 集成与装机"
for size in 9 13; do
  for t in entry beginner intermediate advanced master; do
    [ -s "kt${size}-$t.bin" ] || continue
    cp -f "kt${size}-$t.bin" "$A/pv${size}-$t.bin"
    cp -f "kt${size}-$t.json" "$A/pv${size}-$t.json"
  done
  log "- ${size} 路五档已写入 assets"
done
cd "$APP"
if ~/gradle-9.8.0/bin/gradle :app:assembleRelease --console=plain > /tmp/build.log 2>&1; then
  log "- 编译成功：$(ls -la app/build/outputs/apk/release/app-release.apk | awk '{print $5\" 字节\"}')"
  if $ADB install -r app/build/outputs/apk/release/app-release.apk > /tmp/install.log 2>&1; then
    log "- 装机成功（$(date '+%T')）"
  else
    log "- **装机失败**（电视可能已关/离线）：$(tail -2 /tmp/install.log | tr '\n' ' ')"
  fi
else
  log "- **编译失败**：$(grep -E 'error:|e: ' /tmp/build.log | head -3 | tr '\n' ' ')"
fi

hr
log "## 完成（$(date '+%F %T')）"
log "报告文件：$REPORT"
