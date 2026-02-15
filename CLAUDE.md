# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Monorepo (pnpm + Turborepo)
pnpm install                    # 전체 의존성 설치
pnpm dev:full                   # DB + 전체 dev 서버 시작
pnpm dev                        # 전체 dev 서버 시작 (DB가 이미 실행 중일 때)
pnpm build                      # 전체 빌드
pnpm test                       # 전체 테스트
pnpm lint                       # 전체 린트
pnpm typecheck                  # 전체 타입체크
pnpm db:up / pnpm db:down       # PostgreSQL 시작/중지 (Docker)
pnpm db:reset                   # PostgreSQL 볼륨 삭제 후 재시작

# Backend (apps/firehub-api)
cd apps/firehub-api && ./gradlew bootRun --args='--spring.profiles.active=local'
cd apps/firehub-api && ./gradlew build
cd apps/firehub-api && ./gradlew test
cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.auth.service.AuthServiceTest"  # 단일 테스트 클래스
cd apps/firehub-api && ./gradlew test --tests "*.AuthServiceTest.login_success"                # 단일 테스트 메서드

# Frontend (apps/firehub-web)
cd apps/firehub-web && pnpm dev
cd apps/firehub-web && pnpm build
cd apps/firehub-web && pnpm lint
cd apps/firehub-web && pnpm typecheck
```

## Tech Stack

- **Backend**: Spring Boot 3.4 + Java 21, jOOQ (not JPA), Flyway migrations, Spring Security + JWT
- **Frontend**: Vite + React 19 + TypeScript, TanStack Query, React Router v7, shadcn/ui (Radix + Tailwind CSS v4), Zod + React Hook Form, Axios
- **Database**: PostgreSQL 16 (Docker), two schemas: `public` (metadata) and `data` (dynamic user tables)
- **Monorepo**: pnpm workspaces + Turborepo

## Architecture

### Backend (`apps/firehub-api`)

Package base: `com.smartfirehub`. Feature-sliced by domain module:

| Module | Purpose |
|--------|---------|
| `auth` | JWT 인증 (signup/login/refresh/logout). Access token은 응답 body, refresh token은 HttpOnly cookie |
| `user` | 사용자 CRUD, 프로필, 역할 할당 |
| `role` | 역할 CRUD, 권한 할당. 시스템 역할(ADMIN, USER)은 수정 불가 |
| `permission` | 권한 목록 조회. 권한 코드는 `{resource}:{action}` 형식 (예: `dataset:read`, `pipeline:execute`) |
| `dataset` | 데이터셋 정의 및 카테고리 관리. 데이터셋 생성 시 `data` 스키마에 실제 테이블 생성 |
| `dataimport` | CSV/XLSX 파일 업로드 → 파싱 → 검증 → `data.{table}` 에 적재 |
| `pipeline` | SQL/Python 스크립트 기반 ETL 파이프라인. Step 간 DAG 의존성, 비동기 실행 |
| `dashboard` | 대시보드 통계 (데이터셋/파이프라인/임포트 요약) |
| `global` | SecurityConfig, JwtAuthenticationFilter, GlobalExceptionHandler, `@RequirePermission` AOP, ErrorResponse/PageResponse 공통 DTO |

각 모듈 내부 구조: `controller/` → `service/` → `repository/` + `dto/`, `exception/`

**Data Access**: JPA가 아닌 **jOOQ**를 사용. `DataTableService`가 `data` 스키마의 동적 테이블을 raw SQL로 관리 (DDL/DML). jOOQ 코드젠 결과는 `src/main/generated`에 위치.

**DB Migrations**: `src/main/resources/db/migration/V{n}__*.sql` (Flyway). 권한 seed 데이터 포함.

**Authorization**: `@RequirePermission("permission:code")` 어노테이션으로 엔드포인트별 권한 제어. `Authentication.getPrincipal()`은 `Long userId`를 반환.

**Tests**: 서비스 테스트는 `IntegrationTestBase`를 상속하는 `@SpringBootTest` + `@ActiveProfiles("test")` 통합 테스트. 테스트 DB는 `smartfirehub_test`. Mockito와 `spring-security-test` 사용. 테스트 실행 시 `--add-opens` JVM args 필요 (build.gradle.kts에 설정됨).

### Frontend (`apps/firehub-web`)

- **API 클라이언트**: `src/api/client.ts` — Axios 인스턴스, baseURL `/api/v1`, 401 시 자동 token refresh 및 request queue
- **인증**: `AuthContext` (React Context) — login/signup/logout, `hasRole()`, `isAdmin` 제공. `ProtectedRoute`/`AdminRoute`로 라우트 보호
- **서버 상태**: TanStack Query. 커스텀 훅은 `src/hooks/queries/` (useDatasets, usePipelines, useUsers, useDashboard)
- **폼 검증**: Zod 스키마 (`src/lib/validations/`) + React Hook Form (`@hookform/resolvers`)
- **UI**: shadcn/ui 컴포넌트 (`src/components/ui/`), Tailwind CSS v4, Lucide 아이콘, Sonner 토스트
- **파이프라인 시각화**: `@xyflow/react`로 DAG 렌더링 (`DagViewer.tsx`)
- **경로 alias**: `@/` → `src/`

### API 규칙

- 모든 API: `/api/v1/**`
- 인증 불필요: `/api/v1/auth/signup`, `/api/v1/auth/login`, `/api/v1/auth/refresh`
- 나머지 `/api/v1/**`는 JWT Bearer token 필요
- Frontend dev 서버에서 `/api` 요청은 Vite 프록시로 백엔드(localhost:8080)에 전달

### 동적 테이블 패턴

데이터셋 생성 시 `data` 스키마에 실제 PostgreSQL 테이블이 동적으로 생성됨. 테이블/컬럼 이름은 `[a-z][a-z0-9_]*` 패턴만 허용. 데이터 임포트는 이 동적 테이블에 행을 삽입하고, 파이프라인은 SQL/Python으로 이 테이블들을 변환.

## Conventions

- Backend 프로파일: `local` (로컬 개발), `test` (테스트)
- 작업은 명확한 단위로 구분하고, 각 작업 단위별로 검증 후 커밋한다.
- 커밋은 반드시 사용자 승인 후 진행한다. 특정 지시가 있는 경우는 반드시 따른다.
- Backend(firehub-api)와 Frontend(firehub-web)는 Claude 팀으로 분리하여 병렬 작업한다.
- 팀 내에서도 독립적인 모듈은 SubAgent를 활용하여 최대한 병렬화한다.
