/**
 * Claude AgentDefinition 을 OpenCode subagent 정의(.opencode/agents/*.md)로 변환한다.
 *
 * 이유: OpenCode 는 Claude Code 와 subagent 개념은 같지만 frontmatter 스키마가 다르다.
 *  - Claude: name/description/tools(화이트리스트)/model
 *  - OpenCode: description(필수)/mode/model/permission(allow|ask|deny)
 * firehub subagent(pipeline-builder 등)를 OpenCode 에서도 동등하게 위임받게 하려면
 * 요청별 작업 디렉토리에 OpenCode 포맷 md 를 써둔다(agent-cli.ts 의 패턴과 동일).
 */
import { mkdir, readdir, unlink, writeFile } from 'fs/promises';
import { join } from 'path';
import type { AgentDefinition } from '@anthropic-ai/claude-agent-sdk';

/** 임의 문자열을 YAML double-quoted 스칼라로 안전 직렬화 (한 줄 값용). */
function yamlDoubleQuoted(value: string): string {
  return `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n')}"`;
}

/** AgentDefinition → OpenCode frontmatter md. mode 는 subagent 고정. */
export function serializeOpenCodeSubagent(name: string, def: AgentDefinition): string {
  const lines: string[] = ['---', `description: ${yamlDoubleQuoted(def.description)}`, 'mode: subagent'];

  // model: inherit 은 OpenCode 에 무의미하므로 생략 (배포측 기본 모델 사용)
  if (def.model && def.model !== 'inherit') {
    lines.push(`model: ${def.model}`);
  }
  lines.push('---', '');
  return lines.join('\n') + (def.prompt ?? '');
}

/**
 * subagent 정의를 workDir/.opencode/agents/<name>.md 로 일괄 작성.
 * 매 호출마다 기존 .md 를 정리하고 다시 써 정의 추가/삭제/변경에 일관성을 유지한다.
 */
export async function writeOpenCodeSubagentDefinitions(
  workDir: string,
  subagents: Record<string, AgentDefinition>,
): Promise<void> {
  const agentsDir = join(workDir, '.opencode', 'agents');
  await mkdir(agentsDir, { recursive: true });

  try {
    const existing = await readdir(agentsDir);
    await Promise.all(
      existing.filter((f) => f.endsWith('.md')).map((f) => unlink(join(agentsDir, f)).catch(() => {})),
    );
  } catch {
    /* 디렉터리 부재는 무시 — mkdir 가 보장 */
  }

  for (const [name, def] of Object.entries(subagents)) {
    await writeFile(join(agentsDir, `${name}.md`), serializeOpenCodeSubagent(name, def), 'utf-8');
  }
}
