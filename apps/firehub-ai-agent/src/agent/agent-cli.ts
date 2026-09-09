/**
 * CLI agent executor using `claude -p` (Claude Code CLI).
 *
 * Spawns the Claude Code CLI process with a temporary MCP config that points
 * to the stdio MCP server, then parses stream-json output into SSEEvents
 * matching the same interface as executeAgent() in agent-sdk.ts.
 */
import { spawn } from 'child_process';
import { mkdir, readFile, readdir, writeFile, unlink } from 'fs/promises';
import { tmpdir } from 'os';
import { join } from 'path';
import { createInterface } from 'readline';
import { randomUUID } from 'crypto';
import { getStdioServerCommand } from '../mcp/stdio-server-command.js';
import { SYSTEM_PROMPT, FILE_ATTACHMENT_PROMPT } from './system-prompt.js';
import { resolveSystemPrompt } from './prompt-utils.js';
import { totalInputTokens, type TokenUsageLike } from './token-usage.js';
import { loadSubagents, buildSubagentGuide } from './subagent-loader.js';
import type { AgentDefinition } from '@anthropic-ai/claude-agent-sdk';
import type { SSEEvent, AgentOptions } from './agent-sdk.js';
import { stripAgentResultFooter } from './process-message.js';
import {
  isSafeSessionId,
  legacyTranscriptDir,
  transcriptDir,
  workspaceDir,
} from './tenant-paths.js';
import { claimSession } from './session-owner.js';
import type { HistoryMessage, HistoryToolCall } from './transcript-reader.js';
import { DEFAULT_MODEL, MAX_BUDGET_USD, COST_ALARM_TURNS } from '../constants.js';
import { FireHubApiClient } from '../mcp/api-client.js';
import {
  downloadChatFiles,
  cleanupChatFiles,
  toAttachmentMeta,
  saveSessionAttachments,
  formatAttachmentLine,
} from './file-downloader.js';
import { DISALLOWED_TOOLS, checkToolPolicy } from './tool-policy.js';
import { createTracker, buildHaltMessage } from './failure-streak.js';
import {
  createDelegationNarrationState,
  classifyMainText,
  noteMainToolUse,
  noteMainToolResult,
  noteSubagentText,
  isNoopHostToolCall,
  redactSubagentIdentifiers,
  createRoutingVocabRedactor,
} from './delegation-narration-guard.js';

/**
 * #410: CLI OAuth 토큰 만료/무효 시 원문 영문 인증 실패 문구가 그대로 노출되던 결함의 패턴.
 *
 * 최초 수정은 `result` 메시지의 `subtype`이 `error*` 인 경로만 검사했으나, 실제로는 CLI 가
 * 인증 실패를 일반 `assistant` text 블록이나 `stream_event` text_delta 로 흘리고 세션 자체는
 * `success`(=done) 로 끝내는 경로가 있어(#410 크로스체크 회귀) 두 경로 모두에서 이 패턴을 검사한다.
 */
const AUTH_FAILURE_PATTERN =
  /not logged in|please run \/login|failed to authenticate|oauth access token is invalid/i;

/** 인증 실패 시 사용자에게 노출할 한국어 안내. 원본 영문 문구는 서버 로그에만 남긴다. */
const AUTH_FAILURE_KOREAN_MESSAGE =
  'AI 에이전트 인증이 만료되었습니다. 관리자에게 문의하거나 설정 > AI 에이전트에서 OAuth 토큰을 갱신해 주세요.';

/**
 * CLI·OpenCode 공용 트랜스크립트 파일 형식.
 *
 * <p>하위 에이전트 세션 id 는 경로별로 다르지만(claude / opencode) **같은 봉투**에 담긴다 —
 * `/agent/history` 가 두 경로의 파일을 같은 코드로 읽는 계약이라 형식을 갈라 두면 안 된다.
 * 예전에는 OpenCode 필드가 이 타입에 없어 호출부가 같은 값을 두 번 캐스트했다.
 */
export interface CliTranscript {
  claudeSessionId?: string;
  opencodeSessionId?: string;
  messages: HistoryMessage[];
}

/**
 * 트랜스크립트 파일 경로. 세그먼트 검증은 두 층 모두 필요하다 — `sessionId` 는 HTTP 경로 변수에서
 * 오므로 경로 이탈(`../`)을 여기서 막고, `tenantId` 는 {@link transcriptDir} 가 fail-closed 로 막는다.
 */
export function getTranscriptPath(tenantId: number, sessionId: string): string {
  if (!isSafeSessionId(sessionId)) {
    throw new Error(`Invalid sessionId: ${sessionId}`);
  }
  return join(transcriptDir(tenantId), `${sessionId}.json`);
}

/** {@link readCliTranscript} 결과. `fromLegacy` 는 테넌트 세그먼트 이전 경로에서 읽었다는 뜻이다. */
export interface LoadedCliTranscript {
  transcript: CliTranscript;
  fromLegacy: boolean;
}

/**
 * CLI 트랜스크립트를 읽는다. 테넌트 경로를 먼저 보고, 없으면 **세그먼트 도입 전 레거시 경로**를
 * 한 번 더 본다(없으면 `null`).
 *
 * <p><b>레거시 폴백이 있어야 하는 이유는 재개(resume) 다.</b> 폴백 없이 테넌트 경로만 읽으면
 * 과거 세션을 이어 말할 때 `saved` 가 빈 값이 되어, 그 세션의 지난 대화가 조용히 사라진 채
 * 새 트랜스크립트가 테넌트 경로에 덮여 쓰인다 — 열람(history)뿐 아니라 재개도 같은 구멍을 갖는다.
 * 읽기만 폴백하고 쓰기는 항상 테넌트 경로로 가므로, 이어 말한 세션은 자연히 이관된다(지연 이관).
 *
 * <p>이관이 안전한 근거: 이 경로에 도달하기 전 firehub-api 가 `ai_session` RLS +
 * `verifySessionOwnership` 으로 소유권을 검증한다 — 도달 가능한 호출자는 그 세션을 소유한
 * 테넌트의 사용자뿐이므로 요청 테넌트를 그 파일의 귀속으로 취급해도 된다.
 *
 * <p><b>`fromLegacy` 를 돌려주는 이유(코드리뷰 지적).</b> 우리 트랜스크립트의 메시지는 그대로
 * 살리되 그 안의 **하위 에이전트 세션 id 는 버려야** 한다 — 아래 호출부 주석 참조.
 */
export async function readCliTranscript(
  tenantId: number,
  sessionId: string,
): Promise<LoadedCliTranscript | null> {
  const candidates: Array<{ path: string; fromLegacy: boolean }> = [
    { path: getTranscriptPath(tenantId, sessionId), fromLegacy: false },
    { path: join(legacyTranscriptDir(), `${sessionId}.json`), fromLegacy: true },
  ];
  for (const candidate of candidates) {
    let parsed: unknown;
    try {
      parsed = JSON.parse(await readFile(candidate.path, 'utf-8'));
    } catch {
      continue;
    }
    // 아주 옛 파일은 봉투 없이 메시지 배열만 저장돼 있다. **여기서** 정규화한다 — 소비자마다
    // 하면 아는 쪽만 처리하고 모르는 쪽은 `messages` 가 undefined 가 된다(재개 경로가 그랬다).
    const transcript: CliTranscript = Array.isArray(parsed)
      ? { messages: parsed as HistoryMessage[] }
      : (parsed as CliTranscript);
    return { transcript, fromLegacy: candidate.fromLegacy };
  }
  return null;
}

/**
 * 트랜스크립트를 테넌트 경로에 저장한다(디렉터리 생성 포함).
 *
 * <p>CLI 와 OpenCode 가 **같은 봉투·같은 경로**로 써야 `/agent/history` 가 한 코드로 읽는다 —
 * 그 계약을 지키는 코드가 두 곳에 흩어져 있으면 P5 가 새로 추가한 세 번째 불변식(테넌트
 * 세그먼트)처럼 양쪽을 손으로 맞춰야 한다. 저장할지 말지(첫 턴 스킵 등)는 호출부 정책이라
 * 여기서 판단하지 않는다.
 */
