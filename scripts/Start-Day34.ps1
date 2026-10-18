param([switch]$Build, [string]$KeyFile,
    [ValidateSet('DEEPSEEK_API_KEY','LLM_API_KEY')][string]$KeyVariable = 'DEEPSEEK_API_KEY')
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'Start-Day31.ps1') -Build:$Build -KeyFile $KeyFile -KeyVariable $KeyVariable
if ($Build) {
    $taskRoot = Split-Path $PSScriptRoot -Parent
    Push-Location $taskRoot
    try {
        docker compose --profile tools build cli
        if ($LASTEXITCODE -ne 0) { throw 'CLI build failed' }
    } finally { Pop-Location }
}
Write-Output 'Day 34 UI: http://localhost:8683/#workspace'
Write-Output 'Day 32 automation remains disabled. Original game is mounted read-only.'
