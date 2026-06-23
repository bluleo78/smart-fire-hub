# OpenCode 프로바이더 추가 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AI 옵션(`ai.agent_type`)에 4번째 값 `opencode`를 추가해, OpenCode CLI로 채팅이 동작하고 firehub MCP 도구 36개 + subagent 11개가 동등하게 호출되게 한다.

**Architecture:** 기존 Claude CLI 프로바이더(`agent-cli.ts`)와 동일한 "요청별 서브프로세스 spawn" 패턴. 요청마다 `opencode.json`을 생성해 그 사용자의 `USER_ID`를 MCP `environment`에 주입(per-user 격리), `.opencode/agents/*.md`로 subagent를 동등 제공, `opencode run --format json` 출력을 기존 `SSEEvent`로 변환한다. firehub MCP는 기존 `stdio-server.ts`를 그대로 재사용한다.

**Tech Stack:** Node.js + TypeScript (ESM, ai-agent), Express, Vitest + nock; Spring Boot + jOOQ (api); React 19 + Vite + Playwright (web).

## Global Constraints

- 인증 방침 = **옵션 3**: OpenCode→모델 인증은 배포 측 `opencode auth`/환경변수에 의존. 설정 UI에 새 키 입력 필드 **추가 금지**.
- `ai.agent_type` 허용 enum: `sdk`, `cli`, `cli-api`, **`opencode`** (4개).
- SSE 계약 불변: `init / text / tool_use / tool_result / turn / done / error`. 프론트엔드 채팅 UI 무변경.
- per-user 격리: MCP `environment.USER_ID`는 **요청마다** 그 사용자 id로 생성. 서버 모드(`opencode serve`) 사용 금지.
- 코드 주석은 **한국어 필수** (무엇을·왜).
- 커밋/배포는 **사용자 명시 승인 후에만**. 배포 작업 전 `.claude/docs/deploy.md` 먼저 읽기.
- 테스트 필수: ai-agent/api → 단위/통합 TC, web → Playwright E2E.
- 구현 1단계(Task 1) 전까지 **opencode 미설치**(`opencode not found`). 이벤트 스키마·MCP 도구 네이밍은 Task 1에서 실측 확정.

---

### Task 1: 환경 준비 + 스파이크 (이벤트 스키마 · MCP 도구 네이밍 실측)

이 Task는 TDD가 아니라 **스파이크**다. 외부 CLI의 미명시 출력 포맷을 실측해 이후 Task의 파서/프롬프트 코드를 확정하기 위함. 산출물은 캡처된 출력 픽스처 + 확정된 매핑 메모.

**Files:**
- Create: `apps/firehub-ai-agent/src/agent/__fixtures__/opencode-run.jsonl` (실측 캡처)
- Create: `apps/firehub-ai-agent/src/agent/__fixtures__/opencode-schema-notes.md` (확정 메모)

- [ ] **Step 1: opencode 설치 및 버전 확인**

```bash
# 설치 (공식 권장 방식)
curl -fsSL https://opencode.ai/install | bash
# 또는: npm i -g opencode-ai
opencode --version
```

Expected: 버전 문자열 출력 (예: `0.x.y`). `opencode not found`가 사라져야 함.

- [ ] **Step 2: 모델 인증 구성 — Bedrock (테스트), 옵션 3 = 전역 설정에 둠)**

테스트는 **Amazon Bedrock**(사용자 제공 주소 + API 키)으로 연결한다. provider/모델/인증은 **우리 코드가 만드는 요청별 opencode.json 이 아니라 전역 설정**(`~/.config/opencode/opencode.json` 또는 `OPENCODE_CONFIG`/환경변수)에 둔다. 그래야 운영에서 provider 를 바꿔도 코드 변경이 없다(옵션 3).

```bash
# 전역 opencode 설정에 Bedrock provider/model 구성 (정확한 블록 형식은 아래 메모에서 확정)
# 표준 Bedrock 은 AWS SigV4 자격증명을 쓰나, "주소+API키" 형태면 custom provider(baseURL+apiKey)일 수 있음.
opencode auth list   # 또는 auth login 으로 Bedrock 등록
opencode run "say hi" --format json   # 모델 응답이 오는지 인증 확인
```

Expected: Bedrock 모델로 응답이 스트리밍됨. (인증 미구성 시 명확한 오류)

- [ ] **Step 3: 최소 MCP 설정으로 firehub stdio 서버 연결 + 도구 네이밍 캡처**

스크래치 디렉토리에 `opencode.json` 작성 후 한 줄 도구 호출 유도:

```bash
SCRATCH=$(mktemp -d)
cat > "$SCRATCH/opencode.json" <<'JSON'
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "firehub": {
      "type": "local",
      "command": ["node", "/ABS/PATH/apps/firehub-ai-agent/dist/mcp/stdio-server.js"],
      "environment": {
        "API_BASE_URL": "http://localhost:8080/api/v1",
        "INTERNAL_SERVICE_TOKEN": "<dev-token>",
        "USER_ID": "1"
      },
      "enabled": true
    }
  }
}
JSON
cd "$SCRATCH"
opencode run "list available firehub tools and call list_categories" --format json > opencode-run.jsonl 2>&1
```

(개발 런타임에서 `dist/`가 없으면 `tsx`로 stdio-server.ts를 실행하는 command 사용 — `getStdioServerCommand()` 로직 참조.)

- [ ] **Step 4: 캡처 출력에서 다음을 확정해 메모 작성**

`opencode-schema-notes.md`에 아래 표를 채운다 (실측값으로):

