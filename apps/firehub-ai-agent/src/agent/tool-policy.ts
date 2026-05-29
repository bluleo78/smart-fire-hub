/**
 * 메인 에이전트 도구 정책 (single source of truth — #256, #266).
 *
 * SDK 프로바이더(agent-sdk.ts)와 CLI 프로바이더(agent-cli.ts)가 동일한 정책으로
 * 도구 화이트리스트/블랙리스트를 적용하도록 공유 상수로 분리한다.
 *
 * 정책 모델 — allow-by-default (#266):
 *   메인 챗의 역할은 라우팅/위임이며 실제 작업은 subagent 가 수행한다. deny-by-default 모델은
 *   호스트 도구 ecosystem 이 확장될 때마다(예: AskUserQuestion 신규 등장) "tool not in allow list"
 *   회귀를 반복적으로 만들어 사용자 무응답 사고를 유발했다. 따라서 **구체적 위험이 있는 도구만
 *   명시 차단**하고 그 외는 모두 허용한다. 운영(컨테이너)·로컬(호스트) 모두 동일 정책 — 로컬
 *   환경에서도 호스트 위협 도구가 차단되도록 보수적 기준 유지.
 *
 * 회귀 발견 경위:
 *   - #256: SDK/CLI 옵션 불일치로 host 도구가 막히지 않음 → tool-policy 도입
 *   - #262: Read/Bash 가 deny-by-default 에 걸려 첨부 파일 처리 불가 → 허용
 *   - #266: AskUserQuestion 이 동일 사유로 차단되어 dataset-manager 워크플로 불가 →
 *           allow-by-default 로 정책 모델을 뒤집고 위험 도구만 명시 차단
 */

/**
 * 명시 차단 도구 블랙리스트.
 *
 * 차단 사유:
 *   - **호스트 파일 변조**: Write/Edit/NotebookEdit — 로컬 dev 에선 사용자 홈/git, 운영 컨테이너에선
 *     마운트된 볼륨 변조 위험. 데이터 변경은 firehub MCP 도구로만 진행.
 *   - **호스트 ecosystem 부산물**: Skill 은 \`~/.claude/skills\` 의 markdown 을 로드해 모델이 본업에서
 *     이탈하는 사고를 만들고(#256 trace skill-repro-010), Task* 는 채팅 SSE 채널 외부에 백그라운드
 *     작업을 만들어 결과가 사용자에게 도달하지 못한다. 비동기 잡은 firehub MCP/Jobrunr 로 일원화.
 *   - **meta-search 우회 (#216)**: ToolSearch 는 매 호출마다 한 턴씩 더 소비하고 SDK 가 disallowedTools
 *     에 포함된 경우 자동 비활성화로 폴백한다. 우리는 firehub MCP 만 등록하므로 발견 대상도 없음.
 *
 * 풀린 도구 (참고):
 *   - Read/Bash/Glob/Grep/LS — 첨부 파일 처리(#262, #266)
 *   - AskUserQuestion/TodoWrite/ExitPlanMode — 채팅 UX (#266)
 *   - WebSearch/WebFetch — 외부 정보 조회 (#266 사용자 결정)
 */
export const DISALLOWED_TOOLS: readonly string[] = [
  // 호스트 파일 변조
  'Write',
  'Edit',
  'NotebookEdit',
  // host skill/task ecosystem
  'Skill',
  'TaskCreate',
  'TaskUpdate',
  'TaskList',
  'TaskGet',
  'TaskStop',
  'TaskOutput',
  // meta-search 우회 (#216)
  'ToolSearch',
  'mcp__claude-search__*',
] as const;

/**
 * (Legacy) 허용 도구 화이트리스트.
 *
 * SDK/CLI 의 \`allowedTools\` 옵션이 deny-by-default 효과를 갖기 때문에, allow-by-default 정책을
 * 달성하려면 \`allowedTools\` 자체를 미전달해야 한다 (#266 — agent-sdk.ts / agent-cli.ts 에서 제외).
 * 본 상수는 핵심 도구 목록을 명시한 보조 변수로, 런타임 in-flight 검사(\`checkToolPolicy\`)는
 * 이 목록을 참조하지 않는다.
 */
export const ALLOWED_TOOLS: readonly string[] = [
  'mcp__firehub__*',
  'Agent',
] as const;

