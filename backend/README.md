# backend

Spring Boot 3.5 + 기존 Spring XML 설정 + MyBatis + MySQL, Maven 실행 JAR 배포.

> 초안. 팀 합의에 따라 PR로 수정한다.

## 필요 버전

- JDK 21 / MySQL 8.0

## 설정 파일의 역할

- `src/main/resources/db.properties`: Spring이 읽는 DB, SMTP, CORS, 챗봇 설정이다. `db.properties.example`를 복사해 만들고 커밋하지 않는다.
- `src/main/resources/redis.properties`: Spring이 읽는 Redis 연결 및 풀 설정이다. `redis.properties.example`를 복사해 만들고 커밋하지 않는다.
- `.env`: Docker Compose가 MySQL, Redis, backend 컨테이너에 값을 전달할 때만 사용한다. Spring Boot는 이 파일을 자동으로 읽지 않는다.

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
