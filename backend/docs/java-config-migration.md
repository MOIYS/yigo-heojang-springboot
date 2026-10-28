# Issue #46 작업 3: XML → Java Config 이관 결과

검증일: 2026-10-27 

## 변경 파일

추가한 운영 설정 클래스:

- `src/main/java/com/wallet/boot/RootConfiguration.java`
- `src/main/java/com/wallet/boot/WebMvcConfiguration.java`

수정한 파일:

- `src/main/java/com/wallet/boot/WalletApplication.java`: XML import 제거, 기존 Boot 스캔과 자동 설정 제외 유지.
- `src/main/java/com/wallet/boot/WebFilterConfiguration.java`: 오래된 설정 설명만 수정.
- `src/main/java/com/wallet/engine/dao/{BenefitMapper,PerformanceMapper,SettlementMapper}.java`: 설정 위치 주석만 수정.
- `src/test/java/com/yigoheojang/testprobe/BootWebRuntimeContractIntegrationTest.java`: 테스트 메서드의 XML 명칭 수정.
- `README.md`, `pom.xml`: 설정 방식 설명 수정. 의존성과 버전 변경 없음.

추가한 검증 파일(Bean 목록은 현재 문서 자료로 이동):

- `src/test/java/com/yigoheojang/configuration/JavaConfigurationContractTest.java`
- `src/test/java/com/yigoheojang/configuration/PropertyLoadingContractTest.java`
- `docs/java-config-migration/beans-before.txt`
- `docs/java-config-migration/beans-after.txt`
- `docs/java-config-migration/bean-names.diff`
- `src/test/resources/configuration/properties/{db-fixture,redis-fixture,jwt-first,jwt-second}.properties`
- 이 보고서 `docs/java-config-migration.md`

삭제한 파일:

- `src/main/resources/spring/root-context.xml`
- `src/main/resources/spring/servlet-context.xml`

## 이관한 Bean과 등록 조건

아래 명시적 Bean은 모두 singleton이고 조건부 등록 없이 기존 이름을 유지한다.
FactoryBean 제품과 FactoryBean 자체도 구분해서 검증했다.

| 이름 | 타입 및 의존 관계 | 유지한 설정 |
| --- | --- | --- |
| `dataSource` | `HikariDataSource` | setter 구성, 첫 연결 시 풀 생성, 종료 시 close |
| `sqlSessionFactory` | `SqlSessionFactoryBean` → dataSource | mapper 패턴, camelCase 매핑 |
| `org.mybatis.spring.mapper.MapperScannerConfigurer#0` | `MapperScannerConfigurer` | com.wallet + @Mapper, sqlSessionFactory 지정, 기존 alias 유지 |
| `transactionManager` | `DataSourceTransactionManager` → dataSource | public 메서드의 트랜잭션 처리 |
| `passwordEncoder` | `BCryptPasswordEncoder` | 기본 생성자 |
| `objectMapper` | `Jackson2ObjectMapperFactoryBean` → ObjectMapper | Java Time 탐색, 알 수 없는 필드 무시, 날짜 배열 |
| `mailSender` | `JavaMailSenderImpl` | 모든 SMTP 속성, UTF-8 |
| `jedisPoolConfig` | `JedisPoolConfig` | 기존 maxTotal, maxIdle, minIdle |
| `jedisPool` | `JedisPool` → jedisPoolConfig | 기존 생성자, 종료 시 close |
| `notificationTaskScheduler` | `ThreadPoolTaskScheduler` | core pool size 4, 기존 두 cron 작업 |
| `chatbotRequestFactory` | `SimpleClientHttpRequestFactory` | connect 2000ms, read 30000ms |
| `restTemplate` | `RestTemplate` → chatbotRequestFactory | 기존 요청 처리 |
| `mvcCorsConfigurations` | `LinkedHashMap` → 내부 CorsConfiguration | 기존 CORS 정책 |
| `antPathMatcher` | `AntPathMatcher` | 기존 경로 매칭 |

루트 컴포넌트 스캔은 기존과 같이 Controller 및 com.wallet.boot를 제외한다. MVC 스캔은 Controller만
포함한다. Boot는 com.wallet.boot만 스캔한다. 기존 두 XML도 동일 Boot 컨텍스트에 import되어
있었으므로 부모·자식 컨텍스트를 새롭게 합친 변경은 아니다.

기존 ClockConfig의 !local/systemClock 및 local/localClock 조건과 local 전용 관리 Controller의
@Profile은 그대로다. UserSeedReplayTest의 @Disabled도 그대로다. testprobe 패키지는 루트 컴포넌트
스캔 밖에 있으며, 운영 컨텍스트와 실행 JAR에 유입되지 않는다.

