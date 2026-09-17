# Inter-App Architecture

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

## 통신 프로토콜

- **web → api**: Vite 프록시(`/api` → `localhost:8080`), JWT Bearer token 인증
- **web → ai-agent**: SSE 스트리밍 (`POST /agent/chat`), JWT Bearer token 인증
- **ai-agent → api**: 내부 서비스 인증 — `Authorization: Internal {token}` + `X-On-Behalf-Of: {userId}` + `X-On-Behalf-Of-Tenant: {tenantId}` 헤더. 테넌트는 원요청(웹 세션 JWT의 tenant 클레임)에서 전파되며, api가 대행 대상의 ACTIVE 멤버십과 대조한 뒤에만 채택한다. 헤더가 없으면 멤버십이 정확히 1개일 때만 역추론하는 폴백(멀티 워크스페이스 사용자는 권한 0개 → 403)

## API 규칙

- 모든 API: `/api/v1/**`
- 공개 엔드포인트: `/api/v1/auth/**`, `/api/v1/triggers/api/**`, `/api/v1/triggers/webhook/**`
- 나머지는 JWT Bearer token 필요

## Tech Stack

| App | Stack |
|-----|-------|
| **firehub-api** | Spring Boot 3.4 + Java 21, jOOQ (not JPA), Flyway, Spring Security + JWT |
| **firehub-web** | Vite + React 19 + TypeScript, TanStack Query, React Router v7, shadcn/ui, Tailwind CSS v4 |
| **firehub-ai-agent** | Node.js + TypeScript, Express 4, Claude Agent SDK, MCP 도구 36종 |
| **Database** | PostgreSQL 16 (Docker), `public` 스키마(메타데이터) + `data` 스키마(동적 사용자 테이블) |
| **Monorepo** | pnpm workspaces + Turborepo |
