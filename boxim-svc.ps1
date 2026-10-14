<#
.SYNOPSIS
    Start / stop / restart the three box-im services.

.DESCRIPTION
    Only java processes whose command line mentions box-im are touched
    (im-platform / im-server / im-simulator). Never kills the DSH Desktop JVM.

.EXAMPLE
    .\boxim-svc.ps1 -Action stop
    .\boxim-svc.ps1 -Action start -Probe
.#>
param(
    [ValidateSet('start','stop','restart','status','clean')]
    [string] $Action = 'status',
    [switch] $Probe,
    # extra Spring Boot --key=value args, appended after -jar <jar> for every service.
    # used to A/B a feature flag, e.g. -ExtraArgs '--im.fanout.online-set-enabled=false'
    [string[]] $ExtraArgs = @(),
    [int]    $WaitSec = 120
)

$ErrorActionPreference = 'Continue'
$JAVA  = 'C:\Program Files\Java\jdk-17\bin\java.exe'
$ROOT  = 'D:\Software\SourceCode\IM'
$RUNDIR = Join-Path $ROOT 'runtime'
$PROBE_FILE = Join-Path $ROOT 'logs\perf-counts.csv'
$FANOUT_FILE = Join-Path $ROOT 'logs\perf-fanout.csv'

function Clear-DerivedKeys {
    # presence / server_id keys are DERIVED state and are NOT removed when a JVM is
    # force-killed. Left behind, they make the fanout believe stale users are online
    # (observed: 1000 leftover keys while only 300 users were logged in), which
    # silently corrupts every online-count measurement. Always call this before
    # login when the online count changes.
    docker exec boxim-redis sh -c "redis-cli --scan --pattern 'im:user:presence:*' | xargs -r redis-cli DEL > /dev/null; redis-cli --scan --pattern 'im:user:server_id:*' | xargs -r redis-cli DEL > /dev/null; redis-cli --scan --pattern 'im:group:online:*' | xargs -r redis-cli DEL > /dev/null" | Out-Null
    $p = (docker exec boxim-redis redis-cli --scan --pattern 'im:user:presence:*' 2>&1 | Measure-Object).Count
    $s = (docker exec boxim-redis redis-cli --scan --pattern 'im:user:server_id:*' 2>&1 | Measure-Object).Count
    $o = (docker exec boxim-redis redis-cli --scan --pattern 'im:group:online:*' 2>&1 | Measure-Object).Count
    Write-Output ('  derived keys cleared; presence=' + $p + ' server_id=' + $s + ' group_online=' + $o)
}

$SVCS = @(
    @{ name='im-platform'; jar="$ROOT\box-im\im-platform\target\im-platform.jar"; dir="$ROOT\box-im\im-platform"; port=8888 },
    @{ name='im-server';   jar="$ROOT\box-im\im-server\target\im-server.jar";       dir="$ROOT\box-im\im-server";   port=8878 },
    @{ name='simulator';   jar="$ROOT\box-im-simulator\target\im-simulator.jar";    dir="$ROOT\box-im-simulator";    port=9091 }
)

function Get-BoximPids {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and ($_.CommandLine -match 'im-platform|im-server|im-simulator|box-im-simulator') } |
        Select-Object -ExpandProperty ProcessId
}

function Stop-Boxim {
    $pids = @(Get-BoximPids)
    if ($pids.Count -eq 0) { Write-Output '  no box-im java process running'; return }
    foreach ($p in $pids) {
        $pr = Get-Process -Id $p -ErrorAction SilentlyContinue
        if ($pr) { Write-Output ('  stopping PID=' + $p); Stop-Process -Id $p -Force -ErrorAction SilentlyContinue }
    }
    for ($i=0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 500
        if (@(Get-BoximPids).Count -eq 0) { break }
    }
    Write-Output ('  remaining: ' + @(Get-BoximPids).Count)
}

function Start-Boxim {
    New-Item -ItemType Directory -Force -Path $RUNDIR | Out-Null
    if ($Probe) { foreach ($f in @($PROBE_FILE, $FANOUT_FILE)) { if (Test-Path $f) { Remove-Item $f -Force -ErrorAction SilentlyContinue } } }
    foreach ($s in $SVCS) {
        if (-not (Test-Path $s.jar)) { Write-Output ('  MISSING jar: ' + $s.jar); continue }
        $so = Join-Path $RUNDIR ($s.name + '.out.log')
        $se = Join-Path $RUNDIR ($s.name + '.err.log')
        # Spring Boot --key=value args must come AFTER -jar <jar>,
        # otherwise the JVM rejects them as 'Unrecognized option'.
        $jvm = @('-Xms512m','-Xmx2g')
        $app = @()
        if ($Probe) {
            if ($s.name -eq 'im-server') {
                $app += "--im.perf.probe-enabled=true"
                $app += "--im.perf.probe-file=$PROBE_FILE"
            } elseif ($s.name -eq 'im-platform') {
                # separate files: a shared one gets TRUNCATE-d by the other process
                $app += "--im.perf.probe-enabled=true"
                $app += "--im.perf.probe-file=$FANOUT_FILE"
            }
        }
        if ($ExtraArgs.Count -gt 0) { $app += $ExtraArgs }
        $a = $jvm + @('-jar', $s.jar) + $app
        $p = Start-Process -FilePath $JAVA -ArgumentList $a -WorkingDirectory $s.dir `
             -RedirectStandardOutput $so -RedirectStandardError $se -PassThru -WindowStyle Hidden
        Write-Output ('  started {0,-12} PID={1,-7} port={2}' -f $s.name, $p.Id, $s.port)
    }
    for ($i=1; $i -le $WaitSec/2; $i++) {
        Start-Sleep -Seconds 2
        $up = 0
        foreach ($s in $SVCS) { if (Get-NetTCPConnection -LocalPort $s.port -State Listen -ErrorAction SilentlyContinue) { $up++ } }
        if ($up -eq $SVCS.Count) { Write-Output ('  all up in ~' + ($i*2) + 's'); return }
    }
    Write-Output '  WARNING: not all ports came up'
}

function Show-Status {
    foreach ($s in $SVCS) {
        $c = Get-NetTCPConnection -LocalPort $s.port -State Listen -ErrorAction SilentlyContinue
        $pid1 = (Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
                 Where-Object { $_.CommandLine -like ('*' + (Split-Path $s.jar -Leaf) + '*') } |
                 Select-Object -First 1 -ExpandProperty ProcessId)
        $cpu = ''
        if ($pid1) { $pp = Get-Process -Id $pid1 -ErrorAction SilentlyContinue; if ($pp) { $cpu = [math]::Round($pp.CPU,1) } }
        Write-Output ('  {0,-12} port={1,-5} {2,-7} pid={3,-7} cpu={4}s' -f $s.name, $s.port, $(if ($c) {'LISTEN'} else {'DOWN'}), $pid1, $cpu)
    }
    foreach ($f in @($PROBE_FILE, $FANOUT_FILE)) { if (Test-Path $f) { Write-Output ('  probe: ' + $f + '  size=' + (Get-Item $f).Length) } }
}

switch ($Action) {
    'stop'    { Write-Output '[stop]';    Stop-Boxim }
    'start'   { Write-Output '[start]';   Start-Boxim }
    'restart' { Write-Output '[stop]'; Stop-Boxim; Write-Output '[start]'; Start-Boxim }
    'status'  { Write-Output '[status]';  Show-Status }
    'clean'   { Write-Output '[clean]';   Clear-DerivedKeys }
}