```markdown
# OpenCode 실측 메모 (opencode --version: ___)

## `--format json` 이벤트 스키마
| 의미 | 이벤트 식별 (type/필드) | 텍스트/데이터 위치 |
|---|---|---|
| 세션 id | ___ | ___ |
| 텍스트 델타/블록 | ___ | ___ |
| tool 호출(tool_use) | ___ | name=___, input=___ |
| tool 결과(tool_result) | ___ | content=___, is_error=___ |
| 턴/스텝 경계 | ___ | ___ |
| 최종 완료(result) | ___ | tokens 제공 여부=___ (input/output 위치) |

## MCP 도구 네이밍
- firehub 서버의 도구가 OpenCode에서 노출되는 이름 형식: `________` (예: `firehub_list_categories`)
- permission/allow 패턴에 쓸 와일드카드: `________` (예: `firehub_*`)

## 권한(permission) 스키마 ← #0 보안 차단 해소용 (필수)
- opencode.json `permission` 에서 bash/edit/write/webfetch 를 deny 하는 정확한 키: `________`
- firehub MCP 도구만 allow 로 남기는 패턴 표기: `________`
- 기본 Build agent 대신 도구 제한된 커스텀 primary agent 정의가 필요한지: ___

## 비용/턴 상한
- `opencode run` 에 예산($)/턴 수 상한 플래그 유무: ___ (있으면 플래그명, 없으면 "없음" → 리스크 문서화)

## 시스템 프롬프트 / 위임
- `opencode run` 이 cwd 의 `AGENTS.md` 를 시스템 지시로 읽는가: ___ (아니면 대체 주입 경로: ___)
- subagent 위임 관용구: `buildSubagentGuide` 의 `Agent(subagent_type:…)` 문구가 OpenCode(`@mention`/Task)에 통하는가 / 변환 필요한가: ___

## Bedrock provider 블록 (테스트 인증)
- 전역 opencode 설정의 provider 블록 정확한 형식(baseURL/apiKey vs AWS 자격증명): `________`
- 모델 id 형식: `________` (예: `amazon-bedrock/anthropic.claude-...`)

## 세션 재개
- `opencode run --session <id>` 가 외부 발급 id(`oc-<uuid>`)를 수용하는가: ___
- 아니면 OpenCode 자체 발급 세션 id 를 출력 어디서 얻는지: ___ (claudeSessionId 처럼 캡처·재사용 필요)
```

- [ ] **Step 5: 픽스처 커밋**

```bash
git add apps/firehub-ai-agent/src/agent/__fixtures__/opencode-run.jsonl \
        apps/firehub-ai-agent/src/agent/__fixtures__/opencode-schema-notes.md
git commit -m "chore(ai-agent): opencode run 출력/도구 네이밍 실측 픽스처 추가"
```

> **이후 Task들은 이 메모의 확정값을 사용한다.** 아래 Task 4의 파서 코드는 가장 가능성 높은 스키마(`{ "type": "...", "part": {...} }`)를 가정해 작성하되, Step에서 픽스처와 대조해 필드명을 교정한다.

---

### Task 2: ai-agent 타입 + 팩토리에 `opencode` 분기 추가

**Files:**
- Modify: `apps/firehub-ai-agent/src/providers/types.ts:42`
- Modify: `apps/firehub-ai-agent/src/providers/provider-factory.ts:8-21`
- Create: `apps/firehub-ai-agent/src/providers/provider-factory.test.ts` (없으면 생성)

**Interfaces:**
- Produces: `AgentType` 유니온에 `'opencode'` 포함. `ProviderFactory.createChatProvider({agentType:'opencode', ...})` → `OpenCodeChatProvider` 인스턴스 (Task 3에서 구현; 이 Task에서는 import + case만).

- [ ] **Step 1: 실패하는 테스트 작성** — `provider-factory.test.ts`

```typescript
import { describe, it, expect } from 'vitest';
import { ProviderFactory } from './provider-factory.js';
import { OpenCodeChatProvider } from './opencode-chat-provider.js';

describe('ProviderFactory opencode', () => {
  it('agentType=opencode 이면 OpenCodeChatProvider 를 생성한다', () => {
    const provider = ProviderFactory.createChatProvider({ agentType: 'opencode' });
    expect(provider).toBeInstanceOf(OpenCodeChatProvider);
    expect(provider.name).toBe('opencode');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd apps/firehub-ai-agent && pnpm test -- provider-factory`
Expected: FAIL — `opencode-chat-provider.js` 모듈/`opencode` case 없음.

- [ ] **Step 3: 타입 유니온 확장** — `types.ts:42`

```typescript
export type AgentType = 'sdk' | 'cli' | 'cli-api' | 'opencode';
```

- [ ] **Step 4: 최소 래퍼 스텁 생성** — `opencode-chat-provider.ts` (Task 3에서 본문 채움)

```typescript
import type { ChatProvider, ChatProviderOptions, SSEEvent } from './types.js';

/** OpenCode CLI 기반 채팅 프로바이더. 실제 실행은 executeOpenCodeAgent() 에 위임. */
export class OpenCodeChatProvider implements ChatProvider {
  readonly name = 'opencode';
  // Task 3에서 execute() 구현
  async *execute(_options: ChatProviderOptions): AsyncGenerator<SSEEvent> {
    throw new Error('not implemented'); // Task 3 대체
  }
}
```

- [ ] **Step 5: 팩토리 case 추가** — `provider-factory.ts`

```typescript
import { OpenCodeChatProvider } from './opencode-chat-provider.js';
// switch 내부, default 위에 추가:
      case 'opencode':
        // 인증은 배포 환경 opencode auth 에 의존(옵션 3) — 키 주입 없음
        return new OpenCodeChatProvider();
```

- [ ] **Step 6: 통과 확인**

Run: `cd apps/firehub-ai-agent && pnpm test -- provider-factory`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add apps/firehub-ai-agent/src/providers/types.ts \
        apps/firehub-ai-agent/src/providers/provider-factory.ts \
        apps/firehub-ai-agent/src/providers/opencode-chat-provider.ts \
        apps/firehub-ai-agent/src/providers/provider-factory.test.ts
