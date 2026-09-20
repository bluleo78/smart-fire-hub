import { isTenantOverridable } from './override-policy';

export type SettingKind = 'text' | 'number' | 'textarea' | 'select' | 'switch' | 'secret';

export interface SettingSpec {
  key: string;
  /** 한국어 고정 라벨. 필드 설명은 서버 `description` 을 그대로 쓰므로 여기 복제하지 않는다. */
  label: string;
  kind: SettingKind;
  options?: { value: string; label: string }[];
  /**
   * DB 행이 없어 화면이 대신 보여줘야 하는 코드 수준 기본값.
   * `ai.session_max_tokens` 는 어떤 마이그레이션도 시드하지 않아 응답 15행에 없다 —
   * 저장소가 upsert 라 첫 저장에 행이 생기고 `내장 기본값` 배지는 사라진다.
   */
  builtinDefault?: string;
  /** 암호화 저장되고 마스킹되어 내려오는 키. 서버 `SECRET_KEYS` 와 같아야 한다. */
  secret: boolean;
  /**
   * 빈 문자열 저장이 합법인가.
   *
   * false 인 키는 9개다(타입형 AI 설정 전환, Task 13 — `ai.api_key` 는 `ai.credential` 문서로
   * 옮겨가 이 카탈로그에서 빠졌고, 그 문서는 `AiCredentialSection` 이 별도 저장 흐름으로 다룬다):
   * - `ai.system_prompt` — 서버 `validateValues` 가 "시스템 프롬프트는 비어있을 수 없습니다" 로 거부한다.
   * - `smtp.starttls` — 스위치라 값이 항상 `'true'`/`'false'` 둘 중 하나다. 비울 대상이 없다.
   * - `embedding.model` · `embedding.base_url` — 서버 `validateEmbeddingConsistency` 가 페이로드에
   *   키가 있으면 blank 를 거부한다(리뷰 M2).
   * - `ai.max_turns` · `ai.max_tokens` · `ai.session_max_tokens` · `ai.temperature` —
   *   서버가 이 네 키에 `Integer.parseInt` / `Double.parseDouble` 을 **무조건** 호출한다
   *   (`SettingsService.validateValues`). 빈 문자열이 도달하면 `NumberFormatException`
   *   (`IllegalArgumentException` 의 서브클래스)이 `GlobalExceptionHandler.handleIllegalArgument`
   *   를 거쳐 **400** 으로 거부된다(500 이 아니다) — 가드 자체는 옳지만, 서버가 던지는 영문 미번역
   *   예외 문구보다 클라이언트가 먼저 막아 한국어 안내를 주는 편이 낫다. 그래서 `지우기` 를 렌더하지
   *   않고, 아래 `validate` 가 빈 값을 **거부**한다.
   * - `smtp.port` — **정정(리뷰 H2, 2026-08-24): 서버가 이 키도 검증한다.** 최초 조사가
   *   `validateValues` 스위치에 `smtp.port` case 가 없는 것만 보고 "서버 미검증"이라 잘못 결론
   *   냈다. 실제로는 별도 메서드 `SettingsService.validateSmtpPort`(710행)가 두 쓰기 경로
   *   (테넌트 421행, 플랫폼 656행) 모두에서 호출되어 빈 값·비숫자·1~65535 범위 밖을
   *   `IllegalArgumentException` 으로 거부한다. 그래서 이 키도 지울 수 없다.
   */
  clearable: boolean;
  /**
   * 값 경계 검증.
   *
   * 원칙은 "서버와 같은 경계" 다 — 어긋나면 사용자가 이유 없이 막히거나(더 엄격), 클라이언트를
   * 지나 서버 예외 문구를 그대로 보게 된다(더 느슨).
   *
   * (예전엔 `ai.model` 이 유일하게 서버보다 엄격한 키였다 — 서버는 자유 문자열로 두는데
   * 3개 Claude 모델로 좁힌 select 였다. Ruling #48 로 그 키가 `AiCredentialSection` 으로
   * 옮겨가며 이 카탈로그에서 빠져, 지금은 이 문단이 가리킬 예외가 없다.)
   *
   * `smtp.port` 는 예전에 "서버 미검증이라 클라이언트만 엄격"으로 여기 기재돼 있었으나
   * **틀렸다(리뷰 H2 정정)**: `SettingsService.validateSmtpPort` 가 같은 1~65535 범위를
   * 서버에서도 강제한다. 지금은 경계가 서버와 1:1 이다 — 목록에서 뺀다.
   *
   * `embedding.model`/`embedding.base_url` 은 **알려진 예외로 서버보다 느슨하다(리뷰 M2)**:
   * 서버 `validateEmbeddingConsistency` 가 blank 를 거부하고 `base_url` 에 http(s) 스킴을
   * 강제하며, `provider === 'OPENAI'` 일 때는 `base_url` 이 https 여야 한다는 **필드 간** 규칙까지
   * 있다. 이 카탈로그의 `validate` 는 필드 하나만 보는 함수라 provider 값을 알 수 없어 그 조건부
   * 규칙은 구현할 수 없다 — blank 거부와 http(s) 스킴 검사까지만 맞추고, provider 조건부 https
   * 강제는 **의도적으로 클라이언트에 없다**(서버가 저장 시점에 400 으로 잡아준다).
   */
  validate?: (value: string) => string | undefined;
}

