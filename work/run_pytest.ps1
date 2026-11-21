Set-Location C:\Users\richa\workspace_local\pyjvm
$env:PYTHONPATH = 'C:\Users\richa\workspace_local\pyjvm'
$env:Path = "$HOME\.local\bin;" + $env:Path
$log = 'C:\Users\richa\workspace_local\pyjvm-work\logs\pytest-full.log'
"start $(Get-Date -Format T)" | Out-File -Encoding utf8 $log
& C:\Users\richa\workspace_local\pyjvm\.venv313\Scripts\python.exe -m pytest -q -p no:cacheprovider -rfE *>&1 | Out-File -Append -Encoding utf8 $log
"end $(Get-Date -Format T)" | Out-File -Append -Encoding utf8 $log
