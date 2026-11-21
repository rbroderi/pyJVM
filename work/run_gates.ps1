# Full Windows gates, one after another (memory): methodsize, pytest, conformance 3.15/3.14/3.13 (each under its own
# CPython), probe, strict (fresh units).
$ErrorActionPreference = 'Continue'
$work = 'C:\Users\richa\workspace_local\pyjvm-work'
$py13 = 'C:\Users\richa\workspace_local\pyjvm\.venv313\Scripts\python.exe'
$status = "$work\logs\gates-status.log"
"start $(Get-Date -Format T)" | Out-File -Encoding utf8 $status
Set-Location C:\Users\richa\workspace_local\pyjvm
$env:PYTHONPATH = 'C:\Users\richa\workspace_local\pyjvm'
$env:Path = "$HOME\.local\bin;" + $env:Path
& $py13 -m pyjvm315.methodsize --runtime *>&1 | Out-File -Encoding utf8 "$work\logs\methodsize.log"
"methodsize done $(Get-Date -Format T) exit $LASTEXITCODE" | Out-File -Append -Encoding utf8 $status
powershell -NoProfile -File "$work\run_pytest.ps1"
"pytest done $(Get-Date -Format T)" | Out-File -Append -Encoding utf8 $status
powershell -NoProfile -File "$work\run_conf.ps1" -Lane 315
"conf315 done $(Get-Date -Format T)" | Out-File -Append -Encoding utf8 $status
powershell -NoProfile -File "$work\run_conf.ps1" -Lane 314
"conf314 done $(Get-Date -Format T)" | Out-File -Append -Encoding utf8 $status
powershell -NoProfile -File "$work\run_conf.ps1" -Lane 313
"conf313 done $(Get-Date -Format T)" | Out-File -Append -Encoding utf8 $status
powershell -NoProfile -File "$work\run_probe.ps1"
"probe done $(Get-Date -Format T)" | Out-File -Append -Encoding utf8 $status
# strict: a fresh unit directory so every piece reruns on the new code
$strictOld = "$work\strict-units"
if (Test-Path $strictOld) { Rename-Item $strictOld "strict-units-8657810" -ErrorAction SilentlyContinue }
Remove-Item -Force -ErrorAction SilentlyContinue "$work\logs\strict-wave1.log"
powershell -NoProfile -File "$work\run_strict.ps1"
"strict done $(Get-Date -Format T)" | Out-File -Append -Encoding utf8 $status
"=== DONE $(Get-Date -Format T)" | Out-File -Append -Encoding utf8 $status