## MVC·트랜잭션·설정 로딩 선택

WebMvcAutoConfiguration, TransactionAutoConfiguration 제외를 유지한다. MVC는 @EnableWebMvc를
사용하고 컨버터 6개를 기존 순서대로 직접 등록한다. Jackson 컨버터는 objectMapper Bean과
동일 인스턴스를 사용한다. LocalDateTime은 ISO 문자열 대신 기존 정수 배열로 직렬화된다.
AntPathMatcher와 trailing slash 허용도 유지한다.

기존 OptionalValidatorFactoryBean을 명시적으로 유지하고 MVC에도 같은 인스턴스를 연결했다.
구체적인 Jakarta Validator 타입으로 선언해 Boot의 별도 defaultValidator 생성을 방지한다.

트랜잭션은 @EnableTransactionManagement 대신 기존 advisor, attribute source, interceptor,
event listener factory와 프록시 등록을 Java로 직접 구성했다. Spring 6의 해당 annotation은
protected/package-visible 메서드까지 트랜잭션 적용 범위를 넓히므로, XML의 public-only 계약과
인프라 이름을 유지하기 위한 선택이다. public/protected 판별과 실제 롤백 테스트를 통과했다.
스케줄·비동기 annotation 처리는 @EnableScheduling/@EnableAsync로 옮겼다.

property-placeholder는 단일 static PropertySourcesPlaceholderConfigurer Bean으로 옮겼다.
db.properties → redis.properties → classpath*:jwt.properties 순서와 wildcard 전체 탐색을 유지한다.
외부 Environment가 로컬 파일보다 우선하고, 로컬 파일끼리는 뒤의 값이 우선한다.
db/redis 파일은 계속 필수이며 jwt wildcard에 일치하는 파일이 없어도 외부 Secret을 사용할 수 있다.

@PropertySource는 파일을 Environment에 추가하고 spring.config.import는 Boot Config Data 규칙을
적용하므로, 기존 placeholder 처리 경로를 보존하기 위해 사용하지 않았다. .env는 계속 Spring이
자동으로 읽지 않는다. 실행 검증에서는 .env의 필요한 JWT 값만 별도 프로세스 환경에 명시적으로
전달했으며 실제 Secret은 소스·명령 인수·보고서에 기록하지 않았다.

