# ADR-0001: 프레임워크 전환 전 위험 기반 회귀 검증 체계 구축

## 상태

- 상태: Accepted
- 결정 시점: 단계적 결정(2026-10-02, 2026-10-7)                                                                                                                                                                                                                                                                                                        
- 작성 시점: 2026-11-08
- 기록 유형: 소급 작성

## 1. 배경 및 문제

**배경:**
- 백엔드는 Spring 5.3 기반 WAR로 로컬 외부 Tomcat에서 실행하고 있었으며, 이를 Spring Boot 실행 JAR로 전환할 예정이었습니다.
- 이번 전환 작업에서는 기존 팀 프로젝트에서 담당했던 인증·알림 기능을 중심으로 회귀 검증 범위를 설정했습니다.
- 인증은 Access Token의 HTTP 헤더 전달, Refresh Token의 쿠키 전달, DB에 저장된 토큰 상태 검증 등 여러 처리 과정이 연동되어 동작합니다. 알림은 배치 처리와 MySQL, Redis 캐시가 연동되어 동작합니다.
- 기존 단위 테스트로는 이런 계층 간 연결이 깨지는 문제를 검증하기 어려웠습니다.
- 또한 프레임워크 전환으로 테스트 환경이 달라지더라도 기존 동작의 변경 여부를 판단할 수 있도록, 전환 전후에 공통으로 적용할 수 있는 검증 기준이 필요했습니다.

**문제:**
- 프레임워크 전환으로 기존 기능의 동작이 의도치 않게 변경되었는지 판단하려면, 전환 전 동작을 테스트로 고정한 기준선이 필요했습니다.
- 이를 위해 어떤 기능을 우선 검증하고, 애플리케이션 내부 상태와 실제 서버의 HTTP 응답을 어떤 방식과 도구로 검증할지 결정해야 했습니다.그래서 어떤 기능을, 어느 경계(애플리케이션 내부 또는 실제 HTTP)에서, 어떤 도구로 고정할지 정해야 했습니다.

## 2. 대안 비교

| 대안                                     | 장점                                                                                                     | 단점 |
|----------------------------------------|--------------------------------------------------------------------------------------------------------|---|
| A. 특성화 통합 테스트 + MockMvc 기반 검증          | Java·Maven 환경에서 테스트를 통합 관리할 수 있으며, 실제 서버를 기동하지 않고 HTTP 요청 처리 동작을 검증할 수 있음                              | 실제 서블릿 컨테이너, 필터 등록, 패키징, 기동 과정을 검증하지 못하므로 프레임워크 전환 과정에서 실행 환경 문제를 놓칠 수 있음 |
| B. 특성화 통합 테스트 + Newman 실행 서버 검증        | 기존 `Postman collection`을 재사용해 CLI와 CI에서 실행할 수 있음. 테스트가 Spring에 묶여 있지 않아 WAR와 JAR에 동일한 HTTP 요청을 보낼 수 있음 | 별도의 Node.js 실행 환경이 따로 필요함. assertion이 collection JSON 안의 JavaScript로 저장되어 리뷰와 수정이 번거로움. 요청 간 의존성이 있는 시나리오에서는 연쇄 실패가 발생할 수 있음 |
| C. 특성화 통합 테스트 + Java 기반 실제 서버 HTTP 테스트 | `REST Assured` 등을 활용해 테스트 데이터 준비와 결과 보고를 Java·Maven 환경에서 통합 관리할 수 있음                                   | 기존 collection을 Java 테스트로 재작성해야 함. Boot 전환 전에는 RANDOM_PORT를 사용할 수 없어 외부 Tomcat을 별도로 기동해야 함 |


## 3. 적용한 결정 및 기술적 평가

**선택:** B안 — 인증·알림의 특성화 통합 테스트와 Newman 기반 실제 서버 HTTP 계약 검증

**선택 이유:**
- **검증 도구:**
  - 기존 Postman collection을 Newman으로 실행하면 테스트 재작성 없이 CLI와 CI에서 반복 검증할 수 있습니다.
  - 또한 Spring 테스트 프레임워크에 의존하지 않으므로 WAR와 JAR 환경에 동일한 HTTP 요청과 검증 조건을 사용해 기존 동작의 유지 여부를 확인할 수 있습니다.

- **검증 범위:**
  - 특성화 통합 테스트는 토큰 폐기 상태와 캐시 처리 등 인증·알림의 기존 내부 동작을 검증합니다.
  - Newman은 실제 서버에 HTTP 요청을 보내 상태 코드, 쿠키, 응답 JSON 등 클라이언트가 받는 결과를 검증합니다.
  - 이를 통해 내부 로직뿐 아니라 실제 서버 환경에서 HTTP 요청을 처리하는 과정의 문제도 확인할 수 있습니다.

**비용 및 제약:**
- Java 기반 특성화 테스트, Newman collection, 테스트 데이터 준비용 PowerShell·SQL 스크립트 등 서로 다른 테스트 및 실행 체계를 함께 유지해야 합니다.
- Newman은 collection v3 형식을 지원하지 않으며, Postman은 Postman CLI 사용을 권장합니다. 
  - 다만 기존 회귀 검증 체계의 변경을 최소화하기 위해 Newman을 유지하고, 신규 collection 형식이나 기능이 필요해질 경우 Postman CLI 전환을 검토합니다.

## 4. 참고 자료

- [이슈 #3](https://github.com/MOIYS/yigo-heojang-springboot/issues/3), [이슈 #5](https://github.com/MOIYS/yigo-heojang-springboot/issues/5): 특성화 대상과 "현재 동작 고정" 목적
- [이슈 #9](https://github.com/MOIYS/yigo-heojang-springboot/issues/9), [PR #10](https://github.com/MOIYS/yigo-heojang-springboot/pull/10), [PR #63](https://github.com/MOIYS/yigo-heojang-springboot/pull/63): Newman 기준선과 실행 JAR 기반 CI 검증
- [PR #67](https://github.com/MOIYS/yigo-heojang-springboot/pull/67): Newman과 MockMvc의 검증 경계 판단, 계약 보강 결과
- [Newman CLI 공식 문서](https://learning.postman.com/docs/reference/newman-cli/command-line-integration-with-newman): Postman collection의 CLI 실행 및 CI 통합
- [Newman에서 Postman CLI로 마이그레이션](https://learning.postman.com/docs/reference/newman-cli/migrate-to-postman-cli): Newman의 지원 범위와 Postman CLI 전환 권고