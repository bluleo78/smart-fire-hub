import { describe, expect, it } from 'vitest';

import { generateTemporaryPassword, PASSWORD_POLICY } from './temp-password';

describe('generateTemporaryPassword', () => {
  it('항상 서버 정책(소·대·숫자 각 1+, 8~128자)을 만족한다', () => {
    for (let i = 0; i < 500; i++) {
      const pw = generateTemporaryPassword();
      expect(pw).toHaveLength(12);
      expect(PASSWORD_POLICY.test(pw)).toBe(true);
    }
  });

  it('구두 전달 시 헷갈리는 문자(0 O 1 l I)를 쓰지 않는다', () => {
    for (let i = 0; i < 500; i++) {
      expect(generateTemporaryPassword()).not.toMatch(/[0O1lI]/);
    }
  });

  it('길이를 지정할 수 있다', () => {
    expect(generateTemporaryPassword(16)).toHaveLength(16);
  });
});
