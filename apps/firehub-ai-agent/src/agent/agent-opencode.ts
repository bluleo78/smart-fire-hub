/**
 * OpenCode CLI agent executor (`opencode run --format json`).
 *
 * agent-cli.ts(Claude CLI) 와 동일한 "요청별 서브프로세스 spawn" 패턴.
 * 요청마다 opencode.json 을 생성해 그 사용자의 USER_ID 를 firehub MCP 의
 * environment 로 주입(per-user 격리)하고, --format json 출력을 SSEEvent 로 변환한다.
 * 인증(OpenCode→모델)은 테넌트가 설정한 opencode provider(baseURL/apiKey)를 요청마다
 * OPENCODE_CONFIG_CONTENT 로 주입한다 — 2026-06-23 의 "옵션 3: 배포 측 전역 설정 상속"
 * 결정은 2026-09-19(이슈 #693)에 뒤집혔다: 그때는 opencode 를 고른 모든 테넌트가 같은
 * 사내 엔드포인트를 공유했고, 이제는 테넌트별 자격증명이 있으므로 상속할 이유가 없다.
 */
import { spawn } from 'child_process';
import { mkdir, mkdtemp, unlink, writeFile } from 'fs/promises';
import { join } from 'path';
import { tmpdir } from 'os';
import { createInterface } from 'readline';
import { randomUUID } from 'crypto';
import type { ChatProviderOptions, SSEEvent } from '../providers/types.js';
import {
  splitOpencodeModelOrNull,
  type OpencodeModelParts,
} from '../providers/opencode-model.js';
import { getStdioServerCommand } from '../mcp/stdio-server-command.js';
import { OPENCODE_MCP_CREDENTIAL_ENV } from '../mcp/opencode-mcp-credential-env.js';
import { OPENCODE_SYSTEM_PROMPT } from './system-prompt.js';
import { resolveSystemPrompt } from './prompt-utils.js';
// 트랜스크립트: CLI 와 동일 포맷/경로로 저장하면 history 엔드포인트가 그대로 읽는다.
import { readCliTranscript, writeCliTranscript, type CliTranscript } from './agent-cli.js';
import { isSafeSessionId, opencodeWorkspaceDir } from './tenant-paths.js';
import { claimSession } from './session-owner.js';
import type { HistoryMessage, HistoryToolCall } from './transcript-reader.js';

/**
 * 요청별 opencode.json(이제는 파일이 아니라 OPENCODE_CONFIG_CONTENT 로 전달되는 값)의 형태.
 *
 * `model`/`provider` 는 테넌트의 opencode 자격증명에서 매번 채운다(옵션 3 폐기) — 배포 측
 * 전역 opencode 설정(PVC `opencode.jsonc`, Bedrock 게이트웨이 등)은 더 이상 이 필드들의
 * 출처가 아니다. 이 값을 비우면 그 전역 설정이 다시 이겨 provider 블록이 죽은 코드가 된다.
 */
export interface OpenCodeConfig {
  $schema: string;
  model: string;
  provider: Record<
    string,
    {
      npm: string;
      options: { baseURL: string; apiKey: string };
      models: Record<
        string,
        {
          modalities: { input: string[]; output: string[] };
          options?: { reasoningEffort: string };
        }
      >;
    }
  >;
  tools: Record<string, boolean>;
  permission: Record<string, string>;
  // 에이전트별 설정: 위임(task) 차단 + 빌트인 general 비활성 (#0 보안 + 응답 정상화).
  agent: {
    build: { permission: { task: Record<string, string> } };
    general: { disable: boolean };
  };
  mcp: {
    firehub: {
      type: string;
      command: string[];
      environment: Record<string, string>;
      enabled: boolean;
    };
  };
}

/** buildOpenCodeConfig 입력 — 세션 배선(userId 등)과 테넌트 opencode 자격증명을 함께 받는다. */
export interface BuildOpenCodeConfigOptions {
  userId: number;
  tenantId: number;
  apiBaseUrl: string;
  internalToken: string;
  /** 저장 규약상 provider 식별자(credential.payload.providerId). 예: "openai". */
  providerId: string;
  /** OpenAI 호환 provider 의 베이스 URL. */
  baseUrl: string;
  /** OpenAI 호환 provider 의 API 키. */
  apiKey: string;
  /** `providerId/modelId` 형식의 모델 문자열(예: "openai/gpt-4o"). */
  model: string;
  /** opencode 가 공급자에게 그대로 넘기는 추론 강도. 빈 값/공백이면 "설정 안 함"이다. */
  reasoningEffort?: string;
}