export async function writeCliTranscript(
  tenantId: number,
  sessionId: string,
  envelope: CliTranscript,
): Promise<void> {
  const path = getTranscriptPath(tenantId, sessionId);
  await mkdir(transcriptDir(tenantId), { recursive: true });
  await writeFile(path, JSON.stringify(envelope));
}


function buildMcpConfig(
  userId: number,
  apiBaseUrl: string,
  internalToken: string,
  credentials?: { apiKey?: string; oauthToken?: string },
): object {
  const { command, args } = getStdioServerCommand();
  // stdio MCP 서버는 별도 프로세스이고 env 를 여기서 명시적으로 구성한다.
  // GraphRAG 도구가 내부적으로 LLM completion 을 호출하므로 요청 자격증명을 반드시 실어 보내야 한다 —
  // 넣지 않으면 그 프로세스는 자격증명 없이 뜨고 GraphRAG 가 prod 에서 인증에 실패한다.
  const env: Record<string, string> = {
    API_BASE_URL: apiBaseUrl,
    INTERNAL_SERVICE_TOKEN: internalToken,
    USER_ID: String(userId),
  };
  if (credentials?.oauthToken?.trim()) {
    env.CLAUDE_CODE_OAUTH_TOKEN = credentials.oauthToken;
  } else if (credentials?.apiKey?.trim()) {
    env.ANTHROPIC_API_KEY = credentials.apiKey;
  }

  return {
    mcpServers: {
      firehub: {
        command,
        args,
        env,
      },
    },
  };
}

interface StreamJsonMessage {
  type: string;
  message?: {
    content?: Array<{
      type: string;
      id?: string;
      name?: string;
      input?: unknown;
      tool_use_id?: string;
      content?: Array<{ type: string; text?: string }> | string;
      text?: string;
    }>;
  };
  result?: string;
  // #336: 캐시 토큰까지 담는다 — 컨텍스트 사용량 칩은 캐시분을 포함한 전체 크기를 봐야 한다.
  usage?: TokenUsageLike & { output_tokens?: number };
  delta?: { type?: string; text?: string };
  subtype?: string;
  cost_usd?: number;
  session_id?: string;
  // #428: claude CLI(`claude -p --output-format stream-json`)도 Agent SDK 와 동일하게
  // subagent 로 위임된 메시지에 위임 tool_use 의 id 를 채워 흘려보낸다(top-level 메인은 null).
  parent_tool_use_id?: string | null;
  // #430: 이전 요청(턴)에서 `Agent`/`SendMessage` 로 위임한 비동기 subagent 가 **이번 요청
  // 진행 중에** 뒤늦게 완료되면, 인터리브된 assistant 메시지가 아니라 이 top-level
  // `system/task_notification` 메시지로 알려온다 — 어느 위임(tool_use id)의 완료인지는
  // `tool_use_id` 로 식별한다.
  task_id?: string;
  tool_use_id?: string;
  status?: string;
}

export interface CliAgentOptions extends AgentOptions {
  /** true = 구독 인증 사용 (ANTHROPIC_API_KEY 제거), false = API 키 사용 */
  useSubscription?: boolean;
  /** DB에서 복호화된 OAuth 토큰 (구독 모드에서 CLAUDE_CODE_OAUTH_TOKEN으로 설정) */
  oauthToken?: string;
}