/** 숫자 키를 비워 저장하려 할 때의 공통 문구. 서버 parse 예외로 넘어가기 전에 여기서 막는다. */
const EMPTY_NUMBER_MESSAGE = '값을 비워 둘 수 없습니다. 숫자를 입력하세요';

/**
 * 정수 검증 공통.
 *
 * 빈 값은 전부 거부한다. `intRange` 를 쓰는 숫자 키 4개(`ai.max_turns`/`ai.max_tokens`/
 * `ai.session_max_tokens`/`smtp.port`) 모두 서버가 값을 무조건 파싱한다 —
 * `ai.*` 세 키는 `SettingsService.validateValues` 의 `Integer.parseInt`, `smtp.port` 는
 * 별도 메서드 `validateSmtpPort`(리뷰 H2 정정: 이전에는 이 키만 서버 미검증이라 믿고
 * 빈 값을 허용했으나, 실측 결과 서버가 여기도 검증한다). 빈 값이 실제로 안전한 숫자 키는
 * 이 카탈로그에 하나도 없다.
 *
 * 형태 검증은 `Integer.parseInt` 와 같은 것만 통과시킨다(리뷰 H1 정정): 선행 부호(`+`/`-`)
 * 다음 십진수 숫자만 허용하고, 공백·소수점·지수 표기는 전부 거부한다. `Number(value)` 를
 * 바로 쓰면 `" 20 "`·`"20.0"`·`"1e3"` 를 모두 유효한 숫자로 통과시켜 서버보다 느슨해진다 —
 * 서버는 이 넷을 전부 `NumberFormatException` 으로 거부하므로, 그 문자열이 클라이언트를
 * 통과하면 사용자는 번역되지 않은 서버 예외 문구(`For input string: "20.0"`)를 그대로 본다.
 */
function intRange(min: number, max: number, message: string) {
  return (value: string): string | undefined => {
    if (value.trim() === '') return EMPTY_NUMBER_MESSAGE;
    if (!/^[+-]?\d+$/.test(value)) return message;
    const n = Number(value);
    if (n < min || n > max) return message;
    return undefined;
  };
}

