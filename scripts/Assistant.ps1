param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CliArguments)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
Push-Location $taskRoot
try {
    docker compose --profile tools run --rm --no-deps cli @CliArguments
    if ($LASTEXITCODE -ne 0) { throw "Assistant CLI exit code $LASTEXITCODE" }
} finally { Pop-Location }
