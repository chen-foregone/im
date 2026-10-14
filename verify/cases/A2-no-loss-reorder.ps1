# A2 -- no loss, no duplication, correct ordering.
#
# Claim under test: for every online member, the messages the client can actually pull
# cover every message the server accepted, exactly once, in ascending order.
#
# This walks the REAL client path: the signal (cmd=6) tells the client how far the group
# has advanced, then the client pages /message/group/history. A lossy signal is fine as
# long as the timeline is complete, which is exactly what read diffusion promises.
#
# Pure ASCII: PowerShell 5.1 reads BOM-less .ps1 as GBK and Chinese literals break parsing.
param([int]$Online = 100, [int]$SampleUsers = 5)
$ErrorActionPreference = 'Continue'

$GroupId = 1102
$Senders = @(2, 3, 4)
$MsgsPerSender = 4
$URLS = Get-BoximUrls

Reset-BoximState -Online $Online -ProbeGroupId $GroupId -ExpectedGroupOnline $Online
Reset-BoximWatermarks

# ---- phase 1: send a known ledger of messages ----
$ledger = @{}
$sendFail = 0
foreach ($s in $Senders) {
    for ($i = 1; $i -le $MsgsPerSender; $i++) {
        $c = 'a2-s' + $s + '-' + $i
        $r = Send-BoximGroupMsg -SenderUserId $s -GroupId $GroupId -Content $c
        if ($r.messageId -gt 0) { $ledger[[long]$r.messageId] = @{ content = $c; sender = $s } } else { $sendFail++ }
    }
}
$expected = $Senders.Count * $MsgsPerSender
Assert-Verify 'A2.1 all sends produced a server message id' ($ledger.Count -eq $expected) ('ledger=' + $ledger.Count + '/' + $expected + ' fail=' + $sendFail)
$maxSent = ($ledger.Keys | Measure-Object -Maximum).Maximum
$minSent = ($ledger.Keys | Measure-Object -Minimum).Minimum

# ---- phase 2: wait until every online session's watermark reached the head ----
# This is A1's criterion, and it is the 'no loss on the push side' half of the claim.
$sw = [Diagnostics.Stopwatch]::StartNew()
$behind = @()
while ($sw.Elapsed.TotalSeconds -lt 30) {
    $wm = Get-BoximWatermarks -GroupId $GroupId
    $behind = Get-WatermarkGap -Ledger $ledger -Watermarks $wm -Users (1..$Online)
    if ($behind.Count -eq 0) { break }
    Start-Sleep -Milliseconds 500
}
Assert-Verify 'A2.2 every online session watermark reached its target' ($behind.Count -eq 0) ('behind=' + $behind.Count + ' maxSent=' + $maxSent + ' who=' + (($behind | Select-Object -First 5) -join ','))

# ---- phase 3: deep check on a sample via the real history API ----
$sample = @($Senders + (1..$Online)) | Select-Object -Unique | Select-Object -First $SampleUsers
$missTotal = 0; $dupTotal = 0; $orderBad = 0; $contentBad = 0; $headBad = 0
$checked = 0
foreach ($u in $sample) {
    $tok = Get-BoximToken -UserId $u
    if (-not $tok.online) { continue }
    $msgs = Get-BoximHistory -Token $tok.accessToken -GroupId $GroupId -FromId ($minSent - 1)
    if ($msgs.Count -eq 0) { $missTotal += $ledger.Count; continue }
    $checked++
    $ids = @($msgs | ForEach-Object { [long]$_.id })
    $head = ($ids | Measure-Object -Maximum).Maximum
    if ($head -lt $maxSent) { $headBad++ }
    $set = @{}
    foreach ($m in $msgs) {
        $id = [long]$m.id
        if ($set.ContainsKey($id)) { $dupTotal++ } else { $set[$id] = $true }
        if ($ledger.ContainsKey($id) -and $m.content -ne $ledger[$id].content) { $contentBad++ }
    }
    foreach ($k in $ledger.Keys) { if (-not $set.ContainsKey($k)) { $missTotal++ } }
    for ($i = 1; $i -lt $ids.Count; $i++) { if ($ids[$i] -le $ids[$i-1]) { $orderBad++ } }
}
Assert-Verify 'A2.3 sampled users can reach the head' ($headBad -eq 0 -and $checked -gt 0) ('checked=' + $checked + ' headBehind=' + $headBad)
Assert-Verify 'A2.4 no message is missing from any sampled timeline' ($missTotal -eq 0) ('misses=' + $missTotal + ' across ' + $checked + ' users')
Assert-Verify 'A2.5 no duplicate ids within a timeline' ($dupTotal -eq 0) ('dups=' + $dupTotal)
Assert-Verify 'A2.6 ids strictly ascending' ($orderBad -eq 0) ('out-of-order pairs=' + $orderBad)
Assert-Verify 'A2.7 content matches what was sent' ($contentBad -eq 0) ('mismatches=' + $contentBad)
Write-Step ('ledger ' + $minSent + '..' + $maxSent + ' (' + $ledger.Count + ' messages), deep-checked ' + $checked + '/' + $Online + ' users')
