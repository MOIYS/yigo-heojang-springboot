[CmdletBinding()]
param(
    [string]$ContainerName = 'yigo-mysql',
    [string]$Database = 'wallet',
    [string]$MySqlUser = 'root',
    [string]$MySqlPassword = $env:MYSQL_PASSWORD,
    [string]$PerfPasswordHash = $env:PERF_PASSWORD_HASH
)

$ErrorActionPreference = 'Stop'

function Invoke-DockerCommand {
    param(
        [Parameter(Mandatory)]
        [string[]]$Arguments,

        [Parameter(Mandatory)]
        [string]$FailureMessage
    )

    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FailureMessage (docker exit code: $LASTEXITCODE)"
    }
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'docker was not found on PATH.'
}

if ([string]::IsNullOrWhiteSpace($PerfPasswordHash)) {
    throw 'PERF_PASSWORD_HASH is required. Set it to the BCrypt hash of PERF_PASSWORD.'
}

if ($PerfPasswordHash.Length -ne 60 -or $PerfPasswordHash -notmatch '^\$2[aby]\$') {
    throw 'PERF_PASSWORD_HASH must be a 60-character BCrypt hash beginning with $2a$, $2b$, or $2y$.'
}

if ([string]::IsNullOrWhiteSpace($MySqlPassword)) {
    throw 'MySQL password is empty. Set MYSQL_PASSWORD or pass -MySqlPassword.'
}

$performanceDirectory = $PSScriptRoot
$cleanupPath = (Resolve-Path -LiteralPath (Join-Path $performanceDirectory 'perf_cleanup.sql')).Path
$seedPath = (Resolve-Path -LiteralPath (Join-Path $performanceDirectory 'perf_seed.sql')).Path

$containerState = & docker inspect --format '{{.State.Running}}' $ContainerName 2>$null
if ($LASTEXITCODE -ne 0 -or $containerState -ne 'true') {
    throw "Docker container '$ContainerName' was not found or is not running."
}

Write-Host "Copying performance SQL files to $ContainerName..."
Invoke-DockerCommand `
    -Arguments @('cp', $cleanupPath, "${ContainerName}:/tmp/perf_cleanup.sql") `
    -FailureMessage 'Failed to copy perf_cleanup.sql'
Invoke-DockerCommand `
    -Arguments @('cp', $seedPath, "${ContainerName}:/tmp/perf_seed.sql") `
    -FailureMessage 'Failed to copy perf_seed.sql'

$mysqlBaseArguments = @(
    'exec',
    '-i',
    '-e', "MYSQL_PWD=$MySqlPassword",
    $ContainerName,
    'mysql',
    "-u$MySqlUser",
    '--default-character-set=utf8mb4',
    $Database
)

Write-Host 'Cleaning the 20 performance accounts...'
Invoke-DockerCommand `
    -Arguments ($mysqlBaseArguments + @('-e', 'source /tmp/perf_cleanup.sql')) `
    -FailureMessage 'perf_cleanup.sql failed'

$initCommand = "SET @perf_password_hash = '$PerfPasswordHash'"
Write-Host 'Seeding deterministic performance data...'
Invoke-DockerCommand `
    -Arguments ($mysqlBaseArguments + @("--init-command=$initCommand", '-e', 'source /tmp/perf_seed.sql')) `
    -FailureMessage 'perf_seed.sql failed'

Write-Host 'Performance data reset completed successfully.'
