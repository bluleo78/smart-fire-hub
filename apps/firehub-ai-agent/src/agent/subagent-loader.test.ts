import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { loadSubagents, buildSubagentGuide, resetSubagentCache } from './subagent-loader.js';
import type { AgentDefinition } from '@anthropic-ai/claude-agent-sdk';

// Helpers

function makeTempDir(): string {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'subagent-loader-test-'));
}

function writeFile(dir: string, name: string, content: string): void {
  fs.writeFileSync(path.join(dir, name), content, 'utf-8');
}

function makeSubagentDir(root: string, agentName: string, agentMd: string, extras?: Record<string, string>): string {
  const agentDir = path.join(root, agentName);
  fs.mkdirSync(agentDir, { recursive: true });
  writeFile(agentDir, 'agent.md', agentMd);
  if (extras) {
    for (const [filename, content] of Object.entries(extras)) {
      writeFile(agentDir, filename, content);
    }
  }
  return agentDir;
}

const BASIC_AGENT_MD = `---
name: test-agent
description: "A test agent"
---

This is the agent prompt body.
`;

// Suppress console output for most tests
beforeEach(() => {
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  vi.spyOn(console, 'error').mockImplementation(() => {});
  vi.spyOn(console, 'log').mockImplementation(() => {});
});

afterEach(() => {
  vi.restoreAllMocks();
});

// ── SL-01: Frontmatter parsing ────────────────────────────────────────────────

describe('SL-01: frontmatter parsing — name and description', () => {
  it('parses name and description correctly', () => {
    const root = makeTempDir();
    makeSubagentDir(root, 'my-agent', BASIC_AGENT_MD);

    const agents = loadSubagents(root);

    expect(agents['my-agent']).toBeDefined();
    expect(agents['my-agent'].description).toBe('A test agent');
    expect(agents['my-agent'].prompt).toContain('This is the agent prompt body.');
  });
});

// ── SL-02: Frontmatter parsing — tools ────────────────────────────────────────

describe('SL-02: frontmatter parsing — tools', () => {
  it('parses tools as string array', () => {
    const root = makeTempDir();
    const md = `---
name: tool-agent
description: "Agent with tools"
tools:
  - mcp__firehub__*
  - Read
  - Grep
---

Body.
`;
    makeSubagentDir(root, 'tool-agent', md);

    const agents = loadSubagents(root);

    expect(agents['tool-agent'].tools).toEqual(['mcp__firehub__*', 'Read', 'Grep']);
  });
});

// ── SL-03: Frontmatter parsing — model ───────────────────────────────────────

describe('SL-03: frontmatter parsing — model', () => {
  it('parses model field when it is not "inherit"', () => {
    const root = makeTempDir();
    const md = `---
name: model-agent
description: "Agent with model"
model: sonnet
---

Body.
`;
    makeSubagentDir(root, 'model-agent', md);

    const agents = loadSubagents(root);

    expect(agents['model-agent'].model).toBe('sonnet');
  });

  it('omits model field when model is "inherit"', () => {
    const root = makeTempDir();
    const md = `---
name: inherit-model-agent
description: "Agent with inherit model"
model: inherit
---

Body.
`;
    makeSubagentDir(root, 'inherit-model-agent', md);

    const agents = loadSubagents(root);

    expect(agents['inherit-model-agent'].model).toBeUndefined();
  });
});

// ── SL-04: Frontmatter parsing — mcpServers ──────────────────────────────────

describe('SL-04: frontmatter parsing — mcpServers', () => {
  it('parses mcpServers and converts to AgentMcpServerSpec array', () => {
    const root = makeTempDir();
    const md = `---
name: mcp-agent
description: "Agent with mcp servers"
mcpServers:
  - firehub
  - other
---

Body.
`;
    makeSubagentDir(root, 'mcp-agent', md);

    const agents = loadSubagents(root);

    expect(agents['mcp-agent'].mcpServers).toEqual(['firehub', 'other']);
  });
});

// ── SL-05: Frontmatter parsing — maxTurns ────────────────────────────────────

describe('SL-05: frontmatter parsing — maxTurns', () => {
  it('parses maxTurns as a number', () => {
    const root = makeTempDir();
    const md = `---
name: turns-agent
description: "Agent with maxTurns"
maxTurns: 15
---

Body.
`;
    makeSubagentDir(root, 'turns-agent', md);

    const agents = loadSubagents(root);

    expect(agents['turns-agent'].maxTurns).toBe(15);
  });

  it('preserves maxTurns: 0 (no falsy-check bug)', () => {
    const root = makeTempDir();
    const md = `---
name: zero-turns-agent
description: "Agent with maxTurns 0"
maxTurns: 0
---

Body.
`;
    makeSubagentDir(root, 'zero-turns-agent', md);

    const agents = loadSubagents(root);

    expect(agents['zero-turns-agent']).toBeDefined();
    expect(agents['zero-turns-agent'].maxTurns).toBe(0);
  });
});

