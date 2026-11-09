import { describe, expect, it } from 'vitest';

import { addMemberSchema } from './user';

/** 멤버 추가 스키마(WD-2) — 필수·형식·비밀번호 정책 문구가 서버 규칙(8자 이상·UTF-8 72바이트 이하, 대·소·숫자)과 맞는지. */
const valid = { email: 'newbie@acme.io', name: '뉴비', temporaryPassword: 'Abcdefg2', roleIds: [] };

/** 실패 시 필드별 첫 오류 문구만 모은다. */
function errorsOf(input: unknown): Record<string, string> {
  const result = addMemberSchema.safeParse(input);
  if (result.success) return {};
  const out: Record<string, string> = {};
  for (const issue of result.error.issues) {
    const key = String(issue.path[0]);
    out[key] ??= issue.message;
  }
  return out;
}

describe('addMemberSchema', () => {
  it('정책을 만족하는 입력은 통과한다', () => {
    expect(addMemberSchema.safeParse(valid).success).toBe(true);
  });

  it('필수 항목은 "…은/는 필수입니다" 문구', () => {
    const errors = errorsOf({ ...valid, email: '  ', name: '' });
    expect(errors.email).toBe('이메일은 필수입니다');
    expect(errors.name).toBe('이름은 필수입니다');
  });

  it('이메일 형식을 검사한다', () => {
    expect(errorsOf({ ...valid, email: 'not-an-email' }).email).toBe('올바른 이메일 형식이 아닙니다');
  });

  it('비밀번호는 8자 이상(7자 거부, 8자 허용)', () => {
    expect(errorsOf({ ...valid, temporaryPassword: 'Abcdef2' }).temporaryPassword).toBe('비밀번호는 8자 이상이어야 합니다');
    expect(addMemberSchema.safeParse({ ...valid, temporaryPassword: 'Abcdef23' }).success).toBe(true);
  });

  // WD-605: BCrypt 는 앞 72바이트만 쓴다 — 상한은 글자 수가 아니라 UTF-8 바이트(한글 1자 = 3바이트)
  it('비밀번호는 UTF-8 72바이트 이하(73바이트 거부, 72바이트 허용)', () => {
    expect(errorsOf({ ...valid, temporaryPassword: 'Aa1' + 'x'.repeat(70) }).temporaryPassword).toBe('비밀번호는 72바이트(영문·숫자 72자, 한글 24자) 이하여야 합니다');
    expect(addMemberSchema.safeParse({ ...valid, temporaryPassword: 'Aa1' + 'x'.repeat(69) }).success).toBe(true);
  });

  it('한글은 1자 3바이트로 센다(27자·75바이트 거부, 26자·72바이트 허용)', () => {
    expect(errorsOf({ ...valid, temporaryPassword: 'Aa1' + '가'.repeat(24) }).temporaryPassword).toBe('비밀번호는 72바이트(영문·숫자 72자, 한글 24자) 이하여야 합니다');
    expect(addMemberSchema.safeParse({ ...valid, temporaryPassword: 'Aa1' + '가'.repeat(23) }).success).toBe(true);
  });

  it('대문자가 없으면 정책 위반', () => {
    expect(errorsOf({ ...valid, temporaryPassword: 'abcdefg2' }).temporaryPassword).toBe(
      '대문자, 소문자, 숫자를 모두 포함해야 합니다',
    );
  });
});
