# Verify.psm1 -- reusable correctness-verification library (A1-A4).
#
# Why this exists: the perf scripts only PRINT NUMBERS, they never FAIL. After a change
# nobody is told something broke. These helpers turn the five hard-won preconditions
# (always clean, always wait until settled, independent cases, JSON output, fast mode)
# into shared code so a test cannot silently test the wrong thing.

$script:IMRoot       = Split-Path $PSScriptRoot -Parent
$script:SvcScript    = Join-Path $script:IMRoot 'boxim-svc.ps1'
$script:SimBase      = 'http://127.0.0.1:9091/simulator'
$script:PlatformBase = 'http://127.0.0.1:8888'
$script:Results      = @()

function Write-Step { param([string]$Msg) Write-Host ('  ' + $Msg) }

function Invoke-Json {
    param([string]$Method, [string]$Url, $Body, $Headers, [int]$TimeoutSec = 30)
    $p = @{ Method = $Method; Uri = $Url; TimeoutSec = $TimeoutSec; UseBasicParsing = $true }
    if ($null -ne $Body) { $p.ContentType = 'application/json'; $p.Body = ($Body | ConvertTo-Json -Compress -Depth 6) }
    if ($Headers) { $p.Headers = $Headers }
    # Spring returns application/json without a charset, and PowerShell 5.1 then decodes
    # the body as ISO-8859-1 -- Chinese error messages come back as mojibake and you
    # cannot tell what the server actually said. Re-decode the raw bytes as UTF-8.
    $resp = Invoke-WebRequest @p
    if ($resp.RawContentStream) {
        $text = [Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
    } else {
        $text = $resp.Content
    }
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    try { return $text | ConvertFrom-Json } catch { return $text }
}

# Case scripts run in their own scope, so $script: inside them is NOT the module's.
# They must reach the module variables through this accessor.
function Get-BoximUrls { return @{ platform = $script:PlatformBase; simulator = $script:SimBase } }

# Assertions accumulate in module scope, so any case script can call this.
function Assert-Verify {
    param([string]$Name, [bool]$Ok, [string]$Detail = '')
    $script:Results += [pscustomobject]@{ name = $Name; ok = $Ok; detail = $Detail }
    $mark  = if ($Ok) { 'PASS' } else { 'FAIL' }
    $color = if ($Ok) { 'Green' } else { 'Red' }
    $line  = '  [' + $mark + '] ' + $Name
    if ($Detail) { $line += '   -- ' + $Detail }
    Write-Host $line -ForegroundColor $color
}

function Get-VerifyResults { $script:Results }
function Reset-VerifyResults { $script:Results = @() }

# clean presence/server_id/group_online, log in N users, wait until settled.
# Skipping clean is how you end up with 891 online while believing you have 1000.
function Reset-BoximState {
    param([int]$Online = 100, [switch]$NoClean, [long]$ProbeGroupId = 0, [int]$ExpectedGroupOnline = -1)
    if (-not $NoClean) {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script:SvcScript -Action clean | Out-Null
    }
    $r = Invoke-Json -Method POST -Url ($script:SimBase + '/batch-login?limit=' + $Online + '&terminal=0')
    Write-Step ('logged in ' + $Online + ': ok=' + $r.succeeded + ' fail=' + $r.failed)
    Wait-BoximSettled -ExpectedOnline $Online -ProbeGroupId $ProbeGroupId -ExpectedGroupOnline $ExpectedGroupOnline | Out-Null
}

# Settled = user-event queue empty AND presence count equals expectation.
# Login storms leave a ~9s drain tail; measuring during it corrupts the run.
# Settled is NOT just 'queue empty and presence correct'.
#
# presence is written by im-server as sockets connect, but the per-group online set is
# built asynchronously while the ONLINE events are consumed. The queue can read 0 for a
# moment while the consumer is still mid-batch, so a test that starts here sees an empty
# receiver set and every downstream assertion silently degenerates into '0 == 0 passes'.
# When the caller names a group, wait for that group's online set too.
function Wait-BoximSettled {
    param([int]$ExpectedOnline, [long]$ProbeGroupId = 0, [int]$ExpectedGroupOnline = -1, [int]$TimeoutSec = 90)
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $q = '?'; $n = '?'; $g = '?'
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
        $q = (& docker exec boxim-redis redis-cli LLEN 'im:user:event' 2>&1 | Out-String).Trim()
        $n = (& docker exec boxim-redis redis-cli --scan --pattern 'im:user:presence:*' 2>&1 | Measure-Object).Count
        $ok = ($q -eq '0') -and ($n -eq $ExpectedOnline)
        if ($ProbeGroupId -gt 0) {
            $g = [int](& docker exec boxim-redis redis-cli SCARD ('im:group:online:{' + $ProbeGroupId + '}') 2>&1 | Out-String).Trim()
            $want = if ($ExpectedGroupOnline -ge 0) { $ExpectedGroupOnline } else { $ExpectedOnline }
            $ok = $ok -and ($g -eq $want)
        }
        if ($ok) {
            $extra = if ($ProbeGroupId -gt 0) { '  groupOnline=' + $g } else { '' }
            Write-Step ('settled in ' + [math]::Round($sw.Elapsed.TotalSeconds,1) + 's  presence=' + $n + $extra)
            return $true
        }
        Start-Sleep -Milliseconds 500
    }
    Write-Step ('WARN settle timeout after ' + $TimeoutSec + 's  queue=' + $q + ' presence=' + $n + ' groupOnline=' + $g)
    return $false
}

