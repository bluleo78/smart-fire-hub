import { describe, expect, it } from 'vitest';

import { isLikelyPublicEndpoint, isSameTransportTarget, normalizeHosting } from './hosting-location';

describe('isLikelyPublicEndpoint', () => {
  it.each([
    ['https://api.openai.com/v1', true],
    ['https://gateway.example.com', true],
    ['http://10.0.0.5:8000/v1', false],
    ['http://192.168.1.20', false],
    ['http://172.16.3.4', false],
    ['http://172.32.0.1', true],
    ['http://127.0.0.1:11434', false],
    ['http://localhost:11434', false],
    ['http://host.docker.internal:11434', false],
    ['http://ollama:11434', false],
    ['http://llm.corp.internal', false],
    ['not a url', false],
    ['', false],
  ])('%s → %s', (url, expected) => {
    expect(isLikelyPublicEndpoint(url)).toBe(expected);
  });
});

describe('isSameTransportTarget', () => {
  it('앞뒤 공백·끝 슬래시 하나는 같은 목적지로 본다(서버 UrlUtils.normalizeBaseUrl 과 같은 규칙)', () => {
    expect(isSameTransportTarget('http://10.0.0.5/v1/', ' http://10.0.0.5/v1 ')).toBe(true);
  });

  it('주소가 다르면 다른 목적지다', () => {
    expect(isSameTransportTarget('http://10.0.0.5/v1', 'https://api.openai.com/v1')).toBe(false);
  });

  it('빈 값과 undefined 는 같다', () => {
    expect(isSameTransportTarget(undefined, '')).toBe(true);
  });
});

describe('normalizeHosting', () => {
  it.each([
    ['SELF_HOSTED', 'SELF_HOSTED'],
    ['EXTERNAL', 'EXTERNAL'],
    ['', 'EXTERNAL'],
    [undefined, 'EXTERNAL'],
    ['self_hosted', 'EXTERNAL'],
  ])('%s → %s', (raw, expected) => {
    expect(normalizeHosting(raw)).toBe(expected);
  });
});
