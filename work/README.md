# work/: plans and helper scripts (snapshot of pyjvm-work)

A copy of the owner's `pyjvm-work` folder, kept in the repo so another machine or session can pick up the N0 work. It is a snapshot: copy later changes back in by hand.

- `*_plan.md`: the plans. `n0_plan.md` is the active one (tickets 0-14); `native_modules_plan.md` is the overall N0-N5 plan.
- `n0/`: N0 ticket scripts, measurements, baseline (`baseline.md`) and the B0 logs.
  - Use these scripts. They take the shared run lock themselves (`run_lock.sh` / `RunLock.ps1`) and read their paths from the environment:
    - WSL: `~/pyjvm-env.sh` must set `JAVA_HOME`, `PATH` and `PYJVM_LOCK` (the Windows-side `pyjvm-work\run.lock` seen from WSL, e.g. `/mnt/e/pyjvm-work/run.lock`); `PYJVM_TREE` picks the clone (default `~/pyjvm`, N0 uses `~/pyjvm-n0`).
    - Windows: `PYJVM_WORK` (the pyjvm-work folder: lock, caches, logs) and `PYJVM_MAIN` (the repo with the `.venv313/314/315` interpreters; also the default `-Tree`).
- Root `run_*.ps1`: **superseded** for N0 work. They hard-code `C:\Users\richa\workspace_local\...`, take no run lock, and `run_subproc.ps1` / `run_wave1.ps1` host the 3.13 lane on 3.15, which the conformance runner now refuses. Kept for history only.
