# Spring Boot 전환 전 Legacy WAR 성능 기준선

Issue #11의 Spring 전환 전 Legacy WAR 읽기 API 기준선입니다. 운영 DB가 아닌 격리된 로컬 MySQL 8.0에서만 실행합니다.

이 문서에는 재현에 필요한 실행 순서, 고정 조건, 커밋할 요약 결과만 둡니다. 원본 k6 출력은 `performance/results/raw/`에 로컬 보관하며 Git에 포함하지 않습니다.

## 처음부터 끝까지 실행

모든 명령은 저장소 루트에서 실행합니다. 기존 k6 스크립트의 VU, 실행 시간, 요청 비율과 threshold를 변경하지 않습니다.

### 1. 사전 조건

- k6가 PATH에 설치되어 있어야 합니다.
- Spring Boot 실행 JAR가 `http://localhost:8080`에서 실행 중이어야 합니다.
- MySQL 8.0에 Flyway V1(`backend/src/main/resources/db/migration/V1__init_schema.sql`)과
  `backend/db/data.sql`, `91_seed_card_benefit.sql`이 적용되어 있어야 합니다.
- MySQL은 운영 데이터와 분리된 성능 측정용 DB여야 합니다.
- 성능 계정 20개가 함께 사용할 로컬 전용 비밀번호를 준비합니다.

비밀번호 원문과 DB 비밀번호는 저장소 파일이나 명령 인수에 기록하지 않습니다. 성능 계정용 BCrypt 해시는 cost 10 이상의 60자 값으로 준비합니다.

```powershell
$env:DB_USER = '<로컬 MySQL 사용자>'
$env:DB_PASSWORD = '<로컬 MySQL 비밀번호>'
$env:PERF_PASSWORD_HASH = '<성능 계정 비밀번호의 BCrypt 해시>'
$env:PERF_PASSWORD = '<성능 계정 비밀번호 원문>'
$env:BASE_URL = 'http://localhost:8080'
```

`DB_USER`, `DB_PASSWORD`, `PERF_PASSWORD_HASH`는 데이터 초기화 스크립트가 사용하고, `PERF_PASSWORD`, `BASE_URL`은 k6가 사용합니다. PowerShell 스크립트와 k6는 `.env` 파일을 자동으로 읽지 않습니다.

데이터 초기화 스크립트의 전체 환경변수는 다음과 같습니다.

| 환경 변수 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `DB_USER` | 예 | 없음 | MySQL 계정 |
| `DB_PASSWORD` | 예 | 없음 | MySQL 비밀번호 |
| `PERF_PASSWORD_HASH` | 예 | 없음 | 성능 계정에 저장할 BCrypt 해시 |
| `DB_CONTAINER` | 아니요 | `yigo-mysql` | MySQL Docker 컨테이너 이름 |
| `DB_NAME` | 아니요 | `wallet` | 성능 데이터를 초기화할 DB 이름 |

### 2. 데이터 초기화

다음 명령은 `yigo-mysql` 컨테이너의 `wallet` DB에서 기존 성능 계정만 정리하고 다시 생성합니다.

```powershell
.\performance\reset-perf-data.ps1
```

환경변수 대신 명령 파라미터를 사용할 수도 있습니다. 비밀번호는 명령 기록에 남을 수 있으므로 `DB_PASSWORD` 환경변수 사용을 권장합니다.

```powershell
.\performance\reset-perf-data.ps1 `
  -ContainerName yigo-mysql `
  -Database wallet `
  -MySqlUser root
```

스크립트가 기대하는 데이터는 다음과 같습니다. 적용 중 자체 검증에 실패하면 트랜잭션을 롤백합니다.

| 데이터 | 기대 건수 |
| --- | ---: |
| 활성 회원 | 20 |
| 활성 보유 카드 / 대표 카드 | 100 / 20 |
| 활성 알림 | 700 |
| 읽음 / 안 읽음 활성 알림 | 300 / 400 |
| 삭제 알림 | 100 |

### 3. Spring Boot 실행 JAR 기동

`backend/target/yigo-heojang-backend.jar`를 `java -jar` 또는 Docker Compose의 `backend` 서비스로 기동합니다. k6 실행 전에 `BASE_URL`이 실제 기동 주소와 같은지만 확인합니다.

### 4. smoke 확인

```powershell
k6 run performance/k6/smoke.js
```

1 VU가 로그인 후 회원 정보, 카드 목록, 카드 상세, 알림 목록을 한 번씩 호출합니다. 모든 check가 성공하고 `http_req_failed`가 0이어야 다음 단계로 진행합니다.

### 5. 기준선 3회 측정

원본 저장 디렉터리를 만든 뒤 동일 명령을 3회 실행합니다. 각 회차 직전에 성능 데이터를 다시 초기화합니다. 스크립트 내부의 60초 warm-up을 사용하며 별도의 폐기 회차는 두지 않습니다.

```powershell
New-Item -ItemType Directory -Force performance/results/raw | Out-Null

