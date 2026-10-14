<#
.SYNOPSIS
    box-im A-series multi-round runner: runs run-perf-test.ps1 N times and reports
    median / min / max / spread per scale.

.DESCRIPTION
    Why this exists:
      Single-round numbers on this machine are NOT comparable. Measured with the
      same build and the same fanout_mode, the 10000-member group came out 553.7 then 324.5
      msg/s (-41%), and the 5000-member group 515.3 then 752.7 (+46%). Any A/B that flips sign between
      orderings is reporting noise, not effect.
      So: never quote a single round. Run N rounds and quote the median.

    Round 1 logs in all simulated users; later rounds reuse the live sessions
    (-SkipLogin), which is both faster and closer to a steady state.

.EXAMPLE
    .\run-perf-median.ps1 -Rounds 3 -Tag presence-on
    .\run-perf-median.ps1 -Rounds 3 -Scales TWO_THOUSAND,TEN_THOUSAND -Tag read
#>
param(
    [int]      $Rounds = 3,
    [string[]] $Scales = @('FIVE_HUNDRED','TWO_THOUSAND','FIVE_THOUSAND','TEN_THOUSAND'),
    [string]   $Tag    = 'run',
    [int]      $OnlineUsers = 100,
    [int]      $SenderCount = 50
)

$ErrorActionPreference = 'Continue'
$here   = $PSScriptRoot
$runner = Join-Path $here 'run-perf-test.ps1'
$logDir = Join-Path (Split-Path -Parent $here) 'logs'
if (-not (Test-Path $logDir)) { $logDir = Join-Path $here 'logs' }
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

Write-Output "=== multi-round: tag=$Tag rounds=$Rounds scales=$($Scales -join ',') ==="

for ($r = 1; $r -le $Rounds; $r++) {
    Write-Output ""
    Write-Output "--- round $r / $Rounds ---"
    $logFile = Join-Path $logDir ("median-$Tag-r$r.log")
    # Two traps, both of which produced silent 0-byte runs before:
    #  1. Array splatting (@array) is POSITIONAL ONLY -- '-Scales' would be bound to
    #     the first positional parameter (OnlineUsers) and blow up. Named parameters
    #     require HASHTABLE splatting (@hash).
    #  2. -Scales is [string[]], so pass a real array, not one 'A,B' string.
    $ht = @{ Scales = $Scales; OnlineUsers = $OnlineUsers; SenderCount = $SenderCount }
    if ($r -gt 1) { $ht['SkipLogin'] = $true }
    & $runner @ht *> $logFile

    $res = Join-Path $logDir 'perf-results.json'
    $dst = Join-Path $logDir ("perf-results.mr-$Tag-r$r.json")
    if (Test-Path $res) {
        Copy-Item $res $dst -Force
        $data = Get-Content $dst -Raw | ConvertFrom-Json
        foreach ($d in $data) {
            Write-Output ("    {0,-14} {1,8:N1} msg/s   ws_ok={2}   failed={3}" -f $d.Scale, $d.MsgPerSec, $d.WsOk, $d.Failed)
        }
    } else {
        Write-Output "    (no perf-results.json produced)"
    }
}

# ---- aggregate ----
Write-Output ""
Write-Output "============================================================"
Write-Output " MEDIAN SUMMARY  tag=$Tag  rounds=$Rounds"
Write-Output "============================================================"
Write-Output ""
Write-Output ("{0,-14} {1,10} {2,10} {3,10} {4,10} {5,9}" -f 'Scale','median','min','max','spread%','n')
Write-Output ("{0,-14} {1,10} {2,10} {3,10} {4,10} {5,9}" -f '-----','------','---','---','-------','-')

foreach ($s in $Scales) {
    $vals = @()
    foreach ($r in 1..$Rounds) {
        $f = Join-Path $logDir ("perf-results.mr-$Tag-r$r.json")
        if (Test-Path $f) {
            $data = Get-Content $f -Raw | ConvertFrom-Json
            $hit = $data | Where-Object { $_.Scale -eq $s }
            if ($hit) { $vals += [double]$hit.MsgPerSec }
        }
    }
    if ($vals.Count -eq 0) { continue }
    $sorted = $vals | Sort-Object
    $n = $sorted.Count
    $median = if ($n % 2 -eq 1) { $sorted[[int](($n-1)/2)] } else { ($sorted[$n/2 - 1] + $sorted[$n/2]) / 2 }
    $min = $sorted[0]; $max = $sorted[-1]
    $spread = if ($median -ne 0) { (($max - $min) / $median) * 100 } else { 0 }
    Write-Output ("{0,-14} {1,10:N1} {2,10:N1} {3,10:N1} {4,10:N1} {5,9}" -f $s, $median, $min, $max, $spread, $n)
}

Write-Output ""
Write-Output "Note: spread% is (max-min)/median. If it is large, a single round cannot"
Write-Output "support any A/B claim at that scale -- compare medians of equal round counts."
Write-Output "MEDIAN-DONE"