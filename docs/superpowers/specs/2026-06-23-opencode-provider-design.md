# OpenCode 프로바이더 추가 설계

- 날짜: 2026-06-23
- 상태: 설계 승인 → 구현 계획 대기
- 관련: `apps/firehub-ai-agent`, `apps/firehub-api`, `apps/firehub-web`

## 1. 목적 / 배경

현재 AI 옵션(`ai.agent_type`)은 3가지다.

| UI 라벨 | enum 값 | 인증 키 | 실행 엔진 | 비용 |
|---|---|---|---|---|
| AI Agent (SDK) | `sdk` | `ai.api_key` | Claude Agent SDK (in-process) | API 과금 |
| Claude Code (구독) | `cli` | `ai.cli_oauth_token` | Claude Code CLI 서브프로세스 | 구독 (고정) |
| Claude Code (API) | `cli-api` | `ai.api_key` | Claude Code CLI 서브프로세스 | API 과금 |

여기에 **4번째 옵션 `opencode`** (SST OpenCode CLI, `opencode.ai`)를 **완전 통합**으로 추가한다. 채팅이 실제 동작하고, firehub MCP 도구 36개 + firehub 전용 subagent 11개가 OpenCode에서도 동등하게 호출되어야 한다.

### 인증 방침 (옵션 3)
OpenCode → 모델 인증은 **배포 환경에 미리 구성된 OpenCode 인증**(`opencode auth login` 결과 `auth.json` 또는 `ANTHROPIC_API_KEY`/`OPENAI_API_KEY` 등 환경변수)에 의존한다. 따라서 설정 화면에 **새 API 키/토큰 입력 필드를 추가하지 않는다.**

## 2. 핵심 통찰: 가장 어려운 부분은 이미 해결돼 있음

OpenCode는 **in-memory MCP를 지원하지 않으므로**(local=별도 자식 프로세스, remote=HTTP) firehub 도구를 별도 transport로 노출해야 한다. 그런데 이 코드베이스에는 이미 그 서버가 있다.

- `apps/firehub-ai-agent/src/mcp/stdio-server.ts` — `USER_ID` / `API_BASE_URL` / `INTERNAL_SERVICE_TOKEN`을 **환경변수로 받아** 36개 firehub 도구를 stdio MCP로 노출. Claude CLI 프로바이더(`agent-cli.ts:74-89`)가 요청마다 임시 `mcp.json`을 만들어 이 서버를 spawn한다.
- OpenCode `mcp` 설정의 `{ type: "local", command: [...], environment: {...} }`는 위 stdio-server 호출과 **거의 1:1 매핑**된다. → **새 MCP 서버 작성 불필요. 기존 `stdio-server.ts` 재사용.**

### 두 개의 인증 레이어 (둘 다 명시)
1. **OpenCode → 모델**: 배포 측 `opencode auth` / 환경변수 (설정 변경 없음).
2. **firehub MCP → firehub-api**: `stdio-server`에 `INTERNAL_SERVICE_TOKEN` + `USER_ID` 주입. **요청마다 그 사용자의 `USER_ID`로 `opencode.json`을 생성**하므로 per-user 격리(`X-On-Behalf-Of`)가 보장된다.

## 3. 아키텍처 결정

### 결정 A: 통합 메커니즘 = CLI 원샷 (`opencode run --format json`)
- 요청마다 `opencode` 프로세스를 spawn하고, 요청별 `opencode.json`을 생성해 그 사용자의 `USER_ID`를 MCP `environment`에 주입한다. 기존 `executeCliAgent` 패턴과 동일.
- **서버 모드(`opencode serve` + SDK)는 채택하지 않음.** 서버 모드의 MCP 설정은 **서버 단위(전역)**라 세션마다 `USER_ID`를 바꿀 수 없어 per-user 격리가 깨진다. (정정 불가 correctness 결함)

