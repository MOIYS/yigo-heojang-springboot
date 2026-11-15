# Backend

백엔드는 Java 21과 Spring Boot 4.1.2 기반의 REST API 서버입니다. Spring MVC와 Jackson 3를 사용하며 기존 JSON 응답 형식을 유지하도록 호환 설정을 적용했습니다.

## 요구 환경

- JDK 21
- MySQL 8.0
- Redis 7.2 
- Docker 및 Docker Compose (컨테이너 실행 시)

MySQL과 Redis는 Docker Compose로 실행할 수 있으며, Maven은 저장소에 포함된 Maven Wrapper를 사용하므로 별도로 설치할 필요가 없습니다.

## 환경 설정

| 파일 | 역할 |
| --- | --- |
| `src/main/resources/db.properties` | DB, SMTP, CORS, 챗봇 연결 설정 |
| `src/main/resources/redis.properties` | Redis 연결, 커넥션 풀, 캐시·분산 락 TTL 설정 |
| `.env` | Docker Compose가 MySQL, Redis, Backend에 전달하는 값 |
| `src/main/resources/application.yml` | Jackson 호환 설정과 MVC 필터 설정 |

저장소의 예제 파일을 복사한 뒤 로컬 환경에 맞게 값을 변경합니다. `.env`는 Docker Compose를 사용할 때만 필요합니다.

```powershell
cd backend
Copy-Item src/main/resources/db.properties.example src/main/resources/db.properties
Copy-Item src/main/resources/redis.properties.example src/main/resources/redis.properties
Copy-Item .env.example .env
```

Docker Compose는 `.env`에서 다음 값을 읽습니다.

| 환경변수 | 용도 |
| --- | --- |
| `MYSQL_ROOT_PASSWORD` | MySQL root 비밀번호 |
| `REDIS_PASSWORD` | Redis 비밀번호 |
| `JWT_ACCESS_TOKEN_SECRET` | Access Token 서명 키 |
| `JWT_REFRESH_TOKEN_SECRET` | Refresh Token 서명 키 |

두 JWT Secret은 서로 다른 32바이트 이상의 값이어야 합니다. `.env`, `db.properties`, `redis.properties`에는 실제 값을 저장하되 커밋하지 않습니다. Spring Boot는 `.env`를 직접 읽지 않습니다. SMTP 속성은 기동에 필요하며, 실제 SMTP 계정은 이메일 기능을 사용할 때 필요합니다.

로컬 JAR에서는 DB·Redis 호스트로 `localhost`를 사용하고, Docker Compose에서는 각각 `mysql`, `redis`를 사용합니다.


## 실행 방법

### 로컬 실행

로컬 MySQL과 Redis를 실행하고 두 JWT Secret을 현재 PowerShell 프로세스에 설정합니다.

```powershell
$env:JWT_ACCESS_TOKEN_SECRET = '<32바이트 이상의 로컬 Access Token Secret>'
$env:JWT_REFRESH_TOKEN_SECRET = '<32바이트 이상의 별도 Refresh Token Secret>'
.\mvnw.cmd clean package
java -jar .\target\yigo-heojang-backend.jar
```

### Docker Compose 실행

`db.properties`와 `redis.properties`의 호스트와 비밀번호를 Docker 환경에 맞춘 뒤 JAR를 빌드합니다.
Backend만 확인할 때는 MySQL, Redis, Backend 서비스만 실행합니다.

```powershell
.\mvnw.cmd clean package
docker compose up -d mysql redis backend
```

AI Server까지 실행하려면 `chatbot.base-url`을 `http://chatbot:8000`으로 설정하고 `../ai-server/.env`를 준비한 뒤 `docker compose up -d`를 실행합니다. 상세 설정은 이 문서에서 다루지 않습니다.

설정 파일은 JAR에 포함됩니다. 설정을 변경하거나 JAR를 다시 빌드한 경우 Backend 컨테이너를 재생성합니다.

```powershell
docker compose stop backend
.\mvnw.cmd clean package
docker compose up -d --force-recreate backend
```

기동 여부는 인증이 필요 없는 약관 API로 확인합니다.

```powershell
curl.exe http://localhost:8080/api/terms
```

## 데이터베이스 초기화

### Flyway

애플리케이션을 기동하면 Flyway가 `src/main/resources/db/migration`의 V1을 빈 `wallet` DB에 적용합니다.
기존 수동 생성 DB는 자동으로 baseline하지 않으며, Seed SQL은 Flyway 대상이 아닙니다.

### Seed 데이터

전체 시연 데이터는 다음 순서로 적재합니다.

```text
Flyway V1 → data.sql → 91_seed_card_benefit.sql → 92_seed_user_data.sql
          → 93_seed_mock_card.sql → 94_seed_membership_usage_place.sql
```

