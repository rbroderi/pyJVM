param([string]$Tree = 'C:\Users\richa\workspace_local\pyjvm-work\wt-n0', [string]$Log = 'C:\Users\richa\workspace_local\pyjvm-work\n0\logs\pytest-methodsize.log')
# methodsize + full pytest in the worktree, under the shared run lock.
$py = 'C:\Users\richa\workspace_local\pyjvm\.venv313\Scripts\python.exe'
$lock = 'C:\Users\richa\workspace_local\pyjvm-work\run.lock'
Set-Location $Tree
$env:PYTHONPATH = $Tree
$env:Path = "$HOME\.local\bin;" + $env:Path
New-Item -ItemType Directory -Force (Split-Path $Log) | Out-Null
while (-not (New-Item -ItemType File $lock -ErrorAction SilentlyContinue)) { Start-Sleep 30 }
try {
    "run_pytest_n0 $(Get-Date)" | Set-Content $lock
    "start $(Get-Date -Format T)" | Out-File -Encoding utf8 $Log
    "--- methodsize" | Out-File -Append -Encoding utf8 $Log
    & $py -m pyjvm315.methodsize --runtime *>&1 | Out-File -Append -Encoding utf8 $Log
    "--- pytest" | Out-File -Append -Encoding utf8 $Log
    & $py -m pytest -q -p no:cacheprovider -rfE *>&1 | Out-File -Append -Encoding utf8 $Log
    "end $(Get-Date -Format T)" | Out-File -Append -Encoding utf8 $Log
} finally { Remove-Item -Force $lock }
