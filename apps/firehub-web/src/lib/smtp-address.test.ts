import { describe, expect, it } from 'vitest';

import { isSenderAddressSyntax } from './smtp-address';

// 서버(SettingsService.validateSmtpFromAddress)가 받는 형태와 같은 집합이어야 한다(#728).
// 같은 값들이 백엔드 SmtpHostAndFromAddressValidationTest 에도 있다.
describe('smtp-address', () => {
  it('주소 하나 또는 "표시명 <주소>" 하나를 받는다', () => {
    for (const ok of [
      'noreply@example.com',
      'first.last+tag@mail.ourcompany.co.kr',
      // 사내 릴레이 — 도메인에 점이 없어도 합법적인 발신자다.
      'alerts@mailhost',
      // 발송 코드(helper.setFrom)가 받는 표시명 형태 — 막으면 과잉 차단이다.
      'Fire Hub <noreply@example.com>',
      '스마트 파이어 허브 <noreply@example.com>',
      '"Hub, Fire" <noreply@example.com>',
      '<noreply@example.com>',
    ])
      expect(isSenderAddressSyntax(ok), ok).toBe(true);
  });

  it('주소가 아닌 값은 받지 않는다', () => {
    for (const bad of [
      'not-an-email',
      '',
      '   ',
      ' noreply@example.com',
      'noreply@example.com ',
      '@example.com',
      'noreply@',
      'a@b@example.com',
      'no reply@example.com',
      'a@example.com, b@example.com',
      'Fire Hub <not-an-email>',
      'Fire Hub <noreply@example.com',
    ])
      expect(isSenderAddressSyntax(bad), bad).toBe(false);
  });
});
