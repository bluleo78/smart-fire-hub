import type {
  CreateRoleRequest,
  RoleDetailResponse,
  RoleGrantBlockResponse,
  RoleResponse,
  SetPermissionsRequest,
  UpdateRoleRequest,
} from '../types/role';
import { client } from './client';

/** 역할 목록(GET /roles) 쿼리 키 — 목록 접두 규칙은 useInvalidateListsOnEntryNotFound 참고 (WD-646) */
export const ROLE_LIST_KEY = ['roles', 'list'] as const;

export const rolesApi = {
  getRoles: () => client.get<RoleResponse[]>('/roles'),
  /** 호출자가 부여할 수 없는 역할 목록(WD-291, role:assign) — 멤버 추가 화면이 체크박스를 미리 잠그는 데 쓴다. */
  getGrantBlockedRoles: () => client.get<RoleGrantBlockResponse[]>('/roles/grant-blocked'),
  getRoleById: (id: number) => client.get<RoleDetailResponse>(`/roles/${id}`),
  createRole: (data: CreateRoleRequest) => client.post<RoleResponse>('/roles', data),
  updateRole: (id: number, data: UpdateRoleRequest) => client.put(`/roles/${id}`, data),
  deleteRole: (id: number) => client.delete(`/roles/${id}`),
  setPermissions: (id: number, data: SetPermissionsRequest) => client.put(`/roles/${id}/permissions`, data),
};
