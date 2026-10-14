@echo off
REM ---------------------------------------------------------------------------
REM  load-data.ps1 的包装脚本。
REM  存在的唯一原因：Windows 默认的 PowerShell 执行策略会拒绝运行 .ps1，
REM  报 "无法加载文件 ... 未对文件进行数字签名"。
REM  这里用 -ExecutionPolicy Bypass 只作用于本次进程，不改系统设置。
REM
REM  用法:
REM     load-data.cmd ..\box-im-simulator\db\汇总.sql
REM     load-data.cmd ..\box-im-simulator\db\汇总.sql im_platform_test
REM ---------------------------------------------------------------------------
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0load-data.ps1" %*
exit /b %ERRORLEVEL%
