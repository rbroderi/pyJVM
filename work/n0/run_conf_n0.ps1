param([string]$Lane, [string]$Tree = 'C:\Users\richa\workspace_local\pyjvm-work\wt-n0', [string]$Log = '')
# Full conformance for one lane under its own CPython. Takes the shared run lock itself (whole lane = one hold).
$main = 'C:\Users\richa\workspace_local\pyjvm'
$lock = 'C:\Users\richa\workspace_local\pyjvm-work\run.lock'
Set-Location $Tree
$env:PYTHONPATH = $Tree
$env:Path = "$HOME\.local\bin;" + $env:Path
$env:PYJVM_BUILD_CACHE = "C:\Users\richa\workspace_local\pyjvm-work\n0\bc-$Lane"
$env:PYJVM_CACHE = "C:\Users\richa\workspace_local\pyjvm-work\n0\c-$Lane"
$py = "$main\.venv$Lane\Scripts\python.exe"
if (-not $Log) { $Log = "C:\Users\richa\workspace_local\pyjvm-work\n0\logs\conf-$Lane.log" }
New-Item -ItemType Directory -Force (Split-Path $Log) | Out-Null
while (-not (New-Item -ItemType File $lock -ErrorAction SilentlyContinue)) { Start-Sleep 30 }
try {
    "run_conf_n0 $Lane $(Get-Date)" | Set-Content $lock
    "start $(Get-Date -Format T) host $py" | Out-File -Encoding utf8 $Log
    & $py -m pyjvm315.conformance tests/conformance --python $py --selfhosted *>&1 | Out-File -Append -Encoding utf8 $Log
    "end $(Get-Date -Format T)" | Out-File -Append -Encoding utf8 $Log
} finally { Remove-Item -Force $lock }
