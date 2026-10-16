param(
    [switch]$Build,
    [string]$KeyFile,
    [ValidateSet('DEEPSEEK_API_KEY','LLM_API_KEY')][string]$KeyVariable = 'DEEPSEEK_API_KEY'
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
New-Item -ItemType Directory -Path (Join-Path $taskRoot '.local\day32') -Force | Out-Null
$taskArgs = @{ KeyVariable = $KeyVariable }
if ($Build) { $taskArgs.Build = $true }
if ($KeyFile) { $taskArgs.KeyFile = $KeyFile }
& (Join-Path $PSScriptRoot 'Start-Day31.ps1') @taskArgs
if ($LASTEXITCODE -ne 0) { throw 'Day 32 start failed' }
Write-Output 'Day 32 reviews: http://localhost:8683/#reviews'