// ── SL-06: Knowledge file collection ─────────────────────────────────────────

describe('SL-06: knowledge file collection', () => {
  it('excludes agent.md from knowledge files', () => {
    const root = makeTempDir();
    makeSubagentDir(root, 'agent', BASIC_AGENT_MD, {
      'kb.md': '# Knowledge',
    });

    const agents = loadSubagents(root);
    const prompt = agents['agent'].prompt;

    // agent.md body should be there but no duplicate "agent.md" heading
    expect(prompt).toContain('### kb.md');
    expect(prompt).not.toContain('### agent.md');
  });

  it('only collects *.md files (not .txt etc.)', () => {
    const root = makeTempDir();
    makeSubagentDir(root, 'agent', BASIC_AGENT_MD, {
      'notes.txt': 'this is text',
      'data.json': '{}',
      'info.md': '# Info',
    });

    const agents = loadSubagents(root);
    const prompt = agents['agent'].prompt;

    expect(prompt).toContain('### info.md');
    expect(prompt).not.toContain('notes.txt');
    expect(prompt).not.toContain('data.json');
  });

  it('sorts knowledge files alphabetically', () => {
    const root = makeTempDir();
    makeSubagentDir(root, 'agent', BASIC_AGENT_MD, {
      'c-last.md': 'C content',
      'a-first.md': 'A content',
      'b-middle.md': 'B content',
    });

    const agents = loadSubagents(root);
    const prompt = agents['agent'].prompt;

    const posA = prompt.indexOf('### a-first.md');
    const posB = prompt.indexOf('### b-middle.md');
    const posC = prompt.indexOf('### c-last.md');

    expect(posA).toBeGreaterThan(-1);
    expect(posB).toBeGreaterThan(-1);
    expect(posC).toBeGreaterThan(-1);
    expect(posA).toBeLessThan(posB);
    expect(posB).toBeLessThan(posC);
  });
});

// ── SL-07: Knowledge inline injection ────────────────────────────────────────

describe('SL-07: knowledge file inline injection', () => {
  it('inlines knowledge content into the prompt', () => {
    const root = makeTempDir();
    makeSubagentDir(root, 'agent', BASIC_AGENT_MD, {
      'kb.md': '# Knowledge\nSome knowledge content.',
    });

    const agents = loadSubagents(root);
    const prompt = agents['agent'].prompt;

    expect(prompt).toContain('This is the agent prompt body.');
    expect(prompt).toContain('## 참고 지식');
    expect(prompt).toContain('### kb.md');
    expect(prompt).toContain('Some knowledge content.');
  });

  it('does not add knowledge section when no knowledge files exist', () => {
    const root = makeTempDir();
    makeSubagentDir(root, 'agent', BASIC_AGENT_MD);

    const agents = loadSubagents(root);
    const prompt = agents['agent'].prompt;

    expect(prompt).not.toContain('## 참고 지식');
  });
});

// ── SL-08: Token budget — 50KB warning ────────────────────────────────────────

describe('SL-08: token budget — 50KB warning', () => {
  it('logs a warning when knowledge files exceed 50KB', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    const root = makeTempDir();
    // Create a file just over 50KB
    const bigContent = 'x'.repeat(51 * 1024);
    makeSubagentDir(root, 'agent', BASIC_AGENT_MD, {
      'big.md': bigContent,
    });

    const agents = loadSubagents(root);

    expect(agents['agent']).toBeDefined(); // still loaded
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('50KB'));
  });
});

// ── SL-09: Token budget — 100KB skip ──────────────────────────────────────────

describe('SL-09: token budget — 100KB skip', () => {
  it('skips the subagent when knowledge files exceed 100KB', () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

    const root = makeTempDir();
    const bigContent = 'x'.repeat(101 * 1024);
    makeSubagentDir(root, 'agent', BASIC_AGENT_MD, {
      'huge.md': bigContent,
    });

    const agents = loadSubagents(root);

    expect(agents['agent']).toBeUndefined();
    expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining('100KB'));
  });
});

// ── SL-10: Missing agent.md → skip ───────────────────────────────────────────

