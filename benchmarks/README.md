# First performance tranche

Run from a checkout with Python (the CI compiler host is 3.14) and a JDK 21 or newer with
`java`, `javac`, and `javap` on PATH:

```bash
python benchmarks/run.py
# Short correctness/smoke run, not a performance baseline:
python benchmarks/run.py --forks 1 --samples 2 --warmup-ms 100 --sample-ms 20
```

No dependencies or benchmark-only changes to the compiler/runtime are required.
The nested/local-class compatibility lane remains independent. Python source
is emitted directly to class files by `compile_file`; only the runtime and
hand-written benchmark/control Java are compiled with javac.

## Workloads and bounds

Each invocation uses n or n+1, with default n=1000 and admitted n in [8, 10000].
Bounds below apply to the maximum admitted input 10001. Inputs and loop indices
are integers; no monkey-patching, subclass arithmetic, overflow, negative
indices, or exceptional operations occur in this corpus.

| Kernel | Algorithm | Largest result / intermediate bound | Future optimization opportunity |
|---|---|---|---|
| arithmetic | while loop adding `i * 3 + 7` | result 150085007; term 30007 | primitive locals and integer operations |
| range_loop | iterate range and add indices | result 50005000 | primitive range iteration and accumulator |
| calls | range loop calling `step(i)` | result 150085007 | proven callee, direct call / inlining |
| collections | grow list, indexed sum | result 50015001; elements 1..10001 | builtin list dispatch and proven indexing |

These are **statically bounded candidates** for a future proof tier, not claims
that the current compiler proves or specializes them. Today the bytecode uses
Object locals, boxed integers and runtime helpers. `Kernels.javap.txt` exposes
that lowering on every run. Annotations do not weaken Python semantics.

## Three matched modes

- **java:** the same loop/call algorithm in hand-written Java using primitive
  long arithmetic; collections uses a growing `ArrayList<Long>` with the same
  allocation and access pattern, not an array or pre-sized list.
- **runtime:** hand-written Java using PyRuntime arithmetic, Python range
  iteration, generic function binding/reflection, list method dispatch and
  indexing. Its call target is a hand-written Java step wrapped as a Python
  function, so no generated callee contaminates this control.
- **generated:** directly invoked emitted kernel methods. Only the outer
  harness entry bypasses Python argument binding; calls *inside* the workload
  use the compiler's ordinary Python dispatch. All modes use the same outer
  harness, input boxing boundary, result consumption and validation.

Generated / Java is the total gap; Runtime / Java measures the cost of the
current dynamic representation/protocol implementation; Generated / Runtime
helps isolate lowering/codegen overhead. These ratios are diagnostics, **not an
exact causal decomposition**: JIT decisions differ, and emitted integer
constants currently involve string parsing and frame/line/local bookkeeping,
whereas the Java control uses Long literals and omits compiler instrumentation. Runtime cost includes removable boxing, generic dispatch, reflection,
and allocation. It must not be described as inherently unavoidable Python cost.

For these bounds, arbitrary precision *results* are not required. General Python
still requires correct overflow and dispatch behavior unless a compiler proof
permits removal of guards. This suite provides the specialized Java target and
current-semantics control; it does not turn those proofs into compiler changes.

## Measurement and validation

Compilation, JVM launch, module initialization, initial checks and calibration
are outside the timed region. Each independent process gets at least 1 second
of warmup; batches adapt toward 100ms, then record seven samples. Three forks per
kernel/mode are run in deterministic shuffled order, in fresh JVMs. Reports use
the median of fork medians and retain every sample and fork dispersion.
Fixed heap (256MB), Serial GC and two active processors constrain JVM variation.
GC during work remains included. No empty-loop subtraction is used.

The harness alternates n/n+1, accumulates returned values and publishes to a
volatile sink after timing. Every timed batch checksum and boundary results at
0, 1, 7, n and n+1 must agree with CPython. The bounded size and capped batch
repetitions also keep the aggregate checksum below signed long overflow.
JIT inlining of ordinary Java calls is allowed: that is the target behavior.
These microbenchmarks do not cover startup, compilation, big-int overflow,
exceptions, or general application performance. Default warmup is a practical
starting point, not a guarantee of JIT convergence; inspect dispersion and use
longer warmup/forks before drawing small-difference conclusions.

Reports include nanoseconds per whole invocation, all three ratios, checksums,
configuration, JDK/Python/platform metadata, compiler commit/dirty status, suite
hash and emitted class hash/disassembly. JSON and Markdown land in
`artifacts/benchmarks/`. Do not compare different suite hashes/configurations or
JDK environments. The default is a local measurement, not a portable absolute
baseline.

## Regression workflow

The `benchmarks` workflow runs on PRs and manually. It checks out base and head,
then runs **the head harness and workload against both compilers** on one runner.
The requested compiler is imported in a separate Python process; base need not
have any benchmark files. Runtime source is rebuilt from each compiler revision.
This avoids comparing different algorithms or accidentally benchmarking head
imports twice. Both results and disassembly are uploaded even on failure.

For equivalent local comparison:

```bash
git worktree add /tmp/pyjvm-base main
python benchmarks/run.py --compiler-root /tmp/pyjvm-base --output artifacts/base
python benchmarks/run.py --output artifacts/head --baseline artifacts/base/report.json \
  --max-regression 1.5 --fail-on-regression
```

The gate fails only if generated median time exceeds base by 50% **and the
fastest head fork exceeds the slowest base fork by 50%**, requiring at least
three forks. It uses generated invocation time rather than a noisy division by
fast primitive Java. Raw ratios and smaller changes remain visible in the job
summary. This broad first guard catches major regressions; it does not assert
near-Java performance or statistical significance. Base/head ordering and
shared-runner load can still bias results; reproduce a failure on a quiet host
before changing thresholds. Do not copy a machine-specific ns baseline into CI.

`tests/test_benchmarks.py` checks mismatch detection, incompatible baselines,
regression/noise handling and an all-mode JVM correctness smoke run. Existing
focused conformance remains the independent compatibility gate.
