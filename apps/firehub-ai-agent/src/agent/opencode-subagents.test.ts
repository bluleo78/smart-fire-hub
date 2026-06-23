import { describe, it, expect } from 'vitest';
import { serializeOpenCodeSubagent } from './opencode-subagents.js';
import type { AgentDefinition } from '@anthropic-ai/claude-agent-sdk';

describe('serializeOpenCodeSubagent', () => {
  it('AgentDefinition 을 OpenCode frontmatter md 로 변환한다', () => {
    const def: AgentDefinition = {
      description: 'Pipeline 전문가',
      prompt: 'You build pipelines.',
      tools: ['mcp__firehub__create_pipeline'],
      model: 'inherit',
    } as AgentDefinition;

    const md = serializeOpenCodeSubagent('pipeline-builder', def);

    // OpenCode 스키마: description 필수, mode: subagent 고정
    expect(md).toContain('description: "Pipeline 전문가"');
    expect(md).toContain('mode: subagent');
    // 본문은 prompt 그대로
    expect(md.trimEnd().endsWith('You build pipelines.')).toBe(true);
    // frontmatter 구분자
    expect(md.startsWith('---\n')).toBe(true);
    expect(md.split('---').length).toBeGreaterThanOrEqual(3);
  });

  it('model 이 inherit 이면 model 필드를 생략한다', () => {
    const def = { description: 'x', prompt: 'p', model: 'inherit' } as AgentDefinition;
    expect(serializeOpenCodeSubagent('a', def)).not.toContain('model:');
  });
});