CORS는 /api/**, 기존 Origin placeholder와 localhost:5173 기본값, GET/POST/PUT/PATCH/DELETE/OPTIONS,
허용 header *, credentials=true, maxAge=1800을 유지했다. 기본 설정과 외부 Origin override
양쪽에서 실제 HTTP preflight, 거부 Origin, trailing slash를 확인했다.

## Bean 목록 비교

XML을 제거하기 전에 WalletApplication의 실제 기본 profile 컨텍스트에서 297개 정의를 캡처했다.
Java Config는 독립 비교 컨텍스트에서 먼저 확인하고, 최종 WalletApplication에서 309개를 캡처했다.
테스트 전용 probe 설정은 두 목록 모두 포함하지 않았다.

목록 형식은 `name|type|scope|aliases`다. CGLIB 클래스는 사용자 클래스로 환원하고 JVM마다 달라지는
lambda 메모리 주소만 제거했다. 이관 당시 일회성 검증에서는 전체 after 목록과 이름 diff를 고정
목록과 비교했고, 각 실행의 실제 목록·diff를 target/configuration-migration에도 생성했다.
이관 완료 후 전체 목록 exact match 테스트는 제거하고 세 목록은 이관 근거 자료로 이동했다.

- 전체 이전 목록: `docs/java-config-migration/beans-before.txt`
- 전체 이후 목록: `docs/java-config-migration/beans-after.txt`
- 전체 이름 diff: `docs/java-config-migration/bean-names.diff`
  결과: 이름 10개 제거, 22개 추가. 기존 애플리케이션 컴포넌트 Bean과 명시적 설정 Bean의 이름·타입·scope 유지.
- 공통 이름에서 달라진 메타데이터는 antPathMatcher의 mvcPathMatcher alias 하나뿐이다.
  이후에는 mvcPathMatcher가 별도 정의이며 같은 AntPathMatcher 인스턴스를 반환함을 검증한다.

| 차이 | 원인과 영향 |
| --- | --- |
| RequestMappingHandlerMapping/Adapter, BeanNameUrlHandlerMapping, HttpRequestHandlerAdapter, SimpleControllerHandlerAdapter의 XML 이름 제거 및 Java 이름 추가 | @EnableWebMvc가 메서드 이름으로 등록한다. MVC 타입·HTTP 동작을 검증했고 해당 XML 인프라 이름을 직접 주입하는 운영 코드는 없다. |
| FormattingConversionServiceFactoryBean#0 제거, mvcConversionService 추가 | Java MVC의 DefaultFormattingConversionService가 담당한다. 프로덕션 코드에 기존 이름/요청 속성에 대한 직접 의존이 없다. |
| MappedInterceptor#0 제거, mvcResourceUrlProvider 등 추가 | XML의 ConversionServiceExposingInterceptor 자동 등록이 Java MVC 구성으로 바뀐다. 프로덕션 코드에 이 request attribute를 읽는 코드가 없다. |
| ExceptionHandlerExceptionResolver#0, ResponseStatusExceptionResolver#0, DefaultHandlerExceptionResolver#0 제거, handlerExceptionResolver 추가 | 같은 resolver 계열을 composite 내부에서 사용한다. 인증·알림 Characterization과 Newman의 상태 코드·오류 코드 계약 통과. |
| mvcPathMatcher, mvcPatternParser, mvcValidator 추가 | Java MVC 표준 정의다. 실제 경로 매칭은 기존 AntPathMatcher이고 pattern parser는 null이다. mvcValidator도 기존 OptionalValidatorFactoryBean과 동일 객체다. |
| viewControllerHandlerMapping, resourceHandlerMapping, defaultServletHandlerMapping, routerFunctionMapping, handlerFunctionAdapter, mvcViewResolver 추가 | Java MVC 기본 인프라다. view/resource/default servlet/router 등록을 새로 구성하지 않았으며 신규 엔드포인트는 추가하지 않았다. |
| RootConfiguration, WebMvcConfiguration, DelegatingWebMvcConfiguration, SchedulingConfiguration, ProxyAsyncConfiguration 추가 | XML namespace를 대체하는 Java 구성 Bean이다. 기존 서비스나 정책의 추가가 아니다. |

## 추가·수정 테스트만 실행

실행 위치: backend. 개인 설정이 Compose 호스트명(mysql/redis)을 사용하므로 호스트 실행에 한해
외부 속성으로 localhost를 지정했다. 개인 설정 파일은 수정하지 않았다.

```powershell
.\mvnw.cmd "-Dtest=JavaConfigurationContractTest,PropertyLoadingContractTest,BootWebRuntimeContractIntegrationTest" "-Ddb.url=jdbc:mysql://localhost:3306/wallet?serverTimezone=Asia/Seoul" "-Dredis.host=localhost" clean test
```

실제 결과: 15개, 실패 0, 오류 0. 외부 CORS Origin을 지정한 다음 실행도 15개 통과했다.

```powershell
.\mvnw.cmd "-Dtest=JavaConfigurationContractTest,PropertyLoadingContractTest,BootWebRuntimeContractIntegrationTest" "-Ddb.url=jdbc:mysql://localhost:3306/wallet?serverTimezone=Asia/Seoul" "-Dredis.host=localhost" "-Dapp.cors.allowed-origin=https://frontend.example.invalid" test
```

검증 내용: 전체 Bean snapshot/diff, 중복 placeholder 방지, 데이터 접근과 약관 API, mapper XML
32개 및 각 namespace의 실제 mapper 등록, camelCase 설정, FactoryBean, JSON·컨버터·validator·경로
매칭, scheduler 선택/core size 4/cron 2개, public-only 트랜잭션, 파일 병합 순서, 복수 wildcard
JWT 파일, 외부 설정 우선순위, Secret 누락·빈 값·길이 부족 시 실패, HTTP 필터 및 CORS.

## 별도 전체 회귀 결과

| 검증 | 실제 실행 결과 |
| --- | --- |
| XML 기준선 | 캡처 1개 + 기존 웹 계약 6개, 총 7개 통과 |
| 백엔드 전체 test | 518개, 실패 0, 오류 0, 기존 Disabled 도구 1개 skip |
| 최종 clean package | 전체 518개 결과 동일, 실행 JAR 패키징 성공 |
| 인증 Characterization | 6개 통과 (전체 테스트에 포함) |
| 알림 Characterization | 실제 MySQL·Redis로 4개 통과 (전체 테스트에 포함) |
| Newman 기존 기준선 | 요청 25개, assertion 25개, 실패 0, 실행별 fixture 정리 완료 |
| k6 smoke | 기존 스크립트·threshold, 1 VU/1 iteration, check 15/15, HTTP 실패 0/5 |
| 실행 JAR | 18080 포트 기동, GET /api/terms 200 |
| Docker Compose 전체 스택 | backend/chatbot/mysql/redis running, 8080의 GET /api/terms 200 |
| 필수 Secret 미설정 실행 JAR | 종료 코드 1, JWT_ACCESS_TOKEN_SECRET unresolved placeholder 확인 |
| 패키징 격리 | Spring 설정 XML 0, testprobe 0, jwt.properties 0, mapper XML 32 |
| 소스 검사 | Spring 설정 XML/ImportResource/테스트 XML 컨텍스트 참조 0, git diff --check 통과 |

전체 테스트·패키징의 실제 명령 (backend):

```powershell
.\mvnw.cmd "-Ddb.url=jdbc:mysql://localhost:3306/wallet?serverTimezone=Asia/Seoul" "-Dredis.host=localhost" test
.\mvnw.cmd "-Ddb.url=jdbc:mysql://localhost:3306/wallet?serverTimezone=Asia/Seoul" "-Dredis.host=localhost" clean package
```

Characterization만 재실행할 때의 명령 (별도 실행은 하지 않았고 위 전체 테스트에 포함해 실행):

```powershell
.\mvnw.cmd "-Dtest=AuthenticationCharacterizationIntegrationTest,NotificationCharacterizationIntegrationTest" "-Ddb.url=jdbc:mysql://localhost:3306/wallet?serverTimezone=Asia/Seoul" "-Dredis.host=localhost" test
```

JAR에 실제 전달한 JVM 인수 (backend, JWT Secret은 프로세스 환경으로 주입):

```powershell
java "-Ddb.url=jdbc:mysql://localhost:3306/wallet?serverTimezone=Asia/Seoul" "-Dredis.host=localhost" -jar .\target\yigo-heojang-backend.jar --server.port=18080
curl.exe http://localhost:18080/api/terms
```

HTTP 상태는 실제로 Invoke-WebRequest로 확인했다. 두 JWT 환경변수를 제거한 별도 프로세스에서는
`java -jar .\target\yigo-heojang-backend.jar --server.port=0`을 실행해 종료 코드와 실패 원인을 확인했다.

Newman (api-test, DB 인증은 기존 개인 db.properties에서 프로세스 환경으로만 전달):

```powershell
$env:API_TEST_BASE_URL = 'http://localhost:8080'
npm.cmd test
```

k6 (저장소 루트, PERF_PASSWORD는 사용자가 지정한 임시 테스트 값으로 설정):

```powershell
.\performance\reset-perf-data.ps1
$env:BASE_URL = 'http://localhost:8080'
k6 run .\performance\k6\smoke.js
```

재시드는 사용자의 명시적 승인을 받아 기존 스크립트로 수행했다. DB_USER/DB_PASSWORD와 cost 10
BCrypt PERF_PASSWORD_HASH를 프로세스 환경으로 전달했다. 기존 성능 계정 20개만 재생성했고,
스크립트 검증 결과는 20/100/20/700/300/400/100이다. 일반 회원·공유 마스터는 재시드 대상이 아니다.
해당 성능 계정의 비밀번호와 데이터는 재시드된 상태다. seed SQL 파일은 수정하지 않았다.
Newman이 만든 실행별 회원·인증·알림 fixture는 기존 스크립트의 finally 정리 절차로 제거했다.

Compose (backend):

```powershell
docker compose up -d
curl.exe http://localhost:8080/api/terms
```

검증 종료 후 별도로 실행한 JAR 프로세스를 중지하고 `docker compose stop`으로 네 서비스를
모두 중지했다. 컨테이너와 볼륨은 삭제하지 않았으므로 다시 시작할 수 있다.

## 유지 여부와 검증 범위

검증한 API·데이터 접근·인증·알림·CORS·JSON·설정 우선순위 계약은 유지된다. 남는 차이는 위에
기록한 Java MVC 인프라 정의 이름과 구성 방식이다. 기본 profile/당시 의존성 버전의 Bean 전체
snapshot이며 이관 근거 자료로 보존한다. 장시간 부하 기준선은
이번 범위가 아니며 k6는 smoke만 실행했다. 라이브 SMTP 서버로 메일을 보내는 검증도 수행하지 않았다.

Maven의 중복 org.json/테스트 에이전트 경고와 Newman의 Node deprecation 경고는 남아 있다.
의존성·Node 버전·CI 구조 변경은 하지 않았다. 커밋은 생성하지 않았다.