### 결정 B: MCP 노출 = 기존 `stdio-server.ts` 재사용
- 요청별 `opencode.json`의 `mcp.firehub`를 `type: "local"`, `command`/`environment`로 구성. dev/prod 런타임 분기(`getStdioServerCommand()`)도 기존 로직 재사용.

### 결정 C: subagent = 완전 동등 (OpenCode 포맷으로 변환)
- OpenCode에도 subagent 개념이 있다(primary/subagent, `mode` 필드, Task 도구 위임, `permission` 객체).
- 정의 경로: 프로젝트 `.opencode/agents/*.md` (frontmatter + 프롬프트 본문).
- `loadSubagents()`가 반환하는 Claude `AgentDefinition`을 **OpenCode frontmatter로 변환하는 셔리얼라이저**를 새로 작성한다. (`agent-cli.ts`의 `writeSubagentDefinitions()` 패턴, frontmatter 스키마만 교체)

  | Claude `.claude/agents/*.md` | OpenCode `.opencode/agents/*.md` |
  |---|---|
  | `name` | (파일명으로 식별) |
  | `description` | `description` (필수) |
  | `tools:` 화이트리스트 | `permission:` 객체 (allow/ask/deny, MCP 와일드카드) |
  | `model` | `model` |
  | — | `mode: subagent` |

### 결정 D: SSE 계약 유지
출력은 기존과 동일하게 `init / text / tool_use / tool_result / turn / done / error`로 변환한다. **프론트엔드 채팅 UI는 무변경.**

## 4. 변경/추가 파일

### Frontend (`apps/firehub-web`)
- `src/pages/admin/SettingsPage.tsx:29-33` — `AGENT_TYPE_OPTIONS`에 `{ value: 'opencode', label: 'OpenCode' }` 추가.
- `src/pages/admin/SettingsPage.tsx:293-386` — `opencode` 선택 시 인증 입력 분기에서 제외. API 키/OAuth 입력란 대신 "배포 환경에 구성된 OpenCode 인증을 사용합니다" 안내 표시.

### Backend (`apps/firehub-api`)
- `settings/service/SettingsService.java:251-254` — 허용 enum 집합에 `opencode` 추가: `Set.of("sdk", "cli", "cli-api", "opencode")`. 에러 메시지도 갱신.
- `ai/service/AiAgentProxyService.java:147-183` — `opencode`는 api_key/oauth 검증을 **면제**하고(인증 정보 없이 통과), `agentType`만 요청 바디에 포함.

### ai-agent (`apps/firehub-ai-agent`) — 핵심
- `src/providers/types.ts` — `ProviderConfig.agentType` 유니온에 `'opencode'` 추가.
- `src/providers/provider-factory.ts:8-21` — `case 'opencode': return new OpenCodeChatProvider(...)` 추가.
- **신규** `src/providers/opencode-chat-provider.ts` — `claude-cli-chat-provider.ts`와 동일한 얇은 래퍼. `executeOpenCodeAgent()` 호출.
- **신규** `src/agent/agent-opencode.ts` — 코어. `agent-cli.ts`를 템플릿으로:
  - 요청별 `opencode.json` 생성 (`mcp.firehub` local + `environment` 주입, model/agent 설정).
  - `.opencode/agents/*.md` 생성 (subagent 동등성) — 신규 셔리얼라이저 사용.
  - `opencode run <msg> --format json [--session <id>]` spawn.
  - stdout `--format json` 이벤트 파싱 → `SSEEvent` 변환 (격리된 단일 파서 함수).
  - 세션 재개(`--session`/`--continue`), abort signal, stderr 캡처, 트랜스크립트 저장은 `agent-cli.ts` 로직 차용.
- **신규** subagent 셔리얼라이저 (`agent-opencode.ts` 내 또는 별도 모듈) — `AgentDefinition` → OpenCode frontmatter md.
- 가능 시 정책/실패 가드(`tool-policy.ts`, `failure-streak.ts`)도 OpenCode 도구 네이밍에 맞춰 적용 (§6-2 참조).

