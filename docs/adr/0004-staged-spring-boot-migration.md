# ADR-0004: 레거시 Spring WAR를 Spring Boot 4.1 실행 JAR로 단계적 전환

## 상태

- 상태: Accepted
- 결정 시점: 2026-10-21
- 작성 시점: 2026-11-09
- 기록 유형: 소급 작성

## 1. 배경 및 문제

**배경:**
- 백엔드는 Spring 5.3 기반 WAR로 로컬 외부 Tomcat 9에서 실행되고 있었으며, 이후 Spring Boot 실행 JAR로 전환할 계획이었습니다.
- 코드와 설정은 `javax.*` API와 XML 컨텍스트에 의존했습니다.
- Spring 6이상으로 전환하려면 Jakarta EE(`javax.*` -> `jakarta.*`)와 Tomcat 10 이상이 필요합니다.
- Boot 4는 여기에 더해 Spring 7과 Tomcat 11을 기반으로 하며, starter 구조 변경과, MyBatis-Spring 4 호환성 대응도 필요합니다.
- 전환하는 동안에도 API 계약과 base URL은 그대로 유지해야 했습니다.

**문제:** 최종 목표인 Boot 4 실행 JAR로 한 번에 전환할지, 단계적으로 전환할지 결정해야 했습니다. 또한 Boot 4의 목표 minor 버전도 선택해야 했습니다.

## 2. 대안 비교

| 대안 | 장점 | 단점|
|---|---|---|
| A. Boot 4.1 직접 전환 | 임시 설정 없이 한 번에 최종 상태에 도달 | namespace, 패키징, 프레임워크, 컨테이너, JSON 처리가 동시에 바뀌어 실패 원인을 좁히기 어려움  |
| B. Jakarta WAR → Boot 3.5 JAR → Boot 4.1 | 단계마다 따로 빌드·검증할 수 있어 실패 원인을 변경 종류별로 좁힐 수 있음 | 임시 브리지(다음 단계에서 제거할 연결 설정)가 생기고, Spring·MyBatis-Spring 세대 전환을 두 번 검증해야 함 |
| C. B안에 Boot 4.0 단계 추가 | 공식 가이드(3.5→4.0)와 4.1 릴리스 노트를 단계별로 나눠 적용 | 빌드·회귀 검증이 한 번 더 필요하지만 최종 수정량은 거의 같음 |


## 3. 적용한 결정 및 기술적 평가

**선택:** B안 — Jakarta WAR, Boot 3.5 실행 JAR, Boot 4.1을 차례로 거치는 단계적 전환

**선택 이유:**
  - **공식 가이드**: Boot 4 마이그레이션 가이드는 최신 3.5.x에서 출발하도록 권고합니다.
  - **장애 격리 최우선**: 가장 중요한 기준은 변경량이 아니라 실패 원인을 얼마나 좁힐 수 있는가입니다. 
    - Jakarta 전환은 API 호환성 문제이고, 실행 JAR 전환은 배포·기동 구조 문제입니다. 따라서 둘을 나누면 컴파일 오류와 기동·필터 등록 오류를 구분할 수 있습니다.

**비용 및 제약:**
- `@ImportResource`를 통한 XML 설정 유지와 spring-boot-jackson2 호환성 구성 등 임시 설정을 관리·제거해야 했으며, 단계별 빌드와 회귀 검증도 반복해야 했습니다.

## 4. 참고 자료

- 이슈 ([#15](https://github.com/MOIYS/yigo-heojang-springboot/issues/15), [#44](https://github.com/MOIYS/yigo-heojang-springboot/issues/44)), PR [#45](https://github.com/MOIYS/yigo-heojang-springboot/pull/45): 전환 경로 최초 기록, 커밋 분리와 의존성 유지 이유, Legacy WAR 태그
- 이슈 [#68](https://github.com/MOIYS/yigo-heojang-springboot/issues/68), PR [#70](https://github.com/MOIYS/yigo-heojang-springboot/pull/70): Boot 4.1 전환 범위, Jackson 2 임시 유지, 계약 assertion 통과
- [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide): 최신 3.5.x 선행 권고, 모듈·starter 재구성
- [Spring Boot 4.1 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes): 4.0에서 deprecated된 API 제거
