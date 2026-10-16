param()
$ErrorActionPreference = 'Stop'
$taskFolder = Join-Path (Split-Path $PSScriptRoot -Parent) '.local\day32'
New-Item -ItemType Directory -Path $taskFolder -Force | Out-Null
$taskPath = Join-Path $taskFolder 'github-token'
$taskSecure = Read-Host 'Paste fine-grained GitHub token (hidden; do not share it in chat)' -AsSecureString
$taskPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskSecure)
try {
    $taskPlain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskPointer).Trim()
    if ($taskPlain -notmatch '^github_pat_[A-Za-z0-9_]{20,}$') { throw 'Expected a fine-grained github_pat_ token. Nothing was saved.' }
    [IO.File]::WriteAllText($taskPath, $taskPlain, (New-Object Text.UTF8Encoding($false)))
    if ($env:OS -eq 'Windows_NT') {
        $taskAcl = Get-Acl -LiteralPath $taskPath
        $taskAcl.SetAccessRuleProtection($true, $false)
        $taskIdentity = [Security.Principal.WindowsIdentity]::GetCurrent().User
        $taskRule = New-Object Security.AccessControl.FileSystemAccessRule($taskIdentity, 'FullControl', 'Allow')
        $taskAcl.SetAccessRule($taskRule)
        Set-Acl -LiteralPath $taskPath -AclObject $taskAcl
    }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskPointer)
    $taskPlain = $null
    if ($taskSecure) { $taskSecure.Dispose() }
}
Write-Output 'Token saved locally. In Project Desk -> Review PR -> Check connection. Token value is not printed.'