export async function* executeCliAgent(options: CliAgentOptions): AsyncGenerator<SSEEvent> {
  const {
    message,
    tenantId,
    userId,
    fileIds,
    model,
    systemPrompt,
    overrideSystemPrompt,
    apiKey,
    oauthToken,
    abortSignal,
    useSubscription = true,
  } = options;

  const apiBaseUrl = process.env.API_BASE_URL ?? 'http://localhost:8080/api/v1';
  const internalToken = process.env.INTERNAL_SERVICE_TOKEN ?? '';

  // 세션 재개 또는 새 세션 생성
  const isResume = !!options.sessionId;
  const sessionId = options.sessionId ?? `cli-${randomUUID()}`;
  yield { type: 'init', sessionId };
  // 세션 귀속 표식 — /agent/history 심층방어 게이트가 읽는다(session-owner.ts 참조).
  // `await` 하지 않는다: 표식은 best-effort 심층방어라 실패해도 채팅을 죽이지 않기로 했는데,
  // 기다리면 그 파일 작업이 매 턴 응답 지연에 들어간다(SDK 경로와 동일한 처리).
  void claimSession(tenantId, sessionId);

  // 첨부 파일 다운로드 및 메시지 변환
  let enhancedMessage = message || '';
  // 테넌트·사용자별 격리된 작업 디렉토리 (세션 간 파일 유지, 소스 코드 접근 차단).
  // 같은 사용자가 두 테넌트에 속하면 두 워크스페이스가 분리된다 — claude CLI 의 cwd 이기도 해서
  // SDK 트랜스크립트가 쌓이는 `~/.claude/projects/{cwd 파생}` 프로젝트 디렉터리까지 함께 갈린다.
  const userWorkDir = workspaceDir(tenantId, userId);
  await mkdir(userWorkDir, { recursive: true });
  const chatFilesDir = join(userWorkDir, 'chat-files', String(Date.now()));
  let downloadedFiles: Awaited<ReturnType<typeof downloadChatFiles>>['files'] = [];

  if (fileIds?.length) {
    const apiClient = new FireHubApiClient(apiBaseUrl, internalToken, userId);
    const { files, failed } = await downloadChatFiles(apiClient, fileIds, chatFilesDir);
    downloadedFiles = files;

    if (failed > 0) {
      console.warn(`[CLI Agent] ${failed}개 파일 다운로드 실패 (만료/삭제됨)`);
    }

    // 첨부 파일 메타데이터 사이드카 저장 (히스토리에서 첨부 표시용)
    if (files.length > 0) {
      saveSessionAttachments(tenantId, sessionId, toAttachmentMeta(files)).catch(() => {});
      const imageFiles = files.filter((f) => f.mimeType.startsWith('image/'));
      const nonImageFiles = files.filter((f) => !f.mimeType.startsWith('image/'));

      const parts: string[] = ['[첨부 파일]'];

      // formatAttachmentLine이 fileId 포함 포맷의 single source of truth (refs #264)
      if (imageFiles.length > 0) {
        const imgList = imageFiles.map((f) => formatAttachmentLine(f, false)).join('\n');
        parts.push(`[이미지]\n${imgList}\n→ Read 도구로 열면 이미지를 직접 볼 수 있습니다. 반드시 Read로 열어서 시각적으로 분석하세요.`);
      }

      if (nonImageFiles.length > 0) {
        const fileList = nonImageFiles.map((f) => formatAttachmentLine(f, true)).join('\n');
        parts.push(`[파일]\n${fileList}\n→ Read 도구로 읽을 수 있습니다.`);
      }

      enhancedMessage = parts.join('\n\n') + '\n\n' + (message || '첨부된 파일을 분석해주세요.');
    }
  }

  // 진입 시점에 세션 id 를 검증한다 — 경로 조립(쓰기/읽기)까지 미루면 요청 처리를 한참
  // 진행한 뒤에 터진다. 경로 자체는 읽기·쓰기 헬퍼가 각자 만든다.
  if (!isSafeSessionId(sessionId)) {
    throw new Error(`Invalid sessionId: ${sessionId}`);
  }

  let saved: CliTranscript = { messages: [] };
  if (isResume) {
    // 레거시 경로 폴백 포함 — 없으면 새로 시작한다(readCliTranscript javadoc 참조).
    const loaded = await readCliTranscript(tenantId, sessionId);
    if (loaded) {
      saved = loaded.transcript;
      if (loaded.fromLegacy) {
        // 레거시 트랜스크립트의 claudeSessionId 는 **버려야 한다**(코드리뷰 지적 MAJOR).
        // claude CLI 는 대화를 cwd 파생 프로젝트 디렉터리(`~/.claude/projects/{...}`)에 두는데,
        // 이 커밋이 cwd 에 테넌트 세그먼트를 끼웠으므로 예전 cwd 에서 만들어진 그 id 는 지금
        // 프로젝트 디렉터리에서 찾을 수 없다. 그대로 `--resume` 에 넘기면 CLI 가
        // "No conversation found with session ID" 로 죽고 result.subtype 이
        // `error_during_execution` 으로 돌아온다 — 그리고 그 subtype 이 처리되지 않으면
        // 빈 응답이 성공처럼 나가며, saveTranscript 가 같은 낡은 id 를 다시 써서 그 세션이
        // **영구히** 같은 실패를 반복한다.
        //
        // 버려도 사용자가 보는 이력은 그대로다 — 화면의 대화는 우리 트랜스크립트의 messages 에서
        // 오고, 버리는 것은 CLI 내부 대화 핸들뿐이다. 결과적으로 이 턴은 우리 이력을 유지한 채
        // CLI 쪽 대화만 새로 시작한다(CLI 대화가 이미 정리된 세션에서 원래도 벌어지는 일이다).
        saved.claudeSessionId = undefined;
      }
    }
  }

  const transcript = saved.messages;
  let claudeSessionId = saved.claudeSessionId;
  const now = () => new Date().toISOString();
  let assistantText = '';
  let assistantToolCalls: HistoryToolCall[] = [];
  // Tier2 강제중단용 연속 실패 트래커
  const haltTracker = createTracker();
  // #277 소프트 알람: 턴 수 누적 + 1회 emit 플래그
  let costTurnCount = 0;
  let costAlarmEmitted = false;
  // #410 회귀: CLI 인증 실패가 result.subtype=error* 경로가 아니라 일반 assistant text
  // 이벤트로만 오고 세션이 done(성공)으로 끝나는 경우가 있어, 텍스트 경로에서도 검사한다.
  // 한 응답 내에서 여러 delta/블록에 걸쳐 중복 치환하지 않도록 플래그로 1회만 처리.
  let authFailureDetected = false;
  // #428/#429: Agent 로 위임된 subagent(subagent_type 불문)가 이미 텍스트로 자기 턴을 마쳤는데
  // 메인(parent_tool_use_id=null)이 같은 요청 안에서 이를 재요약해 별도 텍스트를 또 출력하면
  // 사용자에게 동일 확인이 두 번 노출된다(라이브 재현으로 확인). CLI 로 스폰된 claude 프로세스의
  // stream-json 출력도 SDK 와 동일하게 parent_tool_use_id 로 위임된 하위 턴을 구분해 흘려보내
  // 므로, 위임 tool_use id 를 기록해두고 그 id 를 parent 로 갖는 텍스트가 한 번이라도 도착하면
  // (문구 내용 불문) 이후 메인(parent 없음)의 텍스트를 억제한다.
  //
  // #429: 최초 구현은 pipeline-builder/template-builder/dashboard-builder 3개로 좁힌
  // 화이트리스트였으나, 같은 재서술 패턴이 smart-job-manager 등 화이트리스트 밖 subagent 에서도
  // 재현되어 화이트리스트를 폐지하고 Agent 위임 전체에 적용한다. suppressMainText 는 한 번
  // 세팅되면 유지되므로, 메인이 새 tool_use 를 발행하면(= 재서술이 아니라 실제 다음 작업을
  // 하고 있다는 구조적 증거) 그 시점에 해제한다 — 그래야 "subagent 완료 후 메인이 추가 도구
  // 호출을 거쳐 결과를 보고"하는 정당한 시나리오(예: 파이프라인 생성 후 바로 실행)까지 영구
  // 억제하지 않는다.
  const pendingDesignGuardToolUseIds = new Set<string>();
  let suppressMainText = false;
  // #573 2차 수정: process-message.ts(SDK 프로바이더)에만 이식됐던 동기 위임 fallback relay가
  // 실제 재현 경로인 CLI 프로바이더(agentType: "cli")에는 전혀 없었다 — 크로스체크가 여전히
  // 재발을 확인한 진짜 원인. `Agent(..., run_in_background: false)` 로 위임된 tool_use id 를
  // 기록해두고(subagent 내부 텍스트가 interleave 될 기회가 없는 경로), 그 tool_result 를
  // 메인이 텍스트로 relay 하지 않은 채 턴이 끝나면 강제로 relay 한다.
  const syncDelegationToolUseIds = new Set<string>();
  let pendingSyncAgentResultText: string | undefined;
  // #572 2차 수정(회귀): 1차 수정(위 "always run_in_background:false" 규칙 + #573 fallback)은
  // "메인이 텍스트를 전혀 안 낸 경우"만 방어했다. 실제 재현된 회귀는 모델이 data-analyst 위임
  // (Agent, run_in_background:false)과 find_datasets/get_dataset 등 조사 도구를 같은 tool_use
  // 배치로 병렬 발행하는 것 — 실행이 빠른 조사 도구가 먼저 끝나고 무거운 Agent 위임은 가장 늦게
  // 끝난다. 이후 메인은 "텍스트를 냈다"는 이유만으로 위 fallback 의 "이미 relay 됐다" 판정을
  // 통과시켜(pendingSyncAgentResultText 리셋) 방어가 무력화됐다(크로스체크 3회 재현: 위임의
  // tool_result 가 항상 가장 마지막에 도착 — 라이브 trace 로 확인). 위임의 tool_result 를 아직
  // 받기 전에 메인이 Agent/SendMessage 가 아닌 다른 tool_use 를 발행하는 "시점" 자체를 위반으로
  // 표시하면(병렬/순차 여부와 무관 — 발행 이벤트 자체가 증거), 이후 메인의 모든 text 를 사용자에게
  // 노출하지 않고 억제한 채 위임의 실제 tool_result 만 강제로 relay 할 수 있다.
  let syncDelegationViolated = false;

  // 사용자 메시지 기록 — 원본 메시지 + 첨부 메타 저장 (파일 경로는 AI에게만 전달)
  const userMsg: HistoryMessage = {
    id: `user-${Date.now()}`,
    role: 'user',
    content: message || '첨부된 파일을 분석해주세요.',
    timestamp: now(),
  };
  if (downloadedFiles.length > 0) {
    userMsg.attachments = toAttachmentMeta(downloadedFiles);
  }
  transcript.push(userMsg);

  const commitAssistant = () => {
    if (!assistantText && assistantToolCalls.length === 0) return;
    transcript.push({
      id: `assistant-${Date.now()}`,
      role: 'assistant',
      content: assistantText,
      toolCalls: assistantToolCalls.length > 0 ? assistantToolCalls : undefined,
      timestamp: now(),
    });
    assistantText = '';
    assistantToolCalls = [];
  };

  const saveTranscript = async () => {
    commitAssistant();
    if (transcript.length <= 1) return;
    await writeCliTranscript(tenantId, sessionId, { claudeSessionId, messages: transcript });
  };

  // 환경변수(API_BASE_URL, INTERNAL_SERVICE_TOKEN) 변경 시에도 최신 상태 유지
  const mcpConfigPath = join(userWorkDir, 'mcp.json');
  await writeFile(
    mcpConfigPath,
    JSON.stringify(
      buildMcpConfig(userId, apiBaseUrl, internalToken, { apiKey, oauthToken }),
      null,
      2,
    ),
  );

  const effectiveModel = model ?? DEFAULT_MODEL;

  // #240: firehub 전문 subagent 정의를 CLI에 전달한다.
  // 전달하지 않으면 spawn된 `claude` CLI는 호스트의 빌트인/플러그인 agent만 인지하므로
  // 시스템 프롬프트가 지시한 `Agent(subagent_type: "pipeline-builder")` 호출이
  // "Agent type not found"로 실패하고, 폴백으로 메인 에이전트가 직접 firehub MCP
  // 도구를 호출해 subagent의 rules.md(파괴 확인·GIS 자동 감지 등)가 우회된다.
  // SDK 프로바이더(agent-sdk.ts)는 동일 정의를 `options.agents`로 이미 전달하고 있다.
  // #260: 초기에는 `--agents <json>` 인자로 전달했으나, 11개 subagent 정의 JSON 합산이
  // 172KB에 달해 Linux execve MAX_ARG_STRLEN(128KB) 초과 → spawn E2BIG 재발.
  // claude CLI 는 CWD `.claude/agents/*.md` 를 자동 발견하므로, userWorkDir 하위에
  // 정의 파일들을 써두고 `--agents` 플래그는 사용하지 않는다.
  const subagents = loadSubagents();
  await writeSubagentDefinitions(userWorkDir, subagents);
  // #578: 위임 narration 가드(코드 레벨 백스톱). 프롬프트(#239 L2 가드)만으로는 "trigger-manager에게
  // 위임합니다"·위임 직후 "완료되면 전달드릴게요"·`Bash("echo noop")` 가 재호출에서도 그대로 재발했다.
  // 구조 신호(subagent 코드명 포함 / 비동기 위임 직후 구간 / no-op 호스트 도구)로만 판별하며,
  // 억제 때문에 사용자에게 아무 텍스트도 안 나가는 경우는 아래 `result` 처리에서 fallback 으로 막는다.
  const narrationState = createDelegationNarrationState();
  const subagentNames = Object.keys(subagents);
  // #581: 도구 호출이 전혀 없는 되묻기 턴의 라우팅 어휘("…는 위임하되, 먼저 …") 누출은 위 두 축이 못
  // 잡는다 — 메인 텍스트에 한해 어휘를 중립 표현으로 치환한다(억제 아님 → 빈 응답·relay 누락 불가).
  const routingRedactor = createRoutingVocabRedactor(message);
  let userTextEmitted = false;
  /** #578: 다음 이벤트로 "위임 직전 narration" 여부를 판정하기 위해 보류 중인 메인 텍스트 블록 */
  let pendingMainText: string | undefined;
  // 시스템 프롬프트에 동적 위임 가이드를 부착(SDK 프로바이더와 동일 패턴).
  // subagent 이름 변경/추가 시 system-prompt.ts 정적 표와 동시에 갱신되도록 한다.
  const subagentGuide = buildSubagentGuide(subagents);
  // #260: 파일 첨부 가이드는 fileIds가 있는 요청에만 동적 첨부 (cold cache_creation 945 토큰 절감)
  const fileAttachmentGuide = fileIds?.length ? FILE_ATTACHMENT_PROMPT : '';
  const basePromptWithGuide = `${SYSTEM_PROMPT}${subagentGuide}${fileAttachmentGuide}`;
  const effectiveSystemPrompt = resolveSystemPrompt(basePromptWithGuide, systemPrompt, overrideSystemPrompt);

  // #256: SDK 프로바이더와 동일한 정책을 CLI 프로바이더에도 적용한다.
  // - --allowed-tools: firehub MCP + Agent 위임만 허용 (화이트리스트)
  // - --disallowed-tools: host skill/task/IO/네트워크 도구 명시 차단 (이중 안전망)
  // - --disable-slash-commands: 호스트의 skill ecosystem (Skill 도구 진입점) 비활성
  //   spawn 된 `claude` CLI 가 호스트 `~/.claude/skills/` 를 자동 로드하여 Skill 도구를
  //   노출하는 경로를 차단한다. firehub 메인 에이전트는 슬래시 커맨드/스킬을 사용하지
  //   않으므로 영향 없음.
  // CLI 플래그 이름은 `claude --help` 기준 camelCase 와 hyphen 모두 인식되나, 안정성을
  // 위해 hyphen 표기(--allowed-tools / --disallowed-tools) 를 사용한다.
  // #259: system-prompt 가 매우 크면 --system-prompt 인자로 직접 전달 시
  // 다른 인자와 누적되어 Linux ARG_MAX(컨테이너 128KB) 초과로 spawn E2BIG 발생.
  // claude CLI 의 --system-prompt-file 옵션을 사용해 임시 파일 경로로 전달한다.
  // 임시 파일은 spawn 종료 후 정리.
  const systemPromptFile = join(tmpdir(), `firehub-sysprompt-${randomUUID()}.txt`);
  await writeFile(systemPromptFile, effectiveSystemPrompt, 'utf-8');

  const cliArgs = [
    '-p', enhancedMessage,
    '--output-format', 'stream-json',
    '--verbose',
    '--include-partial-messages',
    '--mcp-config', mcpConfigPath,
    '--strict-mcp-config',
    '--system-prompt-file', systemPromptFile,
    '--permission-mode', 'bypassPermissions',
    // #266: --allowed-tools 미전달 (allow-by-default). --disallowed-tools 만 명시 차단.
    '--disallowed-tools', DISALLOWED_TOOLS.join(','),
    '--disable-slash-commands',
    // #277: 쿼리 전체(서브에이전트 포함) USD 예산 하드 캡. 초과 시 result 가 budget 에러.
    '--max-budget-usd', String(MAX_BUDGET_USD),
    '--model', effectiveModel,
  ];

  // #260: subagent 정의는 `--agents` 인자 대신 `userWorkDir/.claude/agents/*.md`
  // 파일로 전달한다(위 writeSubagentDefinitions 호출). argv 크기 한계 회피.

  // 세션 재개: Claude Code의 내부 session ID로 이전 컨텍스트 복원
  if (isResume && claudeSessionId) {
    cliArgs.push('--resume', claudeSessionId);
  }

  const childEnv = { ...process.env };
  if (useSubscription) {
    // 구독 모드: ANTHROPIC_API_KEY를 제거하여 Claude Pro/Max 구독 인증 사용
    delete childEnv.ANTHROPIC_API_KEY;
    if (oauthToken) {
      childEnv.CLAUDE_CODE_OAUTH_TOKEN = oauthToken;
    }
  } else {
    // API 모드: 전달받은 API 키 사용 (종량제)
    const effectiveApiKey = apiKey ?? process.env.ANTHROPIC_API_KEY ?? '';
    if (!effectiveApiKey) {
      yield { type: 'error', message: 'API key not provided' };
      return;
    }
    childEnv.ANTHROPIC_API_KEY = effectiveApiKey;
  }

  const child = spawn('claude', cliArgs, {
    cwd: userWorkDir,
    env: childEnv,
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  // Propagate abort signal
  if (abortSignal) {
    abortSignal.addEventListener('abort', () => child.kill('SIGTERM'), { once: true });
  }

  const stderrChunks: string[] = [];
  let stderrSize = 0;
  const MAX_STDERR_BYTES = 64 * 1024; // 64KB cap

  child.stderr?.on('data', (chunk: Buffer) => {
    const str = chunk.toString();
    stderrChunks.push(str);
    stderrSize += str.length;
    while (stderrSize > MAX_STDERR_BYTES && stderrChunks.length > 1) {
      stderrSize -= stderrChunks.shift()!.length;
    }
  });

  const rl = createInterface({ input: child.stdout!, crlfDelay: Infinity });

  try {
    for await (const line of rl) {
      const trimmed = line.trim();
      if (!trimmed) continue;

      let msg: StreamJsonMessage;
      try {
        msg = JSON.parse(trimmed) as StreamJsonMessage;
      } catch {
        // Skip non-JSON lines (e.g. debug output)
        continue;
      }
      // #581: 메인 텍스트 델타가 아닌 이벤트가 오면 어휘 치환기가 보류 중인 꼬리를 먼저 내보낸다
      // (보류는 델타 경계에 걸친 어휘 판정용이라 다음 이벤트에서 즉시 풀어야 순서가 어긋나지 않는다).
      const isMainTextDelta =
        msg.type === 'stream_event' && msg.delta?.type === 'text_delta' && Boolean(msg.delta.text) && !msg.parent_tool_use_id;
      if (!isMainTextDelta) {
        const heldTail = routingRedactor.flush();
        if (heldTail) {
          userTextEmitted = true;
          yield { type: 'text', content: heldTail };
        }
      }
      // #578: 위임 **직전** 메인 텍스트("Found pipeline ID 18. Delegating…", "…위임하겠습니다") 판별.
      // 이 텍스트는 Agent tool_use 보다 먼저 도착하므로 도착 시점엔 narration 인지 알 수 없다 — CLI
      // 경로는 텍스트가 완성 블록 단위로 오므로, 메인 텍스트 블록 하나를 보류(pendingMainText)했다가
      // **다음 이벤트**로 판정한다: 다음이 메인의 Agent/SendMessage 위임이면 버리고(위임 예고),
      // 그 외(다른 도구·tool_result·subagent 이벤트·result)면 그 이벤트 앞에 그대로 내보낸다. 지연은
      // stream-json 한 줄 분량이라 체감되지 않고, 최종 답변은 곧바로 뒤따르는 `result` 에서 flush 된다.
      //
      // 주의(라이브 raw stream-json 으로 확인): 텍스트 `assistant` 메시지 뒤에는 의미 없는 `stream_event`
      // (content_block_stop / message_delta / message_stop / 다음 메시지의 message_start …)가 여러 줄 따라온다.
      // 이 줄들에서 판정하면 항상 "위임 아님"으로 flush 돼 가드가 무력화된다(1차 구현의 실제 실패 원인).
      // 따라서 stream_event 는 다음 tool_use 의 content_block_start(이름을 알 수 있음)에서만 판정하고,
      // 그 외 stream_event/rate_limit_event 는 보류를 유지한다.
      if (pendingMainText !== undefined) {
        let decision: 'drop' | 'flush' | 'hold' = 'hold';
        if (msg.type === 'stream_event') {
          const ev = (msg as { event?: { type?: string; content_block?: { type?: string; name?: string } } }).event;
          if (ev?.type === 'content_block_start' && ev.content_block?.type === 'tool_use' && !msg.parent_tool_use_id) {
            decision = ev.content_block.name === 'Agent' || ev.content_block.name === 'SendMessage' ? 'drop' : 'flush';
          }
        } else if (msg.type === 'rate_limit_event') {
          decision = 'hold';
        } else if (msg.type === 'assistant' && !msg.parent_tool_use_id) {
          const delegates = Boolean(
            msg.message?.content?.some((b) => b.type === 'tool_use' && (b.name === 'Agent' || b.name === 'SendMessage')),
          );
          decision = delegates ? 'drop' : 'flush';
        } else {
          decision = 'flush';
        }
        if (decision !== 'hold') {
          const held = pendingMainText;
          pendingMainText = undefined;
          if (decision === 'drop') {
            narrationState.lastSuppressedMainText = held;
            console.log(`[CLI Agent] [narration-guard] 위임 직전 메인 텍스트 억제(#578): ${held.slice(0, 80)}`);
          } else {
            userTextEmitted = true;
            yield { type: 'text', content: routingRedactor.redact(held) };
          }
        }
      }
      // Stream text deltas
      if (msg.type === 'stream_event' && msg.delta?.type === 'text_delta' && msg.delta.text) {
        // #572 2차 수정(라이브 재현으로 확정된 실제 메커니즘): 동기 위임의 tool_result 를 받은
        // 뒤 메인이 내놓는 첫 텍스트를 "정상 relay 로 신뢰"하던 기존 전제(#573)가 틀렸다 —
        // 실측 결과 data-analyst 위임이 끝난 뒤 메인은 그 tool_result 를 **문자 그대로 relay
        // 하지 않고 자신의 말로 재구성(표 행 누락·문구 변경)** 했는데, 그 텍스트가 "텍스트를
        // 냈다"는 이유만으로 신뢰받아 그대로 사용자에게 노출됐다(fallback 도 트리거 안 됨,
        // 크로스체크 3회 재현). 이제는 동기 위임 tool_result 대기 중인(`pendingSyncAgentResultText`
        // 가 세팅된) 상태에서는 메인의 텍스트를 **내용에 관계없이** 절대 신뢰하지 않고 억제한다
        // — 신뢰할 수 있는 유일한 신호는 메인이 새 tool_use 를 발행하는 것(실제 후속 작업의
        // 구조적 증거, 아래 tool_use 분기 참조)뿐이다. 억제된 텍스트는 트랜스크립트에도 남기지
        // 않는다(누적조차 하지 않음) — 아래 `result` 처리에서 위임의 실제 tool_result 를 강제로
        // relay 하므로, 여기서 섞으면 잘못된 텍스트와 강제 relay 텍스트가 뒤섞인다.
        if (!msg.parent_tool_use_id && (pendingSyncAgentResultText !== undefined || syncDelegationViolated)) {
          continue;
        }
        assistantText += msg.delta.text;
        // #410: 이미 이번 응답에서 인증 실패로 판정했으면 이후 델타는 원문 조각이 섞여 있을 수
        // 있으므로 더 이상 사용자에게 그대로 흘리지 않는다(한국어 안내는 아래서 1회만 emit됨).
        if (authFailureDetected) {
          continue;
        }
        if (AUTH_FAILURE_PATTERN.test(assistantText)) {
          authFailureDetected = true;
          console.warn(`[CLI Agent] [auth-failure] stream_event text=${assistantText}`);
          yield { type: 'error', message: AUTH_FAILURE_KOREAN_MESSAGE };
          try {
            child.kill('SIGTERM');
          } catch {
            /* ignore */
          }
          return;
        }
        // #428: DESIGN 가드 subagent 가 이미 텍스트로 완료했으면, 이후 메인(parent 없음)의
        // 델타는 재요약 중복이므로 억제한다.
        if (!msg.parent_tool_use_id && suppressMainText) {
          continue;
        }
        // #578: 메인 델타가 subagent 코드명을 포함하거나 비동기 위임 직후 구간이면 억제.
        if (!msg.parent_tool_use_id) {
          const verdict = classifyMainText(narrationState, msg.delta.text, subagentNames);
          if (verdict.suppress) {
            console.log(`[CLI Agent] [narration-guard] 메인 델타 억제(${verdict.reason}, #578): ${msg.delta.text.slice(0, 80)}`);
            continue;
          }
          // #581: 메인 델타는 라우팅 어휘 치환기를 거친다(경계 보류 시 빈 문자열 → 다음 델타에서 방출).
          const redactedDelta = routingRedactor.push(msg.delta.text);
          if (!redactedDelta) continue;
          userTextEmitted = true;
          yield { type: 'text', content: redactedDelta };
          continue;
        }
        noteSubagentText(narrationState);
        userTextEmitted = true;
        yield { type: 'text', content: msg.delta.text };
        continue;
      }

      // Assistant messages: tool_use or text blocks
      if (msg.type === 'assistant' && msg.message?.content) {
        for (const block of msg.message.content) {
          if (block.type === 'tool_use') {
            const toolName = block.name ?? '';
            // #256: SDK 옵션이 어떤 이유로 무력화돼도(plugin/skill 채널 우회 등) 런타임에서 차단.
            // tool_use 이벤트를 받은 즉시 정책 위반 여부를 검사하고 차단 시 child 를 종료한다.
            // #276: Agent 위임은 정의된 subagent 화이트리스트(loadSubagents 키)로 백스톱.
            const policyDeny = checkToolPolicy(
              toolName,
              (block.input as Record<string, unknown>) ?? undefined,
              Object.keys(subagents),
            );
            if (policyDeny) {
              console.warn(`[CLI Agent] [policy] ${policyDeny} — killing child`);
              yield { type: 'error', message: policyDeny };
              try { child.kill('SIGTERM'); } catch { /* ignore */ }
              return;
            }
            assistantToolCalls.push({
              name: toolName,
              input: (block.input as Record<string, unknown>) ?? {},
            });
            // #429: 메인(parent 없음)이 새 tool_use 를 발행하면 그 시점에 억제를 해제한다 — 새
            // 도구 호출은 메인이 이전 subagent 응답을 재서술하는 게 아니라 실제로 다음 작업을
            // 하고 있다는 구조적 증거다. subagent 내부 tool_use(parent 있음)는 해제 대상이 아니다.
            if (!msg.parent_tool_use_id && suppressMainText) {
              suppressMainText = false;
              console.log('[CLI Agent] [design-guard] 메인 새 tool_use 발행 — 재요약 억제 해제(#429)');
            }
            // #428/#429/#430: 메인이 `Agent`(신규 위임) 또는 `SendMessage`(이전 턴에 pin해둔
            // 비동기 subagent 재개)를 발행하는 순간(subagent_type 불문)을 기록해, 이후 그
            // subagent 의 완료를 parent_tool_use_id(동기 인터리브) 또는 tool_use_id(비동기
            // task_notification, #430) 로 식별할 수 있게 한다.
            //
            // #573 2차 수정(진짜 회귀 원인): `run_in_background:false`(동기 위임)로 등록한
            // id 를 이 화이트리스트(pendingDesignGuardToolUseIds)에도 함께 넣었더니, CLI 가
            // 내부적으로 같은 Agent 호출에 대해 **뒤늦은 `task_notification`(#430)을 중복
            // 발사**하는 경우(라이브 재현으로 확인 — 동기 tool_result 직후 비동기 완료 알림이
            // 거의 즉시 도착)에 `suppressMainText` 가 잘못 세팅됐다. #430 의 전제는 "그 알림이
            // 가리키는 위임은 이미 사용자에게 보이는 채널(인터리브 텍스트)로 내용이 전달됐다"
            // 인데, 동기 위임은 그 채널 자체가 없다(내용은 오직 tool_result → 메인 relay 로만
            // 전달된다 — 위 syncDelegationToolUseIds/pendingSyncAgentResultText 참조). 그
            // 전제가 성립하지 않는 동기 위임 id 를 화이트리스트에 넣지 않으면, 중복
            // task_notification 이 와도 무시되고 메인의 진짜(첫) relay 텍스트가 억제되지 않는다.
            // subagent 내부 tool_use/tool_result 인터리브(parent_tool_use_id 매칭)에도 이
            // 화이트리스트는 필요 없다 — 그건 parent id 비교로만 억제되는 별개 채널이고, 동기
            // 위임의 subagent 는 애초에 parent 태그된 자기 완료 텍스트를 내보내지 않는다(라이브
            // 재현으로 확인 — 내부 tool_result 만 interleave 되고 최종 텍스트는 없음).
            const runInBackground = (block.input as { run_in_background?: unknown } | undefined)
              ?.run_in_background;
            const isSyncDelegation = toolName === 'Agent' && runInBackground === false;
            // #572 2차 수정: 동기 위임의 tool_result 를 아직 받기 전(syncDelegationToolUseIds 에
            // 그 id 가 남아있음)인데 메인이 Agent/SendMessage 가 아닌 다른 tool_use 를 발행하면,
            // 이는 "위임 결과를 기다리지 않고 직접 재조사"하는 이번 회귀 패턴 그 자체다 — 병렬
            // tool_use 배치로 발행돼도(실행이 빠른 도구가 먼저 끝나 스트림 순서가 뒤바뀌어 보여도)
            // 이 발행 "시점" 자체는 병렬/순차 여부와 무관하게 감지된다.
            if (
              !msg.parent_tool_use_id &&
              toolName !== 'Agent' &&
              toolName !== 'SendMessage' &&
              syncDelegationToolUseIds.size > 0
            ) {
              syncDelegationViolated = true;
              console.warn(`[CLI Agent] [sync-guard] 동기 위임 대기 중 다른 tool_use(${toolName}) 발행 감지 — 위임 결과 폐기 위반으로 표시(#572 2차)`);
            }
            if (!msg.parent_tool_use_id && (toolName === 'Agent' || toolName === 'SendMessage') && block.id) {
              if (isSyncDelegation) {
                syncDelegationToolUseIds.add(block.id);
              } else {
                pendingDesignGuardToolUseIds.add(block.id);
              }
            }
            // #573 2차 수정: 메인이 새 tool_use 를 발행했다는 것은 이전 동기 위임 결과에 대한
            // 실제 후속 작업(예: 확인 후 실제 도구 호출)이 진행 중이라는 구조적 증거다 — fallback
            // relay 대상에서 제외한다(강제 relay 가 후속 작업과 순서/내용이 어긋나는 것을 방지).
            // #572 2차: 단, 위반이 이미 표시됐다면 이 리셋을 하지 않는다 — 위임의 실제 tool_result
            // 를 끝까지 강제 relay 대상으로 보존해야 한다.
            if (!msg.parent_tool_use_id && !syncDelegationViolated) {
              pendingSyncAgentResultText = undefined;
            }
            if (!msg.parent_tool_use_id) {
              // #578: 같은 assistant 메시지 안에 [text, tool_use] 가 함께 온 경우의 보류 텍스트 판정 —
              // 위임(Agent/SendMessage)이면 버리고, 다른 도구면 순서 보존을 위해 tool_use 앞에 내보낸다.
              if (pendingMainText !== undefined) {
                const held = pendingMainText;
                pendingMainText = undefined;
                if (toolName === 'Agent' || toolName === 'SendMessage') {
                  narrationState.lastSuppressedMainText = held;
                  console.log(`[CLI Agent] [narration-guard] 위임 직전 메인 텍스트 억제(#578): ${held.slice(0, 80)}`);
                } else {
                  userTextEmitted = true;
                  yield { type: 'text', content: routingRedactor.redact(held) };
                }
              }
              // #578: 비동기 위임 발행 → 위임 직후 구간 시작 / 그 외 도구 → 구간 종료(#429 원칙).
              noteMainToolUse(narrationState, toolName, block.input);
              // #578: 목적 없는 `Bash("echo noop")` 류 호출은 사용자 표시(도구 칩)에서 숨긴다 —
              // 트랜스크립트·정책 검사는 그대로 거치고 SSE 노출만 생략한다(대응 tool_result 도 숨김).
              if (isNoopHostToolCall(toolName, block.input)) {
                if (block.id) narrationState.hiddenNoopToolUseIds.add(block.id);
                console.log(`[CLI Agent] [narration-guard] 메인 no-op 호스트 도구 호출 숨김(#578): ${toolName}(${JSON.stringify(block.input)})`);
                continue;
              }
            }
            yield {
              type: 'tool_use',
              toolName,
              input: block.input,
            };
          } else if (block.type === 'text' && block.text) {
            // #572 2차 수정: 동기 위임 tool_result 대기 중(`pendingSyncAgentResultText` 세팅됨)
            // 이거나 위반이 표시된 경우, 메인(parent 없음)의 텍스트는 트랜스크립트에도 남기지
            // 않는다(누적조차 하지 않음) — 위 stream_event 델타 분기와 동일 이유·근거.
            if (!msg.parent_tool_use_id && (pendingSyncAgentResultText !== undefined || syncDelegationViolated)) {
              continue;
            }
            assistantText += block.text;
            // #410: stream_event 델타를 안 쓰는 CLI 경로(또는 델타 없이 완성된 블록으로만 오는
            // 경우)에서도 동일하게 인증 실패 패턴을 검사해 원문 노출을 막는다.
            if (authFailureDetected) {
              continue;
            }
            if (AUTH_FAILURE_PATTERN.test(block.text)) {
              authFailureDetected = true;
              console.warn(`[CLI Agent] [auth-failure] assistant text=${block.text}`);
              yield { type: 'error', message: AUTH_FAILURE_KOREAN_MESSAGE };
              try {
                child.kill('SIGTERM');
              } catch {
                /* ignore */
              }
              return;
            }
            // #428/#429: subagent(parent_tool_use_id 있음) 텍스트가 위임 목록에 있으면 문구
            // 내용과 무관하게 이후 메인(parent 없음)의 텍스트를 억제한다(subagent_type 불문) —
            // subagent 텍스트 자체는 그대로 emit.
            if (msg.parent_tool_use_id && pendingDesignGuardToolUseIds.has(msg.parent_tool_use_id)) {
              suppressMainText = true;
            }
            // #572 2차 수정(라이브 재현으로 확정): 예전 #573 구현은 "메인이 텍스트를 냈다"는
            // 사실만으로 relay 가 정상적으로 일어났다고 간주해 fallback 을 해제했다. 그러나 실제
            // 재현된 회귀는 정확히 이 지점이다 — 메인이 낸 텍스트가 tool_result 를 **문자 그대로**
            // relay 한 게 아니라 자신의 말로 재구성(표 행 누락·문구 변경)한 것이었는데도 이 리셋
            // 때문에 검증 없이 신뢰돼 그대로 노출됐다(크로스체크 3회 재현 — 라이브 trace 로 확정).
            // 이제 "텍스트가 나갔다"는 사실만으로는 더 이상 지우지 않는다 — 신뢰할 수 있는 유일한
            // 신호는 메인이 새 tool_use 를 발행하는 것뿐이다(위 tool_use 분기 참조).
            if (!msg.parent_tool_use_id && suppressMainText) {
              continue;
            }
            // #578: 메인 텍스트가 subagent 코드명을 포함하거나 비동기 위임 직후 구간이면 억제.
            if (!msg.parent_tool_use_id) {
              const verdict = classifyMainText(narrationState, block.text, subagentNames);
              if (verdict.suppress) {
                console.log(`[CLI Agent] [narration-guard] 메인 텍스트 억제(${verdict.reason}, #578): ${block.text.slice(0, 80)}`);
                continue;
              }
              // #578: 메인 텍스트 블록은 즉시 내보내지 않고 보류 — 다음 이벤트가 위임이면 버린다.
              // 이미 보류 중인 블록이 있으면(한 메시지에 텍스트 블록 2개) 앞 것은 그대로 내보낸다.
              if (pendingMainText !== undefined) {
                userTextEmitted = true;
                yield { type: 'text', content: pendingMainText };
              }
              // #581: 보류 시점에 라우팅 어휘를 치환해 두면 이후 어느 경로로 나가든 동일하게 가려진다.
              pendingMainText = routingRedactor.redact(block.text);
              continue;
            }
            noteSubagentText(narrationState);
            userTextEmitted = true;
            yield { type: 'text', content: block.text };
          }
        }
        continue;
      }

      // Tool results (user messages containing tool_result blocks)
      if (msg.type === 'user' && msg.message?.content) {
        for (const block of msg.message.content) {
          if (block.type === 'tool_result') {
            const resultText =
              typeof block.content === 'string'
                ? block.content
                : Array.isArray(block.content)
                  ? block.content.map((c) => c.text ?? '').join('')
                  : '';
            // 마지막 tool call에 결과 첨부
            if (assistantToolCalls.length > 0) {
              assistantToolCalls[assistantToolCalls.length - 1].result = resultText;
            }
            const isError = Boolean((block as { is_error?: unknown }).is_error);
            // #578: 숨긴 no-op 호출의 결과도 숨긴다. 위임(Agent) 자체가 실패(is_error)했으면 subagent 가
            // 답할 수 없으므로 위임 직후 구간을 끝내 메인이 직접 설명할 수 있게 한다.
            const isHiddenNoop = Boolean(block.tool_use_id && narrationState.hiddenNoopToolUseIds.has(block.tool_use_id));
            if (!msg.parent_tool_use_id) noteMainToolResult(narrationState, isError);
            if (!isHiddenNoop) {
              yield {
                type: 'tool_result',
                toolName: '',
                result: resultText,
              };
            }
            // Tier2: 연속 실패 기록 + 강제중단
            // #573 2차 수정: 이 tool_result 가 동기(run_in_background:false) Agent 위임의
            // 결과라면, subagent 는 자신의 텍스트를 interleave 로 노출할 기회가 없었으므로 이
            // 문자열 자체가 relay 되어야 할 최종 응답이다. block.tool_use_id 로 위임 tool_use
            // 와 매칭한다(#428/#430 의 task_notification 식별과 동일 필드 사용).
            if (
              !msg.parent_tool_use_id &&
              !isError &&
              resultText &&
              block.tool_use_id &&
              syncDelegationToolUseIds.has(block.tool_use_id)
            ) {
              syncDelegationToolUseIds.delete(block.tool_use_id);
              pendingSyncAgentResultText = resultText;
            }
            const tName = assistantToolCalls[assistantToolCalls.length - 1]?.name ?? '';
            const { halt } = haltTracker.record(tName, resultText, isError);
            if (halt) {
              const haltMessage = buildHaltMessage(tName, resultText);
              console.warn(`[CLI Agent] [failure-streak] ${haltMessage} — killing child`);
              yield { type: 'error', message: haltMessage };
              try {
                child.kill('SIGTERM');
              } catch {
                /* ignore */
              }
              return;
            }
          }
        }
        continue;
      }

      // #430: 이전 턴(별도 HTTP 요청)에서 `SendMessage`/`Agent` 로 위임/재개한 비동기 subagent
      // 가 **이번 요청 처리 도중** 뒤늦게 완료되면, 인터리브된 assistant 메시지가 아니라 이
      // top-level `system/task_notification` 으로 알려온다. 그 사이 메인이 같은 작업을 직접
      // 처리하고 이미 완료 보고를 했더라도(예: 위임을 기다리지 않고 도구를 스스로 호출)
      // 뒤늦게 도착한 알림을 메인이 다시 사용자에게 요약해 보고하면 완료 보고가 중복된다.
      // #428/#429 와 동일하게 그 위임 tool_use id 가 pendingDesignGuardToolUseIds 에 있으면
      // (문구 내용 불문) 이후 메인의 재서술을 억제한다.
      if (
        msg.type === 'system' &&
        msg.subtype === 'task_notification' &&
        msg.tool_use_id &&
        pendingDesignGuardToolUseIds.has(msg.tool_use_id)
      ) {
        suppressMainText = true;
        console.log(`[CLI Agent] [design-guard] [${sessionId}] 비동기 subagent 지연 완료 알림 감지 — 메인 재요약 억제(#430) tool_use_id=${msg.tool_use_id}`);
      }

      // Turn boundary — commit current assistant message, start new one
      if (msg.type === 'turn') {
        commitAssistant();
        costTurnCount++;
        // #277: 턴 수가 임계 초과 시 cost_alarm 1회 emit(중단 안 함)
        if (!costAlarmEmitted && costTurnCount >= COST_ALARM_TURNS) {
          costAlarmEmitted = true;
          console.warn(`[CLI Agent] [cost-alarm] ${costTurnCount}턴 — 비싼 작업 진행 중`);
          yield {
            type: 'cost_alarm',
            tokens: 0,
            turns: costTurnCount,
            message: `이 작업이 ${costTurnCount}턴째 진행 중입니다.`,
          };
        }
        continue;
      }

      // Final result — Claude session ID는 result 메시지에서만 캡처 (가장 신뢰)
      if (msg.type === 'result') {
        if (msg.session_id) claudeSessionId = msg.session_id;
        // #336: 캐시 read/creation을 합산해야 실제 컨텍스트 크기가 나온다.
        // input_tokens만 쓰면 캐시 히트 시 4 같은 값이 나와 칩이 상시 0%가 된다.
        const inputTokens = totalInputTokens(msg.usage);
        const outputTokens = msg.usage?.output_tokens ?? 0;
        // #573 2차 수정: 동기 위임 tool_result 를 아무도 relay 하지 않은 채 턴이 끝나는(성공)
        // 경우 — 시스템 프롬프트 준수가 실패해도 사용자에게 완전히 빈 응답이 나가지 않도록
        // 방어적으로 relay 한다(process-message.ts 의 SDK 경로와 동일 패턴). 반드시
        // saveTranscript() 이전에 assistantText 에 반영해야 트랜스크립트에도 남는다.
        // #572 2차 수정: 이 fallback 은 이제 두 경로에서 트리거된다 — (1) 원래 #573 케이스(텍스트
        // 없이 턴 종료), (2) syncDelegationViolated(위임 결과 대기 중 다른 tool_use 발행 감지)로
        // 메인의 모든 후속 텍스트가 위에서 이미 억제된 경우. 두 경로 모두 pendingSyncAgentResultText
        // 가 살아있는 것으로 판별되므로 이 블록은 변경 없이 재사용된다.
        const isBudgetError = (msg.subtype as string) === 'error_max_budget_usd';
        const isOtherError = !isBudgetError && Boolean((msg.subtype as string | undefined)?.startsWith('error'));
        if (!isBudgetError && !isOtherError && pendingSyncAgentResultText) {
          const relayText = stripAgentResultFooter(pendingSyncAgentResultText);
          pendingSyncAgentResultText = undefined;
          if (relayText) {
            console.warn(`[CLI Agent] [design-guard] 동기 Agent 위임 결과 relay(#573/#572 2차, violated=${syncDelegationViolated}): ${relayText.slice(0, 200)}`);
            assistantText += relayText;
            userTextEmitted = true;
            yield { type: 'text', content: relayText };
          }
        }
        // #578: narration 가드가 메인 텍스트를 억제했는데 그 뒤 subagent 도 아무 텍스트를 내지 않아
        // 사용자에게 한 글자도 나가지 않은 채 성공 종료하는 경우 — 완전히 빈 응답(#573 과 같은 critical
        // 회귀)을 막기 위해 마지막으로 억제한 메인 텍스트를 코드명만 가린 채 fallback 으로 내보낸다.
        if (!isBudgetError && !isOtherError && !userTextEmitted && narrationState.lastSuppressedMainText) {
          const fallback = routingRedactor.redact(redactSubagentIdentifiers(narrationState.lastSuppressedMainText, subagentNames));
          narrationState.lastSuppressedMainText = undefined;
          if (fallback) {
            console.warn(`[CLI Agent] [narration-guard] 빈 응답 방지 fallback(#578): ${fallback.slice(0, 200)}`);
            assistantText += fallback;
            userTextEmitted = true;
            yield { type: 'text', content: fallback };
          }
        }
        await saveTranscript();
        if (isBudgetError) {
          // #277: 예산 초과 전용 메시지
          yield {
            type: 'error',
            message: `이 작업이 비용 한도($${MAX_BUDGET_USD})에 도달해 자동 중단되었습니다. 범위를 좁혀 다시 시도해 주세요.`,
            inputTokens,
            outputTokens,
          };
          // `error` 하나만 보면 안 된다(코드리뷰 지적): CLI 는 `error_during_execution` 같은
          // 다른 error_* subtype 도 쓰는데, 그것들이 아래 else 로 빠지면 **빈 응답이 성공으로
          // 보고된다**. 재개 실패가 정확히 그 형태였다. 접두사로 판정해 새 subtype 이 생겨도
          // 조용히 성공으로 새지 않게 한다.
        } else if (isOtherError) {
          // #410: CLI OAuth 토큰 만료/무효 시 msg.result 에 "Not logged in · Please run /login" 류
          // 원문 영문 문구가 그대로 담겨 있다. 검사 없이 넘기면 이 문구가 그대로 채팅 버블에 노출된다.
          // 원인 문자열은 서버 로그에 남기고, 사용자에게는 한국어 안내 메시지로 치환해 내보낸다.
          const rawResult = msg.result ?? 'CLI agent returned an error';
          const isAuthFailure = AUTH_FAILURE_PATTERN.test(rawResult);
          if (isAuthFailure) {
            console.warn(`[CLI Agent] [auth-failure] subtype=${msg.subtype} result=${rawResult}`);
          }
          yield {
            type: 'error',
            message: isAuthFailure ? AUTH_FAILURE_KOREAN_MESSAGE : rawResult,
            inputTokens,
            outputTokens,
          };
        } else {
          yield { type: 'done', inputTokens, outputTokens };
        }
      }
    }
    // #578: 스트림이 `result` 없이 끝난 경우에도 보류 중인 메인 텍스트를 잃지 않는다.
    if (pendingMainText !== undefined) {
      const held = pendingMainText;
      pendingMainText = undefined;
      userTextEmitted = true;
      yield { type: 'text', content: routingRedactor.redact(held) };
    }
  } finally {
    rl.close();
    child.kill('SIGTERM');
    await saveTranscript().catch(() => {});
    if (fileIds?.length) {
      await cleanupChatFiles(chatFilesDir).catch(() => {});
    }
    // #259: system-prompt 임시 파일 정리
    await unlink(systemPromptFile).catch(() => {});

    const stderr = stderrChunks.join('');
    if (stderr) {
      console.error('[CLI Agent] stderr:', stderr);
    }
  }
}

/**
 * subagent 정의를 `.claude/agents/<name>.md` 파일로 직렬화한다.
 *
 * 이유: claude CLI 의 `--agents <json>` 인자에 전체 정의를 인라인 전달하면
 * Linux execve 의 MAX_ARG_STRLEN(보통 128KB) 한계를 초과해 spawn E2BIG 가 발생한다(#260).
 * claude CLI 는 cwd 의 `.claude/agents/*.md` 를 자동 발견하므로 파일로 두면 argv 부담이 없다.
 *
 * 매 호출마다 기존 .md 를 정리하고 다시 쓴다 — 정의가 추가/삭제/변경되어도 일관 유지.
 */
export async function writeSubagentDefinitions(
  workDir: string,
  subagents: Record<string, AgentDefinition>,
): Promise<void> {
  const agentsDir = join(workDir, '.claude', 'agents');
  await mkdir(agentsDir, { recursive: true });

  // 기존 정의 정리(이전 호출에서 남은 stale 파일 제거)
  try {
    const existing = await readdir(agentsDir);
    await Promise.all(
      existing
        .filter((f) => f.endsWith('.md'))
        .map((f) => unlink(join(agentsDir, f)).catch(() => {})),
    );
  } catch {
    // 디렉터리 부재 등은 무시 — mkdir 가 보장
  }

  for (const [name, def] of Object.entries(subagents)) {
    await writeFile(join(agentsDir, `${name}.md`), serializeSubagent(name, def), 'utf-8');
  }
}

/** AgentDefinition 을 frontmatter + prompt 본문 형식의 markdown 으로 직렬화. */
function serializeSubagent(name: string, def: AgentDefinition): string {
  const lines: string[] = ['---', `name: ${name}`, `description: ${yamlDoubleQuoted(def.description)}`];

  if (def.tools && def.tools.length > 0) {
    lines.push('tools:');
    for (const tool of def.tools) {
      lines.push(`  - ${tool}`);
    }
  }
  if (def.model && def.model !== 'inherit') {
    lines.push(`model: ${def.model}`);
  }
  if (typeof def.maxTurns === 'number') {
    lines.push(`maxTurns: ${def.maxTurns}`);
  }

  lines.push('---', '');
  return lines.join('\n') + (def.prompt ?? '');
}

/**
 * 임의 문자열을 YAML double-quoted 스칼라로 안전하게 직렬화한다.
 * 백슬래시·따옴표·줄바꿈만 이스케이프해 description 한 줄 값에 충분하도록 한다.
 */
function yamlDoubleQuoted(value: string): string {
  return `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n')}"`;
}
