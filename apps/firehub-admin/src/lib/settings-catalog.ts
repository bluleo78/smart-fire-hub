import { isTenantOverridable } from './override-policy';

export type SettingKind = 'text' | 'number' | 'select' | 'switch' | 'secret';

export interface SettingSpec {
  key: string;
  /** 한국어 고정 라벨. 필드 설명은 서버 `description` 을 그대로 쓰므로 여기 복제하지 않는다. */
  label: string;
  kind: SettingKind;
  options?: { value: string; label: string }[];
  /** 암호화 저장되고 마스킹되어 내려오는 키. 서버 `SECRET_KEYS` 와 같아야 한다. */
  secret: boolean;
  /**
   * 빈 문자열 저장이 합법인가.
   *
   * false 인 키는 4개다:
   * - `smtp.starttls` — 스위치라 값이 항상 `'true'`/`'false'` 둘 중 하나다. 비울 대상이 없다.
   * - `embedding.model` · `embedding.base_url` — 서버 `validateEmbeddingConsistency` 가 페이로드에
   *   키가 있으면 blank 를 거부한다(리뷰 M2).
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

/** 숫자 키를 비워 저장하려 할 때의 문구. 서버 parse 예외로 넘어가기 전에 여기서 막는다. */
const EMPTY_NUMBER_MESSAGE = '값을 비워 둘 수 없습니다. 숫자를 입력하세요';

/**
 * 정수 검증.
 *
 * 빈 값은 거부한다. `intRange` 를 쓰는 숫자 키(`smtp.port`)는 서버가 값을 무조건 파싱한다 —
 * `SettingsService.validateSmtpPort`(리뷰 H2 정정: 이전에는 이 키만 서버 미검증이라 믿고
 * 빈 값을 허용했으나, 실측 결과 서버가 여기도 검증한다).
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

/**
 * 플랫폼 설정 키 카탈로그(이메일·임베딩 10키).
 *
 * AI 설정(`ai.*`)은 여기 없다 — 워크스페이스(테넌트)별 설정이라 플랫폼 설정에 속하지 않는다.
 * 서버도 플랫폼 설정 응답에서 `ai.*` 를 빼고, 플랫폼 쓰기에서 거부한다.
 */
export const SETTING_CATALOG: Record<string, SettingSpec> = {
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
  id: 'smtp' | 'embedding';
  label: string;
  keys: string[];
}

/**
 * 탭 2개(이메일·임베딩). 각 탭 안의 키 순서가 곧 화면 순서다.
 * AI 에이전트 탭은 없다 — AI 설정은 각 워크스페이스의 설정 화면에서 관리한다.
 */
export const SETTINGS_TABS: SettingsTab[] = [
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
