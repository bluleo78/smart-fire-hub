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
      'smart-job-manager': {
        description: '스마트 작업 매니저',
        prompt: '당신은 스마트 작업 매니저입니다.',
      },
      'data-analyst': {
        description: '데이터 분석가',
        prompt: '당신은 데이터 분석가입니다.',
      },
    })),
    buildSubagentGuide: vi.fn(
      () =>
        '\n\n## 전문 에이전트 활용\n\n### pipeline-builder\n파이프라인 빌더\n\n### dataset-manager\n데이터셋 매니저\n\n### smart-job-manager\n스마트 작업 매니저\n\n',
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

describe('executeCliAgent — #428/#429 subagent 결과 중복 relay 억제 (화이트리스트 폐지)', () => {
  beforeEach(() => {
    spawnMock.mockReset();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('pipeline-builder 위임 완료 후 메인이 같은 요청에서 재요약하면 억제한다(문구 변형 포함)', async () => {
    const delegateToolUseId = 'toolu_delegate_1';
    const lines: string[] = [
      // 메인이 pipeline-builder 에 위임(Agent tool_use, parent 없음)
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [
            {
              type: 'tool_use',
              id: delegateToolUseId,
              name: 'Agent',
              input: { subagent_type: 'pipeline-builder', prompt: 'Mode: DESIGN\n...' },
            },
          ],
        },
      }),
      // subagent 자신의 완료 텍스트 — parent_tool_use_id = 위임 tool_use id.
      // 실제 라이브 재현에서 관찰된 동의어 표현("생성해도 될지")을 사용해, 정규식 기반
      // 판별이었다면 놓쳤을 케이스를 회귀 가드로 고정한다.
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: delegateToolUseId,
        message: {
          content: [{ type: 'text', text: '## 설계안 ...\n이 설계 그대로 생성해도 될지 확인 부탁드립니다.' }],
        },
      }),
      // 메인이 같은 요청 안에서 재요약을 시도(parent 없음) — 억제 대상
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: '## 설계안 재요약 ...\n이대로 생성할까요?' }] },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ];

    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);

    const events: Array<{ type: string; content?: string }> = [];
    for await (const ev of executeCliAgent({
      message: '파이프라인 만들어줘',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(ev as { type: string; content?: string });
    }

    const texts = events.filter((e) => e.type === 'text').map((e) => e.content);
    // subagent 자신의 확인 텍스트는 그대로 노출되고, 메인의 재요약은 억제되어 정확히 1개만 남는다.
    expect(texts).toEqual(['## 설계안 ...\n이 설계 그대로 생성해도 될지 확인 부탁드립니다.']);
  });

  it('pipeline-builder 위임이 없으면 메인 텍스트를 억제하지 않는다', async () => {
    const lines: string[] = [
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: '일반 응답입니다.' }] },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ];
    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);

    const events: Array<{ type: string; content?: string }> = [];
    for await (const ev of executeCliAgent({
      message: '안녕',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(ev as { type: string; content?: string });
    }

    expect(events.filter((e) => e.type === 'text').map((e) => e.content)).toEqual(['일반 응답입니다.']);
  });

  it('#429: smart-job-manager(화이트리스트 밖 subagent) 위임 완료 후 메인 재요약도 억제한다', async () => {
    const delegateToolUseId = 'toolu_delegate_sjm';
    const lines: string[] = [
      // 메인이 smart-job-manager 에 위임(Agent tool_use, parent 없음) — pipeline-builder 등
      // 3개 화이트리스트에 없는 subagent_type 이라는 점이 이 테스트의 핵심.
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [
            {
              type: 'tool_use',
              id: delegateToolUseId,
              name: 'Agent',
              input: { subagent_type: 'smart-job-manager', prompt: '스마트 작업 만들어줘' },
            },
          ],
        },
      }),
      // subagent 자신의 완료 텍스트(설계안 + 확인 질문).
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: delegateToolUseId,
        message: {
          content: [{ type: 'text', text: '## 설계안 ...\n이대로 생성할까요?' }],
        },
      }),
      // 메인이 같은 요청 안에서 재요약을 시도(parent 없음) — 억제 대상.
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: '## 설계안 재요약 ...\n확인 부탁드립니다.' }] },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ];

    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);

    const events: Array<{ type: string; content?: string }> = [];
    for await (const ev of executeCliAgent({
      message: '스마트 작업 만들어줘',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(ev as { type: string; content?: string });
    }

    const texts = events.filter((e) => e.type === 'text').map((e) => e.content);
    expect(texts).toEqual(['## 설계안 ...\n이대로 생성할까요?']);
  });

  it('#429: subagent 완료 후 메인이 새 tool_use 를 발행하면 억제를 해제하고 이후 메인 텍스트를 emit 한다', async () => {
    const delegateToolUseId = 'toolu_delegate_reset';
    const lines: string[] = [
      // 메인이 pipeline-builder 에 위임.
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [
            {
              type: 'tool_use',
              id: delegateToolUseId,
              name: 'Agent',
              input: { subagent_type: 'pipeline-builder', prompt: 'Mode: DESIGN\n...' },
            },
          ],
        },
      }),
      // subagent 완료 텍스트 — 억제 플래그가 세팅된다.
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: delegateToolUseId,
        message: { content: [{ type: 'text', text: '파이프라인을 생성했습니다.' }] },
      }),
      // 메인이 새 도구(다른 subagent 위임이 아닌 일반 도구)를 호출 — 재서술이 아니라 실제
      // 다음 작업이라는 구조적 증거이므로 이 시점에 억제가 해제되어야 한다.
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [{ type: 'tool_use', id: 'toolu_run', name: 'mcp__firehub__run_pipeline', input: { pipelineId: 1 } }],
        },
      }),
      // run_pipeline 결과에 대한 메인의 정당한 보고 — 억제되지 않고 emit 되어야 한다.
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: '파이프라인 실행을 시작했습니다.' }] },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ];

    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);

    const events: Array<{ type: string; content?: string }> = [];
    for await (const ev of executeCliAgent({
      message: '파이프라인 만들고 바로 실행해줘',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(ev as { type: string; content?: string });
    }

    const texts = events.filter((e) => e.type === 'text').map((e) => e.content);
    expect(texts).toEqual(['파이프라인을 생성했습니다.', '파이프라인 실행을 시작했습니다.']);
  });

  it('#429: subagent 내부 tool_use(parent 있음)는 억제를 해제하지 않는다', async () => {
    const delegateToolUseId = 'toolu_delegate_internal';
    const lines: string[] = [
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [
            {
              type: 'tool_use',
              id: delegateToolUseId,
              name: 'Agent',
              input: { subagent_type: 'smart-job-manager', prompt: '스마트 작업 만들어줘' },
            },
          ],
        },
      }),
      // subagent 완료 텍스트 — 억제 플래그 세팅.
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: delegateToolUseId,
        message: { content: [{ type: 'text', text: '## 설계안 ...\n이대로 생성할까요?' }] },
      }),
      // subagent 가 완료 *후* 내부적으로 또 다른 도구를 호출하는 비정상 케이스를 가정해도
      // (parent_tool_use_id 가 위임 id) 메인의 억제 상태에는 영향이 없어야 한다.
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: delegateToolUseId,
        message: {
          content: [{ type: 'tool_use', id: 'toolu_internal', name: 'mcp__firehub__list_proactive_jobs', input: {} }],
        },
      }),
      // 메인의 재요약 시도 — 여전히 억제되어야 한다.
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: '## 설계안 재요약 ...\n확인 부탁드립니다.' }] },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ];

    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);

    const events: Array<{ type: string; content?: string }> = [];
    for await (const ev of executeCliAgent({
      message: '스마트 작업 만들어줘',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(ev as { type: string; content?: string });
    }

    const texts = events.filter((e) => e.type === 'text').map((e) => e.content);
    expect(texts).toEqual(['## 설계안 ...\n이대로 생성할까요?']);
  });

  it('#430: SendMessage 로 재개한 비동기 subagent 가 지연 완료(task_notification)되면, 메인이 이미 직접 완료 보고를 한 뒤라도 재서술을 억제한다', async () => {
    // #428/#429는 같은 요청 안에서 Agent 로 위임 → parent_tool_use_id 로 태깅된 텍스트가
    // 인터리브되는 경우를 다뤘다. #430은 다른 턴(별도 HTTP 요청)에서 시작된 비동기 subagent 를
    // SendMessage 로 재개했는데, 메인이 그 완료를 기다리지 않고 스스로 도구를 호출해 먼저
    // 완료 보고를 한 뒤, 뒤늦게 도착한 system/task_notification 때문에 같은 내용을 또
    // 보고하던 실제 라이브 재현 패턴을 고정한다.
    const sendMessageToolUseId = 'toolu_sendmsg_1';
    const lines: string[] = [
      // 메인이 이전 턴에 pin된 subagent 를 SendMessage 로 재개(parent 없음)
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [
            { type: 'tool_use', id: sendMessageToolUseId, name: 'SendMessage', input: { to: 'a60d5da10faae7e5b' } },
          ],
        },
      }),
      // 메인이 위임 완료를 기다리지 않고 스스로 도구를 호출해 먼저 완료 보고를 한다.
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [{ type: 'tool_use', id: 'toolu_create_job', name: 'mcp__firehub__create_proactive_job', input: {} }],
        },
      }),
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: "'파이프라인 실패율 주간 체크' 스마트 작업이 등록됐습니다." }] },
      }),
      // 뒤늦게 비동기 subagent 완료 알림 도착 — SendMessage 의 tool_use id 를 참조.
      JSON.stringify({
        type: 'system',
        subtype: 'task_notification',
        task_id: 'a60d5da10faae7e5b',
        tool_use_id: sendMessageToolUseId,
        status: 'completed',
      }),
      // 메인이 뒤늦은 알림을 보고 같은 내용을 다시 보고하려는 시도 — 억제 대상.
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: "'파이프라인 실패율 주간 체크' 스마트 작업이 등록됐습니다." }] },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ];

    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);

    const events: Array<{ type: string; content?: string }> = [];
    for await (const ev of executeCliAgent({
      message: '네, 이대로 생성해주세요.',
      tenantId: 1,
      userId: 1,
      sessionId: 'cli-existing-session',
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(ev as { type: string; content?: string });
    }

    const texts = events.filter((e) => e.type === 'text').map((e) => e.content);
    expect(texts).toEqual(["'파이프라인 실패율 주간 체크' 스마트 작업이 등록됐습니다."]);
  });

  it('#430: task_notification 의 tool_use_id 가 위임 목록에 없으면(무관한 알림) 메인 텍스트를 억제하지 않는다', async () => {
    const lines: string[] = [
      JSON.stringify({
        type: 'system',
        subtype: 'task_notification',
        task_id: 'unrelated-task',
        tool_use_id: 'toolu_never_registered',
        status: 'completed',
      }),
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: '일반 응답입니다.' }] },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ];
    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);

    const events: Array<{ type: string; content?: string }> = [];
    for await (const ev of executeCliAgent({
      message: '안녕',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(ev as { type: string; content?: string });
    }

    expect(events.filter((e) => e.type === 'text').map((e) => e.content)).toEqual(['일반 응답입니다.']);
  });
});

