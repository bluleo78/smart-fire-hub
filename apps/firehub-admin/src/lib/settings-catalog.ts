export type SettingKind = 'text' | 'select' | 'secret';

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
   * false 인 키는 2개다: `embedding.model` · `embedding.base_url` — 서버
   * `validateEmbeddingConsistency` 가 페이로드에 키가 있으면 blank 를 거부한다(리뷰 M2).
   */
  clearable: boolean;
  /**
   * 값 경계 검증.
   *
   * 원칙은 "서버와 같은 경계" 다 — 어긋나면 사용자가 이유 없이 막히거나(더 엄격), 클라이언트를
   * 지나 서버 예외 문구를 그대로 보게 된다(더 느슨).
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

/**
 * 플랫폼 설정 키 카탈로그(임베딩 4키).
 *
 * 이메일(SMTP) 설정은 여기 없다 — 워크스페이스 전용으로 바뀌어(#712) 플랫폼 값이 없어졌다.
 * 서버도 플랫폼 설정 응답에서 `smtp.*` 를 빼고, 플랫폼 쓰기에 `smtp.*` 가 섞이면 요청 전체를
 * 400 으로 거부한다. AI 설정(`ai.*`)도 같은 이유로 없다.
 */
export const SETTING_CATALOG: Record<string, SettingSpec> = {
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

/**
 * 화면에 그리는 키 순서. 카탈로그에서 파생한다 — 문자열 키의 삽입 순서가 보존되므로
 * `SETTING_CATALOG` 에 적은 순서가 곧 카드 안의 필드 순서다. 목록을 손으로 한 번 더 적으면
 * 카탈로그와 어긋날 수 있어 두지 않는다.
 */
export const ALL_SETTING_KEYS: string[] = Object.keys(SETTING_CATALOG);

export function validateSettingValue(key: string, value: string): string | undefined {
  return SETTING_CATALOG[key]?.validate?.(value);
}
