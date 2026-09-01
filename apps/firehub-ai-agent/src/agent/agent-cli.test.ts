/**
 * agent-cli.ts 단위 테스트
 *
 * 핵심 회귀 방지 대상:
 * - #240: firehub subagent 정의가 spawn된 claude CLI에 전달되어야 한다.
 *   누락 시 메인 에이전트가 `Agent(subagent_type: "pipeline-builder")` 호출 시
 *   "Agent type not found" 에러가 발생하고 subagent rules.md가 우회된다.
 * - #260: subagent 정의는 `--agents <json>` 인자가 아닌 cwd `.claude/agents/<name>.md`
 *   파일로 전달되어야 한다. JSON 인자가 172KB → Linux MAX_ARG_STRLEN(128KB) 초과로 spawn E2BIG 재발.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { EventEmitter } from 'events';
import { Readable } from 'stream';
import { readFileSync, existsSync } from 'fs';
import { mkdir, readFile, writeFile } from 'fs/promises';
import { useTempHome } from './temp-home.fixture.js';
import { join } from 'path';
import { MAX_BUDGET_USD, COST_ALARM_TURNS } from '../constants.js';

// child_process.spawn을 모킹하여 실제 CLI를 띄우지 않고 인자만 검증한다.
const spawnMock = vi.fn();
vi.mock('child_process', () => ({
  spawn: (...args: unknown[]) => spawnMock(...args),
}));

// #259: --system-prompt-file 의 파일은 finally 에서 unlink 되므로
// spawn 호출 시점에 즉시 캡처해두어야 테스트에서 검증 가능.
let capturedSystemPromptFile: { path: string; content: string } | null = null;

// loadSubagents가 디스크를 읽지 않도록 모킹.
vi.mock('./subagent-loader.js', async () => {
  const actual = await vi.importActual<typeof import('./subagent-loader.js')>('./subagent-loader.js');
  return {
    ...actual,
    loadSubagents: vi.fn(() => ({
      'pipeline-builder': {
        description: '파이프라인 빌더',
        prompt: '당신은 파이프라인 빌더입니다.',
      },
      'dataset-manager': {
        description: '데이터셋 매니저',
        prompt: '당신은 데이터셋 매니저입니다.',
      },
    })),
    buildSubagentGuide: vi.fn(
      () => '\n\n## 전문 에이전트 활용\n\n### pipeline-builder\n파이프라인 빌더\n\n### dataset-manager\n데이터셋 매니저\n\n',
    ),
  };
});

import { executeCliAgent } from './agent-cli.js';

/** spawn() 호출 시 사용할 가짜 child process — readline 호환 stdout Readable + stderr EventEmitter. */
function makeFakeChild() {
  const stdout = Readable.from([]); // 빈 stream → 즉시 종료
  const stderr = new EventEmitter() as EventEmitter & { on: EventEmitter['on'] };

  const child = new EventEmitter() as EventEmitter & {
    stdout: Readable;
    stderr: EventEmitter;
    kill: (sig?: string) => void;
  };
  child.stdout = stdout;
  child.stderr = stderr;
  child.kill = vi.fn();

  return child;
}

/**
 * 특정 stdout 라인을 순차적으로 내보내는 가짜 child process를 생성한다.
 * Tier2 강제중단 테스트에서 사용 (lines를 스트리밍하는 Readable 생성).
 */
function makeFakeChildWithLines(lines: string[]) {
  const stdout = Readable.from(lines.map((l) => l + '\n'));
  const stderr = new EventEmitter() as EventEmitter & { on: EventEmitter['on'] };

  const child = new EventEmitter() as EventEmitter & {
    stdout: Readable;
    stderr: EventEmitter;
    kill: (sig?: string) => void;
  };
  child.stdout = stdout;
  child.stderr = stderr;
  child.kill = vi.fn();

  return child;
}

