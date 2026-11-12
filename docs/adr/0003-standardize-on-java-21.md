# ADR-0003: 빌드·CI·컨테이너의 Java 기준을 21로 통일

## 상태

- 상태: Accepted
- 결정 시점: 2026-10-20
- 작성 시점: 2026-11-09
- 기록 유형: 소급 작성

## 1. 배경 및 문제

**배경:**
- 백엔드는 Spring 5.3 기반 WAR로 로컬 외부 Tomcat 9에서 실행되고 있었으며, 이후 Spring Boot 실행 JAR로 전환할 계획이었습니다.
- Maven 컴파일 기준(`maven.compiler.release`), GitHub Actions JDK, Docker Tomcat 이미지는 모두 Java 17이었습니다.
- Docker 이미지는 Tomcat 버전이 드러나지 않는 유동 태그(`tomcat:9-jdk17`)를 쓰고 있었습니다.
- Java 21로 컴파일한 애플리케이션은 Java 17 환경에서 실행할 수 없으므로, Maven 컴파일 기준과 CI·Docker의 JDK 버전을 함께 조정해야 했습니다.

**문제:** 프레임워크 전환을 앞두고 사용할 Java 기준을 정해야 했습니다. 후보는 17 유지, 21, 최신 LTS인 25였습니다.

## 2. 대안 비교

| 대안 | 장점 | 단점 |
|---|---|---|
| A. Java 17 유지 후, Spring Boot 전환 완료 시 Java 25로 업그레이드 | 프레임워크 전환 중 Java 변경이 없고, Java 업그레이드를 한 번만 하면 됨 | 프레임워크 전환 후 Java·Lombok·JaCoCo를 함께 업그레이드해야 하므로 추가 회귀 검증 필요  |
| B. Java 21로 선행 전환 | Spring 5.3과 향후 Spring Boot 환경까지 모든 단계가 공식 지원하는 가장 높은 LTS이고, 기존 Lombok·JaCoCo 버전을 그대로 쓸 수 있음 | Boot 전환에 꼭 필요한 변경은 아니며, 25로 가려면 한 번 더 전환해야 함 |
| C. Java 25로 직접 전환 | 최신 LTS를 바로 쓰고 이후 major 전환이 줄어듦 | Spring 5.3의 공식 지원 범위(JDK 8~21)를 벗어나고, Lombok·JaCoCo도 함께 올려야 함|


## 3. 적용한 결정 및 기술적 평가

**선택:** B안. Maven `release`, CI JDK, Docker 이미지를 모두 Java 21로 바꾸고, Docker 태그는 `tomcat:9.0.123-jdk21-temurin-noble`로 구체화

**선택 이유:**
  - **호환성:** Java 21은 기존 Spring 5.3과 향후 Spring Boot 전환 환경에서 모두 사용할 수 있는 LTS(Temurin 기준 2029년 12월)이며, 기존 Lombok과 JaCoCo 버전도 유지할 수 있습니다.
  - **변경 범위 분리:** Java 버전을 먼저 통일해 이후 프레임워크 전환에서 Java major 버전 변경과 그에 따른 회귀 검증을 분리할 수 있습니다.
  - **대안 대비 우위:** Java 17 유지는 가능하지만 지원 종료(Temurin 기준 2027년 10월)가 가까워 향후 별도 업그레이드가 필요하고, Java 25 직접 전환은 기존 Spring 5.3의 공식 지원 범위를 벗어나며 관련 도구 업그레이드도 필요합니다.

**비용 및 제약:**
- **비용:** Spring Boot 전환에 필수적인 변경은 아니므로 별도의 Java 21 전환·검증이 발생하며, 향후 Java 25 도입 시 추가 업그레이드가 필요합니다.

## 4. 참고 자료

- [이슈 #13](https://github.com/MOIYS/yigo-heojang-springboot/issues/13) / [PR #14](https://github.com/MOIYS/yigo-heojang-springboot/pull/14): 변경 범위, 범위 밖 항목, 전후 검증 결과
- [Spring Framework Versions](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-Versions): 5.3(JDK 8~21), 6.2(17~25), 7.x(17~25+) 지원 범위
- [Spring Boot 4.1 System Requirements](https://docs.spring.io/spring-boot/system-requirements.html): Boot 4.1의 최소 Java 17