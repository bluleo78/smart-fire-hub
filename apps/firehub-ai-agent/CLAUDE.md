# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

See also the root [CLAUDE.md](../../CLAUDE.md) for monorepo-level commands and cross-app architecture.

## Commands

```bash
pnpm dev              # 개발 서버 실행 (tsx watch, port 3001)
pnpm build            # TypeScript 빌드 (dist/)
pnpm typecheck        # 타입 체크 (tsc --noEmit)
pnpm test             # Vitest 테스트 실행
pnpm test:watch       # Vitest watch 모드
pnpm test -- src/mcp/api-client.test.ts          # 단일 파일 테스트
pnpm test -- -t "should list categories"          # 단일 테스트 케이스
```

## Tech Stack

- Runtime: Node.js + TypeScript (ESM, `NodeNext` module resolution)
- Framework: Express 4
- AI SDK: `@anthropic-ai/claude-agent-sdk` — `query()` for agent execution, `createSdkMcpServer()` + `tool()` for MCP tool registration
- HTTP Client: Axios (`FireHubApiClient`)
- Validation: Zod v4 (import from `zod/v4`)
- Test: Vitest + nock (HTTP mocking)

## Architecture

이 서비스는 firehub-web 프론트엔드와 firehub-api 백엔드 사이에 위치하는 AI 에이전트 서비스입니다. 사용자 메시지를 받아 Claude Agent SDK로 처리하고, MCP 도구를 통해 firehub-api를 호출합니다.

### Request Flow

```
Frontend (SSE) → POST /agent/chat → internalAuth middleware → executeAgent()
  → Claude Agent SDK query() → MCP tools → FireHubApiClient → firehub-api (Spring Boot)
  ← SSE events (init, text, tool_use, tool_result, turn, done, error)
```

### Key Modules

**`src/routes/chat.ts`** — Express 라우터. 3개 엔드포인트:
- `POST /agent/chat` — SSE 스트리밍 채팅. 요청마다 `executeAgent()` AsyncGenerator를 소비하여 SSE 이벤트를 전송. 세션 컴팩션(토큰 초과 시 요약 + 새 세션 전환) 로직 포함
- `GET /agent/history/:sessionId` — JSONL 트랜스크립트에서 대화 이력 조회
- `GET /agent/health` — 헬스체크

**`src/agent/agent-sdk.ts`** — Agent SDK 통합 핵심. `executeAgent()`는 AsyncGenerator로 SDK의 `query()` 스트림을 SSE 이벤트(`SSEEvent`)로 변환. `processMessage()`에서 SDK 메시지 타입별 처리:
- `system` → `init` (세션 ID 반환)
- `assistant` → `text`, `tool_use`
- `user` → `tool_result`
- `result` → `done` / `error` (토큰 사용량 포함)
- `stream_event` → `text` (text_delta 스트리밍)

세션 재개: `sessionId`가 있으면 `options.resume`에 전달하여 이전 대화를 이어감. Claude Code의 `~/.claude/projects/` 하위 JSONL 파일에 트랜스크립트 저장됨.

**`src/mcp/firehub-mcp-server.ts`** — MCP 서버 정의. `createSdkMcpServer()`로 `firehub` 네임스페이스 서버 생성. 36개 도구를 `safeTool()` 래퍼로 등록 (에러 시 `isError: true` 반환). 도구 카테고리: 카테고리(3), 데이터셋(5), 데이터 조작(8), 파이프라인(6), 트리거(4), API 연결(5), 기타(2). Zod v4 스키마로 입력 검증.

**`src/mcp/api-client.ts`** — Axios 기반 HTTP 클라이언트. firehub-api의 `/api/v1/*` 엔드포인트 호출. `Authorization: Internal {token}` + `X-On-Behalf-Of: {userId}` 헤더로 내부 서비스 인증 및 사용자 대행.

**`src/agent/compaction.ts`** — 세션 토큰 초과 감지 및 대화 요약. 인메모리 토큰 카운트 → JSONL 파일 크기 폴백 (1.45 bytes/token 비율). 초과 시 Anthropic API(haiku)로 요약 생성, 실패 시 템플릿 폴백.

**`src/agent/transcript-reader.ts`** — Claude SDK가 저장한 JSONL 트랜스크립트(`~/.claude/projects/{projectId}/{sessionId}.jsonl`)를 파싱하여 user/assistant 메시지 추출. assistant 메시지는 동일 API message ID로 병합, tool_result 블록은 제외.

**`src/middleware/auth.ts`** — `Internal` 토큰 기반 인증 미들웨어. `timingSafeEqual`로 토큰 비교.

### Environment Variables

`.env.local` 파일에서 로드 (dotenv). `.env.example` 참조:
- `PORT` — 서버 포트 (기본 3001)
- `INTERNAL_SERVICE_TOKEN` — firehub-api와 공유하는 내부 인증 토큰
- `API_BASE_URL` — firehub-api 주소 (기본 `http://localhost:8080/api/v1`)
- `ANTHROPIC_API_KEY` — Claude API 키 (Agent SDK + 컴팩션 요약)
- `MAX_TURNS` — 에이전트 최대 턴 수 (기본 10)

### Agent SDK Integration

`query()` 호출 시 주요 설정:
- `permissionMode: 'bypassPermissions'` — 도구 실행 자동 허용
- `allowedTools: ['mcp__firehub__*']` — firehub MCP 도구만 허용
- `mcpServers: { firehub }` — 인메모리 MCP 서버 (네트워크 없이 직접 호출)
- 환경변수에서 `CLAUDECODE`, `CLAUDE_CODE_ENTRYPOINT` 제거하여 중첩 세션 방지

## Conventions

- **코드 수정 시 반드시 테스트 작성**: 모든 코드 변경은 대응하는 테스트 코드를 함께 작성하거나 업데이트한다. 테스트 없이 코드만 수정하지 않는다.
- **테스트 패턴**: Vitest + nock. HTTP 호출은 nock으로 모킹, 파일 I/O는 `vi.mock`으로 모킹한다.
- **테스트 파일 위치**: 소스 파일과 같은 디렉토리에 `*.test.ts`로 배치한다. (예: `auth.ts` → `auth.test.ts`)
- **MCP 도구 추가 시**: `firehub-mcp-server.ts`에 `safeTool()` 래퍼로 등록하고, `api-client.ts`에 대응 메서드 추가, `system-prompt.ts`에 도구 설명 추가.
- **SSE 이벤트 타입**: `init`, `text`, `tool_use`, `tool_result`, `turn`, `done`, `error` — 프론트엔드와의 계약.
