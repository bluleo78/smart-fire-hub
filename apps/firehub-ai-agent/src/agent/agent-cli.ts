/**
 * CLI agent executor using `claude -p` (Claude Code CLI).
 *
 * Spawns the Claude Code CLI process with a temporary MCP config that points
 * to the stdio MCP server, then parses stream-json output into SSEEvents
 * matching the same interface as executeAgent() in agent-sdk.ts.
 */
import { spawn } from 'child_process';
import { existsSync } from 'fs';
import { mkdir, readFile, writeFile } from 'fs/promises';
import { homedir } from 'os';
import { join } from 'path';
import { createInterface } from 'readline';
import { randomUUID } from 'crypto';
import { fileURLToPath } from 'url';
import { dirname } from 'path';
import { SYSTEM_PROMPT } from './system-prompt.js';
import type { SSEEvent, AgentOptions } from './agent-sdk.js';
import type { HistoryMessage, HistoryToolCall } from './transcript-reader.js';
import { DEFAULT_MODEL } from '../constants.js';
import { FireHubApiClient } from '../mcp/api-client.js';
import { downloadChatFiles, cleanupChatFiles } from './file-downloader.js';

/** CLI 트랜스크립트 파일 형식 */
export interface CliTranscript {
  claudeSessionId?: string;
  messages: HistoryMessage[];
}

const SAFE_SESSION_ID = /^[a-zA-Z0-9_-]+$/;

export function getTranscriptDir(): string {
  return join(homedir(), '.firehub', 'transcripts');
}

export function getTranscriptPath(sessionId: string): string {
  if (!SAFE_SESSION_ID.test(sessionId)) {
    throw new Error(`Invalid sessionId: ${sessionId}`);
  }
  return join(getTranscriptDir(), `${sessionId}.json`);
}

/** Resolve MCP stdio server command + args for the current runtime.
 *  - Production (dist/): `node dist/mcp/stdio-server.js`
 *  - Development (src/): `tsx src/mcp/stdio-server.ts` (node can't run .ts)
 */
function getStdioServerCommand(): { command: string; args: string[] } {
  const __filename = fileURLToPath(import.meta.url);
  const __dirname = dirname(__filename);
  const serverJs = join(__dirname, '..', 'mcp', 'stdio-server.js');

  if (existsSync(serverJs)) {
    // Production: compiled .js exists in dist/
    return { command: 'node', args: [serverJs] };
  }

  // Dev: .ts only — use tsx from project node_modules
  const serverTs = join(__dirname, '..', 'mcp', 'stdio-server.ts');
  const tsxBin = join(__dirname, '..', '..', 'node_modules', '.bin', 'tsx');
  return { command: tsxBin, args: [serverTs] };
}

