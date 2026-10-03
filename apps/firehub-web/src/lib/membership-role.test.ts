import { describe, expect, it } from 'vitest';

import { membershipRoleLabel } from './membership-role';

describe('membershipRoleLabel (WD-15)', () => {
  it('워크스페이스 역할 코드를 한국어 라벨로', () => {
    expect(membershipRoleLabel('OWNER')).toBe('소유자');
    expect(membershipRoleLabel('ADMIN')).toBe('관리자');
    expect(membershipRoleLabel('MEMBER')).toBe('멤버');
  });
  it('알 수 없는 값은 원문 그대로 — 새 역할을 숨기지 않는다', () => {
    expect(membershipRoleLabel('GUEST')).toBe('GUEST');
  });
});
