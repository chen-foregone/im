<#
  run-verify.ps1 -- single entry point for correctness verification.

  Usage:
    powershell -File run-verify.ps1 -Case A4 -Online 100
    powershell -File run-verify.ps1 -Case A4 -Online 1000

  Each case is a script under cases\<name>-*.ps1 that calls Assert-Verify.
  Exit code is non-zero when any assertion failed, so this is CI-usable.
#>
param(
    [string[]]$Case = @('A4'),
    [int]$Online = 100,
    [string]$OutFile = ''
)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Import-Module (Join-Path $here 'Verify.psm1') -Force
Reset-VerifyResults

foreach ($c in $Case) {
    $file = Get-ChildItem -Path (Join-Path $here 'cases') -Filter ($c + '-*.ps1') | Select-Object -First 1
    if (-not $file) { Write-Host ('case not found: ' + $c) -ForegroundColor Red; exit 2 }
    Write-Host ''
    Write-Host ('==== running ' + $file.BaseName + ' (online=' + $Online + ') ====') -ForegroundColor Cyan
    & $file.FullName -Online $Online
}

if (-not $OutFile) { $OutFile = Join-Path $here ('last-verify-' + ($Case -join '_') + '.json') }
exit (Write-VerifyReport -OutFile $OutFile)
