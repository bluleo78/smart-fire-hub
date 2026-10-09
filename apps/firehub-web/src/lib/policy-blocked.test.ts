import { describe, expect, it } from 'vitest';

import { parsePolicyBlocked, policyBlockedLabel } from './policy-blocked';

/** ai-agent policyBlockedResultText 가 만드는 것과 같은 키 순서·형태의 표식(S3 §4.3). */
function marker(overrides: Record<string, unknown> = {}): string {
  return JSON.stringify({
    policyBlocked: true,
    code: 'POLICY_BLOCKED',
    action: 'AI',
    levelName: '민감',
    policyKey: 'ai_policy',
    message: 'm',
    ...overrides,
  });
}

describe('parsePolicyBlocked', () => {
  it('표식 JSON 을 읽는다', () => {
    const info = parsePolicyBlocked(marker());
    expect(info).toEqual({ action: 'AI', levelName: '민감', policyKey: 'ai_policy', message: 'm' });
  });

  it('표식 뒤에 다른 텍스트가 이어져도(다음 줄) 첫 줄의 표식을 읽는다', () => {
    expect(parsePolicyBlocked(`${marker()}\n\n[힌트] 같은 오류가 반복됩니다`)?.levelName).toBe('민감');
  });

  it('메시지 안의 개행은 이스케이프되므로 첫 줄 파싱이 깨지지 않는다', () => {
    expect(parsePolicyBlocked(marker({ message: '첫 줄\n둘째 줄' }))?.message).toBe('첫 줄\n둘째 줄');
  });

  it('일반 결과·깨진 JSON·undefined 는 null', () => {
    expect(parsePolicyBlocked(JSON.stringify({ rows: [] }))).toBeNull();
    expect(parsePolicyBlocked('API 오류 (500): boom')).toBeNull();
    expect(parsePolicyBlocked('{"policyBlocked":true')).toBeNull();
    expect(parsePolicyBlocked('')).toBeNull();
    expect(parsePolicyBlocked(undefined)).toBeNull();
  });

  it('policyBlocked 가 true 가 아니거나 등급 이름이 없으면 null', () => {
    expect(parsePolicyBlocked(marker({ policyBlocked: false }))).toBeNull();
    expect(parsePolicyBlocked(marker({ policyBlocked: 'true' }))).toBeNull();
    expect(parsePolicyBlocked(marker({ levelName: undefined }))).toBeNull();
    // 계약 코드가 없거나 다르면 표식이 아니다 — 비슷한 키를 가진 일반 결과의 오표시 방지
    expect(parsePolicyBlocked(marker({ code: undefined }))).toBeNull();
    expect(parsePolicyBlocked(marker({ code: 'OTHER' }))).toBeNull();
  });

  it('사유 문구: AI 는 현재 AI 공급자, SHARE 는 공유·외부 발송', () => {
    expect(policyBlockedLabel({ action: 'AI', levelName: '민감', policyKey: 'ai_policy', message: '' })).toBe(
      "'민감' 등급 — 현재 AI 공급자로 보낼 수 없음",
    );
    expect(policyBlockedLabel({ action: 'SHARE', levelName: '기밀', policyKey: 'share_policy', message: '' })).toBe(
      "'기밀' 등급 — 공유·외부 발송 불가",
    );
  });
});
