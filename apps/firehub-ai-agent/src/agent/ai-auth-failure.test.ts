import { describe, expect, it } from 'vitest';
import {
  AGENT_AUTH_OR_QUOTA_FAILURE,
  AUTH_FAILURE_KOREAN_MESSAGE,
  AiCredentialFailureError,
  MissingAiCredentialError,
  classifyAssistantError,
  isAiCredentialFailure,
  isTerminalProviderError,
  leadingProviderErrorSignature,
  providerErrorFields,
  resolveResultError,
  toCompletionError,
} from './ai-auth-failure.js';
import { AdminActionableError } from './admin-actionable-error.js';

/**
 * 공급자 오류 정책(#711) — SDK·CLI·completion·proactive 가 공유하는 판정의 단위 테스트.
 * 경로별 테스트는 배선만 보고, 정책 자체의 경계는 여기서 한 번 고정한다.
 */
describe('공급자 오류 정책', () => {
  it('AAF-01: 종결 오류는 인증·결제뿐이다 — 요청 한도는 코드는 붙지만 종결이 아니다', () => {
    expect(isTerminalProviderError('authentication_failed')).toBe(true);
    expect(isTerminalProviderError('billing_error')).toBe(true);
    expect(isTerminalProviderError('rate_limit')).toBe(false);
    expect(isTerminalProviderError('server_error')).toBe(false);
    expect(providerErrorFields('rate_limit', '').code).toBe(AGENT_AUTH_OR_QUOTA_FAILURE);
    expect(providerErrorFields('server_error', '').code).toBeUndefined();
  });

  it('AAF-02: 구조화 종류가 없거나 모르는 종류면 원문 서명으로 추정한다', () => {
    expect(providerErrorFields(undefined, 'Invalid API key · Fix external API key')).toEqual({
      message: AUTH_FAILURE_KOREAN_MESSAGE,
      code: AGENT_AUTH_OR_QUOTA_FAILURE,
    });
    expect(providerErrorFields('unknown', 'API Error: 429').message).toContain('요청 한도');
    // 알 수 없는 원문은 그대로(원인 파악에 더 유용), 코드 없음.
    expect(providerErrorFields(undefined, 'tool process crashed')).toEqual({ message: 'tool process crashed' });
  });

  it('AAF-03: classifyAssistantError — subagent·max_output_tokens·오류 없음은 null, 종결/보류를 가른다', () => {
    const content = [{ type: 'text', text: 'Failed to authenticate. API Error: 401' }];
    expect(classifyAssistantError({ error: 'authentication_failed', message: { content } }, 'toolu_1')).toBeNull();
    expect(classifyAssistantError({ error: 'max_output_tokens', message: { content } }, null)).toBeNull();
    expect(classifyAssistantError({ message: { content } }, null)).toBeNull();

    const terminal = classifyAssistantError({ error: 'authentication_failed', message: { content } }, null);
    expect(terminal && 'terminal' in terminal && terminal.terminal.code).toBe(AGENT_AUTH_OR_QUOTA_FAILURE);

    const pending = classifyAssistantError({ error: 'rate_limit', message: { content } }, null);
    expect(pending).toEqual({ pending: { kind: 'rate_limit', text: 'Failed to authenticate. API Error: 401' } });
  });

  it('AAF-04: resolveResultError — 보류한 종류가 결과 원문보다 우선한다', () => {
    expect(resolveResultError({ kind: 'rate_limit', text: 'x' }, 'Failed to authenticate').message).toContain('요청 한도');
    expect(resolveResultError(undefined, 'Failed to authenticate').message).toBe(AUTH_FAILURE_KOREAN_MESSAGE);
  });

  it('AAF-05: 선두 서명 검사는 맨 앞만 보고, 코드가 붙는 종류만 잡는다', () => {
    expect(leadingProviderErrorSignature('Failed to authenticate. API Error: 401')).toBe('failed to authenticate');
    expect(leadingProviderErrorSignature('API Error: 429 rate_limit_error')).toBe('api error: 429');
    // 본문 중간의 서술은 오탐하지 않는다.
    expect(leadingProviderErrorSignature('## 장애 요약\nAPI Error: 401 이 3회 발생')).toBeNull();
    // 서버 오류는 코드가 없으므로 선두 검사 대상이 아니다.
    expect(leadingProviderErrorSignature('Overloaded 상태 분석 리포트')).toBeNull();
  });

  it('AAF-06: toCompletionError — 종결이면 AiCredentialFailureError, 아니면 맥락을 담은 일반 오류', () => {
    const auth = toCompletionError('Not logged in · Please run /login', 'ctx');
    expect(auth).toBeInstanceOf(AiCredentialFailureError);
    expect(auth.message).toBe(AUTH_FAILURE_KOREAN_MESSAGE);
    const other = toCompletionError('tool process crashed', 'SDK 실행 실패 (subtype=error_during_execution)');
    expect(other).not.toBeInstanceOf(AiCredentialFailureError);
    expect(other.message).toBe('[completion] SDK 실행 실패 (subtype=error_during_execution): tool process crashed');
  });

  it('AAF-07: MissingAiCredentialError 는 관리자 조치 오류이고 코드를 싣는다 — 둘 다 자격증명 실패다', () => {
    const missing = new MissingAiCredentialError();
    expect(missing).toBeInstanceOf(AdminActionableError);
    expect(missing.code).toBe(AGENT_AUTH_OR_QUOTA_FAILURE);
    expect(isAiCredentialFailure(missing)).toBe(true);
    expect(isAiCredentialFailure(new AiCredentialFailureError('x'))).toBe(true);
    expect(isAiCredentialFailure(new Error('x'))).toBe(false);
  });
});
