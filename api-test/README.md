# Newman API 회귀·계약 테스트

Spring Boot 실행 JAR이 기존 API 계약을 유지하는지 Newman으로 검증합니다.
컬렉션의 `[legacy]`는 Legacy WAR 런타임이 아니라, 마이그레이션 후에도 의도적으로 유지하는 기존 동작을 뜻합니다.

## 빠른 실행

### 사전 요구사항

- Node.js 24와 npm
- Docker CLI
- 실행 중인 Spring Boot Backend, MySQL 8과 Redis 7.2
- 대상 DB에 적용된 Flyway V1과 `data.sql`, 91, 92, 93 Seed
- 유효한 `SIGNUP` 약관과 활성 `mock_card`

DB와 Seed 준비 방법은 [Backend 실행 가이드](../backend/README.md)를 참고합니다.
MySQL은 Docker 컨테이너를 사용하며 호스트의 MySQL CLI는 필요하지 않습니다.

### 설치 및 실행

```powershell
cd api-test
npm ci
$env:DB_USER = '<local mysql user>'
$env:DB_PASSWORD = '<local mysql password>'
npm test
```

`npm test`는 다음 명령으로 `run.ps1`을 실행합니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run.ps1
```

실행기는 `.env`를 자동으로 읽지 않습니다. DB 인증정보는 저장소 파일이나 Postman environment에 기록하지 않습니다.

## 검증 범위

| 영역 | 주요 검증 내용 |
| --- | --- |
| 회원·인증 | 회원가입, 로그인, Access Token, 재발급, 로그아웃 |
| 회원정보 | 인증 실패, 회원 조회·수정 |
| 보유 카드 | 등록, 목록·상세, 대표 카드 설정, 삭제 |
| 알림 | 목록, 읽음·삭제와 실패 응답 |
| 공통 계약 | Jackson 호환, Validation, 400·404·405 오류 응답, 인증 필터, CORS preflight |

정지·탈퇴 회원과 만료 Access Token은 현재 Newman 검증 범위에 포함되지 않습니다.

## 환경변수

| 환경변수 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `DB_USER` | 예 | 없음 | MySQL 계정 |
| `DB_PASSWORD` | 예 | 없음 | MySQL 비밀번호 |
| `DB_CONTAINER` | 아니요 | `yigo-mysql` | MySQL Docker 컨테이너 이름 |
| `DB_NAME` | 아니요 | `wallet` | Fixture를 적용할 DB 이름 |
| `API_TEST_BASE_URL` | 아니요 | `http://localhost:8080` | 테스트 대상 Backend 주소 |
| `NEWMAN_JSON_REPORT_PATH` | 아니요 | 없음 | CLI와 함께 생성할 JSON 리포트 경로 |

## 실행 흐름

`run.ps1`은 대상 DB와 필수 파일을 확인한 뒤 다음 순서로 실행합니다.

1. 실행별 12자리 `runId`를 생성합니다.
2. `init.sql`로 해당 실행의 회원·인증·알림 Fixture를 생성합니다.
3. 활성 `mock_card`를 조회하고 Newman collection을 실행합니다.
4. 성공·실패와 관계없이 `finally`에서 `cleanup.sql`을 실행합니다.

로그인처럼 이후 요청의 전제조건인 요청이 실패하면 collection은 즉시 중단합니다.

## 선택 실행과 결과 저장

특정 Newman 폴더나 요청만 실행하려면 `-Folder`를 사용합니다.

```powershell
npm test -- -Folder "06 Request and Error Contracts"
```

JSON 리포트가 필요하면 실행 전에 출력 경로를 지정합니다.

```powershell
$env:NEWMAN_JSON_REPORT_PATH = 'results/newman-report.json'
npm test
```

### CI

CI는 MySQL 8·Redis 7.2를 새로 구성하고 JDK 21로 실행 JAR을 빌드·기동한 뒤, Flyway V1과 Seed를 적용하고 Node.js 24에서 같은 실행기를 호출합니다.
`newman-api-test-results` Artifact에는 `newman-report.json`, `newman-transcript.log`, `backend.log`를 수집합니다.

## 테스트 데이터 관리

- 공유 약관, 카드 마스터 및 기존 사용자 데이터를 수정하거나 삭제하지 않습니다.
- 테스트 데이터는 실행별 `runId`로 격리하며, `TRUNCATE` 없이 해당 실행의 데이터만 정리합니다.
- 실행 중 생성한 Token과 ID는 collection variable에서 관리하며 Postman environment로 내보내지 않습니다.

<details>
<summary>강제 종료 시 수동 cleanup</summary>

프로세스 강제 종료로 `finally`가 실행되지 않으면 테스트 데이터가 남을 수 있습니다.

`run.ps1`은 `runId`를 별도로 출력하지 않으므로, 남아 있는 Newman Fixture 이메일에서 12자리 `runId`를 확인합니다.

정리할 실행이 종료되었는지 확인하며, 대상 실행을 정확히 식별할 수 없다면 임의로 cleanup하지 않습니다.

**1. 잔여 Fixture 조회**

```powershell
$dbContainer = if ($env:DB_CONTAINER) { $env:DB_CONTAINER } else { 'yigo-mysql' }
$dbName = if ($env:DB_NAME) { $env:DB_NAME } else { 'wallet' }

if ($dbContainer -notmatch '^[A-Za-z0-9_.-]+$') { throw 'DB_CONTAINER 형식 오류' }
if ($dbName -notmatch '^[A-Za-z0-9_]+$') { throw 'DB_NAME 형식 오류' }

docker exec -i -e "MYSQL_PWD=$env:DB_PASSWORD" $dbContainer `
  mysql --protocol=socket --user=$env:DB_USER --database=$dbName --batch --skip-column-names `
  -e "SELECT email FROM member WHERE email LIKE 'newman-%@example.invalid';"

if ($LASTEXITCODE -ne 0) { throw 'Newman Fixture 조회 실패' }
```

**2. 대상 실행의 데이터 정리**

조회 결과에서 정리할 실행의 `runId`를 확인한 뒤, 같은 PowerShell 터미널에서 실행합니다.

```powershell
$runId = '<조회 결과에서 확인한 12자리 runId>'

if ($runId -notmatch '^[0-9a-f]{12}$') { throw 'runId 형식 오류' }

$cleanupSql = "SET @api_test_run_id := '$runId';`n" + (Get-Content -Raw .\fixtures\cleanup.sql)

$cleanupSql | docker exec -i `
  -e "MYSQL_PWD=$env:DB_PASSWORD" `
  $dbContainer mysql --protocol=socket --user=$env:DB_USER --database=$dbName

if ($LASTEXITCODE -ne 0) { throw 'Newman Fixture cleanup 실패' }
```

</details>

## 실패 확인

Newman 출력에서 가장 먼저 실패한 요청을 확인한 뒤 Backend 프로세스 로그 또는
`docker compose logs backend`와 대조합니다. 연쇄 실패보다 최초 실패 원인을 먼저 해결합니다.
