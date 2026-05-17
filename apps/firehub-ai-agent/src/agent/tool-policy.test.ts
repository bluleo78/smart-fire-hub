/**
 * tool-policy.ts 단위 테스트 (#256)
 *
 * SDK/CLI 옵션이 어떤 이유로 무력화돼도 런타임 in-flight 검사가 host 도구를 차단해야 한다.
 */
import { describe, it, expect } from 'vitest';
import { ALLOWED_TOOLS, DISALLOWED_TOOLS, checkToolPolicy } from './tool-policy.js';

describe('tool-policy (#256)', () => {
  it('ALLOWED_TOOLS 와 DISALLOWED_TOOLS 의 명시 핵심 도구가 유지된다 (회귀 가드)', () => {
    expect(ALLOWED_TOOLS).toContain('mcp__firehub__*');
    expect(ALLOWED_TOOLS).toContain('Agent');

    // skill/task ecosystem 명시 차단
    for (const t of ['Skill', 'TaskCreate', 'TaskUpdate', 'TaskList', 'TaskGet', 'TaskStop']) {
      expect(DISALLOWED_TOOLS).toContain(t);
    }
    // host IO/shell 명시 차단
    for (const t of ['Read', 'Write', 'Edit', 'Bash', 'Glob', 'Grep']) {
      expect(DISALLOWED_TOOLS).toContain(t);
    }
    // 외부 네트워크 차단
    for (const t of ['WebFetch', 'WebSearch']) {
      expect(DISALLOWED_TOOLS).toContain(t);
    }
  });

  it('firehub MCP 도구는 허용된다', () => {
    expect(checkToolPolicy('mcp__firehub__list_datasets')).toBeNull();
    expect(checkToolPolicy('mcp__firehub__run_pipeline')).toBeNull();
    expect(checkToolPolicy('Agent')).toBeNull();
  });

  it('host skill/task 도구는 차단된다', () => {
    expect(checkToolPolicy('Skill')).toMatch(/blocked/);
    expect(checkToolPolicy('TaskCreate')).toMatch(/blocked/);
    expect(checkToolPolicy('TaskUpdate')).toMatch(/blocked/);
    expect(checkToolPolicy('TaskList')).toMatch(/blocked/);
  });

  it('host filesystem / shell 도구는 차단된다', () => {
    expect(checkToolPolicy('Read')).toMatch(/blocked/);
    expect(checkToolPolicy('Write')).toMatch(/blocked/);
    expect(checkToolPolicy('Bash')).toMatch(/blocked/);
    expect(checkToolPolicy('Edit')).toMatch(/blocked/);
  });

  it('외부 네트워크 도구는 차단된다', () => {
    expect(checkToolPolicy('WebFetch')).toMatch(/blocked/);
    expect(checkToolPolicy('WebSearch')).toMatch(/blocked/);
  });

  it('meta-search 도구(ToolSearch / mcp__claude-search__*)는 차단된다', () => {
    expect(checkToolPolicy('ToolSearch')).toMatch(/blocked/);
    expect(checkToolPolicy('mcp__claude-search__find')).toMatch(/blocked/);
  });

  it('allow list 외 알 수 없는 도구는 deny-by-default', () => {
    const result = checkToolPolicy('SomeUnknownHostTool');
    expect(result).not.toBeNull();
    expect(result).toMatch(/not in allow list/);
  });

  it('빈 도구명은 null (파싱 노이즈 — 상위에서 처리)', () => {
    expect(checkToolPolicy('')).toBeNull();
  });
});
