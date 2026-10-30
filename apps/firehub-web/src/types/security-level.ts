/** 보안 등급 타입 — 백엔드 securitylevel/dto 와 1:1(스펙 §2.2). */
export type ExportPolicy = 'ALLOW' | 'PERMISSION' | 'DENY';
export type AiPolicy = 'ALL' | 'SELF_HOSTED_ONLY' | 'DENY';
export type SharePolicy = 'ALLOW' | 'DENY';

/** 데이터셋 응답에 실리는 요약(배지·정책 칩용). */
export interface SecurityLevelSummary {
  id: number;
  name: string;
  rank: number;
  allowlistRequired: boolean;
  exportPolicy: ExportPolicy;
  aiPolicy: AiPolicy;
  sharePolicy: SharePolicy;
  auditAccess: boolean;
}

/** 등급 정의 전체(설정 화면). */
export interface SecurityLevel extends SecurityLevelSummary {
  isDefault: boolean;
  adminBypass: boolean;
}

export interface SecurityLevelUsage {
  levelId: number;
  datasetCount: number;
  roleCount: number;
  /** 이 등급을 리포트 등급으로 가진 스마트 작업 실행 수(WD-198) — 삭제 시 함께 옮겨야 한다. 이전 서버 응답에는 없다. */
  reportCount?: number;
}

export interface MyClearance {
  rank: number | null;
  levelId: number | null;
}

export interface SecurityLevelRequest {
  name: string;
  allowlistRequired: boolean;
  adminBypass: boolean;
  exportPolicy: ExportPolicy;
  aiPolicy: AiPolicy;
  sharePolicy: SharePolicy;
  auditAccess: boolean;
  seedAllowlistFromViewers?: boolean;
  /** 수정 때 편집을 시작하며 읽은 등급 값(WD-440) — 서버 값과 다르면 409 EDIT_CONFLICT. 빼면 비교하지 않는다(추가·덮어쓰기). */
  expected?: SecurityLevelBaseline;
}

/** 등급 편집 기준 값 — 화면이 바꿀 수 있는 정책 필드만(WD-440). */
export type SecurityLevelBaseline = Omit<SecurityLevelRequest, 'seedAllowlistFromViewers' | 'expected'>;

export interface ReorderPreview {
  roles: { roleId: number; roleName: string; datasetDelta: number }[];
}

export interface AllowlistImpact {
  datasetsWithoutAllowlist: number;
}

/**
 * 허용 목록 대상(역할·사용자)의 열람 등급 요약(WD-292). 역할은 역할 등급, 사용자는 보유 역할 등급의 최댓값(유효 열람 등급).
 * 열람 판정은 「열람 등급 ≥ 데이터셋 등급」을 허용 목록보다 먼저 보므로, 이 값이 낮으면 허용 목록에 있어도 볼 수 없다.
 */
export interface SubjectClearance {
  levelId: number | null;
  levelName: string | null;
  rank: number;
}

/**
 * 허용 목록 대상의 등급 정보(WD-292). 이전 서버 응답에는 없으므로 선택 필드다 —
 * `sufficientClearance === false` 일 때만 경고·표시를 하고, 없으면(undefined) 판단하지 않는다.
 */
interface GrantSubjectClearanceFields {
  /** 대상의 열람 등급 — 역할이 없는 사용자는 null. */
  clearance?: SubjectClearance | null;
  /** 대상 열람 등급이 데이터셋 등급 이상인가 — false 면 허용 목록에 넣어도 (역할은 그 역할만으로는) 볼 수 없다. */
  sufficientClearance?: boolean;
}

export interface AccessGrant {
  id: number;
  type: 'USER' | 'ROLE';
  subjectId: number;
  subjectName: string;
  grantedByName: string | null;
  grantedAt: string;
  /** 대상의 열람 등급(WD-292) — 역할이 없는 사용자는 null, 이전 서버 응답에는 없다. */
  subjectClearance?: SubjectClearance | null;
  /** 대상 열람 등급이 데이터셋 등급 이상인가(WD-292) — false 면 이 항목만으로는 볼 수 없다. */
  sufficientClearance?: boolean;
}

export interface GrantCandidates {
  users: ({ id: number; name: string; email: string } & GrantSubjectClearanceFields)[];
  roles: ({ id: number; name: string } & GrantSubjectClearanceFields)[];
}

export interface RoleClearance {
  roleId: number;
  securityLevelId: number;
  userCount: number;
  fixed: boolean;
}

export interface ClearancePreview {
  callerLostDatasetCount: number;
  topLevelRoleCountAfter: number;
  roleUserCount: number;
}
