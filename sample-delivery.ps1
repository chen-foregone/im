<#
.SYNOPSIS
    Sample the group-message delivery curve at high frequency.

.DESCRIPTION
    The perf runner only samples /status every 4 seconds, which is far too
    coarse to tell a uniform delivery rate from a long tail. This script
    polls every IntervalMs and records (elapsed, wsReceived) so the shape
    of the delivery curve becomes visible.

    Requires the simulator to already have online users (use -SkipLogin on
    run-perf-test.ps1 first).

.EXAMPLE
    .\sample-delivery.ps1 -Scale TEN_THOUSAND -OutFile logs\delivery-curve.csv
#>
param(
    [string] $Scale        = 'TEN_THOUSAND',
    [int]    $SenderCount  = 50,
    [int]    $OnlineUsers  = 100,
    [int]    $GroupCount   = 1,
    [int]    $Rounds       = 20,
    [int]    $IntervalMs   = 250,
    [string] $OutFile      = 'delivery-curve.csv'
)

$ErrorActionPreference = 'Continue'
$base = 'http://127.0.0.1:9091/simulator'

function Get-Status {
    try { return Invoke-RestMethod -Uri "$base/group-msg-simulate/status" -TimeoutSec 30 } catch { return $null }
}

# --- drain any previous run ---
try { Invoke-RestMethod -Method Post -Uri "$base/group-msg-simulate/stop" -TimeoutSec 30 | Out-Null } catch {}
$prev = -1; $stable = 0
for ($i = 0; $i -lt 20 -and $stable -lt 3; $i++) {
    Start-Sleep -Seconds 2
    $s = Get-Status
    if (-not $s) { break }
    if ($s.wsGroupMessagesReceived -eq $prev) { $stable++ } else { $stable = 0; $prev = $s.wsGroupMessagesReceived }
}
if ($stable -lt 3) { Write-Warning 'previous run did not drain within 40s' }

$body = @{
    groupScale         = $Scale
    senderCount        = $SenderCount
    receiverGroupCount = $GroupCount
    sendRounds         = $Rounds
    onlineMemberCount  = $OnlineUsers
} | ConvertTo-Json

$base0 = (Get-Status).wsGroupMessagesReceived
$t0 = Get-Date
try {
    Invoke-RestMethod -Method Post -Uri "$base/group-msg-simulate" -Body $body -ContentType 'application/json' -TimeoutSec 60 | Out-Null
} catch { Write-Output ('start failed: ' + $_.Exception.Message); exit 1 }

$rows = New-Object System.Collections.ArrayList
$terminal = @('COMPLETED','FAILED','CANCELLED')
$sendMsSeen = 0
while ($true) {
    $s = Get-Status
    if (-not $s) { break }
    $el = [math]::Round(((Get-Date) - $t0).TotalMilliseconds, 0)
    if ($s.sendMs -gt 0) { $sendMsSeen = $s.sendMs }
    $delta = $s.wsGroupMessagesReceived - $base0
    [void]$rows.Add([pscustomobject]@{
        elapsedMs = $el
        ws        = $s.wsGroupMessagesReceived
        delta     = $delta
        status    = $s.status
        sendMs    = $s.sendMs
        recvMs    = $s.recvMs
    })
    if ($terminal -contains $s.status) { break }
    Start-Sleep -Milliseconds $IntervalMs
}

$rows | Export-Csv -Path $OutFile -NoTypeInformation -Encoding UTF8

$last = $rows[-1]
$totalWs = $last.delta
Write-Output ''
Write-Output ('status={0}  frames={1}  sendMs={2}  recvMs={3}  measuredWall={4}ms' -f $last.status, $totalWs, $last.sendMs, $last.recvMs, $last.elapsedMs)
Write-Output ''
Write-Output 'bucketed delivery rate (cumulative share of frames):'
Write-Output '  window(ms)   frames   rate(f/s)   cumulative%'
$bucket = 500
$buckets = @{}
foreach ($r in $rows) { $k = [math]::Floor($r.elapsedMs / $bucket) * $bucket; if (-not $buckets.ContainsKey($k)) { $buckets[$k] = 0 }; $buckets[$k] = $r.delta }
$keys = $buckets.Keys | Sort-Object
$prevCum = 0
foreach ($k in $keys) {
    $cum = $buckets[$k]
    $frames = $cum - $prevCum
    $rate = [math]::Round($frames / ($bucket / 1000.0), 0)
    $pct = if ($totalWs -gt 0) { [math]::Round($cum / $totalWs * 100, 1) } else { 0 }
    Write-Output ('  {0,8}-{1,-6} {2,7} {3,11} {4,12}' -f $k, ($k + $bucket), $frames, $rate, $pct)
    $prevCum = $cum
}
Write-Output ''
Write-Output ('CSV -> ' + (Resolve-Path $OutFile))