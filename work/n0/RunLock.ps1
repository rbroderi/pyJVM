# Shared one-run-at-a-time lock for Windows scripts (plan 2.2). Dot-source it after setting $work (the pyjvm-work folder).
#   Enter-RunLock "<who> <what>"   waits while another run holds the lock; returns the text written into it
#   Exit-RunLock $text             removes the lock only if it still holds $text
function Enter-RunLock([string]$What) {
    $lock = Join-Path $work 'run.lock'
    if (-not (Test-Path -PathType Container $work)) { throw "run lock directory $work does not exist (set PYJVM_WORK)" }
    $text = "$What $PID $(Get-Date -Format o)"
    while ($true) {
        try {
            $s = [System.IO.File]::Open($lock, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write)
            try { $b = [System.Text.Encoding]::UTF8.GetBytes($text); $s.Write($b, 0, $b.Length) } finally { $s.Dispose() }
            return $text
        } catch [System.IO.IOException] {
            if (-not (Test-Path $lock)) { throw }   # not "already exists": a real error
            Start-Sleep 30
        }
    }
}

function Exit-RunLock([string]$Text) {
    $lock = Join-Path $work 'run.lock'
    if ((Test-Path $lock) -and ((Get-Content -Raw $lock) -eq $Text)) { Remove-Item -Force $lock }
}
