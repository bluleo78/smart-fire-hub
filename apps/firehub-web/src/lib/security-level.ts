import type { SecurityLevel, SecurityLevelSummary } from '../types/security-level';

export type LevelTone = 'outline' | 'warning' | 'caution';

/** 배지 톤 — 순위가 아니라 정책에서 파생(스펙 §5). 허용 목록 > 제한 > 무제한. */
export function levelTone(
  level: Pick<SecurityLevelSummary, 'allowlistRequired' | 'exportPolicy' | 'aiPolicy' | 'sharePolicy'>,
): LevelTone {
  if (level.allowlistRequired) return 'caution';
  if (level.exportPolicy !== 'ALLOW' || level.aiPolicy !== 'ALL' || level.sharePolicy !== 'ALLOW') return 'warning';
  return 'outline';
}

/**
 * 「보안」 탭 정책 칩 — "왜 다운로드가 없지?"에 답하는 자리(목업 s2). 무제한 값은 칩을 만들지 않는다.
 * 주의: 내보내기·AI·공유 정책의 실제 강제는 S3/S4 — 이 칩은 정책 선언을 보여준다.
 */
export function policyChips(level: SecurityLevelSummary): string[] {
  const chips: string[] = [];
  if (level.exportPolicy === 'PERMISSION') chips.push('내보내기 권한 필요');
  if (level.exportPolicy === 'DENY') chips.push('내보내기 차단');
  if (level.aiPolicy === 'SELF_HOSTED_ONLY') chips.push('자체 호스팅 AI만');
  if (level.aiPolicy === 'DENY') chips.push('AI 분석 차단');
  if (level.sharePolicy === 'DENY') chips.push('외부 공유 차단');
  if (level.auditAccess) chips.push('감사 기록 중');
  return chips;
}

/** 역할 자격 라디오의 누적 설명(목업 s3) — 서열 개념을 문장으로 학습시킨다. levelsAsc 는 rank 오름차순. */
export function cumulativeDescription(levelsAsc: SecurityLevel[], index: number): string {
  const level = levelsAsc[index];
  if (level.allowlistRequired) return `${level.name}까지 열람 — 단, 허용 목록에 있는 데이터셋만`;
  if (index === 0) return `${level.name} 데이터셋만 열람`;
  return `${levelsAsc.slice(0, index + 1).map((l) => l.name).join('·')} 데이터셋 열람`;
}
