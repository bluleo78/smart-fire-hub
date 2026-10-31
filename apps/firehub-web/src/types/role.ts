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

/** 호출자가 부여할 수 없는 역할 하나(WD-291) — code·message 는 실제로 부여하면 받을 403 과 같다. */
export interface RoleGrantBlockResponse {
  roleId: number;
  code: string;
  message: string;
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
