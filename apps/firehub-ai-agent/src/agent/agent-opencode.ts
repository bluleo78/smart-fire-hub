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
import { OPENCODE_SYSTEM_PROMPT } from './system-prompt.js';
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
/** 요청별 opencode.json 의 형태. model 은 옵션 3(전역 상속)이라 의도적으로 미포함. */
export interface OpenCodeConfig {
  $schema: string;
  model?: string;
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

export function buildOpenCodeConfig(
  userId: number,
  apiBaseUrl: string,
  internalToken: string,
): OpenCodeConfig {
  const { command, args } = getStdioServerCommand();
  return {
    $schema: 'https://opencode.ai/config.json',
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
          // 게이트웨이 호환: tools/list 스키마에서 propertyNames 제거(2026-06-24 실측 400 회피).
          OPENCODE_SCHEMA_COMPAT: '1',
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
 *   그러면 워크스페이스 opencode.json(firehub MCP·도구 잠금·agent 블록)·AGENTS.md 가 로드되지
 *   않아 firehub 도구가 사라지고 모델이 /app 소스를 풀권한으로 훑는다(2026-06-24 운영 실측).
 *   --dir 로 격리 워크스페이스를 프로젝트 루트로 강제해 cwd 의존성을 제거한다.
 * --model 미전달: provider/model 은 배포 측 전역 opencode 설정(Bedrock 등) 상속(옵션 3).
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

export async function* executeOpenCodeAgent(options: ChatProviderOptions): AsyncGenerator<SSEEvent> {
  // fileIds(첨부)는 v1 범위 외 — 의도적으로 destructure 하지 않음.
  const { message, userId, systemPrompt, overrideSystemPrompt, abortSignal } = options;

  const apiBaseUrl = process.env.API_BASE_URL ?? 'http://localhost:8080/api/v1';
  const internalToken = process.env.INTERNAL_SERVICE_TOKEN ?? '';

  const isResume = !!options.sessionId;
  // 세션 id: OpenCode 자체 발급(ses_...) 를 첫 이벤트에서 캡처.
  // isResume 시 기존 트랜스크립트에서 opencodeSessionId 를 읽어 --session 에 전달.
  const firehubSessionId = options.sessionId ?? `oc-${randomUUID()}`;
  let opencodeSessionId: string | undefined;

  // 대화 이력: CLI 와 동일한 CliTranscript JSON 으로 저장 → history 엔드포인트가 그대로 읽음.
  const transcriptPath = getTranscriptPath(firehubSessionId);
  let saved: CliTranscript = { messages: [] };
  if (isResume) {
    try {
      const raw = JSON.parse(await readFile(transcriptPath, 'utf-8')) as CliTranscript & { opencodeSessionId?: string };
      saved = raw;
      // 재개 시 opencode 세션 id 복원
      opencodeSessionId = raw.opencodeSessionId;
    } catch { /* 파일 없으면 새로 시작 */ }
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
    await mkdir(getTranscriptDir(), { recursive: true });
    // opencodeSessionId 를 함께 저장해 재개 시 --session 에 활용
    await writeFile(transcriptPath, JSON.stringify({ messages: transcript, opencodeSessionId }));
  };

  // 사용자별 격리 작업 디렉토리 (소스 접근 차단, 세션 간 파일 유지)
  const userWorkDir = join(homedir(), '.firehub', 'workspaces-opencode', String(userId));
  await mkdir(userWorkDir, { recursive: true });

  // opencode.json 생성 (요청별 USER_ID 주입 + permission 잠금, model 은 전역 상속)
  await writeFile(
    join(userWorkDir, 'opencode.json'),
    JSON.stringify(buildOpenCodeConfig(userId, apiBaseUrl, internalToken), null, 2),
  );

  // 시스템 프롬프트 (단일 에이전트 직접처리). OpenCode 는 프로젝트 디렉토리의 AGENTS.md 를 시스템 지시로 읽는다.
  //  - 위임(task)은 buildOpenCodeConfig 에서 차단하므로 subagent 정의(.opencode/agents)·위임 가이드는
  //    쓰지 않는다. OPENCODE_SYSTEM_PROMPT 가 firehub 도구 직접 호출·결과 요약을 지시한다.
  const effectiveSystemPrompt = resolveSystemPrompt(OPENCODE_SYSTEM_PROMPT, systemPrompt, overrideSystemPrompt);
  await writeFile(join(userWorkDir, 'AGENTS.md'), effectiveSystemPrompt, 'utf-8');

  // 재개: opencode 자체 발급 세션 id(ses_...) 를 --session 에 전달.
  // OpenCode 가 외부 id 를 수용하지 않으므로 첫 이벤트 sessionID 를 캡처해 저장/재사용.
  const cliArgs = buildOpenCodeRunArgs(message || '', userWorkDir, isResume ? opencodeSessionId : undefined);

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
  // init yield: 세션 id 는 opencode 첫 이벤트에서 캡처 후 emit(아래).
  // isResume 가 아닌 경우 여기서 임시 firehubSessionId 를 먼저 emit 하고
  // opencodeSessionId 캡처 후 트랜스크립트 저장 시 함께 보존.
  yield { type: 'init', sessionId: firehubSessionId };
  try {
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
    await saveTranscript().catch(() => {}); // done 누락(비정상 종료)에도 진행분 보존
    const stderr = stderrChunks.join('');
    if (stderr) console.error('[OpenCode Agent] stderr:', stderr);
    // 정상 done 이벤트가 없었으면(프로세스 비정상 종료 등) 에러로 마감
    if (!sawDone) {
      yield { type: 'error', message: stderr || 'OpenCode agent terminated without result' };
    }
  }
}
