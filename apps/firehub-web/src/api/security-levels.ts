import type {
  AccessGrant,
  AllowlistImpact,
  ClearancePreview,
  GrantCandidates,
  MyClearance,
  ReorderPreview,
  RoleClearance,
  SecurityLevel,
  SecurityLevelRequest,
  SecurityLevelUsage,
} from '../types/security-level';
import { client } from './client';

/** 보안 등급 API — 등급 정의(security:settings), 데이터셋 등급·허용 목록, 역할 자격. */
export const securityLevelsApi = {
  list: () => client.get<SecurityLevel[]>('/security-levels'),
  usage: () => client.get<SecurityLevelUsage[]>('/security-levels/usage'),
  myClearance: () => client.get<MyClearance>('/security-levels/my-clearance'),
  create: (data: SecurityLevelRequest) => client.post<SecurityLevel>('/security-levels', data),
  update: (id: number, data: SecurityLevelRequest) => client.put<SecurityLevel>(`/security-levels/${id}`, data),
  setDefault: (id: number) => client.put(`/security-levels/${id}/default`),
  // DELETE 본문 — 사용 중이면 이동 대상·하향 사유(스펙 §4.7)
  remove: (id: number, data: { reassignToLevelId: number | null; reason: string | null }) =>
    client.delete(`/security-levels/${id}`, { data }),
  previewReorder: (orderedIds: number[]) =>
    client.post<ReorderPreview>('/security-levels/reorder/preview', { orderedIds }),
  reorder: (orderedIds: number[]) => client.put('/security-levels/reorder', { orderedIds }),
  allowlistImpact: (id: number) => client.get<AllowlistImpact>(`/security-levels/${id}/allowlist-impact`),
  changeDatasetLevel: (datasetId: number, data: { securityLevelId: number; reason?: string }) =>
    client.put(`/datasets/${datasetId}/security-level`, data),
  listGrants: (datasetId: number) => client.get<AccessGrant[]>(`/datasets/${datasetId}/access-grants`),
  grantCandidates: (datasetId: number) =>
    client.get<GrantCandidates>(`/datasets/${datasetId}/access-grants/candidates`),
  addGrant: (datasetId: number, data: { userId?: number; roleId?: number }) =>
    client.post<AccessGrant>(`/datasets/${datasetId}/access-grants`, data),
  removeGrant: (datasetId: number, grantId: number) =>
    client.delete(`/datasets/${datasetId}/access-grants/${grantId}`),
  getRoleClearance: (roleId: number) => client.get<RoleClearance>(`/roles/${roleId}/clearance`),
  previewRoleClearance: (roleId: number, securityLevelId: number) =>
    client.post<ClearancePreview>(`/roles/${roleId}/clearance/preview`, { securityLevelId }),
  setRoleClearance: (roleId: number, securityLevelId: number) =>
    client.put(`/roles/${roleId}/clearance`, { securityLevelId }),
};
