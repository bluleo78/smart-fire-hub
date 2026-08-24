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
   * `ai.session_max_tokens` 는 어떤 마이그레이션도 시드하지 않아 응답 18행에 없다 —
   * 저장소가 upsert 라 첫 저장에 행이 생기고 `내장 기본값` 배지는 사라진다.
   */
  builtinDefault?: string;
  /** 암호화 저장되고 마스킹되어 내려오는 키. 서버 `SECRET_KEYS` 와 같아야 한다. */
  secret: boolean;
  /**
   * 빈 문자열 저장이 합법인가.
   *
   * false 인 키는 7개다:
   * - `ai.api_key` — 서버 `validateValues` 가 "API 키는 비어있을 수 없습니다" 로 거부한다.
   * - `ai.system_prompt` — 같은 이유("시스템 프롬프트는 비어있을 수 없습니다").
   * - `smtp.starttls` — 스위치라 값이 항상 `'true'`/`'false'` 둘 중 하나다. 비울 대상이 없다.
   * - `ai.max_turns` · `ai.max_tokens` · `ai.session_max_tokens` · `ai.temperature` —
   *   서버가 이 네 키에 `Integer.parseInt` / `Double.parseDouble` 을 **무조건** 호출한다
   *   (`SettingsService.validateValues`). 빈 문자열이 도달하면 `NumberFormatException` 으로
   *   500 이 난다. 그래서 `지우기` 를 렌더하지 않고, 아래 `validate` 가 빈 값을 **거부**한다.
   *
   * 반대로 `smtp.port` 는 서버 switch 의 `default -> {}` 로 떨어지는 키다. 빈 값이 안전하므로
   * 지울 수 있고, 그래서 allowEmpty=true 를 **명시**한다(숫자 키의 기본값은 반대다).
   */
  clearable: boolean;
  /**
   * 값 경계 검증.
   *
   * 원칙은 "서버 `validateValues` 와 같은 경계" 다 — 어긋나면 사용자가 이유 없이 막힌다.
   * 다만 **의도적으로 서버보다 엄격한 키가 두 개** 있고, 이는 사실로 기록해 둔다:
   * - `smtp.port` — 서버에 case 가 없다(`default -> {}`). 1~65535 는 클라이언트만의 규칙이다.
   * - `ai.model` — 서버는 자유 문자열로 둔다. 3개 옵션 제한은 select 드롭다운만의 규칙이다.
   * 나머지 키의 경계는 서버와 1:1 이어야 한다.
   */
  validate?: (value: string) => string | undefined;
}

/** 숫자 키를 비워 저장하려 할 때의 공통 문구. 서버 parse 예외로 넘어가기 전에 여기서 막는다. */
const EMPTY_NUMBER_MESSAGE = '값을 비워 둘 수 없습니다. 숫자를 입력하세요';

/**
 * 정수 검증 공통.
 *
 * `allowEmpty` 기본값이 **false** 인 이유: 서버가 `ai.max_turns`/`ai.max_tokens`/
 * `ai.session_max_tokens` 에 `Integer.parseInt` 를 무조건 부르므로 빈 값을 통과시키면
 * 클라이언트를 지나 서버에서 `NumberFormatException` 이 난다. 빈 값이 실제로 안전한 키
 * (`smtp.port` — 서버 case 없음)만 명시적으로 `true` 를 넘긴다.
 */
function intRange(min: number, max: number, message: string, allowEmpty = false) {
  return (value: string): string | undefined => {
    if (value.trim() === '') return allowEmpty ? undefined : EMPTY_NUMBER_MESSAGE;
    const n = Number(value);
    if (!Number.isInteger(n) || n < min || n > max) return message;
    return undefined;
  };
}

