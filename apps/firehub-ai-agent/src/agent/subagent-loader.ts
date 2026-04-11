import { fileURLToPath } from 'url';
import path from 'path';
import fs from 'fs';
import type { AgentDefinition } from '@anthropic-ai/claude-agent-sdk';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const MAX_KNOWLEDGE_SIZE = 100 * 1024; // 100KB
const WARN_KNOWLEDGE_SIZE = 50 * 1024; // 50KB

interface Frontmatter {
  name?: string;
  description?: string;
  tools?: string[];
  mcpServers?: string[];
  model?: string;
  maxTurns?: number;
}

/**
 * Parse simple YAML frontmatter from markdown content.
 * Supports: strings, numbers, and string arrays only.
 * Returns { frontmatter, body } where body is the content after the closing ---.
 */
function parseFrontmatter(content: string): { frontmatter: Frontmatter; body: string } {
  const match = content.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/);
  if (!match) {
    return { frontmatter: {}, body: content };
  }

  const yamlStr = match[1];
  const body = match[2] ?? '';
  const frontmatter: Frontmatter = {};

  let i = 0;
  const lines = yamlStr.split('\n');

  while (i < lines.length) {
    const line = lines[i];
    const kvMatch = line.match(/^(\w+):\s*(.*)$/);
    if (!kvMatch) {
      i++;
      continue;
    }

    const key = kvMatch[1];
    const value = kvMatch[2].trim();

    if (value === '' || value === null) {
      // Possibly a list follows
      const items: string[] = [];
      i++;
      while (i < lines.length && lines[i].match(/^\s+-\s+/)) {
        const itemMatch = lines[i].match(/^\s+-\s+(.*)/);
        if (itemMatch) {
          items.push(itemMatch[1].trim().replace(/^["']|["']$/g, ''));
        }
        i++;
      }
      if (items.length > 0) {
        (frontmatter as Record<string, unknown>)[key] = items;
      }
      continue;
    } else if (value.startsWith('[')) {
      // Inline array: [a, b, c]
      const inner = value.slice(1, value.endsWith(']') ? -1 : value.length);
      const items = inner
        .split(',')
        .map((s) => s.trim().replace(/^["']|["']$/g, ''))
        .filter((s) => s.length > 0);
      (frontmatter as Record<string, unknown>)[key] = items;
    } else {
      // Scalar: string or number
      const unquoted = value.replace(/^["']|["']$/g, '');
      const num = Number(unquoted);
      if (!isNaN(num) && unquoted !== '') {
        (frontmatter as Record<string, unknown>)[key] = num;
      } else {
        (frontmatter as Record<string, unknown>)[key] = unquoted;
      }
    }
    i++;
  }

  return { frontmatter, body };
}

/**
 * Scan a subagent directory, parse agent.md frontmatter + body,
 * collect knowledge files, and return an AgentDefinition or null if invalid.
 */
function loadSubagentDir(dirPath: string, dirName: string): AgentDefinition | null {
  const agentMdPath = path.join(dirPath, 'agent.md');

  if (!fs.existsSync(agentMdPath)) {
    console.warn(`[subagent-loader] Skipping "${dirName}": agent.md not found`);
    return null;
  }

  let agentMdContent: string;
  try {
    agentMdContent = fs.readFileSync(agentMdPath, 'utf-8');
  } catch (err) {
    console.error(`[subagent-loader] Failed to read agent.md in "${dirName}":`, err);
    return null;
  }

  const { frontmatter, body } = parseFrontmatter(agentMdContent);

  if (!frontmatter.name) {
    console.warn(`[subagent-loader] Skipping "${dirName}": missing required field "name"`);
    return null;
  }
  if (!frontmatter.description) {
    console.warn(`[subagent-loader] Skipping "${dirName}": missing required field "description"`);
    return null;
  }

  // Collect knowledge files: *.md except agent.md, sorted alphabetically
  let knowledgeFiles: string[] = [];
  try {
    knowledgeFiles = fs
      .readdirSync(dirPath)
      .filter((f) => f.endsWith('.md') && f !== 'agent.md')
      .sort();
  } catch (err) {
    console.error(`[subagent-loader] Failed to read directory "${dirName}":`, err);
    return null;
  }

  // Read and inline knowledge files
  let knowledgeContent = '';
  let knowledgeTotalSize = 0;

  for (const fileName of knowledgeFiles) {
    const filePath = path.join(dirPath, fileName);
    let fileContent: string;
    try {
      fileContent = fs.readFileSync(filePath, 'utf-8');
    } catch (err) {
      console.error(`[subagent-loader] Failed to read knowledge file "${fileName}" in "${dirName}":`, err);
      continue;
    }
    knowledgeTotalSize += Buffer.byteLength(fileContent, 'utf-8');
    knowledgeContent += `\n\n### ${fileName}\n\n${fileContent}`;
  }

  if (knowledgeTotalSize > MAX_KNOWLEDGE_SIZE) {
    console.error(
      `[subagent-loader] Skipping "${dirName}": knowledge files exceed 100KB limit (${(knowledgeTotalSize / 1024).toFixed(1)}KB)`,
    );
    return null;
  }

  if (knowledgeTotalSize > WARN_KNOWLEDGE_SIZE) {
    console.warn(
      `[subagent-loader] "${dirName}": knowledge files are large (${(knowledgeTotalSize / 1024).toFixed(1)}KB > 50KB)`,
    );
  }

  // Build final prompt
  let prompt = body.trim();
  if (knowledgeContent) {
    prompt += '\n\n---\n\n## 참고 지식\n' + knowledgeContent;
  }

  // Build AgentDefinition using !== undefined to avoid falsy-check bugs (e.g., maxTurns: 0)
  const agentDef: AgentDefinition = {
    description: frontmatter.description,
    prompt,
    ...(frontmatter.tools !== undefined && { tools: frontmatter.tools }),
    ...(frontmatter.mcpServers !== undefined && {
      mcpServers: frontmatter.mcpServers as AgentDefinition['mcpServers'],
    }),
    ...(frontmatter.model !== undefined &&
      frontmatter.model !== 'inherit' && {
        model: frontmatter.model as AgentDefinition['model'],
      }),
    ...(frontmatter.maxTurns !== undefined && { maxTurns: frontmatter.maxTurns }),
  };

  return agentDef;
}

// 기본 subagents 디렉터리 로드 결과를 모듈 수준에서 메모이즈한다.
// 서브에이전트 정의는 런타임에 변경되지 않으므로 매 채팅 턴마다 디스크 I/O를
// 반복할 필요가 없다. 명시적 경로로 호출되는 경우(테스트 등)는 캐시 우회.
let cachedDefaultSubagents: Record<string, AgentDefinition> | null = null;

/**
 * 테스트에서 기본 경로 캐시를 초기화할 때 사용한다.
 */
export function resetSubagentCache(): void {
  cachedDefaultSubagents = null;
}

/**
 * Scan the subagents directory and load all valid subagent definitions.
 * Returns a Record<name, AgentDefinition>.
 * Individual load failures do not crash the entire loader.
 */
export function loadSubagents(subagentsDir?: string): Record<string, AgentDefinition> {
  // 기본 디렉터리 호출(인자 없음)에 대해서만 모듈 캐시를 사용한다.
  if (subagentsDir === undefined && cachedDefaultSubagents) {
    return cachedDefaultSubagents;
  }

  const dir = subagentsDir ?? path.join(__dirname, 'subagents');

  if (!fs.existsSync(dir)) {
    return {};
  }

  let entries: string[];
  try {
    entries = fs.readdirSync(dir);
  } catch (err) {
    console.error(`[subagent-loader] Failed to read subagents directory:`, err);
    return {};
  }

  const result: Record<string, AgentDefinition> = {};

  for (const entry of entries) {
    const entryPath = path.join(dir, entry);
    let stat: fs.Stats;
    try {
      stat = fs.statSync(entryPath);
    } catch {
      continue;
    }

    if (!stat.isDirectory()) {
      continue;
    }

    try {
      const agentDef = loadSubagentDir(entryPath, entry);
      if (agentDef !== null) {
        result[entry] = agentDef;
      }
    } catch (err) {
      console.error(`[subagent-loader] Unexpected error loading subagent "${entry}":`, err);
    }
  }

  // 기본 경로 호출 결과는 이후 호출을 위해 캐시한다.
  if (subagentsDir === undefined) {
    cachedDefaultSubagents = result;
  }

  return result;
}

/**
 * Generate a dynamic delegation guide for the main agent's system prompt.
 * Returns empty string when no subagents are loaded.
 */
export function buildSubagentGuide(agents: Record<string, AgentDefinition>): string {
  if (Object.keys(agents).length === 0) return '';

  let guide = '\n\n## 전문 에이전트 활용\n\n';
  guide += '복잡한 작업은 전문 에이전트에게 위임하여 더 정확한 결과를 얻을 수 있습니다.\n';
  guide +=
    'Agent 도구로 호출하며, 사용자의 요구사항과 관련 컨텍스트를 프롬프트에 포함하세요.\n\n';

  for (const [name, def] of Object.entries(agents)) {
    guide += `### ${name}\n${def.description}\n\n`;
  }

  return guide;
}