describe('SL-10: missing agent.md → skip', () => {
  it('skips folder that has no agent.md', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    const root = makeTempDir();
    const agentDir = path.join(root, 'no-agent-md');
    fs.mkdirSync(agentDir, { recursive: true });
    writeFile(agentDir, 'kb.md', '# Knowledge');

    const agents = loadSubagents(root);

    expect(agents['no-agent-md']).toBeUndefined();
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('agent.md not found'));
  });
});

// ── SL-11: Missing name/description → skip ───────────────────────────────────

describe('SL-11: missing name or description → skip', () => {
  it('skips subagent with missing name', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    const root = makeTempDir();
    const md = `---
description: "No name"
---

Body.
`;
    makeSubagentDir(root, 'no-name', md);

    const agents = loadSubagents(root);

    expect(agents['no-name']).toBeUndefined();
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('"name"'));
  });

  it('skips subagent with missing description', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    const root = makeTempDir();
    const md = `---
name: no-desc
---

Body.
`;
    makeSubagentDir(root, 'no-desc', md);

    const agents = loadSubagents(root);

    expect(agents['no-desc']).toBeUndefined();
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('"description"'));
  });
});

// ── SL-12: Empty subagents/ folder ───────────────────────────────────────────

describe('SL-12: empty subagents/ folder → empty object', () => {
  it('returns empty object for an empty subagents directory', () => {
    const root = makeTempDir();

    const agents = loadSubagents(root);

    expect(agents).toEqual({});
  });
});

// ── SL-13: Non-existent subagents/ folder ────────────────────────────────────

describe('SL-13: non-existent subagents/ folder → empty object', () => {
  it('returns empty object when subagents directory does not exist', () => {
    const agents = loadSubagents('/nonexistent/path/that/does/not/exist');

    expect(agents).toEqual({});
  });
});

// ── SL-14: maxTurns: 0 preserved (falsy-check bug prevention) ────────────────

describe('SL-14: maxTurns: 0 preserved', () => {
  it('does not drop maxTurns when value is 0', () => {
    const root = makeTempDir();
    const md = `---
name: zero-agent
description: "Zero turns"
maxTurns: 0
---

Body.
`;
    makeSubagentDir(root, 'zero-agent', md);

    const agents = loadSubagents(root);
    const agent = agents['zero-agent'];

    expect(agent).toBeDefined();
    // maxTurns must be explicitly 0, not undefined
    expect(agent.maxTurns).toBe(0);
    expect('maxTurns' in agent).toBe(true);
  });
});

// ── SL-15: buildSubagentGuide ────────────────────────────────────────────────

describe('SL-15: buildSubagentGuide', () => {
  it('returns empty string when no agents are provided', () => {
    const guide = buildSubagentGuide({});

    expect(guide).toBe('');
  });

  it('generates correct markdown with agent names and descriptions', () => {
    const agents: Record<string, AgentDefinition> = {
      'pipeline-builder': {
        description: '파이프라인을 설계하고 생성하는 전문 에이전트.',
        prompt: 'You are a pipeline builder.',
      },
      'data-analyst': {
        description: '데이터를 분석하는 에이전트.',
        prompt: 'You are a data analyst.',
      },
    };

    const guide = buildSubagentGuide(agents);

    expect(guide).toContain('## 전문 에이전트 활용');
    expect(guide).toContain('Agent 도구로 호출하며');
    expect(guide).toContain('### pipeline-builder');
    expect(guide).toContain('파이프라인을 설계하고 생성하는 전문 에이전트.');
    expect(guide).toContain('### data-analyst');
    expect(guide).toContain('데이터를 분석하는 에이전트.');
  });

  it('starts the guide with a leading newline for system prompt appending', () => {
    const agents: Record<string, AgentDefinition> = {
      'test-agent': {
        description: 'A test agent.',
        prompt: 'Test.',
      },
    };

    const guide = buildSubagentGuide(agents);

    expect(guide.startsWith('\n\n')).toBe(true);
  });
});

// ── SL-17: dataset-manager subagent integration ─────────────────────────────

describe('SL-17: dataset-manager subagent is loaded from real subagents dir', () => {
  it('loads dataset-manager subagent', () => {
    // 실제 subagents/ 디렉토리(default path)를 스캔해 dataset-manager가 올바르게
    // 로드되는지 확인하는 통합성 테스트. agent.md와 knowledge 파일이 모두 반영돼야 한다.
    const agents = loadSubagents();
    const ds = agents['dataset-manager'];

    expect(ds).toBeDefined();
    // description: 위임 금지 규칙과 도메인(데이터셋) 명시
    expect(ds.description).toContain('데이터셋');
    expect(ds.description).toContain('위임하지 마세요');
    // prompt(= systemPrompt): agent.md 본문의 파괴 작업 체크리스트 + GEOMETRY 감지 규칙
    expect(ds.prompt).toContain('GEOMETRY');
    expect(ds.prompt).toContain('파괴 작업 체크리스트');
  });
});