describe('executeCliAgent — #573 2차 수정: 동기(run_in_background:false) Agent 위임 fallback relay', () => {
  // 1차 수정(process-message.ts, SDK 프로바이더)은 실제 재현 경로인 CLI 프로바이더
  // (agentType: "cli" → agent-cli.ts)에 이식되지 않아 크로스체크에서 그대로 재발했다(회귀).
  // 이 describe 는 agent-cli.ts 에 이식한 fallback relay 를 라이브 트레이스
  // (crosscheck-573-attempt1.sse) 와 동일한 이벤트 시퀀스로 고정한다.
  beforeEach(() => {
    spawnMock.mockReset();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('PM-573e: 동기 위임 tool_result 가 텍스트 없이 턴이 끝나면 footer 를 제거하고 강제로 relay 한다', async () => {
    const delegateToolUseId = 'toolu_sync_delegate_1';
    const lines: string[] = [
      // 메인이 dataset-manager 에 동기(run_in_background:false) 위임 — 크로스체크 실측 케이스.
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [
            {
              type: 'tool_use',
              id: delegateToolUseId,
              name: 'Agent',
              input: {
                subagent_type: 'dataset-manager',
                prompt: 'Mode: DESIGN\n...',
                run_in_background: false,
              },
            },
          ],
        },
      }),
      // 동기 위임 경로는 subagent 내부 텍스트가 interleave 되지 않고 tool_result 문자열
      // 하나로만 온다 — 실측 트레이스와 동일하게 agentId/<usage> footer 를 포함한다.
      JSON.stringify({
        type: 'user',
        parent_tool_use_id: null,
        message: {
          content: [
            {
              type: 'tool_result',
              tool_use_id: delegateToolUseId,
              content:
                "created_at은 시스템 예약 컬럼이라 사용할 수 없습니다. 대체 이름을 제안합니다.\n\n이 스키마로 생성할까요?agentId: ac10e871dec5c7204 (use SendMessage with to: 'ac10e871dec5c7204', summary: '<5-10 word recap>' to continue this agent)\n<usage>subagent_tokens: 19710\ntool_uses: 0\nduration_ms: 3010</usage>",
            },
          ],
        },
      }),
      // 메인이 아무 텍스트도 내지 않고 바로 턴 종료 — 회귀 재현.
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ];

    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);

    const events: Array<{ type: string; content?: string }> = [];
    for await (const ev of executeCliAgent({
      message: '테스트용 데이터셋 만들어줘. 컬럼은 created_at(날짜), value(숫자) 두개만.',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(ev as { type: string; content?: string });
    }

    const texts = events.filter((e) => e.type === 'text').map((e) => e.content);
    // 내부 식별자(agentId)/사용량 footer 는 제거되고 제안 본문만 강제 relay 되어야 한다.
    expect(texts).toEqual([
      'created_at은 시스템 예약 컬럼이라 사용할 수 없습니다. 대체 이름을 제안합니다.\n\n이 스키마로 생성할까요?',
    ]);
    expect(events.some((e) => e.type === 'done')).toBe(true);
  });

  it('PM-573f: 메인이 텍스트로 정상 relay 했으면 fallback 을 중복 적용하지 않는다', async () => {
    const delegateToolUseId = 'toolu_sync_delegate_2';
    const lines: string[] = [
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [
            {
              type: 'tool_use',
              id: delegateToolUseId,
              name: 'Agent',
              input: { subagent_type: 'dataset-manager', prompt: '...', run_in_background: false },
            },
          ],
        },
      }),
      JSON.stringify({
        type: 'user',
        parent_tool_use_id: null,
        message: {
          content: [{ type: 'tool_result', tool_use_id: delegateToolUseId, content: '제안 내용...확인할까요?' }],
        },
      }),
      // 메인이 정상적으로 시스템 프롬프트를 준수해 텍스트로 relay 함.
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: '제안 내용...확인할까요?' }] },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ];

    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);

    const events: Array<{ type: string; content?: string }> = [];
    for await (const ev of executeCliAgent({
      message: '테스트',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(ev as { type: string; content?: string });
    }

    const texts = events.filter((e) => e.type === 'text').map((e) => e.content);
    // fallback 이 중복 적용됐다면 같은 텍스트가 2번 나온다 — 정확히 1개여야 한다.
    expect(texts).toEqual(['제안 내용...확인할까요?']);
  });

  it('PM-573g: 비동기 위임(run_in_background 미지정)의 tool_result 는 fallback 대상이 아니다', async () => {
    const delegateToolUseId = 'toolu_async_delegate_1';
    const lines: string[] = [
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [
            {
              type: 'tool_use',
              id: delegateToolUseId,
              name: 'Agent',
              input: { subagent_type: 'dataset-manager', prompt: '...' },
            },
          ],
        },
      }),
      // 비동기 위임의 tool_result 는 보통 "Async agent launched..." 같은 안내이며 최종 응답이
      // 아니다 — run_in_background:false 로 표시되지 않았으므로 fallback 대상에서 제외돼야 한다.
      JSON.stringify({
        type: 'user',
        parent_tool_use_id: null,
        message: {
          content: [
            { type: 'tool_result', tool_use_id: delegateToolUseId, content: 'Async agent launched successfully.' },
          ],
        },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ];

    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);

    const events: Array<{ type: string; content?: string }> = [];
    for await (const ev of executeCliAgent({
      message: '테스트',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(ev as { type: string; content?: string });
    }

    expect(events.filter((e) => e.type === 'text')).toEqual([]);
  });

  it('PM-573h: 동기 위임 결과 이후 메인이 새 tool_use 를 발행하면(후속 작업) fallback relay 를 적용하지 않는다', async () => {
    const delegateToolUseId = 'toolu_sync_delegate_3';
    const lines: string[] = [
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [
            {
              type: 'tool_use',
              id: delegateToolUseId,
              name: 'Agent',
              input: { subagent_type: 'dataset-manager', prompt: '...', run_in_background: false },
            },
          ],
        },
      }),
      JSON.stringify({
        type: 'user',
        parent_tool_use_id: null,
        message: {
          content: [{ type: 'tool_result', tool_use_id: delegateToolUseId, content: '스키마 확정, 생성 진행합니다.' }],
        },
      }),
      // 메인이 확인 텍스트 없이 바로 다음 도구를 호출 — 실제 후속 작업이 진행 중이라는 증거.
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [{ type: 'tool_use', id: 'toolu_create_dataset', name: 'mcp__firehub__create_dataset', input: {} }],
        },
      }),
      JSON.stringify({
        type: 'user',
        parent_tool_use_id: null,
        message: { content: [{ type: 'tool_result', tool_use_id: 'toolu_create_dataset', content: '{"id":1}' }] },
      }),
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: '데이터셋 생성이 완료됐습니다.' }] },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ];

    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);

    const events: Array<{ type: string; content?: string }> = [];
    for await (const ev of executeCliAgent({
      message: '테스트',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(ev as { type: string; content?: string });
    }

    const texts = events.filter((e) => e.type === 'text').map((e) => e.content);
    // fallback 이 잘못 끼어들었다면 '스키마 확정, 생성 진행합니다.' 가 먼저 나왔을 것이다.
    expect(texts).toEqual(['데이터셋 생성이 완료됐습니다.']);
  });

  it('PM-573i: 동기 위임 id 에 대해 뒤늦게 도착하는 중복 task_notification(#430)이 메인의 정상 relay 텍스트를 억제하지 않는다', async () => {
    // 라이브 크로스체크로 실제 확인된 2차 회귀의 진짜 원인: `Agent(run_in_background:false)` 로
    // 위임해도 CLI 는 내부적으로 같은 호출에 대해 `system/task_notification`(#430) 을 중복
    // 발사할 수 있다(관찰상 tool_result 직후 거의 즉시 도착). 이전 구현은 동기 위임 id 를
    // pendingDesignGuardToolUseIds 화이트리스트에도 함께 등록해, 이 중복 알림이 "이미
    // 사용자에게 보였다"는 잘못된 전제로 suppressMainText 를 세팅했고, 메인이 tool_result 를
    // 정상적으로 relay 하려는 유일한 텍스트까지 억제해 완전히 빈 응답이 재발했다.
    const delegateToolUseId = 'toolu_sync_delegate_430';
    const lines: string[] = [
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [
            {
              type: 'tool_use',
              id: delegateToolUseId,
              name: 'Agent',
              input: { subagent_type: 'dataset-manager', prompt: '...', run_in_background: false },
            },
          ],
        },
      }),
      // #430 중복 알림이 tool_result 보다 먼저 도착 — 라이브 재현과 동일 순서.
      JSON.stringify({
        type: 'system',
        subtype: 'task_notification',
        task_id: delegateToolUseId,
        tool_use_id: delegateToolUseId,
        status: 'completed',
      }),
      JSON.stringify({
        type: 'user',
        parent_tool_use_id: null,
        message: {
          content: [
            {
              type: 'tool_result',
              tool_use_id: delegateToolUseId,
              content: '다음과 같이 설계안을 제안합니다...이대로 생성할까요?',
            },
          ],
        },
      }),
      // 메인이 시스템 프롬프트를 준수해 tool_result 를 텍스트로 relay 하려는 시도 — 억제되면 안 된다.
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: '다음과 같이 설계안을 제안합니다...이대로 생성할까요?' }] },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ];

    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);

    const events: Array<{ type: string; content?: string }> = [];
    for await (const ev of executeCliAgent({
      message: '테스트용 데이터셋 만들어줘.',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(ev as { type: string; content?: string });
    }

    const texts = events.filter((e) => e.type === 'text').map((e) => e.content);
    // 중복 task_notification 때문에 억제됐다면 texts 가 빈 배열이었을 것이다(회귀 시 관찰된 증상).
    expect(texts).toEqual(['다음과 같이 설계안을 제안합니다...이대로 생성할까요?']);
  });
});

