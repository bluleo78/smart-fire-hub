/**
 * AI 자격증명(`ai.credential`)의 유형별 필드 정의 + 화면 순수 로직 — 플랫폼(super-admin) 전용.
 *
 * <b>`apps/firehub-web/src/lib/ai-credential.ts` + `ai-credential-screen.ts` 의 의도적인
 * 복제본이다.</b> 설계서(§231 "카탈로그")가 명시적으로 공유 패키지를 만들지 않기로 했다 —
 * `packages/*` 가 워크스페이스에 선언돼 있지만 디렉터리가 없어 선례가 0건이고, 첫 공유
 * 패키지를 이 과제에서 만들면 빌드·타입·린트 배선이 딸려 온다. 두 앱의 정의가 어긋나면
 * 서버의 유형별 스키마 검증(`AiCredentialService`)이 400 으로 잡아준다.
 *
 * 백엔드 `AiCredential` sealed interface(`AiCredentialService.java`)와 나란히 간다 — 유형이
 * 늘 때 저쪽 switch 는 컴파일 오류로 누락을 잡아주지만, 여기는 컴파일 강제가 없는 상수라
 * 두 곳(및 firehub-web 사본)을 함께 고쳐야 한다는 사실을 이 주석으로 남긴다.
 */
export type AgentType = 'sdk' | 'cli' | 'cli-api' | 'opencode';

/**
 * 자격증명 필드 한 칸의 명세. `plane` 이 서버 저장 위치(payload=평문 JSON, secret=하위 필드
 * 암호화)를 가르고, 화면은 이 값으로 입력창 종류(비밀번호 vs 일반 입력)를 정한다.
 */
export interface FieldSpec {
  /** PUT 요청의 `payload`/`secret` 맵 키와 같다. */
  name: string;
  plane: 'payload' | 'secret';
  label: string;
  kind?: 'text' | 'select';
  required?: boolean;
}

/**
 * 유형별로 어떤 필드가 유효한가. `sdk`/`opencode` 둘 다 `apiKey` 라는 이름을 쓰지만
 * <b>다른 비밀이다</b>(전자는 Anthropic, 후자는 OpenAI 호환 공급자) — 서버가 유형 전환 시
 * 이름이 겹쳐도 이전 비밀을 통째로 폐기하는 이유가 이것이다. 화면도 유형을 바꾸면 입력값을
 * 전부 비워 같은 착각(이름이 같으니 값도 이어진다)이 생기지 않게 한다.
 */
export const CREDENTIAL_FIELDS: Record<AgentType, FieldSpec[]> = {
  sdk: [
    { name: 'oauthToken', plane: 'secret', label: 'OAuth 토큰' },
    { name: 'apiKey', plane: 'secret', label: 'API 키' },
  ],
  cli: [{ name: 'oauthToken', plane: 'secret', label: 'OAuth 토큰' }],
  'cli-api': [{ name: 'apiKey', plane: 'secret', label: 'API 키' }],
  opencode: [
    { name: 'providerId', plane: 'payload', label: '공급자', kind: 'select' },
    { name: 'baseURL', plane: 'payload', label: '기본 URL', kind: 'text', required: true },
    { name: 'apiKey', plane: 'secret', label: 'API 키' },
    { name: 'reasoningEffort', plane: 'payload', label: '추론 강도', kind: 'select' },
  ],
};

/** 전체 유형 목록. 순서가 화면 Select 옵션 순서다. */
export const AGENT_TYPES: readonly AgentType[] = ['sdk', 'cli', 'cli-api', 'opencode'];

/** 유형 Select·저장 확인 다이얼로그가 공유하는 사람이 읽는 유형 이름. */
export const AGENT_TYPE_LABELS: Record<AgentType, string> = {
  sdk: 'Claude Agent SDK',
  cli: 'Claude Code CLI',
  'cli-api': 'Claude API',
  opencode: 'OpenCode',
};

/**
 * opencode 공급자(`providerId`) 후보. 권위 있는 카탈로그가 없어(`providerId` 는 OpenAI 호환이면
 * 임의 문자열이 유효하다) `"openai"` 하나만 후보로 두고, 저장된/입력된 다른 값은
 * `withPreservedValue` 로 목록 맨 앞에 끼워 보존한다(firehub-web `ai-credential-screen.ts` 와
 * 같은 규칙).
 */
