/**
 * executeOpenCodeAgent 의 비용·실패 안전장치 테스트 (WD-60).
 *
 * SDK/CLI 경로에는 있던 세 장치가 opencode 경로에만 없었다:
 *  1. 하드 예산 상한 MAX_BUDGET_USD — 넘으면 자식을 죽이고 CLI 경로와 같은 예산 초과 안내로 끝낸다.
 *  2. cost_alarm — 누적 토큰/턴 임계 초과 시 중단 없이 1회 emit.
 *  3. failure-streak — 같은 도구가 같은 오류로 연속 실패하면 buildHaltMessage 로 강제 중단.
 *
 * 가짜 opencode 자식의 stdout 으로 `--format json` 이벤트 라인을 흘려 보내 검증한다(spawn 모킹 —
 * agent-opencode-env.test.ts 와 같은 패턴). "kill 이 호출됐다"는 증거가 못 된다 — finally 가 항상
 * kill 하기 때문이다. 그래서 중단 지점 <b>뒤에</b> 센티널 텍스트 라인을 두고 그것이 소비자에게
 * 나가지 않았음을 단언해 실제로 스트림이 끊겼음을 증명한다.
 */
import { describe, it, expect, vi, beforeEach, afterAll } from 'vitest';
import { EventEmitter } from 'events';
import { Readable } from 'stream';
import { rm } from 'fs/promises';
import { useTempHome } from './temp-home.fixture.js';
import type { ChatProviderOptions, SSEEvent } from '../providers/types.js';
import type { OpenCodeCredentials } from './agent-opencode.js';
import { HALT_AT, buildHaltMessage } from './failure-streak.js';

// 임계값은 모듈 로드 시점에 env 로 확정되므로 상수 모듈 자체를 모킹한다. 테스트마다 값을 바꿀
// 수 있게 getter 로 hoisted 객체를 읽는다.
const limits = vi.hoisted(() => ({ budget: 1, tokens: 1_000_000, turns: 1_000 }));
vi.mock('../constants.js', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../constants.js')>();
  return {
    ...actual,
    get MAX_BUDGET_USD() {
      return limits.budget;
    },
    get COST_ALARM_TOKENS() {
      return limits.tokens;
    },
    get COST_ALARM_TURNS() {
      return limits.turns;
    },
  };
});

const spawnMock = vi.fn();
vi.mock('child_process', () => ({
  spawn: (...args: unknown[]) => spawnMock(...args),
}));

import { executeOpenCodeAgent } from './agent-opencode.js';

const SESSION = 'ses_test';
const SENTINEL = '이-텍스트는-나가면-안-된다';

/** stdout 으로 주어진 이벤트 라인들을 흘려 보내는 가짜 opencode 자식. */
function makeFakeChild(lines: Record<string, unknown>[]) {
  // 첫 라인 전에 잠깐 쉰다: readline 은 생성 즉시 읽기 시작하는데, 제너레이터는 init yield 와
  // claimSession(디스크 쓰기)을 마친 뒤에야 `for await (rl)` 로 구독한다. 그 사이 라인·close 가
  // 모두 지나가 버리면 이터레이터가 영원히 기다린다(실제 opencode 는 모델 호출 때문에 첫 출력이
  // 그보다 훨씬 늦다).
  async function* emit() {
    await new Promise((r) => setTimeout(r, 30));
    for (const l of lines) yield JSON.stringify({ sessionID: SESSION, ...l }) + '\n';
  }
  const stdout = Readable.from(emit());
  const child = new EventEmitter() as EventEmitter & {
    stdout: Readable;
    stderr: EventEmitter;
    kill: ReturnType<typeof vi.fn>;
  };
  child.stdout = stdout;
  child.stderr = new EventEmitter();
  child.kill = vi.fn();
  return child;
}

/** step_finish 이벤트 라인. tokens 는 opencode 실측 형태(input/output/cache.read/cache.write). */
function stepFinish(
  reason: string,
  opts: { input?: number; output?: number; cacheRead?: number; cost?: number } = {},
) {
  return {
    type: 'step_finish',
    part: {
      type: 'step-finish',
      reason,
      tokens: {
        input: opts.input ?? 0,
        output: opts.output ?? 0,
        reasoning: 0,
        cache: { read: opts.cacheRead ?? 0, write: 0 },
      },
      ...(opts.cost !== undefined ? { cost: opts.cost } : {}),
    },
  };
}

/** 도구 실패 라인 — opencode 는 MCP isError 결과를 throw 로 바꿔 state.status="error" 로 내보낸다. */
function toolError(tool: string, error: string) {
  return {
    type: 'tool_use',
    part: { type: 'tool', tool, state: { status: 'error', input: {}, error } },
  };
}

function toolOk(tool: string, output = 'ok') {
  return {
    type: 'tool_use',
    part: { type: 'tool', tool, state: { status: 'completed', input: {}, output } },
  };
}

function text(t: string) {
  return { type: 'text', part: { type: 'text', text: t } };
}

