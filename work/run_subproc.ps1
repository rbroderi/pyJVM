Set-Location C:\Users\richa\workspace_local\pyjvm
$env:PYTHONPATH = 'C:\Users\richa\workspace_local\pyjvm'
$env:Path = "$HOME\.local\bin;" + $env:Path
$env:PYJVM_BUILD_CACHE = 'C:\Users\richa\workspace_local\pyjvm-work\bc-conf'
$env:PYJVM_CACHE = 'C:\Users\richa\workspace_local\pyjvm-work\c-conf'
$py13 = 'C:\Users\richa\workspace_local\pyjvm\.venv313\Scripts\python.exe'
$py15 = 'C:\Users\richa\workspace_local\pyjvm\.venv315\Scripts\python.exe'
$log = 'C:\Users\richa\workspace_local\pyjvm-work\logs\subproc.log'
Remove-Item -Force -ErrorAction SilentlyContinue $log
foreach ($lane in @(@('3.15', $py15), @('3.13', $py13))) {
    "=== $($lane[0]) subprocess_windows $(Get-Date -Format T)" | Out-File -Append -Encoding utf8 $log
    & $py15 -m pyjvm315.conformance tests/conformance/subprocess_windows.py --python $lane[1] --selfhosted *>&1 | Out-File -Append -Encoding utf8 $log
}
"=== DONE $(Get-Date -Format T)" | Out-File -Append -Encoding utf8 $log