export const PROVIDER_ID_CANDIDATES: { value: string; label: string }[] = [
  { value: 'openai', label: 'OpenAI' },
];

/**
 * opencode 추론 강도 후보 — 화면 Select 용(사람이 읽는 라벨 포함). **권위 있는 집합이 아니다**:
 * opencode 가 공급자에게 그대로 넘기는 값이라 실제 지원 여부는 공급자마다 다르고 런타임에야
 * 드러난다. 저장된 값이 이 목록에 없어도 화면은 그 값을 맨 앞에 끼워 보존해야 한다.
 */
export const REASONING_EFFORT_CANDIDATES: { value: string; label: string }[] = [
  { value: 'low', label: 'low' },
  { value: 'medium', label: 'medium' },
  { value: 'high', label: 'high' },
];

/**
 * Radix `Select.Item` 은 `value=""` 를 허용하지 않는다(빈 문자열은 내부 sentinel 로 예약돼
 * 있다). `기본값` 옵션(빈 문자열 저장, 설계서 §201 "저장되고 아무것도 내려보내지 않는다")을
 * 표현하려면 화면 전용 대체값이 필요하다 — 저장/표시 경계에서만 이 상수와 빈 문자열을
 * 서로 바꾼다.
 */
export const REASONING_EFFORT_DEFAULT_SENTINEL = '__default__';

/**
 * 후보 목록에 없는 현재 값을 맨 앞에 끼워 보존한다(설계서 §201 "저장된 값이 목록에 없으면 맨
 * 앞에 끼워 보존"). 빈 값은 "선택 안 함"이라 보존 대상이 아니다.
 */
export function withPreservedValue(
  candidates: { value: string; label: string }[],
  current: string,
): { value: string; label: string }[] {
  if (current === '' || candidates.some((c) => c.value === current)) return candidates;
  return [{ value: current, label: current }, ...candidates];
}

/**
 * `ai.model` Select 후보(Claude 세 모델) — Ruling #48(Task 13 fix round 1)로 `SETTING_CATALOG`
 * 밖으로 옮겨왔다. `ai.model` 이 더는 범용 카탈로그 렌더러가 아니라 `AiCredentialSection` 이
 * 직접 그리므로(유형에 따라 Select/자유 입력을 가른다), 그 옵션 목록도 여기로 옮겨 컴포넌트가
 * 같은 파일에서 유형 정의와 함께 참조하게 한다. 값 자체(`claude-sonnet-5` 등)는 예전
 * `settings-catalog.ts` 의 `ai.model` 항목과 문자 그대로 같다 — 서버 저장 형식이 바뀐 게
 * 아니라 "어느 화면이 그리는가"만 바뀌었다.
 */
export const CLAUDE_MODEL_CANDIDATES: { value: string; label: string }[] = [
  { value: 'claude-sonnet-5', label: 'Claude Sonnet 5' },
  { value: 'claude-opus-4-8', label: 'Claude Opus 4.8' },
  { value: 'claude-haiku-4-5', label: 'Claude Haiku 4.5' },
];

/**
 * opencode `ai.model` 은 `providerId/modelId` 형식으로 저장된다(서버
 * `OpencodeCredentialValidation.checkProviderConsistency`). 화면은 맨 모델 id 만 입력받고
 * 저장 직전에 공급자 접두어를 붙인다 — `apps/firehub-web/src/lib/ai-credential-screen.ts` 의
 * 같은 이름 함수와 동일한 규칙(의도적 복제, 파일 헤더 주석 참고).
 */
export function withProviderPrefix(modelId: string, providerId: string): string {
  const trimmed = modelId.trim();
  if (trimmed === '' || providerId === '') return trimmed;
  return `${providerId}/${trimmed}`;
}

/** 위 함수의 역연산 — 입력칸에 보여줄 "맨 모델 id"만 뽑아낸다. */
export function stripProviderPrefix(model: string, providerId: string): string {
  if (providerId !== '' && model.startsWith(`${providerId}/`)) {
    return model.slice(providerId.length + 1);
  }
  return model;
}
