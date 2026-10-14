<#
.SYNOPSIS
    Interleaved A/B driver for box-im group fanout: write-diffusion vs read-diffusion.

.DESCRIPTION
    Why interleaved rather than "run A three times, then B three times":
      This machine drifts a lot over minutes. Running A A A B B B confounds the
      effect with the drift. Running A B A B A B cancels it (to first order).

    Why medians rather than means:
      The distribution is skewed by stalls. Median is the honest summary.

    The only variable is im_group.fanout_mode, flipped in MySQL between runs.
    Same build, same jar, same data, no restart.

.EXAMPLE
    .\run-perf-ab.ps1 -Rounds 3 -Scales FIVE_THOUSAND,TEN_THOUSAND
#>
param(
    [int]      $Rounds = 3,
    [string[]] $Scales  = @('FIVE_THOUSAND','TEN_THOUSAND'),
    [string]   $Tag     = 'fv-r',
    [int]      $OnlineUsers = 100,
    [int]      $SenderCount = 50
)

$ErrorActionPreference = 'Continue'
$here   = $PSScriptRoot
$runner = Join-Path $here 'run-perf-test.ps1'
$logDir = Join-Path (Split-Path -Parent $here) 'logs'
if (-not (Test-Path $logDir)) { $logDir = Join-Path $here 'logs' }
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

# Groups used by FIVE_THOUSAND and TEN_THOUSAND scales.
$whereRead  = '(id BETWEEN 1111 AND 1120) OR (id BETWEEN 1141 AND 1150)'
$whereWrite = '(id BETWEEN 1111 AND 1120) OR (id BETWEEN 1141 AND 1150)'

function Set-Mode([int]$mode) {
    $sql = "UPDATE im_group SET fanout_mode=$mode WHERE $whereRead"
    docker exec boxim-mysql mysql -uroot -proot im_platform_test -N -B -e $sql 2>$null | Out-Null
}

Write-Output "=== interleaved AB: rounds=$Rounds scales=$($Scales -join ',') tag=$Tag ==="

$first = $true
for ($r = 1; $r -le $Rounds; $r++) {
    foreach ($cfg in @( @{name='WRITE'; mode=0}, @{name='READ'; mode=1} )) {
        Write-Output ""
        Write-Output "--- round $r/$Rounds  cfg=$($cfg.name) (fanout_mode=$($cfg.mode)) ---"
        Set-Mode $cfg.mode
        $logFile = Join-Path $logDir ("ab-$Tag-r$r-$($cfg.name).log")
        # Two traps here, both of which produced silent 0-byte runs before:
        #  1. Array splatting (@array) is POSITIONAL ONLY -- '-Scales' would be bound to
        #     the first positional parameter (OnlineUsers) and blow up. Named parameters
        #     require HASHTABLE splatting (@hash).
        #  2. -Scales is [string[]], so pass a real array, not one 'A,B' string.
        $ht = @{ Scales = $Scales; OnlineUsers = $OnlineUsers; SenderCount = $SenderCount }
        if (-not $first) { $ht['SkipLogin'] = $true }
        $first = $false
        & $runner @ht *> $logFile

        $res = Join-Path $logDir 'perf-results.json'
        if (Test-Path $res) {
            $dst = Join-Path $logDir ("perf-results.ab2-$Tag-r$r-$($cfg.name).json")
            Copy-Item $res $dst -Force
            $data = Get-Content $dst -Raw | ConvertFrom-Json
            foreach ($d in $data) {
                Write-Output ("    {0,-14} {1,8:N1} msg/s  ws_ok={2} failed={3}" -f $d.Scale, $d.MsgPerSec, $d.WsOk, $d.Failed)
            }
        }
    }
}

Write-Output ""
Write-Output "============================================================"
Write-Output " INTERLEAVED MEDIAN SUMMARY  tag=$Tag  rounds=$Rounds"
Write-Output "============================================================"
Write-Output ""
Write-Output ("{0,-14} {1,12} {2,12} {3,10} {4,12}" -f 'Scale','WRITE med','READ med','delta%','spread(W/R)')
Write-Output ("{0,-14} {1,12} {2,12} {3,10} {4,12}" -f '-----','---------','--------','------','-----------')

$script:allDeltas = @()
function Get-Median([double[]]$v) {
    if ($v.Count -eq 0) { return $null }
    $s = $v | Sort-Object
    $n = $s.Count
    if ($n % 2 -eq 1) { return $s[[int](($n-1)/2)] }
    return ($s[$n/2 - 1] + $s[$n/2]) / 2
}

