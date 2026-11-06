import { describe, expect, it } from 'vitest';

import { reportTemplateSchema } from './report-template';

/**
 * 리포트 양식 이름 검증 (WD-501) — 공백만 있는 이름은 비어 있는 것으로 보고, 앞뒤 공백은 잘라 보낸다.
 * 예전에는 `.min(1)` 만 있어 "   " 가 통과해 서버의 영어 기본 문구가 토스트로 보였다.
 */
describe('reportTemplateSchema.name', () => {
  /** 이름 필드의 첫 오류 문구 — 통과하면 undefined. */
  const nameError = (name: string) => {
    const result = reportTemplateSchema.safeParse({ name, description: '' });
    return result.success ? undefined : result.error.issues.find((i) => i.path[0] === 'name')?.message;
  };

  it('공백만 있으면 「이름을 입력해주세요.」로 실패한다', () => {
    expect(nameError('   ')).toBe('이름을 입력해주세요.');
    expect(nameError('')).toBe('이름을 입력해주세요.');
  });

  it('앞뒤 공백은 잘라서 통과한다', () => {
    const result = reportTemplateSchema.parse({ name: '  양식 ', description: '' });
    expect(result.name).toBe('양식');
  });

  it('보통 이름은 그대로 통과한다', () => {
    expect(reportTemplateSchema.parse({ name: '주간 리포트', description: '' }).name).toBe('주간 리포트');
  });

  it('100자는 통과하고 101자는 실패한다', () => {
    expect(nameError('가'.repeat(100))).toBeUndefined();
    expect(nameError('가'.repeat(101))).toBe('100자 이내로 입력해주세요.');
  });
});
