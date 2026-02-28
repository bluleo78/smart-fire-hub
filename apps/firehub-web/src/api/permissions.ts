import type { PermissionResponse } from '../types/role';
import { client } from './client';

export const permissionsApi = {
  getPermissions: (category?: string) =>
    client.get<PermissionResponse[]>('/permissions', { params: category ? { category } : undefined }),
};