/**
 * `model` 을 `providerId/modelId` 로 분해한다. 저장 규약은 이 형식을 항상 전제하므로, 없으면
 * (슬래시가 없으면) 설정 오류로 간주해 방어적으로 throw 한다(iacloud_eis `splitOpencodeModel`
 * 과 동일 계약).
 *
 * 파싱 규칙 자체는 `providers/opencode-model.ts` 한 곳에 있다 — 이 함수는 그 위에 "없으면
 * 던진다"는 이 호출부만의 정책을 얹은 얇은 래퍼다(완성 프로바이더는 같은 파서를 null
 * 패스스루로 쓴다).
 */
function splitOpencodeModel(model: string): OpencodeModelParts {
  const parts = splitOpencodeModelOrNull(model);
  if (parts === null) {
    throw new Error(`opencode 모델은 'providerId/modelId' 형식이어야 합니다(받은 값: ${model})`);
  }
  return parts;
}

export function buildOpenCodeConfig(options: BuildOpenCodeConfigOptions): OpenCodeConfig {
  const {
    userId,
    tenantId,
    apiBaseUrl,
    internalToken,
    providerId,
    baseUrl,
    apiKey,
    model,
    reasoningEffort,
  } = options;

  // provider 설정이 불완전하면 여기서 크게 실패한다 — 조용히 배포 측 전역 설정으로 떨어지거나
  // provider 블록의 키가 "undefined" 문자열이 되는 것을 막는다(ProviderFactory.
  // createCompletionProvider 의 baseUrl 가드와 같은 정신, 설계서 "알 수 없는 agentType 은
  // fail-closed" 절과 같은 이유).
  if (!providerId) {
    throw new Error('[opencode] providerId 가 없습니다 — provider 설정이 불완전합니다.');
  }
  if (!baseUrl) {
    throw new Error('[opencode] baseUrl 이 없습니다 — provider 설정이 불완전합니다.');
  }
  if (!model) {
    throw new Error('[opencode] model 이 없습니다 — provider 설정이 불완전합니다.');
  }

  const { providerID, modelID } = splitOpencodeModel(model);
  // 모델의 providerID 와 payload.providerId 가 다르면 opencode 가 provider 미스매치로 실패하므로
  // 방어적으로 먼저 throw 한다(설계서 "에이전트" 절).
  if (providerID !== providerId) {
    throw new Error(
      `opencode 모델의 providerID(${providerID}) 가 credential.payload.providerId(${providerId}) 와 다릅니다`,
    );
  }

  // 빈 문자열/공백은 "설정 안 함"이다 — 그대로 opencode 에 보내면 400 이 된다(iacloud_eis 실측).
  const effort = typeof reasoningEffort === 'string' ? reasoningEffort.trim() : undefined;

  const { command, args } = getStdioServerCommand();
  const resolvedModel = `${providerId}/${modelID}`;

  return {
    $schema: 'https://opencode.ai/config.json',
    // 테넌트가 고른 provider/model 을 명시한다. 비우면 배포 측 전역 opencode 설정(PVC 마운트,
    // 예: Bedrock 게이트웨이)의 model 이 여전히 이겨 아래 provider 블록이 죽은 코드가 된다.
    model: resolvedModel,
    provider: {
      [providerId]: {
        npm: '@ai-sdk/openai-compatible',
        options: { baseURL: baseUrl, apiKey },
        models: {
          [modelID]: {
            // modalities.input 에 image 필수 — 없으면 opencode 코어가 사용자 메시지의 image
            // 파트를 제거하고 "does not support image input" 에러 텍스트로 바꿔친다
            // (iacloud_eis 실측 — 이론이 아니라 실제로 재현된 버그).
            modalities: { input: ['text', 'image'], output: ['text'] },
            ...(effort ? { options: { reasoningEffort: effort } } : {}),
          },
        },
      },
    },
    // 내장 도구 전체 비활성화: 보안 + 게이트웨이 호환성(#0).
    // task 는 서브에이전트 위임에 필요하므로 활성 유지.
    tools: {
      bash: false, edit: false, write: false, read: false, glob: false,
      grep: false, list: false, patch: false, webfetch: false,
      todowrite: false, todoread: false,
    },
    permission: {
      bash: 'deny',
      edit: 'deny',
      write: 'deny',
      webfetch: 'deny',
      // firehub MCP 도구만 허용 (네이밍/패턴은 Task 1 확정값으로 교체)
      'firehub_*': 'allow',
    },
    // 위임(task) 전면 차단 + 빌트인 general 비활성화.
    //  - 약한 모델(gemma)이 firehub 전용 subagent 대신 빌트인 general 로 위임하면,
    //    general 은 요청별 permission 잠금을 상속하지 않아 bash/read 로 소스를 훑으며
    //    멈추고(응답 지연) 내부 소스가 노출된다(2026-06-24 운영 인시던트, #0 보안).
    //  - 메인(build)에서 task 를 deny 하면 firehub 도구 직접 호출로 강제되고(실측 정상),
    //    OPENCODE_SYSTEM_PROMPT 가 위임 대신 직접처리·요약을 지시한다.
    agent: {
      build: { permission: { task: { '*': 'deny' } } },
      general: { disable: true },
    },
    mcp: {
      firehub: {
        type: 'local',
        command: [command, ...args],
        environment: {
          API_BASE_URL: apiBaseUrl,
          INTERNAL_SERVICE_TOKEN: internalToken,
          USER_ID: String(userId),
          // 원요청 테넌트(누락 시 동작은 FireHubApiClient 생성자 주석 참고).
          TENANT_ID: String(tenantId),
          // 게이트웨이 호환: tools/list 스키마에서 propertyNames 제거(2026-06-24 실측 400 회피).
          OPENCODE_SCHEMA_COMPAT: '1',
          // GraphRAG 도구(stdio-server.ts 가 등록)가 LLM completion 을 호출할 때 이 테넌트의
          // opencode provider 를 쓰도록 명시 전달한다. 없으면 stdio-server.ts 가 컨테이너의
          // ambient ANTHROPIC_API_KEY/CLAUDE_CODE_OAUTH_TOKEN 로 폴백해, opencode 세션 안의
          // GraphRAG 호출이 플랫폼 Anthropic 계정으로 과금되는 6b1c6383 과 같은 모양의 회귀가
          // 이 경로에만 남는다(Ruling #30).
          [OPENCODE_MCP_CREDENTIAL_ENV.AGENT_TYPE]: 'opencode',
          [OPENCODE_MCP_CREDENTIAL_ENV.PROVIDER_ID]: providerId,
          [OPENCODE_MCP_CREDENTIAL_ENV.BASE_URL]: baseUrl,
          [OPENCODE_MCP_CREDENTIAL_ENV.API_KEY]: apiKey,
          [OPENCODE_MCP_CREDENTIAL_ENV.REASONING_EFFORT]: effort ?? '',
          [OPENCODE_MCP_CREDENTIAL_ENV.MODEL]: resolvedModel,
        },
        enabled: true,
      },
    },
  };
}

