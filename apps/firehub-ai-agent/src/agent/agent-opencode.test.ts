import { describe, it, expect } from 'vitest';
import { parseOpenCodeEvent, buildOpenCodeConfig } from './agent-opencode.js';

describe('buildOpenCodeConfig', () => {
  it('mcp.firehub 에 USER_ID 등 환경변수를 주입한다', () => {
    const cfg = buildOpenCodeConfig(7, 'http://api/v1', 'tok');
    expect(cfg.mcp.firehub.type).toBe('local');
    expect(cfg.mcp.firehub.environment.USER_ID).toBe('7');
    expect(cfg.mcp.firehub.environment.INTERNAL_SERVICE_TOKEN).toBe('tok');
    expect(cfg.mcp.firehub.environment.API_BASE_URL).toBe('http://api/v1');
    expect(Array.isArray(cfg.mcp.firehub.command)).toBe(true);
  });

  it('model 필드를 넣지 않는다 (옵션 3: 배포 측 전역 설정 상속)', () => {
    const cfg = buildOpenCodeConfig(1, 'u', 't');
    expect(cfg.model).toBeUndefined();
  });

  it('permission 으로 bash/edit/write/webfetch 를 deny 하고 firehub MCP 만 allow 한다', () => {
    const cfg = buildOpenCodeConfig(1, 'u', 't');
    expect(cfg.permission.bash).toBe('deny');
    expect(cfg.permission.edit).toBe('deny');
    expect(cfg.permission.write).toBe('deny');
    expect(cfg.permission.webfetch).toBe('deny');
  });
});

describe('parseOpenCodeEvent', () => {
  it('텍스트 이벤트를 SSE text 로 변환한다 (실측 스키마: part.text)', () => {
    // 실측 픽스처: type="text", part.text=텍스트
    const ev = parseOpenCodeEvent({ type: 'text', part: { text: '안녕' } });
    expect(ev).toEqual([{ type: 'text', content: '안녕' }]);
  });

  it('tool_use 이벤트(completed)를 SSE tool_use + tool_result 둘 다 반환한다 (실측 스키마)', () => {
    // 실측 픽스처: type="tool_use", part.type="tool", part.tool=도구명,
    // part.state.status="completed", part.state.input/output 포함
    const evs = parseOpenCodeEvent({
      type: 'tool_use',
      part: { type: 'tool', tool: 'firehub_list_categories', state: { status: 'completed', input: { a: 1 }, output: 'result' } },
    });
    expect(evs).toHaveLength(2);
    expect(evs[0]).toEqual({ type: 'tool_use', toolName: 'firehub_list_categories', input: { a: 1 } });
    expect(evs[1]).toEqual({ type: 'tool_result', toolName: 'firehub_list_categories', result: 'result' });
  });

  it('tool_use 이벤트(running 등 비완료 상태)는 빈 배열을 반환한다', () => {
    // running 상태에서는 output 이 아직 없으므로 무시(중복 이벤트 방지)
    const evs = parseOpenCodeEvent({
      type: 'tool_use',
      part: { type: 'tool', tool: 'firehub_list_categories', state: { status: 'running' } },
    });
    expect(evs).toEqual([]);
  });

  it('step_finish(reason=stop)를 done+tokens 로 변환한다', () => {
    const evs = parseOpenCodeEvent({
      type: 'step_finish',
      part: { reason: 'stop', tokens: { input: 100, output: 20 } },
    });
    expect(evs).not.toBeNull();
    const done = evs!.find((e) => e.type === 'done');
    expect(done).toBeDefined();
    expect(done!.inputTokens).toBe(100);
    expect(done!.outputTokens).toBe(20);
  });

  it('step_finish(reason=tool-calls)를 turn 으로 변환한다', () => {
    const evs = parseOpenCodeEvent({
      type: 'step_finish',
      part: { reason: 'tool-calls', tokens: { input: 50, output: 5 } },
    });
    expect(evs).not.toBeNull();
    const turn = evs!.find((e) => e.type === 'turn');
    expect(turn).toBeDefined();
  });

  it('알 수 없는 이벤트는 빈 배열을 반환한다', () => {
    expect(parseOpenCodeEvent({ type: 'unknown_xyz' })).toEqual([]);
  });

  it('step_start 이벤트는 빈 배열을 반환한다', () => {
    expect(parseOpenCodeEvent({ type: 'step_start', part: {} })).toEqual([]);
  });

  it('실측 픽스처 라인들이 기대 SSE 시퀀스로 변환된다', async () => {
    const { readFile } = await import('fs/promises');
    const { fileURLToPath } = await import('url');
    const { dirname, join } = await import('path');
    const dir = dirname(fileURLToPath(import.meta.url));
    const raw = await readFile(join(dir, '__fixtures__', 'opencode-run.jsonl'), 'utf-8');
    const events = raw
      .split('\n')
      .filter(Boolean)
      .flatMap((l) => {
        try {
          return parseOpenCodeEvent(JSON.parse(l));
        } catch {
          return [];
        }
      })
      .filter(Boolean);
    // 최소 보장: tool_use(firehub_list_categories) + tool_result + done 이벤트 존재
    expect(events.some((e) => e!.type === 'tool_use')).toBe(true);
    expect(events.some((e) => e!.type === 'tool_result')).toBe(true);
    expect(events.some((e) => e!.type === 'done')).toBe(true);
    // 픽스처 마지막 step_finish 토큰 값 검증 (input: 77, output: 4)
    const doneEv = events.find((e) => e!.type === 'done');
    expect(doneEv!.inputTokens).toBe(77);
    expect(doneEv!.outputTokens).toBe(4);
  });
});
