/**
 * agent-cli.ts 단위 테스트
 *
 * 핵심 회귀 방지 대상:
 * - #240: firehub subagent 정의가 spawn된 claude CLI에게 `--agents` 플래그로 전달되어야 한다.
 *   누락 시 메인 에이전트가 `Agent(subagent_type: "pipeline-builder")` 호출 시
 *   "Agent type not found" 에러가 발생하고 subagent rules.md가 우회된다.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { EventEmitter } from 'events';
import { Readable } from 'stream';

// child_process.spawn을 모킹하여 실제 CLI를 띄우지 않고 인자만 검증한다.
const spawnMock = vi.fn();
vi.mock('child_process', () => ({
  spawn: (...args: unknown[]) => spawnMock(...args),
}));

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
    spawnMock.mockImplementation(() => makeFakeChild());
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('spawn된 claude CLI 인자에 --agents JSON이 포함되어야 한다 (#240)', async () => {
    const gen = executeCliAgent({
      message: 'test',
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    });

    // 제너레이터 소비 (init만 받고 종료)
    for await (const _ev of gen) {
      // no-op — just drain
    }

    expect(spawnMock).toHaveBeenCalledTimes(1);
    const [bin, args] = spawnMock.mock.calls[0];
    expect(bin).toBe('claude');
    expect(Array.isArray(args)).toBe(true);

    const argv = args as string[];
    const agentsIdx = argv.indexOf('--agents');
    expect(agentsIdx).toBeGreaterThan(-1);

    const agentsJson = argv[agentsIdx + 1];
    const parsed = JSON.parse(agentsJson);
    expect(Object.keys(parsed).sort()).toEqual(['dataset-manager', 'pipeline-builder']);
    expect(parsed['pipeline-builder']).toHaveProperty('description');
    expect(parsed['pipeline-builder']).toHaveProperty('prompt');
  });

  it('system-prompt에 buildSubagentGuide 결과가 부착되어야 한다', async () => {
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
    const spIdx = argv.indexOf('--system-prompt');
    expect(spIdx).toBeGreaterThan(-1);
    const systemPrompt = argv[spIdx + 1];
    // buildSubagentGuide가 반환한 헤더 마커가 포함되어야 한다.
    expect(systemPrompt).toContain('## 전문 에이전트 활용');
    expect(systemPrompt).toContain('pipeline-builder');
  });
});
