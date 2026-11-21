param([string]$Lane)
# Each lane runs entirely under its own CPython: the host compiler (ast.parse) and the reference are the same interpreter.
Set-Location C:\Users\richa\workspace_local\pyjvm
$env:PYTHONPATH = 'C:\Users\richa\workspace_local\pyjvm'
$env:Path = "$HOME\.local\bin;" + $env:Path
$suffix = if ($Lane -eq '315') { '' } else { "-$Lane" }
$env:PYJVM_BUILD_CACHE = "C:\Users\richa\workspace_local\pyjvm-work\bc-conf$suffix"
$env:PYJVM_CACHE = "C:\Users\richa\workspace_local\pyjvm-work\c-conf$suffix"
$py = "C:\Users\richa\workspace_local\pyjvm\.venv$Lane\Scripts\python.exe"
$log = "C:\Users\richa\workspace_local\pyjvm-work\logs\conf-$Lane-wave1.log"
"start $(Get-Date -Format T) host $py" | Out-File -Encoding utf8 $log
& $py -m pyjvm315.conformance tests/conformance --python $py --selfhosted *>&1 | Out-File -Append -Encoding utf8 $log
"end $(Get-Date -Format T)" | Out-File -Append -Encoding utf8 $log
