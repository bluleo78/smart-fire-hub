import { describe, it, expect } from 'vitest';
import { parseOpenCodeEvent, buildOpenCodeConfig, buildOpenCodeRunArgs, normalizeFirehubToolName } from './agent-opencode.js';
import { OPENCODE_SYSTEM_PROMPT, SYSTEM_PROMPT } from './system-prompt.js';

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

  it('메인(build) 에서 task 위임을 전면 차단하고 빌트인 general 을 비활성화한다 (#0 보안)', () => {
    // 위임 차단으로 firehub 도구 직접 호출을 강제 — 비격리 general 서브에이전트 누수 방지.
    const cfg = buildOpenCodeConfig(1, 'u', 't');
    expect(cfg.agent.build.permission.task['*']).toBe('deny');
    expect(cfg.agent.general.disable).toBe(true);
  });
});

describe('buildOpenCodeRunArgs', () => {
  it('--dir 로 격리 워크스페이스를 프로젝트 루트로 강제한다 (cwd 의존 제거)', () => {
    // --dir 누락 시 opencode 가 /app(소스)로 앵커돼 워크스페이스 설정이 무시되는 회귀 방지.
    const args = buildOpenCodeRunArgs('안녕', '/home/u/.firehub/workspaces-opencode/1');
    const di = args.indexOf('--dir');
    expect(di).toBeGreaterThan(-1);
    expect(args[di + 1]).toBe('/home/u/.firehub/workspaces-opencode/1');
    expect(args).toContain('--format');
    expect(args).not.toContain('--session');
  });

  it('재개 세션 id 가 있으면 --session 을 덧붙인다', () => {
    const args = buildOpenCodeRunArgs('계속', '/wd', 'ses_abc');
    expect(args.slice(-2)).toEqual(['--session', 'ses_abc']);
  });
});

describe('OPENCODE_SYSTEM_PROMPT', () => {
  it('위임을 금지하고 직접 처리·요약을 지시한다 (가짜 위임 태그 방지)', () => {
    // 위임 라우팅을 그대로 주면 약한 모델이 막힌 위임을 텍스트로 흉내내 응답이 깨진다.
    expect(OPENCODE_SYSTEM_PROMPT).toContain('위임 금지');
    expect(OPENCODE_SYSTEM_PROMPT).toContain('<call:Agent');
    expect(OPENCODE_SYSTEM_PROMPT).toContain('결과 요약 필수');
    // 위임 중심 SYSTEM_PROMPT 의 라우팅 표 헤더가 섞이지 않았는지 (회귀 가드)
    expect(OPENCODE_SYSTEM_PROMPT).not.toContain('전문 에이전트에게 위임하세요');
  });

  it('안전 통제(PII 마스킹·파괴 2턴 확인)를 보존한다', () => {
    // 위임 제거로 안전 규칙이 약한 모델 메인에 얹히므로 프롬프트에 반드시 잔류해야 한다.
    expect(OPENCODE_SYSTEM_PROMPT).toContain('PII 마스킹');
    expect(OPENCODE_SYSTEM_PROMPT).toContain('파괴 작업 2턴 확인');
    expect(OPENCODE_SYSTEM_PROMPT).toContain('get_dataset_references');
  });

  // GraphRAG 조회는 opencode 경로에도 필요하지만, 구축·검수 결정은 다단계 가드 흐름이라
  // 약한 모델에서 깨진다 — 읽기만 안내하고 쓰기는 UI 로 보내는 경계를 고정한다.
  it('GraphRAG 는 읽기 전용으로만 안내하고 구축·검수 결정 도구는 배제한다', () => {
    // `firehub_` 접두 표기는 opencode 경로 전용 — 이 needle 이 SYSTEM_PROMPT 에도 있으면
    // 블록이 엉뚱한 템플릿에 들어간 것이다(두 프롬프트 모두 'graphrag_query' 는 포함하므로 판별 불가).
    expect(OPENCODE_SYSTEM_PROMPT).toContain('firehub_graphrag_query');
    expect(SYSTEM_PROMPT).not.toContain('firehub_graphrag_query');
    expect(OPENCODE_SYSTEM_PROMPT).toContain('firehub_graphrag_structured_query');
    expect(OPENCODE_SYSTEM_PROMPT).toContain('firehub_graphrag_describe_ontology');
    // 구축·검수 결정 도구는 사용 안내가 없어야 한다
    expect(OPENCODE_SYSTEM_PROMPT).not.toContain('graphrag_approve_review_item');
    expect(OPENCODE_SYSTEM_PROMPT).not.toContain('graphrag_activate_mapping');
    expect(OPENCODE_SYSTEM_PROMPT).not.toContain('graphrag_project_table');
    // 근거 인용·환각 금지 규칙 동반
    expect(OPENCODE_SYSTEM_PROMPT).toMatch(/sourceChunks/);
    expect(OPENCODE_SYSTEM_PROMPT).toMatch(/환각/);
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
    // 프론트엔드 위젯 매칭을 위해 mcp__firehub__ 형식으로 정규화돼야 한다.
    expect(evs[0]).toEqual({ type: 'tool_use', toolName: 'mcp__firehub__list_categories', input: { a: 1 } });
    expect(evs[1]).toEqual({ type: 'tool_result', toolName: 'mcp__firehub__list_categories', result: 'result' });
  });

  it('firehub_ 도구명을 mcp__firehub__ 로 정규화한다 (위젯 렌더링 계약)', () => {
    // 프론트 WidgetRegistry 는 mcp__firehub__ 접두사만 벗겨 show_chart 등으로 매칭한다.
    expect(normalizeFirehubToolName('firehub_show_chart')).toBe('mcp__firehub__show_chart');
    expect(normalizeFirehubToolName('firehub_execute_analytics_query')).toBe('mcp__firehub__execute_analytics_query');
    // 접두사 1회만 치환 (도구명 내부 underscore 보존)
    expect(normalizeFirehubToolName('firehub_get_data_schema')).toBe('mcp__firehub__get_data_schema');
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
