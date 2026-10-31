import { describe, expect, it } from 'vitest';

import { countEmailRecipients, normalizeEmail } from './email-recipients';

describe('email-recipients (WD-299)', () => {
  it('normalizeEmail 은 공백을 빼고 소문자로 맞춘다', () => {
    expect(normalizeEmail('  QA@Example.COM ')).toBe('qa@example.com');
  });

  it('대소문자만 다른 외부 이메일과 등록 사용자 주소와 같은 외부 이메일은 한 사람으로 센다', () => {
    // 이슈 재현 config — 등록 사용자 1(qa267low) + 외부 4건 = 실제 3명
    expect(
      countEmailRecipients(
        [28],
        ['QA@Example.COM', 'qa@example.com', 'workloop@example.com', 'qa267low@example.com'],
        ['qa267low@example.com'],
      ),
    ).toBe(3);
  });

  it('등록 사용자 주소를 아직 모르면 외부 이메일끼리만 합친다', () => {
    expect(countEmailRecipients([28], ['a@x.com', 'A@X.com'], [])).toBe(2);
  });

  it('수신자가 없으면 0', () => {
    expect(countEmailRecipients([], [], [])).toBe(0);
  });
});
