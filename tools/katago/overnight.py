#!/usr/bin/env python3
"""一夜无人值守管线：等数据 → 多配置训练 → 端侧耗时筛选 → 导出 → 装进 app → 评测 → 报告。

为什么用 Python 写编排（而不是 bash）：配置扫描、指标解析、候选筛选、报告生成都是
带判断的逻辑，bash 里写这些容易出静默错误（上次的 `: >` 截断就是一个）。

关键设计：
  · **等数据但不死等**：达到阈值就开工；超过时限就用手上已有的样本（宁可样本少也别空等一夜）
  · **训练多个配置**，用验证集指标 + 端侧前向耗时**两个条件**筛（光看质量可能选出电视跑不动的网络）
  · 每个阶段都写日志与报告，任何一步失败都不影响已完成的阶段留痕
  · 最后跑 A/B（无网络 / 有网络）拿验收数字，直接对照 baseline

用法：python3 overnight.py --min-samples 20000 --max-wait-min 240
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent                      # .../weiqi-tv
PROJ = ROOT / "src" / "WeiqiTV"
DATA_ALL = HERE / "distill-9-all.jsonl"
APP_ASSETS = PROJ / "app" / "src" / "main" / "assets" / "net"
CORE_RES = PROJ / "core" / "src" / "test" / "resources" / "net"
BENCH = PROJ / "bench" / "build" / "install" / "bench" / "bin" / "bench"
GRADLE = str(Path.home() / "gradle-9.8.0" / "bin" / "gradle")

# 训练配置候选：(宽, 残差块, 步数)。可用 --configs "16,1,80;32,2,3000" 覆盖（冒烟用）
# 有 15 秒预算后，网络可以明显放大：端侧 12ms/次（32 宽）→ 64 宽约 4 倍算力，
# 一次选点 25 次前向也才 1.2 秒，远在预算内。所以扫描里加入更大的配置。
DEFAULT_CONFIGS = [(32, 2, 3000), (48, 4, 4000), (64, 4, 5000), (64, 6, 6000)]
# 端侧预算：PC 上前向 ≤ 25ms。按实测算力比（PC 约比电视快 5.5 倍）→ 电视约 140ms，
# 一次选点 25 次前向 ≈ 3.5 秒，落在 15 秒预算内（留出余量给后续的两层前瞻）。
PC_EVAL_BUDGET_MS = 25.0


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def run(cmd: list[str], env_extra: dict | None = None, **kw) -> subprocess.CompletedProcess:
    """统一注入 PYTHONPATH（torch/numpy 装在独立目录），并可叠加调用方额外的环境变量。

    注意：环境变量必须在这里合并后一次性传给 subprocess —— 调用方再传 env= 会与
    这里的 env= 撞车（TypeError: got multiple values for keyword argument 'env'）。
    """
    env = {**os.environ, "PYTHONPATH": str(ROOT / "tools" / "ml" / "pylibs"), **(env_extra or {})}
    return subprocess.run(cmd, capture_output=True, text=True, env=env, **kw)


def count_samples() -> int:
    n = 0
    for f in HERE.glob("distill-9-w*.jsonl"):
        with f.open(encoding="utf-8") as fh:
            n += sum(1 for _ in fh)
    return n


def wait_for_data(min_samples: int, max_wait_min: int) -> int:
    t0 = time.time()
    while True:
        n = count_samples()
        waited = (time.time() - t0) / 60
        if n >= min_samples:
            log(f"数据够了：{n} 样本（等了 {waited:.0f} 分钟）")
            return n
        if waited >= max_wait_min:
            log(f"等待超时（{max_wait_min} 分钟），用手上 {n} 个样本开工")
            return n
        log(f"等数据中：{n} / {min_samples} 样本（已等 {waited:.0f} 分钟）")
        time.sleep(120)


def merge_data() -> Path:
    with DATA_ALL.open("w", encoding="utf-8") as out:
        for f in sorted(HERE.glob("distill-9-w*.jsonl")):
            out.write(f.read_text(encoding="utf-8"))
    n = sum(1 for _ in DATA_ALL.open(encoding="utf-8"))
    log(f"合并完成：{DATA_ALL.name} 共 {n} 样本")
    return DATA_ALL


def train(width: int, blocks: int, steps: int) -> dict:
    out = HERE / f"pv9-w{width}b{blocks}"
    log(f"训练 宽{width} 块{blocks} 步{steps} → {out.name}")
    r = run([sys.executable, "-u", "train_pv.py", "--data", DATA_ALL.name,
             "--width", str(width), "--blocks", str(blocks), "--steps", str(steps),
             "--out", out.name], cwd=str(HERE))
    logfile = HERE / f"{out.name}.log"
    logfile.write_text(r.stdout + "\n" + r.stderr)
    # 取最后一次验证指标
    val_loss = top1 = None
    for line in r.stdout.splitlines():
        m = re.search(r"val 策略 ([0-9.]+) 价值 ([0-9.]+)\s+top1命中 ([0-9.]+)%", line)
        if m:
            val_loss, top1 = float(m.group(1)), float(m.group(3))
    k = None
    m = re.search(r"斜率 K = ([0-9.]+)", r.stdout)
    if m:
        k = float(m.group(1))
    ok = (out.with_suffix(".bin")).exists()
    log(f"  完成={ok}  val 策略={val_loss}  top1={top1}%  K={k}")
    return {"width": width, "blocks": blocks, "steps": steps, "out": str(out),
            "val_policy": val_loss, "top1": top1, "k": k, "ok": ok}


def net_bench(bin_path: Path) -> float | None:
    """PC 上量前向耗时。"""
    r = run([str(BENCH)], input="netbench 9 40\nquit\n",
            env_extra={"WEIQI_NET": str(bin_path)})
    m = re.search(r"前向 ([0-9.]+) ms/次", r.stdout)
    if not m:
        log(f"  netbench 失败：{r.stdout.strip()[:120]} {r.stderr.strip()[:120]}")
        return None
    return float(m.group(1))


def deploy(bin_path: Path) -> None:
    APP_ASSETS.mkdir(parents=True, exist_ok=True)
    shutil.copy(bin_path, APP_ASSETS / "pv9.bin")
    shutil.copy(bin_path.with_suffix(".json"), APP_ASSETS / "pv9.json")
    # ⚠️ **不要把训练好的网络复制进 core 的测试资源**。
    # 那里的 pv9_weights.bin 与 pv9_expected.json 是一对**自洽**的夹具
    # （由 make_fixture.py 同时生成，用来验证"端侧前向与 PyTorch 逐值一致"）。
    # 只换权重不换期望 → 对齐测试失败，而且失败现象是"策略偏差 0.2"，
    # 看起来像端侧前向实现坏了 —— 会把排查方向整个带偏（实测踩过一次，
    # 白查了一轮才反应过来是部署脚本干的）。
    # 夹具只需要验证前向实现的一致性，用哪个网络都不影响这个目的。
    log(f"已部署：app/assets/net/pv9.bin（{bin_path.stat().st_size/1024:.0f} KB）"
        f"（core 测试夹具有意不动，见 deploy 注释）")


def grade(with_net: Path | None, positions: int, difficulty: str) -> dict:
    label = "有网络" if with_net else "无网络"
    log(f"评测：{label}（{positions} 个局面，{difficulty}）")
    env = {**os.environ, "PYTHONPATH": str(ROOT / "tools" / "ml" / "pylibs"),
           "LD_LIBRARY_PATH": f"/usr/lib/wsl/lib:{HERE}/cuda-libs/nvidia/cudnn/lib"}
    if with_net:
        env["WEIQI_NET"] = str(with_net)
    r = subprocess.run(
        [sys.executable, "grade.py", "grade", "--size", "9", "--difficulty", difficulty,
         "--sample-from", "master", "--plies", "60", "--stride", "5",
         "--positions", str(positions), "--visits", "400"],
        capture_output=True, text=True, cwd=str(HERE), env=env, timeout=3600)
    out = r.stdout
    (HERE / f"grade-{label}.log").write_text(out)
    res = {}
    m = re.search(r"平均每手丢 ([0-9.]+) 目\s+中位 ([0-9.]+)\s+最差 ([0-9.]+)", out)
    if m:
        res = {"mean": float(m.group(1)), "median": float(m.group(2)), "worst": float(m.group(3))}
    m = re.search(r"开局\s+\d+ 手\s+平均丢\s+([0-9.]+) 目", out)
    if m: res["opening"] = float(m.group(1))
    m = re.search(r"中盘\s+\d+ 手\s+平均丢\s+([0-9.]+) 目", out)
    if m: res["midgame"] = float(m.group(1))
    log(f"  {label}: {res}")
    return res


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--min-samples", type=int, default=20000)
    ap.add_argument("--max-wait-min", type=int, default=240)
    ap.add_argument("--positions", type=int, default=8)
    ap.add_argument("--difficulty", default="master")
    ap.add_argument("--skip-deploy", action="store_true")
    ap.add_argument("--configs", default=None,
                    help='覆盖训练配置，形如 "16,1,80;32,2,3000"（冒烟或调参用）')
    args = ap.parse_args()

    configs = DEFAULT_CONFIGS
    if args.configs:
        configs = [tuple(int(x) for x in c.split(",")) for c in args.configs.split(";")]

    report: list[str] = ["# 一夜管线报告", ""]
    report.append(f"开始时间：{time.strftime('%Y-%m-%d %H:%M:%S')}")

    n = wait_for_data(args.min_samples, args.max_wait_min)
    report.append(f"\n## 数据\n样本数 {n}")
    merge_data()
    report.append(f"合并后 {sum(1 for _ in DATA_ALL.open(encoding='utf-8'))} 条")

    # ---- 训练多配置 ----
    report.append("\n## 训练配置扫描\n")
    report.append("| 宽 | 块 | 步 | val 策略 | top1命中 | PC前向ms | 入选 |")
    report.append("|---|---|---|---|---|---|---|")
    trained = []
    for w, b, s in configs:
        info = train(w, b, s)
        if not info["ok"]:
            report.append(f"| {w} | {b} | {s} | 失败 | | | ✘ |")
            continue
        ms = net_bench(Path(info["out"] + ".bin"))
        info["eval_ms"] = ms
        trained.append(info)
        fits = ms is not None and ms <= PC_EVAL_BUDGET_MS
        report.append(f"| {w} | {b} | {s} | {info['val_policy']} | {info['top1']}% | "
                      f"{ms:.2f} | {'✔' if fits else '✘ 太慢'} |")
        log(f"  端侧前向 {ms:.2f} ms/次 → {'可用' if fits else '超出端侧预算'}")

    fit = [t for t in trained if t.get("eval_ms") is not None
           and t["eval_ms"] <= PC_EVAL_BUDGET_MS and t["val_policy"] is not None]
    if not fit:
        report.append("\n**没有既合格又够快的配置，流程中止**")
        (HERE / "REPORT.md").write_text("\n".join(report), encoding="utf-8")
        print("\n".join(report))
        return 1
    best = min(fit, key=lambda t: t["val_policy"])
    log(f"选定配置：宽{best['width']} 块{best['blocks']}（val 策略 {best['val_policy']}，"
        f"前向 {best['eval_ms']:.2f} ms）")
    report.append(f"\n**选定**：宽 {best['width']} / 块 {best['blocks']}，"
                  f"val 策略 {best['val_policy']}，PC 前向 {best['eval_ms']:.2f} ms")

    best_bin = Path(best["out"] + ".bin")
    if not args.skip_deploy:
        deploy(best_bin)

    # ---- A/B 评测 ----
    report.append("\n## A/B 验收（同一批局面，唯一变量是网络）\n")
    a = grade(None, args.positions, args.difficulty)
    b = grade(best_bin, args.positions, args.difficulty)
    report.append("| 路径 | 平均丢目 | 中位 | 最差 | 开局 | 中盘 |")
    report.append("|---|---|---|---|---|---|")
    for label, r in (("无网络（原路径）", a), ("有网络（蒸馏）", b)):
        report.append(f"| {label} | {r.get('mean')} | {r.get('median')} | {r.get('worst')} | "
                      f"{r.get('opening')} | {r.get('midgame')} |")
    if a.get("mean") is not None and b.get("mean") is not None:
        report.append(f"\n平均丢目：{a['mean']} → {b['mean']} 目（**变化 {b['mean']-a['mean']:+.2f} 目**）")
        report.append(f"baseline 参照：原大师档 6.17 目 / 中盘 8.83 目")
    report.append(f"\n结束时间：{time.strftime('%Y-%m-%d %H:%M:%S')}")

    text = "\n".join(report)
    (HERE / "REPORT.md").write_text(text, encoding="utf-8")
    print("\n" + "=" * 60)
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
