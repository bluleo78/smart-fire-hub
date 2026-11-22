import { describe, expect, it } from 'vitest';

import { httpUrlError } from './http-url';

/** http(s) URL 형식 검사 공용 함수(WD-909) — 시작 문구와 형식 문구를 가른다. */
describe('httpUrlError', () => {
  it.each([
    '',
    '   ',
    'https://api.example.com',
    '  https://api.example.com/v1?a=b#c  ',
    'HTTPS://api.example.com',
    'http://localhost:8080/x',
    'http://my_api.internal/x',
    'https://user:pw@host.example/v1',
    'https://api.example.com/자료',
    // 호스트 밖 공백은 실행기(httpx)가 %20 으로 보내므로 통과한다
    'https://api.example.com/search?q=seoul city',
    'https://api.example.com/a b',
    'https://api.example.com/v1?name=홍 길동',
    'https://api.example.com/a#b c',
    'https://user:p ss@api.example.com/v1',
    'https://[2606:4700::1111]:8443/x',
    'https://한국.kr/api',
    'https://api.example.com/items/{id}',
    'https://api.example.com/a\\b',
  ])('정상 값 %j 은 통과한다', (value) => {
    expect(httpUrlError(value)).toBeUndefined();
  });

  it.each(['예: https://api.your-service.example', 'ftp://x', 'api.example.com', 'https:/host', '//host'])(
    '시작이 http(s):// 가 아닌 %j 은 시작 문구',
    (value) => {
      expect(httpUrlError(value)).toBe('URL은 http 또는 https로 시작해야 합니다.');
    },
  );

  it.each(['https://', 'https:///data', 'https://?a=b', 'https://exa mple.com', 'https://[::1'])(
    '시작은 맞지만 호스트가 없거나 깨진 %j 은 형식 문구',
    (value) => {
      expect(httpUrlError(value)).toBe('URL 형식이 올바르지 않습니다.');
    },
  );

  it.each([
    'https://exa mple.com',
    'https://exa mple.com/data',
    'https://user@exa mple.com',
    'https://api.example.com/a\tb',
    'https://api.example.com/a\u0000b',
    'https://host:65536',
    'https://\\\\data',
    'https://host\\v1',
  ])('호스트 안 공백·역슬래시·제어문자·잘못된 포트 %j 은 형식 문구', (value) => {
    expect(httpUrlError(value)).toBe('URL 형식이 올바르지 않습니다.');
  });

  it('label 을 주면 두 문구의 앞말이 바뀐다', () => {
    expect(httpUrlError('ftp://x', 'Base URL')).toBe('Base URL은 http 또는 https로 시작해야 합니다.');
    expect(httpUrlError('https://', 'Base URL')).toBe('Base URL 형식이 올바르지 않습니다.');
  });
});