### 테스트
- `agent-opencode.test.ts` (Vitest) — 이벤트 파싱(샘플 JSON 라인 → SSEEvent), `opencode.json` 생성 내용, subagent 셔리얼라이저 출력 검증.
- nock으로 HTTP, `vi.mock`으로 파일 I/O 모킹 (앱 컨벤션).

### 배포 (`.claude/docs/deploy.md`)
- `opencode` 바이너리를 ai-agent Docker 이미지에 포함.
- 배포 환경에 OpenCode 모델 인증 구성(`auth.json` 마운트 또는 provider API 키 환경변수).
- 배포 규칙: 작업 전 `.claude/docs/deploy.md` 먼저 읽기.

## 5. 요청 흐름 (opencode 선택 시)

```
Frontend → /api/v1/ai/chat (agent_type=opencode)
  → AiAgentProxyService: agentType만 전달 (인증 정보 없음)
  → ai-agent POST /agent/chat
  → ProviderFactory → OpenCodeChatProvider → executeOpenCodeAgent()
      ├─ 요청별 opencode.json 생성 (mcp.firehub: USER_ID 주입)
      ├─ .opencode/agents/*.md 생성
      └─ spawn `opencode run --format json`
          ├─ MCP firehub (stdio-server, USER_ID별 격리) ↔ firehub-api
          └─ stdout JSON 이벤트 → SSEEvent(init/text/tool_use/tool_result/turn/done/error)
  ← SSE → Frontend (UI 무변경)
```

## 6. 구현 1단계: 실측으로 확정해야 할 미확정 항목

opencode는 현재 dev 환경에 **미설치**(`opencode not found`)이고, 아래 2건은 공식 문서에 미명시다. **구현 첫 단계는 "opencode 설치 + 아래 실측"이다.**

### 6-1. `opencode run --format json` 이벤트 스키마
- 실제 출력을 캡처해 이벤트 `type`과 `part` 구조(text 델타 / tool 호출 / tool 결과 / step/turn 경계 / 최종 result·토큰 사용량)의 필드명을 확정한다.
- 파서는 `agent-opencode.ts` 내 **단일 함수로 격리**해, 스키마 확정 후 그 함수만 손보면 되도록 한다.
- 토큰 사용량(`done`의 inputTokens/outputTokens)을 OpenCode가 제공하는지 확인 — 없으면 0으로 채우고 그 사실을 기록.

### 6-2. OpenCode의 MCP 도구 네이밍
- `firehub` 서버의 도구가 OpenCode에서 어떤 이름으로 노출되는지(예: `firehub_<tool>` 형태) 실측한다.
- 이 이름은 **시스템 프롬프트(`system-prompt.ts`)와 `tool-policy.ts`**(현재 Claude의 `mcp__firehub__*` 기준)에 영향. 실측 후:
  - 시스템 프롬프트의 도구 참조를 OpenCode 네이밍에 맞게 분기/매핑.
  - `tool-policy.ts`의 화이트/블랙리스트 패턴을 OpenCode 네이밍으로 매핑(또는 OpenCode `permission`으로 대체).

## 7. 비범위 (Out of scope)
- OpenAI 등 비-Anthropic 모델을 **UI에서 선택**하는 기능 (모델/provider는 배포 측 OpenCode 설정에서 고정; 옵션 3).
- `opencode serve` 서버 모드 통합.
- OpenCode remote(HTTP) MCP transport (local stdio만 사용).

## 8. 리스크 / 완화
- **이벤트 스키마 변동**: 파서 단일 함수 격리 + 테스트로 회귀 방지.
- **도구 네이밍 불일치로 프롬프트/정책 우회**: 실측 후 매핑 확정, 정책 위반 런타임 차단 유지.
- **배포 이미지 비대화 / 인증 누락**: Dockerfile·deploy.md에 명시, 인증 미구성 시 명확한 `error` SSE 반환.
- **subagent frontmatter 비호환**: 셔리얼라이저 단위 테스트로 OpenCode 스키마 준수 검증.
