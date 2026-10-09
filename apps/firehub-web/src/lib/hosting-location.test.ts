import { describe, expect, it } from 'vitest';

import {
  isKnownPublicAiHost,
  isLikelyPublicEndpoint,
  isSameTransportTarget,
  KNOWN_PUBLIC_AI_HOSTS,
  normalizeHosting,
} from './hosting-location';

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

// api KnownPublicAiHostsTest 와 같은 집합·같은 사례 표다 — 두 언어의 목록을 한 곳에 정의할 수 없어 같은 표로 일치를 고정한다.
// 한쪽 행을 바꾸면 다른 쪽도 바꿀 것.
describe('isKnownPublicAiHost', () => {
  it('목록이 api KnownPublicAiHosts.HOSTS 와 같은 집합이다', () => {
    expect([...KNOWN_PUBLIC_AI_HOSTS].sort()).toEqual(
      [
        'api.openai.com',
        'api.anthropic.com',
        'generativelanguage.googleapis.com',
        'api.mistral.ai',
        'api.cohere.com',
        'api.cohere.ai',
        'api.groq.com',
        'openrouter.ai',
        'api.together.xyz',
        'api.deepseek.com',
        'api.voyageai.com',
        'api.fireworks.ai',
        'api.perplexity.ai',
        'api.x.ai',
      ].sort(),
    );
  });

  // "확실"(true)만 서버가 거부하고, 공용일 "가능성"만 있는 주소는 허용(false)한다.
  it.each([
    ['https://api.openai.com/v1', true],
    ['HTTPS://API.OPENAI.COM/v1', true],
    ['https://api.openai.com:443/v1', true],
    ['https://api.openai.com./v1', true],
    ['https://api.anthropic.com', true],
    ['https://generativelanguage.googleapis.com/v1beta/openai', true],
    ['https://openrouter.ai/api/v1', true],
    ['https://api.mistral.ai/v1', true],
    ['https://gateway.example.com', false],
    ['https://openai.example.com/v1', false],
    ['https://api.openai.com.evil.example/v1', false],
    ['http://10.0.0.5:8000/v1', false],
    ['http://localhost:11434', false],
    ['http://ollama:11434', false],
    ['http://llm.corp.internal', false],
    ['not a url', false],
    ['', false],
  ])('%s → %s', (url, expected) => {
    expect(isKnownPublicAiHost(url)).toBe(expected);
  });
});
