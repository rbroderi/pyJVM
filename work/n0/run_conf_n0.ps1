param([string]$Lane, [string]$Tree = '', [string]$Log = '')
# Full conformance for one lane under its own CPython. Takes the shared run lock itself (whole lane = one hold).
# PYJVM_WORK: the pyjvm-work folder (lock, caches, logs); PYJVM_MAIN: the repo whose .venv31x interpreters are used.
# -Tree defaults to PYJVM_MAIN (on a machine without a separate worktree the main repo is the N0 tree).
$work = if ($env:PYJVM_WORK) { $env:PYJVM_WORK } else { 'C:\Users\richa\workspace_local\pyjvm-work' }
$main = if ($env:PYJVM_MAIN) { $env:PYJVM_MAIN } else { 'C:\Users\richa\workspace_local\pyjvm' }
if (-not $Tree) { $Tree = $main }
. (Join-Path $PSScriptRoot 'RunLock.ps1')
$tag = Split-Path -Leaf $Tree
Set-Location $Tree
$env:PYTHONPATH = $Tree
# An inherited PYTHONDONTWRITEBYTECODE (some agent shells set it) stops the JVM's compile cache from writing, so every
# case recompiles importlib and the stdlib it imports: the lane runs at more than twice its normal time.
Remove-Item Env:PYTHONDONTWRITEBYTECODE -ErrorAction SilentlyContinue
$env:Path = "$HOME\.local\bin;" + $env:Path
$env:PYJVM_BUILD_CACHE = "$work\n0\bc-$tag-$Lane"
$env:PYJVM_CACHE = "$work\n0\c-$tag-$Lane"
$py = "$main\.venv$Lane\Scripts\python.exe"
if (-not $Log) { $Log = "$work\n0\logs\conf-$tag-$Lane.log" }
New-Item -ItemType Directory -Force (Split-Path $Log) | Out-Null
$held = Enter-RunLock "run_conf_n0 $tag $Lane"
try {
    "start $(Get-Date -Format T) host $py tree $Tree" | Out-File -Encoding utf8 $Log
    & $py -m pyjvm315.conformance tests/conformance --python $py --selfhosted *>&1 | Out-File -Append -Encoding utf8 $Log
    "end $(Get-Date -Format T)" | Out-File -Append -Encoding utf8 $Log
} finally { Exit-RunLock $held }