describe('executeCliAgent — #572 2차 수정: data-analyst 동기 위임과 조사 도구의 병렬 tool_use 배치', () => {
  // 1차 수정(#572, commit 44a81286)은 "data-analyst 위임은 항상 run_in_background:false" 규칙만
  // 추가했으나, 크로스체크에서 3회 연속 재현됐다 — 실제 재현 경로는 라이브 trace
  // (test-results/subagent-eval/2026-09-09T10-05/traces/crosscheck-572-r1.sse)와 동일하게, 메인이
  // Agent(data-analyst, run_in_background:false) 위임과 find_datasets/get_dataset 등 조사 도구를
  // 같은 turn 안에서 병렬로 발행하는 것이다 — 실행이 가벼운 도구가 먼저 끝나고 무거운 Agent 위임의
  // tool_result 는 가장 마지막에 도착한다. 메인은 자체 조사 결과로 결론 텍스트를 작성해 응답을
  // 마치고, data-analyst 의 실제 분석 결과는 어떤 text 에도 노출되지 않은 채 폐기됐다.
  beforeEach(() => {
    spawnMock.mockReset();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('PM-572-2: 위임 tool_result 를 받기 전에 조사 도구가 병렬 발행되면, 메인의 자체 재조사 텍스트는 억제되고 위임의 실제 결과만 relay 된다', async () => {
    const delegateToolUseId = 'toolu_da_delegate';
    const delegateResultText =
      '"화재발생현황" 데이터셋을 찾아 월별 평균 사망자수를 집계했습니다: 1월 2.3명, 2월 1.8명...(LINE 차트 생성 완료)';
    const wrongMainText =
      '"화재발생현황"이라는 이름의 데이터셋은 없고, death_count 컬럼이 있는 fire_incidents 데이터셋은 0건이라 평균을 계산할 수 없습니다.';

    const lines: string[] = [
      // 메인이 data-analyst 에 동기 위임 — 라이브 trace 와 동일한 프롬프트.
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [
            {
              type: 'tool_use',
              id: delegateToolUseId,
              name: 'Agent',
              input: {
                subagent_type: 'data-analyst',
                description: '월별 평균 사망자수 분석',
                run_in_background: false,
                prompt: '화재발생현황 데이터셋을 찾아서 월별 평균 사망자수를 집계해줘.',
              },
            },
          ],
        },
      }),
      // 위임의 tool_result 가 오기 전에 메인이 조사 도구를 발행 — 병렬 배치의 정확한 재현.
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [
            { type: 'tool_use', id: 'toolu_find_1', name: 'mcp__firehub__find_datasets', input: { query: '화재발생현황' } },
          ],
        },
      }),
      JSON.stringify({
        type: 'user',
        parent_tool_use_id: null,
        message: {
          content: [
            { type: 'tool_result', tool_use_id: 'toolu_find_1', content: '[{"datasetId":87,"name":"지역별 화재 통계"}]' },
          ],
        },
      }),
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [{ type: 'tool_use', id: 'toolu_schema_1', name: 'mcp__firehub__get_data_schema', input: { datasetIds: [67, 87] } }],
        },
      }),
      JSON.stringify({
        type: 'user',
        parent_tool_use_id: null,
        message: { content: [{ type: 'tool_result', tool_use_id: 'toolu_schema_1', content: '{"tables":[]}' }] },
      }),
      // 위임의 실제 tool_result 는 가장 마지막에 도착 — 라이브 trace 와 동일한 순서.
      JSON.stringify({
        type: 'user',
        parent_tool_use_id: null,
        message: {
          content: [{ type: 'tool_result', tool_use_id: delegateToolUseId, content: delegateResultText }],
        },
      }),
      // 메인이 자체 조사 결과로 재구성한 결론 텍스트로 응답 — 반드시 사용자에게 노출되면 안 된다.
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: wrongMainText }] },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ];

    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);

    const events: Array<{ type: string; content?: string }> = [];
    for await (const ev of executeCliAgent({
      message: '화재발생현황 데이터셋을 분석해서 월별 평균 사망자수를 내줘',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(ev as { type: string; content?: string });
    }

    const texts = events.filter((e) => e.type === 'text').map((e) => e.content);
    // 메인의 자체 재조사 텍스트(wrongMainText)는 절대 노출되지 않고, 위임의 실제 결과만 relay 된다.
    expect(texts).toEqual([delegateResultText]);
    expect(events.some((e) => e.type === 'done')).toBe(true);
  });

  it('PM-572-2c: 라이브 재현으로 확정된 실제 메커니즘 — 조사 도구는 subagent 내부(parent 태그)이고, 메인은 위임의 tool_result 를 문자 그대로 relay 하지 않고 재구성(paraphrase)만 한다', async () => {
    // 2026-09-09 라이브 재현(curl 직접 호출, 동일 프롬프트 3회)으로 확정된 실제 원인: 최초
    // 가설(메인이 top-level 로 조사 도구를 병렬 발행)과 달리, find_datasets/get_dataset 등은
    // 전부 data-analyst subagent **내부**(parent_tool_use_id = 위임 tool_use id)에서 정당하게
    // 호출된 것이었다 — 이는 아무 문제가 없다. 진짜 결함은 위임의 tool_result 가 도착한 뒤
    // 메인이 그 내용을 문자 그대로 relay 하지 않고 자신의 말로 재구성(표 행 누락·문구 변경)해서
    // 응답한 것 — "텍스트를 냈다"는 사실만으로 정상 relay 로 신뢰하던 예전 로직이 이를 통과시켰다.
    const delegateToolUseId = 'toolu_01FCnsqvfY3XyXvjQx91Z6rQ';
    const delegateResultText =
      '이 테스트 더미셋도 0건, 사망자 관련 컬럼 없음. 확인 완료했습니다.\n\n## 결론: "화재발생현황"이라는 이름의 데이터셋은 존재하지 않으며, 월별 평균 사망자수를 산출할 수 있는 데이터가 없습니다\n\n| 데이터셋 | 사망자 관련 컬럼 | 행 수 |\n|---|---|---|\n| fire_incidents | death_count | 0건 |\n| fire_stats | casualties | 5건 |';
    const paraphrasedMainText =
      '"화재발생현황"이라는 이름의 데이터셋은 없고, 월별 평균 사망자수를 산출할 데이터가 없습니다.\n\n| 데이터셋 | 컬럼 | 행 수 |\n|---|---|---|\n| fire_incidents | death_count | 0건 |';

    const lines: string[] = [
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [
            {
              type: 'tool_use',
              id: delegateToolUseId,
              name: 'Agent',
              input: { subagent_type: 'data-analyst', run_in_background: false, prompt: '...' },
            },
          ],
        },
      }),
      // subagent 내부 조사 — parent_tool_use_id 가 위임 tool_use id 로 채워져 있다(정당한 흐름).
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: delegateToolUseId,
        message: {
          content: [{ type: 'tool_use', id: 'toolu_inner_find', name: 'mcp__firehub__find_datasets', input: {} }],
        },
      }),
      JSON.stringify({
        type: 'user',
        parent_tool_use_id: delegateToolUseId,
        message: { content: [{ type: 'tool_result', tool_use_id: 'toolu_inner_find', content: '[]' }] },
      }),
      // 위임의 최종 tool_result(top-level, parent 없음) — subagent 의 실제 완료 내용.
      JSON.stringify({
        type: 'user',
        parent_tool_use_id: null,
        message: {
          content: [{ type: 'tool_result', tool_use_id: delegateToolUseId, content: delegateResultText }],
        },
      }),
      // 메인이 이 tool_result 를 받고도 자신의 말로 재구성(paraphrase)한 텍스트로 응답 — 새
      // top-level tool_use 는 전혀 없다(라이브 재현과 동일).
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: paraphrasedMainText }] },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ];

    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);

    const events: Array<{ type: string; content?: string }> = [];
    for await (const ev of executeCliAgent({
      message: '화재발생현황 데이터셋을 분석해서 월별 평균 사망자수를 내줘',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(ev as { type: string; content?: string });
    }

    const texts = events.filter((e) => e.type === 'text').map((e) => e.content);
    // 메인의 paraphrase 텍스트는 노출되지 않고, 위임의 tool_result 원문이 정확히 1번 relay 된다.
    expect(texts).toEqual([delegateResultText]);
    expect(events.some((e) => e.type === 'done')).toBe(true);
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

  it('CLI-AUTH-ERR-TEXT: 일반 assistant text 블록으로 인증 실패 문구가 오고 세션이 success 로 끝나는 경우도 한국어 안내로 치환 (#410 회귀)', async () => {
    // 크로스체크 재현: subtype=error* 경로가 아니라 assistant text 블록으로
    // "Failed to authenticate..." 가 오고, result.subtype 은 success(=done) 로 끝난다.
    const lines = [
      JSON.stringify({
        type: 'assistant',
        message: {
          content: [
            { type: 'text', text: 'Failed to authenticate. API Error: 401 OAuth access token is invalid.' },
          ],
        },
      }),
      JSON.stringify({ type: 'result', subtype: 'success', session_id: 's', usage: { input_tokens: 1, output_tokens: 1 } }),
    ];
    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);
    const events: Array<{ type: string; message?: string; content?: string }> = [];
    for await (const e of executeCliAgent({
      message: 'hi',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(e as { type: string; message?: string; content?: string });
    }
    // 원문 영문 문구가 'text' 이벤트로도, 다른 어떤 이벤트로도 노출되면 안 된다.
    const leaked = events.some(
      (e) =>
        (typeof e.content === 'string' && /oauth access token is invalid/i.test(e.content)) ||
        (typeof e.message === 'string' && /oauth access token is invalid/i.test(e.message)),
    );
    expect(leaked).toBe(false);
    const errs = events.filter((e) => e.type === 'error');
    expect(errs.length).toBeGreaterThanOrEqual(1);
    expect(String(errs[0].message)).toContain('인증이 만료');
  });

  it('CLI-AUTH-ERR-DELTA: stream_event text_delta 로 인증 실패 문구가 조각나 오는 경우도 한국어 안내로 치환 (#410 회귀)', async () => {
    const lines = [
      JSON.stringify({ type: 'stream_event', delta: { type: 'text_delta', text: 'Failed to authenticate. ' } }),
      JSON.stringify({ type: 'stream_event', delta: { type: 'text_delta', text: 'Please run /login' } }),
      JSON.stringify({ type: 'result', subtype: 'success', session_id: 's', usage: { input_tokens: 1, output_tokens: 1 } }),
    ];
    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);
    const events: Array<{ type: string; message?: string; content?: string }> = [];
    for await (const e of executeCliAgent({
      message: 'hi',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(e as { type: string; message?: string; content?: string });
    }
    const textEvents = events.filter((e) => e.type === 'text');
    expect(textEvents.length).toBe(0);
    const errs = events.filter((e) => e.type === 'error');
    expect(errs.length).toBeGreaterThanOrEqual(1);
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

describe('executeCliAgent — #578 위임 narration 가드(코드 레벨 백스톱)', () => {
  beforeEach(() => {
    spawnMock.mockReset();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  async function run(lines: string[]) {
    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);
    const events: Array<{ type: string; content?: string; toolName?: string }> = [];
    for await (const ev of executeCliAgent({
      message: '트리거 바꿔줘',
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(ev as { type: string; content?: string; toolName?: string });
    }
    return events;
  }

  // trig-010 실측 시퀀스: 메인 text(코드명 노출) → Agent(비동기) → 메인 text(위임 예고) →
  // subagent 내부 tool_use → subagent 확인 질문. 사용자에게는 subagent 확인 질문 하나만 남아야 한다.
  it('trig-010: 코드명 노출 텍스트와 위임 직후 예고 텍스트를 억제하고 subagent 텍스트만 남긴다', async () => {
    const delegateId = 'toolu_578_agent';
    const events = await run([
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [
            { type: 'text', text: '트리거 유형 변경은 삭제+재생성이 필요한 작업이라 trigger-manager에게 위임합니다.' },
            { type: 'tool_use', id: delegateId, name: 'Agent', input: { subagent_type: 'pipeline-builder', prompt: '...' } },
          ],
        },
      }),
      JSON.stringify({
        type: 'user',
        parent_tool_use_id: null,
        message: { content: [{ type: 'tool_result', tool_use_id: delegateId, content: 'Async agent launched successfully.' }] },
      }),
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: '트리거 변경 작업을 진행 중입니다. 완료되면 결과를 전달드릴게요.' }] },
      }),
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: delegateId,
        message: { content: [{ type: 'tool_use', id: 'toolu_sub_1', name: 'mcp__firehub__list_triggers', input: { pipelineId: 15 } }] },
      }),
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: delegateId,
        message: { content: [{ type: 'text', text: "'issue233_verify' 트리거(파이프라인 'fatal_fires_filter', ID: 32)를 삭제합니다. 계속할까요? (네 / 아니오)" }] },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ]);
    const texts = events.filter((e) => e.type === 'text').map((e) => e.content);
    expect(texts).toEqual(["'issue233_verify' 트리거(파이프라인 'fatal_fires_filter', ID: 32)를 삭제합니다. 계속할까요? (네 / 아니오)"]);
  });

  // trig-002 실측: 메인이 `Bash("echo noop")` 를 먼저 호출 — 사용자 표시(tool_use/tool_result)에서 숨긴다.
  it('trig-002: 메인의 Bash("echo noop") 호출과 그 결과를 SSE 에서 숨긴다', async () => {
    const events = await run([
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'tool_use', id: 'toolu_noop', name: 'Bash', input: { command: 'echo noop' } }] },
      }),
      JSON.stringify({
        type: 'user',
        parent_tool_use_id: null,
        message: { content: [{ type: 'tool_result', tool_use_id: 'toolu_noop', content: 'noop' }] },
      }),
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'tool_use', id: 'toolu_lp', name: 'mcp__firehub__list_pipelines', input: {} }] },
      }),
      JSON.stringify({
        type: 'user',
        parent_tool_use_id: null,
        message: { content: [{ type: 'tool_result', tool_use_id: 'toolu_lp', content: '[]' }] },
      }),
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: '파이프라인이 없습니다.' }] },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ]);
    expect(events.filter((e) => e.type === 'tool_use').map((e) => e.toolName)).toEqual(['mcp__firehub__list_pipelines']);
    expect(events.filter((e) => e.type === 'tool_result')).toHaveLength(1);
    expect(events.filter((e) => e.type === 'text').map((e) => e.content)).toEqual(['파이프라인이 없습니다.']);
  });

  // trig-002 2차 실측(프롬프트 보강 후에도 재발): 조사 tool_result 와 Agent 사이의 영어 독백
  // "Found pipeline ID 18. Delegating trigger creation." — Agent 보다 먼저 도착하므로 다음 이벤트로 판정한다.
  it('trig-002: 조사 도구 → 텍스트 → Agent 위임 흐름에서 위임 직전 텍스트를 억제한다', async () => {
    const delegateId = 'toolu_578_pre';
    const events = await run([
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'tool_use', id: 'toolu_lp', name: 'mcp__firehub__list_pipelines', input: {} }] },
      }),
      JSON.stringify({
        type: 'user',
        parent_tool_use_id: null,
        message: { content: [{ type: 'tool_result', tool_use_id: 'toolu_lp', content: '[{"id":18}]' }] },
      }),
      // 텍스트와 Agent 가 별도 assistant 메시지로 오는 경우
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: 'Found pipeline ID 18. Delegating trigger creation.' }] },
      }),
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'tool_use', id: delegateId, name: 'Agent', input: { subagent_type: 'pipeline-builder', prompt: '...' } }] },
      }),
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: delegateId,
        message: { content: [{ type: 'text', text: "'주간 검증' 트리거가 등록되었습니다 (ID: 53)." }] },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ]);
    expect(events.filter((e) => e.type === 'text').map((e) => e.content)).toEqual(["'주간 검증' 트리거가 등록되었습니다 (ID: 53)."]);
    // 위임 직전 텍스트를 버렸어도 tool_use 이벤트 순서는 유지된다
    expect(events.filter((e) => e.type === 'tool_use').map((e) => e.toolName)).toEqual(['mcp__firehub__list_pipelines', 'Agent']);
  });

  // 라이브 raw stream-json 실측: 텍스트 assistant 메시지 뒤에 content_block_stop/message_delta/message_stop/
  // message_start 같은 stream_event 가 여러 줄 따라온 뒤에야 Agent tool_use 가 온다 — 이 줄들에서 flush 하면
  // 가드가 무력화된다(1차 구현의 실제 실패 원인). stream_event 는 보류를 유지해야 한다.
  it('텍스트와 Agent 사이에 stream_event 줄이 끼어도 위임 직전 텍스트를 억제한다', async () => {
    const delegateId = 'toolu_578_se';
    const se = (event: Record<string, unknown>) => JSON.stringify({ type: 'stream_event', parent_tool_use_id: null, event });
    const events = await run([
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: 'Pipeline ID 18 확인했습니다. 트리거 생성을 위임할게요.' }] },
      }),
      se({ type: 'content_block_stop', index: 0 }),
      se({ type: 'message_delta', delta: { stop_reason: 'tool_use' } }),
      se({ type: 'message_stop' }),
      JSON.stringify({ type: 'rate_limit_event', rate_limit_info: { status: 'allowed' } }),
      se({ type: 'message_start', message: { id: 'msg_2' } }),
      se({ type: 'content_block_start', index: 0, content_block: { type: 'tool_use', id: delegateId, name: 'Agent' } }),
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'tool_use', id: delegateId, name: 'Agent', input: { subagent_type: 'pipeline-builder', prompt: '...' } }] },
      }),
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: delegateId,
        message: { content: [{ type: 'text', text: '설계안입니다. 이대로 생성할까요?' }] },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ]);
    expect(events.filter((e) => e.type === 'text').map((e) => e.content)).toEqual(['설계안입니다. 이대로 생성할까요?']);
  });

  it('텍스트 뒤 stream_event 를 지나 일반 도구 content_block_start 가 오면 그 앞에 텍스트를 내보낸다', async () => {
    const se = (event: Record<string, unknown>) => JSON.stringify({ type: 'stream_event', parent_tool_use_id: null, event });
    const events = await run([
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: '트리거 목록을 불러올게요' }] },
      }),
      se({ type: 'content_block_stop', index: 0 }),
      se({ type: 'content_block_start', index: 1, content_block: { type: 'tool_use', id: 'toolu_lt', name: 'mcp__firehub__list_triggers' } }),
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'tool_use', id: 'toolu_lt', name: 'mcp__firehub__list_triggers', input: { pipelineId: 15 } }] },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ]);
    expect(events.map((e) => e.type).filter((t) => t !== 'init')).toEqual(['text', 'tool_use', 'done']);
  });

  // 최종 답변(뒤에 result 만 오는 텍스트)은 보류됐다가 그대로 나가야 한다 — 순서·내용 보존.
  it('최종 답변 텍스트는 보류 후 done 앞에 그대로 내보낸다', async () => {
    const events = await run([
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: '최종 답변입니다.' }] },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ]);
    expect(events.map((e) => e.type).filter((t) => t !== 'init')).toEqual(['text', 'done']);
    expect(events.find((e) => e.type === 'text')?.content).toBe('최종 답변입니다.');
  });

  // 허용 status(#260)와 최종 응답은 건드리지 않는다 — 과억제 회귀 방지.
  it('위임이 없는 요청의 허용 status 와 최종 응답은 그대로 통과한다', async () => {
    const events = await run([
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [
            { type: 'text', text: '트리거 목록을 불러올게요' },
            { type: 'tool_use', id: 'toolu_lt', name: 'mcp__firehub__list_triggers', input: { pipelineId: 15 } },
          ],
        },
      }),
      JSON.stringify({
        type: 'user',
        parent_tool_use_id: null,
        message: { content: [{ type: 'tool_result', tool_use_id: 'toolu_lt', content: '[]' }] },
      }),
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: '파이프라인 15번에는 트리거가 없습니다.' }] },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ]);
    expect(events.filter((e) => e.type === 'text').map((e) => e.content)).toEqual([
      '트리거 목록을 불러올게요',
      '파이프라인 15번에는 트리거가 없습니다.',
    ]);
  });

  // 억제 후 subagent 가 아무 텍스트도 내지 않으면 빈 응답 대신 코드명을 가린 fallback 을 내보낸다.
  it('억제된 텍스트 외에 아무 텍스트도 나가지 않으면 코드명을 가린 fallback 을 내보낸다(빈 응답 방지)', async () => {
    const events = await run([
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [
            { type: 'text', text: 'trigger-manager에게 위임합니다.' },
            { type: 'tool_use', id: 'toolu_a', name: 'Agent', input: { subagent_type: 'pipeline-builder', prompt: '...' } },
          ],
        },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ]);
    const texts = events.filter((e) => e.type === 'text').map((e) => e.content);
    // #581: fallback 도 사용자 대상 메인 텍스트이므로 코드명에 더해 라우팅 어휘('위임')까지 가려진다.
    expect(texts).toEqual(['전문 에이전트에게 처리합니다.']);
  });

  // 위임 자체가 실패(is_error)하면 메인이 직접 설명해야 하므로 억제하지 않는다.
  it('Agent 위임 tool_result 가 is_error 면 이후 메인 텍스트를 억제하지 않는다', async () => {
    const events = await run([
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'tool_use', id: 'toolu_a', name: 'Agent', input: { subagent_type: 'pipeline-builder', prompt: '...' } }] },
      }),
      JSON.stringify({
        type: 'user',
        parent_tool_use_id: null,
        message: { content: [{ type: 'tool_result', tool_use_id: 'toolu_a', is_error: true, content: 'Agent failed' }] },
      }),
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: '처리 중 오류가 발생했습니다. 다시 시도해 주세요.' }] },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ]);
    expect(events.filter((e) => e.type === 'text').map((e) => e.content)).toEqual(['처리 중 오류가 발생했습니다. 다시 시도해 주세요.']);
  });
});

