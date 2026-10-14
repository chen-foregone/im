<#
.SYNOPSIS
    box-im 群聊性能测试执行器（A 系列）。

.DESCRIPTION
    为什么在线人数固定为 100 而不是 1000：
      发送成本是 O(3N)，与「谁在线」无关。把在线人数在所有规模下固定，
      扇出成本就成为常数 —— 各规模之间 msg/s 的任何变化，纯粹来自路由查询。
      这比变在线人数更干净地隔离了自变量，顺带大幅降低内存占用。

    每个规模跑一轮，轮询到终态后记录：
      msg/s  = 总请求 / (sendMs/1000)
      key/s  = msg/s * 3N          <- 最关键的一列，若各规模基本恒定则假设成立

    关于 3N 而不是 4N：
      IMTerminalType.recvCodes() 排除了 UNKNOW，只返回 WEB/APP/PC 三个终端，
      所以接收侧 MGET key 数 = 3 x 群成员数。脚本早期写死 4N，那是 UNKNOW 还被
      放进 recvTerminals 默认值时的旧值（见 IMTerminalType.java:54-55 的注释）。

.EXAMPLE
    .\run-perf-test.ps1
    .\run-perf-test.ps1 -OnlineUsers 200 -SenderCount 50
#>
param(
    [int]      $OnlineUsers = 100,
    [int]      $SenderCount = 50,
    [string[]] $Scales      = @('FIVE_HUNDRED','TWO_THOUSAND','FIVE_THOUSAND','TEN_THOUSAND'),
    [switch]   $SkipLogin
)

$ErrorActionPreference = 'Continue'
$base = 'http://127.0.0.1:9091/simulator'
$NL   = [Environment]::NewLine

# 规模 -> 群成员数 N / 轮次 / 说明
$spec = [ordered]@{
    'FIVE_HUNDRED'  = @{ N = 500;   Rounds = 400; Label = '五百人群' }
    'TWO_THOUSAND'  = @{ N = 2000;  Rounds = 100; Label = '两千人群' }
    'FIVE_THOUSAND' = @{ N = 5000;  Rounds = 40;  Label = '五千人群' }
    'TEN_THOUSAND'  = @{ N = 10000; Rounds = 20;  Label = '万人群'   }
}

$GroupCount = 1
$logDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'logs'
if (-not (Test-Path $logDir)) { $logDir = Join-Path $PSScriptRoot 'logs' }
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

Write-Host '============================================================' -ForegroundColor Cyan
Write-Host ' box-im 群聊性能测试 · A 系列（固定在线人数，变群规模）' -ForegroundColor Cyan
Write-Host '============================================================' -ForegroundColor Cyan
Write-Host ("  在线用户: {0}   发送人数: {1}   每轮群数: {2}" -f $OnlineUsers, $SenderCount, $GroupCount)
Write-Host ''

# ---------------- 1. 批量登录 ----------------
if (-not $SkipLogin) {
    Write-Host ("[1] 批量登录 {0} 个用户 (terminal=web) ..." -f $OnlineUsers) -ForegroundColor Yellow
    try {
        $t0 = Get-Date
        $login = Invoke-RestMethod -Method Post -Uri "$base/batch-login?limit=$OnlineUsers&terminal=0" -TimeoutSec 300
        $sec = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)
        Write-Host ("    成功 {0} / 失败 {1}    耗时 {2}s" -f $login.succeeded, $login.failed, $sec) -ForegroundColor Green
        if ($login.failed -gt 0) { Write-Host ("    失败样例: " + ($login.failureSamples | ConvertTo-Json -Compress)) -ForegroundColor Red }
        if ($login.succeeded -eq 0) { Write-Host '    一个都没登录上，中止' -ForegroundColor Red; exit 1 }
    } catch {
        Write-Host ("    登录失败: " + $_.Exception.Message) -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host '[1] 跳过登录（沿用已有会话）' -ForegroundColor DarkGray
}

# ---------------- 2. 逐规模跑 ----------------
$results = New-Object System.Collections.Generic.List[object]

