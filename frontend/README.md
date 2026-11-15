# Frontend

두리의 Frontend는 Vue 3 기반의 클라이언트입니다.
기존 팀 프로젝트 구현을 유지하는 구성요소이며, 개인 Backend 고도화의 직접 범위에는 포함하지 않습니다.

## 요구 환경

- Node.js `>=24 <25`
- npm

의존성 버전은 `package-lock.json`으로 고정합니다.

## 설치 및 실행

```bash
cd frontend
npm ci
```

| 명령 | 용도 |
| --- | --- |
| `npm run dev` | 개발 서버 실행 (`http://localhost:5173`) |
| `npm run build` | 프로덕션 빌드 생성 |
| `npm run preview` | 빌드 결과 미리보기 |

일반적인 설치에는 lockfile을 그대로 재현하는 `npm ci`를 사용합니다.
패키지를 추가하거나 변경할 때만 `npm install <package>`를 사용하고 변경된 `package-lock.json`을 함께 관리합니다.

## Backend 연결과 인증

Axios Base URL은 현재 `src/api/axios.js`에 `http://localhost:8080`으로 고정되어 있습니다.
Vite proxy는 사용하지 않습니다. Backend를 다른 주소에서 실행하려면 현재는 Axios 설정을 직접 변경해야 합니다.

`.env.example`에는 `VITE_API_BASE_URL`이 정의되어 있지만 코드에서 읽지 않으므로 값을 변경해도 API 주소는 바뀌지 않습니다.
환경변수 기반 주소 설정은 별도의 코드 변경이 필요한 후속 과제입니다.

로컬 개발 시 Backend CORS의 허용 Origin은 Frontend 개발 서버 주소인 `http://localhost:5173`과 일치해야 합니다.

API 요청 시 Access Token이 `localStorage`에 있을 때만 `Authorization: Bearer <token>` 헤더를 추가합니다.
<br>로그인·회원가입·약관 API는 Token 없이 호출할 수 있습니다. 
<br>보호 API에서 401 응답을 받으면 Access Token 재발급 후 원래 요청을 다시 시도합니다.
<br>Refresh Token Cookie를 전달할 수 있도록 Axios에 `withCredentials: true`가 설정되어 있습니다.

## 프로젝트 구조

```text
src/
├── api/           # 도메인별 Backend API 함수와 Axios 설정
├── assets/        # 이미지와 전역 스타일·디자인 토큰
├── components/    # 공통·레이아웃·도메인 UI 컴포넌트
├── composables/   # 재사용 가능한 Composition 함수
├── router/        # Vue Router 설정
├── stores/        # Pinia 상태 관리
├── utils/         # 공통 유틸리티
├── views/         # 라우트별 페이지 컴포넌트
├── App.vue        # 최상위 컴포넌트
├── main.js        # 애플리케이션 진입점
└── style.css      # 공통 스타일
```

## 개발 가이드

- 컴포넌트는 Vue 3 `<script setup>` 문법을 사용합니다.
- 공유 상태는 필요한 경우 Pinia Store에서 관리합니다.
- API 함수는 `src/api` 아래에 도메인별로 구분합니다.
- 스타일은 `src/assets/styles/tokens.css`의 디자인 토큰을 사용합니다.
- 다크 모드는 `[data-theme="dark"]` 선택자를 사용합니다.

## 검증

현재 Frontend 단위 테스트 스크립트는 없습니다. 로컬과 CI에서는 프로덕션 빌드로 문법 오류와 모듈 참조를 검증합니다.

```bash
npm run build
```

CI는 Node.js 24에서 `npm ci` 후 `npm run build`를 실행합니다.
