#!/usr/bin/env bash
# ============================================================================
# 第二轮流水线（最终版）：用「初版 + 补充」合并语料重训，再装机，然后放行开源
#
# 为什么要第二轮：
#   初版语料是赶时间生成的（9 路 4099 条 / 13 路 2880 条），比旧语料少一个量级，
#   第一轮主要用来验证「训练量梯度」这套配方是否成立。
#   补充语料（每 1 手取一个局面，密度翻 2-3 倍）到手后，合并两边一起训 ——
#   数据量回到万级、且质量是 KataGo 级的，才是可以交付的最终版。
#
# 收尾：touch FINAL.ready —— 开源脚本在等这个文件，保证推上去的是最终版。
# ============================================================================
set -uo pipefail
cd "$(dirname "$0")"
export PYTHONPATH=$PWD/../ml/pylibs
K=$PWD
A=/home/david/.hermes/workspace/weiqi-tv/src/WeiqiTV/app/src/main/assets/net
APP=/home/david/.hermes/workspace/weiqi-tv/src/WeiqiTV
ADB=~/Android/Sdk/platform-tools/adb
REPORT="$K/PIPELINE2-REPORT.md"
: > "$REPORT"
log() { echo "$*" | tee -a "$REPORT"; }
ed() { export LD_LIBRARY_PATH=/usr/lib/wsl/lib:$K/cuda-libs/nvidia/cudnn/lib; }
ld() { unset LD_LIBRARY_PATH; }   # ⚠️ 训练必须 unset（设了反而 CUDNN 崩）

log "# 第二轮（最终版）报告（$(date '+%F %T')）"

# ── 0. 等补充语料 + 等第一轮结束（避免抢 GPU/CPU 又互相拖慢）────────────────
log ""
log "## 0. 等待"
while pgrep -f "distill_gen.py.*katago2.jsonl" >/dev/null 2>&1; do sleep 60; done
log "- 补充语料生成完毕（$(date '+%T')）"
while pgrep -f "auto-pipeline.sh" >/dev/null 2>&1; do sleep 60; done
log "- 第一轮流水线已结束（$(date '+%T')）"

# 合并语料
for s in 9 13; do
  cat "distill-${s}-katago.jsonl" "distill-${s}-katago2.jsonl" > "distill-${s}-final.jsonl" 2>/dev/null || true
  log "- distill-${s}-final.jsonl：$(wc -l < "distill-${s}-final.jsonl" 2>/dev/null || echo 0) 条"
done

