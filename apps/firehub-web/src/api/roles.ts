import type { CreateRoleRequest, RoleDetailResponse, RoleResponse, SetPermissionsRequest,UpdateRoleRequest } from '../types/role';
import { client } from './client';

export const rolesApi = {
  getRoles: () => client.get<RoleResponse[]>('/roles'),
  getRoleById: (id: number) => client.get<RoleDetailResponse>(`/roles/${id}`),
  createRole: (data: CreateRoleRequest) => client.post<RoleResponse>('/roles', data),
  updateRole: (id: number, data: UpdateRoleRequest) => client.put(`/roles/${id}`, data),
  deleteRole: (id: number) => client.delete(`/roles/${id}`),
  setPermissions: (id: number, data: SetPermissionsRequest) => client.put(`/roles/${id}/permissions`, data),
};
