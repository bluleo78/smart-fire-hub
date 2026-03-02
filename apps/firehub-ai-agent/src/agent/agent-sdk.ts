import { query } from '@anthropic-ai/claude-agent-sdk';
import { FireHubApiClient } from '../mcp/api-client.js';
import { createFireHubMcpServer } from '../mcp/firehub-mcp-server.js';
import { SYSTEM_PROMPT } from './system-prompt.js';
import { DEFAULT_MODEL, DEFAULT_MAX_TURNS, HEARTBEAT_INTERVAL_MS } from '../constants.js';
import { truncate, timestamp } from '../utils.js';
import { processMessage } from './process-message.js';

export interface SSEEvent {
  type: 'init' | 'text' | 'tool_use' | 'tool_result' | 'turn' | 'done' | 'error';
  [key: string]: unknown;
}

export interface AgentOptions {
  message: string;
  sessionId?: string;
  userId: number;
  model?: string;
  maxTurns?: number;
  systemPrompt?: string;
  temperature?: number;
  maxTokens?: number;
  abortSignal?: AbortSignal;
}

export async function* executeAgent(options: AgentOptions): AsyncGenerator<SSEEvent> {
  const {
    message,
    sessionId,
    userId,
    model,
    maxTurns = Number(process.env.MAX_TURNS) || DEFAULT_MAX_TURNS,
    systemPrompt,
    temperature,
    maxTokens,
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

  const queryOptions: Parameters<typeof query>[0] = {
    prompt: message,
    options: {
      model: model || DEFAULT_MODEL,
      systemPrompt: systemPrompt
        ? `${SYSTEM_PROMPT}\n\n[사용자 지시사항]\n${systemPrompt}`
        : SYSTEM_PROMPT,
      maxTurns,
      ...(temperature !== undefined ? { temperature } : {}),
      ...(maxTokens !== undefined ? { maxTokens } : {}),
      abortController,
      env: cleanEnv,
      mcpServers: {
        firehub: firehubServer,
      },
      allowedTools: ['mcp__firehub__*'],
      permissionMode: 'bypassPermissions',
      allowDangerouslySkipPermissions: true,
      includePartialMessages: true,
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
  }
}

export { processMessage } from './process-message.js';