foreach ($scale in $Scales) {
    if (-not $spec.Contains($scale)) { Write-Host ("  未知规模: " + $scale) -ForegroundColor Red; continue }
    $s      = $spec[$scale]
    $n      = $s.N
    $rounds = $s.Rounds
    $total  = $SenderCount * $GroupCount * $rounds
    $expWs  = $total * ($OnlineUsers - 1)

    Write-Host ''
    Write-Host ('------------------------------------------------------------') -ForegroundColor DarkGray
    Write-Host ("[A] {0}  N={1}  3N={2}  总请求={3}  期望WS={4}" -f $s.Label, $n, (3*$n), $total, $expWs) -ForegroundColor Yellow

    $body = @{
        groupScale         = $scale
        senderCount        = $SenderCount
        receiverGroupCount = $GroupCount
        sendRounds         = $rounds
        onlineMemberCount  = $OnlineUsers
    } | ConvertTo-Json

    # 【修复3】规模之间必须等上一轮彻底排空。原来固定 sleep 2 秒，
    # 上一轮的 Redis 推送队列可能还在飞，会把帧计到下一轮的账上（跨规模串扰）。
    try { Invoke-RestMethod -Method Post -Uri "$base/group-msg-simulate/stop" -TimeoutSec 30 | Out-Null } catch {}
    $drainPrev = -1; $drainStable = 0
    for ($i = 0; $i -lt 20 -and $drainStable -lt 3; $i++) {
        Start-Sleep -Seconds 2
        try { $cur = (Invoke-RestMethod -Uri "$base/group-msg-simulate/status" -TimeoutSec 30).wsGroupMessagesReceived } catch { break }
        if ($cur -eq $drainPrev) { $drainStable++ } else { $drainStable = 0; $drainPrev = $cur }
    }
    if ($drainStable -lt 3) { Write-Host "    [警告] 上一轮队列 40s 内未排空" -ForegroundColor Yellow }

    try {
        Invoke-RestMethod -Method Post -Uri "$base/group-msg-simulate" -Body $body -ContentType 'application/json' -TimeoutSec 60 | Out-Null
    } catch {
        Write-Host ('    启动失败: ' + $_.Exception.Message) -ForegroundColor Red
        continue
    }

    $t0 = Get-Date
    $deadline = (Get-Date).AddSeconds(1800)
    $st = $null
    $terminal = @('COMPLETED','FAILED','CANCELLED')
    # 【修复1】原条件是「status 不等于 RUNNING 就退出」，于是遇到 SETTLING 立刻跳出。
    # SETTLING 阶段 sendMs 已定稿，但 wsGroupMessagesReceived 仍在增长
    # （实测 3 秒内 48951 -> 49000），据此算出的 WS 偏差是假的 ——
    # 这正是历史结果里 A1/A2「WS 核对异常」的根因。必须等终态。
    do {
        Start-Sleep -Seconds 4
        try { $st = Invoke-RestMethod -Uri "$base/group-msg-simulate/status" -TimeoutSec 30 } catch { $st = $null }
        if ($st -and -not ($terminal -contains $st.status)) {
            Write-Host ("    [{0}] {1}  req={2} ok={3} ws={4}" -f (Get-Date -Format 'HH:mm:ss'), $st.status, $st.totalRequested, $st.succeeded, $st.wsGroupMessagesReceived) -ForegroundColor DarkGray
        }
    } while ($st -and -not ($terminal -contains $st.status) -and (Get-Date) -lt $deadline)

    # 【新增2】终态后再确认 WS 计数收敛：连续 3 次采样不变才落数，
    # 防止 Redis 队列里仍有在途帧时就把数字记下来。
    if ($st -and $terminal -contains $st.status) {
        $prev = $st.wsGroupMessagesReceived; $stable = 0
        for ($i = 0; $i -lt 30 -and $stable -lt 3; $i++) {
            Start-Sleep -Seconds 3
            try { $st2 = Invoke-RestMethod -Uri "$base/group-msg-simulate/status" -TimeoutSec 30 } catch { break }
            if ($st2.wsGroupMessagesReceived -eq $prev) { $stable++ } else { $stable = 0; $prev = $st2.wsGroupMessagesReceived }
            $st = $st2
        }
        if ($stable -lt 3) { Write-Host '    [警告] WS 计数未在 90s 内收敛，本档数据存疑' -ForegroundColor Red }
    }
    $wall = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)

    if (-not $st) { Write-Host '    取不到状态' -ForegroundColor Red; continue }

    $msgPerSec = if ($st.sendMs -gt 0) { [math]::Round($st.totalRequested / ($st.sendMs / 1000.0), 1) } else { 0 }
    $keyPerSec = [math]::Round($msgPerSec * 3 * $n)

    # WS 核对
    $wsOk = $false
    if ($st.totalWs -gt 0) {
        $diff = [math]::Abs($st.wsGroupMessagesReceived - $st.totalWs) / $st.totalWs * 100
        $wsOk = $diff -lt 1
        $wsMsg = "{0} (偏差 {1:N2}%)" -f $st.wsGroupMessagesReceived, $diff
    } else { $wsMsg = "$($st.wsGroupMessagesReceived) (无期望值)" }

    $ok = ($st.failed -eq 0) -and $wsOk
    $color = if ($ok) { 'Green' } else { 'Yellow' }

    Write-Host ("    状态={0}  成功={1} 失败={2}  sendMs={3}  recvMs={4}  墙钟={5}s" -f $st.status, $st.succeeded, $st.failed, $st.sendMs, $st.recvMs, $wall) -ForegroundColor $color
    Write-Host ("    msg/s = {0}    折算 key/s = {1}" -f $msgPerSec, $keyPerSec) -ForegroundColor $color
    Write-Host ("    WS: 实际 {0} / 期望 {1}" -f $wsMsg, $st.totalWs) -ForegroundColor $color
    if ($st.failureSamples -and $st.failureSamples.Count -gt 0) {
        Write-Host ('    失败样例: ' + (($st.failureSamples | Select-Object -First 2 | ForEach-Object { $_ | ConvertTo-Json -Compress }) -join ' | ')) -ForegroundColor Red
    }

    $results.Add([pscustomobject]@{
        Scale      = $scale
        Label      = $s.Label
        N          = $n
        KeysPerMsg = 3 * $n
        Online     = $OnlineUsers
        Senders    = $SenderCount
        Rounds     = $rounds
        Total      = $st.totalRequested
        Succeeded  = $st.succeeded
        Failed     = $st.failed
        SendMs     = $st.sendMs
        RecvMs     = $st.recvMs
        WallSec    = $wall
        MsgPerSec  = $msgPerSec
        KeyPerSec  = $keyPerSec
        TotalWs    = $st.totalWs
        WsRecv     = $st.wsGroupMessagesReceived
        WsOk       = $wsOk
        Status     = $st.status
    })
}