export const SETTING_CATALOG: Record<string, SettingSpec> = {
  // ── AI 에이전트 ────────────────────────────────────────────────────────────
  // `ai.model` 은 여기 없다(Ruling #48, Task 13 fix round 1) — `AiCredentialSection` 이 직접
  // 그리고 직접 쓴다. 옛 3-Claude-모델 Select 는 opencode 를 고른 관리자가 `providerId/modelId`
  // 형식을 이 화면만으로 지정할 방법이 없다는 결함이 있었다(그 화면은 서버가 자유 문자열로 두는
  // 값을 3개로 임의로 좁히기만 했다) — 유형에 따라 Select/자유 입력을 가르려면 유형을 아는
  // 컴포넌트(`AiCredentialSection`)가 이 필드도 함께 가져야 한다. 옵션 목록은
  // `lib/ai-credential.ts` 의 `CLAUDE_MODEL_CANDIDATES` 로 옮겼다. 백엔드 키·저장 형식은
  // 그대로다 — `SettingsOverridePolicy.TENANT_OVERRIDABLE` 에도 여전히 있다(진짜 재정의 가능
  // 키다, `override-policy.ts` 참고) — 바뀐 것은 "어느 화면이 그리는가"뿐이다.
  'ai.max_turns': {
    key: 'ai.max_turns',
    label: '최대 턴 수',
    kind: 'number',
    secret: false,
    // 비우면 400(서버가 parseInt 를 무조건 부른다) — 근거는 위 clearable 필드 문서 참고.
    clearable: false,
    validate: intRange(1, 50, '1~50 사이의 정수를 입력하세요'),
  },
  'ai.system_prompt': {
    key: 'ai.system_prompt',
    label: '시스템 프롬프트',
    kind: 'textarea',
    secret: false,
    clearable: false,
    validate: (value) => (value.trim() === '' ? '시스템 프롬프트를 입력하세요' : undefined),
  },
  'ai.temperature': {
    key: 'ai.temperature',
    label: 'Temperature',
    kind: 'number',
    secret: false,
    // 비우면 400(서버가 parseDouble 을 무조건 부른다) — 근거는 위 clearable 필드 문서 참고.
    clearable: false,
    validate: (value) => {
      if (value.trim() === '') return EMPTY_NUMBER_MESSAGE;
      const n = Number(value);
      if (Number.isNaN(n) || n < 0 || n > 1) return '0.0~1.0 사이의 값을 입력하세요';
      return undefined;
    },
  },
  'ai.max_tokens': {
    key: 'ai.max_tokens',
    label: '최대 응답 토큰',
    kind: 'number',
    secret: false,
    // 비우면 400(서버가 parseInt 를 무조건 부른다) — 근거는 위 clearable 필드 문서 참고.
    clearable: false,
    validate: intRange(1, 65536, '1~65536 사이의 정수를 입력하세요'),
  },
  'ai.session_max_tokens': {
    key: 'ai.session_max_tokens',
    label: '세션 최대 토큰',
    kind: 'number',
    builtinDefault: '50000',
    secret: false,
    // 비우면 400(서버가 parseInt 를 무조건 부른다) — 근거는 위 clearable 필드 문서 참고.
    clearable: false,
    validate: intRange(10000, 200000, '10,000~200,000 사이의 정수를 입력하세요'),
  },
  // `ai.api_key`/`ai.agent_type`/`ai.cli_oauth_token` 은 여기 없다(타입형 AI 설정 전환,
  // Task 13) — 유형별 구조 `ai.credential` 문서로 옮겨갔고, `AiCredentialSection.tsx` 가
  // 전용 `GET/PUT /settings/ai-credential` 로 별도 관리한다. 이 범용 카탈로그(`/settings`
  // 문자열 키·값)에는 애초에 얹을 수 없는 모양(하위 필드 암호화 JSON)이다.

  // ── 이메일(SMTP) ──────────────────────────────────────────────────────────
  'smtp.host': { key: 'smtp.host', label: 'SMTP 호스트', kind: 'text', secret: false, clearable: true },
  'smtp.port': {
    key: 'smtp.port',
    label: '포트',
    kind: 'number',
    secret: false,
    // 정정(리뷰 H2, 2026-08-24): 서버 SettingsService.validateSmtpPort(710행)가
    // 두 쓰기 경로(테넌트 421행, 플랫폼 656행) 모두에서 이 키를 검증한다 — 빈 값·비숫자·
    // 1~65535 범위 밖을 전부 거부한다. "서버 switch 에 case 없음"은 validateValues 스위치만
    // 본 오판이었다. 서버가 지운 값을 거부하므로 클라이언트도 지울 수 없다.
    clearable: false,
    validate: intRange(1, 65535, '1~65535 사이의 정수를 입력하세요'),
  },
  'smtp.username': { key: 'smtp.username', label: '사용자 이름', kind: 'text', secret: false, clearable: true },
  'smtp.password': { key: 'smtp.password', label: '비밀번호', kind: 'secret', secret: true, clearable: true },
  'smtp.starttls': { key: 'smtp.starttls', label: 'STARTTLS', kind: 'switch', secret: false, clearable: false },
  'smtp.from_address': {
    key: 'smtp.from_address',
    label: '보낸 사람 주소',
    kind: 'text',
    secret: false,
    clearable: true,
  },

  // ── 임베딩 ────────────────────────────────────────────────────────────────
  'embedding.provider': {
    key: 'embedding.provider',
    label: '제공자',
    kind: 'select',
    options: [
      { value: 'OLLAMA', label: 'Ollama' },
      { value: 'VOYAGE', label: 'Voyage' },
      { value: 'OPENAI', label: 'OpenAI' },
    ],
    secret: false,
    clearable: true,
  },
  'embedding.model': {
    key: 'embedding.model',
    label: '모델',
    kind: 'text',
    secret: false,
    // 서버 validateEmbeddingConsistency 가 키가 페이로드에 있으면 blank 를 거부한다(리뷰 M2).
    clearable: false,
    validate: (value) => (value.trim() === '' ? '임베딩 모델은 비어있을 수 없습니다' : undefined),
  },
  'embedding.base_url': {
    key: 'embedding.base_url',
    label: '기본 URL',
    kind: 'text',
    secret: false,
    // 서버 validateEmbeddingConsistency 가 blank 거부 + http(s) 스킴을 강제한다(리뷰 M2).
    // provider 가 OPENAI 일 때만 https 를 강제하는 조건부 규칙은 필드 단일 validate 로 표현할
    // 수 없어(provider 값을 모른다) 의도적으로 클라이언트에 없다 — 서버가 저장 시점에 400 으로 잡는다.
    clearable: false,
    validate: (value) => {
      if (value.trim() === '') return '임베딩 Base URL 은 비어있을 수 없습니다';
      if (!/^https?:\/\/.+/i.test(value))
        return '임베딩 Base URL 은 http:// 또는 https:// 로 시작하는 올바른 주소여야 합니다';
      return undefined;
    },
  },
  'embedding.api_key': {
    key: 'embedding.api_key',
    label: 'API 키',
    kind: 'secret',
    secret: true,
    clearable: true,
  },
};

