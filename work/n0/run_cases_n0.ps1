param([string]$CaseList, [string]$Lanes = '315,314,313', [string]$Tree = '', [string]$Log = '')
# Each lane runs entirely under its own CPython (host compiler and reference are the same interpreter).
# Takes the shared run lock around each case itself. Caches are per tree and lane under $PYJVM_WORK\n0\.
# PYJVM_WORK: the pyjvm-work folder; PYJVM_MAIN: the repo whose .venv31x interpreters are used (-Tree defaults to it).
$work = if ($env:PYJVM_WORK) { $env:PYJVM_WORK } else { 'C:\Users\richa\workspace_local\pyjvm-work' }
$main = if ($env:PYJVM_MAIN) { $env:PYJVM_MAIN } else { 'C:\Users\richa\workspace_local\pyjvm' }
if (-not $Tree) { $Tree = $main }
if (-not $Log) { $Log = "$work\n0\logs\cases.log" }
. (Join-Path $PSScriptRoot 'RunLock.ps1')
$Cases = $CaseList -split ','
$tag = Split-Path -Leaf $Tree
Set-Location $Tree
$env:PYTHONPATH = $Tree
# An inherited PYTHONDONTWRITEBYTECODE (some agent shells set it) stops the JVM's compile cache from writing.
Remove-Item Env:PYTHONDONTWRITEBYTECODE -ErrorAction SilentlyContinue
$env:Path = "$HOME\.local\bin;" + $env:Path
New-Item -ItemType Directory -Force (Split-Path $Log) | Out-Null
Remove-Item -Force -ErrorAction SilentlyContinue $Log
foreach ($lane in ($Lanes -split ',')) {
    $env:PYJVM_BUILD_CACHE = "$work\n0\bc-$tag-$lane"
    $env:PYJVM_CACHE = "$work\n0\c-$tag-$lane"
    $py = "$main\.venv$lane\Scripts\python.exe"
    foreach ($c in $Cases) {
        $held = Enter-RunLock "run_cases_n0 $tag $lane $c"
        try {
            "=== $lane $c $(Get-Date -Format T)" | Out-File -Append -Encoding utf8 $Log
            & $py -m pyjvm315.conformance "tests/conformance/$c.py" --python $py --selfhosted *>&1 | Out-File -Append -Encoding utf8 $Log
        } finally { Exit-RunLock $held }
    }
}
"=== DONE $(Get-Date -Format T)" | Out-File -Append -Encoding utf8 $Log