foreach ($sc in $Scales) {
    $w = @(); $rd = @()
    foreach ($r in 1..$Rounds) {
        $fw = Join-Path $logDir ("perf-results.ab2-$Tag-r$r-WRITE.json")
        $fr = Join-Path $logDir ("perf-results.ab2-$Tag-r$r-READ.json")
        if (Test-Path $fw) { $h = (Get-Content $fw -Raw | ConvertFrom-Json) | Where-Object { $_.Scale -eq $sc }; if ($h) { $w += [double]$h.MsgPerSec } }
        if (Test-Path $fr) { $h = (Get-Content $fr -Raw | ConvertFrom-Json) | Where-Object { $_.Scale -eq $sc }; if ($h) { $rd += [double]$h.MsgPerSec } }
    }
    $mw = Get-Median $w; $mr = Get-Median $rd
    if ($null -eq $mw -or $null -eq $mr) { continue }
    $delta = (($mr - $mw) / $mw) * 100
    $sw = if ($w.Count -gt 1) { (($w | Measure-Object -Maximum).Maximum - ($w | Measure-Object -Minimum).Minimum) / $mw * 100 } else { 0 }
    $sr = if ($rd.Count -gt 1) { (($rd | Measure-Object -Maximum).Maximum - ($rd | Measure-Object -Minimum).Minimum) / $mr * 100 } else { 0 }

    # PAIRED statistic -- this is the one that answers the question.
    # Pooled medians mix different drift levels, because the machine warms up
    # over the session (WRITE on 5000 went 505.6 -> 691.1 -> 870.7, +72%).
    # Pairing r-th WRITE with r-th READ cancels the drift to first order.
    $pairDeltas = @()
    foreach ($r in 1..$Rounds) {
        $fw = Join-Path $logDir ("perf-results.ab2-$Tag-r$r-WRITE.json")
        $fr = Join-Path $logDir ("perf-results.ab2-$Tag-r$r-READ.json")
        if (-not (Test-Path $fw) -or -not (Test-Path $fr)) { continue }
        $hw = (Get-Content $fw -Raw | ConvertFrom-Json) | Where-Object { $_.Scale -eq $sc }
        $hr = (Get-Content $fr -Raw | ConvertFrom-Json) | Where-Object { $_.Scale -eq $sc }
        if ($hw -and $hr) {
            $pairDeltas += (($hr.MsgPerSec - $hw.MsgPerSec) / $hw.MsgPerSec) * 100
        }
    }
    $pm = Get-Median $pairDeltas
    $wins = ($pairDeltas | Where-Object { $_ -gt 0 } | Measure-Object).Count
    $n = $pairDeltas.Count
    Write-Output ("{0,-14} {1,12:N1} {2,12:N1} {3,9:N1}% {4,12} {5,11:N1}% {6,8}" -f $sc, $mw, $mr, $delta, ("{0:N0}%/{1:N0}%" -f $sw, $sr), $pm, ("$wins/$n"))
    $script:allDeltas += $pairDeltas
}

Write-Output ""
Write-Output "pooled% = from the two pooled medians. DRIFT-CONFOUNDED -- do not use it alone."
Write-Output "paired med = median of per-round (READ-WRITE)/WRITE. Drift cancels in the pairing."
Write-Output "R wins = how many pairings READ beat WRITE out of the total."
Write-Output ""
if ($script:allDeltas.Count -gt 0) {
    $tot = $script:allDeltas.Count
    $won = ($script:allDeltas | Where-Object { $_ -gt 0 } | Measure-Object).Count
    Write-Output ("OVERALL paired: median {0:N1}%   READ wins {1}/{2}" -f (Get-Median $script:allDeltas), $won, $tot)
    if ($won -eq $tot -and $tot -ge 5) { Write-Output "  sign test: unanimous -> p = $([math]::Round([math]::Pow(0.5,$tot)*100,1))%" }
    elseif ($won -eq ($tot-1) -and $tot -ge 6) { Write-Output "  sign test: $won/$tot -> p = $([math]::Round($tot*[math]::Pow(0.5,$tot)*100,1))%" }
    else { Write-Output "  sign test: $won/$tot is not significant -- the A/B does not resolve an effect." }
}
Write-Output "AB-DONE"