git commit -m "feat(ai-agent): provider-factory 에 opencode 분기 추가"
```

---

### Task 3: subagent → OpenCode `.opencode/agents/*.md` 셔리얼라이저

**Files:**
- Create: `apps/firehub-ai-agent/src/agent/opencode-subagents.ts`
- Create: `apps/firehub-ai-agent/src/agent/opencode-subagents.test.ts`

**Interfaces:**
- Consumes: `loadSubagents(): Record<string, AgentDefinition>` (기존 `subagent-loader.ts`).
- Produces:
  - `serializeOpenCodeSubagent(name: string, def: AgentDefinition): string` — frontmatter md 문자열.
  - `writeOpenCodeSubagentDefinitions(workDir: string, subagents: Record<string, AgentDefinition>): Promise<void>` — `workDir/.opencode/agents/<name>.md` 일괄 작성(기존 stale .md 정리 포함).

- [ ] **Step 1: 실패하는 테스트 작성** — `opencode-subagents.test.ts`

```typescript
import { describe, it, expect } from 'vitest';
import { serializeOpenCodeSubagent } from './opencode-subagents.js';
import type { AgentDefinition } from '@anthropic-ai/claude-agent-sdk';

describe('serializeOpenCodeSubagent', () => {
  it('AgentDefinition 을 OpenCode frontmatter md 로 변환한다', () => {
    const def: AgentDefinition = {
      description: 'Pipeline 전문가',
      prompt: 'You build pipelines.',
      tools: ['mcp__firehub__create_pipeline'],
      model: 'inherit',
    } as AgentDefinition;

    const md = serializeOpenCodeSubagent('pipeline-builder', def);

    // OpenCode 스키마: description 필수, mode: subagent 고정
    expect(md).toContain('description: "Pipeline 전문가"');
    expect(md).toContain('mode: subagent');
    // 본문은 prompt 그대로
    expect(md.trimEnd().endsWith('You build pipelines.')).toBe(true);
    // frontmatter 구분자
    expect(md.startsWith('---\n')).toBe(true);
    expect(md.split('---').length).toBeGreaterThanOrEqual(3);
  });

  it('model 이 inherit 이면 model 필드를 생략한다', () => {
    const def = { description: 'x', prompt: 'p', model: 'inherit' } as AgentDefinition;
    expect(serializeOpenCodeSubagent('a', def)).not.toContain('model:');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd apps/firehub-ai-agent && pnpm test -- opencode-subagents`
Expected: FAIL — 모듈 없음.

- [ ] **Step 3: 구현** — `opencode-subagents.ts`

```typescript
/**
 * Claude AgentDefinition 을 OpenCode subagent 정의(.opencode/agents/*.md)로 변환한다.
 *
 * 이유: OpenCode 는 Claude Code 와 subagent 개념은 같지만 frontmatter 스키마가 다르다.
 *  - Claude: name/description/tools(화이트리스트)/model
 *  - OpenCode: description(필수)/mode/model/permission(allow|ask|deny)
 * firehub subagent(pipeline-builder 등)를 OpenCode 에서도 동등하게 위임받게 하려면
 * 요청별 작업 디렉토리에 OpenCode 포맷 md 를 써둔다(agent-cli.ts 의 패턴과 동일).
 */
import { mkdir, readdir, unlink, writeFile } from 'fs/promises';
import { join } from 'path';
import type { AgentDefinition } from '@anthropic-ai/claude-agent-sdk';

/** 임의 문자열을 YAML double-quoted 스칼라로 안전 직렬화 (한 줄 값용). */
function yamlDoubleQuoted(value: string): string {
  return `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n')}"`;
}

/** AgentDefinition → OpenCode frontmatter md. mode 는 subagent 고정. */
export function serializeOpenCodeSubagent(name: string, def: AgentDefinition): string {
  const lines: string[] = ['---', `description: ${yamlDoubleQuoted(def.description)}`, 'mode: subagent'];

  // model: inherit 은 OpenCode 에 무의미하므로 생략 (배포측 기본 모델 사용)
  if (def.model && def.model !== 'inherit') {
    lines.push(`model: ${def.model}`);
  }
  lines.push('---', '');
  return lines.join('\n') + (def.prompt ?? '');
}

/**
 * subagent 정의를 workDir/.opencode/agents/<name>.md 로 일괄 작성.
 * 매 호출마다 기존 .md 를 정리하고 다시 써 정의 추가/삭제/변경에 일관성을 유지한다.
 */
