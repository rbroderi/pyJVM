param([switch]$DryRun)
Set-Location C:\Users\richa\workspace_local\pyjvm
$env:PYTHONPATH = 'C:\Users\richa\workspace_local\pyjvm'
$env:Path = "$HOME\.local\bin;" + $env:Path
# reuse the compiled-class cache of the earlier (stopped) runs
$env:PYJVM_BUILD_CACHE = 'C:\Users\richa\workspace_local\pyjvm-work\strict'
$py = 'C:\Users\richa\workspace_local\pyjvm\.venv315\Scripts\python.exe'
$out = 'C:\Users\richa\workspace_local\pyjvm-work\strict-units'
$log = 'C:\Users\richa\workspace_local\pyjvm-work\logs\strict-wave1.log'
$extra = @(); if ($DryRun) { $extra = @('--dry-run') }
"start $(Get-Date -Format T)" | Out-File -Append -Encoding utf8 $log
& $py tools/resumable.py strict --out $out --jobs 1 --python $py --cpython-root C:\Users\richa\workspace_local\cpython @extra *>&1 | Out-File -Append -Encoding utf8 $log
"end $(Get-Date -Format T) exit $LASTEXITCODE" | Out-File -Append -Encoding utf8 $log
