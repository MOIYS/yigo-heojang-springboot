# k6 성능 테스트

k6를 사용하여 인증 읽기 API의 응답 시간과 처리량을 측정하고, 동일한 조건에서 변경 전후의 성능을 비교합니다.

> 현재 확정 결과는 2026-10-20에 측정한 Legacy WAR 기준선입니다. Spring Boot 전환 후 결과는 아직 측정하지 않았습니다.

성능 테스트는 운영 DB가 아닌 격리된 로컬 MySQL에서만 실행합니다.

## 측정 조건

### 고정 데이터

다음은 성능 계정 20개 전체의 데이터 건수입니다.

| 데이터 | 건수 |
| --- | ---: |
| 활성 성능 계정 | 20 |
| 활성 보유 카드 / 대표 카드 | 100 / 20 |
| 활성 알림 | 700 |
| 읽음 / 안 읽음 활성 알림 | 300 / 400 |
| 삭제 알림 | 100 |

### 부하 시나리오

| 단계 | VU | 시간 | 결과 집계 |
| --- | ---: | ---: | --- |
| Warm-up | 0 → 10 | 60초 | 제외 |
| Baseline | 10 고정 | 5분 | 포함 |
| Cool-down | 10 → 0 | 30초 | 제외 |

| Endpoint | 요청 비율 |
| --- | ---: |
| `members_me` | 15% |
| `user_cards_list` | 30% |
| `user_cards_detail` | 25% |
| `notifications_list` | 30% |

- 각 반복은 요청 하나를 선택한 뒤 0.5~1.5초 대기합니다.
- 알림 목록은 page 0/1/2를 80%/15%/5%로 선택하며 size는 10입니다.
- Setup에서 20개 계정에 로그인하고 활성 카드 ID를 조회합니다. Setup, Warm-up, Cool-down은 결과 집계에서 제외합니다.
- Baseline checks 100%, `http_req_failed` 1% 미만인 회차만 유효합니다.
- Access Token은 재발급하지 않습니다. 전체 부하 시간이 10분 이상이거나 Setup 후 Token의 남은 수명이 부족하면 측정을 중단합니다.

기준선과 비교할 때는 동일한 하드웨어, 데이터와 Seed, 부하 시나리오, k6 버전 및 3회 중앙값 산정 절차를 유지합니다. 변경된 JDK·JVM·프레임워크 환경은 결과와 함께 기록하며, 성능 차이를 특정 기술의 영향으로 단정하지 않습니다.

## 측정 방법

모든 명령은 저장소 루트에서 실행합니다.

### 1. 실행 준비

- PowerShell, Docker CLI, k6 2.2.0
- 실행 가능한 Spring Boot Backend
- Docker MySQL 8.0 및 Redis 7.2

Backend 빌드 및 인프라 구성은 [Backend 실행 가이드](../backend/README.md)를 참고합니다.

MySQL에는 Flyway V1과 `backend/db/data.sql`, `backend/db/91_seed_card_benefit.sql`이 적용되어 있어야 합니다. 데이터 초기화 대상 DB와 Backend가 사용하는 DB는 동일해야 합니다.

### 2. 환경변수 설정

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `DB_USER` | 필수 입력 | MySQL 계정 |
| `DB_PASSWORD` | 필수 입력 | MySQL 비밀번호 |
| `PERF_PASSWORD_HASH` | 필수 입력 | 성능 계정의 BCrypt Hash |
| `PERF_PASSWORD` | 필수 입력 | 성능 계정 로그인 비밀번호 |
| `DB_CONTAINER` | `yigo-mysql` | MySQL 컨테이너 |
| `DB_NAME` | `wallet` | 측정 대상 DB |
| `BASE_URL` | `http://localhost:8080` | Backend 주소 |
| `PERF_EMAILS` | 미지정 | 성능 계정 이메일 목록 (쉼표 구분) |
| `PERF_ACCOUNT_COUNT` | `20` | `PERF_EMAILS` 미지정 시 계정 수 |
| `BASELINE_VUS` | `10` | Warm-up 목표 및 Baseline VU |
| `BASELINE_DURATION` | `5m` | Baseline 시간 |