| 파일 | 역할 |
| --- | --- |
| `data.sql` | 회원·약관·카테고리·포인트 기본 데이터 |
| `91_seed_card_benefit.sql` | 카드·가맹점·실적 구간·혜택 마스터 |
| `92_seed_user_data.sql` | 보유 카드·소비·결제·월별 계산 상태 |
| `93_seed_mock_card.sql` | 카드 등록 시연과 Newman용 가상 카드 번호 |
| `94_seed_membership_usage_place.sql` | 멤버십 사용처와 공식 사이트 |

```powershell
$seedFiles = @('db/data.sql', 'db/91_seed_card_benefit.sql', 'db/92_seed_user_data.sql', 'db/93_seed_mock_card.sql', 'db/94_seed_membership_usage_place.sql')

foreach ($seedFile in $seedFiles) {
  Get-Content -Raw $seedFile |
    docker exec -i yigo-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 wallet'
  if ($LASTEXITCODE -ne 0) { throw "Seed 적재 실패: $seedFile" }
}
```

일반 CI는 `92_seed_user_data.sql`까지, Newman CI는 `93_seed_mock_card.sql`까지 적재합니다.

<details>
<summary>기존 Docker DB를 삭제하고 V1부터 다시 적용하기</summary>

다음 명령은 `wallet` DB의 모든 데이터를 삭제합니다. 필요한 데이터를 먼저 백업하고 대상 컨테이너와 DB 이름을 확인합니다.

```powershell
docker compose stop backend
docker exec yigo-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE IF EXISTS wallet; CREATE DATABASE wallet CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"'
```

이후 일반 실행 절차로 Backend를 기동하면 Flyway V1이 적용됩니다.

</details>

## 테스트

### 단위·통합 테스트와 JaCoCo

통합 테스트는 H2 대신 실제 MySQL 8과 Redis 7.2를 사용합니다. 테스트 데이터는 트랜잭션 롤백 또는 테스트 종료 후 정리로 남기지 않습니다. CI는 빈 MySQL에서 실행하며, 테스트 컨텍스트 기동 시 Flyway V1이 적용됩니다.
JWT는 `src/test/resources/jwt.properties`의 테스트 전용 값을 사용합니다.

```powershell
.\mvnw.cmd -B test
```

JaCoCo 리포트는 `target/site/jacoco/`에 HTML(`index.html`)과 XML(`jacoco.xml`)로 생성되며,
CI에서는 `backend-jacoco-report` Artifact로 업로드합니다.

### 알림 배치 수동 실행

두 배치는 `Asia/Seoul` 기준 매일 09:00(실적 부족)과 09:05(혜택 한도)에 자동 실행됩니다.
실행 시각을 기다리지 않고 알림 생성 흐름을 확인할 수 있도록 `local` 프로파일에서만 즉시 실행 API를 등록합니다.
알림 정책은 [알림 규칙](../docs/notification-rules.md)을 참고합니다.

```powershell
java -jar .\target\yigo-heojang-backend.jar --spring.profiles.active=local
curl.exe -X POST -H "Authorization: Bearer <ACCESS_TOKEN>" `
  http://localhost:8080/api/admin/notifications/batch/performance-shortage/run
```

- 지원 jobName: `performance-shortage`, `benefit-limit`
- 배치는 호출한 회원이 아닌 전체 회원을 대상으로 실행됩니다.
- API의 성공 응답만으로 실제 배치 실행 및 알림 저장 여부를 판단할 수 없습니다. 처리 결과는 서버 로그(`배치 완료 - 후보 N건, 저장 N건`)에서 확인합니다. 
- 실행 중에 같은 배치를 다시 호출하면 Redis 락 때문에 건너뛰며, 완료 후 재호출하면 다시 실행됩니다. 
- 이미 저장된 알림은 중복 저장되지 않습니다.

> **보안 주의**: Access Token은 필요하지만 관리자 권한은 확인하지 않아, 활성 회원이면 누구나 실행할 수 있습니다.
> 공유 개발 서버나 운영 환경에서는 `local` 프로파일을 활성화하지 않습니다.

<details>
<summary>기준일 변경 (실적 부족 배치 확인용)</summary>

실적 부족 배치는 말일 7일 전과 3일 전에만 후보를 계산합니다.
`local` 프로파일에서 JVM 옵션으로 기준일을 옮길 수 있으며, Spring 인수(`--test.clock.date`)로는 적용되지 않습니다.

```powershell
java -Dtest.clock.date=2026-11-23 -jar .\target\yigo-heojang-backend.jar --spring.profiles.active=local
```
기준일은 JVM 옵션(-Dtest.clock.date)으로 지정하며, Spring 실행 인수(--test.clock.date)로는 적용되지 않습니다. 알림 배치, 배치 락, 간편 비밀번호 잠금에 영향을 줍니다.
</details>
