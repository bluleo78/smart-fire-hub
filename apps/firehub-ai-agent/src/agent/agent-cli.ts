/**
 * CLI agent executor using `claude -p` (Claude Code CLI).
 *
 * Spawns the Claude Code CLI process with a temporary MCP config that points
 * to the stdio MCP server, then parses stream-json output into SSEEvents
 * matching the same interface as executeAgent() in agent-sdk.ts.
 */
import { spawn } from 'child_process';
import { existsSync } from 'fs';
import { mkdir, readFile, readdir, writeFile, unlink } from 'fs/promises';
import { homedir, tmpdir } from 'os';
import { join } from 'path';
import { createInterface } from 'readline';
import { randomUUID } from 'crypto';
import { fileURLToPath } from 'url';
import { dirname } from 'path';
import { SYSTEM_PROMPT, FILE_ATTACHMENT_PROMPT } from './system-prompt.js';
import { resolveSystemPrompt } from './prompt-utils.js';
import { loadSubagents, buildSubagentGuide } from './subagent-loader.js';
import type { AgentDefinition } from '@anthropic-ai/claude-agent-sdk';
import type { SSEEvent, AgentOptions } from './agent-sdk.js';
import type { HistoryMessage, HistoryToolCall } from './transcript-reader.js';
import { DEFAULT_MODEL } from '../constants.js';
import { FireHubApiClient } from '../mcp/api-client.js';
import {
  downloadChatFiles,
  cleanupChatFiles,
  toAttachmentMeta,
  saveSessionAttachments,
  formatAttachmentLine,
} from './file-downloader.js';
import { DISALLOWED_TOOLS, checkToolPolicy } from './tool-policy.js';

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

export interface CliAgentOptions extends AgentOptions {
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
    overrideSystemPrompt,
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
  // 사용자별 격리된 작업 디렉토리 (세션 간 파일 유지, 소스 코드 접근 차단)
  const userWorkDir = join(homedir(), '.firehub', 'workspaces', String(userId));
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
      saveSessionAttachments(sessionId, toAttachmentMeta(files)).catch(() => {});
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
    await mkdir(getTranscriptDir(), { recursive: true });
    await writeFile(transcriptPath, JSON.stringify({ claudeSessionId, messages: transcript }));
  };

  // 환경변수(API_BASE_URL, INTERNAL_SERVICE_TOKEN) 변경 시에도 최신 상태 유지
  const mcpConfigPath = join(userWorkDir, 'mcp.json');
  await writeFile(mcpConfigPath, JSON.stringify(buildMcpConfig(userId, apiBaseUrl, internalToken), null, 2));

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
            yield {
              type: 'tool_use',
              toolName,
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