export interface SettingsTab {
  id: 'ai' | 'smtp' | 'embedding';
  label: string;
  keys: string[];
}

/**
 * 탭 3개. firehub-web 의 4탭에서 `일반` 을 뺐다 — 그쪽에서도 "준비 중입니다" 플레이스홀더다.
 * 각 탭 안의 키 순서가 곧 화면 순서다.
 */
export const SETTINGS_TABS: SettingsTab[] = [
  {
    id: 'ai',
    label: 'AI 에이전트',
    // `ai.api_key`/`ai.agent_type`/`ai.cli_oauth_token` 3키는 여기 없다 — `ai.credential`
    // 문서로 옮겨가 `AiCredentialSection`(`SettingsPage.tsx`)이 이 탭 맨 앞에 전용으로
    // 그린다(Task 13). `ai.model` 도 이제 여기 없다(Ruling #48, fix round 1) — 같은
    // `AiCredentialSection` 이 유형별 필드 바로 다음, 이 범용 렌더러보다 앞에 그리고 직접
    // 쓴다(SETTING_CATALOG 의 `ai.model` 헤더 주석 참고).
    keys: ['ai.max_turns', 'ai.system_prompt', 'ai.temperature', 'ai.max_tokens', 'ai.session_max_tokens'],
  },
  {
    id: 'smtp',
    label: '이메일(SMTP)',
    keys: [
      'smtp.host',
      'smtp.port',
      'smtp.username',
      'smtp.password',
      'smtp.starttls',
      'smtp.from_address',
    ],
  },
  {
    id: 'embedding',
    label: '임베딩',
    keys: ['embedding.provider', 'embedding.model', 'embedding.base_url', 'embedding.api_key'],
  },
];

export const ALL_SETTING_KEYS: string[] = SETTINGS_TABS.flatMap((t) => t.keys);

export function validateSettingValue(key: string, value: string): string | undefined {
  return SETTING_CATALOG[key]?.validate?.(value);
}

/**
 * 배지 종류. 색으로 구별하지 않는다(10-accessibility) — 문구와 아이콘이 대비를 만든다.
 *
 * 잠금 아이콘의 의미가 테넌트 앱과 **반대**라 문구를 다르게 썼다: 테넌트 앱의 `플랫폼 전용` 은
 * "당신이 못 고침", 여기 `전역 고정` 은 "당신만 고칠 수 있고 전부에 적용됨".
 */
export function badgeKindOf(key: string): 'tenant-overridable' | 'global-fixed' {
  return isTenantOverridable(key) ? 'tenant-overridable' : 'global-fixed';
}
