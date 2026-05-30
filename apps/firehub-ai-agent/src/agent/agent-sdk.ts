import { query } from '@anthropic-ai/claude-agent-sdk';
import type { SDKUserMessage } from '@anthropic-ai/claude-agent-sdk';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import { FireHubApiClient } from '../mcp/api-client.js';
import { createFireHubMcpServer } from '../mcp/firehub-mcp-server.js';
import { SYSTEM_PROMPT, FILE_ATTACHMENT_PROMPT } from './system-prompt.js';
import { loadSubagents, buildSubagentGuide } from './subagent-loader.js';
import { DEFAULT_MODEL, DEFAULT_MAX_TURNS, HEARTBEAT_INTERVAL_MS } from '../constants.js';
import { truncate, timestamp } from '../utils.js';
import { processMessage } from './process-message.js';
import { resolveSystemPrompt } from './prompt-utils.js';
import {
  downloadChatFiles,
  cleanupChatFiles,
  toAttachmentMeta,
  saveSessionAttachments,
  formatAttachmentLine,
} from './file-downloader.js';
import { DISALLOWED_TOOLS, checkToolPolicy } from './tool-policy.js';
import { createTracker, buildHaltMessage } from './failure-streak.js';

import type { SSEEvent } from '../providers/types.js';
export type { SSEEvent } from '../providers/types.js';

// #216: SDK 의 E96()/getExternalMcpMode 는 query() 옵션의 env 가 아닌
// **현재 프로세스의 process.env.ENABLE_TOOL_SEARCH** 를 직접 읽는다 (cli.js dm8).
// 따라서 options.env 에만 설정하면 SDK 내부 모드 결정은 영향받지 않고
// "tst-auto" 기본 모드 그대로 동작 → 임계치 도달 시 ToolSearch 가 다시 발생.
// 모듈 로드 시점에 process.env 를 직접 설정하여 SDK 가 "standard" 모드를
// 선택하도록 강제한다. ENABLE_EXPERIMENTAL_MCP_CLI 도 동일 이유로 차단.
if (!('ENABLE_TOOL_SEARCH' in process.env) || process.env.ENABLE_TOOL_SEARCH !== 'false') {
  process.env.ENABLE_TOOL_SEARCH = 'false';
}
if (!('ENABLE_EXPERIMENTAL_MCP_CLI' in process.env) || process.env.ENABLE_EXPERIMENTAL_MCP_CLI !== 'false') {
  process.env.ENABLE_EXPERIMENTAL_MCP_CLI = 'false';
}

export interface AgentOptions {
  message: string;
  sessionId?: string;
  userId: number;
  fileIds?: number[];
  model?: string;
  maxTurns?: number;
  systemPrompt?: string;
  /** true이면 systemPrompt가 기본 SYSTEM_PROMPT를 완전히 대체한다 */
  overrideSystemPrompt?: boolean;
  temperature?: number;
  maxTokens?: number;
  apiKey?: string;
  cliOauthToken?: string;
  abortSignal?: AbortSignal;
}

/**
 * 세션 사용자 권한 조회 — fail-closed 래퍼.
 *
 * 백엔드 `/auth/me/permissions` 호출에 실패하면 빈 배열(`[]`)로 폴백한다.
 * 권한 필터는 `undefined`를 "전부 허용(permissive)"으로, `[]`를 "요구 권한 있는
 * 파괴 도구 전부 차단(fail-closed)"으로 해석하므로, 실패 경로에서는 반드시 `[]` 를
 * 반환해야 기본 차단이 유지된다.
 *
 * 단위 테스트 가능하도록 executeAgent 바깥에 정의한다.
 */
export async function fetchSessionPermissionsFailClosed(
  apiClient: FireHubApiClient,
  tag: () => string = () => '[Claude]',
): Promise<string[]> {
  try {
    return await apiClient.getSessionPermissions();
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    console.warn(
      `${tag()} [AI Chat] failed to fetch session permissions, defaulting to [] (fail-closed): ${message}`,
    );
    return [];
  }
}