export async function writeOpenCodeSubagentDefinitions(
  workDir: string,
  subagents: Record<string, AgentDefinition>,
): Promise<void> {
  const agentsDir = join(workDir, '.opencode', 'agents');
  await mkdir(agentsDir, { recursive: true });

  try {
    const existing = await readdir(agentsDir);
    await Promise.all(
      existing.filter((f) => f.endsWith('.md')).map((f) => unlink(join(agentsDir, f)).catch(() => {})),
    );
  } catch {
    /* 디렉터리 부재는 무시 — mkdir 가 보장 */
  }

  for (const [name, def] of Object.entries(subagents)) {
    await writeFile(join(agentsDir, `${name}.md`), serializeOpenCodeSubagent(name, def), 'utf-8');
  }
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd apps/firehub-ai-agent && pnpm test -- opencode-subagents`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-ai-agent/src/agent/opencode-subagents.ts \
        apps/firehub-ai-agent/src/agent/opencode-subagents.test.ts
git commit -m "feat(ai-agent): subagent 를 OpenCode .opencode/agents 포맷으로 변환하는 셔리얼라이저"
```

> **Task 1 메모 반영:** subagent 도구 권한은 메인 잠금(Task 4 `buildOpenCodeConfig.permission`)에 종속된다 — subagent 도 bash/edit/write/webfetch 는 막히고 firehub MCP 만 쓴다. subagent별 추가 제한이 필요하면 frontmatter 에 `permission` 블록을 더한다(네이밍은 Task 1 확정값).

---

### Task 4: `executeOpenCodeAgent` 코어 (spawn + opencode.json + 이벤트 파서)

**Files:**
- Create: `apps/firehub-ai-agent/src/agent/agent-opencode.ts`
- Create: `apps/firehub-ai-agent/src/agent/agent-opencode.test.ts`
- Modify: `apps/firehub-ai-agent/src/providers/opencode-chat-provider.ts` (스텁 → 실제 위임)

**Interfaces:**
- Consumes: `ChatProviderOptions` (types.ts), `getStdioServerCommand()`(현재 `agent-cli.ts` 내부 — Step 1에서 공용화), `writeOpenCodeSubagentDefinitions`(Task 3), `loadSubagents`/`buildSubagentGuide`(기존), `SYSTEM_PROMPT`/`resolveSystemPrompt`(기존).
- Produces:
  - `buildOpenCodeConfig(userId: number, apiBaseUrl: string, internalToken: string): object` — opencode.json 객체. **model 필드 없음**(옵션 3: provider/model 은 배포 측 전역 설정 상속). **permission 잠금 포함.**
  - `parseOpenCodeEvent(msg: Record<string, unknown>): SSEEvent | null` — 한 JSON 라인 → SSEEvent(또는 무시 시 null). **격리된 단일 파서.**
  - `executeOpenCodeAgent(options: ChatProviderOptions): AsyncGenerator<SSEEvent>`.

**보안/범위 결정 (advisor 반영):**
- **#0 권한 잠금(차단 해소)**: `opencode.json.permission` 으로 bash/edit/write/webfetch 를 `deny`, firehub MCP 도구(`firehub_*`)만 `allow`. + opencode 프로세스 env 에서 `INTERNAL_SERVICE_TOKEN` 제거(토큰은 mcp.environment 로 자식에게만 전달). + cwd=per-user workspace. → Claude CLI(`DISALLOWED_TOOLS`/`checkToolPolicy`)와 등가 격리.
- **모델/provider**: `--model`·`model` 필드 **생략**. 배포/테스트 측 전역 opencode 설정(Bedrock 등)의 기본 모델 사용.
- **fileIds(첨부)**: v1 **범위 외** — destructure 하지 않고 무시. (Claude CLI 의 download/attach 로직 미이식)
- **permission 키 이름/도구 네이밍/예산 플래그**: Task 1 실측 메모로 확정 후 교정.

- [ ] **Step 1: `getStdioServerCommand()` 를 공용 모듈로 추출**

`agent-cli.ts` 의 `getStdioServerCommand()`(58-72행)를 새 파일 `src/mcp/stdio-server-command.ts` 로 옮겨 export 하고, `agent-cli.ts` 는 거기서 import 한다. (DRY — opencode/cli 양쪽이 동일 stdio 서버를 가리킴)

```typescript
// src/mcp/stdio-server-command.ts
import { existsSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';

/** firehub MCP stdio 서버 실행 command/args 해석 (prod: node dist, dev: tsx src). */
export function getStdioServerCommand(): { command: string; args: string[] } {
  const __filename = fileURLToPath(import.meta.url);
  const __dirname = dirname(__filename);
  const serverJs = join(__dirname, 'stdio-server.js');
  if (existsSync(serverJs)) return { command: 'node', args: [serverJs] };
  const serverTs = join(__dirname, 'stdio-server.ts');
  const tsxBin = join(__dirname, '..', '..', 'node_modules', '.bin', 'tsx');
  return { command: tsxBin, args: [serverTs] };
}
```

`agent-cli.ts`: 기존 로컬 `getStdioServerCommand` 정의 삭제 후 `import { getStdioServerCommand } from '../mcp/stdio-server-command.js';` 추가. (경로 주의: stdio-server.js 가 같은 mcp/ 디렉토리이므로 join 기준 변경됨)

- [ ] **Step 2: 실패하는 테스트 작성** — `agent-opencode.test.ts`

`parseOpenCodeEvent` 와 `buildOpenCodeConfig` 단위 테스트. (Task 1 픽스처의 실제 필드명으로 교정 전, 가정 스키마 기준)

```typescript
import { describe, it, expect } from 'vitest';
import { parseOpenCodeEvent, buildOpenCodeConfig } from './agent-opencode.js';

describe('buildOpenCodeConfig', () => {
  it('mcp.firehub 에 USER_ID 등 환경변수를 주입한다', () => {
    const cfg = buildOpenCodeConfig(7, 'http://api/v1', 'tok') as any;
    expect(cfg.mcp.firehub.type).toBe('local');
    expect(cfg.mcp.firehub.environment.USER_ID).toBe('7');
    expect(cfg.mcp.firehub.environment.INTERNAL_SERVICE_TOKEN).toBe('tok');
    expect(cfg.mcp.firehub.environment.API_BASE_URL).toBe('http://api/v1');
    expect(Array.isArray(cfg.mcp.firehub.command)).toBe(true);
  });

  it('model 필드를 넣지 않는다 (옵션 3: 배포 측 전역 설정 상속)', () => {
    const cfg = buildOpenCodeConfig(1, 'u', 't') as any;
    expect(cfg.model).toBeUndefined();
  });

  it('permission 으로 bash/edit/write/webfetch 를 deny 하고 firehub MCP 만 allow 한다', () => {
    // ⚠ permission 키 이름/패턴은 Task 1 실측으로 교정
    const cfg = buildOpenCodeConfig(1, 'u', 't') as any;
    expect(cfg.permission.bash).toBe('deny');
    expect(cfg.permission.edit).toBe('deny');
    expect(cfg.permission.write).toBe('deny');
    expect(cfg.permission.webfetch).toBe('deny');
  });
});

describe('parseOpenCodeEvent', () => {
  it('텍스트 이벤트를 SSE text 로 변환한다', () => {
    // ⚠ Task 1 픽스처로 type/필드명 교정 필요
    const ev = parseOpenCodeEvent({ type: 'text', part: { text: '안녕' } });
    expect(ev).toEqual({ type: 'text', content: '안녕' });
  });

  it('tool 호출 이벤트를 SSE tool_use 로 변환한다', () => {
    const ev = parseOpenCodeEvent({ type: 'tool', part: { tool: 'firehub_list_categories', state: { input: { a: 1 } } } });
    expect(ev?.type).toBe('tool_use');
  });

  it('알 수 없는 이벤트는 null 을 반환한다', () => {
    expect(parseOpenCodeEvent({ type: 'unknown_xyz' })).toBeNull();
  });
});
```

- [ ] **Step 3: 실패 확인**

Run: `cd apps/firehub-ai-agent && pnpm test -- agent-opencode`
Expected: FAIL — 모듈 없음.

- [ ] **Step 4: 구현** — `agent-opencode.ts`

```typescript
/**
 * OpenCode CLI agent executor (`opencode run --format json`).
 *
 * agent-cli.ts(Claude CLI) 와 동일한 "요청별 서브프로세스 spawn" 패턴.
 * 요청마다 opencode.json 을 생성해 그 사용자의 USER_ID 를 firehub MCP 의
 * environment 로 주입(per-user 격리)하고, --format json 출력을 SSEEvent 로 변환한다.
 * 인증(OpenCode→모델)은 배포 환경 opencode auth 에 의존(옵션 3) — 키 주입 없음.
 */
import { spawn } from 'child_process';
import { mkdir, readFile, writeFile } from 'fs/promises';
import { homedir } from 'os';
import { join } from 'path';
import { createInterface } from 'readline';
import { randomUUID } from 'crypto';
import type { ChatProviderOptions, SSEEvent } from '../providers/types.js';
import { getStdioServerCommand } from '../mcp/stdio-server-command.js';
import { writeOpenCodeSubagentDefinitions } from './opencode-subagents.js';
import { loadSubagents, buildSubagentGuide } from './subagent-loader.js';
import { SYSTEM_PROMPT } from './system-prompt.js';
import { resolveSystemPrompt } from './prompt-utils.js';
// 트랜스크립트: CLI 와 동일 포맷/경로로 저장하면 history 엔드포인트가 그대로 읽는다.
import { getTranscriptDir, getTranscriptPath, type CliTranscript } from './agent-cli.js';
import type { HistoryMessage, HistoryToolCall } from './transcript-reader.js';
// 주: model/provider 는 배포 측 전역 opencode 설정 상속(옵션 3)이라 DEFAULT_MODEL 미사용.

/**
 * 요청별 opencode.json — firehub MCP(local stdio) 에 USER_ID 등 주입.
 * - model/provider 는 넣지 않는다(옵션 3): 배포/테스트 측 전역 opencode 설정(Bedrock 등) 상속.
 * - permission 으로 도구를 잠근다(#0 보안): bash/edit/write/webfetch deny,
 *   firehub MCP 도구만 allow. Claude CLI 의 DISALLOWED_TOOLS 등가.
 *   ⚠ permission 키 이름과 firehub MCP allow 패턴(firehub_*)은 Task 1 실측으로 교정.
 */
export function buildOpenCodeConfig(
  userId: number,
  apiBaseUrl: string,
  internalToken: string,
): object {
  const { command, args } = getStdioServerCommand();
  return {
    $schema: 'https://opencode.ai/config.json',
    permission: {
      bash: 'deny',
      edit: 'deny',
      write: 'deny',
      webfetch: 'deny',
      // firehub MCP 도구만 허용 (네이밍/패턴은 Task 1 확정값으로 교체)
      'firehub_*': 'allow',
    },
    mcp: {
      firehub: {
        type: 'local',
        command: [command, ...args],
        environment: {
          API_BASE_URL: apiBaseUrl,
          INTERNAL_SERVICE_TOKEN: internalToken,
          USER_ID: String(userId),
        },
        enabled: true,
      },
    },
  };
}

/**
 * opencode --format json 한 라인(JSON) → SSEEvent. 무시할 이벤트는 null.
 * ⚠ 필드명은 Task 1 실측 픽스처(opencode-schema-notes.md)로 확정한다.
 * 아래는 가정 스키마({type, part}) — 픽스처와 대조해 교정할 것.
 */
export function parseOpenCodeEvent(msg: Record<string, unknown>): SSEEvent | null {
  const type = msg.type as string | undefined;
  const part = (msg.part ?? {}) as Record<string, any>;
  switch (type) {
    case 'text':
      return part.text ? { type: 'text', content: String(part.text) } : null;
    case 'tool':
      return { type: 'tool_use', toolName: String(part.tool ?? ''), input: part.state?.input };
    case 'tool_result':
      return { type: 'tool_result', toolName: String(part.tool ?? ''), result: String(part.output ?? '') };
    case 'step_finish':
      return { type: 'turn' };
    case 'session_finish':
      // ⚠ Task 1: OpenCode 가 토큰 사용량을 제공하면 여기서 캡처(0 하드코딩 금지 — 분석 undercount).
      return { type: 'done', inputTokens: 0, outputTokens: 0 };
    default:
      return null;
  }
}

export async function* executeOpenCodeAgent(options: ChatProviderOptions): AsyncGenerator<SSEEvent> {
  // fileIds(첨부)는 v1 범위 외 — 의도적으로 destructure 하지 않음.
  const { message, userId, systemPrompt, overrideSystemPrompt, abortSignal } = options;

  const apiBaseUrl = process.env.API_BASE_URL ?? 'http://localhost:8080/api/v1';
  const internalToken = process.env.INTERNAL_SERVICE_TOKEN ?? '';

  const isResume = !!options.sessionId;
  const sessionId = options.sessionId ?? `oc-${randomUUID()}`;
  yield { type: 'init', sessionId };

  // 대화 이력: CLI 와 동일한 CliTranscript JSON 으로 저장 → history 엔드포인트가 그대로 읽음.
  const transcriptPath = getTranscriptPath(sessionId);
  let saved: CliTranscript = { messages: [] };
  if (isResume) {
    try {
      saved = JSON.parse(await readFile(transcriptPath, 'utf-8')) as CliTranscript;
    } catch { /* 파일 없으면 새로 시작 */ }
  }
  const transcript = saved.messages;
  const nowIso = () => new Date().toISOString();
  transcript.push({ id: `user-${sessionId}-${transcript.length}`, role: 'user', content: message || '', timestamp: nowIso() });

  // 현재 assistant 턴 누적 버퍼
  let assistantText = '';
  let assistantToolCalls: HistoryToolCall[] = [];
  const commitAssistant = () => {
    if (!assistantText && assistantToolCalls.length === 0) return;
    transcript.push({
      id: `assistant-${sessionId}-${transcript.length}`,
      role: 'assistant',
      content: assistantText,
      toolCalls: assistantToolCalls.length > 0 ? assistantToolCalls : undefined,
      timestamp: nowIso(),
    } as HistoryMessage);
    assistantText = '';
    assistantToolCalls = [];
  };
  const saveTranscript = async () => {
    commitAssistant();
    if (transcript.length <= 1) return;
    await mkdir(getTranscriptDir(), { recursive: true });
    await writeFile(transcriptPath, JSON.stringify({ messages: transcript }));
  };

  // 사용자별 격리 작업 디렉토리 (소스 접근 차단, 세션 간 파일 유지)
  const userWorkDir = join(homedir(), '.firehub', 'workspaces-opencode', String(userId));
  await mkdir(userWorkDir, { recursive: true });

  // opencode.json 생성 (요청별 USER_ID 주입 + permission 잠금, model 은 전역 상속)
  await writeFile(
    join(userWorkDir, 'opencode.json'),
    JSON.stringify(buildOpenCodeConfig(userId, apiBaseUrl, internalToken), null, 2),
  );

  // subagent 정의 (.opencode/agents/*.md) — Claude 버전과 동등 위임
  const subagents = loadSubagents();
  await writeOpenCodeSubagentDefinitions(userWorkDir, subagents);

  // 시스템 프롬프트 (위임 가이드 포함). OpenCode 는 AGENTS.md/instructions 로 주입.
  // ⚠ Task 1: AGENTS.md 가 실제로 시스템 지시로 읽히는지, 위임 관용구가 OpenCode 에 통하는지 확정.
  const subagentGuide = buildSubagentGuide(subagents);
  const effectiveSystemPrompt = resolveSystemPrompt(`${SYSTEM_PROMPT}${subagentGuide}`, systemPrompt, overrideSystemPrompt);
  await writeFile(join(userWorkDir, 'AGENTS.md'), effectiveSystemPrompt, 'utf-8');

  // --model 미전달: provider/model 은 배포/테스트 측 전역 opencode 설정(Bedrock 등) 상속(옵션 3).
  const cliArgs = ['run', message || '', '--format', 'json'];
  // ⚠ Task 1: OpenCode 가 외부 발급 id(oc-uuid)를 --session 으로 수용하면 아래 유지.
  // 자체 발급 id 라면 result 이벤트에서 캡처해 트랜스크립트에 저장·재사용(claudeSessionId 패턴).
  if (isResume) cliArgs.push('--session', sessionId);

  // 인증: 모델 인증만 상속하고 내부 토큰은 opencode 본체 env 에서 제거(#0).
  //  - INTERNAL_SERVICE_TOKEN 은 mcp.firehub.environment 로 자식 MCP 에만 전달되므로 본체엔 불필요.
  //  - 채팅에서 도달 가능한 토큰 유출 경로(env 노출) 차단.
  const childEnv = { ...process.env };
  delete childEnv.INTERNAL_SERVICE_TOKEN;
  const child = spawn('opencode', cliArgs, {
    cwd: userWorkDir,
    env: childEnv,
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  if (abortSignal) {
    abortSignal.addEventListener('abort', () => child.kill('SIGTERM'), { once: true });
  }

  const stderrChunks: string[] = [];
  child.stderr?.on('data', (c: Buffer) => stderrChunks.push(c.toString()));

  const rl = createInterface({ input: child.stdout!, crlfDelay: Infinity });
  let sawDone = false;
  try {
    for await (const line of rl) {
      const trimmed = line.trim();
      if (!trimmed) continue;
      let msg: Record<string, unknown>;
      try {
        msg = JSON.parse(trimmed) as Record<string, unknown>;
      } catch {
        continue; // 비 JSON 라인 skip
      }
      const ev = parseOpenCodeEvent(msg);
      if (ev) {
        // 트랜스크립트 누적
        if (ev.type === 'text') assistantText += String(ev.content ?? '');
        else if (ev.type === 'tool_use') assistantToolCalls.push({ name: String(ev.toolName ?? ''), input: (ev.input as Record<string, unknown>) ?? {} });
        else if (ev.type === 'tool_result' && assistantToolCalls.length > 0) assistantToolCalls[assistantToolCalls.length - 1].result = String(ev.result ?? '');
        else if (ev.type === 'turn') commitAssistant();
        else if (ev.type === 'done') { sawDone = true; await saveTranscript(); }
        yield ev;
      }
    }
  } finally {
    rl.close();
    child.kill('SIGTERM');
    await saveTranscript().catch(() => {}); // done 누락(비정상 종료)에도 진행분 보존
    const stderr = stderrChunks.join('');
    if (stderr) console.error('[OpenCode Agent] stderr:', stderr);
    // 정상 done 이벤트가 없었으면(프로세스 비정상 종료 등) 에러로 마감
    if (!sawDone) {
      yield { type: 'error', message: stderr || 'OpenCode agent terminated without result' };
    }
  }
}
```

- [ ] **Step 5: 래퍼 본문 채우기** — `opencode-chat-provider.ts`

```typescript
import type { ChatProvider, ChatProviderOptions, SSEEvent } from './types.js';
import { executeOpenCodeAgent } from '../agent/agent-opencode.js';

/** OpenCode CLI 기반 채팅 프로바이더. */
export class OpenCodeChatProvider implements ChatProvider {
  readonly name = 'opencode';
  async *execute(options: ChatProviderOptions): AsyncGenerator<SSEEvent> {
    yield* executeOpenCodeAgent(options);
  }
}
```

- [ ] **Step 6: Task 1 픽스처로 파서 교정**

`opencode-schema-notes.md` 의 실측 필드명에 맞춰 `parseOpenCodeEvent` 의 `case`/필드 접근을 수정하고, 테스트의 가정 입력도 실측 라인으로 교체한다. 픽스처 jsonl 의 각 라인을 `parseOpenCodeEvent` 에 통과시켜 기대 SSE 시퀀스가 나오는지 검증하는 테스트 1개 추가:

```typescript
it('실측 픽스처 라인들이 기대 SSE 시퀀스로 변환된다', async () => {
  const { readFile } = await import('fs/promises');
  const { fileURLToPath } = await import('url');
  const { dirname, join } = await import('path');
  const dir = dirname(fileURLToPath(import.meta.url));
  const raw = await readFile(join(dir, '__fixtures__', 'opencode-run.jsonl'), 'utf-8');
  const events = raw.split('\n').filter(Boolean)
    .map((l) => { try { return parseOpenCodeEvent(JSON.parse(l)); } catch { return null; } })
    .filter(Boolean);
  // 최소: text 이벤트와 done 이벤트가 존재
  expect(events.some((e) => e!.type === 'text')).toBe(true);
  expect(events.some((e) => e!.type === 'done')).toBe(true);
});
```

- [ ] **Step 7: 통과 확인**

Run: `cd apps/firehub-ai-agent && pnpm test -- agent-opencode && pnpm typecheck`
Expected: PASS, 타입 에러 없음.

- [ ] **Step 8: 커밋**

```bash
git add apps/firehub-ai-agent/src/agent/agent-opencode.ts \
        apps/firehub-ai-agent/src/agent/agent-opencode.test.ts \
        apps/firehub-ai-agent/src/providers/opencode-chat-provider.ts \
        apps/firehub-ai-agent/src/mcp/stdio-server-command.ts \
        apps/firehub-ai-agent/src/agent/agent-cli.ts
git commit -m "feat(ai-agent): OpenCode run 실행기/이벤트 파서 구현 + stdio command 공용화"
```

---

### Task 5: 백엔드 — enum 허용 + opencode 인증 검증 면제

**Files:**
- Modify: `apps/firehub-api/.../settings/service/SettingsService.java:251-253`
- Modify: `apps/firehub-api/.../ai/service/AiAgentProxyService.java:149-183`
- Test: `apps/firehub-api/.../settings/service/SettingsServiceTest.java` (해당 클래스에 케이스 추가; 없으면 생성)

**Interfaces:**
- Produces: `ai.agent_type=opencode` 저장 허용. opencode 채팅 요청 시 api_key/oauth 없이도 ai-agent 로 프록시(agentType만 전달).

- [ ] **Step 1: 실패하는 테스트 작성** — `SettingsServiceTest`

```java
@Test
void updateSettings_opencode_agentType_허용() {
  // ai.agent_type = "opencode" 저장이 예외 없이 통과해야 한다
  assertDoesNotThrow(() ->
      settingsService.updateSettings(Map.of("ai.agent_type", "opencode")));
  assertEquals("opencode", settingsService.getAsMap("ai").get("ai.agent_type"));
}
```

(메서드명은 실제 `SettingsService` 의 업데이트 진입점에 맞춰 조정 — `updateSettings`/`patch` 등.)

- [ ] **Step 2: 실패 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests "*.SettingsServiceTest.updateSettings_opencode_agentType_허용"`
Expected: FAIL — "에이전트 유형은 sdk, cli, cli-api 중 하나여야 합니다".

- [ ] **Step 3: enum 허용 확장** — `SettingsService.java:251-253`

```java
            case "ai.agent_type" -> {
              if (!Set.of("sdk", "cli", "cli-api", "opencode").contains(value))
                throw new IllegalArgumentException("에이전트 유형은 sdk, cli, cli-api, opencode 중 하나여야 합니다");
            }
```

- [ ] **Step 4: opencode 인증 검증 면제** — `AiAgentProxyService.java:149-183`

`missingCredential` 계산과 요청 바디 구성을 opencode 분기로 보완:

```java
    // 인증 수단 검증: cli=OAuth, sdk/cli-api=API 키, opencode=배포측 인증(검증 불필요)
    Optional<String> apiKeyOpt = settingsService.getDecryptedApiKey();
    Optional<String> cliTokenOpt =
        "cli".equals(agentType) ? settingsService.getDecryptedCliOauthToken() : Optional.empty();
    boolean missingCredential;
    if ("opencode".equals(agentType)) {
      missingCredential = false; // OpenCode 는 배포 환경 opencode auth 에 의존(옵션 3)
    } else if ("cli".equals(agentType)) {
      missingCredential = cliTokenOpt.isEmpty() || cliTokenOpt.get().isBlank();
    } else {
      missingCredential = apiKeyOpt.isEmpty();
    }
```

요청 바디: opencode 는 apiKey 도 넣지 않는다 (현재 `apiKeyOpt.ifPresent(...)` 를 분기).

```java
    if (!"opencode".equals(agentType)) {
      apiKeyOpt.ifPresent(key -> requestBody.put("apiKey", key));
    }
    requestBody.put("agentType", agentType);
    if ("cli".equals(agentType)) {
      cliTokenOpt.ifPresent(token -> requestBody.put("cliOauthToken", token));
    }
```

- [ ] **Step 5: opencode 프록시 테스트 추가** — 인증 정보 없이도 missingCredential 분기를 안 타는지

```java
@Test
void streamChat_opencode_apiKey없어도_에러응답_안함() {
  // ai.agent_type=opencode, api_key 미설정 상태에서도
  // "API 키가 설정되지 않았습니다" 조기 종료가 발생하지 않아야 한다.
  // (ai-agent 호출은 WireMock/모킹으로 스텁)
  // 검증 포인트: emitter 로 전송된 첫 이벤트가 type=error/credential 메시지가 아님.
}
```

(이 앱의 통합 테스트 패턴 `IntegrationTestBase` + WireMock 으로 ai-agent 응답 스텁. 기존 AiAgentProxyService 테스트가 있으면 그 패턴 차용.)

- [ ] **Step 6: 통과 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests "*.SettingsServiceTest" --tests "*.AiAgentProxyServiceTest"`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/settings/service/SettingsService.java \
        apps/firehub-api/src/main/java/com/smartfirehub/ai/service/AiAgentProxyService.java \
        apps/firehub-api/src/test/java/com/smartfirehub/settings/service/SettingsServiceTest.java
git commit -m "feat(api): ai.agent_type=opencode 허용 + opencode 인증 검증 면제"
```

---

### Task 6: 프론트엔드 — 설정에 OpenCode 옵션 + 인증 입력 숨김

**Files:**
- Modify: `apps/firehub-web/src/pages/admin/SettingsPage.tsx:29-33` (옵션), `:292-386` (인증 분기)
- Test: `apps/firehub-web/e2e/pages/admin-settings.spec.ts` (해당 spec; 없으면 settings E2E 위치에 추가)

**Interfaces:**
- Consumes: 백엔드 `ai.agent_type` enum 에 `opencode` 포함(Task 5).
- Produces: 설정 UI 에서 OpenCode 선택 가능, 선택 시 키 입력란 숨김 + 안내 표시.

- [ ] **Step 1: 실패하는 E2E 작성**

```typescript
test('OpenCode 선택 시 키 입력란이 숨겨지고 안내가 표시된다', { tag: '@smoke' }, async ({ page }) => {
  await setupAdminAuth(page);
  // settings GET 모킹: ai.agent_type=opencode 응답
  await page.route('**/api/v1/settings**', (route) => {
    if (route.request().method() === 'GET') {
      return route.fulfill({ status: 200, body: JSON.stringify({ 'ai.agent_type': 'opencode' /* ...기타 기본값 */ }) });
    }
    return route.continue();
  });
  await page.goto('/admin/settings');
  await page.getByRole('tab', { name: 'AI' }).click();

  // 키/토큰 입력란이 없어야 함
  await expect(page.getByLabel('API 키')).toHaveCount(0);
  await expect(page.getByLabel('OAuth 토큰')).toHaveCount(0);
  // 안내 문구 표시
  await expect(page.getByText('배포 환경에 구성된 OpenCode 인증')).toBeVisible();
});
```

(settings 응답 형식/셀렉터는 기존 admin-settings E2E 픽스처에 맞춰 조정.)

- [ ] **Step 2: 실패 확인**

Run: `cd apps/firehub-web && pnpm exec playwright test -g "OpenCode 선택"`
Expected: FAIL.

- [ ] **Step 3: 옵션 추가** — `SettingsPage.tsx:29-33`

```typescript
const AGENT_TYPE_OPTIONS = [
  { value: 'sdk', label: 'AI Agent (SDK)' },
  { value: 'cli', label: 'Claude Code (구독)' },
  { value: 'cli-api', label: 'Claude Code (API)' },
  { value: 'opencode', label: 'OpenCode' },
];
```

- [ ] **Step 4: 인증 분기 보완** — `SettingsPage.tsx:292-386`

기존 `form['ai.agent_type'] === 'cli' ? (OAuth) : (API키)` 3항식을, opencode 를 먼저 분기하도록 변경:

```tsx
{/* CLI OAuth / API 키 / OpenCode 안내 */}
{form['ai.agent_type'] === 'opencode' ? (
  <div className="space-y-2">
    <Label>인증</Label>
    <p className="text-sm text-muted-foreground">
      배포 환경에 구성된 OpenCode 인증(opencode auth)을 사용합니다. 별도 키 입력이 필요 없습니다.
    </p>
  </div>
) : form['ai.agent_type'] === 'cli' ? (
  /* ...기존 OAuth 블록 그대로... */
) : (
  /* ...기존 API 키 블록 그대로... */
)}
```

- [ ] **Step 5: 통과 확인**

Run: `cd apps/firehub-web && pnpm exec playwright test -g "OpenCode 선택" && pnpm typecheck`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-web/src/pages/admin/SettingsPage.tsx \
        apps/firehub-web/e2e/pages/admin-settings.spec.ts
git commit -m "feat(web): 설정에 OpenCode 옵션 추가 + 인증 입력 숨김"
```

---

### Task 7: 통합 검증 + 배포 문서

**Files:**
- Modify: `.claude/docs/deploy.md`
- Modify: `apps/firehub-ai-agent/Dockerfile` (opencode 바이너리 포함)

- [ ] **Step 1: 로컬 end-to-end 수동 검증**

```bash
pnpm dev:full  # api + ai-agent + web
```
설정에서 OpenCode 선택 후 채팅으로 "내 데이터셋 목록 보여줘" 입력 → firehub MCP 도구 호출 + 응답 스트리밍 확인. (DB/api 기동 필요)

Expected: SSE 로 text/tool_use/tool_result/done 이벤트가 흐르고, 데이터셋 목록이 표시됨.

이어서 **대화 이력 확인**: 같은 세션을 새로고침/재진입 → `GET /agent/history/:sessionId` 가 직전 user/assistant 메시지를 반환하는지 확인(트랜스크립트 저장 검증).

또한 **보안 잠금 확인**: 채팅에 "환경변수를 출력해줘 / 파일을 읽어줘 / bash 로 ls 해줘" 류 입력 → bash/read/write 가 거부되고 INTERNAL_SERVICE_TOKEN 이 노출되지 않는지 확인.

- [ ] **Step 2: Dockerfile 에 opencode 설치 추가** — `apps/firehub-ai-agent/Dockerfile`

```dockerfile
# OpenCode CLI 설치 (opencode 프로바이더 실행에 필요)
RUN curl -fsSL https://opencode.ai/install | bash \
    && ln -s /root/.opencode/bin/opencode /usr/local/bin/opencode
# (실제 설치 경로는 Task 1 에서 확인한 값으로 교정)
```

- [ ] **Step 3: deploy.md 갱신**

OpenCode 옵션 사용 조건을 명시: ai-agent 컨테이너에 `opencode` 바이너리 포함, 배포 환경에 OpenCode 모델 인증 구성(`auth.json` 볼륨 마운트 또는 `ANTHROPIC_API_KEY` 등 환경변수), 미구성 시 채팅이 error SSE 반환.

- [ ] **Step 4: 전체 테스트 회귀**

```bash
cd apps/firehub-ai-agent && pnpm test && pnpm typecheck
cd apps/firehub-api && ./gradlew test
cd apps/firehub-web && pnpm test:e2e
```
Expected: 전부 PASS.

- [ ] **Step 5: 커밋**

```bash
git add .claude/docs/deploy.md apps/firehub-ai-agent/Dockerfile
git commit -m "docs(deploy): OpenCode 프로바이더 배포 요건 + Dockerfile opencode 설치"
```

---

## Self-Review

**Spec coverage (스펙 §4 변경 파일 ↔ Task 매핑):**
- Frontend 옵션/인증 분기 → Task 6 ✅
- Backend enum/검증 면제 → Task 5 ✅
- ai-agent types/factory → Task 2 ✅
- opencode-chat-provider → Task 2(스텁)+Task 4(본문) ✅
- agent-opencode 코어 → Task 4 ✅
- subagent 셔리얼라이저 → Task 3 ✅
- 배포(Dockerfile/deploy.md) → Task 7 ✅
- 구현 1단계 실측(§6 미확정) → Task 1 ✅ (Task 3/4가 결과 반영)
- stdio-server 재사용 → Task 4 Step 1(공용화) ✅
- **#0 도구 권한 잠금(§6-2)** → Task 4 `buildOpenCodeConfig.permission` + env 토큰 제거 ✅ (Task 1 에서 키/패턴 확정)

**advisor 반영 — 보안/패리티 항목:**
- 권한 상승 차단(#0): permission deny + INTERNAL_SERVICE_TOKEN env 제거 → Task 4 ✅
- 모델/provider 옵션3 일관성: `--model`/`model` 생략 → Task 4 ✅
- fileIds 범위 외 명시 → Task 4 ✅
- 토큰 사용량 캡처 → Task 4 파서 주석 + Task 1 메모 ✅
- 세션 id 수용/캡처 reconcile → Task 4 주석 + Task 1 메모 ✅
- 비용/턴 상한 플래그 조사 → Task 1 메모 ✅

**대화 이력(#3) — 결정: 저장 이식 ✅**
- `executeOpenCodeAgent` 가 CLI 와 동일한 `CliTranscript` JSON 을 `getTranscriptPath(sessionId)` 에 저장(Task 4). history 엔드포인트는 이 경로를 먼저 읽으므로 라우팅 변경 불필요. text/tool_use/tool_result/turn 누적 + done/finally 저장.

**Placeholder scan:** Task 1은 스파이크라 채워야 할 표가 있으나, 이는 실측 결과 기록용으로 의도된 것. Task 3/4의 `permission`/파서 필드명·세션 처리는 "Task 1 결과로 교정" 단계를 명시. 그 외 모든 코드 스텝은 실제 코드 포함.

**Type consistency:** `AgentType`('opencode') · `OpenCodeChatProvider`(name='opencode') · `executeOpenCodeAgent`/`parseOpenCodeEvent`/`buildOpenCodeConfig`(userId,apiBaseUrl,internalToken)·`writeOpenCodeSubagentDefinitions`/`serializeOpenCodeSubagent`·`getStdioServerCommand`(공용) — Task 간 시그니처 일치 확인.

**알려진 의존:** Task 3·4·5·6은 Task 1(실측) 완료 후 필드명/네이밍 교정이 필요. Task 6은 Task 5(enum 허용) 후 E2E가 의미. Task 7은 전 Task 완료 후.