describe('executeCliAgent — Tier2 연속 실패 강제중단 (#271)', () => {
  beforeEach(() => {
    spawnMock.mockReset();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('동일 도구가 동일 오류로 8회 실패하면 child.kill + error emit', async () => {
    const lines: string[] = [];
    for (let i = 0; i < 10; i++) {
      const id = `tu_${i}`;
      lines.push(
        JSON.stringify({
          type: 'assistant',
          message: { content: [{ type: 'tool_use', name: 'execute_sql_query', input: { sql: `q${i}` }, id }] },
        }),
      );
      lines.push(
        JSON.stringify({
          type: 'user',
          message: {
            content: [{ type: 'tool_result', tool_use_id: id, content: 'column "x" does not exist', is_error: true }],
          },
        }),
      );
    }
    lines.push(JSON.stringify({ type: 'result' }));

    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);

    // 제너레이터의 모든 이벤트를 수집한다.
    const events: Array<{ type: string; message?: unknown }> = [];
    for await (const ev of executeCliAgent({
      message: 'test',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(ev as { type: string; message?: unknown });
    }

    const errs = events.filter((e) => e.type === 'error');
    expect(errs.length).toBeGreaterThanOrEqual(1);
    expect(String(errs[0].message)).toContain('execute_sql_query');
    expect(child.kill).toHaveBeenCalled();
  });
});

describe('executeCliAgent — #240 subagent registration', () => {
  beforeEach(() => {
    spawnMock.mockReset();
    capturedSystemPromptFile = null;
    spawnMock.mockImplementation((_bin: string, args: unknown) => {
      const argv = args as string[];
      // #259: --system-prompt-file 의 임시 파일은 finally 에서 unlink 되므로
      // spawn 호출 시점에 즉시 읽어 캡처해둔다.
      const fileIdx = argv.indexOf('--system-prompt-file');
      if (fileIdx >= 0) {
        const path = argv[fileIdx + 1];
        try {
          capturedSystemPromptFile = { path, content: readFileSync(path, 'utf-8') };
        } catch {
          /* ignore */
        }
      }
      return makeFakeChild();
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('subagent 정의가 cwd .claude/agents/<name>.md 파일로 작성되어야 한다 (#240, #260)', async () => {
    const gen = executeCliAgent({
      message: 'test',
      tenantId: 1,
      userId: 9999, // 고정 userId — userWorkDir 경로 예측 가능
      useSubscription: false,
      apiKey: 'sk-test',
    });

    for await (const _ev of gen) {
      // drain
    }

    expect(spawnMock).toHaveBeenCalledTimes(1);
    const [bin, args, opts] = spawnMock.mock.calls[0];
    expect(bin).toBe('claude');

    // #260: argv 에는 --agents 가 더 이상 포함되지 않아야 한다.
    const argv = args as string[];
    expect(argv).not.toContain('--agents');

    // cwd 의 .claude/agents/*.md 가 작성되어 있어야 한다.
    const cwd = (opts as { cwd: string }).cwd;
    const agentsDir = join(cwd, '.claude', 'agents');
    expect(existsSync(join(agentsDir, 'pipeline-builder.md'))).toBe(true);
    expect(existsSync(join(agentsDir, 'dataset-manager.md'))).toBe(true);

    const pbContent = readFileSync(join(agentsDir, 'pipeline-builder.md'), 'utf-8');
    expect(pbContent).toMatch(/^---\n/);
    expect(pbContent).toContain('name: pipeline-builder');
    expect(pbContent).toContain('description: "파이프라인 빌더"');
    expect(pbContent).toContain('당신은 파이프라인 빌더입니다.');
  });

  // #266: allow-by-default 정책 — --allowed-tools 미전달, --disallowed-tools 만 명시 차단
  // 이전 회귀(#256)에서 발견된 host 도구 우회를 막으면서, 신규 호스트 도구가 자동 차단돼
  // 채팅이 무응답으로 끊기는 사고(AskUserQuestion 회귀)를 동시에 방지한다.
  it('spawn된 claude CLI 인자: --allowed-tools 미전달, --disallowed-tools 만 명시 차단 (#266)', async () => {
    const gen = executeCliAgent({
      message: 'test',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    });

    for await (const _ev of gen) {
      // drain
    }

    const argv = spawnMock.mock.calls[0][1] as string[];

    // --allowed-tools 는 더 이상 전달되지 않는다 (allow-by-default)
    expect(argv).not.toContain('--allowed-tools');

    // --disallowed-tools: 명시 차단 도구만 전달
    const disallowedIdx = argv.indexOf('--disallowed-tools');
    expect(disallowedIdx).toBeGreaterThan(-1);
    const disallowedValue = argv[disallowedIdx + 1];
    // 호스트 파일 변조 / skill·task ecosystem / meta-search 차단 유지
    for (const blocked of ['Skill', 'TaskCreate', 'TaskUpdate', 'Write', 'Edit', 'NotebookEdit', 'ToolSearch']) {
      expect(disallowedValue).toContain(blocked);
    }
    // #266: AskUserQuestion / Glob / Grep / LS / WebFetch / WebSearch 는 차단 대상이 아니다
    expect(disallowedValue).not.toContain('AskUserQuestion');
    expect(disallowedValue).not.toContain('WebFetch');
    expect(disallowedValue).not.toContain('WebSearch');

    // --disable-slash-commands: 호스트 skill ecosystem 자동 로드 차단
    expect(argv).toContain('--disable-slash-commands');
  });

  it('system-prompt-file 의 내용에 buildSubagentGuide 결과가 부착되어야 한다 (#259)', async () => {
    const gen = executeCliAgent({
      message: 'test',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    });

    for await (const _ev of gen) {
      // drain
    }

    const argv = spawnMock.mock.calls[0][1] as string[];
    // #259: --system-prompt 직접 전달이 아닌 --system-prompt-file 로 파일 경로 전달.
    // ARG_MAX(128KB) 초과로 인한 spawn E2BIG 회피.
    expect(argv).toContain('--system-prompt-file');
    expect(argv).not.toContain('--system-prompt');

    expect(capturedSystemPromptFile).not.toBeNull();
    const systemPrompt = capturedSystemPromptFile!.content;
    // buildSubagentGuide가 반환한 헤더 마커가 포함되어야 한다.
    expect(systemPrompt).toContain('## 전문 에이전트 활용');
    expect(systemPrompt).toContain('pipeline-builder');
  });
});

describe('executeCliAgent — #277 비용 가드레일', () => {
  beforeEach(() => {
    spawnMock.mockReset();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('CLI-BUDGET-ARG: cliArgs에 --max-budget-usd 가 포함된다', async () => {
    const child = makeFakeChild();
    spawnMock.mockReturnValue(child);
    const events: Array<{ type: string }> = [];
    for await (const e of executeCliAgent({
      message: 'hi',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(e as { type: string });
    }
    const args = spawnMock.mock.calls[0][1] as string[];
    expect(args).toContain('--max-budget-usd');
    expect(args[args.indexOf('--max-budget-usd') + 1]).toBe(String(MAX_BUDGET_USD));
  });

  it('CLI-BUDGET-ERR: result error_max_budget_usd → 비용 한도 error 이벤트', async () => {
    const lines = [
      JSON.stringify({ type: 'result', subtype: 'error_max_budget_usd', session_id: 's', usage: { input_tokens: 1, output_tokens: 1 } }),
    ];
    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);
    const events: Array<{ type: string; message?: string }> = [];
    for await (const e of executeCliAgent({
      message: 'hi',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(e as { type: string; message?: string });
    }
    const errs = events.filter((e) => e.type === 'error');
    expect(errs.length).toBeGreaterThanOrEqual(1);
    expect(String(errs[0].message)).toContain('비용 한도');
  });

  it('CLI-AUTH-ERR: result.result 에 "Not logged in" 포함 시 한국어 인증 안내 error 이벤트 (#410)', async () => {
    const lines = [
      JSON.stringify({
        type: 'result',
        subtype: 'error_during_execution',
        session_id: 's',
        result: 'Not logged in · Please run /login',
        usage: { input_tokens: 1, output_tokens: 1 },
      }),
    ];
    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);
    const events: Array<{ type: string; message?: string }> = [];
    for await (const e of executeCliAgent({
      message: 'hi',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(e as { type: string; message?: string });
    }
    const errs = events.filter((e) => e.type === 'error');
    expect(errs.length).toBeGreaterThanOrEqual(1);
    // 원문 영문 문구가 아니라 한국어 안내로 치환되어야 한다.
    expect(String(errs[0].message)).not.toContain('Not logged in');
    expect(String(errs[0].message)).toContain('인증이 만료');
  });

  it('CLI-ALARM: 턴 수가 임계 초과 시 cost_alarm 1회', async () => {
    const lines: string[] = [];
    for (let i = 0; i < COST_ALARM_TURNS + 2; i++) {
      lines.push(JSON.stringify({ type: 'turn' }));
    }
    lines.push(JSON.stringify({ type: 'result', subtype: 'success', session_id: 's', usage: {} }));
    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);
    const events: Array<{ type: string }> = [];
    for await (const e of executeCliAgent({
      message: 'hi',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(e as { type: string });
    }
    expect(events.filter((e) => e.type === 'cost_alarm')).toHaveLength(1);
  });
});

/**
 * 재개(resume) 경로의 레거시 트랜스크립트 취급 — 코드리뷰 MAJOR 회귀 가드.
 *
 * <p>테넌트 세그먼트를 cwd 에 끼우면서 claude CLI 의 프로젝트 디렉터리가 갈렸으므로, 예전 cwd 에서
 * 만들어진 `claudeSessionId` 를 그대로 `--resume` 에 넘기면 CLI 가 "No conversation found" 로 죽고
 * result.subtype 이 `error_during_execution` 으로 돌아온다. 그 subtype 을 처리하지 않으면 **빈
 * 응답이 성공으로 보고**되고 낡은 id 가 다시 저장돼 그 세션이 영구히 같은 실패를 반복한다.
 */
describe('executeCliAgent — 레거시 트랜스크립트 재개 (코드리뷰 MAJOR)', () => {
  const home = useTempHome('firehub-cli-resume');

  beforeEach(() => {
    spawnMock.mockReset();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  /** 지정 경로에 CliTranscript 를 심는다. */
  async function seedTranscript(dir: string, sessionId: string, claudeSessionId: string) {
    await mkdir(dir, { recursive: true });
    await writeFile(
      join(dir, `${sessionId}.json`),
      JSON.stringify({
        claudeSessionId,
        messages: [{ id: 'u1', role: 'user', content: '이전 질문', timestamp: '2026-01-01T00:00:00Z' }],
      }),
    );
  }

  async function runResume(sessionId: string): Promise<string[]> {
    spawnMock.mockReturnValue(makeFakeChild());
    const gen = executeCliAgent({
      message: '이어서',
      tenantId: 7,
      userId: 1,
      sessionId,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never);
    for await (const _ of gen) {
      /* drain */
    }
    return (spawnMock.mock.calls[0][1] as string[]) ?? [];
  }

  // CLI-RESUME-01: 레거시 경로에서 읽었으면 --resume 을 붙이지 않는다.
  it('CLI-RESUME-01: drops the stale claudeSessionId when the transcript came from the legacy path', async () => {
    const legacyDir = join(home.path, '.firehub', 'transcripts');
    await seedTranscript(legacyDir, 'cli-old', 'claude-sess-from-old-cwd');

    const args = await runResume('cli-old');

    expect(args).not.toContain('--resume');
    expect(args).not.toContain('claude-sess-from-old-cwd');
  });

  // CLI-RESUME-02: 테넌트 경로에서 읽었으면 그대로 재개한다 — 위 가드가 정상 재개까지
  // 죽이지 않는지 확인한다(이게 없으면 "항상 버린다" 로도 CLI-RESUME-01 이 통과한다).
  it('CLI-RESUME-02: still resumes when the transcript is already tenant-scoped', async () => {
    const tenantDir = join(home.path, '.firehub', 'transcripts', 't7');
    await seedTranscript(tenantDir, 'cli-new', 'claude-sess-current');

    const args = await runResume('cli-new');

    expect(args).toContain('--resume');
    expect(args).toContain('claude-sess-current');
  });

  // CLI-RESUME-04: 봉투 없는 옛 배열 포맷도 재개된다. 정규화를 소비자(transcript-reader)에만
  // 두면 이 경로는 `saved.messages` 가 undefined 가 되어 이어 말하기가 깨진다 — 그래서
  // readCliTranscript 가 발생지에서 정규화한다.
  it('CLI-RESUME-04: resumes a legacy array-shaped transcript without an envelope', async () => {
    const dir = join(home.path, '.firehub', 'transcripts', 't7');
    await mkdir(dir, { recursive: true });
    // 봉투(`{claudeSessionId, messages}`) 없이 메시지 배열만 저장된 옛 파일.
    await writeFile(
      join(dir, 'cli-array.json'),
      JSON.stringify([
        { id: 'u1', role: 'user', content: '옛 질문', timestamp: '2026-01-01T00:00:00Z' },
      ]),
    );

    spawnMock.mockReturnValue(makeFakeChild());
    const events: Array<{ type: string }> = [];
    for await (const ev of executeCliAgent({
      message: '이어서',
      tenantId: 7,
      userId: 1,
      sessionId: 'cli-array',
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(ev as { type: string });
    }

    // 던지지 않고 정상 진행했다는 것 자체가 검증 대상이다(정규화 전에는 messages 가 undefined 라
    // transcript.push 에서 TypeError 가 났다).
    expect(events.some((e) => e.type === 'init')).toBe(true);
    // 옛 메시지가 보존된 채 새 트랜스크립트가 테넌트 경로에 저장돼야 한다.
    const saved = JSON.parse(await readFile(join(dir, 'cli-array.json'), 'utf-8')) as {
      messages: Array<{ content: string }>;
    };
    expect(saved.messages[0].content).toBe('옛 질문');
  });

  // CLI-RESUME-03: error_* subtype 은 조용한 done 이 아니라 error 로 나가야 한다.
  it('CLI-RESUME-03: surfaces error_during_execution as an error event, not a silent done', async () => {
    const child = makeFakeChildWithLines([
      JSON.stringify({
        type: 'result',
        subtype: 'error_during_execution',
        result: 'No conversation found with session ID: claude-sess-from-old-cwd',
      }),
    ]);
    spawnMock.mockReturnValue(child);

    const events: Array<{ type: string; message?: unknown }> = [];
    for await (const ev of executeCliAgent({
      message: '이어서',
      tenantId: 7,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(ev as { type: string; message?: unknown });
    }

    expect(events.some((e) => e.type === 'error')).toBe(true);
    expect(events.some((e) => e.type === 'done')).toBe(false);
  });
});
