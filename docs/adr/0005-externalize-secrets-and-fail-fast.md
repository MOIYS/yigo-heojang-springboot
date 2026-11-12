# ADR-0005: Secret 외부 주입과 기동 시 즉시 실패(fail-fast) 정책 채택

## 상태

- 상태: Accepted
- 결정 시점: 2026-10-23(외부화), 2026-10-25(CI 추가)
- 작성 시점: 2026-11-09
- 기록 유형: 소급 작성

## 1. 배경 및 문제

**배경:**
- JWT Access·Refresh Token 서명 키가 `JwtTokenProvider`에 하드코딩되어 있었습니다.
- CI와 실행 스크립트에도 DB·Redis 관련 설정 값이 하드코딩되어 있었습니다.

**문제:** 
- 서명 키가 코드에 포함된 상태에서는 키를 변경할 때 코드 수정이 필요하고, 저장소에 민감정보가 남는 위험이 있습니다. 
- 이를 해결하기 위해 세 가지 결정이 필요했습니다. 
  - 서명 키를 외부에서 어떻게 주입할지
  - 키가 누락되거나 유효하지 않을 때 애플리케이션을 어느 시점에 중단할지
  - 앞으로 커밋되는 Secret을 어느 범위까지 검사할지

## 2. 대안 비교

### 주입과 검증 방식

| 대안 | 장점 | 단점  |
|---|---|---|
| A. 환경변수 주입 + 기동 시 검증 | 별도 인프라 없이 로컬·CI·Docker Compose에서 동일한 방식으로 주입할 수 있으며, 키가 유효하지 않으면 애플리케이션 기동을 중단함 | 프로세스 환경에 접근할 수 있는 주체에게 Secret이 노출될 수 있으며, 자체적인 접근 감사·자동 회전 기능이 없음 |
| B. 환경변수 + 기본값 | 키 설정 없이도 바로 실행할 수 있음 | 공통 기본 키가 여러 환경에서 재사용될 수 있고, 사용 시점에 검증하면 첫 인증 요청에서야 설정 오류가 드러날 수 있음 |
| C. 파일 마운트 Secret(`configtree:`) 혹은 Secret Manager | 파일 마운트는 Secret을 일반 환경변수와 분리하고, Secret Manager는 중앙 저장·접근 감사·회전 기능을 제공할 수 있음 | 파일 마운트는 별도 파일 배포·권한 관리가 필요하고, Secret Manager는 서비스 연동·인증·운영 비용이 추가됨 |

### 신규 유출 탐지 범위

| 대안 | 장점 | 단점|
|---|---|---|
| D. PR 커밋 범위 gitleaks | 기존 Git 이력의 탐지 결과와 분리할 수 있으며, PR에 포함된 중간 커밋도 검사함 | 원격 push가 끝난 뒤 실행되고, 기존 이력은 검사하지 않음 |
| E. 전체 이력 검사(+baseline) | 과거에 커밋된 Secret까지 찾아냄 | 기존 탐지 결과를 분류하고, 노출된 실제 Secret을 교체하며, baseline(기존 탐지 결과의 기준 목록)을 관리해야 함 |
| F. 커밋·push 단계 차단(pre-commit, GitHub Push Protection) | 개발 단계에서 Secret 유출을 탐지하거나 원격 저장소 반영을 사전에 차단할 수 있음 | pre-commit은 설치하지 않거나 우회할 수 있고, Push Protection은 지원하는 패턴에만 동작함 |


## 3. 적용한 결정 및 기술적 평가

**선택:** A안 + D안
- `JWT_ACCESS_TOKEN_SECRET`, `JWT_REFRESH_TOKEN_SECRET`을 외부 설정으로 주입합니다.
- 값이 없거나, 공백이거나, 32바이트 미만이면 기동을 중단합니다.
- PR마다 `BASE_SHA..HEAD_SHA` 범위를 gitleaks로 검사합니다.

**선택 이유:**
- **실행 환경의 일관성:** 추가 인프라 없이 로컬·CI·Docker Compose에 같은 주입 방식 적용이 가능합니다.
- **기동 시점에 오류 발견:** 키 설정 오류를 첫 인증 요청이 아닌 애플리케이션 초기화 단계에서 발견 가능합니다.
- **PR 단위 검사:** 기존 이력의 탐지 결과에 영향을 받지 않으면서 PR에 포함된 커밋만 검사할 수 있습니다.

**비용 및 제약:**
- 환경변수로 주입하는 Secret의 안전한 보관과 접근 통제는 별도로 관리해야 합니다.
- Secret 교체와 기존 Git 이력의 유출 문제를 별도로 처리해야 하는 부담이 있습니다.
- gitleaks는 Draft PR을 포함한 PR 단계에서 검사하므로, 원격 저장소로의 Secret 유출 자체를 사전에 차단하지 못합니다.

## 4. 참고 자료

- [이슈 #39](https://github.com/MOIYS/yigo-heojang-springboot/issues/39) / [PR #40](https://github.com/MOIYS/yigo-heojang-springboot/pull/40): 외부화와 기동 실패의 범위, 제외 항목, 검증 결과
- [이슈 #41](https://github.com/MOIYS/yigo-heojang-springboot/issues/41) / [PR #43](https://github.com/MOIYS/yigo-heojang-springboot/pull/43): PR 범위 검사, 선행 조건, 임시 PR 검증
- [OWASP Secrets Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html): 환경변수의 노출 위험, Secret 저장·회전 수명주기
- [GitHub Secret Scanning 범위](https://docs.github.com/en/code-security/reference/secret-security/secret-scanning-scope): Push Protection의 지원 패턴과 한계
