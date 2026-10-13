[CmdletBinding()]
param(
    [string]$BaseUrl = $(if ($env:API_TEST_BASE_URL) { $env:API_TEST_BASE_URL } else { 'http://localhost:8080' }),
    [string]$DbContainer = $(if ($env:API_TEST_DB_CONTAINER) { $env:API_TEST_DB_CONTAINER } else { 'yigo-mysql' }),
    [string]$DbName = $(if ($env:API_TEST_DB_NAME) { $env:API_TEST_DB_NAME } else { 'wallet' }),
    [string]$DbUser = $(if ($env:API_TEST_DB_USER) { $env:API_TEST_DB_USER } else { 'root' }),
    [string]$DbPassword = $env:API_TEST_DB_PASSWORD
)

$ErrorActionPreference = 'Stop'

$apiTestRoot = Split-Path -Parent $PSScriptRoot
$collectionPath = Join-Path $apiTestRoot 'collections/legacy-api-regression.postman_collection.json'
$environmentPath = Join-Path $apiTestRoot 'environments/default.postman_environment.json'
$initSqlPath = Join-Path $apiTestRoot 'fixtures/init.sql'
$cleanupSqlPath = Join-Path $apiTestRoot 'fixtures/cleanup.sql'
$newmanPath = Join-Path $apiTestRoot 'node_modules/newman/bin/newman.js'
$runId = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$fixtureSetupAttempted = $false

function Assert-RequiredInput {
    if ([string]::IsNullOrWhiteSpace($DbPassword)) {
        throw 'API_TEST_DB_PASSWORD 환경변수를 설정해야 합니다. 비밀번호는 파일에 저장하지 않습니다.'
    }

    if ($DbName -notmatch '^[A-Za-z0-9_]+$') {
        throw 'API_TEST_DB_NAME에는 영문자, 숫자, 밑줄만 사용할 수 있습니다.'
    }

    if ($DbContainer -notmatch '^[A-Za-z0-9_.-]+$') {
        throw 'API_TEST_DB_CONTAINER 값이 올바른 Docker 컨테이너 이름이 아닙니다.'
    }

    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'docker 명령을 찾을 수 없습니다.'
    }

    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
        throw 'node 명령을 찾을 수 없습니다.'
    }

    foreach ($requiredPath in @($collectionPath, $environmentPath, $initSqlPath, $cleanupSqlPath)) {
        if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
            throw "필수 파일을 찾을 수 없습니다: $requiredPath"
        }
    }

    if (-not (Test-Path -LiteralPath $newmanPath -PathType Leaf)) {
        throw 'Newman이 설치되지 않았습니다. api-test 디렉터리에서 npm install을 먼저 실행하세요.'
    }
}

function Invoke-MySqlText {
    param(
        [Parameter(Mandatory)]
        [string]$Sql
    )

    $output = $Sql | & docker exec -i `
        -e "MYSQL_PWD=$DbPassword" `
        $DbContainer `
        mysql `
        --protocol=socket `
        --user=$DbUser `
        --database=$DbName `
        --batch `
        --skip-column-names

    if ($LASTEXITCODE -ne 0) {
        throw "Docker MySQL 실행이 실패했습니다. container=$DbContainer, database=$DbName"
    }

    return ($output | Out-String).Trim()
}

function Invoke-FixtureSql {
    param(
        [Parameter(Mandatory)]
        [string]$Path
    )

    $sqlBody = Get-Content -LiteralPath $Path -Raw
    $sql = "SET @api_test_run_id := '$runId';`n$sqlBody"
    [void](Invoke-MySqlText -Sql $sql)
}

Assert-RequiredInput

try {
    $actualDatabase = Invoke-MySqlText -Sql 'SELECT DATABASE();'
    if ($actualDatabase -ne $DbName) {
        throw "연결된 DB가 요청한 DB와 다릅니다. expected=$DbName, actual=$actualDatabase"
    }

    $fixtureSetupAttempted = $true
    Invoke-FixtureSql -Path $initSqlPath

    $cardNumber = Invoke-MySqlText -Sql @'
SELECT mc.card_number
FROM mock_card mc
INNER JOIN card c ON c.card_id = mc.card_id
INNER JOIN card_company cc ON cc.card_company_id = c.card_company_id
WHERE mc.is_active = 'Y'
  AND c.is_active = 'Y'
  AND cc.is_active = 'Y'
ORDER BY mc.mock_card_id
LIMIT 1;
'@

    if ($cardNumber -notmatch '^\d{13,19}$') {
        throw '등록 가능한 활성 mock_card를 찾지 못했습니다.'
    }

    $memberEmail = "newman-member-$runId@example.invalid"
    $signupEmail = "newman-signup-$runId@example.invalid"
    $signupVerificationToken = "newman-signup-token-$runId"
    $notificationReadTitle = "NEWMAN_READ_$runId"
    $notificationDeleteTitle = "NEWMAN_DELETE_$runId"

    & node $newmanPath run $collectionPath `
        --environment $environmentPath `
        --env-var "baseUrl=$BaseUrl" `
        --env-var "runId=$runId" `
        --env-var "memberEmail=$memberEmail" `
        --env-var "signupEmail=$signupEmail" `
        --env-var "signupVerificationToken=$signupVerificationToken" `
        --env-var "cardNumber=$cardNumber" `
        --env-var "notificationReadTitle=$notificationReadTitle" `
        --env-var "notificationDeleteTitle=$notificationDeleteTitle" `
        --reporters cli

    if ($LASTEXITCODE -ne 0) {
        throw "Newman 회귀 테스트가 실패했습니다. exitCode=$LASTEXITCODE"
    }
}
finally {
    if ($fixtureSetupAttempted) {
        Invoke-FixtureSql -Path $cleanupSqlPath
    }
}