# ---------------- 3. 汇总 ----------------
Write-Host ''
Write-Host '============================================================' -ForegroundColor Cyan
Write-Host ' A 系列结果汇总' -ForegroundColor Cyan
Write-Host '============================================================' -ForegroundColor Cyan
$results | Format-Table -AutoSize @(
    @{L='规模';E={$_.Label};W=10},
    @{L='N';E={$_.N};W=7},
    @{L='3N';E={$_.KeysPerMsg};W=7},
    @{L='总请求';E={$_.Total};W=8},
    @{L='成功';E={$_.Succeeded};W=8},
    @{L='失败';E={$_.Failed};W=6},
    @{L='sendMs';E={$_.SendMs};W=8},
    @{L='msg/s';E={$_.MsgPerSec};W=9},
    @{L='key/s';E={$_.KeyPerSec};W=10},
    @{L='WS核对';E={$_.WsOk};W=7}
) | Out-String -Width 200

$json = Join-Path $logDir 'perf-results.json'
$results | ConvertTo-Json -Depth 5 | Set-Content -Path $json -Encoding UTF8
Write-Host ("原始结果已保存: " + $json) -ForegroundColor DarkGray
Write-Host ''
Write-Host '判读: 若 key/s 一列在各规模下基本恒定，则「瓶颈是 Redis 路由查询次数」成立。' -ForegroundColor Yellow
Write-Host 'DONE'
