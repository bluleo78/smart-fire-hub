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
}
