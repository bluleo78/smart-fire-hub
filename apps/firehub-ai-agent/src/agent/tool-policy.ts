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
  // 코디네이터 전용 메타 도구 (#614): Monitor 는 최상위 코디네이터가 백그라운드 Bash 작업을
  // 감시하기 위한 도구인데, 이 서비스는 요청당 단일 query() 스트림만 실행하고 메인은 라우팅/위임
  // 역할만 한다 — Monitor 로 감시할 백그라운드 작업 자체가 구조적으로 없다. 실제 관찰된 회귀:
  // 스마트 작업 실행 완료를 기다리며 메인이 Monitor 를 직접 호출했다가 실패하자 그 판단 과정을
  // 영어 내부 독백으로 사용자에게 노출했다(#614). Monitor 를 아예 차단해 이 경로 자체를 없앤다.
  'Monitor',
] as const;

/**
 * 위임 전용(delegation-only) 도구 — 메인이 \`Agent\` 위임 없이 직접 호출하면 차단한다 (#588).
 *
 * 배경: audit-analyst 전용 안전장치(Phase 1.5 관리자 권한 고지, 4단계 워크플로, PII 마스킹
 * 세부 규칙)는 subagent 의 \`agent.md\`/\`rules.md\` 에만 정의돼 있어, 메인이 \`Agent\` 위임을
 * 생략하고 \`mcp__firehub__list_audit_logs\` 를 직접 호출하면 전부 우회된다. SYSTEM_PROMPT L1
 * 라우팅 표(프롬프트 레벨, 1차 방어)만으로는 프롬프트 표현에 따라 위임 여부가 비결정적으로
 * 흔들리는 것이 실측됐다(audit-001 직접 호출 vs audit-003 정상 위임, 동일 카테고리 프롬프트).
 *
 * \`checkToolPolicy\` 는 \`parentToolUseId\` 가 있으면(= 이미 \`Agent\` 로 위임된 subagent 내부에서
 * 발행된 tool_use) 통과시키고, 없으면(= 메인이 top-level 에서 직접 발행) 차단한다 — 그래서
 * audit-analyst 자신의 정당한 \`list_audit_logs\` 호출은 막지 않고, 메인의 직접 호출만 막는다.
 */
export const DELEGATION_ONLY_TOOLS: readonly string[] = [
  'mcp__firehub__list_audit_logs',
  // #590: audit-analyst(#588)와 동일한 회귀 — 생성/수정 확인(DESIGN) 단계가 Agent 위임 없이
  // 메인에서 직접 처리되면 agent.md 보안 원칙 1(인증 값 대화 반복 금지)이 우회된다.
  // list/get/delete 는 agent.md 담당표가 메인 직접 호출을 명시적으로 허용(단순 조회, 파괴
  // 확인은 L3 트리거 매핑 표에서 "위임·직접 모두")하므로 제외 — create/update 만 차단한다.
  'mcp__firehub__create_api_connection',
  'mcp__firehub__update_api_connection',
  // #614: audit-analyst(#588)와 동일한 회귀의 새 표면 — 스마트 작업 생성 완료 직후 "지금
  // 실행해줘" 후속 요청에서 메인이 smart-job-manager 위임 없이 이 3개 도구를 직접 호출하고,
  // 실행 완료를 기다리며 코디네이터 전용 도구(Monitor)/Bash 로 직접 폴링을 시도하다 실패하자
  // 내부 독백을 사용자 text 로 노출했다. smart-job-manager 의 agent.md 담당표는 "즉시 실행 및
  // 결과 확인"을 자신의 책임으로 명시하므로, 메인의 top-level 직접 호출만 차단하고
  // smart-job-manager 내부(parentToolUseId 존재)의 정당한 호출은 통과시킨다.
  'mcp__firehub__execute_proactive_job',
  'mcp__firehub__list_job_executions',
  'mcp__firehub__get_execution',
] as const;

/**
 * DELEGATION_ONLY_TOOLS 차단 사유 메시지 — 도구별 매핑 (#590).
 *
 * #588 초기 구현은 메시지를 단일 하드코딩 문자열로 두어, 이후 도구가 추가되면 엉뚱한 사유
 * (예: audit 문구가 api-connection 차단에 노출)가 그대로 재사용되는 결함이 있었다. 도구별로
 * 분리해 각자의 사유를 정확히 반환한다. L2 준수: mcp__firehub__* 식별자·subagent 코드명은
 * 담지 않는다(테스트: tool-policy.test.ts L2 준수 케이스).
 */
const DELEGATION_ONLY_REASONS: Readonly<Record<string, string>> = {
  'mcp__firehub__list_audit_logs':
    'admin-only tool blocked by policy (#588): audit log lookups must be delegated to the audit review subagent',
  'mcp__firehub__create_api_connection':
    'admin-only tool blocked by policy (#590): API connection creation must be delegated to the connection management subagent',
  'mcp__firehub__update_api_connection':
    'admin-only tool blocked by policy (#590): API connection updates must be delegated to the connection management subagent',
  'mcp__firehub__execute_proactive_job':
    'admin-only tool blocked by policy (#614): smart job execution must be delegated to the smart job management subagent',
  'mcp__firehub__list_job_executions':
    'admin-only tool blocked by policy (#614): smart job execution history lookups must be delegated to the smart job management subagent',
  'mcp__firehub__get_execution':
    'admin-only tool blocked by policy (#614): smart job execution result lookups must be delegated to the smart job management subagent',
};

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
 * @param parentToolUseId (선택) SDK/CLI 메시지의 \`parent_tool_use_id\`. null/undefined 면 메인의
 *        top-level 호출, 값이 있으면 이미 위임된 subagent 내부에서 발생한 호출이다(#588).
 * @returns 차단 사유 문자열(차단해야 할 때) 또는 null(허용)
 */
export function checkToolPolicy(
  toolName: string,
  input?: Record<string, unknown>,
  validSubagentTypes?: readonly string[],
  parentToolUseId?: string | null,
): string | null {
  if (!toolName) return null; // 빈 이름은 파싱 노이즈
  for (const pat of DISALLOWED_TOOLS) {
    if (matchToolPattern(pat, toolName)) {
      return `host tool blocked by policy (#256): ${toolName}`;
    }
  }

  // #588: 위임 전용 도구를 메인이 top-level(parent_tool_use_id 없음)로 직접 호출하면 차단.
  // subagent 내부(parentToolUseId 존재)에서의 호출은 정상 위임 경로이므로 허용한다.
  //
  // L2 준수: 이 반환값은 user-facing SSE error 로 그대로 전달되므로(호출부가 console.warn 과
  // 동일 문자열을 사용) mcp__firehub__* 도구 식별자·subagent 코드명(*-analyst 등)을 담지 않는다
  // — toolName 원문은 서버 로그(호출부 console.warn)에서만 확인한다.
  if (!parentToolUseId && DELEGATION_ONLY_TOOLS.includes(toolName)) {
    return DELEGATION_ONLY_REASONS[toolName] ?? 'admin-only tool blocked by policy: this action must be delegated to a specialized subagent';
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
