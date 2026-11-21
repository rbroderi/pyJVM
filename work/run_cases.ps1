param([string]$CaseList, [string]$Lanes = '315,314,313')
# Each lane runs entirely under its own CPython (host compiler and reference are the same interpreter).
$Cases = $CaseList -split ','
Set-Location C:\Users\richa\workspace_local\pyjvm
$env:PYTHONPATH = 'C:\Users\richa\workspace_local\pyjvm'
$env:Path = "$HOME\.local\bin;" + $env:Path
$log = 'C:\Users\richa\workspace_local\pyjvm-work\logs\cases.log'
Remove-Item -Force -ErrorAction SilentlyContinue $log
foreach ($lane in ($Lanes -split ',')) {
    $suffix = if ($lane -eq '315') { '' } else { "-$lane" }
    $env:PYJVM_BUILD_CACHE = "C:\Users\richa\workspace_local\pyjvm-work\bc-conf$suffix"
    $env:PYJVM_CACHE = "C:\Users\richa\workspace_local\pyjvm-work\c-conf$suffix"
    $py = "C:\Users\richa\workspace_local\pyjvm\.venv$lane\Scripts\python.exe"
    foreach ($c in $Cases) {
        "=== $lane $c $(Get-Date -Format T)" | Out-File -Append -Encoding utf8 $log
        & $py -m pyjvm315.conformance "tests/conformance/$c.py" --python $py --selfhosted *>&1 | Out-File -Append -Encoding utf8 $log
    }
}
"=== DONE $(Get-Date -Format T)" | Out-File -Append -Encoding utf8 $log