const OPTIONS: ChatProviderOptions = { message: '안녕', tenantId: 3, userId: 7, model: 'openai/gpt-4o' };
const CREDENTIALS: OpenCodeCredentials = {
  providerId: 'openai',
  baseUrl: 'https://api.openai.com/v1',
  apiKey: 'sk-tenant-key',
};

describe('executeOpenCodeAgent — 예산 상한·비용 알람·연속 실패 중단 (WD-60)', () => {
  useTempHome('agent-opencode-guards');
  const usedXdgDirs = new Set<string>();

  beforeEach(() => {
    spawnMock.mockReset();
    limits.budget = 1;
    limits.tokens = 1_000_000;
    limits.turns = 1_000;
  });

  afterAll(async () => {
    for (const dir of usedXdgDirs) await rm(dir, { recursive: true, force: true }).catch(() => {});
  });

  /** 가짜 이벤트 스트림으로 제너레이터를 끝까지 돌려 나온 SSE 이벤트 전부를 모은다. */
  async function run(lines: Record<string, unknown>[]): Promise<SSEEvent[]> {
    spawnMock.mockReturnValue(makeFakeChild(lines));
    const events: SSEEvent[] = [];
    for await (const ev of executeOpenCodeAgent(OPTIONS, CREDENTIALS)) events.push(ev);
    for (const call of spawnMock.mock.calls) {
      const dir = (call[2] as { env?: Record<string, string> })?.env?.XDG_CONFIG_HOME;
      if (dir) usedXdgDirs.add(dir);
    }
    return events;
  }

  const ofType = (events: SSEEvent[], type: SSEEvent['type']) => events.filter((e) => e.type === type);
  const hasText = (events: SSEEvent[], content: string) =>
    events.some((e) => e.type === 'text' && e.content === content);
  const sentinelLeaked = (events: SSEEvent[]) => hasText(events, SENTINEL);

  describe('cost_alarm', () => {
    it('누적 토큰(input+cache)이 임계를 넘으면 그 스텝의 turn 보다 먼저 1회만 emit 하고 중단하지 않는다', async () => {
      limits.tokens = 1_000;
      const events = await run([
        stepFinish('tool-calls', { input: 300, cacheRead: 300 }), // 누적 600
        stepFinish('tool-calls', { input: 100, cacheRead: 400 }), // 누적 1100 → 알람
        stepFinish('tool-calls', { input: 500 }), // 누적 1600 → 이미 알람 냄
        text('최종 답'),
        stepFinish('stop', { input: 10, output: 5 }),
      ]);

      const alarms = ofType(events, 'cost_alarm');
      expect(alarms).toHaveLength(1);
      // SDK 경로(agent-sdk.ts)와 같은 필드 형태 — {tokens, turns, message}.
      expect(alarms[0]).toMatchObject({ tokens: 1100, turns: 2 });
      expect(String(alarms[0].message)).toContain('1,100');
      // 알람은 넘긴 스텝의 turn 앞에 나간다(마지막 스텝이었다면 done 뒤로 밀리지 않게).
      const alarmIdx = events.indexOf(alarms[0]);
      const turnIdxs = events.map((e, i) => (e.type === 'turn' ? i : -1)).filter((i) => i >= 0);
      expect(alarmIdx).toBeGreaterThan(turnIdxs[0]);
      expect(alarmIdx).toBeLessThan(turnIdxs[1]);
      // 중단하지 않는다 — 정상 done 으로 끝나고 error 는 없다.
      expect(ofType(events, 'done')).toHaveLength(1);
      expect(ofType(events, 'error')).toHaveLength(0);
    });

    it('스텝(턴) 수가 임계에 닿으면 1회 emit 한다', async () => {
      limits.turns = 2;
      const events = await run([
        stepFinish('tool-calls', { input: 1 }),
        stepFinish('tool-calls', { input: 1 }),
        stepFinish('tool-calls', { input: 1 }),
        stepFinish('stop', { input: 1 }),
      ]);
      const alarms = ofType(events, 'cost_alarm');
      expect(alarms).toHaveLength(1);
      expect(alarms[0]).toMatchObject({ turns: 2 });
      expect(ofType(events, 'done')).toHaveLength(1);
    });

    it('마지막(stop) 스텝에서 임계를 넘어도 done 보다 먼저 나간다', async () => {
      limits.tokens = 100;
      const events = await run([text('답'), stepFinish('stop', { input: 200 })]);
      const types = events.map((e) => e.type);
      expect(types.indexOf('cost_alarm')).toBeGreaterThanOrEqual(0);
      expect(types.indexOf('cost_alarm')).toBeLessThan(types.indexOf('done'));
    });

    it('임계 미만이면 emit 하지 않는다', async () => {
      const events = await run([stepFinish('tool-calls', { input: 10 }), stepFinish('stop', { input: 10 })]);
      expect(ofType(events, 'cost_alarm')).toHaveLength(0);
    });
  });

  describe('MAX_BUDGET_USD 하드 상한', () => {
    it('누적 cost 가 상한 이상이면 CLI 경로와 같은 안내로 1회 error 를 내고 스트림을 끊는다', async () => {
      const events = await run([
        stepFinish('tool-calls', { input: 100, output: 10, cost: 0.6 }),
        stepFinish('tool-calls', { input: 200, output: 20, cost: 0.5 }), // 누적 1.1 ≥ 1 → 중단
        text(SENTINEL),
        stepFinish('stop', { cost: 0.1 }),
      ]);

      const errors = ofType(events, 'error');
      expect(errors).toHaveLength(1);
      expect(errors[0].message).toBe(
        '이 작업이 비용 한도($1)에 도달해 자동 중단되었습니다. 범위를 좁혀 다시 시도해 주세요.',
      );
      // CLI 경로처럼 토큰 사용량을 함께 싣는다.
      expect(errors[0]).toMatchObject({ inputTokens: 200, outputTokens: 20 });
      // 상한을 넘긴 뒤의 출력은 소비자에게 나가지 않는다(실제로 끊겼다는 증거).
      expect(sentinelLeaked(events)).toBe(false);
      expect(ofType(events, 'done')).toHaveLength(0);
      // error 는 종결 이벤트 — 그 뒤에 아무것도 오지 않는다.
      expect(events[events.length - 1]).toBe(errors[0]);
      // 자식 프로세스를 죽인다.
      const child = spawnMock.mock.results[0].value as { kill: ReturnType<typeof vi.fn> };
      expect(child.kill).toHaveBeenCalled();
    });

    it('마지막(stop) 스텝에서 상한을 넘으면 이미 완성된 답을 버리지 않고 정상 done 으로 끝낸다', async () => {
      const events = await run([text('완성된 답'), stepFinish('stop', { cost: 2 })]);
      expect(ofType(events, 'error')).toHaveLength(0);
      expect(ofType(events, 'done')).toHaveLength(1);
      expect(hasText(events, '완성된 답')).toBe(true);
    });

    it('공급자가 비용을 보고하지 않으면(cost 0/없음) USD 상한은 적용되지 않는다', async () => {
      limits.budget = 0.000001;
      const events = await run([
        stepFinish('tool-calls', { input: 100, cost: 0 }),
        stepFinish('tool-calls', { input: 100 }),
        text('답'),
        stepFinish('stop', { input: 100 }),
      ]);
      expect(ofType(events, 'error')).toHaveLength(0);
      expect(ofType(events, 'done')).toHaveLength(1);
    });
  });

  describe('연속 실패 강제 중단(failure-streak)', () => {
    it(`같은 도구가 같은 오류로 ${HALT_AT}회 연속 실패하면 buildHaltMessage 로 1회 error 를 내고 끊는다`, async () => {
      const lines: Record<string, unknown>[] = [];
      for (let i = 0; i < HALT_AT; i++) {
        lines.push(toolError('firehub_execute_sql', `column "c${i}" does not exist`));
        lines.push(stepFinish('tool-calls', { input: 1 }));
      }
      lines.push(text(SENTINEL));
      lines.push(stepFinish('stop'));

      const events = await run(lines);

      const errors = ofType(events, 'error');
      expect(errors).toHaveLength(1);
      expect(errors[0].message).toBe(
        buildHaltMessage('mcp__firehub__execute_sql', `column "c${HALT_AT - 1}" does not exist`),
      );
      // 마지막 실패 결과(tool_result)는 프론트에 먼저 전달된 뒤 중단된다(CLI 경로와 같은 순서).
      const results = ofType(events, 'tool_result');
      expect(results).toHaveLength(HALT_AT);
      expect(events.indexOf(results[HALT_AT - 1])).toBeLessThan(events.indexOf(errors[0]));
      expect(sentinelLeaked(events)).toBe(false);
      expect(ofType(events, 'done')).toHaveLength(0);
      expect(events[events.length - 1]).toBe(errors[0]);
    });

    it('중간에 같은 도구가 한 번 성공하면 카운트가 리셋돼 중단하지 않는다', async () => {
      const lines: Record<string, unknown>[] = [];
      for (let i = 0; i < HALT_AT - 1; i++) lines.push(toolError('firehub_execute_sql', 'syntax error'));
      lines.push(toolOk('firehub_execute_sql'));
      for (let i = 0; i < HALT_AT - 1; i++) lines.push(toolError('firehub_execute_sql', 'syntax error'));
      lines.push(text('끝'));
      lines.push(stepFinish('stop'));

      const events = await run(lines);
      expect(ofType(events, 'error')).toHaveLength(0);
      expect(ofType(events, 'done')).toHaveLength(1);
    });

    it('도구 실패는 tool_result(isError=true)로 프론트에 전달된다', async () => {
      const events = await run([toolError('firehub_execute_sql', 'boom'), stepFinish('stop')]);
      expect(ofType(events, 'tool_result')[0]).toMatchObject({
        toolName: 'mcp__firehub__execute_sql',
        result: 'boom',
        isError: true,
      });
    });
  });
});