/**
 * OpenCode 도구명(`firehub_<tool>`) → 프론트엔드 계약(`mcp__firehub__<tool>`)으로 정규화.
 *
 * 왜: 프론트엔드 위젯 레지스트리(WidgetRegistry)는 `mcp__firehub__` 접두사만 벗겨 `show_chart`
 *   등으로 매칭한다(Claude SDK 경로가 emit 하는 형식). OpenCode 는 `<server>_<tool>` 규칙으로
 *   `firehub_show_chart` 를 내보내므로 정규화하지 않으면 show_chart/show_table 등 위젯이 렌더되지
 *   않는다(2026-06-24 운영: 차트 미표시). 트랜스크립트 저장에도 동일 형식이라 history 재생도 일치.
 */
export function normalizeFirehubToolName(toolName: string): string {
  return toolName.replace(/^firehub_/, 'mcp__firehub__');
}

/**
 * opencode --format json 한 라인(JSON) → SSEEvent[].
 * 실측 스키마(opencode-schema-notes.md):
 *  - type="text": part.text → [{type:'text', content}]
 *  - type="tool_use": part.type="tool", part.tool, part.state.{input,output}
 *    → completed 상태에서 tool_use + tool_result 둘 다 emit (toolName 은 mcp__firehub__ 로 정규화)
 *  - type="step_finish": part.reason="stop" → done(with tokens), 그 외 → turn
 *  - type="step_start" 등 무시 대상 → []
 *  - type="error": error 이벤트로 변환
 */
export function parseOpenCodeEvent(msg: Record<string, unknown>): SSEEvent[] {
  const type = msg.type as string | undefined;
  // 실측 스키마의 part 형태(필드는 모두 선택적, 값은 unknown 으로 안전 접근).
  const part = (msg.part ?? {}) as {
    text?: unknown;
    tool?: unknown;
    state?: { status?: unknown; input?: unknown; output?: unknown };
    tokens?: { input?: unknown; output?: unknown };
    reason?: unknown;
  };
  switch (type) {
    case 'text':
      return part.text ? [{ type: 'text', content: String(part.text) }] : [];
    case 'tool_use': {
      // 실측: completed 상태에서만 input+output 이 모두 채워진다.
      // running 등 중간 상태는 무시(중복 tool_use 이벤트 방지).
      if (part.state?.status !== 'completed') return [];
      // 프론트엔드 위젯 매칭을 위해 mcp__firehub__ 형식으로 정규화(Claude 경로와 동일 계약).
      const toolName = normalizeFirehubToolName(String(part.tool ?? ''));
      return [
        { type: 'tool_use', toolName, input: part.state?.input },
        { type: 'tool_result', toolName, result: String(part.state?.output ?? '') },
      ];
    }
    case 'step_finish': {
      const tokens = part.tokens ?? {};
      if (part.reason === 'stop') {
        // 완료: done 이벤트. 토큰 사용량 포함(0 하드코딩 금지 — step_finish.part.tokens 제공).
        return [
          {
            type: 'done',
            inputTokens: typeof tokens.input === 'number' ? tokens.input : 0,
            outputTokens: typeof tokens.output === 'number' ? tokens.output : 0,
          },
        ];
      }
      // tool-calls 등 중간 스텝 종료 → turn
      return [{ type: 'turn' }];
    }
    case 'error': {
      const err = (msg.error ?? {}) as { data?: { message?: unknown }; message?: unknown };
      return [{ type: 'error', message: String(err.data?.message ?? err.message ?? 'OpenCode error') }];
    }
    // step_start, tool(중간 상태) 등 무시
    default:
      return [];
  }
}

