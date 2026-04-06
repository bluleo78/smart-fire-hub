# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

See also the root [CLAUDE.md](../../CLAUDE.md) for monorepo-level commands and cross-app architecture.

## Commands

```bash
pnpm dev              # Vite dev server (proxies /api to localhost:8080)
pnpm build            # TypeScript check + Vite production build
pnpm lint             # ESLint (flat config, ignores dist and src/components/ui)
pnpm typecheck        # tsc -b --noEmit
pnpm preview          # Preview production build locally
pnpm test:e2e         # Playwright E2E 테스트 (Vite dev 서버 자동 기동)
pnpm test:e2e:headed  # 브라우저 창을 띄워서 E2E 테스트 실행
pnpm test:e2e:ui      # Playwright UI 모드 (인터랙티브 디버깅)
```

### E2E 테스트 (Playwright)

- 설정: `playwright.config.ts`, 테스트 디렉토리: `e2e/`, 176개 테스트
- API 모킹 기반 — `page.route()`로 백엔드 API를 모킹하므로 백엔드(Spring Boot + PostgreSQL) 불필요
- 모킹 데이터는 `src/types/`의 타입을 적용하여 API 스펙 변경 시 컴파일 에러로 감지
- 타입 체크: `npx tsc -p tsconfig.e2e.json --noEmit`

#### 디렉토리 구조
- `e2e/factories/` — 모킹 데이터 팩토리 (auth, dataset, pipeline, analytics, ai-insight, admin)
- `e2e/fixtures/` — API 모킹 헬퍼 + 인증/도메인별 fixture (auth, base, dataset, pipeline, analytics, ai-insight, admin)
- `e2e/flows/` — 유저 플로우 시나리오 (해피 패스 연속 시나리오)
- `e2e/pages/` — 개별 페이지 상세 테스트 (유효성 검사, 엣지 케이스)

#### 새 테스트 추가 시
1. 팩토리: `e2e/factories/`에서 모킹 데이터 생성 함수 추가 (타입 필수 적용)
2. Fixture: `e2e/fixtures/`에서 도메인 API 모킹 헬퍼 추가
3. 테스트: `e2e/flows/` (해피 패스) 또는 `e2e/pages/` (상세)에 spec 파일 추가
4. `auth.fixture.ts`의 `test`, `expect`를 import하여 인증 fixture 사용
5. 셀렉터: `getByRole`/`getByLabel`/`getByText` 우선, `data-testid`는 필요한 곳만
6. 관리자 페이지: `setupAdminAuth(page)` 호출 필요 (AdminRoute 우회)

## Architecture

### Stack

Vite + React 19 + TypeScript, TanStack Query, React Router v7 (BrowserRouter), shadcn/ui (new-york style, Radix + Tailwind CSS v4), Zod + React Hook Form, Axios. Path alias `@/` maps to `src/`.

### Layered Structure

```
src/
  api/          # Axios API modules (one per domain: datasets, pipelines, ai, etc.)
  types/        # TypeScript interfaces matching backend DTOs
  lib/          # Utilities: validations/ (Zod schemas), api-error.ts, formatters.ts
  hooks/        # useAuth (AuthContext consumer), useRecentDatasets
    queries/    # TanStack Query hooks (one file per domain)
  components/
    ui/         # shadcn/ui primitives (auto-generated, lint-ignored)
    layout/     # AppLayout (sidebar + header + AI panel), UserNav
    ai/         # AI chat system (AIProvider, panels, message rendering)
  pages/        # Route pages organized by domain
    data/       # Dataset CRUD, categories, import, SQL query
    pipeline/   # Pipeline editor (DAG canvas), triggers, executions
    admin/      # User/role management, audit logs, settings, API connections
```

### Key Patterns

**API Client** (`src/api/client.ts`): Axios instance at `/api/v1` with automatic JWT Bearer injection. Response interceptor handles 401 by refreshing token via `/api/v1/auth/refresh` (HttpOnly cookie) with a queue for concurrent failed requests. Access token is stored in-memory (not localStorage).

**Authentication** (`src/hooks/AuthContext.tsx` + `auth-context-value.ts`): `AuthProvider` wraps the app, provides `useAuth()` hook with `user`, `isAdmin`, `hasRole()`, `login/signup/logout`. Route protection via `ProtectedRoute` (redirects to `/login`) and `AdminRoute` (redirects to `/`).

**Server State**: All data fetching uses TanStack Query hooks in `src/hooks/queries/`. Pattern: `useQuery` for reads, `useMutation` with `queryClient.invalidateQueries()` for writes. Polling via `refetchInterval` for active executions/imports.

**Form Validation**: Zod schemas in `src/lib/validations/` + `@hookform/resolvers`. Each domain has its own schema file.

**Error Handling**: `handleApiError()` / `extractApiError()` in `src/lib/api-error.ts` extracts backend `ErrorResponse.message` and shows via Sonner toast.

**Routing** (`src/App.tsx`): All pages are lazy-loaded with `React.lazy()` + `Suspense`. Routes nest under `ProtectedRoute > AppLayout`, admin routes additionally nest under `AdminRoute`.

**AI Chat System**: SSE streaming via raw `fetch` (not Axios) in `src/api/ai.ts`. `AIProvider` context manages chat state with three display modes: side panel, floating, fullscreen. Toggle with Cmd/Ctrl+K. Session management with compaction support.

**Pipeline Visualization**: `@xyflow/react` for DAG rendering with dagre auto-layout (`src/pages/pipeline/utils/dagre-layout.ts`). Supports SQL, Python, and API_CALL step types with CodeMirror editors.

**Code Splitting**: Manual chunks in `vite.config.ts` for CodeMirror, xyflow/dagre, and react-markdown. AI panels and page components are lazy-loaded.

### Conventions

- **코드 주석 필수**: 컴포넌트, 훅, 주요 로직 블록에 한국어 주석(JSDoc/인라인)을 작성한다. "무엇을 하는지"와 "왜 이렇게 하는지"를 설명하여 코드 이해를 돕는다.
- shadcn/ui components in `src/components/ui/` are generated via `npx shadcn` CLI; do not manually edit them
- shadcn config: new-york style, neutral base color, Lucide icons, CSS variables enabled
- Theming via `next-themes` (dark/light/system)
- Toast notifications via Sonner (`toast.success()`, `toast.error()`)
- ESLint flat config with `typescript-eslint`, `react-hooks`, `react-refresh` plugins
- Vite proxy forwards `/api` requests to backend at `localhost:8080` with SSE buffering disabled

## Design System

UI 작업(새 페이지, 컴포넌트 생성, 기존 UI 수정) 시 반드시 [`docs/design-system/`](../../docs/design-system/index.md)의 디자인 가이드라인을 참조하여 구현한다. 타이포그래피, 간격, 페이지 패턴, 폼 패턴, 피드백 상태, 색상 토큰 등 모든 UI 규칙은 해당 문서가 단일 원본(Single Source of Truth)이다.
