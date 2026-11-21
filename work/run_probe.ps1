Set-Location C:\Users\richa\workspace_local\pyjvm
$env:PYTHONPATH = 'C:\Users\richa\workspace_local\pyjvm'
$env:Path = "$HOME\.local\bin;" + $env:Path
$log = 'C:\Users\richa\workspace_local\pyjvm-work\logs\probe-wave1.log'
"start $(Get-Date -Format T)" | Out-File -Encoding utf8 $log
& C:\Users\richa\workspace_local\pyjvm\.venv315\Scripts\python.exe -m pyjvm315.regression_probe --manifest tools/cpython_probe_3000_manifest.txt --cpython-root C:\Users\richa\workspace_local\cpython --baseline tools/cpython_probe_3000_baseline.json --expect-cases 3000 --min-compiles 2918 --fail-on-error *>&1 | Out-File -Append -Encoding utf8 $log
"end $(Get-Date -Format T) exit $LASTEXITCODE" | Out-File -Append -Encoding utf8 $log