1..3 | ForEach-Object {
  $run = $_.ToString('00')
  .\performance\reset-perf-data.ps1

  k6 run `
    --summary-export "performance/results/raw/legacy-authenticated-read-$run.json" `
    performance/k6/authenticated-read.js 2>&1 |
    Tee-Object "performance/results/raw/legacy-authenticated-read-$run.txt"

  if ($LASTEXITCODE -ne 0) { throw "k6 baseline run $run failed" }
}
```

유효한 회차는 baseline 구간의 checks가 100%이고 `http_req_failed`가 1% 미만이어야 합니다. 세 회차의 endpoint별 p50/p95/p99와 전체 처리량 중앙값을 README에 기록합니다.

### 6. 종료 확인

```powershell
git status --short
git diff --name-only
git ls-files --others --exclude-standard
```

`performance/results/raw/` 파일이 Git 대상에 나타나지 않아야 합니다. 측정 종료 후 데이터 정리가 필요하면 `perf_cleanup.sql`을 사용합니다.

### 6-1. 테스트 데이터 정리
k6 테스트 종료 후에도 성능 측정용 계정과 관련 데이터는 MySQL에 유지됩니다. reset-perf-data.ps1은 각 측정 회차 시작 전에 데이터를 초기화하며, 마지막 측정 후 자동 정리는 수행하지 않습니다.

테스트 데이터 삭제가 필요한 경우 perf_cleanup.sql을 별도로 실행합니다. 해당 SQL은 성능 계정과 직접 의존 데이터만 삭제하도록 설계되어 있습니다.

## 고정 측정 설정

`authenticated-read.js`의 기본 프로파일입니다.

| 단계 | VU | 시간 | 결과 집계 |
| --- | ---: | ---: | --- |
| warm-up | 0 → 10 | 60초 | 제외 |
| baseline | 10 고정 | 5분 | 포함 |
| cool-down | 10 → 0 | 30초 | 제외 |

각 반복은 요청 하나를 선택한 뒤 0.5~1.5초 대기합니다.

| endpoint | 요청 비율 |
| --- | ---: |
| `members_me` | 15% |
| `user_cards_list` | 30% |
| `user_cards_detail` | 25% |
| `notifications_list` | 30% |

알림 목록은 page 0/1/2를 80%/15%/5%로 선택하고 size는 10으로 고정합니다. setup에서 20개 계정에 로그인하고 실제 활성 카드 ID를 조회합니다. 로그인 요청과 warm-up/cool-down 요청은 기준선 결과에서 제외합니다.

| 환경 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:8080` | 대상 Spring Boot 백엔드 주소 |
| `PERF_PASSWORD` | 없음 | 필수 성능 계정 비밀번호 |
| `PERF_EMAILS` | 시드 계정 20개 | 쉼표로 구분한 계정 목록 |
| `PERF_ACCOUNT_COUNT` | `20` | 사용할 기본 시드 계정 수 |
| `BASELINE_VUS` | `10` | warm-up 목표 및 baseline VU |
| `BASELINE_DURATION` | `5m` | baseline 길이 |

Access Token 재발급은 수행하지 않습니다. 전체 부하 구간이 10분 이상이거나 로그인 후 남은 토큰 수명이 부족하면 스크립트가 시작 전에 실패합니다.

## Legacy WAR 기준선 결과

동일 명령 3회의 실제 측정 결과입니다. 괄호 안은 최소~최대이며 VU 10은 endpoint별 값이 아니라 전체 부하에 적용된 값입니다.

### 실행 환경

환경 확인일은 2026-10-20입니다. 실행 중인 Legacy WAR 프로세스와 Docker 컨테이너에서 확인한 값입니다.

