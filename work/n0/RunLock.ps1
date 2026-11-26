# Shared run lock for Windows scripts (plan 2.2), with up to $env:PYJVM_LOCK_SLOTS (default 3) concurrent JVM runs.
# Dot-source it after setting $work (the pyjvm-work folder). Slot files: run.lock (slot 1, the historical single
# lock), run.lock.2, run.lock.3 - the same files the WSL helper run_lock.sh uses.
#   Enter-RunLock "<who> <what>"   waits until a slot is free; returns a handle (the text plus the slot files held)
#                                  with $env:PYJVM_LOCK_EXCLUSIVE = '1': takes every slot in order (full gates run alone)
#   Exit-RunLock $handle           removes the slot files that still hold the handle's text
function Get-RunLockSlotFile([int]$I) {
    if ($I -eq 1) { return (Join-Path $work 'run.lock') }
    return (Join-Path $work ("run.lock.$I"))
}

function Enter-RunLockSlot([string]$Lock, [string]$Text) {
    try {
        $s = [System.IO.File]::Open($Lock, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write)
        try { $b = [System.Text.Encoding]::UTF8.GetBytes($Text); $s.Write($b, 0, $b.Length) } finally { $s.Dispose() }
        return $true
    } catch [System.IO.IOException] {
        if (-not (Test-Path $Lock)) { throw }   # not "already exists": a real error
        return $false
    }
}

function Enter-RunLock([string]$What) {
    if (-not (Test-Path -PathType Container $work)) { throw "run lock directory $work does not exist (set PYJVM_WORK)" }
    $n = 3
    if ($env:PYJVM_LOCK_SLOTS) { $n = [int]$env:PYJVM_LOCK_SLOTS }
    $text = "$What $PID $(Get-Date -Format o)"
    $held = @()
    if ($env:PYJVM_LOCK_EXCLUSIVE -eq '1') {
        for ($i = 1; $i -le $n; $i++) {
            $f = Get-RunLockSlotFile $i
            while (-not (Enter-RunLockSlot $f $text)) { Start-Sleep 30 }
            $held += $f
        }
        return [pscustomobject]@{ Text = $text; Files = $held }
    }
    while ($true) {
        for ($i = 1; $i -le $n; $i++) {
            $f = Get-RunLockSlotFile $i
            if (Enter-RunLockSlot $f $text) { return [pscustomobject]@{ Text = $text; Files = @($f) } }
        }
        Start-Sleep 30
    }
}

function Exit-RunLock($Handle) {
    if ($null -eq $Handle) { return }
    if ($Handle -is [string]) {   # handle from the old single-lock helper
        $Handle = [pscustomobject]@{ Text = $Handle; Files = @((Get-RunLockSlotFile 1)) }
    }
    foreach ($f in $Handle.Files) {
        if ((Test-Path $f) -and ((Get-Content -Raw $f) -eq $Handle.Text)) { Remove-Item -Force $f }
    }
}
