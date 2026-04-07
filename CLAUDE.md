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

### 에이전트 팀 구성

모든 업무 지시는 **Project Leader** (`.claude/agents/project-leader.md`)에게 내린다. Project Leader가 업무 규모를 판단하여 적절한 에이전트를 투입하고 전체 흐름을 오케스트레이션한다.

| 에이전트 | 역할 |
|---------|------|
| **Project Leader** | 업무 총괄 — 분석→설계→구현→검증→완료 전체 오케스트레이션 |
| **Analyst** | 업무 분석/조사/기획 — 요구사항 추출, 영향 범위, 수용 기준 |
| **Architect** | 아키텍처 설계 리드 — 앱 간 연동, API 스펙, 코드 리뷰 |
| **Backend Developer** | firehub-api 구현 — Java/Spring Boot/jOOQ |
| **Frontend Developer** | firehub-web 구현 — React/TypeScript/Playwright |
| **AI Agent Developer** | firehub-ai-agent 구현 — Node.js/Claude SDK/MCP |
| **UI/UX Designer** | 화면 설계 리드 — 디자인 시스템, UI/UX, 접근성 |
| **QA Tester** | 통합 검증 리드 — 빌드/테스트/회귀, 앱 간 연동 검증 |
| **Project Manager** | 로드맵 관리 — 상태 추적, 사용자 승인, 완료 기록 |

**업무 흐름** (각 단계마다 리드 + 전원 참여):
```
사용자 지시 → Project Leader
  → Phase 1: 분석 (Analyst 리드)
  → Phase 2a: 아키텍처 설계 (Architect 리드)
  → Phase 2b: 화면 설계 (Designer 리드, 병렬)
  → Phase 3: 계획 (Project Leader 리드)
  → Phase 4: 구현 (각 Developer 리드, 병렬)
  → Phase 5: 검증 (QA Tester 리드)
  → Phase 6: 완료 (Project Leader → PM 로드맵 업데이트)
```

## 운영 배포

배포 스크립트: `./scripts/deploy.sh [api|web|ai-agent|all]`

```bash
./scripts/deploy.sh api        # API만 배포
./scripts/deploy.sh all        # 전체 배포
```

### Docker 빌드 규칙 (중요)

각 앱의 Dockerfile은 **서로 다른 build context**를 사용한다. 잘못된 context로 빌드하면 소스가 누락된다.

| App | Build Context | 빌드 명령 |
|-----|---------------|----------|
| **firehub-api** | `apps/firehub-api/` (자체 디렉토리) | `docker build apps/firehub-api/` |
| **firehub-web** | `.` (프로젝트 루트) | `docker build -f apps/firehub-web/Dockerfile .` |
| **firehub-ai-agent** | `.` (프로젝트 루트) | `docker build -f apps/firehub-ai-agent/Dockerfile .` |

- **firehub-api**는 Dockerfile 내부에서 `COPY src/ src/`로 상대 경로를 사용하므로 context가 `apps/firehub-api/`여야 한다
- **firehub-web, firehub-ai-agent**는 `COPY apps/firehub-web/ ...` 형태로 절대 경로를 사용하므로 context가 프로젝트 루트(`.`)여야 한다
- **절대로** `docker build -f apps/firehub-api/Dockerfile .`으로 빌드하지 않는다 (소스 누락)
- 반드시 `--no-cache` 옵션을 사용하여 캐시로 인한 소스 누락을 방지한다

### 운영 환경

- 이미지 레지스트리: `ghcr.io/bluleo78/smart-fire-hub/{api,web,ai-agent}:latest`
- 운영 디렉토리: `~/prod/smart-fire-hub/` (docker-compose.yml + nginx.conf + .env)
- 배포 후 반드시 `docker compose up -d --force-recreate {app}`으로 컨테이너 재생성

## Conventions

- **코드 주석 필수**: 모든 코드 생성/수정 시 한국어 주석을 작성한다. 클래스, 메서드, 주요 로직 블록에 "무엇을 하는지"와 "왜 이렇게 하는지"를 설명하는 주석을 반드시 포함한다. 사용자가 코드를 이해할 수 있도록 충분히 상세하게 작성한다.
- 작업은 명확한 단위로 구분하고, 각 작업 단위별로 검증 후 커밋한다.
- **커밋/배포 금지 (사용자 승인 필수)**: 절대로 자의적으로 `git commit`, `git push`, `deploy.sh`를 실행하지 않는다. 반드시 사용자에게 "커밋할까요?", "배포할까요?"를 물어보고 명시적 승인을 받은 후에만 진행한다. "커밋해", "배포해", "커밋하고 배포하자" 등 사용자의 직접 지시가 있을 때만 실행한다.
- **작업 완료 시 워크플로**: 사용자가 "작업 완료", "커밋", "마무리" 등을 지시하면 다음 순서로 진행한다:
  1. `docs/ROADMAP.md`를 읽고 완료된 작업 항목의 상태를 업데이트한다 (⬜→✅, 진행률, 검증 기준, 변경 이력 등)
  2. 로드맵 변경 사항을 포함하여 커밋한다
- Backend(firehub-api), Frontend(firehub-web), AI Agent(firehub-ai-agent)는 Claude 팀으로 분리하여 병렬 작업한다.
- 팀 내에서도 독립적인 모듈은 SubAgent를 활용하여 최대한 병렬화한다.
- 스크린샷은 프로젝트 루트의 `snapshots/` 폴더에 저장한다.
- **검증 가능한 코드 작성**: 모든 코드는 검증 가능해야 하며, 백엔드(firehub-api)와 AI 에이전트(firehub-ai-agent)는 반드시 테스트 코드(TC)를 함께 작성한다. 프론트엔드(firehub-web)는 반드시 Playwright E2E 회귀 테스트를 함께 작성한다 — 입력→API payload→응답→UI 반영 전체 파이프라인을 검증하는 수준이어야 한다.
- 각 앱의 상세 아키텍처와 규칙은 해당 모듈의 CLAUDE.md를 참조한다.

### 계획 수립 원칙

- **OMC 전문 스킬 활용**: 계획 수립 시 oh-my-claudecode의 `/plan` (또는 `/ralplan`) 스킬을 사용한다. Planner, Architect, Analyst 등 전문 에이전트가 협력하여 깊고 정밀한 계획을 수립한다.
- **깊고 자세하게**: 계획은 파일 단위, 함수 단위까지 구체적으로 명시한다. "~한다" 수준이 아니라 "어떤 파일에 어떤 클래스/함수를 추가하고, 입출력 스펙은 무엇이며, 에러 케이스는 어떻게 처리한다"까지 기술한다.
- **검증 가능하게**: 모든 계획 항목에는 검증 기준(TC 목록, 수동 검증 시나리오, 빌드/타입체크 통과 등)을 반드시 포함한다. 검증 기준이 없는 항목은 계획이 완성된 것이 아니다.
- **구현 후 검증 필수**: 구현 완료 후 반드시 검증 단계를 거친다. 백엔드는 통합 테스트 실행, 프론트엔드는 빌드+타입체크+스크린샷 확인, AI 에이전트는 단위 테스트 실행. 검증 미통과 시 수정 후 재검증한다.
