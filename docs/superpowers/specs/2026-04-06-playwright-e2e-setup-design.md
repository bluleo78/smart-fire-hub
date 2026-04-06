# Playwright E2E 테스트 환경 구축 — 설계 스펙

**날짜**: 2026-04-06
**범위**: firehub-web (React 19 + Vite + TypeScript)
**목적**: 회귀 방지, 기능 검증, CI 게이트를 위한 E2E 테스트 기반 구축

## 결정 사항

| 항목 | 결정 |
|------|------|
| 테스트 목적 | 복합 (회귀 방지 + 기능 검증 + CI 게이트) |
| API 전략 | Playwright `page.route()` 기반 모킹 (백엔드 불필요) |
| 초기 범위 | 최소 골격 — 설정 + 로그인 테스트 1개, 이후 점진 확장 |
| CI 통합 | 로컬만 (GitHub Actions는 추후) |
| 설치 위치 | `apps/firehub-web/` 내부 (방식 3: webServer 자동 기동) |

## 패키지 구조

```
apps/firehub-web/
├── e2e/
│   ├── fixtures/
│   │   ├── auth.fixture.ts    # 인증 모킹 커스텀 fixture
│   │   └── api-mock.ts        # API route 모킹 유틸리티
│   └── login.spec.ts          # 로그인 E2E 테스트
├── playwright.config.ts        # Playwright 설정
└── package.json                # @playwright/test devDependency
```

## playwright.config.ts

| 설정 | 값 | 이유 |
|------|----|------|
| baseURL | `http://localhost:5173` | Vite dev 서버 기본 포트 |
| webServer | `pnpm dev` (포트 5173) | 테스트 실행 시 자동 기동 |
| projects | Chromium만 | 초기에는 단일 브라우저로 충분 |
| reporter | HTML (`playwright-report/`) | 로컬 디버깅 편의 |
| testDir | `./e2e` | 소스 코드와 분리 |
| retries | 0 | 로컬 실행, flaky 방지보다 빠른 피드백 우선 |
| use.trace | `on-first-retry` | 실패 시 디버깅용 |

## API 모킹 패턴

Playwright의 `page.route()`로 `/api/v1/*` 요청을 가로채서 JSON 응답 반환.

```typescript
// e2e/fixtures/api-mock.ts
// mockApi(page, method, path, response) 헬퍼 함수
// 예: mockApi(page, 'POST', '/api/v1/auth/login', { token: '...', user: {...} })
```

### 인증 모킹 (auth.fixture.ts)

Playwright `test.extend()`로 커스텀 fixture 제공:

- `authenticatedPage` — 로그인 완료 상태의 page 제공
- 모킹 대상 엔드포인트:
  - `POST /api/v1/auth/login` → JWT 토큰 + 유저 정보
  - `GET /api/v1/auth/me` → 현재 유저 정보
  - `POST /api/v1/auth/refresh` → 토큰 갱신

## 첫 번째 테스트: login.spec.ts

| 시나리오 | 검증 |
|----------|------|
| 로그인 페이지 렌더링 | 이메일/비밀번호 입력 필드, 로그인 버튼 존재 |
| 로그인 성공 | 모킹 API 응답 → `/` 홈 페이지로 리다이렉트 |
| 로그인 실패 | 에러 응답 → 에러 메시지 표시 |
| 빈 필드 제출 | 유효성 검사 메시지 표시 |

## package.json 스크립트

```json
{
  "test:e2e": "playwright test",
  "test:e2e:ui": "playwright test --ui",
  "test:e2e:headed": "playwright test --headed"
}
```

## .gitignore 추가

```
playwright-report/
test-results/
```

## 향후 확장 방향 (이번 범위 아님)

- 주요 페이지별 스모크 테스트 추가
- GitHub Actions CI 워크플로우
- 멀티 브라우저 (Firefox, WebKit)
- 시각적 회귀 테스트 (스크린샷 비교)
