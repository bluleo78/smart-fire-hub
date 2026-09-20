/**
 * AI 자격증명(`ai.credential`)의 유형별 필드 정의.
 *
 * 백엔드 `AiCredential` sealed interface(`AiCredentialService.java`)와 나란히 간다 — 유형이
 * 늘 때 저쪽 switch 는 컴파일 오류로 누락을 잡아주지만, 여기는 컴파일 강제가 없는 상수라
 * 두 곳을 함께 고쳐야 한다는 사실을 이 주석으로 남긴다.
 */
export type AgentType = 'sdk' | 'cli' | 'cli-api' | 'opencode';

/**
 * 자격증명 필드 한 칸의 명세. `plane` 이 서버 저장 위치(payload=평문 JSON, secret=하위 필드
 * 암호화)를 가르고, 화면은 이 값으로 입력창 종류(비밀번호 vs 일반 입력)를 정한다.
 */
export interface FieldSpec {
  /** PUT 요청의 `payload`/`secret` 맵 키와 같다. */
  name: string;
  /** `payload`(평문, 서버가 그대로 돌려줌) | `secret`(암호화, 이름만 내려옴 — 값은 절대 안 옴). */
  plane: 'payload' | 'secret';
  label: string;
  /** 입력창 종류. 생략하면 `text` — 비밀 필드는 화면이 별도로 비밀번호 입력으로 그린다. */
  kind?: 'text' | 'select';
  /**
   * 저장을 막는 필수 필드인가. `opencode` 의 `baseURL` 만 해당한다 — `providerId` 는 Select 라
   * 항상 선택값을 갖고, 서버 쪽 필수 검증(`requireNonBlank`)과는 별개로 화면이 미리 안내하는
   * 용도다(강제하는 쪽은 여전히 서버).
   */
  required?: boolean;
}

/**
 * 유형별로 어떤 필드가 유효한가. 화면(입력 렌더)과 저장 페이로드 구성이 같은 곳을 본다 — 둘이
 * 갈라진 목록을 따로 가지면 "화면엔 있는데 안 보내는 필드"나 그 반대가 생긴다.
 *
 * `sdk`/`opencode` 둘 다 `apiKey` 라는 이름을 쓰지만 <b>다른 비밀이다</b>(전자는 Anthropic,
 * 후자는 OpenAI 호환 공급자) — 서버가 유형 전환 시 이름이 겹쳐도 이전 비밀을 통째로 폐기하는
 * 이유가 이것이다(`AiCredentialService#save` javadoc). 화면도 유형을 바꾸면 `secretInputs` 를
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

/**
 * 전체 유형 목록. Select 옵션 렌더와 "알 수 없는 agentType" 방어에 쓴다 — 순서가 화면
 * 라디오/Select 옵션 순서다.
 */
export const AGENT_TYPES: readonly AgentType[] = ['sdk', 'cli', 'cli-api', 'opencode'];

/**
 * opencode 추론 강도 후보. **권위 있는 집합이 아니다** — opencode 가 공급자에게 그대로 넘기는
 * 값이라 실제 지원 여부는 공급자마다 다르고 런타임에야 드러난다(과금되는 completion 호출로
 * 저장을 게이트하지 않는다는 것이 설계 결정이다). 저장된 값이 이 목록에 없어도 화면은 그 값을
 * 맨 앞에 끼워 보존해야 한다 — 이 상수 자체는 그 보존 로직이 아니라 "기본 후보"만 담는다.
 *
 * <b>맨 앞의 `''`(Ruling #39)</b> 는 설계서 "화면" 절의 `기본값` 옵션이다 — "빈 값으로
 * 저장되고 아무것도 내려보내지 않는다." 세 단계와 나란한 네 번째 선택지가 아니라 "선택하지
 * 않음"을 표현하는 유효한 값이다. 이 값의 사람이 읽는 라벨("기본값")과 Select 옵션 렌더는
 * Task 11 의 몫이다 — 이 파일은 유효한 값의 <b>집합</b>만 정의한다.
 */
export const REASONING_EFFORTS = ['', 'low', 'medium', 'high'] as const;
export type ReasoningEffort = (typeof REASONING_EFFORTS)[number];
