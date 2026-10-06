#!/usr/bin/env bash
# ============================================================================
# 流水线收尾：等重训流水线跑完 → 提交 + 推送到 GitHub（开源）
#
# 顺序要求（用户明确）：**先发电视，再开源** —— 所以本脚本必须等 auto-pipeline.sh
# 结束（它内部含装机）之后才动作，否则会把"中间状态的权重"推上去。
#
# 安全纪律（记忆里的铁律）：
#   · **严禁 force push**：先 fetch，确认能 fast-forward 才 push
#   · 大文件不入库：KataGo 二进制/官方网络（97MB）、训练产物语料都不进仓库
# ============================================================================
set -uo pipefail
ROOT=/home/david/.hermes/workspace/weiqi-tv
K=$ROOT/tools/katago
APP=$ROOT/src/WeiqiTV
REPORT="$K/OPENSOURCE-REPORT.md"
: > "$REPORT"
log() { echo "$*" | tee -a "$REPORT"; }

log "# 开源报告（$(date '+%F %T')）"
log ""
log "## 0. 等待最终版就绪（第二轮流水线结束时 touch FINAL.ready）"
while [ ! -s "$K/FINAL.ready" ]; do sleep 60; done
log "- 最终版就绪（$(date '+%T')）"
# 双保险：再确认没有流水线在跑（避免读到写了一半的 assets）
while pgrep -f "auto-pipeline.*\.sh" >/dev/null 2>&1; do sleep 60; done
log "- 已确认无流水线在跑（$(date '+%T')）"
if [ -s "$K/PIPELINE-REPORT.md" ]; then
  log "- 流水线报告摘要（最近 25 行）："
  tail -25 "$K/PIPELINE-REPORT.md" | sed 's/^/    /' >> "$REPORT"
fi

# ── 1. 检查是否有大文件混进来 ────────────────────────────────────────────────
log ""
log "## 1. 仓库体积检查（大文件不入库）"
cd "$APP"
big=$(git ls-files -z | xargs -0 du -m 2>/dev/null | sort -rn | head -8)
log '```'
echo "$big" | sed 's/^/  /' >> "$REPORT"
log '```'
for f in $(git ls-files | grep -E '\.(gz|bin)$' | head -20); do
  sz=$(du -m "$f" 2>/dev/null | cut -f1)
  [ "${sz:-0}" -gt 20 ] && log "- ⚠️ 大文件 $f（${sz}MB）—— 建议改用 Release 附件"
done

# ── 2. 提交 ────────────────────────────────────────────────────────────────
log ""
log "## 2. 提交本次全部改动"
git add -A
if git diff --cached --quiet; then
  log "- 没有需要提交的改动"
else
  n=$(git diff --cached --name-only | wc -l)
  git -c user.name="${GIT_AUTHOR_NAME:-heizhu}" -c user.email="${GIT_AUTHOR_EMAIL:-heizhu@example.com}" \
      commit -q -m "重训全部档位：语料改由 KataGo 自战生成、档位梯度改用训练量

- 语料：从「我们自己的引擎自战取局面」改为「KataGo 自战取局面」，并覆盖到
  13 路 300 手 / 9 路 200 手（旧语料 100% 只覆盖前 50 手且含崩局）
- 档位：梯度改用训练步数（200/600/1500/4000/12000），此前容量/数据量/搜索
  三个轴实测都不单调
- 引擎：修掉早终局、交替停手死循环、收工视野、手数兜底等问题
- app：修掉「AI 最长思考时间」取代档位预算导致的档位被抹平 + 13 路卡 84 秒
- 评测台：新增 appmatch（走产品真实终局流程）与 drunkard（不会下棋的对手）两个探针"
  log "- 已提交 $n 个文件（$(date '+%T')）"
fi

# ── 3. 推送（带代理；严禁 force）─────────────────────────────────────────────
log ""
log "## 3. 推送到 GitHub"
export https_proxy=http://172.30.16.1:7897 http_proxy=http://172.30.16.1:7897
if git remote get-url origin >/dev/null 2>&1; then
  log "- 远端：$(git remote get-url origin)"
  if git fetch origin 2>/tmp/fetch.err; then
    br=$(git rev-parse --abbrev-ref HEAD)
    behind=$(git rev-list --count "origin/$br..HEAD" 2>/dev/null || echo 0)
    ahead=$(git rev-list --count "HEAD..origin/$br" 2>/dev/null || echo 0)
    log "- 本地领先 $behind 个提交，落后 $ahead 个提交"
    if [ "$ahead" != "0" ]; then
      log "- ⚠️ 远端有新提交，**不直接推**（严禁 force）。尝试合并："
      if git merge --no-edit "origin/$br" >>"$REPORT" 2>&1; then
        log "  - 合并成功"
      else
        log "  - **合并冲突**，已中止（等你处理）：$(git status --short | head -5 | tr '\n' ' ')"
        git merge --abort 2>/dev/null
      fi
    fi
    if git push origin "$br" 2>/tmp/push.err; then
      log "- ✔ **推送成功**（$(date '+%T')）"
    else
      log "- ✘ 推送失败：$(tail -3 /tmp/push.err | tr '\n' ' ')"
    fi
  else
    log "- ✘ fetch 失败（代理不通？）：$(tail -3 /tmp/fetch.err | tr '\n' ' ')"
  fi
else
  log "- ✘ 没有配置远端"
fi

# ── 4. 开源材料清单 ────────────────────────────────────────────────────────
log ""
log "## 4. 开源材料现状"
for f in LICENSE README.md .gitignore; do
  [ -s "$APP/$f" ] && log "- ✔ $f（$(wc -l < "$APP/$f") 行）" || log "- ✘ 缺 $f"
done
log ""
log "## 完成（$(date '+%F %T')）—— 报告：$REPORT"
