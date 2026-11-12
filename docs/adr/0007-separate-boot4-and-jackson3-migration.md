# ADR-0007: Spring Boot 4 전환과 Jackson 3 전환 분리 및 기존 JSON 계약 유지

## 상태

- 상태: Accepted
- 결정 시점: 2026-11-03
- 작성 시점: 2026-11-10
- 기록 유형: 소급 작성

## 1. 배경 및 문제

**배경:**
- Spring Boot 4는 Jackson 3을 기본 사용하며, Jackson 2와 JSON 처리 기본값이 달라 기존 API 동작이 바뀔 수 있습니다.
- 기존 API는 날짜를 숫자 배열(e.g. `[2026, 11, 3]`), enum을 상수 이름으로 응답하고, 알 수 없는 필드와 JSON 본문 뒤의 추가 토큰을 허용했습니다.
    - 마이그레이션에서는 이 계약을 유지해야 했습니다.
- JwtAuthenticationFilter는 MVC 밖에서 직접 401 JSON을 생성하며, JJWT 0.11.5는 Jackson 2에 의존합니다.

**문제:**
- Spring Boot 4와 Jackson 3을 한 번에 전환할지, 단계적으로 전환할지 결정해야 했습니다.
- 또한 Jackson 3으로 전환하면서 기존 JSON 응답 형식과 요청 허용 기준을 어떻게 유지할지도 결정해야 했습니다.

## 2. 대안 비교

| 대안 | 장점 | 단점 |
|---|---|---|
| A. 2단계 전환 + 호환 설정 명시 | Boot 전환 문제와 JSON 계약 변경 여부를 단계별로 검증할 수 있음 | deprecated 모듈인 `spring-boot-jackson2`를 임시로 사용하고, 검증을 두 번 해야 함 |
| B. 일괄 전환 | 임시 의존성 없이 한 번에 최종 상태에 도달함 | 문제 발생 시 원인이 Boot 전환인지 Jackson 전환인지 구분하기 어려움 |
| C. Boot 4에서 Jackson 2 장기 유지 | 기존 JSON 동작을 유지하면서 Jackson 3 전환을 미룰 수 있음 | deprecated 모듈에 계속 의존하며, 이후 Jackson 3 전환이 필요함 |
| D. Jackson 3 기본값 채택 | 일부 요청 JSON에 대한 엄격한 검사를 적용하고, 별도의 호환 설정이 필요 없음 | 날짜·enum 응답 형식이 바뀌어 기존 클라이언트와의 호환성이 깨짐 |

## 3. 적용한 결정 및 기술적 평가

**선택:** A안 — 2단계 전환 + 호환 설정 명시

**선택 이유:**
- **문제 원인 분리:** Boot 호환성 문제는 주로 빌드·기동 과정에서 드러나지만, Jackson 전환 문제는 정상 기동 후에도 JSON 응답 형식이 바뀔 수 있어 별도 검증이 필요했습니다.
- **공식 전환 경로:** Boot 4에서 제공하는 spring-boot-jackson2를 활용해 Jackson 2를 임시로 유지할 수 있었습니다.
- **기존 계약 유지:** 호환 옵션만으로는 날짜 배열이 문자열로 바뀌는 문제가 확인되어 개별 설정이 필요했고, 향후 옵션 제거 시에도 동작이 바뀌지 않도록 설정을 명시했습니다.

**비용 및 제약:**
- 단계별 검증을 두 번 수행해야 하는 추가 비용이 발생합니다.
- 임시 spring-boot-jackson2 의존성 유지 및 후속 제거 비용 발생합니다.

## 4. 참고 자료

- [이슈 #68](https://github.com/MOIYS/yigo-heojang-springboot/issues/68) · [PR #70](https://github.com/MOIYS/yigo-heojang-springboot/pull/70): 2단계 전환 결정 및 Jackson 2를 유지한 1단계 구현
- [이슈 #69](https://github.com/MOIYS/yigo-heojang-springboot/issues/69) · [PR #71](https://github.com/MOIYS/yigo-heojang-springboot/pull/71): Jackson 3 전환 및 기존 JSON 계약 유지 설정
- [이슈 #65](https://github.com/MOIYS/yigo-heojang-springboot/issues/65) · [PR #67](https://github.com/MOIYS/yigo-heojang-springboot/pull/67): 전환 전 JSON 계약 기준선 및 검증 결과
- [Jackson 3 Migration Guide](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md): Jackson 3의 주요 변경 사항과 기본값 차이
- [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide): Jackson 3 기본화 및 Jackson 2 호환 지원


