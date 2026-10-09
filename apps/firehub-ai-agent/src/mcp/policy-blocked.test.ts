// api 정책 차단(POLICY_BLOCKED)을 도구 결과 표식으로 옮기는 계약 테스트(S3).
import { describe, it, expect } from 'vitest';
import { policyBlockedOf, policyBlockedResultText, POLICY_BLOCKED_CODE } from './policy-blocked.js';

describe('policy-blocked', () => {
  it('api-client 가 붙인 policyBlocked 정보를 꺼낸다', () => {
    const err = Object.assign(new Error('API 오류 (403): 차단'), {
      status: 403,
      policyBlocked: { action: 'AI', levelName: '민감', policyKey: 'ai_policy', message: '차단' },
    });
    expect(policyBlockedOf(err)).toEqual({ action: 'AI', levelName: '민감', policyKey: 'ai_policy', message: '차단' });
  });

  it('일반 오류는 null', () => {
    expect(policyBlockedOf(new Error('x'))).toBeNull();
    expect(policyBlockedOf('x')).toBeNull();
    expect(policyBlockedOf(null)).toBeNull();
  });

  it('결과 텍스트는 웹이 파싱하는 고정 JSON 표식이다', () => {
    const text = policyBlockedResultText({ action: 'SHARE', levelName: '기밀', policyKey: 'share_policy', message: 'm' });
    expect(JSON.parse(text)).toEqual({
      policyBlocked: true,
      code: POLICY_BLOCKED_CODE,
      action: 'SHARE',
      levelName: '기밀',
      policyKey: 'share_policy',
      message: 'm',
    });
  });
});
