import { describe, expect, it } from 'vitest';

import type { ResolvedSettingResponse } from '../types/settings';
import {
  BUILTIN_AI_DEFAULTS,
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

describe('BUILTIN_AI_DEFAULTS', () => {
  // 백엔드 AiAgentProxyService(L226~232)의 폴백 인자와 값이 같아야 한다 — 어긋나면 화면이
  // 실제 적용되지 않는 값을 "내장 기본값"이라고 보여준다.
  it('AiAgentProxyService 의 코드 폴백 값과 일치한다', () => {
    expect(BUILTIN_AI_DEFAULTS).toEqual({
      'ai.agent_type': 'sdk',
      'ai.model': 'claude-sonnet-5',
      'ai.max_turns': '10',
      'ai.temperature': '1.0',
      'ai.max_tokens': '16384',
      'ai.session_max_tokens': '50000',
    });
  });

  it('코드 폴백이 없는 키는 담지 않는다', () => {
    // system_prompt 는 null 이 그대로 전달되고, 비밀 키에는 기본값이라는 개념이 없다.
    ['ai.system_prompt', 'ai.api_key', 'ai.cli_oauth_token'].forEach((key) =>
      expect(BUILTIN_AI_DEFAULTS[key]).toBeUndefined(),
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

  it('DB 값이 없고 코드 기본값이 있으면 내장 기본값이다', () => {
    // 응답에서 아예 빠지는 ai.session_max_tokens 의 실제 상황 — AiAgentProxyService 가 50000 을
    // 적용하고 있으므로 "기본값 없음"이 아니다.
    expect(resolveSettingFieldState('ai.session_max_tokens', undefined)).toBe('builtin-default');
    expect(
      resolveSettingFieldState('ai.max_turns', setting('ai.max_turns', { value: null })),
    ).toBe('builtin-default');
  });

  it('DB 값도 코드 기본값도 없으면 기본값 없음이다', () => {
    // ai.system_prompt 은 백엔드가 null 을 그대로 넘겨 코드 기본값이 없다.
    expect(
      resolveSettingFieldState('ai.system_prompt', setting('ai.system_prompt', { value: null })),
    ).toBe('no-default');
    expect(resolveSettingFieldState('ai.system_prompt', undefined)).toBe('no-default');
  });

  it('응답에 없는 편집 허용 키를 잠금으로 떨어뜨리지 않는다', () => {
    // 조회 miss 를 falsy 로 흘리면 편집 가능한 키가 잠긴 것으로 뒤집힌다 — 그 반대를 단언한다.
    TENANT_EDITABLE_AI_KEYS.forEach((key) => {
      expect(resolveSettingFieldState(key, undefined)).not.toBe('locked');
    });
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