# ── 1. 重训五档（训练量梯度按新数据量重新标定）──────────────────────────────
log ""
log "## 1. 重训五档（语料合并后，步数按数据量同步放大）"
for s in 9 13; do
  data="distill-${s}-final.jsonl"
  n=$(wc -l < "$data" 2>/dev/null || echo 0)
  [ "$n" -gt 100 ] || { log "- ${s} 路：语料不足（$n），跳过"; continue; }
  log "### ${s} 路（语料 $n 条，架构 64×5）"
  # ⚠️ 步数按数据量标定：合并后 9 路约 1.2 万条、13 路约 1.2 万条。
  # 第一轮教训：初版只有 2880~4099 条时，12000 步（≈3 遍）已过拟合（大师 top1 反低于高级）。
  # 所以最高档控制在 ~1.2 遍以内，低档保持"明显欠拟合"以保证梯度跨度。
  for pair in "entry 500" "beginner 1500" "intermediate 4000" "advanced 8000" "master 14000"; do
    t=${pair%% *}; st=${pair##* }
    out="fin${s}-$t"
    rm -f "$out.bin" "$out.json" "$out.pt"
    ( ld; env WEIQI_ABS_VIEW=1 WEIQI_RAW_VALUE=1 WEIQI_AUGMENT=1 \
      python3 -u train_pv.py --data "$data" --out "$out" --size "$s" \
        --width 64 --blocks 5 --steps "$st" --limit 999999 > "$out.log" 2>&1 )
    if [ -s "$out.bin" ]; then
      log "- $t（${st} 步）：$(grep -E '^[[:space:]]+step' "$out.log" | tail -1 | grep -oE 'top1命中 [0-9.]+%')"
    else
      log "- $t（${st} 步）：**失败** $(tail -2 "$out.log" | tr '\n' ' ')"
    fi
  done
done

# ── 2. 验证：相邻档对打（棋力的直接证据）────────────────────────────────────
log ""
log "## 2. 验证：相邻档直接对打（高档应明显 >50%）"
for s in 9 13; do
  for pair in "entry beginner" "beginner intermediate" "intermediate advanced" "advanced master"; do
    a=${pair%% *}; b=${pair##* }
    [ -s "fin${s}-$a.bin" ] && [ -s "fin${s}-$b.bin" ] || continue
    out=$(GAMES=30 MAXPLIES=250 python3 app_baseline.py "$s" master master 30 250 \
      "$K/fin${s}-$a.bin" "$K/fin${s}-$b.bin" 2>/dev/null | grep -E "A 胜|可采信" | tr '\n' ' ')
    log "- ${s}路 $a vs $b：$out"
  done
  # 顶层 vs 底层（看总跨度）
  if [ -s "fin${s}-entry.bin" ] && [ -s "fin${s}-master.bin" ]; then
    out=$(GAMES=30 MAXPLIES=250 python3 app_baseline.py "$s" master master 30 250 \
      "$K/fin${s}-entry.bin" "$K/fin${s}-master.bin" 2>/dev/null | grep -E "A 胜" | tr '\n' ' ')
    log "- ${s}路 **入门 vs 大师（总跨度）**：$out"
  fi
done

log ""
log "## 2b. 醉汉测试（不会下棋的对手能不能赢）"
for s in 9 13; do
  for t in entry beginner intermediate advanced; do
    [ -s "fin${s}-$t.bin" ] || continue
    log "- ${s}路 $t：$(GAMES=10 MAXPLIES=250 python3 app_baseline.py "$s" drunkard "$t" 10 250 \
      "$K/fin${s}-$t.bin" "$K/fin${s}-$t.bin" 2>/dev/null | grep -E 'A 胜|被吃光' | tr '\n' ' ')"
  done
done

# ── 3. 集成 + 编译 + 装机 ───────────────────────────────────────────────────
log ""
log "## 3. 集成与装机"
for s in 9 13; do
  for t in entry beginner intermediate advanced master; do
    [ -s "fin${s}-$t.bin" ] || continue
    cp -f "fin${s}-$t.bin"  "$A/pv${s}-$t.bin"
    cp -f "fin${s}-$t.json" "$A/pv${s}-$t.json"
    log "- ${s}路 $t 已写入 assets"
  done
done
cd "$APP"
if ~/gradle-9.8.0/bin/gradle :app:assembleRelease --console=plain > /tmp/build2.log 2>&1; then
  log "- 编译成功"
  if $ADB install -r app/build/outputs/apk/release/app-release.apk > /tmp/install2.log 2>&1; then
    log "- ✔ **装机成功**（$(date '+%T')）"
  else
    log "- ✘ 装机失败（电视关机/离线？）：$(tail -2 /tmp/install2.log | tr '\n' ' ')"
    log "  ⇒ APK 已在 app/build/outputs/apk/release/app-release.apk，电视可用时手动装"
  fi
else
  log "- ✘ 编译失败：$(grep -E 'error:|e: ' /tmp/build2.log | head -3 | tr '\n' ' ')"
fi

# ── 4. 放行开源 ─────────────────────────────────────────────────────────────
touch "$K/FINAL.ready"
log ""
log "## 完成（$(date '+%F %T')）—— 已放行开源（FINAL.ready）"
log "报告：$REPORT"
