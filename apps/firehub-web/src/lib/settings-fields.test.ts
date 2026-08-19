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
  // 주의: 이것은 **백엔드 계약 테스트가 아니다.** 같은 파일 안의 리터럴 배열과 대조할 뿐이라,
  // 백엔드 SettingsOverridePolicy 를 어떻게 바꿔도 절대 빨개지지 않는다. 예전 이름과 주석은
  // "백엔드와 일치한다"고 주장해서, 존재하지 않는 보호가 있는 것처럼 보이게 했다 — 그 착각이
  // 오히려 위험하므로 이름과 문구를 실제 하는 일로 낮춘다.
  //
  // 백엔드 정책이 넓어지거나 좁아졌을 때의 실제 방어는 이 상수가 아니라 **서버가 내리는
  // tenantEditable 플래그**다. 표시(배지·disabled)와 저장 페이로드가 모두 그 플래그로 구동되므로
  // (SettingsPage 의 fieldState), 이 상수는 "응답에 아예 없는 키"의 폴백 판정에만 남는다.
  // 그 폴백 동작은 아래 resolveSettingFieldState 케이스들이 검증한다.
  it('폴백 판정에 쓰는 6키 상수의 내용이 바뀌지 않았다(회귀 가드, 백엔드 대조 아님)', () => {
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
  // 시드 행이 없어 응답에서 빠지는 키 하나만 담는다. 시드된 키까지 넣으면 백엔드 기본값을
  // 화면이 복사해 들고 있는 셈이 되는데, 값이 어긋나도 아무것도 깨지지 않아 "적용되지 않는
  // 숫자를 자신 있게 보여주는" 상태로 조용히 썩는다.
  it('시드 행이 없는 ai.session_max_tokens 만 담는다', () => {
    expect(BUILTIN_AI_DEFAULTS).toEqual({ 'ai.session_max_tokens': '50000' });
  });

  it('시드된 키·코드 폴백이 없는 키는 담지 않는다', () => {
    // 앞의 4개는 system_settings 에 시드돼 항상 서버 값이 온다(맵을 읽을 일이 없다).
    // system_prompt 는 백엔드가 null 을 그대로 넘겨 코드 폴백이 아예 없고, 비밀 키도 마찬가지다.
    [
      'ai.model',
      'ai.max_turns',
      'ai.temperature',
      'ai.max_tokens',
      'ai.agent_type',
      'ai.system_prompt',
      'ai.api_key',
      'ai.cli_oauth_token',
    ].forEach((key) => expect(BUILTIN_AI_DEFAULTS[key]).toBeUndefined());
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

  it('화이트리스트 소속이어도 서버가 tenantEditable=false 라 하면 잠금이다', () => {
    // 계약: 응답이 있을 때 편집 가능 여부의 권위는 **서버 플래그**이지 화면의 화이트리스트 사본이
    // 아니다. 백엔드 SettingsService.getValue 는 읽을 때마다 화이트리스트를 다시 확인하므로,
    // 플랫폼이 키를 회수하면 그 즉시 tenantEditable=false 가 내려온다. 화면이 자기 상수를
    // 우선하면 입력창이 열린 채 남고 사용자는 저장 시 400 을 받는다.
    // 위 두 케이스(api_key·smtp.host)는 플래그와 화이트리스트가 같은 답을 주어 이 구분을 못 한다.
    expect(
      resolveSettingFieldState('ai.model', setting('ai.model', { tenantEditable: false })),
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
    // 응답에서 아예 빠지는 ai.session_max_tokens 의 실제 상황 — 백엔드가 50000 을 적용하고
    // 있으므로 "기본값 없음"이 아니다. 키 부재와 value=null 은 같게 취급한다.
    expect(resolveSettingFieldState('ai.session_max_tokens', undefined)).toBe('builtin-default');
    expect(
      resolveSettingFieldState(
        'ai.session_max_tokens',
        setting('ai.session_max_tokens', { value: null }),
      ),
    ).toBe('builtin-default');
  });

  it('DB 값도 코드 기본값도 없으면 기본값 없음이다', () => {
    // ai.system_prompt 은 백엔드가 null 을 그대로 넘겨 코드 폴백이 없다.
    expect(
      resolveSettingFieldState('ai.system_prompt', setting('ai.system_prompt', { value: null })),
    ).toBe('no-default');
    expect(resolveSettingFieldState('ai.system_prompt', undefined)).toBe('no-default');
    // 시드된 키가 만약 값 없이 온다면 화면이 대신 채울 값이 없다 — 그 사실을 그대로 표시한다.
    expect(resolveSettingFieldState('ai.max_turns', setting('ai.max_turns', { value: null }))).toBe(
      'no-default',
    );
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
