import type {
  PlatformMeResponse,
  PlatformTokenResponse,
  PlatformUserResponse,
  SettingResponse,
  TenantMemberResponse,
  TenantSummaryResponse,
} from '../../src/types/platform';

/** 모든 플랫폼 권한을 가진 SUPER_ADMIN 세션. 화면별 스펙이 필요한 만큼 덜어 쓴다. */
export const ALL_PLATFORM_PERMISSIONS = [
  'platform:tenant:create',
  'platform:tenant:read',
  'platform:tenant:suspend',
  'platform:member:read',
  'platform:settings:read',
  'platform:settings:write',
];

export function createPlatformMe(overrides: Partial<PlatformMeResponse> = {}): PlatformMeResponse {
  return {
    userId: 1,
    username: 'ops@example.com',
    name: '김운영',
    permissions: ALL_PLATFORM_PERMISSIONS,
    ...overrides,
  };
}

export function createTokenResponse(
  overrides: Partial<PlatformTokenResponse> = {},
): PlatformTokenResponse {
  return {
    accessToken: 'mock-platform-access-token',
    refreshToken: null,
    tokenType: 'Bearer',
    expiresIn: 1800,
    permissions: ALL_PLATFORM_PERMISSIONS,
    ...overrides,
  };
}

export function createTenant(overrides: Partial<TenantSummaryResponse> = {}): TenantSummaryResponse {
  return {
    id: 1,
    slug: 'hanbit',
    name: '한빛소방서',
    status: 'ACTIVE',
    memberCount: 12,
    createdAt: '2026-03-04T09:21:14',
    ...overrides,
  };
}

export function createMember(overrides: Partial<TenantMemberResponse> = {}): TenantMemberResponse {
  return {
    userId: 10,
    username: 'kimsb',
    email: 'kim@example.com',
    role: 'OWNER',
    status: 'ACTIVE',
    ...overrides,
  };
}

export function createPlatformUser(
  overrides: Partial<PlatformUserResponse> = {},
): PlatformUserResponse {
  return { id: 42, email: 'owner@example.com', name: '박소유', ...overrides };
}

export function createSetting(key: string, value: string | null, description = ''): SettingResponse {
  return { key, value, description, updatedAt: '2026-08-19T14:02:31' };
}
