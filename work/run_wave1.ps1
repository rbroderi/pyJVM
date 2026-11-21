$ErrorActionPreference = 'Continue'
Set-Location C:\Users\richa\workspace_local\pyjvm
$env:PYTHONPATH = 'C:\Users\richa\workspace_local\pyjvm'
$env:Path = "$HOME\.local\bin;" + $env:Path
$env:PYJVM_BUILD_CACHE = 'C:\Users\richa\workspace_local\pyjvm-work\bc-conf'
$env:PYJVM_CACHE = 'C:\Users\richa\workspace_local\pyjvm-work\c-conf'
$py13 = 'C:\Users\richa\workspace_local\pyjvm\.venv313\Scripts\python.exe'
$py15 = 'C:\Users\richa\workspace_local\pyjvm\.venv315\Scripts\python.exe'
$log = 'C:\Users\richa\workspace_local\pyjvm-work\logs\wave1-win.log'
Remove-Item -Force -ErrorAction SilentlyContinue $log
$all = 'audit_events','os_putenv','os_fchmod','oserror_errno_subclass','runtime_exc_traceback','recursion_limit_depth','os_pipe','os_platform','ast_get_source_segment'
foreach ($lane in @(@('3.15', $py15), @('3.13', $py13))) {
    # 3.15 already ran the middle five before the hang; rerun only what changed or never ran
    $cases = if ($lane[0] -eq '3.15') { 'audit_events','os_fchmod','os_platform','ast_get_source_segment' } else { $all }
    foreach ($c in $cases) {
        "=== $($lane[0]) $c" | Out-File -Append -Encoding utf8 $log
        & $py15 -m pyjvm315.conformance "tests/conformance/$c.py" --python $lane[1] --selfhosted *>&1 | Out-File -Append -Encoding utf8 $log
    }
}
"=== DONE" | Out-File -Append -Encoding utf8 $log
