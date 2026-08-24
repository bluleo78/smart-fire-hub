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

  it('숫자 경계가 서버와 같다', () => {
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

    // 정정(리뷰 H2): SettingsService.validateSmtpPort 가 서버에서도 같은 1~65535 범위를
    // 강제하고 빈 값·비숫자도 거부한다. "서버 미검증"은 validateValues 스위치만 본 오판이었다.
    expect(validateSettingValue('smtp.port', '65536')).toBe('1~65535 사이의 정수를 입력하세요');
    expect(validateSettingValue('smtp.port', '')).toBe('값을 비워 둘 수 없습니다. 숫자를 입력하세요');
    expect(validateSettingValue('smtp.port', '587')).toBeUndefined();

    expect(validateSettingValue('ai.system_prompt', '  ')).toBe('시스템 프롬프트를 입력하세요');
    expect(validateSettingValue('ai.api_key', '')).toBe('API 키는 비워 둘 수 없습니다');
  });

  it('서버가 무조건 파싱하는 숫자 4키는 빈 값을 거부하고 지울 수 없다', () => {
    // 근거: SettingsService.validateValues 가 이 네 키에 Integer.parseInt / Double.parseDouble 을
    // 조건 없이 호출한다. 클라이언트가 빈 값을 통과시키면 서버가 400 으로 거부한다(500 아님,
    // 리뷰 M1 정정) — 가드는 500 회피가 아니라 영문 미번역 예외 문구 대신 한국어 안내를 주기 위함이다.
    const parsedKeys = ['ai.max_turns', 'ai.max_tokens', 'ai.session_max_tokens', 'ai.temperature'];
    parsedKeys.forEach((key) => {
      expect(validateSettingValue(key, '')).toBe('값을 비워 둘 수 없습니다. 숫자를 입력하세요');
      expect(validateSettingValue(key, '   ')).toBe('값을 비워 둘 수 없습니다. 숫자를 입력하세요');
      // 지우기 버튼이 렌더되면 사용자가 도달할 수 있는 경로가 생긴다 — 그 경로를 아예 막는다.
      expect(SETTING_CATALOG[key].clearable).toBe(false);
    });
  });

  it('정수 형태 검증은 Integer.parseInt 와 같은 것만 통과시킨다 (리뷰 H1)', () => {
    // Number(value) 를 그대로 썼다면 이 넷은 전부 유효한 숫자로 통과했을 것이다 — 서버
    // Integer.parseInt 는 전부 NumberFormatException 으로 거부한다(공백·소수점·지수 표기 불허).
    const malformed = ['20.0', '1e3', ' 20 ', '5e4'];
    malformed.forEach((value) => {
      expect(validateSettingValue('ai.max_turns', value)).toBe('1~50 사이의 정수를 입력하세요');
      expect(validateSettingValue('smtp.port', value)).toBe('1~65535 사이의 정수를 입력하세요');
    });
    // 대조군: 서버 parseInt 가 실제로 받아들이는 형태(선행 부호 포함)는 통과해야 한다.
    expect(validateSettingValue('ai.max_turns', '+20')).toBeUndefined();
    expect(validateSettingValue('ai.max_turns', '20')).toBeUndefined();
  });

  it('임베딩 model/base_url 은 blank 를 거부하고 base_url 은 http(s) 스킴을 강제한다 (리뷰 M2)', () => {
    expect(validateSettingValue('embedding.model', '')).toBe('임베딩 모델은 비어있을 수 없습니다');
    expect(validateSettingValue('embedding.model', 'bge-m3')).toBeUndefined();

    expect(validateSettingValue('embedding.base_url', '')).toBe('임베딩 Base URL 은 비어있을 수 없습니다');
    expect(validateSettingValue('embedding.base_url', 'localhost:11434')).toBe(
      '임베딩 Base URL 은 http:// 또는 https:// 로 시작하는 올바른 주소여야 합니다',
    );
    expect(validateSettingValue('embedding.base_url', 'http://localhost:11434')).toBeUndefined();
    expect(validateSettingValue('embedding.base_url', 'https://api.openai.com')).toBeUndefined();
  });

  it('지울 수 없는 키는 정확히 10개다 — smtp.port·embedding.model·embedding.base_url 포함(리뷰 H2/M2/L3)', () => {
    // 대조군: 지울 수 있는 키(smtp.host)는 이 집합에 없어야 한다 — 그렇지 않으면 필터가
    // 아무것도 걸러내지 않아도 통과하는 공허한 단언이 된다.
    const notClearable = ALL_SETTING_KEYS.filter((k) => !SETTING_CATALOG[k].clearable);
    expect(notClearable.sort()).toEqual(
      [
        'ai.api_key',
        'ai.system_prompt',
        'ai.max_turns',
        'ai.max_tokens',
        'ai.session_max_tokens',
        'ai.temperature',
        'smtp.starttls',
        'smtp.port',
        'embedding.model',
        'embedding.base_url',
      ].sort(),
    );
    expect(notClearable).not.toContain('smtp.host');
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