export const SETTING_CATALOG: Record<string, SettingSpec> = {
  // ── AI 에이전트 ────────────────────────────────────────────────────────────
  'ai.model': {
    key: 'ai.model',
    label: '모델',
    kind: 'select',
    // 서버 validateValues 는 이 키를 자유 문자열로 두고 검증하지 않는다 — 목록이 유일한 방어선이다.
    options: [
      { value: 'claude-sonnet-5', label: 'Claude Sonnet 5' },
      { value: 'claude-opus-4-8', label: 'Claude Opus 4.8' },
      { value: 'claude-haiku-4-5', label: 'Claude Haiku 4.5' },
    ],
    secret: false,
    clearable: true,
  },
  'ai.max_turns': {
    key: 'ai.max_turns',
    label: '최대 턴 수',
    kind: 'number',
    secret: false,
    // 서버가 parseInt 를 무조건 부른다 — 비우면 500 이므로 지우기를 렌더하지 않는다.
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
    // 서버가 parseDouble 을 무조건 부른다 — 비우면 500 이므로 지우기를 렌더하지 않는다.
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
    // 서버가 parseInt 를 무조건 부른다 — 비우면 500 이므로 지우기를 렌더하지 않는다.
    clearable: false,
    validate: intRange(1, 65536, '1~65536 사이의 정수를 입력하세요'),
  },
  'ai.session_max_tokens': {
    key: 'ai.session_max_tokens',
    label: '세션 최대 토큰',
    kind: 'number',
    builtinDefault: '50000',
    secret: false,
    // 서버가 parseInt 를 무조건 부른다 — 비우면 500 이므로 지우기를 렌더하지 않는다.
    clearable: false,
    validate: intRange(10000, 200000, '10,000~200,000 사이의 정수를 입력하세요'),
  },
  'ai.api_key': {
    key: 'ai.api_key',
    label: 'API 키',
    kind: 'secret',
    secret: true,
    // 서버가 빈 값을 거부한다 — 지우기 버튼을 보여주고 400 으로 실패시키는 것보다 안 보이는 편이 낫다.
    clearable: false,
    validate: (value) => (value.trim() === '' ? 'API 키는 비워 둘 수 없습니다' : undefined),
  },
  'ai.agent_type': {
    key: 'ai.agent_type',
    label: '에이전트 유형',
    kind: 'select',
    options: [
      { value: 'sdk', label: 'Claude Agent SDK' },
      { value: 'cli', label: 'Claude Code CLI' },
      { value: 'cli-api', label: 'Claude API' },
      { value: 'opencode', label: 'OpenCode' },
    ],
    secret: false,
    clearable: true,
  },
  'ai.cli_oauth_token': {
    key: 'ai.cli_oauth_token',
    label: 'OAuth 토큰',
    kind: 'secret',
    secret: true,
    clearable: true,
  },

  // ── 이메일(SMTP) ──────────────────────────────────────────────────────────
  'smtp.host': { key: 'smtp.host', label: 'SMTP 호스트', kind: 'text', secret: false, clearable: true },
  'smtp.port': {
    key: 'smtp.port',
    label: '포트',
    kind: 'number',
    secret: false,
    // 서버 switch 의 default -> {} 로 떨어지는 키다. 빈 값이 안전하므로 지울 수 있고,
    // 그래서 allowEmpty=true 를 **명시**한다(숫자 키의 기본값은 반대다).
    clearable: true,
    validate: intRange(1, 65535, '1~65535 사이의 정수를 입력하세요', true),
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
  'embedding.model': { key: 'embedding.model', label: '모델', kind: 'text', secret: false, clearable: true },
  'embedding.base_url': { key: 'embedding.base_url', label: '기본 URL', kind: 'text', secret: false, clearable: true },
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
    keys: [
      'ai.model',
      'ai.max_turns',
      'ai.system_prompt',
      'ai.temperature',
      'ai.max_tokens',
      'ai.session_max_tokens',
      'ai.api_key',
      'ai.agent_type',
      'ai.cli_oauth_token',
    ],
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