export async function* executeAgent(options: AgentOptions): AsyncGenerator<SSEEvent> {
  const {
    message,
    sessionId,
    userId,
    fileIds,
    model,
    maxTurns = Number(process.env.MAX_TURNS) || DEFAULT_MAX_TURNS,
    systemPrompt,
    overrideSystemPrompt,
    temperature,
    maxTokens,
    apiKey,
    abortSignal,
  } = options;

  const startTime = Date.now();
  const elapsed = () => `${((Date.now() - startTime) / 1000).toFixed(1)}s`;
  const tag = () => `[Claude ${timestamp()} +${elapsed()}]`;

  console.log(`${tag()} ▶ User: "${truncate(message, 500)}"`);

  const apiBaseUrl = process.env.API_BASE_URL || 'http://localhost:8080/api/v1';
  const internalToken = process.env.INTERNAL_SERVICE_TOKEN || '';
  const apiClient = new FireHubApiClient(apiBaseUrl, internalToken, userId);

  // 세션 사용자 권한 조회 + 첨부 파일 다운로드를 병렬 실행한다.
  // 두 작업은 서로 독립적이므로 순차 대기할 필요가 없다.
  // 권한 조회 실패 시 빈 배열로 폴백해 파괴 도구를 차단한다(fail-closed).
  const chatFilesDir = path.join(os.tmpdir(), 'firehub-chat-files', `${userId}-${Date.now()}`);
  const [userPermissions, downloadResult] = await Promise.all([
    fetchSessionPermissionsFailClosed(apiClient, tag),
    fileIds?.length
      ? downloadChatFiles(apiClient, fileIds, chatFilesDir)
      : Promise.resolve(null),
  ]);
  const firehubServer = createFireHubMcpServer(apiClient, { userPermissions });

  const abortController = new AbortController();
  if (abortSignal) {
    abortSignal.addEventListener('abort', () => abortController.abort(), { once: true });
  }

  // Remove env vars that prevent nested Claude Code sessions
  const cleanEnv = { ...process.env };
  delete cleanEnv.CLAUDECODE;
  delete cleanEnv.CLAUDE_CODE_ENTRYPOINT;
  // Auto-compact at ~60% of effective context window (~108K tokens)
  cleanEnv.CLAUDE_AUTOCOMPACT_PCT_OVERRIDE = '60';
  // #216: deferred-tools(ToolSearch 메타 호출) 강제 비활성화.
  //
  // Claude Agent SDK 의 도구 노출 모드는 ENABLE_TOOL_SEARCH 환경변수로 제어된다
  // (SDK 내부 getExternalMcpMode/E96, cli.js):
  //   - 미설정 / "auto" / "auto:N" → "tst-auto" (자동 임계치 도달 시 ToolSearch 활성)
  //   - "true" / "1" / "on"        → "tst"      (항상 ToolSearch 활성)
  //   - "false" / "0" / "off"      → "standard" (ToolSearch 완전 비활성)
  //
  // settingSources: [] 만으로는 호스트의 ~/.claude/settings.json 상속만 차단되므로
  // SDK 기본 "tst-auto" 가 임계치를 넘으면 ToolSearch 가 그대로 발생한다.
  // 서비스 백엔드는 firehub MCP 도구를 시스템 프롬프트로 명시했으므로 deferred
  // 모드가 필요 없다 → "standard" 모드로 강제 고정.
  cleanEnv.ENABLE_TOOL_SEARCH = 'false';
  // 호스트의 ENABLE_EXPERIMENTAL_MCP_CLI 가 주입되어 mcp-cli 모드로 빠지지 않도록
  // 함께 차단한다. ai-agent 백엔드는 in-process MCP 서버(createSdkMcpServer)만 쓴다.
  cleanEnv.ENABLE_EXPERIMENTAL_MCP_CLI = 'false';

  if (apiKey) {
    cleanEnv.ANTHROPIC_API_KEY = apiKey;
  } else if (!cleanEnv.ANTHROPIC_API_KEY) {
    yield { type: 'error' as const, message: 'API key not provided' };
    return;
  }

  // Build enhanced prompt from parallel-downloaded files (see Promise.all above).
  // 이미지 파일은 base64 content block으로 직접 전달하여 Claude가 시각적으로 분석 가능하게 한다.
  // 비이미지 파일은 경로를 텍스트로 안내하여 Read 도구로 접근하도록 한다.
  let enhancedMessage = message;
  let imageContentBlocks: SDKUserMessage['message']['content'] | null = null;

  if (fileIds?.length && downloadResult) {
    const { files, failed } = downloadResult;

    if (failed > 0) {
      console.warn(`${tag()} ${failed}개 파일 다운로드 실패 (만료/삭제됨)`);
    }

    if (files.length > 0) {
      const imageFiles = files.filter((f) => f.mimeType.startsWith('image/'));
      const nonImageFiles = files.filter((f) => !f.mimeType.startsWith('image/'));

      // 이미지 파일: base64로 읽어 image content block 생성
      if (imageFiles.length > 0) {
        const blocks: Extract<SDKUserMessage['message']['content'], Array<unknown>> = [];
        for (const img of imageFiles) {
          const data = await fs.readFile(img.localPath);
          blocks.push({
            type: 'image' as const,
            source: {
              type: 'base64' as const,
              media_type: img.mimeType as 'image/jpeg' | 'image/png' | 'image/gif' | 'image/webp',
              data: data.toString('base64'),
            },
          });
        }

        // 비이미지 파일 안내 + 사용자 메시지를 text block으로 추가
        // fileId 포함은 formatAttachmentLine이 single source of truth (refs #264)
        let textContent = message || '첨부된 파일을 분석해주세요.';
        if (nonImageFiles.length > 0) {
          const fileList = nonImageFiles
            .map((f) => formatAttachmentLine(f, true))
            .join('\n');
          textContent = `[첨부 파일]\n${fileList}\nRead 도구로 읽을 수 있습니다.\n\n` + textContent;
        }
        blocks.push({ type: 'text' as const, text: textContent });
        imageContentBlocks = blocks;
      } else {
        // 비이미지 파일만 있는 경우: 동일 helper 사용
        const fileList = nonImageFiles
          .map((f) => formatAttachmentLine(f, true))
          .join('\n');
        enhancedMessage =
          `[첨부 파일]\n${fileList}\n\n` +
          `위 파일들은 Read 도구로 읽을 수 있습니다.\n\n` +
          (message || '첨부된 파일을 분석해주세요.');
      }
    }
  }

  // Load subagents and build dynamic delegation guide
  const subagents = loadSubagents();
  const subagentGuide = buildSubagentGuide(subagents);
  // #260: 파일 첨부 가이드는 fileIds가 있는 요청에만 동적 첨부 (cold cache_creation 945 토큰 절감)
  const fileAttachmentGuide = fileIds?.length ? FILE_ATTACHMENT_PROMPT : '';
  const basePrompt = `${SYSTEM_PROMPT}${subagentGuide}${fileAttachmentGuide}`;

  const effectiveSystemPrompt = resolveSystemPrompt(basePrompt, systemPrompt, overrideSystemPrompt);

  // 이미지가 있으면 AsyncIterable<SDKUserMessage>로, 없으면 string으로 전달
  const prompt: Parameters<typeof query>[0]['prompt'] = imageContentBlocks
    ? (async function* () {
        yield {
          type: 'user' as const,
          message: { role: 'user' as const, content: imageContentBlocks! },
          parent_tool_use_id: null,
          session_id: '',
        } satisfies SDKUserMessage;
      })()
    : enhancedMessage;

  const queryOptions: Parameters<typeof query>[0] = {
    prompt,
    options: {
      model: model || DEFAULT_MODEL,
      systemPrompt: effectiveSystemPrompt,
      maxTurns,
      ...(temperature !== undefined ? { temperature } : {}),
      ...(maxTokens !== undefined ? { maxTokens } : {}),
      abortController,
      env: cleanEnv,
      mcpServers: {
        firehub: firehubServer,
      },
      // #266: allow-by-default — allowedTools 는 양수 화이트리스트 효과가 강해
      // (그 외 host 도구 전체 차단) 새 도구 등장 시마다 회귀를 만들어 의도적으로 미전달.
      // 메인은 라우팅/위임 역할이고, 위험한 도구는 아래 disallowedTools 로 명시 차단한다.
      //
      // #216 / #256: 메타-검색·skill·task·host 파일 변조 도구만 명시 차단.
      // SDK 의 isToolSearchEnabled 는 disallowedTools 에 ToolSearch 가 있으면
      // 곧바로 비활성으로 폴백한다 (cli.js XOq 체크 — "may have been disallowed via
      // disallowedTools"). 정책은 ./tool-policy.ts 가 single source of truth — CLI 프로바이더도 동일 리스트 적용.
      disallowedTools: [...DISALLOWED_TOOLS],
      permissionMode: 'bypassPermissions',
      allowDangerouslySkipPermissions: true,
      includePartialMessages: true,
      // settingSources: 호스트 사용자의 ~/.claude/settings.json 상속을 차단한다.
      // 'user'로 설정하면 개발자/운영자의 deferred-tools, enableAllProjectMcpServers
      // 등이 SDK에 주입되어 매 신규 툴 호출마다 ToolSearch 메타-툴이 한 턴 더 추가됨
      // (#216). 서비스 백엔드는 시스템 프롬프트와 MCP 서버를 코드로 명시하므로
      // 사용자 설정을 상속할 이유가 없다. 빈 배열로 명시적 차단.
      settingSources: [],
      ...(Object.keys(subagents).length > 0 && { agents: subagents }),
      ...(sessionId ? { resume: sessionId } : {}),
    },
  };

  console.log(
    `${tag()} SDK query starting (model=${model || DEFAULT_MODEL}, maxTurns=${maxTurns})`,
  );
  const agentQuery = query(queryOptions);
  let doneEmitted = false;
  let hadToolResult = false;
  let turnNumber = 1;
  let toolCallStart = 0;
  let lastToolName = '';
  let firstTextReceived = false;
  let hasStreamedText = false;
  let lastTurnContextTokens = 0;
  // Tier2 강제중단용 연속 실패 트래커 (도구 이름은 lastToolName 재사용)
  const haltTracker = createTracker();

  // Heartbeat: log periodically when waiting for Claude API response
  let waitTimer: ReturnType<typeof setInterval> | null = null;
  let waitStart = 0;
  const startWaitHeartbeat = () => {
    stopWaitHeartbeat();
    waitStart = Date.now();
    waitTimer = setInterval(() => {
      const waitSec = Math.round((Date.now() - waitStart) / 1000);
      console.log(`${tag()} ⏳ Waiting for Claude API response... (${waitSec}s)`);
    }, HEARTBEAT_INTERVAL_MS);
  };
  const stopWaitHeartbeat = () => {
    if (waitTimer) {
      clearInterval(waitTimer);
      waitTimer = null;
    }
  };

  try {
    for await (const msg of agentQuery) {
      stopWaitHeartbeat();

      // Extract per-turn context size from message_start stream events
      if (msg.type === 'stream_event') {
        const evt = msg.event as { type: string; message?: { usage?: Record<string, number> } };
        if (evt.type === 'message_start' && evt.message?.usage) {
          const u = evt.message.usage;
          lastTurnContextTokens =
            (u.input_tokens ?? 0) +
            (u.cache_read_input_tokens ?? 0) +
            (u.cache_creation_input_tokens ?? 0);
        }
      }

      const events = processMessage(msg, tag, hasStreamedText);
      for (const event of events) {
        // Tier2 halt 판정 결과를 이벤트 처리 후 사용하기 위한 플래그
        let haltNow = false;
        let haltMessage = '';
        if (event.type === 'tool_use') {
          // #256: 옵션이 어떤 이유로 무력화돼도(plugin 채널 우회 등) 런타임에서 차단.
          // 차단 시 abort 신호로 SDK 스트림을 즉시 종료시킨다.
          // #276: Agent 위임은 정의된 subagent 화이트리스트(loadSubagents 키)로 백스톱.
          const policyDeny = checkToolPolicy(
            String(event.toolName || ''),
            event.input as Record<string, unknown> | undefined,
            Object.keys(subagents),
          );
          if (policyDeny) {
            console.warn(`${tag()} [policy] ${policyDeny} — aborting stream`);
            yield { type: 'error', message: policyDeny };
            doneEmitted = true;
            try { abortController.abort(); } catch { /* ignore */ }
            return;
          }
          toolCallStart = Date.now();
          lastToolName = String(event.toolName || '');
        }
        if (event.type === 'tool_result') {
          hadToolResult = true;
          if (toolCallStart) {
            const toolDuration = ((Date.now() - toolCallStart) / 1000).toFixed(1);
            console.log(`${tag()} Tool ${lastToolName} completed in ${toolDuration}s`);
            toolCallStart = 0;
          }
          // 연속 실패 기록 + halt 판정 (도구 이름은 lastToolName)
          const resultText = String((event as { result?: unknown }).result ?? '');
          const { halt } = haltTracker.record(
            lastToolName,
            resultText,
            Boolean((event as { isError?: unknown }).isError),
          );
          if (halt) {
            haltNow = true;
            haltMessage = buildHaltMessage(lastToolName, resultText);
          }
        }
        // init 이벤트에서 sessionId 확보 → 첨부 파일 메타데이터 사이드카 저장
        if (event.type === 'init' && event.sessionId && downloadResult?.files.length) {
          saveSessionAttachments(
            String(event.sessionId),
            toAttachmentMeta(downloadResult.files),
          ).catch((err) => console.warn(`${tag()} 첨부 메타 저장 실패:`, err));
        }
        if (event.type === 'text' && !firstTextReceived) {
          firstTextReceived = true;
          hasStreamedText = true;
          console.log(`${tag()} First text token received`);
        }
        if (event.type === 'done' || event.type === 'error') {
          doneEmitted = true;
          // Override cumulative inputTokens with last turn's actual context size
          if (lastTurnContextTokens > 0) {
            console.log(`${tag()} Context: ${lastTurnContextTokens} tokens (cumulative was ${event.inputTokens})`);
            event.inputTokens = lastTurnContextTokens;
          }
          console.log(`${tag()} Total ${turnNumber} turn(s)`);
        }
        yield event;
        // Tier2: 실패 결과를 프론트에 전달한 뒤 강제중단
        if (haltNow) {
          console.warn(`${tag()} [failure-streak] ${haltMessage} — aborting stream`);
          yield { type: 'error', message: haltMessage };
          doneEmitted = true;
          try {
            abortController.abort();
          } catch {
            /* ignore */
          }
          return;
        }
      }
      // Emit turn event right after processing all tool_results in a user message
      // so the frontend can commit the current turn and show ThinkingIndicator
      // during the (potentially long) Claude API wait
      if (hadToolResult && msg.type === 'user') {
        hadToolResult = false;
        turnNumber++;
        console.log(`${tag()} ── Turn ${turnNumber} waiting ──`);
        yield { type: 'turn' };
      }
      startWaitHeartbeat();
    }
    stopWaitHeartbeat();
  } catch (error: unknown) {
    stopWaitHeartbeat();
    if (error instanceof Error && error.name === 'AbortError') {
      console.log(`${tag()} Aborted`);
      return;
    }
    // Don't emit error if done/error was already sent (e.g. process exit after completion)
    if (doneEmitted) {
      return;
    }
    const errorMessage = error instanceof Error ? error.message : String(error);
    console.error(`${tag()} Error: ${errorMessage}`);
    yield { type: 'error', message: errorMessage };
  } finally {
    if (fileIds?.length) {
      await cleanupChatFiles(chatFilesDir);
    }
  }
}

export { processMessage } from './process-message.js';
