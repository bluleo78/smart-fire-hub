import { query } from '@anthropic-ai/claude-agent-sdk';
import type { SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { FireHubApiClient } from '../mcp/api-client.js';
import { createFireHubMcpServer } from '../mcp/firehub-mcp-server.js';
import { SYSTEM_PROMPT } from './system-prompt.js';

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

function truncate(text: string, maxLen = 200): string {
  if (text.length <= maxLen) return text;
  return text.slice(0, maxLen) + '...';
}

function timestamp(): string {
  return new Date().toLocaleTimeString('ko-KR', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export async function* executeAgent(options: AgentOptions): AsyncGenerator<SSEEvent> {
  const {
    message,
    sessionId,
    userId,
    model,
    maxTurns = Number(process.env.MAX_TURNS) || 10,
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
      model: model || 'claude-sonnet-4-6',
      systemPrompt: systemPrompt || SYSTEM_PROMPT,
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

  console.log(`${tag()} SDK query starting (model=${model || 'claude-sonnet-4-6'}, maxTurns=${maxTurns})`);
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
    }, 10_000);
  };
  const stopWaitHeartbeat = () => {
    if (waitTimer) { clearInterval(waitTimer); waitTimer = null; }
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

function processMessage(msg: SDKMessage, tag: () => string, hasStreamedText: boolean): SSEEvent[] {
  const events: SSEEvent[] = [];

  switch (msg.type) {
    case 'system': {
      if (msg.subtype === 'init') {
        console.log(`${tag()} ● Session init: ${msg.session_id}`);
        events.push({
          type: 'init',
          sessionId: msg.session_id,
        });
      } else {
        console.log(`${tag()} ● System: ${msg.subtype}`);
      }
      break;
    }

    case 'assistant': {
      // Normally text is streamed via stream_event (text_delta), so we skip text blocks
      // here to avoid duplication. However, in error cases (e.g. credit balance too low),
      // the SDK may return text directly in the assistant message without streaming.
      // In that case, emit the text so the frontend can display it.
      if (msg.message?.content) {
        for (const block of msg.message.content) {
          if (block.type === 'text' && 'text' in block) {
            console.log(`${tag()} ◀ Text: "${truncate(String(block.text))}"`);
            if (!hasStreamedText) {
              events.push({ type: 'text', content: block.text });
            }
          } else if (block.type === 'tool_use' && 'name' in block) {
            const input = 'input' in block ? block.input : {};
            console.log(`${tag()} ◀ Tool call: ${block.name}(${truncate(JSON.stringify(input))})`);
            events.push({
              type: 'tool_use',
              toolName: block.name,
              input,
            });
          } else {
            console.log(`${tag()} ◀ Assistant block: ${block.type}`);
          }
        }
      }
      break;
    }

    case 'user': {
      if (msg.message?.content && Array.isArray(msg.message.content)) {
        for (const block of msg.message.content) {
          if (typeof block === 'object' && 'type' in block && block.type === 'tool_result') {
            const rawContent = 'content' in block ? block.content : undefined;
            let resultStr: string | undefined;
            if (typeof rawContent === 'string') {
              resultStr = rawContent;
            } else if (Array.isArray(rawContent)) {
              resultStr = rawContent
                .map((c: unknown) => (typeof c === 'object' && c !== null && 'text' in c ? (c as { text: string }).text : JSON.stringify(c)))
                .join('\n');
            } else if (rawContent !== undefined) {
              resultStr = JSON.stringify(rawContent);
            }
            const toolId = 'tool_use_id' in block ? String(block.tool_use_id) : 'unknown';
            console.log(`${tag()} ◀ Tool result [${toolId}]: ${truncate(resultStr ?? '(empty)')}`);
            events.push({
              type: 'tool_result',
              toolName: toolId,
              result: resultStr,
            });
          } else {
            const blockType = typeof block === 'object' && block !== null && 'type' in block ? (block as { type: string }).type : 'unknown';
            console.log(`${tag()} ◀ User block: ${blockType}`);
          }
        }
      }
      break;
    }

    case 'result': {
      // Log token usage from result message
      const resultMsg = msg as { usage?: Record<string, number>; modelUsage?: Record<string, { inputTokens: number; outputTokens: number; cacheReadInputTokens: number; cacheCreationInputTokens: number }> };
      let totalInputTokens = 0;
      if (resultMsg.usage) {
        const u = resultMsg.usage;
        totalInputTokens = (u.input_tokens ?? 0) + (u.cache_read_input_tokens ?? 0) + (u.cache_creation_input_tokens ?? 0);
        console.log(`${tag()} 📊 Total tokens — input: ${u.input_tokens ?? 0}, output: ${u.output_tokens ?? 0}, cache_read: ${u.cache_read_input_tokens ?? 0}, cache_create: ${u.cache_creation_input_tokens ?? 0} (total_input: ${totalInputTokens})`);
      }
      if (resultMsg.modelUsage) {
        for (const [modelName, u] of Object.entries(resultMsg.modelUsage)) {
          console.log(`${tag()} 📊 Model ${modelName} — input: ${u.inputTokens}, output: ${u.outputTokens}, cache_read: ${u.cacheReadInputTokens}, cache_create: ${u.cacheCreationInputTokens}`);
        }
      }
      if (msg.subtype === 'success') {
        console.log(`${tag()} ✓ Session completed: ${msg.session_id}`);
        events.push({
          type: 'done',
          sessionId: msg.session_id,
          inputTokens: totalInputTokens,
        });
      } else {
        const errorMsg = 'errors' in msg ? msg.errors.join('; ') : 'Agent execution failed';
        console.error(`${tag()} ✗ Session failed: ${errorMsg}`);
        events.push({
          type: 'error',
          message: errorMsg,
        });
      }
      break;
    }

    case 'stream_event': {
      const event = msg.event;
      if (event.type === 'content_block_delta' && 'delta' in event) {
        const delta = event.delta;
        if (delta.type === 'text_delta' && 'text' in delta) {
          events.push({
            type: 'text',
            content: delta.text,
          });
        }
      } else if (event.type === 'message_delta') {
        // Log per-turn output tokens from message_delta
        const delta = event as { type: string; usage?: { output_tokens?: number } };
        if (delta.usage?.output_tokens) {
          console.log(`${tag()} ⚡ Stream: message_delta (output_tokens: ${delta.usage.output_tokens})`);
        } else {
          console.log(`${tag()} ⚡ Stream: message_delta`);
        }
      } else if (event.type !== 'content_block_delta') {
        // Log non-delta stream events (message_start, content_block_start, content_block_stop, message_stop)
        console.log(`${tag()} ⚡ Stream: ${event.type}`);
      }
      break;
    }

    default: {
      console.log(`${tag()} ❓ Unknown SDK message: ${(msg as { type: string }).type}`);
      break;
    }
  }

  return events;
}
