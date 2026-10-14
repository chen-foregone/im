<#
.SYNOPSIS
    把 SQL 文件导入 boxim-mysql 容器。

.DESCRIPTION
    故意不用管道（Get-Content | docker exec）—— Windows 上管道会破坏 UTF-8 中文，
    导致群名「十人群1」变成乱码，进而让模拟器的群名前缀匹配失效。
    这里改用 docker cp 拷进容器内部再重定向，编码全程不受 Windows 影响。

.EXAMPLE
    .\load-data.ps1 ..\box-im-simulator\db\汇总.sql
    .\load-data.ps1 ..\box-im-simulator\db\3.创建1000个十人群.sql
#>
param(
    [Parameter(Mandatory = $true)][string]$SqlFile,
    [string]$Database  = "im_platform_test",
    [string]$Container = "boxim-mysql"
)

$ErrorActionPreference = "Stop"
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')

if (-not (Test-Path $SqlFile))                  { throw "找不到 SQL 文件: $SqlFile" }
if (-not (docker ps --format '{{.Names}}' | Select-String -SimpleMatch $Container)) {
    throw "容器 $Container 没在运行，先执行 docker compose up -d"
}

$full = (Resolve-Path $SqlFile).Path
$sizeMb = [math]::Round((Get-Item $full).Length / 1MB, 1)
$inContainer = "/tmp/" + [IO.Path]::GetFileName($full)

Write-Host "[1/3] 拷贝 $sizeMb MB 到容器内 ..." -ForegroundColor Cyan
docker cp "$full" "${Container}:$inContainer"

Write-Host "[2/3] 导入到数据库 $Database ...（34MB 大约要几分钟，别中断）" -ForegroundColor Cyan
$sw = [Diagnostics.Stopwatch]::StartNew()
docker exec $Container sh -c "mysql --default-character-set=utf8mb4 -uroot -proot $Database < '$inContainer'"
if ($LASTEXITCODE -ne 0) { throw "导入失败，退出码 $LASTEXITCODE" }
$sw.Stop()

Write-Host "[3/3] 完成，耗时 $([math]::Round($sw.Elapsed.TotalSeconds,1)) 秒" -ForegroundColor Green
docker exec $Container mysql -uroot -proot -N -B -e `
  "SELECT 'im_user',COUNT(*) FROM $Database.im_user
   UNION ALL SELECT 'im_group',COUNT(*) FROM $Database.im_group
   UNION ALL SELECT 'im_group_member',COUNT(*) FROM $Database.im_group_member
   UNION ALL SELECT 'im_friend',COUNT(*) FROM $Database.im_friend;" |
  ForEach-Object { Write-Host "        $_" }