```powershell
$env:DB_USER = '<MySQL 사용자>'
$env:DB_PASSWORD = '<MySQL 비밀번호>'
$env:PERF_PASSWORD = '<성능 계정 비밀번호>'
$env:PERF_PASSWORD_HASH = '<대응하는 60자 BCrypt Hash>'
```

비밀번호는 환경변수로 전달하고 저장소에 기록하지 않습니다. 스크립트는 `.env`를 자동으로 읽지 않습니다.

기준선 비교 시 `PERF_EMAILS`는 지정하지 않고 계정 수 20개, `BASELINE_VUS=10`, `BASELINE_DURATION=5m`을 유지합니다.

### 3. 데이터 준비 및 Smoke 테스트

Backend를 실행하고 `BASE_URL`이 실제 주소와 일치하는지 확인합니다.

성능 데이터를 초기화합니다.

```powershell
.\performance\reset-perf-data.ps1
```

초기화에 성공하면 Smoke 테스트를 실행합니다.

```powershell
k6 run performance/k6/smoke.js
```

첫 번째 성능 계정으로 로그인한 뒤 1 VU가 회원정보, 카드 목록·상세, 알림 목록을 한 번씩 조회합니다.

모든 check가 성공하고 `http_req_failed`가 0인 경우에만 본 측정을 진행합니다.

### 4. 동일 조건 3회 측정

각 회차 직전에 성능 데이터를 초기화합니다. 기존 결과를 보호하기 위해 동일한 파일이 있으면 측정을 시작하지 않습니다.

```powershell
New-Item -ItemType Directory -Force performance/results/raw | Out-Null

foreach ($i in 1..3) {
  $run = $i.ToString('00')
  $path = "performance/results/raw/boot4-authenticated-read-$run"
  if ((Test-Path "$path.json") -or (Test-Path "$path.txt")) {
    throw "기존 측정 결과가 있습니다: $path"
  }
}

foreach ($i in 1..3) {
  $run = $i.ToString('00')
  $path = "performance/results/raw/boot4-authenticated-read-$run"

  try {
    .\performance\reset-perf-data.ps1
  } catch {
    throw "성능 데이터 초기화 실패(run $run): $($_.Exception.Message)"
  }

  k6 run --summary-export "$path.json" `
    performance/k6/authenticated-read.js 2>&1 |
    Tee-Object "$path.txt"

  $k6ExitCode = $LASTEXITCODE
  if ($k6ExitCode -ne 0) { throw "k6 run $run failed: $k6ExitCode" }
}
```

데이터 초기화 또는 k6 실행에 실패하면 측정을 중단합니다. 실패한 원본 결과는 별도로 보관하고 원인을 해결한 뒤 3회 측정을 다시 진행합니다.

원본 JSON·텍스트는 `performance/results/raw/`에 저장하며 Git에서 제외합니다. 유효한 세 회차의 Endpoint별 응답 시간과 전체 처리량의 중앙값 및 최소~최대 범위를 기록합니다.

### 5. 테스트 데이터 정리

데이터 초기화 및 정리는 성능 계정과 관련 데이터만 대상으로 합니다.

초기화에 실패하면 기존 성능 데이터가 삭제된 상태일 수 있으므로, 원인을 해결한 뒤 초기화를 다시 실행합니다.

측정 후 성능 데이터는 자동으로 삭제되지 않습니다.

<details>
<summary>성능 테스트 데이터 수동 정리</summary>

정리할 MySQL 컨테이너와 DB 이름을 확인한 뒤 실행합니다.

```powershell
$perfContainer = if ($env:DB_CONTAINER) { $env:DB_CONTAINER } else { 'yigo-mysql' }
$perfDatabase = if ($env:DB_NAME) { $env:DB_NAME } else { 'wallet' }

