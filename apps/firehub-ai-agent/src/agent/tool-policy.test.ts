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
});
