# Smart Fire Hub

데이터셋 관리, ETL 파이프라인, AI 에이전트를 통합한 데이터 허브 플랫폼.

## Monorepo Commands

```bash
pnpm install                    # 전체 의존성 설치
pnpm dev:full                   # DB + 전체 dev 서버 시작
pnpm dev                        # 전체 dev 서버 시작 (DB가 이미 실행 중일 때)
pnpm build                      # 전체 빌드
pnpm test                       # 전체 테스트
pnpm lint                       # 전체 린트
pnpm typecheck                  # 전체 타입체크
pnpm db:up / pnpm db:down       # PostgreSQL 시작/중지 (Docker)
pnpm db:reset                   # PostgreSQL 볼륨 삭제 후 재시작
```

각 앱의 개별 명령어는 해당 모듈의 CLAUDE.md를 참조.

## Apps

| App | 경로 | 역할 | 포트 |
|-----|------|------|------|
| **firehub-api** | `apps/firehub-api` | Spring Boot 백엔드 — 인증, 데이터셋, 파이프라인, API 연결 | 8080 |
| **firehub-web** | `apps/firehub-web` | React 프론트엔드 — 사용자 UI, 대시보드, AI 채팅 | 5173 |
| **firehub-ai-agent** | `apps/firehub-ai-agent` | Node.js AI 에이전트 — Claude Agent SDK 기반 MCP 도구 서비스 | 3001 |

## Inter-App Architecture

```
┌─────────────┐    /api/v1/*     ┌──────────────┐
│  firehub-web │ ──────────────→ │  firehub-api  │
│  (React)     │  Vite proxy     │ (Spring Boot) │
│              │  JWT Bearer     │               │
│              │                 │               │
│              │    SSE stream   ┌──────────────────┐
│              │ ──────────────→ │ firehub-ai-agent │
│              │  POST /agent/*  │  (Node.js)       │
│              │  JWT Bearer     │                  │
└─────────────┘                 │    Internal auth  │
                                │ ────────────────→ │  firehub-api
                                └──────────────────┘
```

### 통신 프로토콜

- **web → api**: Vite 프록시(`/api` → `localhost:8080`), JWT Bearer token 인증
- **web → ai-agent**: SSE 스트리밍 (`POST /agent/chat`), JWT Bearer token 인증
- **ai-agent → api**: 내부 서비스 인증 — `Authorization: Internal {token}` + `X-On-Behalf-Of: {userId}` 헤더

### API 규칙

- 모든 API: `/api/v1/**`
- 공개 엔드포인트: `/api/v1/auth/**`, `/api/v1/triggers/api/**`, `/api/v1/triggers/webhook/**`
- 나머지는 JWT Bearer token 필요

## Tech Stack

- **Backend**: Spring Boot 3.4 + Java 21, jOOQ (not JPA), Flyway, Spring Security + JWT
- **Frontend**: Vite + React 19 + TypeScript, TanStack Query, React Router v7, shadcn/ui, Tailwind CSS v4
- **AI Agent**: Node.js + TypeScript, Express 4, Claude Agent SDK, MCP 도구 36종
- **Database**: PostgreSQL 16 (Docker), `public` 스키마(메타데이터) + `data` 스키마(동적 사용자 테이블)
- **Monorepo**: pnpm workspaces + Turborepo

## Roadmap

이 프로젝트는 `docs/ROADMAP.md`를 기반으로 개발이 진행된다.

- 작업을 시작하기 전에 반드시 `docs/ROADMAP.md`를 읽고, 현재 진행 중인 항목이 무엇인지 파악한다.
- 진행 중인 항목이 있으면 해당 항목을 이어서 작업한다.
- 진행 중인 항목이 없으면, 사용자에게 어떤 항목을 진행할지 반드시 물어본 후 작업을 시작한다.
- ROADMAP.md의 항목 상태 업데이트(완료 표시 등)는 사용자 확인 후 진행한다.

### Project Manager 에이전트

로드맵 작업은 **Project Manager 에이전트** (`.claude/agents/project-manager.md`)가 조율한다.

- **계획 단계**: PM이 Explore, Architect, Executor 등 전문 에이전트의 의견을 수렴하여 실행 계획을 수립한다.
- **사용자 승인**: 작업 범위, 기술 접근, 순서 등은 반드시 사용자 승인 후 진행한다.
- **실행 단계**: 승인된 계획에 따라 Backend/Frontend/AI Agent 팀을 병렬 배치한다.
- **완료 단계**: 검증 기준 충족 확인 후 사용자에게 로드맵 상태 업데이트를 요청한다.

## Conventions

- 작업은 명확한 단위로 구분하고, 각 작업 단위별로 검증 후 커밋한다.
- 커밋은 반드시 사용자 승인 후 진행한다. 특정 지시가 있는 경우는 반드시 따른다.
- Backend(firehub-api), Frontend(firehub-web), AI Agent(firehub-ai-agent)는 Claude 팀으로 분리하여 병렬 작업한다.
- 팀 내에서도 독립적인 모듈은 SubAgent를 활용하여 최대한 병렬화한다.
- 스크린샷은 프로젝트 루트의 `snapshots/` 폴더에 저장한다.
- **검증 가능한 코드 작성**: 모든 코드는 검증 가능해야 하며, 백엔드(firehub-api)와 AI 에이전트(firehub-ai-agent)는 반드시 테스트 코드(TC)를 함께 작성한다.
- 각 앱의 상세 아키텍처와 규칙은 해당 모듈의 CLAUDE.md를 참조한다.