/**
 * `opencode run` CLI 인자 구성.
 *
 * --dir 명시(필수): OpenCode 는 서버(location services) 모델이라 spawn 의 cwd 만으로는
 *   프로젝트 디렉토리가 컨테이너 부팅 상태에 따라 /app(소스 트리)로 잘못 앵커될 수 있다.
 *   그러면 워크스페이스(세션 저장 위치)가 어긋난다(2026-06-24 운영 실측).
 *   --dir 로 격리 워크스페이스를 프로젝트 루트로 강제해 cwd 의존성을 제거한다.
 * --model 미전달: model 선택은 OPENCODE_CONFIG_CONTENT 로 주입되는 config.model 로 충분하다
 *   (opencode 가 설정 파일의 최상위 model 필드를 기본값으로 읽는다) — CLI 인자로 다시 실을
 *   필요가 없다.
 */
export function buildOpenCodeRunArgs(
  message: string,
  workDir: string,
  resumeSessionId?: string,
): string[] {
  const args = ['run', message, '--dir', workDir, '--format', 'json'];
  if (resumeSessionId) args.push('--session', resumeSessionId);
  return args;
}

/** opencode CLI 스폰에 필요한 테넌트 opencode provider 자격증명. ProviderFactory 가 생성 시점에 채운다. */
export interface OpenCodeCredentials {
  providerId: string;
  baseUrl: string;
  apiKey: string;
  reasoningEffort?: string;
}

/**
 * opencode 자식에게 줄 <b>설정 디렉터리</b> — ai-agent 프로세스당 하나를 지연 생성해 재사용한다.
 *
 * <p><b>무엇을</b>: 최초 요청에서 `mkdtemp` 로 OS 임시 디렉터리 하나를 만들고, 이후 모든 요청이
 * 같은 디렉터리를 `XDG_CONFIG_HOME` 으로 받는다. 프로세스 수명 동안 지우지 않는다.
 *
 * <p><b>왜 요청별이 아닌가(재검토 D1)</b>: opencode 는 설정을 로드할 때 이 디렉터리 아래
 * `opencode/` 에 플러그인 npm 트리를 <b>스스로 설치</b>한다(v1.18.31 실측 61MB/3,648 파일).
 * 요청마다 빈 디렉터리를 주면 그 설치가 매 채팅에 콜드로 다시 일어난다 — 실측 빈 4.35s vs
 * 채워진 0.63s, npm 레지스트리 egress 가 막힌 클러스터에서는 첫 진행까지 1분 11초. 동시 N건이면
 * N×61MB 를 쓰고 지운다. 프로세스당 1개로 두면 그 비용이 파드 수명당 1회로 돌아온다.
 *
 * <p><b>왜 공유해도 안전한가</b>(이 파일의 보안 요건은 "전역 `opencode.json` 을 읽지 않게 한다"
 * 하나다):
 * <ul>
 *   <li>테넌트 자격증명은 <b>요청별 `OPENCODE_CONFIG_CONTENT` env</b> 로만 흐른다. 디스크를
 *       거치지 않으므로 이 디렉터리는 테넌트 간 누출 경로가 아니다.</li>
 *   <li>v1.18.31 에서 이 디렉터리에 남는 것은 `opencode/node_modules`·`package.json`·
 *       `package-lock.json`·`.gitignore` 뿐이고 `opencode.json` 은 생기지 않는 것을 관측했다
 *       — 즉 "전역 설정 없음" 상태가 재사용 뒤에도 유지된다. 다만 이것은 <b>그 버전에서 관측된
 *       동작</b>이지 opencode 가 보장하는 계약이 아니다. 그래서 deploy.md 의 배포 조치(PVC
 *       subPath 마운트 제거)와 배포 후 수동 검증은 그대로 유지한다.</li>
 *   <li>경로는 `mkdtemp` 의 <b>무작위</b> 이름(0700)을 유지한다 — `/tmp/firehub-opencode-xdg`
 *       같은 고정 경로로 바꾸면 공유 `/tmp` 에 제3자가 `opencode/opencode.json` 을 미리 심어
 *       이 가드를 되돌릴 수 있다.</li>
 * </ul>
 *
 * <p><b>프로세스 종료 시 정리 — 하지 않는다(의식적 결정)</b>: 디렉터리는 프로세스당 1개뿐이고
 * OS 임시 디렉터리 아래라 재부팅/파드 재생성으로 사라진다. `process.on('exit')` 훅은
 * SIGKILL·OOM 에서 돌지 않아 "정리한다"는 주장만 코드보다 강해지므로 달지 않는다.
 *
 * <p><b>동시성</b>: 문자열이 아니라 <b>Promise 를 캐시</b>한다 — 동시 첫 요청 2건이 모두 같은
 * `mkdtemp` 를 기다리므로 디렉터리가 둘 만들어지지 않는다. 생성이 실패하면 캐시를 비워 다음
 * 요청이 재시도하고, 예외는 그대로 전파한다(실패 시 디렉터리도 env 도 만들어지지 않는다).
 */
