#!/usr/bin/env python3
"""Compile once, verify checksums, and time warmed JVM kernels (stdlib only)."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import random
import statistics
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
CASES = ("arithmetic", "range_loop", "calls", "collections")
MODES = ("java", "runtime", "generated")
JVM_FLAGS = ["-Xms256m", "-Xmx256m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2"]


def run(command, **kwargs):
    result = subprocess.run(command, text=True, capture_output=True, timeout=180, **kwargs)
    if result.returncode:
        raise RuntimeError(f"Command failed: {command}\n{result.stdout}\n{result.stderr}")
    return result.stdout


def reference():
    spec = importlib.util.spec_from_file_location("benchmark_kernels", HERE / "kernels.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def parse_output(output, kernel, size):
    checks, samples, reps = {}, [], []
    for line in output.splitlines():
        parts = line.split()
        if parts[0] == "CHECK":
            checks[int(parts[1])] = int(parts[2])
        elif parts[0] == "SAMPLE":
            samples.append(float(parts[1]))
            reps.append(int(parts[2]))
            n = reps[-1]
            want = ((n + 1) // 2 * kernel(size) + n // 2 * kernel(size + 1))
            if int(parts[3]) != want:
                raise ValueError("timed checksum differs from CPython")
    expected = {n: kernel(n) for n in (0, 1, 7, size, size + 1)}
    if checks != expected or not samples or any(x <= 0 for x in samples):
        raise ValueError("missing/incorrect CPython checksums or timings")
    return {"samples_ns_per_invocation": samples, "repetitions": reps,
            "median_ns": statistics.median(samples), "checks": checks}


def summarize(forks):
    medians = [fork["median_ns"] for fork in forks]
    median = statistics.median(medians)
    return {"median_ns": median, "min_fork_ns": min(medians),
            "max_fork_ns": max(medians),
            "relative_mad": statistics.median(abs(x - median) for x in medians) / median,
            "forks": forks}


def compare(current, baseline, threshold):
    """Require a large, non-overlapping slowdown in independent fork medians."""
    for key in ("schema", "suite_sha256", "config", "environment"):
        if current[key] != baseline[key]:
            raise ValueError(f"incompatible benchmark baseline: {key}")
    if set(current["results"]) != set(baseline["results"]):
        raise ValueError("incompatible benchmark cases")
    output = {}
    for case in CASES:
        head = current["results"][case]["generated"]
        base = baseline["results"][case]["generated"]
        ratio = head["median_ns"] / base["median_ns"]
        floor = head["min_fork_ns"] / base["max_fork_ns"]
        output[case] = {"head_over_base": ratio, "conservative_ratio": floor,
                        "regression": ratio > threshold and floor > threshold}
    return output


def markdown(report):
    lines = ["# pyJVM performance tranche 1", "",
             "Ratios > 1 mean slower. Time is ns per whole kernel invocation.", "",
             "| Kernel | Java ns | Runtime Java ns | Generated ns | Generated / Java | Runtime / Java | Generated / Runtime |",
             "|---|---:|---:|---:|---:|---:|---:|"]
    for case, modes in report["results"].items():
        j, r, g = [modes[m]["median_ns"] for m in MODES]
        lines.append(f"| {case} | {j:.0f} | {r:.0f} | {g:.0f} | {g/j:.2f} | {r/j:.2f} | {g/r:.2f} |")
    if "comparison" in report:
        lines += ["", "| Kernel | Head / base generated | Conservative ratio | Regression |",
                  "|---|---:|---:|---|"]
        for case, row in report["comparison"].items():
            lines.append(f"| {case} | {row['head_over_base']:.2f} | {row['conservative_ratio']:.2f} | {row['regression']} |")
    lines += ["", "Runtime Java is a diagnostic control, not a lower bound on unavoidable Python cost.",
              "Raw samples, fork dispersion, checksums, compiler commit, environment, and emitted bytecode are retained.", ""]
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler-root", type=Path, default=HERE.parent)
    parser.add_argument("--output", type=Path, default=HERE.parent / "artifacts/benchmarks")
    parser.add_argument("--forks", type=int, default=3)
    parser.add_argument("--samples", type=int, default=7)
    parser.add_argument("--warmup-ms", type=int, default=1000)
    parser.add_argument("--sample-ms", type=int, default=100)
    parser.add_argument("--size", type=int, default=1000)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--max-regression", type=float, default=1.5)
    parser.add_argument("--fail-on-regression", action="store_true")
    args = parser.parse_args(argv)
    if min(args.forks, args.samples, args.warmup_ms, args.sample_ms) <= 0:
        parser.error("forks, samples and durations must be positive")
    if not 8 <= args.size <= 10000:
        parser.error("size must be in [8, 10000] for bounded integer proof/checksum safety")
    if args.max_regression <= 1:
        parser.error("max-regression must exceed 1")
    if args.fail_on_regression and (not args.baseline or args.forks < 3):
        parser.error("regression gating requires a baseline and at least 3 forks")
    root, output = args.compiler_root.resolve(), args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    config = {key: getattr(args, key) for key in ("forks", "samples", "warmup_ms", "sample_ms", "size")}
    suite_hash = hashlib.sha256(b"".join((HERE / f).read_bytes()
                                      for f in ("kernels.py", "Bench.java", "run.py"))).hexdigest()
    report = {"schema": 1, "suite_sha256": suite_hash, "config": config,
              "environment": {"python": sys.version, "platform": platform.platform(),
                              "machine": platform.machine(), "cpu_count": os.cpu_count(),
                              "cpu_model": next((line.split(":", 1)[1].strip()
                                                 for line in Path("/proc/cpuinfo").read_text().splitlines()
                                                 if line.startswith("model name")), "unknown")
                                           if Path("/proc/cpuinfo").exists() else platform.processor(),
                              "java": subprocess.run(["java", "-version"], text=True, capture_output=True,
                                                     check=True).stderr.strip(),
                              "javac": run(["javac", "-version"]).strip(), "jvm_flags": JVM_FLAGS},
              "compiler_commit": run(["git", "-C", str(root), "rev-parse", "HEAD"]).strip(),
              "compiler_dirty": bool(run(["git", "-C", str(root), "status", "--porcelain"])),
              "results": {c: {m: [] for m in MODES} for c in CASES}}
    kernels = reference()
    with tempfile.TemporaryDirectory(prefix="pyjvm-bench-") as temp:
        classes = Path(temp)
        # A separate Python process guarantees imports use the requested compiler,
        # even if it predates this harness. Same workload/harness for base and head.
        code = ("import sys; from pathlib import Path; "
                "from pyjvm315.compiler import compile_file; "
                "from pyjvm315.cli import build_runtime; "
                "compile_file(sys.argv[1], Path(sys.argv[2]), 'Kernels'); "
                "build_runtime(Path(sys.argv[2]))")
        env = {**os.environ, "PYTHONPATH": str(root)}
        run([sys.executable, "-c", code, str(HERE / "kernels.py"), str(classes)], cwd=root, env=env)
        run(["javac", "-cp", str(classes), "-d", str(classes), str(HERE / "Bench.java")])
        bytecode = run(["javap", "-c", "-p", "-classpath", str(classes), "Kernels"])
        (output / "Kernels.javap.txt").write_text(bytecode)
        report["bytecode_sha256"] = hashlib.sha256((classes / "Kernels.class").read_bytes()).hexdigest()
        jobs = [(fork, c, m) for fork in range(args.forks) for c in CASES for m in MODES]
        random.Random(315).shuffle(jobs) # Avoid always timing one mode first.
        for fork, case, mode in jobs:
            print(f"fork {fork + 1}/{args.forks}: {case}/{mode}", file=sys.stderr, flush=True)
            raw = run(["java", *JVM_FLAGS, "-cp", str(classes), "Bench", mode, case,
                       str(args.size), str(args.warmup_ms), str(args.sample_ms), str(args.samples)])
            measured = parse_output(raw, getattr(kernels, case), args.size)
            if len(measured["samples_ns_per_invocation"]) != args.samples:
                raise ValueError("wrong sample count")
            measured["fork"] = fork
            report["results"][case][mode].append(measured)
    for modes in report["results"].values():
        for mode in MODES:
            modes[mode] = summarize(sorted(modes[mode], key=lambda f: f["fork"]))
        j, r, g = [modes[m]["median_ns"] for m in MODES]
        modes["ratios"] = {"generated_over_java": g/j, "runtime_over_java": r/j,
                           "generated_over_runtime": g/r}
    if args.baseline:
        report["comparison"] = compare(report, json.loads(args.baseline.read_text()), args.max_regression)
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    summary = markdown(report)
    (output / "report.md").write_text(summary)
    print(summary)
    return int(args.fail_on_regression and any(r["regression"] for r in report["comparison"].values()))


if __name__ == "__main__":
    raise SystemExit(main())
