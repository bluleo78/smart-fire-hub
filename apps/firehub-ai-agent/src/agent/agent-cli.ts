/**
 * CLI agent executor using `claude -p` (Claude Code CLI).
 *
 * Spawns the Claude Code CLI process with a temporary MCP config that points
 * to the stdio MCP server, then parses stream-json output into SSEEvents
 * matching the same interface as executeAgent() in agent-sdk.ts.
 */
import { spawn } from 'child_process';
import { writeFile, unlink } from 'fs/promises';
import { tmpdir } from 'os';
import { join } from 'path';
import { createInterface } from 'readline';
import { randomUUID } from 'crypto';
import { fileURLToPath } from 'url';
import { dirname } from 'path';
import { SYSTEM_PROMPT } from './system-prompt.js';
import type { SSEEvent, AgentOptions } from './agent-sdk.js';
import { DEFAULT_MODEL } from '../constants.js';

/** Absolute path to the compiled stdio server entry point */
function getStdioServerPath(): string {
  const __filename = fileURLToPath(import.meta.url);
  const __dirname = dirname(__filename);
  // In dev (tsx): src/agent/ → src/mcp/stdio-server.ts (resolved via tsx)
  // In prod (node dist/): dist/agent/ → dist/mcp/stdio-server.js
  return join(__dirname, '..', 'mcp', 'stdio-server.js');
}

function buildMcpConfig(userId: number, apiBaseUrl: string, internalToken: string): object {
  return {
    mcpServers: {
      firehub: {
        command: 'node',
        args: [getStdioServerPath()],
        env: {
          API_BASE_URL: apiBaseUrl,
          INTERNAL_SERVICE_TOKEN: internalToken,
          USER_ID: String(userId),
        },
      },
    },
  };
}

interface StreamJsonMessage {
  type: string;
  message?: {
    content?: Array<{
      type: string;
      name?: string;
      input?: unknown;
      tool_use_id?: string;
      content?: Array<{ type: string; text?: string }> | string;
      text?: string;
    }>;
  };
  result?: string;
  usage?: { input_tokens?: number; output_tokens?: number };
  delta?: { type?: string; text?: string };
  subtype?: string;
  cost_usd?: number;
}

interface CliAgentOptions extends AgentOptions {
  /** true = 구독 인증 사용 (ANTHROPIC_API_KEY 제거), false = API 키 사용 */
  useSubscription?: boolean;
  /** DB에서 복호화된 CLI OAuth 토큰 (구독 모드에서 CLAUDE_CODE_OAUTH_TOKEN으로 설정) */
  cliOauthToken?: string;
}

export async function* executeCliAgent(options: CliAgentOptions): AsyncGenerator<SSEEvent> {
  const {
    message,
    userId,
    model,
    systemPrompt,
    apiKey,
    cliOauthToken,
    abortSignal,
    useSubscription = true,
  } = options;

  const apiBaseUrl = process.env.API_BASE_URL ?? 'http://localhost:8080/api/v1';
  const internalToken = process.env.INTERNAL_SERVICE_TOKEN ?? '';

  const sessionId = `cli-${randomUUID()}`;
  yield { type: 'init', sessionId };

  const mcpConfigPath = join(tmpdir(), `firehub-mcp-${userId}-${Date.now()}.json`);
  await writeFile(mcpConfigPath, JSON.stringify(buildMcpConfig(userId, apiBaseUrl, internalToken), null, 2));

  const effectiveModel = model ?? DEFAULT_MODEL;
  const effectiveSystemPrompt = systemPrompt
    ? `${SYSTEM_PROMPT}\n\n[사용자 지시사항]\n${systemPrompt}`
    : SYSTEM_PROMPT;

  const cliArgs = [
    '-p', message || '',
    '--output-format', 'stream-json',
    '--verbose',
    '--include-partial-messages',
    '--mcp-config', mcpConfigPath,
    '--system-prompt', effectiveSystemPrompt,
    '--permission-mode', 'bypassPermissions',
    '--no-session-persistence',
    '--model', effectiveModel,
  ];

  const childEnv = { ...process.env };
  if (useSubscription) {
    // 구독 모드: ANTHROPIC_API_KEY를 제거하여 Claude Pro/Max 구독 인증 사용
    delete childEnv.ANTHROPIC_API_KEY;
    if (cliOauthToken) {
      childEnv.CLAUDE_CODE_OAUTH_TOKEN = cliOauthToken;
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

      // Stream text deltas
      if (msg.type === 'stream_event' && msg.delta?.type === 'text_delta' && msg.delta.text) {
        yield { type: 'text', content: msg.delta.text };
        continue;
      }

      // Assistant messages: tool_use or text blocks
      if (msg.type === 'assistant' && msg.message?.content) {
        for (const block of msg.message.content) {
          if (block.type === 'tool_use') {
            yield {
              type: 'tool_use',
              toolName: block.name ?? '',
              input: block.input,
            };
          } else if (block.type === 'text' && block.text) {
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
            yield {
              type: 'tool_result',
              toolName: '',
              result: resultText,
            };
          }
        }
        continue;
      }

      // Final result
      if (msg.type === 'result') {
        const inputTokens = msg.usage?.input_tokens ?? 0;
        const outputTokens = msg.usage?.output_tokens ?? 0;
        if (msg.subtype === 'error') {
          yield {
            type: 'error',
            message: msg.result ?? 'CLI agent returned an error',
            inputTokens,
            outputTokens,
          };
        } else {
          yield { type: 'done', inputTokens, outputTokens };
        }
      }
    }
  } finally {
    rl.close();
    child.kill('SIGTERM');
    await unlink(mcpConfigPath).catch(() => {
      // Ignore cleanup errors
    });

    // If the process exited with error and we collected stderr, emit it
    const stderr = stderrChunks.join('');
    if (stderr) {
      console.error('[CLI Agent] stderr:', stderr);
    }
  }
}
