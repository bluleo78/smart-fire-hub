import { describe, expect, it } from 'vitest';

import type { ResolvedSettingResponse } from '../types/settings';
import {
  indexSettingsByKey,
  isTenantEditableAiKey,
  resolveSettingFieldState,
  TENANT_EDITABLE_AI_KEYS,
} from './settings-fields';

// 응답 1건을 만드는 헬퍼 — 판정에 쓰이는 필드만 인자로 받는다.
function setting(
  key: string,
  overrides: Partial<ResolvedSettingResponse> = {},
): ResolvedSettingResponse {
  return {
    key,
    value: '1.0',
    description: null,
    updatedAt: null,
    overridden: false,
    tenantEditable: true,
    ...overrides,
  };
}

describe('TENANT_EDITABLE_AI_KEYS', () => {
  // 백엔드 SettingsOverridePolicy 화이트리스트와 정확히 같아야 한다 — 넓으면 400 을 부르고,
  // 좁으면 편집 가능한 항목이 화면에서 사라진다.
  it('백엔드 화이트리스트 6키와 일치한다', () => {
    expect([...TENANT_EDITABLE_AI_KEYS].sort()).toEqual(
      [
        'ai.max_tokens',
        'ai.max_turns',
        'ai.model',
        'ai.session_max_tokens',
        'ai.system_prompt',
        'ai.temperature',
      ].sort(),
    );
  });

  it('플랫폼 잠금 키는 편집 허용 키가 아니다', () => {
    ['ai.agent_type', 'ai.api_key', 'ai.cli_oauth_token', 'smtp.host', 'embedding.model'].forEach(
      (key) => expect(isTenantEditableAiKey(key)).toBe(false),
    );
  });
});

describe('resolveSettingFieldState', () => {
  it('tenantEditable=false 면 값과 무관하게 잠금이다', () => {
    expect(
      resolveSettingFieldState('ai.api_key', setting('ai.api_key', { tenantEditable: false })),
    ).toBe('locked');
    expect(
      resolveSettingFieldState('smtp.host', setting('smtp.host', { tenantEditable: false, overridden: true })),
    ).toBe('locked');
  });

  it('편집 가능 + 오버라이드 없음 → 상속 중', () => {
    expect(resolveSettingFieldState('ai.temperature', setting('ai.temperature'))).toBe('inherited');
  });

  it('편집 가능 + 오버라이드 있음 → 재정의', () => {
    expect(
      resolveSettingFieldState('ai.temperature', setting('ai.temperature', { overridden: true })),
    ).toBe('overridden');
  });

  it('빈 문자열은 정상적인 기본값이므로 상속 중이다', () => {
    // 시스템 프롬프트처럼 빈 값이 합법인 키가 있어 "값 없음"과 섞으면 안 된다.
    expect(
      resolveSettingFieldState('ai.system_prompt', setting('ai.system_prompt', { value: '' })),
    ).toBe('inherited');
  });

  it('value=null 이면 기본값 없음이다', () => {
    expect(
      resolveSettingFieldState('ai.max_turns', setting('ai.max_turns', { value: null })),
    ).toBe('no-default');
  });

  it('응답에 없는 편집 허용 키는 기본값 없음이다', () => {
    // 플랫폼 시드 행도 오버라이드도 없는 ai.session_max_tokens 의 실제 상황.
    expect(resolveSettingFieldState('ai.session_max_tokens', undefined)).toBe('no-default');
  });

  it('응답에 없는 잠금 키는 잠금으로 남는다(fail-closed)', () => {
    // 폴백이 열리는 방향으로 틀리면 저장 시 400 을 받는 입력창이 생긴다.
    expect(resolveSettingFieldState('ai.api_key', undefined)).toBe('locked');
    expect(resolveSettingFieldState('embedding.provider', undefined)).toBe('locked');
  });
});

describe('indexSettingsByKey', () => {
  it('키로 조회할 수 있게 인덱싱한다', () => {
    const byKey = indexSettingsByKey([setting('ai.model'), setting('ai.temperature')]);
    expect(Object.keys(byKey)).toEqual(['ai.model', 'ai.temperature']);
    expect(byKey['ai.model'].key).toBe('ai.model');
  });

  it('빈 목록은 빈 객체가 된다 — 조회 실패 시에도 안전하게 undefined 로 폴백한다', () => {
    expect(indexSettingsByKey([])['ai.model']).toBeUndefined();
  });
});
