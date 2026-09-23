import type { AgentType } from './ai-credential';

/**
 * `AiCredentialFieldset.tsx`(테넌트 AI 탭 화면)가 쓰는 <b>순수 로직</b>만 모은 파일.
 *
 * <b>왜 컴포넌트 파일에 같이 두지 않는가</b>: `react-refresh/only-export-components` 가 컴포넌트
 * 파일은 컴포넌트만 export 하기를 요구한다(Fast Refresh 가 컴포넌트 아닌 export 를 보면 모듈
 * 전체를 다시 평가해 상태를 잃는다). 아래 함수들은 렌더 없이도 검증 가능한 결정 로직이라 —
 * 저장 확인 다이얼로그를 실제로 열지 않고도 `typeChangeConfirmDescription` 문구를, 훅을 마운트하지
 * 않고도 `hasTypeChangedFromSaved` 의 경계값을 테스트할 수 있다는 이점도 겸한다.
 */

/** 유형 Select·확인 다이얼로그가 공유하는 사람이 읽는 유형 이름. */
export const AGENT_TYPE_LABELS: Record<AgentType, string> = {
  sdk: 'Claude Agent SDK',
  cli: 'Claude Code CLI',
  'cli-api': 'Claude API',
  opencode: 'OpenCode',
};

/**
 * opencode 공급자(`providerId`) 후보.
 *
 * <b>권위 있는 카탈로그가 이 코드베이스 어디에도 없다</b> — `providerId` 는 `@ai-sdk/openai-compatible`
 * 에 그대로 넘어가는 임의 문자열이고(`agent-opencode.ts`), OpenAI 호환이기만 하면 어떤 값도
 * 유효하다. 스펙·설계 문서 전체에서 실제로 등장하는 예시는 `"openai"` 뿐이라 그것 하나만 후보로
 * 두고, 그 밖의 저장된/입력된 값은 `withPreservedValue` 로 목록 맨 앞에 끼워 보존한다 —
 * `REASONING_EFFORTS`(설계서 §201)와 같은 "후보일 뿐 권위가 아니다" 원칙을 그대로 따른다.
 * `CREDENTIAL_FIELDS.opencode.providerId` 가 `kind: 'select'` 로 명시돼 있어(Task 10) 자유 입력
 * 대신 Select 로 그리되, 이 보존 규칙으로 목록 밖 값을 잠그지 않는다.
 */
export const PROVIDER_ID_CANDIDATES: { value: string; label: string }[] = [
  { value: 'openai', label: 'OpenAI' },
];

/** 추론 강도 Select 의 후보 — `기본값`(빈 값)은 별도 sentinel 로 다룬다(아래 참고). */
export const REASONING_EFFORT_CANDIDATES: { value: string; label: string }[] = [
  { value: 'low', label: 'low' },
  { value: 'medium', label: 'medium' },
  { value: 'high', label: 'high' },
];

/**
 * Radix `Select.Item` 은 `value=""` 를 허용하지 않는다(빈 문자열은 "선택 없음"을 뜻하는 내부
 * sentinel 로 예약돼 있다). `기본값` 옵션(빈 문자열 저장)을 표현하려면 화면 전용 대체값이
 * 필요하다 — 저장/표시 경계에서만 이 상수와 빈 문자열을 서로 바꾼다.
 */
export const REASONING_EFFORT_DEFAULT_SENTINEL = '__default__';

/**
 * 후보 목록에 없는 현재 값을 맨 앞에 끼워 보존한다(설계서 §201 "저장된 값이 목록에 없으면 맨
 * 앞에 끼워 보존"). 빈 값은 "선택 안 함"이라 보존 대상이 아니다 — 호출부가 그 경우를 별도
 * sentinel/placeholder 로 이미 다룬다.
 */
export function withPreservedValue(
  candidates: { value: string; label: string }[],
  current: string,
): { value: string; label: string }[] {
  if (current === '' || candidates.some((c) => c.value === current)) return candidates;
  return [{ value: current, label: current }, ...candidates];
}

/**
 * opencode 모델 접두사(`providerId/`)를 **한 곳에서만** 붙인다(설계서 §199 "모델 접두사"). Select
 * 옵션의 value 에 미리 붙이면 자유 입력 분기가 접두사 없는 값을 저장하는 참조 구현
 * (`iacloud_eis`)의 잠재 버그를 그대로 반복한다 — 이 함수가 Select·자유 입력 두 분기의 유일한
 * 접두사 부착 지점이다. `providerId` 가 아직 없으면(막 opencode 로 전환해 아직 고르지 않음)
 * 접두사 없이 그대로 돌려준다 — 그러지 않으면 `/gpt-4o` 처럼 접두사만 남는다.
 */
export function withProviderPrefix(modelId: string, providerId: string): string {
  const trimmed = modelId.trim();
  if (trimmed === '' || providerId === '') return trimmed;
  return `${providerId}/${trimmed}`;
}

/** 위 함수의 역연산 — Select/자유 입력에 보여줄 "맨 모델 id"만 뽑아낸다. */
export function stripProviderPrefix(model: string, providerId: string): string {
  if (providerId !== '' && model.startsWith(`${providerId}/`)) {
    return model.slice(providerId.length + 1);
  }
  return model;
}

/**
 * 유형 Select 를 "저장된 유형"과 비교해 전환 경고를 띄울지 결정한다(설계서 §193 "유형 전환").
 * `savedAgentType` 은 `useAiCredentialForm` 이 들고 있는 마지막 <b>서버와 동기화된</b> 유형이다.
 *
 * `configured` 를 요구하는 이유: 테넌트에 저장된 자격증명이 아직 없으면(`configured===false`)
 * 잃을 비밀이 없다 — 이때 `savedAgentType` 은 서버가 준 빈 문서의 기본 유형일 뿐이라, 그것과
 * 다르다고 경고하면 "지금 막 처음 고르는 값인데 무언가 사라진다"는 거짓 경고가 된다.
 */
export function hasTypeChangedFromSaved(
  agentType: AgentType,
  savedAgentType: AgentType,
  configured: boolean,
): boolean {
  return configured && agentType !== savedAgentType;
}

/**
 * 유형 전환 저장 확인 다이얼로그의 본문 — 렌더와 분리된 순수 함수라 다이얼로그를 열지 않고도
 * 문구를 테스트할 수 있다. 확인이 필요한 파괴적 시나리오는 유형 전환(이전 비밀 폐기) 하나뿐이다.
 */
export function typeChangeConfirmDescription(savedAgentType: AgentType, agentType: AgentType): string {
  return (
    `유형을 ${AGENT_TYPE_LABELS[savedAgentType]}에서 ${AGENT_TYPE_LABELS[agentType]}(으)로 바꾸면 ` +
    '이전 유형의 저장된 비밀이 삭제됩니다. 복구할 수 없습니다.'
  );
}

/**
 * sdk/cli/cli-api 에서 고를 수 있는 Claude 모델 — AI 에이전트 탭과 AI 분류 탭(#707)이 같은 목록을
 * 쓴다(탭마다 사본을 두면 모델이 늘 때 한쪽만 고쳐진다).
 */
export const CLAUDE_MODEL_OPTIONS: { value: string; label: string }[] = [
  { value: 'claude-sonnet-5', label: 'Claude Sonnet 5' },
  { value: 'claude-opus-4-8', label: 'Claude Opus 4.8' },
  { value: 'claude-haiku-4-5', label: 'Claude Haiku 4.5' },
];
