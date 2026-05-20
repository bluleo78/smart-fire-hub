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
import { join } from 'path';

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

  // #256: 회귀 — CLI 프로바이더에 --allowed-tools/--disallowed-tools/--disable-slash-commands
  // 플래그가 누락되면 host 도구(Skill, TaskCreate, Bash 등)가 차단되지 않는다.
  it('spawn된 claude CLI 인자에 도구 정책 플래그가 포함되어야 한다 (#256)', async () => {
    const gen = executeCliAgent({
      message: 'test',
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    });

    for await (const _ev of gen) {
      // drain
    }

    const argv = spawnMock.mock.calls[0][1] as string[];

    // --allowed-tools: 화이트리스트
    const allowedIdx = argv.indexOf('--allowed-tools');
    expect(allowedIdx).toBeGreaterThan(-1);
    const allowedValue = argv[allowedIdx + 1];
    expect(allowedValue).toContain('mcp__firehub__*');
    expect(allowedValue).toContain('Agent');

    // --disallowed-tools: skill/task/host IO 명시 차단
    const disallowedIdx = argv.indexOf('--disallowed-tools');
    expect(disallowedIdx).toBeGreaterThan(-1);
    const disallowedValue = argv[disallowedIdx + 1];
    // #262: Bash/Read 는 첨부 처리용으로 ALLOWED 이동 — 차단 검증 대상에서 제외
    for (const blocked of ['Skill', 'TaskCreate', 'TaskUpdate', 'Write', 'WebFetch']) {
      expect(disallowedValue).toContain(blocked);
    }

    // --disable-slash-commands: 호스트 skill ecosystem 자동 로드 차단
    expect(argv).toContain('--disable-slash-commands');
  });

  it('system-prompt-file 의 내용에 buildSubagentGuide 결과가 부착되어야 한다 (#259)', async () => {
    const gen = executeCliAgent({
      message: 'test',
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