function Get-BoximToken {
    param([long]$UserId)
    return Invoke-Json -Method GET -Url ($script:SimBase + '/verify/token?userId=' + $UserId)
}

# user -> watermark (max signal messageId) for a group; only users with a value > 0.
# Pull every group message in [FromId, head], paging 100 at a time.
#
# The history API is 'between(minSeqNo, maxSeqNo)' with a hard 'limit 100' and id ASC.
# Its schema comment claims maxSeqNo=0 means 'unlimited' but the implementation does not
# honour that -- pass a huge maxSeqNo or you silently get zero rows.
function Get-BoximHistory {
    param([string]$Token, [long]$GroupId, [long]$FromId, [int]$MaxPages = 60)
    $all = New-Object System.Collections.ArrayList
    $cursor = $FromId
    for ($p = 0; $p -lt $MaxPages; $p++) {
        $r = Invoke-Json -Method POST -Url ($script:PlatformBase + '/message/group/history') -Headers @{ accessToken = $Token } -Body @{
            groupId = $GroupId; minSeqNo = $cursor; maxSeqNo = 9223372036854775807 }
        if ($null -eq $r -or $null -eq $r.data) { break }
        $page = @($r.data)
        if ($page.Count -eq 0) { break }
        foreach ($m in $page) { [void]$all.Add($m) }
        $last = [long]$page[$page.Count - 1].id
        if ($last -le $cursor) { break }
        $cursor = $last + 1
        if ($page.Count -lt 100) { break }
    }
    return $all.ToArray()
}

# Target watermark for one user, given a ledger of id -> sender.
#
# A sender never receives the signal for its own message (fanout strips senderId by
# design). Comparing a watermark against the GLOBAL max therefore always shows the
# sender of the last message as '1 behind'. This rule bit twice: once in A1 and again
# in A2, which is why it lives here instead of being re-derived per case.
function Get-TargetWatermark {
    param([hashtable]$Ledger, [long]$UserId)
    $t = 0L
    foreach ($e in $Ledger.GetEnumerator()) {
        if ([long]$e.Value.sender -ne $UserId -and [long]$e.Key -gt $t) { $t = [long]$e.Key }
    }
    return $t
}

# How many users have not yet caught up with the messages addressed to them.
function Get-WatermarkGap {
    param([hashtable]$Ledger, [hashtable]$Watermarks, [int[]]$Users)
    $behind = @()
    foreach ($u in $Users) {
        $seen = if ($Watermarks.ContainsKey([long]$u)) { [long]$Watermarks[[long]$u] } else { 0L }
        $target = Get-TargetWatermark -Ledger $Ledger -UserId ([long]$u)
        if ($seen -lt $target) { $behind += $u }
    }
    return $behind
}

function Get-BoximWatermarks {
    param([long]$GroupId)
    $r = Invoke-Json -Method GET -Url ($script:SimBase + '/verify/watermarks?groupId=' + $GroupId)
    $h = @{}
    if ($r) { $r.PSObject.Properties | ForEach-Object { $h[[long]$_.Name] = [long]$_.Value } }
    return $h
}

function Reset-BoximWatermarks { Invoke-Json -Method POST -Url ($script:SimBase + '/verify/reset') | Out-Null }

function Send-BoximGroupMsg {
    param([long]$SenderUserId, [long]$GroupId, [string]$Content = 'verify')
    return Invoke-Json -Method POST -Url ($script:SimBase + '/verify/send') -Body @{
        senderUserId = $SenderUserId; groupId = $GroupId; content = $Content }
}

# Poll until a specific user's watermark passes a threshold (or time out).
function Wait-WatermarkAbove {
    param([long]$UserId, [long]$GroupId, [long]$Above, [int]$TimeoutSec = 20)
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
        $h = Get-BoximWatermarks -GroupId $GroupId
        if ($h.ContainsKey($UserId) -and $h[$UserId] -gt $Above) { return $h[$UserId] }
        Start-Sleep -Milliseconds 400
    }
    $h = Get-BoximWatermarks -GroupId $GroupId
    if ($h.ContainsKey($UserId)) { return $h[$UserId] }
    return 0
}

# Sample a user's watermark, wait, sample again. Equal means the user went quiet.
function Wait-WatermarkQuiet {
    param([long]$UserId, [long]$GroupId, [int]$Seconds = 5)
    $h1 = Get-BoximWatermarks -GroupId $GroupId
    $first = if ($h1.ContainsKey($UserId)) { $h1[$UserId] } else { 0 }
    Start-Sleep -Seconds $Seconds
    $h2 = Get-BoximWatermarks -GroupId $GroupId
    $last = if ($h2.ContainsKey($UserId)) { $h2[$UserId] } else { 0 }
    return @{ first = $first; last = $last }
}

function Write-VerifyReport {
    param([string]$OutFile = '')
    $failed = @($script:Results | Where-Object { -not $_.ok })
    $pass   = @($script:Results | Where-Object { $_.ok }).Count
    Write-Host ''
    Write-Host ('==== SUMMARY: ' + $pass + ' passed, ' + $failed.Count + ' failed ====')
    if ($OutFile) {
        $obj = [pscustomobject]@{
            at = (Get-Date).ToString('s'); passed = $pass; failed = $failed.Count; assertions = $script:Results }
        $obj | ConvertTo-Json -Depth 6 | Out-File -FilePath $OutFile -Encoding UTF8
        Write-Host ('  report -> ' + $OutFile)
    }
    if ($failed.Count -gt 0) { return 1 }
    return 0
}

Export-ModuleMember -Function *