docker cp .\performance\perf_cleanup.sql "${perfContainer}:/tmp/perf_cleanup.sql"
if ($LASTEXITCODE -ne 0) { throw 'cleanup SQL 복사 실패' }

docker exec -i -e "MYSQL_PWD=$env:DB_PASSWORD" $perfContainer `
  mysql "-u$env:DB_USER" --default-character-set=utf8mb4 $perfDatabase `
  -e "source /tmp/perf_cleanup.sql"
if ($LASTEXITCODE -ne 0) { throw '성능 테스트 데이터 정리 실패' }
```

성공 시 `targeted_accounts=20`(고정 정리 대상 수), `remaining_accounts=0`을 반환합니다.

</details>



## 측정 결과

### Legacy WAR 기준선

2026-10-20에 동일 시나리오를 세 번 실행한 결과입니다. 괄호 안은 세 회차의 최소~최대 범위이며, VU 10은 전체 부하에 적용된 값입니다.

#### 측정 환경

| 항목 | 환경 |
| --- | --- |
| 호스트 | Windows 11 |
| CPU / 메모리 | Intel Core i5-10400F (6코어 12스레드) / 15.9 GiB |
| 실행 구성 | k6·Tomcat은 호스트, MySQL·Redis는 Docker (동일 PC) |
| Backend | Apache Tomcat 9.0.119 / Oracle OpenJDK 17 |
| JVM | G1 GC, 자동 선택 Heap 256 MiB 초기 / 4,078 MiB |
| Docker Engine | 12 CPU / 7.7 GiB |
| MySQL / Redis | MySQL 8.0.46 / `redis:7.2-alpine` |
| Redis | `redis:7.2-alpine` |
| Hikari Pool | 라이브러리 기본값 사용 |
| k6 | 2.2.0 |


MySQL과 Redis는 컨테이너별 자원 제한 없이 Docker Engine 자원을 공유했습니다.

고정 데이터 검증 및 Smoke 테스트 성공 후 측정했으며, 측정 당시 CPU·메모리 사용량은 기록하지 않았습니다.

#### Endpoint별 응답 시간 (3회 중앙값)

| Endpoint | p50 | p95 | p99 | 실패율 |
| --- | ---: | ---: | ---: | ---: |
| `members_me` | 8.05ms (7.99~8.63) | 10.56ms (10.17~10.67) | 16.36ms (12.35~17.71) | 0% |
| `user_cards_list` | 8.24ms (8.20~8.71) | 10.91ms (10.81~10.94) | 19.55ms (14.76~19.60) | 0% |
| `user_cards_detail` | 8.15ms (8.11~8.65) | 10.43ms (10.40~10.81) | 19.94ms (19.57~20.94) | 0% |
| `notifications_list` | 8.22ms (8.16~8.76) | 10.91ms (10.55~11.25) | 14.23ms (14.21~21.70) | 0% |

#### 회차별 전체 처리량

| 회차 | Baseline 요청 수 | 처리량 | 실패 요청 | Checks |
| --- | ---: | ---: | ---: | ---: |
| 1 | 2,984 | 9.95 req/s | 0 | 100% |
| 2 | 2,996 | 9.99 req/s | 0 | 100% |
| 3 | 2,943 | 9.81 req/s | 0 | 100% |
| 중앙값 | 2,984 | 9.95 req/s | 0 | 100% |

세 회차 모두 유효 측정 조건을 충족했습니다. 처리량은 10 VU와 Think time이 적용된 300초 동안의 측정값이며 서버의 최대 처리량이 아닙니다.

Endpoint별 요청 수는 회차마다 달라 개별 Endpoint 처리량은 기준값으로 사용하지 않습니다. 응답 시간은 중앙값과 범위를 함께 비교합니다.

### Spring Boot

미측정 — 동일 조건 재측정 후 결과 추가 예정입니다.