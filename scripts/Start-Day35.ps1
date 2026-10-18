param(
    [string]$GamePath = 'F:\ai-advent-challenge\ai-challenge-game',
    [string]$ExistingKeyFile = 'F:\ai-advent-challenge\ai-chat\.env',
    [string]$OllamaContainer = 'ai-challenge-assistants-day31-ollama-1',
    [ValidatePattern('^[a-z0-9][a-z0-9_-]+$')][string]$ProjectName = 'ai-challenge-assistants-day35',
    [ValidateRange(1024,65535)][int]$UiPort = 8983,
    [ValidateRange(1024,65535)][int]$ApiPort = 8982,
    [ValidateRange(1024,65535)][int]$McpPort = 8984,
    [switch]$Build
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskPreviousKey = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'Process')
$taskPreviousGame = [Environment]::GetEnvironmentVariable('GAME_PROJECT_PATH', 'Process')
$taskPreviousPorts = @{}
foreach ($taskPortName in @('UI_PORT','API_PORT','MCP_PORT')) { $taskPreviousPorts[$taskPortName] = [Environment]::GetEnvironmentVariable($taskPortName, 'Process') }
try {
    if (-not (Test-Path -LiteralPath (Join-Path $GamePath 'README.md'))) { throw 'Game README was not found.' }
    $env:GAME_PROJECT_PATH = (Resolve-Path -LiteralPath $GamePath).Path.Replace('\', '/')
    $env:UI_PORT = "$UiPort"; $env:API_PORT = "$ApiPort"; $env:MCP_PORT = "$McpPort"
    $taskNetworks = @(docker network ls --format '{{.Name}}')
    if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop first.' }
    if ('ai-challenge-local-models' -notin $taskNetworks) {
        docker network create ai-challenge-local-models | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Model network creation failed.' }
    }
    docker start $OllamaContainer | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Existing Ollama was not found. Specify -OllamaContainer.' }
    $taskAttached = docker inspect --format '{{json .NetworkSettings.Networks}}' $OllamaContainer | ConvertFrom-Json
    if (-not $taskAttached.PSObject.Properties['ai-challenge-local-models']) {
        docker network connect --alias shared-ollama ai-challenge-local-models $OllamaContainer
        if ($LASTEXITCODE -ne 0) { throw 'Model network attachment failed.' }
    }
    if (-not $taskPreviousKey -and (Test-Path -LiteralPath $ExistingKeyFile)) {
        $taskKeyLine = Get-Content -LiteralPath $ExistingKeyFile | Where-Object { $_ -match '^\s*LLM_API_KEY\s*=' } | Select-Object -First 1
        if ($taskKeyLine) { $env:DEEPSEEK_API_KEY = ($taskKeyLine -split '=', 2)[1].Trim().Trim([char[]]@([char]34, [char]39)) }
    }
    $taskCompose = Join-Path $taskRoot 'compose.day35.yml'
    if ($Build) { docker compose -p $ProjectName -f $taskCompose up -d --build } else { docker compose -p $ProjectName -f $taskCompose up -d }
    if ($LASTEXITCODE -ne 0) { throw 'Day 35 deployment failed.' }
} finally {
    [Environment]::SetEnvironmentVariable('DEEPSEEK_API_KEY', $taskPreviousKey, 'Process')
    [Environment]::SetEnvironmentVariable('GAME_PROJECT_PATH', $taskPreviousGame, 'Process')
    foreach ($taskPortName in @('UI_PORT','API_PORT','MCP_PORT')) { [Environment]::SetEnvironmentVariable($taskPortName, $taskPreviousPorts[$taskPortName], 'Process') }
    $taskKeyLine = $null
}
Write-Output "Day 35: http://localhost:$UiPort/#passport"
Write-Output 'Review automation is OFF. All game mounts are read-only.'
