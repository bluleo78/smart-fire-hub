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
  it('10키를 정확히 덮는다 — 서버 플랫폼 쓰기 화이트리스트와 같은 집합', () => {
    // 근거: ALLOWED_SMTP_KEYS(6) + ALLOWED_EMBEDDING_KEYS(4) = 10.
    // AI 설정(ai.*)은 워크스페이스 전용이라 플랫폼 카탈로그에 하나도 없다 — 서버도 플랫폼 응답에서
    // 빼고 플랫폼 쓰기에서 거부한다.
    expect(ALL_SETTING_KEYS).toHaveLength(10);
    expect(new Set(ALL_SETTING_KEYS).size).toBe(10);
    expect(ALL_SETTING_KEYS.filter((k) => k.startsWith('ai.'))).toEqual([]);
    expect(Object.keys(SETTING_CATALOG).filter((k) => k.startsWith('ai.'))).toEqual([]);
    expect(Object.keys(SETTING_CATALOG).sort()).toEqual([...ALL_SETTING_KEYS].sort());
  });

  it('탭은 이메일·임베딩 2개이고 AI 에이전트 탭은 없다', () => {
    expect(SETTINGS_TABS.map((t) => t.label)).toEqual(['이메일(SMTP)', '임베딩']);
    expect(SETTINGS_TABS.find((t) => t.id === 'smtp')!.keys).toHaveLength(6);
    expect(SETTINGS_TABS.find((t) => t.id === 'embedding')!.keys).toHaveLength(4);
  });

  it('워크스페이스가 자기 값을 쓸 수 있는 키는 SMTP 6키다 — 서버 SettingsOverridePolicy.TWO_PLANE 과 같다', () => {
    expect([...TENANT_OVERRIDABLE_KEYS].sort()).toEqual(
      [
        'smtp.host',
        'smtp.port',
        'smtp.username',
        'smtp.password',
        'smtp.starttls',
        'smtp.from_address',
      ].sort(),
    );
    TENANT_OVERRIDABLE_KEYS.forEach((k) => expect(SETTING_CATALOG[k]).toBeDefined());
    expect(isTenantOverridable('ai.model')).toBe(false);
  });

  it('비밀 키는 정확히 2개다', () => {
    const secrets = ALL_SETTING_KEYS.filter((k) => SETTING_CATALOG[k].secret);
    expect(secrets.sort()).toEqual(['embedding.api_key', 'smtp.password'].sort());
  });

  it('smtp.password 는 비밀이면서 동시에 테넌트 재정의 가능하다 (두 축은 직교)', () => {
    expect(SETTING_CATALOG['smtp.password'].secret).toBe(true);
    expect(badgeKindOf('smtp.password')).toBe('tenant-overridable');
  });

  it('지울 수 없는 비밀 키는 없다', () => {
    const notClearableSecrets = ALL_SETTING_KEYS.filter(
      (k) => SETTING_CATALOG[k].secret && !SETTING_CATALOG[k].clearable,
    );
    expect(notClearableSecrets).toEqual([]);
  });

  it('숫자 경계가 서버와 같다', () => {
    // 정정(리뷰 H2): SettingsService.validateSmtpPort 가 서버에서도 같은 1~65535 범위를
    // 강제하고 빈 값·비숫자도 거부한다. "서버 미검증"은 validateValues 스위치만 본 오판이었다.
    expect(validateSettingValue('smtp.port', '65536')).toBe('1~65535 사이의 정수를 입력하세요');
    expect(validateSettingValue('smtp.port', '')).toBe('값을 비워 둘 수 없습니다. 숫자를 입력하세요');
    expect(validateSettingValue('smtp.port', '587')).toBeUndefined();
  });

  it('정수 형태 검증은 Integer.parseInt 와 같은 것만 통과시킨다 (리뷰 H1)', () => {
    // Number(value) 를 그대로 썼다면 이 넷은 전부 유효한 숫자로 통과했을 것이다 — 서버
    // Integer.parseInt 는 전부 NumberFormatException 으로 거부한다(공백·소수점·지수 표기 불허).
    const malformed = ['20.0', '1e3', ' 20 ', '5e4'];
    malformed.forEach((value) => {
      expect(validateSettingValue('smtp.port', value)).toBe('1~65535 사이의 정수를 입력하세요');
    });
    // 대조군: 서버 parseInt 가 실제로 받아들이는 형태(선행 부호 포함)는 통과해야 한다.
    expect(validateSettingValue('smtp.port', '+587')).toBeUndefined();
    expect(validateSettingValue('smtp.port', '587')).toBeUndefined();
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

  it('지울 수 없는 키는 정확히 4개다 — smtp.port·embedding.model·embedding.base_url 포함(리뷰 H2/M2/L3)', () => {
    // 대조군: 지울 수 있는 키(smtp.host)는 이 집합에 없어야 한다 — 그렇지 않으면 필터가
    // 아무것도 걸러내지 않아도 통과하는 공허한 단언이 된다.
    const notClearable = ALL_SETTING_KEYS.filter((k) => !SETTING_CATALOG[k].clearable);
    expect(notClearable.sort()).toEqual(
      [
        'smtp.starttls',
        'smtp.port',
        'embedding.model',
        'embedding.base_url',
      ].sort(),
    );
    expect(notClearable).not.toContain('smtp.host');
  });

  it('배지 종류가 두 가지뿐이고 전역 고정은 4키다', () => {
    const globalFixed = ALL_SETTING_KEYS.filter((k) => badgeKindOf(k) === 'global-fixed');
    expect(globalFixed.sort()).toEqual(
      ['embedding.provider', 'embedding.model', 'embedding.base_url', 'embedding.api_key'].sort(),
    );
    // 나머지(SMTP 6키)는 전부 워크스페이스가 자기 값을 쓸 수 있다.
    expect(ALL_SETTING_KEYS.filter((k) => badgeKindOf(k) === 'tenant-overridable')).toHaveLength(6);
  });
});
