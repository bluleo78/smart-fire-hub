import type { SecurityLevel, SecurityLevelSummary } from '../../src/types/security-level';

/** V133 기본 4등급과 같은 모양(공개 < 내부(기본) < 민감 < 기밀). */
export function createLevels(): SecurityLevel[] {
  return [
    { id: 1, name: '공개', rank: 1, isDefault: false, allowlistRequired: false, adminBypass: false, exportPolicy: 'ALLOW', aiPolicy: 'ALL', sharePolicy: 'ALLOW', auditAccess: false },
    { id: 2, name: '내부', rank: 2, isDefault: true, allowlistRequired: false, adminBypass: false, exportPolicy: 'ALLOW', aiPolicy: 'ALL', sharePolicy: 'ALLOW', auditAccess: false },
    { id: 3, name: '민감', rank: 3, isDefault: false, allowlistRequired: false, adminBypass: false, exportPolicy: 'PERMISSION', aiPolicy: 'SELF_HOSTED_ONLY', sharePolicy: 'ALLOW', auditAccess: true },
    { id: 4, name: '기밀', rank: 4, isDefault: false, allowlistRequired: true, adminBypass: false, exportPolicy: 'DENY', aiPolicy: 'SELF_HOSTED_ONLY', sharePolicy: 'DENY', auditAccess: true },
  ];
}

/** 데이터셋 응답용 요약 — 이름으로 기본 4등급 중 하나를 고르고 설정 전용 필드(isDefault·adminBypass)는 뺀다. */
export function createLevelSummary(name: '공개' | '내부' | '민감' | '기밀'): SecurityLevelSummary {
  const level = createLevels().find((l) => l.name === name)!;
  return {
    id: level.id,
    name: level.name,
    rank: level.rank,
    allowlistRequired: level.allowlistRequired,
    exportPolicy: level.exportPolicy,
    aiPolicy: level.aiPolicy,
    sharePolicy: level.sharePolicy,
    auditAccess: level.auditAccess,
  };
}
