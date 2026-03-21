import { query } from '@anthropic-ai/claude-agent-sdk';
import { FireHubApiClient } from '../mcp/api-client.js';
import { createFireHubMcpServer } from '../mcp/firehub-mcp-server.js';
import { SYSTEM_PROMPT } from './system-prompt.js';
import { loadSubagents, buildSubagentGuide } from './subagent-loader.js';
import { DEFAULT_MODEL, DEFAULT_MAX_TURNS, HEARTBEAT_INTERVAL_MS } from '../constants.js';
import { truncate, timestamp } from '../utils.js';
import { processMessage } from './process-message.js';
import { downloadChatFiles, cleanupChatFiles } from './file-downloader.js';

export interface SSEEvent {
  type: 'init' | 'text' | 'tool_use' | 'tool_result' | 'turn' | 'done' | 'error' | 'compaction';
  [key: string]: unknown;
}

export interface AgentOptions {
  message: string;
  sessionId?: string;
  userId: number;
  fileIds?: number[];
  model?: string;
  maxTurns?: number;
  systemPrompt?: string;
  temperature?: number;
  maxTokens?: number;
  apiKey?: string;
  abortSignal?: AbortSignal;
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
  const firehubServer = createFireHubMcpServer(apiClient);

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

  if (apiKey) {
    cleanEnv.ANTHROPIC_API_KEY = apiKey;
  } else if (!cleanEnv.ANTHROPIC_API_KEY) {
    yield { type: 'error' as const, message: 'API key not provided' };
    return;
  }

  // Download attached files and build enhanced prompt
  let enhancedMessage = message;
  const sessionTag = `${userId}-${Date.now()}`;

  if (fileIds?.length) {
    const { files, failed } = await downloadChatFiles(apiClient, fileIds, sessionTag);

    if (failed > 0) {
      console.warn(`${tag()} ${failed}개 파일 다운로드 실패 (만료/삭제됨)`);
    }

    if (files.length > 0) {
      const fileList = files
        .map(
          (f) =>
            `- ${f.originalName} (${f.fileCategory}, ${(f.fileSize / 1024).toFixed(1)}KB): ${f.localPath}`,
        )
        .join('\n');

      enhancedMessage =
        `[첨부 파일]\n${fileList}\n\n` +
        `위 파일들은 Read 도구로 읽을 수 있습니다.\n\n` +
        (message || '첨부된 파일을 분석해주세요.');
    }
  }

  // Load subagents and build dynamic delegation guide
  const subagents = loadSubagents();
  const subagentGuide = buildSubagentGuide(subagents);
  const basePrompt = `${SYSTEM_PROMPT}${subagentGuide}`;

  const queryOptions: Parameters<typeof query>[0] = {
    prompt: enhancedMessage,
    options: {
      model: model || DEFAULT_MODEL,
      systemPrompt: systemPrompt
        ? `${basePrompt}\n\n[사용자 지시사항]\n${systemPrompt}`
        : basePrompt,
      maxTurns,
      ...(temperature !== undefined ? { temperature } : {}),
      ...(maxTokens !== undefined ? { maxTokens } : {}),
      abortController,
      env: cleanEnv,
      mcpServers: {
        firehub: firehubServer,
      },
      allowedTools: [
        'mcp__firehub__*',
        'Read',
        'Write',
        'Edit',
        'Glob',
        'Grep',
        'Bash',
        'WebFetch',
        'WebSearch',
        'Agent',
        'NotebookEdit',
        'TodoWrite',
      ],
      permissionMode: 'bypassPermissions',
      allowDangerouslySkipPermissions: true,
      includePartialMessages: true,
      settingSources: ['user'],
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
        if (event.type === 'tool_use') {
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
      await cleanupChatFiles(sessionTag);
    }
  }
}

export { processMessage } from './process-message.js';
