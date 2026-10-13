# Legacy API Newman baseline

현재 Spring Legacy API 동작을 변경하지 않고 고정하는 로컬 Newman 회귀 테스트다. 애플리케이션 소스, DB 스키마, MyBatis Mapper는 수정하지 않는다.

## 범위

- 회원가입, 로그인, Access Token 인증, Token 재발급, 로그아웃
- 회원 조회·수정
- 카드 등록·목록·상세·삭제 및 대표카드
- 알림 목록·읽음·삭제
- 대표 실패 응답: 인증 누락, 잘못된 로그인, 존재하지 않는 카드·알림
- 현재 레거시 Refresh Token 비회전·재사용 동작
- 현재 레거시 Health 인증 동작

정지·탈퇴 회원과 만료 Access Token은 이번 범위에 포함하지 않는다.

## 레거시로 고정하는 동작

- `[legacy] Health - 토큰 없음 401`: `/api/health`가 JWT 필터의 공개 경로가 아니므로 `ACCESS_TOKEN_INVALID`를 반환한다.
- `[legacy] Health - 토큰 있음 200`: 유효한 Access Token으로 `data: "ok"`를 반환한다.
- `[legacy] 토큰 재발급 - Refresh Token 비회전`: 재발급 응답에 `Set-Cookie`가 없다.
- `[legacy] 토큰 재발급 - 동일 Refresh Token 재사용`: 같은 Refresh Token으로 다시 재발급할 수 있다.

## 파일

```text
api-test/
├─ collections/legacy-api-regression.postman_collection.json
├─ environments/default.postman_environment.json
├─ fixtures/init.sql
├─ fixtures/cleanup.sql
├─ scripts/run.ps1
├─ package.json
└─ .gitignore
```

## 전제 조건

- Backend와 MySQL이 이미 실행 중이어야 한다.
- MySQL은 저장소의 Docker Compose 컨테이너를 사용한다. 기본 컨테이너 이름은 `yigo-mysql`이다.
- 호스트의 로컬 `mysql` CLI는 사용하지 않는다.
- 대상 DB에는 현재 유효한 `SIGNUP` 약관과 활성 `mock_card`가 최소 1개 있어야 한다.
- Node.js, npm, Docker CLI가 필요하다.

최초 한 번 Newman을 설치한다. 저장소의 lockfile 기준으로 동일한 의존성을 설치하려면 `npm ci`를 사용한다.

```powershell
cd api-test
npm ci
```

`node_modules/`는 커밋하지 않고 `package-lock.json`은 재현 가능한 Newman 버전을 위해 함께 관리한다.

## 환경변수

DB 비밀번호는 Postman environment나 저장소 파일에 넣지 않는다.

```powershell
$env:API_TEST_DB_PASSWORD = '<local mysql password>'
```

선택 설정:

| 환경변수 | 기본값 |
|---|---|
| `API_TEST_BASE_URL` | `http://localhost:8080` |
| `API_TEST_DB_CONTAINER` | `yigo-mysql` |
| `API_TEST_DB_NAME` | `wallet` |
| `API_TEST_DB_USER` | `root` |

## 실행

```powershell
cd api-test
npm test
```

로그인처럼 이후 요청 전체의 전제 조건인 요청이 실패하면 collection은 즉시 중단한다. 빈 Access Token과 빈 동적 ID로 나머지 요청을 실행해 같은 원인의 401을 반복 보고하지 않는다.

실행기는 다음 순서를 보장한다.

1. `docker exec -i yigo-mysql mysql ...` 방식으로 실제 DB 이름을 확인한다.
2. 실행별 12자리 `runId`를 만든다.
3. `init.sql`로 해당 실행 전용 회원·인증·알림 fixture만 생성한다.
4. 활성 `mock_card` 번호를 읽어 Newman runtime 변수로 넘긴다.
5. Newman collection을 실행한다.
6. 성공·실패와 무관하게 PowerShell `finally`에서 `cleanup.sql`을 실행한다.

## 데이터 안전성

- 테스트 이메일은 `newman-<용도>-<runId>@example.invalid` 형식이다.
- 알림 deduplication key와 제목에도 같은 `runId`를 넣는다.
- fixture는 공유 약관과 카드 마스터를 추가·수정·삭제하지 않는다.
- 카드 등록 테스트는 기존 활성 `mock_card`를 읽기 전용으로 사용하고 테스트 회원의 `user_card`만 생성한다.
- cleanup은 현재 실행의 정확한 이메일로 찾은 행만 FK 역순으로 삭제한다.
- `TRUNCATE`, 스키마 변경, AUTO_INCREMENT 초기화, 일반 사용자 데이터 삭제를 하지 않는다.
- Access Token, Refresh Token, 생성 ID는 런타임 collection variable에만 보관하며 environment를 export하지 않는다.

중간에 PowerShell 프로세스 자체가 강제 종료되어 `finally`가 실행되지 못한 경우에는 그 실행의 `runId`를 확인한 뒤 다음처럼 cleanup만 수동 실행할 수 있다. SQL을 직접 실행할 때도 반드시 같은 `runId`를 `@api_test_run_id`에 지정해야 한다.

## assertion 원칙

- ID는 양수 숫자인지 확인하고 이후 요청에서 캡처한 값을 사용한다.
- JWT는 값 비교 대신 세 개의 Base64URL segment 형식과 존재 여부를 검사한다.
- 현재 레거시 MVC는 `LocalDateTime`을 ISO 문자열이 아니라 `[year, month, day, hour, minute, second?, nano?]` 배열로 직렬화한다. 시각 값 자체는 고정하지 않고 5~7개의 정수 배열 형식만 검사한다.
- 204 응답은 빈 본문을 검사한다.
- 확인된 상태 코드와 오류 코드는 정확히 검사한다.
- 실행마다 달라지는 Token, ID, 시각 및 수치 기록은 저장하지 않는다.

## 문제 진단

Newman 요약만으로는 최초 실패와 연쇄 실패를 구분하기 어렵다. 먼저 CLI 출력에서 가장 앞선 실패 요청을 확인하고, 로컬 Tomcat의 access/error log와 대조한다.

`curl.exe -i http://localhost:8080/api/auth/signup`은 GET 요청이다. 이 저장소의 회원가입 endpoint는 POST 전용이며, 현재 전역 예외 처리기는 지원하지 않는 HTTP method 예외를 `SERVER_INTERNAL_ERROR` 500으로 변환한다. 회원가입 자체를 확인하려면 반드시 POST와 JSON 본문을 사용해야 한다.

```powershell
curl.exe -i -X POST `
  -H "Content-Type: application/json" `
  --data '{"email":"...","password":"Password123!","name":"...","signupVerificationToken":"...","termsAgreements":[...]}' `
  http://localhost:8080/api/auth/signup
```

fixture의 회원 로그인 비밀번호는 `password`다. `init.sql`에는 이 값과 일치하는 BCrypt 해시만 저장한다.
