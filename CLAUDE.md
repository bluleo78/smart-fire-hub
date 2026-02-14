# Smart Fire Hub - Development Guide

## Monorepo (pnpm + Turborepo)

```bash
pnpm install                    # 전체 의존성 설치
pnpm dev:full                   # DB + 전체 dev 서버 시작
pnpm dev                        # 전체 dev 서버 시작
pnpm build                      # 전체 빌드
pnpm test                       # 전체 테스트
pnpm lint                       # 전체 린트
pnpm typecheck                  # 전체 타입체크
pnpm db:up                      # PostgreSQL 시작
pnpm db:down                    # PostgreSQL 중지
```

## Per-project Commands

```bash
# Backend (firehub-api)
cd apps/firehub-api && ./gradlew bootRun --args='--spring.profiles.active=local'
cd apps/firehub-api && ./gradlew build
cd apps/firehub-api && ./gradlew test

# Frontend (firehub-web)
cd apps/firehub-web && pnpm dev
cd apps/firehub-web && pnpm build
cd apps/firehub-web && pnpm lint
```

## Architecture

- **Backend** (`apps/firehub-api/`): Spring Boot 3.4 + Java 21
  - `controller/` - REST API 엔드포인트
  - `service/` - 비즈니스 로직
  - `repository/` - 데이터 접근 계층 (Spring Data JPA)
  - `domain/` - JPA 엔티티
  - `dto/` - 요청/응답 DTO
  - `config/` - 설정 클래스

- **Frontend** (`apps/firehub-web/`): Vite + React 19 + TypeScript
  - `pages/` - 페이지 컴포넌트
  - `components/` - 공통 컴포넌트
  - `api/` - API 호출 함수
  - `hooks/` - 커스텀 훅
  - `types/` - TypeScript 타입 정의

## Conventions

- Backend API 경로: `/api/v1/**`
- Frontend에서 `/api` 요청은 Vite 프록시로 백엔드(8080)로 전달됨
- DB 프로파일: `local` (로컬 개발)
