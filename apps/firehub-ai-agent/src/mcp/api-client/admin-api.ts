import type { AxiosInstance } from 'axios';

/** 사용자 목록 항목 */
export interface UserResponse {
  id: number;
  username: string;
  email: string;
  name: string;
  isActive: boolean;
  createdAt: string;
}

/** 사용자 상세 (역할 포함) */
export interface UserDetailResponse {
  id: number;
  username: string;
  email: string;
  name: string;
  isActive: boolean;
  createdAt: string;
  roles: RoleResponse[];
}

/** 역할 목록 항목 */
export interface RoleResponse {
  id: number;
  name: string;
  description: string | null;
  isSystem: boolean;
}

/** 권한 목록 항목 */
export interface PermissionResponse {
  id: number;
  code: string;
  description: string;
  category: string;
}

/** 사용자 목록 페이지 */
export interface UserPage {
  content: UserResponse[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/**
 * 사용자/역할/권한 관리 API 서브모듈.
 * firehub-api의 /users, /roles, /permissions 엔드포인트를 호출한다.
 */
export function createAdminApi(client: AxiosInstance) {
  return {
    /** 사용자 목록 조회 (검색·페이지네이션 지원) */
    async listUsers(params?: { search?: string; page?: number; size?: number }): Promise<UserPage> {
      const response = await client.get<UserPage>('/users', { params });
      return response.data;
    },

    /** 사용자 상세 조회 (할당된 역할 포함) */
    async getUser(id: number): Promise<UserDetailResponse> {
      const response = await client.get<UserDetailResponse>(`/users/${id}`);
      return response.data;
    },

    /**
     * 사용자 역할 교체.
     * roleIds 배열로 기존 역할을 전부 교체한다. 빈 배열이면 모든 역할 제거.
     */
    async setUserRoles(userId: number, roleIds: number[]): Promise<void> {
      await client.put(`/users/${userId}/roles`, { roleIds });
    },

    /** 사용자 계정 활성화/비활성화 */
    async setUserActive(userId: number, active: boolean): Promise<void> {
      await client.put(`/users/${userId}/active`, { active });
    },

    /** 시스템 역할 전체 목록 조회 */
    async listRoles(): Promise<RoleResponse[]> {
      const response = await client.get<RoleResponse[]>('/roles');
      return response.data;
    },

    /** 시스템 권한 목록 조회. category로 필터 가능 */
    async listPermissions(params?: { category?: string }): Promise<PermissionResponse[]> {
      const response = await client.get<PermissionResponse[]>('/permissions', { params });
      return response.data;
    },
  };
}
