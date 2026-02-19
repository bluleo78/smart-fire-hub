import { query } from '@anthropic-ai/claude-agent-sdk';
import type { SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { FireHubApiClient } from '../mcp/api-client.js';
import { createFireHubMcpServer } from '../mcp/firehub-mcp-server.js';
import { SYSTEM_PROMPT } from './system-prompt.js';

export interface SSEEvent {
  type: 'init' | 'text' | 'tool_use' | 'tool_result' | 'done' | 'error';
  [key: string]: unknown;
}

export interface AgentOptions {
  message: string;
  sessionId?: string;
  userId: number;
  maxTurns?: number;
  abortSignal?: AbortSignal;
}

function truncate(text: string, maxLen = 200): string {
  if (text.length <= maxLen) return text;
  return text.slice(0, maxLen) + '...';
}

export async function* executeAgent(options: AgentOptions): AsyncGenerator<SSEEvent> {
  const {
    message,
    sessionId,
    userId,
    maxTurns = Number(process.env.MAX_TURNS) || 10,
    abortSignal,
  } = options;

  console.log(`[Claude] ▶ User: "${truncate(message, 500)}"`);

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
      model: 'claude-sonnet-4-6',
      systemPrompt: SYSTEM_PROMPT,
      maxTurns,
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

  const agentQuery = query(queryOptions);
  let doneEmitted = false;

  try {
    for await (const msg of agentQuery) {
      const events = processMessage(msg);
      for (const event of events) {
        if (event.type === 'done' || event.type === 'error') {
          doneEmitted = true;
        }
        yield event;
      }
    }
  } catch (error: unknown) {
    if (error instanceof Error && error.name === 'AbortError') {
      return;
    }
    // Don't emit error if done/error was already sent (e.g. process exit after completion)
    if (doneEmitted) {
      return;
    }
    const errorMessage = error instanceof Error ? error.message : String(error);
    yield { type: 'error', message: errorMessage };
  }
}

function processMessage(msg: SDKMessage): SSEEvent[] {
  const events: SSEEvent[] = [];

  switch (msg.type) {
    case 'system': {
      if (msg.subtype === 'init') {
        console.log(`[Claude] ● Session init: ${msg.session_id}`);
        events.push({
          type: 'init',
          sessionId: msg.session_id,
        });
      }
      break;
    }

    case 'assistant': {
      // Text is already streamed via stream_event (text_delta), so skip text blocks here
      // to avoid content duplication. Only emit tool_use events.
      if (msg.message?.content) {
        for (const block of msg.message.content) {
          if (block.type === 'text' && 'text' in block) {
            console.log(`[Claude] ◀ Text: "${truncate(String(block.text))}"`);
          } else if (block.type === 'tool_use' && 'name' in block) {
            const input = 'input' in block ? block.input : {};
            console.log(`[Claude] ◀ Tool call: ${block.name}(${truncate(JSON.stringify(input))})`);
            events.push({
              type: 'tool_use',
              toolName: block.name,
              input,
            });
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
            console.log(`[Claude] ◀ Tool result [${toolId}]: ${truncate(resultStr ?? '(empty)')}`);
            events.push({
              type: 'tool_result',
              toolName: toolId,
              result: resultStr,
            });
          }
        }
      }
      break;
    }

    case 'result': {
      if (msg.subtype === 'success') {
        console.log(`[Claude] ✓ Session completed: ${msg.session_id}`);
        events.push({
          type: 'done',
          sessionId: msg.session_id,
        });
      } else {
        const errorMsg = 'errors' in msg ? msg.errors.join('; ') : 'Agent execution failed';
        console.error(`[Claude] ✗ Session failed: ${errorMsg}`);
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
      }
      break;
    }
  }

  return events;
}
