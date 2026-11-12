# ADR-0006: XML 설정의 단계적 Java Config 이관

## 상태

- 상태: Accepted
- 결정 시점: 2026-10-26 
- 작성 시점: 2026-11-10
- 기록 유형: 소급 작성

## 1. 배경 및 문제

**배경:**

- 기존 애플리케이션은 `web.xml`과 `root-context.xml`·`servlet-context.xml`로 구성되어 있었습니다.
- 기존 XML은 Bean 정의뿐만 아니라 애플리케이션 주요 동작도 제어했습니다.
    - Controller와 서비스의 scan 범위 분리, public 메서드에만 적용되는 트랜잭션, properties 파일의 병합 순서, HTTP 컨버터 순서 같은 동작도 함께 정해 두고 있었습니다.
- Boot 실행 JAR로 전환하면서, 기존 `web.xml` 기반 초기화 및 필터 등록 방식을 Boot 환경에 맞게 변경해야 했습니다.
    - 반면 Bean 설정은 Spring이 공식 지원하는 `@ImportResource`로 그대로 둘 수 있었습니다.
- 최종 목표인 Spring Boot 4.1(Spring Framework 7)에서 MVC XML namespace가 deprecated되었지만, 일반적인 Bean XML 설정은 지원됩니다.

**문제:** XML 설정을 Boot 전환과 함께 바꿀지, 나중에 따로 옮길지, 계속 유지할지 정해야 했습니다. 옮긴다면 동작이 그대로인지 확인할 방법도 필요했습니다.

## 2. 대안 비교

| 대안                                                                                 | 장점                                                                | 단점                                                                              |
| ------------------------------------------------------------------------------------ | ------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| A. 단계적 이관 (Boot 전환 때는 `@ImportResource`로 유지하고 이후 별도 작업으로 이관) | 실행 방식 변경과 설정 의미 변경이 분리되어 회귀 원인을 좁힐 수 있음 | `@ImportResource`를 통한 임시 XML 유지와 이후 Java Config 이관·정리 작업이 추가됨 |
| B. 일괄 Java Config 전환                                                             | 임시 XML 설정 없이, 기존 동작을 Java Config로 한 번에 옮길 수 있음  | Boot 전환과 설정 이관이 동시에 발생해 실패 원인 분리가 어려움                     |
| C. XML 영구 유지                                                                     | 당장의 변경과 검증 비용이 가장 작음                                 | Spring 7에서 MVC XML의 장기 유지 가능성이 낮고, Boot 기능 활용이 제한됨           |

## 3. 적용한 결정 및 기술적 평가

**선택:** A안 — 단계적 이관

**선택 이유:**
- **잠재적 동작 변경 위험:** XML의 Java Config 이관은 단순한 문법 변경이 아니라 트랜잭션 적용 범위와 Bean 구성 등 기존 동작에 영향을 줄 수 있습니다.
- **회귀 원인 격리:** 따라서 Boot 전환과 설정 이관을 분리하여, 회귀 원인을 좁힐 수 있는 구조입니다.
- **기준선 기반 검증:** 이관 직전 XML 상태를 기준선으로 고정한 뒤 설정만 이관하므로, Bean diff·설정 계약 테스트·Newman 검증으로 동작 유지 여부를 확인할 수 있습니다.

**비용 및 제약:**
- 임시 XML 설정의 유지·제거 작업이 추가되었습니다.

## 4. 참고 자료

- [이슈 #44](https://github.com/MOIYS/yigo-heojang-springboot/issues/44)·[PR #45](https://github.com/MOIYS/yigo-heojang-springboot/pull/45): Boot 3.5 전환 때 XML을 유지하고 Java Config 이관을 범위에서 뺀 결정
- [PR #47](https://github.com/MOIYS/yigo-heojang-springboot/pull/47): 이관 내용, 검증 결과, 미검증 한계
- [backend/docs/java-config-migration.md](backend/docs/java-config-migration.md): Bean 매핑과 이름 diff별 원인
- [Composing Java-based Configurations](https://docs.spring.io/spring-framework/reference/core/beans/java/composing-configuration-classes.html): `@ImportResource`를 통한 XML·Java Config 혼합 구성 지원
- [Using @Transactional](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html): Spring 6 트랜잭션 적용 범위 변화 및 기존 public 메서드 동작 유지 방법
