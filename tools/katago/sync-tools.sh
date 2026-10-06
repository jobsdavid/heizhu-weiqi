#!/usr/bin/env bash
# 把「运行副本」的工具链脚本同步到「提交副本」（git 仓库内的 tools/katago）。
#
# 为什么要这一步：本项目的工具链有两份 ——
#   外层 ~/.hermes/workspace/weiqi-tv/tools/katago   ← 跑实验的地方（含 700MB 语料/权重，不入库）
#   内层 ~/.hermes/workspace/weiqi-tv/src/WeiqiTV/tools/katago  ← git 仓库根内的那份（会被提交）
# 两份脚本的 ROOT 都是硬编码绝对路径，所以两边都能运行；但**改错了就会提交旧版本**
# （2026-10-06 真踩过：改完脚本直接 commit，提交的是内层旧快照）。
# 提交前跑一次本脚本即可，它只同步 *.py/*.sh/*.cfg/*.md，绝不带数据与权重。
set -euo pipefail
OUT=/home/david/.hermes/workspace/weiqi-tv/tools/katago
INN=/home/david/.hermes/workspace/weiqi-tv/src/WeiqiTV/tools/katago
[ -d "$OUT" ] && [ -d "$INN" ] || { echo "路径不对"; exit 1; }
n=0
for f in "$OUT"/*; do
  b=$(basename "$f")
  case "$b" in *.py|*.sh|*.cfg|*.md) ;; *) continue ;; esac
  if [ ! -f "$INN/$b" ] || ! cmp -s "$f" "$INN/$b"; then
    cp -p "$f" "$INN/$b"; echo "  同步 $b"; n=$((n+1))
  fi
done
echo "  共同步 $n 个文件"