// ── SL-DA: data-analyst subagent integration ─────────────────────────────────

describe('SL-DA: data-analyst subagent integration', () => {
  it('loads data-analyst from real subagents directory', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    expect(agents['data-analyst']).toBeDefined();
    expect(agents['data-analyst'].description).toContain('SQL 분석');
    expect(agents['data-analyst'].tools).toContain('mcp__firehub__execute_analytics_query');
    expect(agents['data-analyst'].tools).toContain('mcp__firehub__get_data_schema');
    expect(agents['data-analyst'].tools).toContain('mcp__firehub__create_chart');
  });

  it('data-analyst prompt includes workflow phases', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    const prompt = agents['data-analyst'].prompt;
    expect(prompt).toContain('EXPLORE');
    expect(prompt).toContain('ANALYZE');
    expect(prompt).toContain('PERSIST');
    expect(prompt).toContain('VISUALIZE');
  });

  it('data-analyst rules.md is inlined into prompt', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    const prompt = agents['data-analyst'].prompt;
    expect(prompt).toContain('execute_analytics_query');
    expect(prompt).toContain('EDA SQL');
  });
});

// ── SL-16: Graceful degradation across multiple subagents ────────────────────

describe('SL-16: graceful degradation', () => {
  it('loads valid subagents even when one subagent is invalid', () => {
    const root = makeTempDir();

    // Valid agent
    makeSubagentDir(root, 'good-agent', BASIC_AGENT_MD);

    // Invalid agent (no agent.md)
    const badDir = path.join(root, 'bad-agent');
    fs.mkdirSync(badDir, { recursive: true });
    writeFile(badDir, 'not-agent.md', '# Not valid');

    const agents = loadSubagents(root);

    expect(agents['good-agent']).toBeDefined();
    expect(agents['bad-agent']).toBeUndefined();
  });
});

describe('SL-ACM: api-connection-manager subagent integration', () => {
  it('loads api-connection-manager from real subagents directory', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    expect(agents['api-connection-manager']).toBeDefined();
    expect(agents['api-connection-manager'].description).toContain('API 연결');
    expect(agents['api-connection-manager'].tools).toContain('mcp__firehub__create_api_connection');
    expect(agents['api-connection-manager'].tools).toContain('mcp__firehub__delete_api_connection');
  });

  it('api-connection-manager prompt includes 4-phase workflow', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    const prompt = agents['api-connection-manager'].prompt;
    expect(prompt).toContain('IDENTIFY');
    expect(prompt).toContain('DESIGN');
    expect(prompt).toContain('EXECUTE');
    expect(prompt).toContain('CONFIRM');
  });

  it('api-connection-manager rules.md is inlined with authType info', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    const prompt = agents['api-connection-manager'].prompt;
    expect(prompt).toContain('API_KEY');
    expect(prompt).toContain('BEARER');
    expect(prompt).toContain('authConfig');
  });

  it('api-connection-manager description and prompt include baseUrl keyword', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    // description은 에이전트 위임 판단에 사용됨 — baseUrl 관련 역할이 포함되어야 함
    const prompt = agents['api-connection-manager'].prompt;
    expect(prompt).toContain('baseUrl');
    expect(prompt).toContain('healthCheckPath');
    expect(prompt).toContain('test_api_connection');
  });
});

describe('SL-TM: trigger-manager subagent integration', () => {
  it('SL-TM-01: loads trigger-manager from real subagents directory', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    expect(agents['trigger-manager']).toBeDefined();
    expect(agents['trigger-manager'].description).toContain('트리거');
    expect(agents['trigger-manager'].tools).toContain('mcp__firehub__list_triggers');
    expect(agents['trigger-manager'].tools).toContain('mcp__firehub__delete_trigger');
  });

  it('SL-TM-02: trigger-manager tools include all 4 MCP trigger tools', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    const tools = agents['trigger-manager'].tools;
    expect(tools).toContain('mcp__firehub__list_triggers');
    expect(tools).toContain('mcp__firehub__create_trigger');
    expect(tools).toContain('mcp__firehub__update_trigger');
    expect(tools).toContain('mcp__firehub__delete_trigger');
  });

  it('SL-TM-03: trigger-manager prompt inlines rules.md and examples.md content', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    const prompt = agents['trigger-manager'].prompt;
    // rules.md 핵심 키워드
    expect(prompt).toContain('cronExpression');
    // examples.md 핵심 키워드
    expect(prompt).toContain('새벽 집계');
  });
});

