param([string]$CaseList, [string]$Lanes = '315,314,313', [string]$Tree = 'C:\Users\richa\workspace_local\pyjvm-work\wt-n0', [string]$Log = 'C:\Users\richa\workspace_local\pyjvm-work\n0\logs\cases.log')
# Each lane runs entirely under its own CPython (host compiler and reference are the same interpreter).
# Takes the shared run lock around each case itself. Caches live under pyjvm-work\n0\.
$Cases = $CaseList -split ','
$main = 'C:\Users\richa\workspace_local\pyjvm'
$lock = 'C:\Users\richa\workspace_local\pyjvm-work\run.lock'
Set-Location $Tree
$env:PYTHONPATH = $Tree
$env:Path = "$HOME\.local\bin;" + $env:Path
New-Item -ItemType Directory -Force (Split-Path $Log) | Out-Null
Remove-Item -Force -ErrorAction SilentlyContinue $Log
foreach ($lane in ($Lanes -split ',')) {
    $env:PYJVM_BUILD_CACHE = "C:\Users\richa\workspace_local\pyjvm-work\n0\bc-$lane"
    $env:PYJVM_CACHE = "C:\Users\richa\workspace_local\pyjvm-work\n0\c-$lane"
    $py = "$main\.venv$lane\Scripts\python.exe"
    foreach ($c in $Cases) {
        while (-not (New-Item -ItemType File $lock -ErrorAction SilentlyContinue)) { Start-Sleep 30 }
        try {
            "run_cases_n0 $lane $c $(Get-Date)" | Set-Content $lock
            "=== $lane $c $(Get-Date -Format T)" | Out-File -Append -Encoding utf8 $Log
            & $py -m pyjvm315.conformance "tests/conformance/$c.py" --python $py --selfhosted *>&1 | Out-File -Append -Encoding utf8 $Log
        } finally { Remove-Item -Force $lock }
    }
}
"=== DONE $(Get-Date -Format T)" | Out-File -Append -Encoding utf8 $Log