let sharedXdgConfigDirPromise: Promise<string> | null = null;

async function getSharedXdgConfigDir(): Promise<string> {
  if (!sharedXdgConfigDirPromise) {
    sharedXdgConfigDirPromise = mkdtemp(join(tmpdir(), 'firehub-opencode-xdg-')).catch((err) => {
      sharedXdgConfigDirPromise = null; // 실패를 영구 캐시하지 않는다
      throw err;
    });
  }
  return sharedXdgConfigDirPromise;
}

export async function* executeOpenCodeAgent(
  options: ChatProviderOptions,
  credentials: OpenCodeCredentials,
): AsyncGenerator<SSEEvent> {
  // fileIds(첨부)는 v1 범위 외 — 의도적으로 destructure 하지 않음.
  const { message, tenantId, userId, systemPrompt, overrideSystemPrompt, abortSignal, model } = options;

  const apiBaseUrl = process.env.API_BASE_URL ?? 'http://localhost:8080/api/v1';
  const internalToken = process.env.INTERNAL_SERVICE_TOKEN ?? '';

  const isResume = !!options.sessionId;
  // 세션 id: OpenCode 자체 발급(ses_...) 를 첫 이벤트에서 캡처.
  // isResume 시 기존 트랜스크립트에서 opencodeSessionId 를 읽어 --session 에 전달.
  const firehubSessionId = options.sessionId ?? `oc-${randomUUID()}`;
  let opencodeSessionId: string | undefined;

  // 대화 이력: CLI 와 동일한 CliTranscript JSON 으로 저장 → history 엔드포인트가 그대로 읽음.
  // 진입 시점에 세션 id 를 검증한다 — 경로 조립(쓰기/읽기)까지 미루면 요청 처리를 한참
  // 진행한 뒤에 터진다. 경로 자체는 읽기·쓰기 헬퍼가 각자 만든다.
  if (!isSafeSessionId(firehubSessionId)) {
    throw new Error(`Invalid sessionId: ${firehubSessionId}`);
  }
  let saved: CliTranscript = { messages: [] };
  if (isResume) {
    // 레거시(테넌트 세그먼트 이전) 경로 폴백 포함 — CLI 와 같은 헬퍼를 쓴다.
    const loaded = await readCliTranscript(tenantId, firehubSessionId);
    if (loaded) {
      saved = loaded.transcript;
      // 레거시에서 읽었으면 하위 에이전트 세션 id 는 버린다 — CLI 경로와 같은 이유다
      // (agent-cli.ts 의 fromLegacy 분기 주석 참조). 우리 트랜스크립트의 메시지는 유지되고,
      // opencode 쪽 대화만 새로 시작한다.
      opencodeSessionId = loaded.fromLegacy ? undefined : saved.opencodeSessionId;
    }
  }
  const transcript = saved.messages;
  const nowIso = () => new Date().toISOString();
  transcript.push({ id: `user-${firehubSessionId}-${transcript.length}`, role: 'user', content: message || '', timestamp: nowIso() });

  // 현재 assistant 턴 누적 버퍼
  let assistantText = '';
  let assistantToolCalls: HistoryToolCall[] = [];
  const commitAssistant = () => {
    if (!assistantText && assistantToolCalls.length === 0) return;
    transcript.push({
      id: `assistant-${firehubSessionId}-${transcript.length}`,
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
    // opencodeSessionId 를 함께 저장해 재개 시 --session 에 활용
    await writeCliTranscript(tenantId, firehubSessionId, { messages: transcript, opencodeSessionId });
  };

  // 사용자별 격리 작업 디렉토리 (소스 접근 차단, 세션 간 파일 유지)
  // CLI 경로와 같은 이유로 테넌트 세그먼트를 끼운다(tenant-paths.ts 단일 파생 지점).
  const userWorkDir = opencodeWorkspaceDir(tenantId, userId);
  await mkdir(userWorkDir, { recursive: true });

  // opencode 설정 조립. provider 블록에 테넌트의 apiKey 가 들어가므로 더 이상 디스크에 쓰지
  // 않는다 — OPENCODE_CONFIG_CONTENT(child env)로만 전달한다(아래 spawn 부분 참고).
  const openCodeConfig = buildOpenCodeConfig({
    userId,
    tenantId,
    apiBaseUrl,
    internalToken,
    providerId: credentials.providerId,
    baseUrl: credentials.baseUrl,
    apiKey: credentials.apiKey,
    model: model ?? '',
    reasoningEffort: credentials.reasoningEffort,
  });

  // userWorkDir 는 세션 간 재사용되는 디렉터리다 — 이 변경 이전 버전이 여기에 opencode.json 을
  // 남겼을 수 있다. 이제 config 는 OPENCODE_CONFIG_CONTENT 로만 전달하지만, opencode 는 --dir
  // 로 지정한 프로젝트 디렉토리의 opencode.json 도 여전히 읽어 병합한다 — 과거 파일이 남아
  // 있으면 오래된(혹은 다른 테넌트 세션 당시의) provider 설정이 새 설정과 섞여 로드될 수 있다.
  // 파일이 없으면(ENOENT) 무시한다.
  await unlink(join(userWorkDir, 'opencode.json')).catch((err) => {
    if ((err as NodeJS.ErrnoException).code !== 'ENOENT') throw err;
  });

  // 시스템 프롬프트 (단일 에이전트 직접처리). OpenCode 는 프로젝트 디렉토리의 AGENTS.md 를 시스템 지시로 읽는다.
  //  - 위임(task)은 buildOpenCodeConfig 에서 차단하므로 subagent 정의(.opencode/agents)·위임 가이드는
  //    쓰지 않는다. OPENCODE_SYSTEM_PROMPT 가 firehub 도구 직접 호출·결과 요약을 지시한다.
  //  - AGENTS.md 는 비밀이 아니므로 그대로 디스크에 쓴다(config 만 env 로 옮겼다).
  const effectiveSystemPrompt = resolveSystemPrompt(OPENCODE_SYSTEM_PROMPT, systemPrompt, overrideSystemPrompt);
  await writeFile(join(userWorkDir, 'AGENTS.md'), effectiveSystemPrompt, 'utf-8');

  // 재개: opencode 자체 발급 세션 id(ses_...) 를 --session 에 전달.
  // OpenCode 가 외부 id 를 수용하지 않으므로 첫 이벤트 sessionID 를 캡처해 저장/재사용.
  const cliArgs = buildOpenCodeRunArgs(message || '', userWorkDir, isResume ? opencodeSessionId : undefined);

  // 자식 프로세스 환경 구성.
  //  - INTERNAL_SERVICE_TOKEN 은 mcp.firehub.environment 로 자식 MCP 에만 전달되므로 본체엔 불필요
  //    (채팅에서 도달 가능한 토큰 유출 경로 차단, #0).
  //  - ANTHROPIC_API_KEY/CLAUDE_CODE_OAUTH_TOKEN 은 컨테이너의 ambient Anthropic 자격증명이다.
  //    opencode 본체는 위 provider 블록(OPENCODE_CONFIG_CONTENT)으로 테넌트의 OpenAI 호환
  //    자격증명을 이미 받았으므로 이 값들이 남아 있을 이유가 없다 — 남아 있으면 opencode 가
  //    (또는 opencode 가 스폰하는 다른 provider 경로가) 플랫폼의 Anthropic 계정으로 조용히
  //    과금하는 6b1c6383 과 같은 모양의 회귀가 opencode 경로에서 재발한다.
  //  - OPENCODE_CONFIG: opencode CLI 자체가 인식하는 "전역 설정 파일 경로" 환경변수다(자세한
  //    맥락은 opencode-mcp-credential-env.ts 상단 주석). PVC 전역 opencode.json 을 가리킨다 —
  //    남아 있으면 opencode 가 그 전역 파일을 읽어 이 요청의 OPENCODE_CONFIG_CONTENT(테넌트별
  //    provider)와 병합하고, 병합 순서에 따라 전역 설정이 조용히 이길 수 있다(보안 리뷰 Fix4,
  //    Ruling #34 가 "병합 순서 미검증"으로 이미 인정한 위험을 여기서 원천 차단한다). deploy.md
  //    는 이 값을 배포에 심지 말라고 이미 적었지만, 옛 런북을 따른 배포는 여전히 갖고 있을 수
  //    있다 — 컨테이너 쪽 설정을 신뢰하지 않고 이 프로세스가 스스로 지운다.
  //  - OPENCODE_PERMISSION: 위와 같은 부류(opencode 자체 전역 설정 환경변수 관례,
  //    opencode-mcp-credential-env.ts 주석 참고) — 존재하면 같은 이유로 지운다.
  //  - BEDROCK_API_KEY: 이 리포(ai-agent 소스 전체) 어디에도 직접 참조가 없는데도 실제 배포
  //    차트(k8s/smart-fire-hub/templates/aiagent-deployment.yaml)가 컨테이너 env 로 주입한다 —
  //    Ruling #34 가 우려한 "공유 회사 게이트웨이"가 정확히 이 값이다. opencode(혹은 그 안에서
  //    로드되는 AWS Bedrock provider)가 이런 이름의 ambient 자격증명을 인식해 자동 등록하면,
  //    테넌트가 opencode 자격증명 화면에서 고른 공급자가 이 공유 값으로 조용히 대체될 수 있다
  //    — ANTHROPIC_API_KEY 와 같은 모양의 사고를 공급자만 바꿔 재현한다. AWS SDK 자격증명
  //    체인이 fallback 으로 보는 나머지 표준 변수도 같은 이유로 함께 지운다.
  //  - 이 목록은 닫힌 목록이 아니다(deny-list 의 근본적 한계) — 새 공급자 자격증명 관례가
  //    생길 때마다 여기 손으로 추가해야 하고, 놓치면 같은 사고가 새 이름으로 재발한다. 더
  //    견고한 해법은 필요한 변수만 명시적으로 골라 담는 allow-list 로 뒤집는 것이지만, 이
  //    실행 환경에서는 실제 opencode 바이너리로 검증할 방법이 없어(설치 자체가 안 됨) 잘못된
  //    allow-list 가 모든 opencode 채팅을 조용히 깨뜨릴 위험이 커 이번엔 deny-list 보강에
  //    그친다 — 후속 이슈로 남긴다(보안 리뷰 보고서 참고).
  const childEnv = { ...process.env };
  delete childEnv.INTERNAL_SERVICE_TOKEN;
  delete childEnv.ANTHROPIC_API_KEY;
  delete childEnv.CLAUDE_CODE_OAUTH_TOKEN;
  delete childEnv.OPENCODE_CONFIG;
  delete childEnv.OPENCODE_PERMISSION;
  delete childEnv.BEDROCK_API_KEY;
  delete childEnv.AWS_BEARER_TOKEN_BEDROCK;
  delete childEnv.AWS_ACCESS_KEY_ID;
  delete childEnv.AWS_SECRET_ACCESS_KEY;
  delete childEnv.AWS_SESSION_TOKEN;
  delete childEnv.AWS_PROFILE;
  // 재검토 N6 — 같은 부류인데 빠져 있던 키들. ANTHROPIC_API_KEY 를 지우면서
  // ANTHROPIC_AUTH_TOKEN(같은 SDK 가 읽는 대체 이름)이나 OPENAI_API_KEY(opencode 의 openai
  // provider 가 관례적으로 자동 인식 — Anthropic 다음으로 직접적인 과금 경로)를 남기면 같은
  // 사고가 이름만 바꿔 재현된다. ANTHROPIC_BASE_URL 은 키가 아니라 라우팅이지만, 남아 있으면
  // 테넌트 설정과 무관한 공유 게이트웨이로 트래픽이 흘러 같은 오귀속이 된다.
  // AWS 쪽은 정적 키만 지우는 게 무의미했다 — IRSA 환경은 AWS_WEB_IDENTITY_TOKEN_FILE +
  // AWS_ROLE_ARN 로, ECS/EKS 컨테이너 자격증명은 AWS_CONTAINER_CREDENTIALS_* 로, 파일 기반은
  // AWS_SHARED_CREDENTIALS_FILE/AWS_CONFIG_FILE 로 동작하므로 체인 전체를 끊어야 한다.
  delete childEnv.OPENAI_API_KEY;
  delete childEnv.ANTHROPIC_AUTH_TOKEN;
  delete childEnv.ANTHROPIC_BASE_URL;
  delete childEnv.AWS_WEB_IDENTITY_TOKEN_FILE;
  delete childEnv.AWS_ROLE_ARN;
  delete childEnv.AWS_CONTAINER_CREDENTIALS_RELATIVE_URI;
  delete childEnv.AWS_CONTAINER_CREDENTIALS_FULL_URI;
  delete childEnv.AWS_SHARED_CREDENTIALS_FILE;
  delete childEnv.AWS_CONFIG_FILE;
  childEnv.OPENCODE_CONFIG_CONTENT = JSON.stringify(openCodeConfig);

  // 재검토 N5 — OPENCODE_CONFIG 를 지우는 것만으로는 전역 설정을 끊지 못한다. opencode CLI 는
  // 그 env 가 없으면 <b>기본 경로</b> `$XDG_CONFIG_HOME/opencode/opencode.json`(XDG 미설정이면
  // `$HOME/.config/opencode/opencode.json`)을 읽고, 배포 차트는 정확히 그 자리에 PVC 를
  // 마운트한다(aiagent-deployment.yaml, mountPath `/home/appuser/.config/opencode`). 즉 이
  // 단계가 없애려던 "배포 전역 설정 상속"이 그대로 살아 있었다.
  //
  // 그래서 자식에게만 XDG_CONFIG_HOME 을 <b>ai-agent 프로세스 전용 임시 디렉터리</b>로 준다.
  // 그 자리에 전역 opencode.json 이 없고(PVC 마운트 경로 밖이라는 것도 경로만 보면 자명하다:
  // OS 임시 디렉터리 아래다), 테넌트 provider 는 OPENCODE_CONFIG_CONTENT 로만 들어온다.
  // 요청별이 아니라 프로세스당 1개인 이유와 그래도 안전한 근거는 getSharedXdgConfigDir 주석 참고
  // (재검토 D1 — 요청별로 두면 매 채팅이 opencode 플러그인 npm 트리를 다시 설치한다).
  //
  // <b>HOME 을 통째로 바꾸지 않는 이유</b>: opencode 세션 상태는 `~/.local/share/opencode`
  // (XDG_DATA_HOME)에 있어 HOME 을 갈아끼우면 `--session` 재개가 매 요청 깨지고, `.claude`/
  // `.firehub` 마운트에 의존하는 다른 경로도 같이 무너진다. 그래서 XDG_CONFIG_HOME 만 바꾼다.
  //
  // <b>한계(재검토 D3)</b>: 이 코드가 끊는 것은 <b>설정</b> 디렉터리 하나뿐이다. opencode 의
  // ambient provider 인증은 `$XDG_DATA_HOME/opencode/auth.json`(기본 `~/.local/share/opencode`)
  // 에도 남을 수 있고, 그 경로는 `--session` 재개 때문에 일부러 살려 둔다 — 즉 이 가드만으로는
  // ML4 가 완전히 닫히지 않는다. 현재는 배포 차트가 `.local/share/opencode` 를 마운트하지 않아
  // 실 위험이 없고, 그 조건이 이 한계를 닫는 실제 근거다(deploy.md 참고).
  //
  // 이 동작(XDG_CONFIG_HOME 이 전역 설정 없는 디렉터리를 가리키면 $HOME/.config/opencode/
  // opencode.json 을 읽지 않고, OPENCODE_CONFIG_CONTENT 는 그대로 읽는다)은 실제 opencode
  // 바이너리(v1.18.31)로 확인했다.
  // 그래도 코드 가드 하나에만 기대지 않는다 — deploy.md 가 PVC subPath 마운트 제거를 배포
  // 조치로 함께 요구한다(가드가 무효화될 경우의 백업).
  childEnv.XDG_CONFIG_HOME = await getSharedXdgConfigDir();

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
  // init yield: 세션 id 는 opencode 첫 이벤트에서 캡처 후 emit(아래).
  // isResume 가 아닌 경우 여기서 임시 firehubSessionId 를 먼저 emit 하고
  // opencodeSessionId 캡처 후 트랜스크립트 저장 시 함께 보존.
  // try 가 첫 yield <b>앞</b>에서 시작한다 — 소비자가 init 직후 제너레이터를 버려도
  // (`gen.return()`) 아래 finally 가 돌아 자식 프로세스가 좀비로 남지 않는다.
  try {
    yield { type: 'init', sessionId: firehubSessionId };
    // 세션 귀속 표식 — /agent/history 심층방어 게이트가 읽는다(session-owner.ts 참조).
    await claimSession(tenantId, firehubSessionId);
    for await (const line of rl) {
      const trimmed = line.trim();
      if (!trimmed) continue;
      let parsedMsg: Record<string, unknown>;
      try {
        parsedMsg = JSON.parse(trimmed) as Record<string, unknown>;
      } catch {
        continue; // 비 JSON 라인 skip
      }
      // OpenCode 자체 발급 세션 id 캡처 (첫 이벤트에서 취득)
      if (!opencodeSessionId && typeof parsedMsg.sessionID === 'string') {
        opencodeSessionId = parsedMsg.sessionID;
      }
      for (const ev of parseOpenCodeEvent(parsedMsg)) {
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
    // XDG 설정 디렉터리는 <b>지우지 않는다</b> — 프로세스 공용이라 여기서 지우면 다른 요청의
    // 캐시까지 날린다(재검토 D1). 수명·정리 방침은 getSharedXdgConfigDir 주석 참고.
    await saveTranscript().catch(() => {}); // done 누락(비정상 종료)에도 진행분 보존
    const stderr = stderrChunks.join('');
    if (stderr) console.error('[OpenCode Agent] stderr:', stderr);
    // 정상 done 이벤트가 없었으면(프로세스 비정상 종료 등) 에러로 마감
    if (!sawDone) {
      yield { type: 'error', message: stderr || 'OpenCode agent terminated without result' };
    }
  }
}
