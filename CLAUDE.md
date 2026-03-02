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
- **작업 완료 시 워크플로**: 사용자가 "작업 완료", "커밋", "마무리" 등을 지시하면 다음 순서로 진행한다:
  1. `docs/ROADMAP.md`를 읽고 완료된 작업 항목의 상태를 업데이트한다 (⬜→✅, 진행률, 검증 기준, 변경 이력 등)
  2. 로드맵 변경 사항을 포함하여 커밋한다
- Backend(firehub-api), Frontend(firehub-web), AI Agent(firehub-ai-agent)는 Claude 팀으로 분리하여 병렬 작업한다.
- 팀 내에서도 독립적인 모듈은 SubAgent를 활용하여 최대한 병렬화한다.
- 스크린샷은 프로젝트 루트의 `snapshots/` 폴더에 저장한다.
- **검증 가능한 코드 작성**: 모든 코드는 검증 가능해야 하며, 백엔드(firehub-api)와 AI 에이전트(firehub-ai-agent)는 반드시 테스트 코드(TC)를 함께 작성한다.
- 각 앱의 상세 아키텍처와 규칙은 해당 모듈의 CLAUDE.md를 참조한다.

### 계획 수립 원칙

- **OMC 전문 스킬 활용**: 계획 수립 시 oh-my-claudecode의 `/plan` (또는 `/ralplan`) 스킬을 사용한다. Planner, Architect, Analyst 등 전문 에이전트가 협력하여 깊고 정밀한 계획을 수립한다.
- **깊고 자세하게**: 계획은 파일 단위, 함수 단위까지 구체적으로 명시한다. "~한다" 수준이 아니라 "어떤 파일에 어떤 클래스/함수를 추가하고, 입출력 스펙은 무엇이며, 에러 케이스는 어떻게 처리한다"까지 기술한다.
- **검증 가능하게**: 모든 계획 항목에는 검증 기준(TC 목록, 수동 검증 시나리오, 빌드/타입체크 통과 등)을 반드시 포함한다. 검증 기준이 없는 항목은 계획이 완성된 것이 아니다.
- **구현 후 검증 필수**: 구현 완료 후 반드시 검증 단계를 거친다. 백엔드는 통합 테스트 실행, 프론트엔드는 빌드+타입체크+스크린샷 확인, AI 에이전트는 단위 테스트 실행. 검증 미통과 시 수정 후 재검증한다.
