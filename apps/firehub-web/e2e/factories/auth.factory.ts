/**
 * 인증 도메인 모킹 데이터 팩토리
 * src/types/auth.ts, role.ts, user.ts 타입 기반으로 테스트용 객체를 생성한다.
 * overrides 파라미터로 특정 필드만 덮어쓸 수 있다.
 */

import type { TokenResponse, UserResponse } from '@/types/auth';
import type { RoleResponse } from '@/types/role';
import type { UserDetailResponse } from '@/types/user';

/** 기본 사용자 응답 객체 생성 */
export function createUser(overrides?: Partial<UserResponse>): UserResponse {
  return {
    id: 1,
    username: 'testuser',
    email: 'test@example.com',
    name: '테스트 사용자',
    isActive: true,
    createdAt: '2024-01-01T00:00:00Z',
    ...overrides,
  };
}

/** JWT 토큰 응답 객체 생성 */
export function createTokenResponse(overrides?: Partial<TokenResponse>): TokenResponse {
  return {
    accessToken: 'mock-access-token-12345',
    tokenType: 'Bearer',
    expiresIn: 3600,
    ...overrides,
  };
}

/** 역할(Role) 응답 객체 생성 */
export function createRole(overrides?: Partial<RoleResponse>): RoleResponse {
  return {
    id: 1,
    name: 'USER',
    description: '일반 사용자',
    isSystem: true,
    ...overrides,
  };
}

/** 역할 목록을 포함한 사용자 상세 응답 객체 생성 */
export function createUserDetail(overrides?: Partial<UserDetailResponse>): UserDetailResponse {
  return {
    id: 1,
    username: 'testuser',
    email: 'test@example.com',
    name: '테스트 사용자',
    isActive: true,
    createdAt: '2024-01-01T00:00:00Z',
    roles: [createRole()],
    ...overrides,
  };
}

/** ADMIN 역할이 포함된 관리자 사용자 상세 응답 객체 생성 */
export function createAdminUserDetail(overrides?: Partial<UserDetailResponse>): UserDetailResponse {
  return createUserDetail({
    id: 2,
    username: 'adminuser',
    email: 'admin@example.com',
    name: '관리자',
    roles: [
      createRole({ id: 2, name: 'ADMIN', description: '시스템 관리자' }),
    ],
    ...overrides,
  });
}
