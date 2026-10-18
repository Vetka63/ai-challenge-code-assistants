param(
    [switch]$Build,
    [string]$KeyFile,
    [ValidateSet('DEEPSEEK_API_KEY','LLM_API_KEY')][string]$KeyVariable = 'DEEPSEEK_API_KEY'
)
$ErrorActionPreference = 'Stop'
# Compose этой ветки жёстко отключает worker дня 32, включая ранее сохранённую очередь.
& (Join-Path $PSScriptRoot 'Start-Day31.ps1') -Build:$Build -KeyFile $KeyFile -KeyVariable $KeyVariable
Write-Output 'Day 33 UI: http://localhost:8683/#support'
Write-Output 'Day 32 worker: disabled; data preserved'
