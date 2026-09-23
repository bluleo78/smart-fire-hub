import { describe, expect, it } from 'vitest';

import { ALL_SETTING_KEYS, SETTING_CATALOG, validateSettingValue } from './settings-catalog';

describe('설정 카탈로그', () => {
  it('임베딩 4키를 정확히 덮는다 — 서버 플랫폼 설정 응답과 같은 집합', () => {
    // 근거: #712 이후 서버 플랫폼 응답은 ALLOWED_EMBEDDING_KEYS(4)뿐이다.
    // SMTP(smtp.*)·AI(ai.*)는 워크스페이스 전용이라 플랫폼 카탈로그에 하나도 없다 — 서버도 플랫폼
    // 응답에서 빼고, 플랫폼 쓰기에 섞이면 요청 전체를 거부한다.
    expect([...ALL_SETTING_KEYS]).toEqual([
      'embedding.provider',
      'embedding.model',
      'embedding.base_url',
      'embedding.api_key',
    ]);
    expect(new Set(ALL_SETTING_KEYS).size).toBe(4);
    expect(Object.keys(SETTING_CATALOG).sort()).toEqual([...ALL_SETTING_KEYS].sort());
    for (const prefix of ['smtp.', 'ai.']) {
      expect(Object.keys(SETTING_CATALOG).filter((k) => k.startsWith(prefix))).toEqual([]);
    }
  });

  it('비밀 키는 embedding.api_key 하나다', () => {
    const secrets = ALL_SETTING_KEYS.filter((k) => SETTING_CATALOG[k].secret);
    expect(secrets).toEqual(['embedding.api_key']);
  });

  it('지울 수 없는 비밀 키는 없다', () => {
    const notClearableSecrets = ALL_SETTING_KEYS.filter(
      (k) => SETTING_CATALOG[k].secret && !SETTING_CATALOG[k].clearable,
    );
    expect(notClearableSecrets).toEqual([]);
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

  it('지울 수 없는 키는 embedding.model·embedding.base_url 2개다 (리뷰 M2)', () => {
    // 대조군: 지울 수 있는 키(embedding.provider)는 이 집합에 없어야 한다 — 그렇지 않으면 필터가
    // 아무것도 걸러내지 않아도 통과하는 공허한 단언이 된다.
    const notClearable = ALL_SETTING_KEYS.filter((k) => !SETTING_CATALOG[k].clearable);
    expect(notClearable.sort()).toEqual(['embedding.base_url', 'embedding.model']);
    expect(notClearable).not.toContain('embedding.provider');
  });
});