describe('SL-DB: dashboard-builder subagent integration', () => {
  it('SL-DB-01: loads dashboard-builder from real subagents directory', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    expect(agents['dashboard-builder']).toBeDefined();
    expect(agents['dashboard-builder'].description).toContain('대시보드');
    expect(agents['dashboard-builder'].tools).toContain('mcp__firehub__create_dashboard');
    expect(agents['dashboard-builder'].tools).toContain('mcp__firehub__add_chart_to_dashboard');
  });

  it('SL-DB-02: dashboard-builder tools include all 5 MCP tools', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    const tools = agents['dashboard-builder'].tools;
    expect(tools).toContain('mcp__firehub__create_dashboard');
    expect(tools).toContain('mcp__firehub__list_dashboards');
    expect(tools).toContain('mcp__firehub__list_charts');
    expect(tools).toContain('mcp__firehub__add_chart_to_dashboard');
    expect(tools).toContain('mcp__firehub__navigate_to');
  });

  it('SL-DB-03: dashboard-builder prompt inlines rules.md and examples.md content', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    const prompt = agents['dashboard-builder'].prompt;
    // rules.md 핵심 키워드 — 그리드 레이아웃 파라미터 + 12열 그리드 설명
    expect(prompt).toContain('positionX');
    expect(prompt).toContain('12열 그리드');
    // examples.md 핵심 키워드 — 대화 예시 + Phase 라벨
    expect(prompt).toContain('화재 현황 대시보드');
    expect(prompt).toContain('Phase 3 — EXECUTE');
  });
});

describe('SL-AM: admin-manager subagent integration', () => {
  it('SL-AM-01: loads admin-manager from real subagents directory', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    expect(agents['admin-manager']).toBeDefined();
    expect(agents['admin-manager'].description).toContain('사용자');
    expect(agents['admin-manager'].tools).toContain('mcp__firehub__list_users');
    expect(agents['admin-manager'].tools).toContain('mcp__firehub__set_user_roles');
  });

  it('SL-AM-02: admin-manager tools include all 6 MCP tools', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    const tools = agents['admin-manager'].tools;
    expect(tools).toContain('mcp__firehub__list_users');
    expect(tools).toContain('mcp__firehub__get_user');
    expect(tools).toContain('mcp__firehub__set_user_roles');
    expect(tools).toContain('mcp__firehub__set_user_active');
    expect(tools).toContain('mcp__firehub__list_roles');
    expect(tools).toContain('mcp__firehub__list_permissions');
  });

  it('SL-AM-03: admin-manager prompt inlines rules.md and examples.md content', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    const prompt = agents['admin-manager'].prompt;
    // rules.md 핵심 키워드 — 권한 게이팅 표 + set_user_roles 동작 설명
    expect(prompt).toContain('role:assign');
    expect(prompt).toContain('교체(replace)');
    // examples.md 핵심 키워드 — 대화 예시 + Phase 라벨
    expect(prompt).toContain('김철수');
    expect(prompt).toContain('Phase 3 — EXECUTE');
  });
});

describe('SL-AA: audit-analyst subagent integration', () => {
  it('SL-AA-01: loads audit-analyst from real subagents directory', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    expect(agents['audit-analyst']).toBeDefined();
    expect(agents['audit-analyst'].description).toContain('감사 로그');
    expect(agents['audit-analyst'].tools).toContain('mcp__firehub__list_audit_logs');
  });

  it('SL-AA-02: audit-analyst tools include list_audit_logs', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    const tools = agents['audit-analyst'].tools;
    expect(tools).toContain('mcp__firehub__list_audit_logs');
    expect(tools).toContain('WebSearch');
    expect(tools).toHaveLength(2);
  });

  it('SL-AA-03: audit-analyst prompt inlines rules.md and examples.md content', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    const prompt = agents['audit-analyst'].prompt;
    // rules.md 핵심 키워드 — 권한 게이팅 표 + 이상 탐지 패턴
    expect(prompt).toContain('audit:read');
    expect(prompt).toContain('FAILURE');
    // examples.md 핵심 키워드 — 대화 예시 + Phase 라벨
    expect(prompt).toContain('홍길동');
    expect(prompt).toContain('Phase 3 — ANALYZE');
  });
});
