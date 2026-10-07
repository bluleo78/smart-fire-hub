import { describe, expect, it } from 'vitest';

import type { SecurityLevel } from '../types/security-level';
import { cumulativeDescription, levelTone, policyChips } from './security-level';

/** 스펙 §5 공통 컴포넌트: 배지 색은 순위가 아니라 정책에서 파생(무제한 outline / 제한 warning / 허용 목록 caution). */
const base = { id: 1, rank: 1, isDefault: false, adminBypass: false, auditAccess: false };
const levels: SecurityLevel[] = [
  { ...base, id: 1, rank: 1, name: '공개', allowlistRequired: false, exportPolicy: 'ALLOW', aiPolicy: 'ALL', sharePolicy: 'ALLOW' },
  { ...base, id: 2, rank: 2, name: '내부', isDefault: true, allowlistRequired: false, exportPolicy: 'ALLOW', aiPolicy: 'ALL', sharePolicy: 'ALLOW' },
  { ...base, id: 3, rank: 3, name: '민감', allowlistRequired: false, exportPolicy: 'PERMISSION', aiPolicy: 'SELF_HOSTED_ONLY', sharePolicy: 'ALLOW', auditAccess: true },
  { ...base, id: 4, rank: 4, name: '기밀', allowlistRequired: true, exportPolicy: 'DENY', aiPolicy: 'SELF_HOSTED_ONLY', sharePolicy: 'DENY', auditAccess: true },
];

describe('levelTone', () => {
  it('정책으로 색을 파생한다', () => {
    expect(levelTone(levels[1])).toBe('outline');
    expect(levelTone(levels[2])).toBe('warning');
    expect(levelTone(levels[3])).toBe('caution');
  });
});

describe('policyChips', () => {
  it('제한 정책과 감사만 칩으로 보여준다(목업 s2 순서)', () => {
    expect(policyChips(levels[3])).toEqual(['내보내기 차단', '자체 호스팅 AI만', '외부 공유 차단', '감사 기록 중']);
    expect(policyChips(levels[2])).toEqual(['내보내기 권한 필요', '자체 호스팅 AI만', '감사 기록 중']);
    expect(policyChips(levels[1])).toEqual([]);
  });
});

describe('cumulativeDescription', () => {
  it('누적 설명 — 목업 s3 문구 그대로', () => {
    expect(cumulativeDescription(levels, 0)).toBe('공개 데이터셋만 열람');
    expect(cumulativeDescription(levels, 1)).toBe('공개·내부 데이터셋 열람');
    expect(cumulativeDescription(levels, 2)).toBe('공개·내부·민감 데이터셋 열람');
    expect(cumulativeDescription(levels, 3)).toBe('기밀까지 열람 — 단, 허용 목록에 있는 데이터셋만');
  });
});
