import { client } from './client';
import type { PermissionResponse } from '../types/role';

export const permissionsApi = {
  getPermissions: (category?: string) =>
    client.get<PermissionResponse[]>('/permissions', { params: category ? { category } : undefined }),
};
