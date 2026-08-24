import { describe, expect, it } from 'vitest';

import { isTenantOverridable, TENANT_OVERRIDABLE_KEYS } from './override-policy';
import {
  ALL_SETTING_KEYS,
  badgeKindOf,
  SETTING_CATALOG,
  SETTINGS_TABS,
  validateSettingValue,
} from './settings-catalog';

describe('설정 카탈로그', () => {
  it('19키를 정확히 덮는다 — 서버 화이트리스트 합집합과 같다', () => {
    // 근거: SettingsService.ALLOWED_AI_KEYS(9) + ALLOWED_SMTP_KEYS(6) + ALLOWED_EMBEDDING_KEYS(4)
    expect(ALL_SETTING_KEYS).toHaveLength(19);
    expect(new Set(ALL_SETTING_KEYS).size).toBe(19);
    expect(Object.keys(SETTING_CATALOG).sort()).toEqual([...ALL_SETTING_KEYS].sort());
  });

  it('탭 3개이고 키가 중복 없이 배분된다', () => {
    expect(SETTINGS_TABS.map((t) => t.label)).toEqual(['AI 에이전트', '이메일(SMTP)', '임베딩']);
    expect(SETTINGS_TABS.find((t) => t.id === 'ai')!.keys).toHaveLength(9);
    expect(SETTINGS_TABS.find((t) => t.id === 'smtp')!.keys).toHaveLength(6);
    expect(SETTINGS_TABS.find((t) => t.id === 'embedding')!.keys).toHaveLength(4);
  });

  it('테넌트 재정의 허용은 12키이고 전부 카탈로그에 있다', () => {
    expect(TENANT_OVERRIDABLE_KEYS).toHaveLength(12);
    TENANT_OVERRIDABLE_KEYS.forEach((k) => expect(SETTING_CATALOG[k]).toBeDefined());
  });

  it('비밀 키는 정확히 4개다 — 서버 SECRET_KEYS 와 같다', () => {
    const secrets = ALL_SETTING_KEYS.filter((k) => SETTING_CATALOG[k].secret);
    expect(secrets.sort()).toEqual(
      ['ai.api_key', 'ai.cli_oauth_token', 'embedding.api_key', 'smtp.password'].sort(),
    );
  });

  it('smtp.password 는 비밀이면서 동시에 테넌트 재정의 가능하다 (두 축은 직교)', () => {
    expect(SETTING_CATALOG['smtp.password'].secret).toBe(true);
    expect(badgeKindOf('smtp.password')).toBe('tenant-overridable');
  });

  it('ai.api_key 만 지울 수 없다', () => {
    const notClearableSecrets = ALL_SETTING_KEYS.filter(
      (k) => SETTING_CATALOG[k].secret && !SETTING_CATALOG[k].clearable,
    );
    expect(notClearableSecrets).toEqual(['ai.api_key']);
  });

  it('ai.session_max_tokens 만 내장 기본값을 가진다', () => {
    const withDefault = ALL_SETTING_KEYS.filter((k) => SETTING_CATALOG[k].builtinDefault !== undefined);
    expect(withDefault).toEqual(['ai.session_max_tokens']);
    expect(SETTING_CATALOG['ai.session_max_tokens'].builtinDefault).toBe('50000');
  });

  it('숫자 경계가 서버 validateValues 와 같다 (smtp.port 는 예외 — 클라이언트만의 규칙)', () => {
    expect(validateSettingValue('ai.max_turns', '0')).toBe('1~50 사이의 정수를 입력하세요');
    expect(validateSettingValue('ai.max_turns', '50')).toBeUndefined();
    expect(validateSettingValue('ai.max_turns', '51')).toBe('1~50 사이의 정수를 입력하세요');

    expect(validateSettingValue('ai.temperature', '1.1')).toBe('0.0~1.0 사이의 값을 입력하세요');
    expect(validateSettingValue('ai.temperature', '0.7')).toBeUndefined();

    expect(validateSettingValue('ai.max_tokens', '65537')).toBe('1~65536 사이의 정수를 입력하세요');

    // 하한 10000: 서버가 2026-08-19 에 1000 → 10000 으로 올렸다. 어긋나면 운영자가 저장한 값
    // 때문에 테넌트가 아무 필드도 저장 못 하는 상태가 만들어진다.
    expect(validateSettingValue('ai.session_max_tokens', '9999')).toBe(
      '10,000~200,000 사이의 정수를 입력하세요',
    );
    expect(validateSettingValue('ai.session_max_tokens', '10000')).toBeUndefined();
    expect(validateSettingValue('ai.session_max_tokens', '200001')).toBe(
      '10,000~200,000 사이의 정수를 입력하세요',
    );

    // smtp.port 의 1~65535 는 **서버에 대응 case 가 없다**(default -> {}). 클라이언트가
    // 일부러 더 엄격한 것이고, 그래서 빈 값은 서버가 받아들이므로 여기서도 통과시킨다.
    expect(validateSettingValue('smtp.port', '65536')).toBe('1~65535 사이의 정수를 입력하세요');
    expect(validateSettingValue('smtp.port', '')).toBeUndefined();

    expect(validateSettingValue('ai.system_prompt', '  ')).toBe('시스템 프롬프트를 입력하세요');
    expect(validateSettingValue('ai.api_key', '')).toBe('API 키는 비워 둘 수 없습니다');
  });

  it('서버가 무조건 파싱하는 숫자 4키는 빈 값을 거부하고 지울 수 없다', () => {
    // 근거: SettingsService.validateValues 가 이 네 키에 Integer.parseInt / Double.parseDouble 을
    // 조건 없이 호출한다. 클라이언트가 빈 값을 통과시키면 서버에서 NumberFormatException(500) 이
    // 난다 — "성공처럼 보이는 무동작"이 아니라 이유를 알 수 없는 실패다.
    const parsedKeys = ['ai.max_turns', 'ai.max_tokens', 'ai.session_max_tokens', 'ai.temperature'];
    parsedKeys.forEach((key) => {
      expect(validateSettingValue(key, '')).toBe('값을 비워 둘 수 없습니다. 숫자를 입력하세요');
      expect(validateSettingValue(key, '   ')).toBe('값을 비워 둘 수 없습니다. 숫자를 입력하세요');
      // 지우기 버튼이 렌더되면 사용자가 도달할 수 있는 경로가 생긴다 — 그 경로를 아예 막는다.
      expect(SETTING_CATALOG[key].clearable).toBe(false);
    });
  });

  it('배지 종류가 두 가지뿐이고 전역 고정은 7키다', () => {
    const globalFixed = ALL_SETTING_KEYS.filter((k) => badgeKindOf(k) === 'global-fixed');
    expect(globalFixed.sort()).toEqual(
      [
        'ai.api_key',
        'ai.agent_type',
        'ai.cli_oauth_token',
        'embedding.provider',
        'embedding.model',
        'embedding.base_url',
        'embedding.api_key',
      ].sort(),
    );
    expect(isTenantOverridable('ai.model')).toBe(true);
  });
});
