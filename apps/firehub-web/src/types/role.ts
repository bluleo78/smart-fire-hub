export interface PermissionResponse {
  id: number;
  code: string;
  description: string | null;
  category: string;
}

export interface RoleResponse {
  id: number;
  name: string;
  description: string | null;
  isSystem: boolean;
}

export interface RoleDetailResponse {
  id: number;
  name: string;
  description: string | null;
  isSystem: boolean;
  permissions: PermissionResponse[];
}

export interface CreateRoleRequest {
  name: string;
  description?: string;
}

export interface UpdateRoleRequest {
  name: string;
  description?: string;
}

export interface SetPermissionsRequest {
  permissionIds: number[];
  /** 화면이 편집을 시작할 때 읽은 권한 목록(WD-440) — 서버 값과 다르면 409 EDIT_CONFLICT. 빼면 비교하지 않는다(덮어쓰기). */
  expectedPermissionIds?: number[];
}
