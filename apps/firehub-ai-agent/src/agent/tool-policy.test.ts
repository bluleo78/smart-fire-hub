/**
 * tool-policy.ts 단위 테스트 (#256, #266)
 *
 * #266: deny-by-default → allow-by-default 로 정책 모델을 뒤집음. 운영/로컬 동일 정책으로
 * 위험 도구(호스트 파일 변조 / skill·task ecosystem / meta-search) 만 명시 차단하고,
 * 새 호스트 도구는 자동 허용되어 "tool not in allow list" 무응답 회귀를 막는다.
 */
import { describe, it, expect } from 'vitest';
import { DISALLOWED_TOOLS, ALLOWED_TOOLS, checkToolPolicy } from './tool-policy.js';

describe('tool-policy (#256, #266)', () => {
  it('DISALLOWED_TOOLS 의 명시 차단 도구가 유지된다 (회귀 가드)', () => {
    // 호스트 파일 변조
    for (const t of ['Write', 'Edit', 'NotebookEdit']) {
      expect(DISALLOWED_TOOLS).toContain(t);
    }
    // skill/task ecosystem
    for (const t of ['Skill', 'TaskCreate', 'TaskUpdate', 'TaskList', 'TaskGet', 'TaskStop', 'TaskOutput']) {
      expect(DISALLOWED_TOOLS).toContain(t);
    }
    // meta-search 우회 (#216)
    for (const t of ['ToolSearch', 'mcp__claude-search__*']) {
      expect(DISALLOWED_TOOLS).toContain(t);
    }
  });

  it('ALLOWED_TOOLS (옵션 양수 화이트리스트 보조) 에 핵심 도구가 포함된다', () => {
    expect(ALLOWED_TOOLS).toContain('mcp__firehub__*');
    expect(ALLOWED_TOOLS).toContain('Agent');
  });

  it('firehub MCP 도구와 Agent 위임은 허용된다', () => {
    expect(checkToolPolicy('mcp__firehub__list_datasets')).toBeNull();
    expect(checkToolPolicy('mcp__firehub__run_pipeline')).toBeNull();
    expect(checkToolPolicy('Agent')).toBeNull();
  });

  it('호스트 파일 변조 도구(Write/Edit/NotebookEdit)는 차단된다', () => {
    expect(checkToolPolicy('Write')).toMatch(/blocked/);
    expect(checkToolPolicy('Edit')).toMatch(/blocked/);
    expect(checkToolPolicy('NotebookEdit')).toMatch(/blocked/);
  });

  it('host skill/task ecosystem 은 차단된다', () => {
    expect(checkToolPolicy('Skill')).toMatch(/blocked/);
    for (const t of ['TaskCreate', 'TaskUpdate', 'TaskList', 'TaskGet', 'TaskStop', 'TaskOutput']) {
      expect(checkToolPolicy(t)).toMatch(/blocked/);
    }
  });

  it('meta-search 도구(ToolSearch / mcp__claude-search__*)는 차단된다', () => {
    expect(checkToolPolicy('ToolSearch')).toMatch(/blocked/);
    expect(checkToolPolicy('mcp__claude-search__find')).toMatch(/blocked/);
  });

  it('채팅 UX 도구(AskUserQuestion 등)는 허용된다 (#266)', () => {
    expect(checkToolPolicy('AskUserQuestion')).toBeNull();
    expect(checkToolPolicy('TodoWrite')).toBeNull();
    expect(checkToolPolicy('ExitPlanMode')).toBeNull();
  });

  it('파일 첨부 처리 도구(Read/Bash/Glob/Grep/LS)는 허용된다 (#262, #266)', () => {
    expect(checkToolPolicy('Read')).toBeNull();
    expect(checkToolPolicy('Bash')).toBeNull();
    expect(checkToolPolicy('Glob')).toBeNull();
    expect(checkToolPolicy('Grep')).toBeNull();
    expect(checkToolPolicy('LS')).toBeNull();
  });

  it('외부 정보 조회 도구(WebFetch/WebSearch)는 허용된다 (#266 — 사용자 결정)', () => {
    expect(checkToolPolicy('WebFetch')).toBeNull();
    expect(checkToolPolicy('WebSearch')).toBeNull();
  });

  // #266: 신규 호스트 도구가 추가돼도 자동 허용 — 이전 deny-by-default 모델에서 발생하던
  // "tool not in allow list (#256): X — killing child" 무응답 회귀 영구 차단
  it('차단 목록에 없는 임의 신규 도구는 자동 허용된다 (allow-by-default)', () => {
    expect(checkToolPolicy('SomeFutureHostTool')).toBeNull();
    expect(checkToolPolicy('NewExperimentalTool')).toBeNull();
  });

  it('빈 도구명은 null (파싱 노이즈 — 상위에서 처리)', () => {
    expect(checkToolPolicy('')).toBeNull();
  });

  // #276: Agent 위임 라우팅 백스톱 — subagent_type 화이트리스트 강제
  describe('Agent subagent_type 백스톱 (#276)', () => {
    const WHITELIST = ['data-analyst', 'dataset-manager', 'trigger-manager'] as const;

    it('정의된 subagent 타입은 허용된다', () => {
      expect(checkToolPolicy('Agent', { subagent_type: 'data-analyst' }, WHITELIST)).toBeNull();
      expect(checkToolPolicy('Agent', { subagent_type: 'dataset-manager' }, WHITELIST)).toBeNull();
    });

    it('general-purpose 위임은 화이트리스트 모드에서 차단된다', () => {
      const r = checkToolPolicy('Agent', { subagent_type: 'general-purpose' }, WHITELIST);
      expect(r).toMatch(/blocked by policy \(#276\)/);
      expect(r).toContain('general-purpose');
    });

    it('표에 없는 임의 타입은 차단된다', () => {
      expect(
        checkToolPolicy('Agent', { subagent_type: 'researcher' }, WHITELIST),
      ).toMatch(/blocked by policy \(#276\)/);
    });

    it('subagent_type 미지정(타입 생략 우회)도 화이트리스트 모드에서 차단된다', () => {
      // 호스트가 general-purpose 로 폴백하므로 빈 타입도 막는다
      expect(checkToolPolicy('Agent', {}, WHITELIST)).toMatch(/blocked by policy \(#276\)/);
    });

    it('공백 패딩된 타입도 trim 후 판정된다', () => {
      expect(checkToolPolicy('Agent', { subagent_type: '  data-analyst  ' }, WHITELIST)).toBeNull();
      expect(
        checkToolPolicy('Agent', { subagent_type: ' general-purpose ' }, WHITELIST),
      ).toMatch(/blocked/);
    });

    it('화이트리스트 미제공 시에도 general-purpose 만은 차단된다 (방어 하한선)', () => {
      expect(checkToolPolicy('Agent', { subagent_type: 'general-purpose' })).toMatch(/blocked/);
      // 그 외 타입은 화이트리스트 없으면 통과 (정보 부족 시 관대)
      expect(checkToolPolicy('Agent', { subagent_type: 'data-analyst' })).toBeNull();
    });

    it('input 미전달 시 Agent 는 허용된다 (BC — 호출부가 input 안 넘기는 경로)', () => {
      expect(checkToolPolicy('Agent')).toBeNull();
    });

    it('차단 메시지에 전체 에이전트 roster(코드명)를 노출하지 않는다 (L2 준수, user-facing SSE)', () => {
      const r = checkToolPolicy('Agent', { subagent_type: 'general-purpose' }, WHITELIST) ?? '';
      // 시도된 타입(general-purpose)만 진단용 포함, 다른 정의된 에이전트명은 누출 금지
      expect(r).not.toContain('dataset-manager');
      expect(r).not.toContain('trigger-manager');
      expect(r).not.toContain('data-analyst');
    });

    it('Agent 가 아닌 도구의 subagent_type 필드는 무시된다', () => {
      expect(
        checkToolPolicy('mcp__firehub__list_datasets', { subagent_type: 'general-purpose' }, WHITELIST),
      ).toBeNull();
    });
  });
});
