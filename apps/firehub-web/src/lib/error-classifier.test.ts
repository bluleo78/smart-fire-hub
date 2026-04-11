/**
 * error-classifier 단위 테스트 — 에러 메시지 패턴 분류.
 */
import { describe, expect, it } from 'vitest';

import { classifyError } from './error-classifier';

describe('classifyError', () => {
  it('null/undefined/empty → unknown (메시지 없음)', () => {
    expect(classifyError(null).type).toBe('unknown');
    expect(classifyError(undefined).type).toBe('unknown');
    expect(classifyError('').type).toBe('unknown');
    expect(classifyError(null).label).toBe('알 수 없는 오류');
  });

  it('AI 키워드 감지', () => {
    expect(classifyError('Rate limit exceeded').type).toBe('ai');
    expect(classifyError('Claude model overloaded').type).toBe('ai');
    expect(classifyError('Anthropic API error').type).toBe('ai');
    expect(classifyError('token usage exceeded').type).toBe('ai');
  });

  it('데이터 접근 키워드 감지', () => {
    expect(classifyError('Database connection refused').type).toBe('data');
    expect(classifyError('Query timeout').type).toBe('data');
    expect(classifyError('SQL syntax error').type).toBe('data');
    expect(classifyError('Datasource not available').type).toBe('data');
  });

  it('채널 전달 키워드 감지', () => {
    expect(classifyError('Email delivery failed').type).toBe('channel');
    expect(classifyError('SMTP connection').type).toBe('data'); // connection이 먼저 매칭됨 — data 패턴이 우선
    expect(classifyError('Mail server error').type).toBe('channel');
  });

  it('매칭 안 되는 메시지는 fallback unknown', () => {
    const result = classifyError('Something weird happened');
    expect(result.type).toBe('unknown');
    expect(result.label).toBe('기타 오류');
  });

  it('대소문자 무관', () => {
    expect(classifyError('RATE LIMIT').type).toBe('ai');
    expect(classifyError('DATABASE DOWN').type).toBe('data');
  });

  it('반환 객체에 icon/guide 포함', () => {
    const result = classifyError('rate limit');
    expect(result.icon).toBeTruthy();
    expect(result.guide).toBeTruthy();
  });
});
