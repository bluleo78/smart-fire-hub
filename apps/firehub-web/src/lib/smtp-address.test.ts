import { describe, expect, it } from 'vitest';

import { isSenderAddressSyntax } from './smtp-address';

// 서버(SettingsService.validateSmtpFromAddress)가 받는 형태와 같은 집합이어야 한다(#728).
// 두 목록은 백엔드 SmtpHostAndFromAddressValidationTest 의 수용·거부 목록과 같은 값이다 —
// 한쪽만 고치면 "칸 검증은 통과하고 저장은 400" 이 다시 생긴다.
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
      // 쉼표·괄호가 든 표시명은 따옴표로 감싸면 된다.
      '"Hub, Fire" <noreply@example.com>',
      '"Fire Hub (알림)" <noreply@example.com>',
      'Fire.Hub <noreply@example.com>',
      '사용자@example.com',
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
      'team: a@example.com;',
      // 따옴표 없는 쉼표·세미콜론·괄호 — 메일 주소 파서가 구분자·주석으로 읽어 서버가 거부한다.
      'Hub, Fire <noreply@example.com>',
      'Hub; Fire <noreply@example.com>',
      'Fire (Hub <noreply@example.com>',
      'Team@Home <noreply@example.com>',
      '"Unbalanced <noreply@example.com>',
      'Fire Hub <noreply@example.com> x',
      'x <a@example.com> <b@example.com>',
      'a..b@example.com',
      '.a@example.com',
      'a@example..com',
      'a@example.com.',
      'a(c)@example.com',
      'a,b@example.com',
      'a@[127.0.0.1]',
    ])
      expect(isSenderAddressSyntax(bad), bad).toBe(false);
  });
});
