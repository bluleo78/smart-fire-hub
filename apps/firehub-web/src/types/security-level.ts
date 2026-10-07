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
}

export interface ReorderPreview {
  roles: { roleId: number; roleName: string; datasetDelta: number }[];
}

export interface AllowlistImpact {
  datasetsWithoutAllowlist: number;
}

export interface AccessGrant {
  id: number;
  type: 'USER' | 'ROLE';
  subjectId: number;
  subjectName: string;
  grantedByName: string | null;
  grantedAt: string;
}

export interface GrantCandidates {
  users: { id: number; name: string; email: string }[];
  roles: { id: number; name: string }[];
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
