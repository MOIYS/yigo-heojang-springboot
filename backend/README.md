# backend

Spring Boot 3.5 + Java Config + MyBatis + MySQL, Maven 실행 JAR 배포.

> 초안. 팀 합의에 따라 PR로 수정한다.

## 필요 버전

- JDK 21 / MySQL 8.0

## 설정 파일의 역할

- `src/main/resources/db.properties`: Spring이 읽는 DB, SMTP, CORS, 챗봇 설정이다. `db.properties.example`를 복사해 만들고 커밋하지 않는다.
- `src/main/resources/redis.properties`: Spring이 읽는 Redis 연결 및 풀 설정이다. `redis.properties.example`를 복사해 만들고 커밋하지 않는다.
- `.env`: Docker Compose가 MySQL, Redis, backend 컨테이너에 값을 전달할 때만 사용한다. Spring Boot는 이 파일을 자동으로 읽지 않는다.

`com.wallet.boot.RootConfiguration`이 기존 이름의 인프라 Bean과 컨트롤러·부트 설정을 제외한 애플리케이션 컴포넌트를 등록하고,
`WebMvcConfiguration`이 `@EnableWebMvc` 기반으로 컨트롤러, JSON 변환, 경로 매칭, CORS를 구성한다.
설정은 단일 `PropertySourcesPlaceholderConfigurer`가 `db.properties`, `redis.properties`,
`classpath*:jwt.properties` 순서로 읽는다. 외부 Environment 설정은 파일보다 우선한다.
Boot Config Data import로 전환하지 않으며 필수 JWT Secret 누락 시 기동에 실패한다.

Bean 목록 diff와 실행 검증 내역은 [Java Config 이관 결과](docs/java-config-migration.md)에 기록했다.

## Flyway 초기화

Flyway 11은 애플리케이션 기동 시 `src/main/resources/db/migration`의 버전 마이그레이션을 실행한다.
V1은 빈 `wallet` DB만 대상으로 하며 기존 수동 생성 DB를 baseline으로 등록하지 않는다.
`baseline-on-migrate`를 활성화하지 않는다. `db/` 아래의 seed SQL은 Flyway 대상이 아니며 계속 수동으로 적재한다.

기존 Docker DB를 V1부터 다시 만들 때는 필요한 데이터를 먼저 별도로 백업한다. 다음 초기화는 되돌릴 수 없으므로
Codex가 대신 실행하지 않으며 사용자가 본인 터미널에서 직접 수행한다.

```powershell
cd backend
docker compose stop backend
docker exec yigo-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE IF EXISTS wallet; CREATE DATABASE wallet CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"'
```

초기화 후 새 JAR로 backend를 기동하면 Flyway가 V1을 적용한다.

```powershell
./mvnw.cmd clean package
docker compose up -d --force-recreate backend
docker logs yigo-backend
```

다음 읽기 전용 명령으로 V1 기록과 핵심 구조를 확인한다.

```powershell
docker exec yigo-mysql sh -c 'mysql -N -B -uroot -p"$MYSQL_ROOT_PASSWORD" wallet -e "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank; SELECT COUNT(*) AS table_count FROM information_schema.tables WHERE table_schema=''wallet'' AND table_type=''BASE TABLE''; SELECT COUNT(*) AS foreign_key_count FROM information_schema.referential_constraints WHERE constraint_schema=''wallet'';"'
```

기대 결과는 성공한 V1 한 건, 애플리케이션 테이블 47개에 `flyway_schema_history`를 더한 총 48개,
외래 키 71개다. 이후 루트 README의 순서대로 seed SQL을 수동 적재한다.

## 로컬 실행 JAR 실행

PowerShell에서 예제 설정을 복사하고 로컬 환경에 맞는 값을 입력한다.

```powershell
cd backend
Copy-Item src/main/resources/db.properties.example src/main/resources/db.properties
Copy-Item src/main/resources/redis.properties.example src/main/resources/redis.properties
```

JWT Secret은 Access Token과 Refresh Token에 서로 다른 32바이트 이상의 값을 사용한다. 다음 환경변수를 설정한 PowerShell에서 빌드하고 실행한다.

```powershell
$env:JWT_ACCESS_TOKEN_SECRET = '<32바이트 이상의 로컬 Access Token Secret>'
$env:JWT_REFRESH_TOKEN_SECRET = '<32바이트 이상의 별도 Refresh Token Secret>'
./mvnw.cmd clean package
java -jar ./target/yigo-heojang-backend.jar
```

서버가 기동되면 인증이 필요 없는 약관 API로 확인한다. `/api/health`는 기존 인증 정책상 공개 경로가 아니다.

```powershell
curl.exe http://localhost:8080/api/terms
```

테스트는 `src/test/resources/jwt.properties`의 테스트 전용 값을 사용한다.

## Docker Compose 실행

Docker Compose는 `backend/.env`에서 다음 필수 변수를 읽는다.

| 환경변수 | 사용처 |
| --- | --- |
| `MYSQL_ROOT_PASSWORD` | MySQL root 계정 |
| `REDIS_PASSWORD` | Redis `requirepass` |
| `JWT_ACCESS_TOKEN_SECRET` | backend의 Access Token 서명 |
| `JWT_REFRESH_TOKEN_SECRET` | backend의 Refresh Token 서명 |

실제 값을 저장소에 기록하지 말고 개인 `.env`를 만든다.

```powershell
cd backend
Copy-Item .env.example .env
```

Compose 실행 전 `db.properties`의 DB 호스트를 `mysql`, 비밀번호를 `.env`의 `MYSQL_ROOT_PASSWORD`와 같게 설정한다. `redis.properties`의 호스트는 `redis`, 비밀번호는 `.env`의 `REDIS_PASSWORD`와 같게 설정한다. 챗봇 주소는 `http://chatbot:8000`으로 설정한다. 이 파일들은 JAR에 포함되므로 값을 바꾼 뒤 다시 빌드해야 한다.

```powershell
./mvnw.cmd clean package
docker compose up -d
curl.exe http://localhost:8080/api/terms
```

JAR는 단일 파일 bind mount이므로 재빌드할 때 backend 컨테이너를 재생성해 새 파일을 확실히 연결한다.

```powershell
docker compose stop backend
./mvnw.cmd clean package
docker compose up -d --force-recreate backend
```

챗봇 컨테이너에는 별도로 커밋하지 않는 `../ai-server/.env`가 필요하다.

## Secret 관리

- `.env`, `db.properties`, `redis.properties`에는 로컬 또는 배포 환경의 실제 값만 두고 커밋하지 않는다.
- `.env.example`과 `*.properties.example`에는 변수명과 자리표시자만 둔다.
- Access Token과 Refresh Token Secret은 서로 다르게 설정하고 최소 32바이트를 사용한다.
- 터미널 출력, README, 명령 인수, 테스트 결과에 비밀번호나 Token Secret을 남기지 않는다.
- Secret이 노출되면 저장소에서 문자열만 지우는 것으로 끝내지 말고 해당 값을 즉시 교체한다.