/**
 * 런타임 in-flight 차단 — allow-by-default (#266).
 *
 * 규칙:
 *   1. DISALLOWED_TOOLS 에 정확히 매칭(또는 \`mcp__claude-search__*\` 같은 prefix-패턴)되면 차단
 *   2. 그 외는 모두 허용 — 새 호스트 도구가 추가돼도 자동 통과되어 무응답 회귀를 막는다
 *
 * #276: Agent 위임 라우팅 백스톱 — \`subagent_type\` 화이트리스트 강제.
 *   메인 에이전트가 SYSTEM_PROMPT L1 의 라우팅 지시를 어기고 호스트 빌트인 \`general-purpose\`
 *   나 미정의 타입으로 위임하면, 전문 subagent 의 rules.md(파괴 확인·PII·maxTurns)가 통째로
 *   우회되고 폭주(운영 trace: 223턴/21.6M토큰)가 발생한다. 프롬프트가 1차 방어이며 본 백스톱은
 *   실제 호출 시 강제 중단하는 2차 안전망이다.
 *
 *   런타임 강제만이 유일한 수단인 이유 (SDK 0.2.x 소스 검증):
 *   - \`permissionMode: 'bypassPermissions'\` 하에서 canUseTool 은 \`passthrough\` 로 무력화.
 *   - 호스트 빌트인 \`general-purpose\`(source:"built-in") 는 \`options.agents\` 와 무관하게 항상 존재.
 *   - 미정의 타입은 에러가 아니라 \`general-purpose\` 로 조용히 폴백(fork resolver).
 *   → 따라서 stream-interception(본 함수) 의 terminal block 이 유일한 강제점이다.
 *
 * @param toolName SDK/CLI 가 보고한 tool_use.name
 * @param input (선택) tool_use.input — Agent 위임의 \`subagent_type\` 검사에 사용
 * @param validSubagentTypes (선택) 정의된 firehub subagent 이름 목록(\`Object.keys(loadSubagents())\`).
 *        제공 시 화이트리스트 강제, 미제공 시 빌트인 \`general-purpose\` 만 차단(방어 하한선).
 * @returns 차단 사유 문자열(차단해야 할 때) 또는 null(허용)
 */
export function checkToolPolicy(
  toolName: string,
  input?: Record<string, unknown>,
  validSubagentTypes?: readonly string[],
): string | null {
  if (!toolName) return null; // 빈 이름은 파싱 노이즈
  for (const pat of DISALLOWED_TOOLS) {
    if (matchToolPattern(pat, toolName)) {
      return `host tool blocked by policy (#256): ${toolName}`;
    }
  }

  // #276: Agent 위임 시 subagent_type 백스톱. input 이 없으면(호출부가 미전달) 검사 생략 — BC.
  if (toolName === 'Agent' && input) {
    const rawType = input.subagent_type;
    const subagentType = typeof rawType === 'string' ? rawType.trim() : '';
    const hasWhitelist = !!validSubagentTypes && validSubagentTypes.length > 0;

    // 차단 메시지는 user-facing SSE error 로 전달되므로 L2(코드명 비노출) 준수 — 전체 에이전트
    // roster 를 싣지 않는다. 시도된 타입(general-purpose/미정의)만 진단용으로 포함하며, 이는
    // 정의된 firehub 에이전트 이름이 아니다(유효 이름은 통과). terminal block 이라 모델은 이
    // 메시지를 보지 못하므로 대안 안내(roster)는 무의미 — 라우팅 1차 방어는 SYSTEM_PROMPT L1 가 담당.
    if (hasWhitelist) {
      // 화이트리스트 모드: 정의된 타입만 허용. 미지정/빈값도 차단 — 호스트가 general-purpose 로
      // 폴백하므로 "타입 생략" 우회를 막는다.
      if (!validSubagentTypes!.includes(subagentType)) {
        return `subagent routing blocked by policy (#276): "${subagentType || '(unspecified)'}" is not an allowed delegation target`;
      }
    } else if (subagentType === 'general-purpose') {
      // 화이트리스트 미제공 시에도 호스트 빌트인 general-purpose 는 차단(방어 하한선).
      return `subagent routing blocked by policy (#276): "general-purpose" is not an allowed delegation target`;
    }
  }

  return null;
}

/** \`mcp__claude-search__*\` 형식의 prefix 와일드카드 + 정확 일치 매칭. */
function matchToolPattern(pattern: string, name: string): boolean {
  if (pattern.endsWith('*')) {
    return name.startsWith(pattern.slice(0, -1));
  }
  return pattern === name;
}