// #581: CLI 경로 — 도구 호출이 전혀 없는 되묻기 턴(inspector crosscheck-578-trig-009 실측)의 라우팅 어휘를
// 치환한다. #578 가드(코드명/비동기 위임 직후)는 이 턴을 구조상 잡지 못하므로 별도 회귀 케이스로 고정.
describe('executeCliAgent — #581 되묻기 턴 라우팅 어휘 치환', () => {
  beforeEach(() => {
    spawnMock.mockReset();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  async function run(lines: string[], message = '트리거 32번 삭제해줘') {
    const child = makeFakeChildWithLines(lines);
    spawnMock.mockReturnValue(child);
    const events: Array<{ type: string; content?: string }> = [];
    for await (const ev of executeCliAgent({
      message,
      tenantId: 1,
      userId: 1,
      useSubscription: false,
      apiKey: 'sk-test',
    } as never)) {
      events.push(ev as { type: string; content?: string });
    }
    return events;
  }

  it('trig-009: tool_use 0건 되묻기 델타의 "위임하되"가 경계에 걸쳐 와도 치환되고 문장은 보존된다', async () => {
    const events = await run([
      JSON.stringify({ type: 'stream_event', delta: { type: 'text_delta', text: '트리거 삭제(파괴 작업)는 위' } }),
      JSON.stringify({ type: 'stream_event', delta: { type: 'text_delta', text: '임하되, 먼저 어느 파이프라인 소속인지 알려주시겠어요?' } }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ]);
    const text = events.filter((e) => e.type === 'text').map((e) => e.content).join('');
    expect(text).toBe('트리거 삭제(파괴 작업)는 처리하되, 먼저 어느 파이프라인 소속인지 알려주시겠어요?');
  });

  it('trig-009: 완성 블록으로 온 되묻기도 치환되며 억제(빈 응답)되지 않는다', async () => {
    const events = await run([
      JSON.stringify({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'text', text: '트리거 삭제(파괴 작업)는 위임하되, 먼저 어느 파이프라인 소속인지 알려주시겠어요?' }] },
      }),
      JSON.stringify({ type: 'result', subtype: 'success' }),
    ]);
    expect(events.filter((e) => e.type === 'text').map((e) => e.content)).toEqual([
      '트리거 삭제(파괴 작업)는 처리하되, 먼저 어느 파이프라인 소속인지 알려주시겠어요?',
    ]);
  });

  it('사용자가 "위임"을 직접 쓴 데이터 문맥이면 치환하지 않는다', async () => {
    const events = await run(
      [
        JSON.stringify({
          type: 'assistant',
          parent_tool_use_id: null,
          message: { content: [{ type: 'text', text: '업무 위임 테이블을 만들까요? 컬럼을 알려주세요.' }] },
        }),
        JSON.stringify({ type: 'result', subtype: 'success' }),
      ],
      '업무 위임 테이블 만들어줘',
    );
    expect(events.filter((e) => e.type === 'text').map((e) => e.content)).toEqual(['업무 위임 테이블을 만들까요? 컬럼을 알려주세요.']);
  });
});
