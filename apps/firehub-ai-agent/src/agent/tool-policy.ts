/**
 * 메인 에이전트 도구 정책 (single source of truth — #256).
 *
 * SDK 프로바이더(agent-sdk.ts)와 CLI 프로바이더(agent-cli.ts)가 동일한 정책으로
 * 도구 화이트리스트/블랙리스트를 적용하도록 공유 상수로 분리한다.
 *
 * 회귀 발견 경위 (#256 라운드 2):
 *   - SDK 프로바이더에는 allowedTools/disallowedTools 가 query() 옵션으로 적용돼 있었으나,
 *     CLI 프로바이더(spawn 'claude')에는 --allowed-tools/--disallowed-tools 플래그가 누락
 *     되어 호스트 도구(Skill, TaskCreate, Bash 등)가 그대로 호출됐다.
 *   - 추가로 SDK 가 allow/disallow 를 "추가 허용" 으로 해석하는 엣지 케이스가 있을 수 있어
 *     런타임 in-flight 검사(tool_use 이벤트에서 차단)를 보조 안전망으로 둔다.
 *
 * 정책:
 *   - allowed: firehub MCP 도구(`mcp__firehub__*`) + 서브에이전트 위임(`Agent`)
 *   - disallowed: host skill/task ecosystem, host 파일 IO/shell, 외부 네트워크,
 *     meta-search(ToolSearch)
 */

/** 허용 도구 화이트리스트. allowed list 와 disallowed list 는 동시에 적용된다. */
export const ALLOWED_TOOLS: readonly string[] = [
  'mcp__firehub__*',
  'Agent',
  // #262: 파일 첨부 처리용 — Read (이미지/PDF/텍스트/CSV) + Bash (XLSX/DOCX python3 처리)
  // FILE_ATTACHMENT_PROMPT (system-prompt.ts) 가 이 도구 사용을 지시하며, 첨부 파일 경로
  // (~/chat-files/*) 외 접근은 SYSTEM_PROMPT 에서 금지. fileIds 없는 요청에는 가이드 자체가
  // 첨부되지 않으므로 LLM 이 호출할 동기 없음.
  'Read',
  'Bash',
] as const;

/** 명시 차단 도구 블랙리스트(이중 안전망). */
export const DISALLOWED_TOOLS: readonly string[] = [
  // meta-search (#216)
  'ToolSearch',
  'mcp__claude-search__*',
  // skill/task ecosystem (#256)
  'Skill',
  'TaskCreate',
  'TaskUpdate',
  'TaskList',
  'TaskGet',
  'TaskStop',
  'TaskOutput',
  // host filesystem / shell 일부 — Write/Edit/NotebookEdit/Glob/Grep/LS 는 첨부 처리에 불필요하므로 차단 유지
  'Write',
  'Edit',
  'NotebookEdit',
  'Glob',
  'Grep',
  'LS',
  // 외부 네트워크
  'WebFetch',
  'WebSearch',
] as const;

/**
 * 런타임 in-flight 차단 — tool_use 블록의 도구명이 허용되지 않으면 true.
 *
 * SDK/CLI 옵션이 어떤 이유로 무력화되더라도(예: 옵션 미전달, plugin 채널 우회) 스트림
 * 파서가 tool_use 이벤트를 받는 즉시 자체 정책으로 차단할 수 있도록 한다.
 *
 * 규칙:
 *   1. DISALLOWED_TOOLS 에 정확히 매칭(또는 `mcp__claude-search__*` 같은 prefix-패턴)되면 차단
 *   2. ALLOWED_TOOLS 의 어떤 패턴에도 매칭되지 않으면 차단(deny-by-default)
 *
 * @param toolName SDK/CLI 가 보고한 tool_use.name (예: 'mcp__firehub__list_datasets', 'Skill')
 * @returns 차단 사유 문자열(차단해야 할 때) 또는 null(허용)
 */
export function checkToolPolicy(toolName: string): string | null {
  if (!toolName) return null; // 빈 이름은 파싱 노이즈 — 상위에서 처리
  // 1) 명시 차단 — 정확 일치 또는 prefix(`*` 와일드카드) 매칭
  for (const pat of DISALLOWED_TOOLS) {
    if (matchToolPattern(pat, toolName)) {
      return `host tool blocked by policy (#256): ${toolName}`;
    }
  }
  // 2) 허용 화이트리스트 — 매칭 패턴이 하나라도 있어야 통과
  for (const pat of ALLOWED_TOOLS) {
    if (matchToolPattern(pat, toolName)) return null;
  }
  return `tool not in allow list (#256): ${toolName}`;
}

/** `mcp__firehub__*` 형식의 prefix 와일드카드 + 정확 일치 매칭. */
function matchToolPattern(pattern: string, name: string): boolean {
  if (pattern.endsWith('*')) {
    return name.startsWith(pattern.slice(0, -1));
  }
  return pattern === name;
}
