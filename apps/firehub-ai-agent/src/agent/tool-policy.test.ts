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
    // #262: 파일 첨부 처리용 Read/Bash 허용
    expect(ALLOWED_TOOLS).toContain('Read');
    expect(ALLOWED_TOOLS).toContain('Bash');

    // skill/task ecosystem 명시 차단
    for (const t of ['Skill', 'TaskCreate', 'TaskUpdate', 'TaskList', 'TaskGet', 'TaskStop']) {
      expect(DISALLOWED_TOOLS).toContain(t);
    }
    // host IO/shell 일부 명시 차단 (Read/Bash 는 #262 로 ALLOWED 이동)
    for (const t of ['Write', 'Edit', 'Glob', 'Grep']) {
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

  it('host filesystem 쓰기 도구(Write/Edit/NotebookEdit/Glob/Grep/LS)는 차단된다', () => {
    expect(checkToolPolicy('Write')).toMatch(/blocked/);
    expect(checkToolPolicy('Edit')).toMatch(/blocked/);
    expect(checkToolPolicy('NotebookEdit')).toMatch(/blocked/);
    expect(checkToolPolicy('Glob')).toMatch(/blocked/);
    expect(checkToolPolicy('Grep')).toMatch(/blocked/);
    expect(checkToolPolicy('LS')).toMatch(/blocked/);
  });

  // #262: Read + Bash 는 파일 첨부 처리용으로 허용 — FILE_ATTACHMENT_PROMPT 가
  // 첨부 파일 경로 한정 사용을 지시하며, fileIds 없는 요청에는 가이드 자체가 첨부되지 않음.
  it('Read 와 Bash 는 파일 첨부 처리용으로 허용된다 (#262)', () => {
    expect(checkToolPolicy('Read')).toBeNull();
    expect(checkToolPolicy('Bash')).toBeNull();
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
