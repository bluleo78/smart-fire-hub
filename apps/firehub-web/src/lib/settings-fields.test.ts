import { describe, expect, it } from 'vitest';

import type { ResolvedSettingResponse } from '../types/settings';
import {
  indexSettingsByKey,
  isTenantEditableKey,
  resolveSettingFieldState,
  TENANT_EDITABLE_KEYS,
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

describe('TENANT_EDITABLE_KEYS', () => {
  // 주의: 이것은 **백엔드 계약 테스트가 아니다.** 같은 파일 안의 리터럴 배열과 대조할 뿐이라,
  // 백엔드 SettingsOverridePolicy 를 어떻게 바꿔도 절대 빨개지지 않는다. 예전 이름과 주석은
  // "백엔드와 일치한다"고 주장해서, 존재하지 않는 보호가 있는 것처럼 보이게 했다 — 그 착각이
  // 오히려 위험하므로 이름과 문구를 실제 하는 일로 낮춘다.
  //
  // 백엔드 정책이 넓어지거나 좁아졌을 때의 실제 방어는 이 상수가 아니라 **서버가 내리는
  // tenantEditable 플래그**다. 표시(배지·disabled)와 저장 페이로드가 모두 그 플래그로 구동되므로
  // (SmtpSettingsTab 의 fieldState), 이 상수는 "응답에 아예 없는 키"의 폴백 판정에만 남는다.
  // 그 폴백 동작은 아래 resolveSettingFieldState 케이스들이 검증한다.
  it('폴백 판정에 쓰는 SMTP 6키 상수의 내용이 바뀌지 않았다(회귀 가드, 백엔드 대조 아님)', () => {
    // 이 테스트는 "길이는 맞는데 구성원이 틀린" 실수를 잡는 유일한 자리다 — 예를 들어
    // 새 키를 추가하면서 기존 키 하나를 실수로 지워도 개수만 보는 테스트는 통과해 버린다.
    expect([...TENANT_EDITABLE_KEYS].sort()).toEqual(
      [
        'smtp.from_address',
        'smtp.host',
        'smtp.password',
        'smtp.port',
        'smtp.starttls',
        'smtp.username',
      ].sort(),
    );
  });

  it('AI 키는 이 목록에 없다 — AI 동작 설정은 서버가 6키를 항상 내려주고 상태 배지를 쓰지 않는다', () => {
    // 응답에서 빠질 일이 없으므로 폴백이 필요 없다. ai.credential(옛 3키 포함)도 전용 엔드포인트만 쓴다.
    ['ai.model', 'ai.session_max_tokens', 'ai.api_key', 'ai.credential'].forEach((key) =>
      expect(isTenantEditableKey(key)).toBe(false),
    );
  });

  it('embedding 키는 아직 플랫폼 잠금이다', () => {
    // embedding.* 4키는 이번 단계에서 건드리지 않는다 — 여전히 플랫폼 전용이다.
    expect(isTenantEditableKey('embedding.model')).toBe(false);
  });

  it('플랫폼 잠금 키는 편집 허용 키가 아니다', () => {
    ['embedding.model', 'embedding.provider'].forEach((key) => expect(isTenantEditableKey(key)).toBe(false));
  });
});

describe('resolveSettingFieldState', () => {
  it('tenantEditable=false 면 값과 무관하게 잠금이다', () => {
    expect(
      resolveSettingFieldState('ai.credential', setting('ai.credential', { tenantEditable: false })),
    ).toBe('locked');
    expect(
      resolveSettingFieldState('smtp.host', setting('smtp.host', { tenantEditable: false, overridden: true })),
    ).toBe('locked');
  });

  it('화이트리스트 소속이어도 서버가 tenantEditable=false 라 하면 잠금이다', () => {
    // 계약: 응답이 있을 때 편집 가능 여부의 권위는 **서버 플래그**이지 화면의 화이트리스트 사본이
    // 아니다. 화면이 자기 상수를 우선하면 입력창이 열린 채 남고 사용자는 저장 시 400 을 받는다.
    expect(
      resolveSettingFieldState('smtp.from_address', setting('smtp.from_address', { tenantEditable: false })),
    ).toBe('locked');
  });

  it('편집 가능 + 오버라이드 없음 → 상속 중', () => {
    expect(resolveSettingFieldState('smtp.host', setting('smtp.host'))).toBe('inherited');
  });

  it('편집 가능 + 오버라이드 있음 → 재정의', () => {
    expect(
      resolveSettingFieldState('smtp.host', setting('smtp.host', { overridden: true })),
    ).toBe('overridden');
  });

  it('빈 문자열·null 값도 오버라이드가 없으면 상속 중이다', () => {
    expect(resolveSettingFieldState('smtp.username', setting('smtp.username', { value: '' }))).toBe(
      'inherited',
    );
    expect(resolveSettingFieldState('smtp.username', setting('smtp.username', { value: null }))).toBe(
      'inherited',
    );
  });

  it('응답에 없는 편집 허용 키를 잠금으로 떨어뜨리지 않는다', () => {
    // 조회 miss 를 falsy 로 흘리면 편집 가능한 키가 잠긴 것으로 뒤집힌다 — 그 반대를 단언한다.
    TENANT_EDITABLE_KEYS.forEach((key) => {
      expect(resolveSettingFieldState(key, undefined)).not.toBe('locked');
    });
  });

  it('응답에 없는 잠금 키는 잠금으로 남는다(fail-closed)', () => {
    // 폴백이 열리는 방향으로 틀리면 저장 시 400 을 받는 입력창이 생긴다.
    expect(resolveSettingFieldState('ai.api_key', undefined)).toBe('locked');
    expect(resolveSettingFieldState('embedding.api_key', undefined)).toBe('locked');
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