function buildMcpConfig(userId: number, apiBaseUrl: string, internalToken: string): object {
  const { command, args } = getStdioServerCommand();
  return {
    mcpServers: {
      firehub: {
        command,
        args,
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
  session_id?: string;
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
    fileIds,
    model,
    systemPrompt,
    apiKey,
    cliOauthToken,
    abortSignal,
    useSubscription = true,
  } = options;

  const apiBaseUrl = process.env.API_BASE_URL ?? 'http://localhost:8080/api/v1';
  const internalToken = process.env.INTERNAL_SERVICE_TOKEN ?? '';

  // 세션 재개 또는 새 세션 생성
  const isResume = !!options.sessionId;
  const sessionId = options.sessionId ?? `cli-${randomUUID()}`;
  yield { type: 'init', sessionId };

  // 첨부 파일 다운로드 및 메시지 변환
  let enhancedMessage = message || '';
  const sessionTag = `cli-${userId}-${Date.now()}`;

  if (fileIds?.length) {
    const apiClient = new FireHubApiClient(apiBaseUrl, internalToken, userId);
    const { files, failed } = await downloadChatFiles(apiClient, fileIds, sessionTag);

    if (failed > 0) {
      console.warn(`[CLI Agent] ${failed}개 파일 다운로드 실패 (만료/삭제됨)`);
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

  const transcriptPath = getTranscriptPath(sessionId);

  let saved: CliTranscript = { messages: [] };
  if (isResume) {
    try {
      saved = JSON.parse(await readFile(transcriptPath, 'utf-8')) as CliTranscript;
    } catch { /* 파일 없으면 새로 시작 */ }
  }

  const transcript = saved.messages;
  let claudeSessionId = saved.claudeSessionId;
  const now = () => new Date().toISOString();
  let assistantText = '';
  let assistantToolCalls: HistoryToolCall[] = [];

  // 사용자 메시지 기록
  transcript.push({ id: `user-${Date.now()}`, role: 'user', content: enhancedMessage, timestamp: now() });

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
    await mkdir(getTranscriptDir(), { recursive: true });
    await writeFile(transcriptPath, JSON.stringify({ claudeSessionId, messages: transcript }));
  };

  // 사용자별 격리된 작업 디렉토리 (세션 간 파일 유지, 소스 코드 접근 차단)
  const userWorkDir = join(homedir(), '.firehub', 'workspaces', String(userId));
  await mkdir(userWorkDir, { recursive: true });

  // 환경변수(API_BASE_URL, INTERNAL_SERVICE_TOKEN) 변경 시에도 최신 상태 유지
  const mcpConfigPath = join(userWorkDir, 'mcp.json');
  await writeFile(mcpConfigPath, JSON.stringify(buildMcpConfig(userId, apiBaseUrl, internalToken), null, 2));

  const effectiveModel = model ?? DEFAULT_MODEL;
  const effectiveSystemPrompt = systemPrompt
    ? `${SYSTEM_PROMPT}\n\n[사용자 지시사항]\n${systemPrompt}`
    : SYSTEM_PROMPT;

  const cliArgs = [
    '-p', enhancedMessage,
    '--output-format', 'stream-json',
    '--verbose',
    '--include-partial-messages',
    '--mcp-config', mcpConfigPath,
    '--strict-mcp-config',
    '--system-prompt', effectiveSystemPrompt,
    '--permission-mode', 'bypassPermissions',
    '--model', effectiveModel,
  ];

  // 세션 재개: Claude Code의 내부 session ID로 이전 컨텍스트 복원
  if (isResume && claudeSessionId) {
    cliArgs.push('--resume', claudeSessionId);
  }

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

      // Stream text deltas
      if (msg.type === 'stream_event' && msg.delta?.type === 'text_delta' && msg.delta.text) {
        assistantText += msg.delta.text;
        yield { type: 'text', content: msg.delta.text };
        continue;
      }

      // Assistant messages: tool_use or text blocks
      if (msg.type === 'assistant' && msg.message?.content) {
        for (const block of msg.message.content) {
          if (block.type === 'tool_use') {
            assistantToolCalls.push({
              name: block.name ?? '',
              input: (block.input as Record<string, unknown>) ?? {},
            });
            yield {
              type: 'tool_use',
              toolName: block.name ?? '',
              input: block.input,
            };
          } else if (block.type === 'text' && block.text) {
            assistantText += block.text;
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
            yield {
              type: 'tool_result',
              toolName: '',
              result: resultText,
            };
          }
        }
        continue;
      }

      // Turn boundary — commit current assistant message, start new one
      if (msg.type === 'turn') {
        commitAssistant();
        continue;
      }

      // Final result — Claude session ID는 result 메시지에서만 캡처 (가장 신뢰)
      if (msg.type === 'result') {
        if (msg.session_id) claudeSessionId = msg.session_id;
        await saveTranscript();
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
    await saveTranscript().catch(() => {});
    if (fileIds?.length) {
      await cleanupChatFiles(sessionTag).catch(() => {});
    }

    const stderr = stderrChunks.join('');
    if (stderr) {
      console.error('[CLI Agent] stderr:', stderr);
    }
  }
}
