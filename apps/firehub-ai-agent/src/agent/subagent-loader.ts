import { fileURLToPath } from 'url';
import path from 'path';
import fs from 'fs';
import type { AgentDefinition } from '@anthropic-ai/claude-agent-sdk';
import { DEFAULT_DB_DIALECT, type DbDialect } from './db-dialect.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const MAX_KNOWLEDGE_SIZE = 100 * 1024; // 100KB
const WARN_KNOWLEDGE_SIZE = 50 * 1024; // 50KB

/**
 * #260 (perf): 매 요청마다 subagent 정의 전체가 LLM 컨텍스트에 포함돼 토큰 비용 부담.
 * 사용 시연 목적의 examples.md 는 agent.md(목적·도구)와 rules.md(안전 가드) 만으로
 * 모델이 추론 가능하므로 기본 제외. 정확도 영향 최소화하면서 약 33KB 절감.
 *
 * 환경변수 FIREHUB_SUBAGENT_INCLUDE_EXAMPLES=1 로 두면 다시 포함 (디버깅·평가용).
 */
const SUBAGENT_KNOWLEDGE_EXCLUDE: ReadonlySet<string> =
  process.env.FIREHUB_SUBAGENT_INCLUDE_EXAMPLES === '1'
    ? new Set()
    : new Set(['examples.md']);

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
      // YAML 주석(`# ...`)·빈 줄은 리스트를 끊지 않고 건너뛴다.
      // 이유(#577): 리스트 중간의 주석 줄에서 루프가 멈춰 그 뒤 항목이 조용히 누락되면
      // tools 화이트리스트가 잘려 subagent가 "도구가 없다"고 답하는 결함이 된다.
      while (i < lines.length && lines[i].match(/^\s+-\s+|^\s*#|^\s*$/)) {
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

/** Tibero 테넌트용 사본을 두는 서브에이전트 하위 디렉터리 이름(WD-79). */
const TIBERO_VARIANT_DIR = 'tibero';

/**
 * 서브에이전트 디렉터리의 파일 경로를 방언에 맞게 고른다(WD-79).
 *
 * <p>TIBERO 이고 `tibero/<파일명>` 이 있으면 그 사본을, 아니면 원본을 쓴다. PG 는 `tibero/` 경로를 아예 만들지도
 * 확인하지도 않는다 — PG 프롬프트가 사본 유무와 무관하게 변경 전과 바이트 동일해야 하기 때문이다.
 * 대체만 한다: 원본 디렉터리에서 고른 파일 이름에 대해서만 부르므로 `tibero/` 에만 있는 파일이 새로 더해지지 않고,
 * 기본 제외(examples.md)도 그대로 적용된다.
 */
function resolveVariantPath(dirPath: string, fileName: string, dialect: DbDialect): string {
  if (dialect === 'TIBERO') {
    const variant = path.join(dirPath, TIBERO_VARIANT_DIR, fileName);
    if (fs.existsSync(variant)) {
      return variant;
    }
  }
  return path.join(dirPath, fileName);
}

/**
 * Scan a subagent directory, parse agent.md frontmatter + body,
 * collect knowledge files, and return an AgentDefinition or null if invalid.
 *
 * WD-79: dialect 가 TIBERO 면 agent.md·지식 파일을 `tibero/` 사본으로 대체한다(resolveVariantPath).
 * 지식 파일 목록은 원본 디렉터리의 `*.md` 만 본다 — readdirSync 가 돌려주는 `tibero` 하위 디렉터리는 `.md` 로
 * 끝나지 않아 걸러진다. 100KB 한도는 대체한 뒤의 실제 내용 크기로 잰다.
 */
function loadSubagentDir(dirPath: string, dirName: string, dialect: DbDialect): AgentDefinition | null {
  const agentMdPath = resolveVariantPath(dirPath, 'agent.md', dialect);

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

  // Collect knowledge files: *.md except agent.md and excluded names, sorted alphabetically
  // #260: examples.md 등 시연 자료는 정확도 보존 범위에서 기본 제외(토큰 비용 절감).
  let knowledgeFiles: string[] = [];
  try {
    knowledgeFiles = fs
      .readdirSync(dirPath)
      .filter(
        (f) => f.endsWith('.md') && f !== 'agent.md' && !SUBAGENT_KNOWLEDGE_EXCLUDE.has(f),
      )
      .sort();
  } catch (err) {
    console.error(`[subagent-loader] Failed to read directory "${dirName}":`, err);
    return null;
  }

  // Read and inline knowledge files
  let knowledgeContent = '';
  let knowledgeTotalSize = 0;

  for (const fileName of knowledgeFiles) {
    const filePath = resolveVariantPath(dirPath, fileName, dialect);
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
// WD-79: 방언마다 결과가 다르므로 방언별로 따로 캐시한다(한 방언 결과가 다른 방언 요청에 섞이면 안 된다).
const cachedDefaultSubagents = new Map<DbDialect, Record<string, AgentDefinition>>();

/**
 * 테스트에서 기본 경로 캐시를 초기화할 때 사용한다. 모든 방언의 캐시를 비운다.
 */
export function resetSubagentCache(): void {
  cachedDefaultSubagents.clear();
}

/**
 * Scan the subagents directory and load all valid subagent definitions.
 * Returns a Record<name, AgentDefinition>.
 * Individual load failures do not crash the entire loader.
 *
 * @param dialect 테넌트 저장소 방언(WD-79). TIBERO 면 각 서브에이전트의 `tibero/` 사본으로 같은 이름 파일을 대체한다.
 *   생략·undefined 는 POSTGRESQL(기존 동작 그대로).
 */
export function loadSubagents(
  subagentsDir?: string,
  dialect: DbDialect = DEFAULT_DB_DIALECT,
): Record<string, AgentDefinition> {
  // 기본 디렉터리 호출(인자 없음)에 대해서만 모듈 캐시를 사용한다.
  const cached = subagentsDir === undefined ? cachedDefaultSubagents.get(dialect) : undefined;
  if (cached) {
    return cached;
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
      const agentDef = loadSubagentDir(entryPath, entry, dialect);
      if (agentDef !== null) {
        result[entry] = agentDef;
      }
    } catch (err) {
      console.error(`[subagent-loader] Unexpected error loading subagent "${entry}":`, err);
    }
  }

  // 기본 경로 호출 결과는 이후 호출을 위해 캐시한다.
  if (subagentsDir === undefined) {
    cachedDefaultSubagents.set(dialect, result);
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
