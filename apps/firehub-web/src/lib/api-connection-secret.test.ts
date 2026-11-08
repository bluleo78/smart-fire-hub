import { describe, expect, it } from 'vitest';

import { missingSecretMessage, normalizeSecret } from './api-connection-secret';

/** 화면 빈 비밀 판정이 서버(Java strip·isBlank·ASCII \s 정규식)와 글자 단위로 같은지(WD-639). */
describe('api-connection-secret', () => {
  it('접두어만 있는 토큰(겹친 것·대소문자 무관)은 빈 토큰이다', () => {
    for (const token of ['', '   ', 'Bearer', 'Bearer   ', ' bearer bearer ', 'BEARER\t']) {
      expect(missingSecretMessage('BEARER', token)).toBe('Bearer 토큰을 입력하세요.');
    }
    expect(missingSecretMessage('BEARER', 'Bearer abc')).toBeNull();
    expect(missingSecretMessage('BEARER', 'bearerXYZ')).toBeNull();
    expect(normalizeSecret('BEARER', '  Bearer Bearer abc ')).toBe('abc');
  });

  it('Java 공백(유니코드 공백 구분자 포함)은 비운 것으로 본다', () => {
    expect(missingSecretMessage('API_KEY', '　 \u001F')).toBe('API 키를 입력하세요.');
    expect(missingSecretMessage('BEARER', ' Bearer　')).toBe('Bearer 토큰을 입력하세요.');
  });

  it('NBSP·U+2007·U+202F·BOM 은 서버처럼 공백이 아니다 — 값으로 남는다', () => {
    for (const ch of [' ', ' ', ' ', '﻿']) {
      expect(missingSecretMessage('API_KEY', ch)).toBeNull();
      expect(missingSecretMessage('BEARER', ch)).toBeNull();
    }
    // 서버 정규식의 \s 는 ASCII 공백뿐이라 「Bearer<NBSP>」 의 접두어는 떼지 않는다
    expect(normalizeSecret('BEARER', 'Bearer ')).toBe('Bearer ');
  });
});
