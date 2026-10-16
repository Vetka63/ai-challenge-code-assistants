param(
    [switch]$Build,
    [string]$KeyFile,
    [ValidateSet('DEEPSEEK_API_KEY','LLM_API_KEY')][string]$KeyVariable = 'DEEPSEEK_API_KEY'
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskPrevious = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'Process')
try {
    if ($KeyFile) {
        # Import exactly one explicitly selected value, never the rest of another .env.
        $taskPattern = '^\s*' + [regex]::Escape($KeyVariable) + '\s*='
        $taskLine = Get-Content -LiteralPath $KeyFile | Where-Object { $_ -match $taskPattern } | Select-Object -First 1
        if (-not $taskLine) { throw "Variable $KeyVariable not found" }
        $taskValue = ($taskLine -split '=',2)[1].Trim().Trim([char[]]@([char]34,[char]39))
        if (-not $taskValue) { throw 'Selected key is empty' }
        $env:DEEPSEEK_API_KEY = $taskValue
    }
    Push-Location $taskRoot
    try {
        if ($Build) { docker compose up -d --build } else { docker compose up -d }
        if ($LASTEXITCODE -ne 0) { throw 'Docker Compose failed' }
    } finally { Pop-Location }
} finally {
    [Environment]::SetEnvironmentVariable('DEEPSEEK_API_KEY', $taskPrevious, 'Process')
    $taskLine = $null; $taskValue = $null
}
Write-Output 'Day 31 UI: http://localhost:8683'
