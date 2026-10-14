<#
.SYNOPSIS
    Per-process CPU attribution sampler for box-im perf runs.

.DESCRIPTION
    The bottleneck during a run can sit in im-platform (fanout), im-server
    (delivery) or simulator (the test client). Sampling each process's CPU
    time is a zero-code-change attribution method: whichever process runs
    near a full core is the bottleneck.

.EXAMPLE
    .\sample-cpu.ps1 -DurationSec 120 -OutFile logs\cpu-attr.csv
#>
param(
    [int]    $DurationSec = 120,
    [int]    $IntervalMs  = 1000,
    [string] $OutFile     = 'cpu-attr.csv'
)

$ErrorActionPreference = 'Continue'
$tags = @('im-platform', 'im-server', 'simulator')

function Get-Tagged {
    $map = @{}
    $procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue
    foreach ($p in $procs) {
        $cl = $p.CommandLine
        if (-not $cl) { continue }
        $tag = $null
        if     ($cl -match 'im-platform')            { $tag = 'im-platform' }
        elseif ($cl -match 'im-server')              { $tag = 'im-server' }
        elseif ($cl -match 'imsimulator|simulator')  { $tag = 'simulator' }
        if ($tag) {
            $pr = Get-Process -Id $p.ProcessId -ErrorAction SilentlyContinue
            if ($pr) { $map[$tag] = @{ pid = $p.ProcessId; cpu = $pr.CPU; ws = $pr.WorkingSet64 } }
        }
    }
    return $map
}

$prev = Get-Tagged
$missing = $tags | Where-Object { -not $prev.ContainsKey($_) }
if ($missing) { Write-Warning ('missing process: ' + ($missing -join ', ')) }

$rows = New-Object System.Collections.ArrayList
$deadline = (Get-Date).AddSeconds($DurationSec)
$i = 0
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds $IntervalMs
    $now = Get-Tagged
    $row = [ordered]@{ t = $i }
    foreach ($tag in $tags) {
        $dc = $tag + '_d'
        $wc = $tag + '_ws'
        if ($now.ContainsKey($tag) -and $prev.ContainsKey($tag) -and $now[$tag].pid -eq $prev[$tag].pid) {
            $row[$dc] = [math]::Round($now[$tag].cpu - $prev[$tag].cpu, 3)
            $row[$wc] = [math]::Round($now[$tag].ws / 1MB, 0)
        } else {
            $row[$dc] = ''
            $row[$wc] = ''
        }
    }
    [void]$rows.Add([pscustomobject]$row)
    $prev = $now
    $i++
}

$rows | Export-Csv -Path $OutFile -NoTypeInformation -Encoding UTF8

Write-Output ''
Write-Output '=== CPU attribution summary (delta CPU seconds -> avg cores) ==='
Write-Output ('sampled {0}s' -f $i)
foreach ($tag in $tags) {
    $dc = $tag + '_d'
    $col = $rows | ForEach-Object { $_.$dc } | Where-Object { $_ -ne '' -and $_ -ne $null }
    if ($col.Count -gt 0) {
        $tot = ($col | Measure-Object -Sum).Sum
        $avg = [math]::Round($tot / $col.Count, 2)
        $max = ($col | Measure-Object -Maximum).Maximum
        Write-Output ('  {0,-14} total {1,8:N1}s   avg {2,5:N2} cores   peak {3,5:N2} cores' -f $tag, $tot, $avg, $max)
    }
}