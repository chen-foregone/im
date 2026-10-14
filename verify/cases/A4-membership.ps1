# A4 -- membership changes must be reflected in the online set.
#
# Claim under test: after a user quits a group, that user must stop receiving signals
# for it; after being re-invited, they must receive them again.
#
# Why this can fail today: fanout prefers im:group:online:{groupId} as its receiver list
# and nothing removed a user from that set on quit (docs 3.17 leftover #2).
#
# NOTE: this file is intentionally pure ASCII. PowerShell 5.1 reads BOM-less .ps1 as GBK,
# so a Chinese literal here corrupts the parse. Keep server messages (which are Chinese)
# as data only.
param([int]$Online = 100)
$ErrorActionPreference = 'Continue'

# A mid-size read-diffusion group (fanout_mode=1). Group 1111 (10k members) cannot be
# used: the server rejects invites beyond its 3000-member cap, so the rejoin half would
# never run.
$GroupId = 1102
$Watcher = 3
$Sender  = 2
$Msgs    = 3
$URLS    = Get-BoximUrls

Reset-BoximState -Online $Online -ProbeGroupId $GroupId -ExpectedGroupOnline $Online
Reset-BoximWatermarks

# The online set must contain the watcher right after login. Without this check a stale
# set silently turns every downstream assertion into a tautology (0 == 0 passes).
$ismember = (& docker exec boxim-redis redis-cli SISMEMBER ('im:group:online:{' + $GroupId + '}') $Watcher 2>&1 | Out-String).Trim()
Assert-Verify 'A4.0a watcher is in the online set after login' ($ismember -eq '1') ('SISMEMBER=' + $ismember)

$tokW = Get-BoximToken -UserId $Watcher
$tokS = Get-BoximToken -UserId $Sender
Assert-Verify 'A4.0b watcher and sender are online' ($tokW.online -and $tokS.online) ('watcher=' + $tokW.online + ' sender=' + $tokS.online)
$hW = @{ accessToken = $tokW.accessToken }
$hS = @{ accessToken = $tokS.accessToken }

# phase 1: before quitting, the watcher must be receiving signals
$sent = @()
for ($i = 1; $i -le $Msgs; $i++) { $sent += Send-BoximGroupMsg -SenderUserId $Sender -GroupId $GroupId -Content ('pre-' + $i) }
$sentOk = @($sent | Where-Object { $_.messageId -gt 0 }).Count
Assert-Verify 'A4.0c sends produced messages' ($sentOk -eq $Msgs) ('sent=' + $sentOk + '/' + $Msgs + ' msg=' + $sent[0].message + ' id=' + $sent[0].messageId)

$baseline = Wait-WatermarkAbove -UserId $Watcher -GroupId $GroupId -Above 0 -TimeoutSec 20
Assert-Verify 'A4.1 watcher receives signals before quit' ($baseline -gt 0) ('baseline watermark=' + $baseline)

# phase 2: quit the group through the real business API
$quitOk = $false
$quitMsg = ''
try {
    $q = Invoke-Json -Method DELETE -Url ($URLS.platform + '/group/quit/' + $GroupId) -Headers $hW
    $quitOk = ($q.code -eq 200)
    $quitMsg = $q.message
} catch { $quitMsg = $_.Exception.Message }
Assert-Verify 'A4.2 quit-group API succeeds' $quitOk $quitMsg

$gone = (& docker exec boxim-redis redis-cli SISMEMBER ('im:group:online:{' + $GroupId + '}') $Watcher 2>&1 | Out-String).Trim()
Assert-Verify 'A4.2b watcher removed from online set on quit' ($gone -eq '0') ('SISMEMBER=' + $gone)

# phase 3: after quitting, the watermark must NOT advance
for ($i = 1; $i -le $Msgs; $i++) { Send-BoximGroupMsg -SenderUserId $Sender -GroupId $GroupId -Content ('post-quit-' + $i) | Out-Null }
$quiet = Wait-WatermarkQuiet -UserId $Watcher -GroupId $GroupId -Seconds 8
Assert-Verify 'A4.3 watcher stops receiving after quit' ($quiet.last -eq $quiet.first) ('quit=' + $quiet.first + ' after=' + $quiet.last + ' (sent ' + $Msgs + ' more)')

# phase 4: re-invite, signals must resume
$invOk = $false
$invMsg = ''
try {
    $inv = Invoke-Json -Method POST -Url ($URLS.platform + '/group/invite') -Headers $hS -Body @{ groupId = $GroupId; friendIds = @($Watcher) }
    $invOk = ($inv.code -eq 200)
    $invMsg = $inv.message
} catch { $invMsg = $_.Exception.Message }
Assert-Verify 'A4.4 re-invite API succeeds' $invOk $invMsg

$back = (& docker exec boxim-redis redis-cli SISMEMBER ('im:group:online:{' + $GroupId + '}') $Watcher 2>&1 | Out-String).Trim()
Assert-Verify 'A4.4b watcher back in online set on re-invite' ($back -eq '1') ('SISMEMBER=' + $back)

for ($i = 1; $i -le $Msgs; $i++) { Send-BoximGroupMsg -SenderUserId $Sender -GroupId $GroupId -Content ('post-join-' + $i) | Out-Null }
$resumed = Wait-WatermarkAbove -UserId $Watcher -GroupId $GroupId -Above $baseline -TimeoutSec 20
Assert-Verify 'A4.5 signals resume after re-invite' ($resumed -gt $baseline) ('resumed=' + $resumed + ' baseline=' + $baseline)