| 항목 | 확인값                                                                                               |
| --- |---------------------------------------------------------------------------------------------------|
| 호스트 | Windows 11 Home 64-bit, 10.0.26200                                                                |
| CPU / 메모리 | Intel Core i5-10400F, 6코어 12스레드 / 15.9 GiB                                                        |
| 배치 | k6와 Tomcat은 호스트, MySQL과 Redis는 Docker; 모두 같은 PC 사용                                                |
| Tomcat | Apache Tomcat 9.0.119, Windows 로컬 프로세스                                                            |
| JDK | Oracle OpenJDK 17                                                         |
| JVM | 별도 `setenv.bat` 및 명시적 `-Xms`/`-Xmx` 없음; G1 GC, 자동 선택 heap 256 MiB 초기 / 4,078 MiB 최대, `Asia/Seoul` |
| Docker Engine | 12 CPU / 7.7 GiB 사용 가능                                                                            |
| MySQL | 8.0.46, `mysql:8.0`, 컨테이너별 CPU/메모리 제한 없음                                                          |
| Redis | `redis:7.2-alpine`, 컨테이너별 CPU/메모리 제한 없음                                                           |
| k6 | 2.2.0, windows/amd64                                                                              |
| seed 검증 | `20 / 100 / 20 / 700 / 300 / 400 / 100` 확인                                                        |
| Hikari | 명시적 pool 설정 없음; 라이브러리 기본값 사용                                                                      |
| smoke | 성공 확인                                                                                             |

MySQL과 Redis는 별도 자원 제한 없이 Docker Engine의 자원을 공유합니다. 측정 당시 CPU·메모리 사용량과 백그라운드 작업 상태를 기록하지 않았으므로, 해당 요인이 성능에 미친 영향은 확인할 수 없습니다.

### 3회 중앙값

| endpoint | p50 | p95 | p99 | 실패율 |
| --- | ---: | ---: | ---: | ---: |
| `members_me` | 8.05ms (7.99~8.63) | 10.56ms (10.17~10.67) | 16.36ms (12.35~17.71) | 0% |
| `user_cards_list` | 8.24ms (8.20~8.71) | 10.91ms (10.81~10.94) | 19.55ms (14.76~19.60) | 0% |
| `user_cards_detail` | 8.15ms (8.11~8.65) | 10.43ms (10.40~10.81) | 19.94ms (19.57~20.94) | 0% |
| `notifications_list` | 8.22ms (8.16~8.76) | 10.91ms (10.55~11.25) | 14.23ms (14.21~21.70) | 0% |

### 전체 처리량

각 회차의 baseline은 300초입니다. 처리량은 think time과 10 VU가 적용된 고정 부하의 값이며 서버의 최대 처리량이 아닙니다.

| 회차 | baseline 요청 수 | 처리량 | 실패 요청 | checks |
| --- | ---: | ---: | ---: | ---: |
| 1 | 2,984 | 9.95 req/s | 0 | 100% |
| 2 | 2,996 | 9.99 req/s | 0 | 100% |
| 3 | 2,943 | 9.81 req/s | 0 | 100% |
| 중앙값 | 2,984 | 9.95 req/s | 0 | 100% |

### 해석 범위

- 세 회차 모두 유효 측정 조건을 만족했습니다.
- endpoint별 요청 수가 회차마다 달라 endpoint별 처리량은 기준값으로 확정하지 않습니다.
- p99는 p50/p95보다 회차별 범위가 크므로 중앙값과 범위를 함께 봅니다.
- 같은 스크립트, 데이터, 부하, 실행 환경과 3회 중앙값 절차를 사용한 결과만 이 기준선과 비교할 수 있습니다.
- 이 문서는 성능 변화의 원인을 판단하지 않습니다.

## 파일 역할

| 파일 | 역할                                 |
| --- |------------------------------------|
| `perf_cleanup.sql` | 성능 계정과 직접 의존 데이터만 제거               |
| `perf_seed.sql` | 고정 성능 데이터 생성 및 건수 검증               |
| `reset-perf-data.ps1` | Docker MySQL에서 cleanup과 seed 순차 실행 |
| `k6/smoke.js` | 1 VU 기능 확인                         |
| `k6/authenticated-read.js` | 3단계 인증 읽기 부하 측정 (warm-up → baseline → cool-down)                   |
| `k6/config.js` | VU, duration, threshold 정의         |

`perf_seed.sql`은 일반 회원, 카드 마스터, 기존 시드와 AUTO_INCREMENT 값을 변경하지 않습니다. 성능 계정 외 데이터까지 삭제해야 하는 의존 관계가 생기면 범위를 확대하지 않고 실패합니다.
