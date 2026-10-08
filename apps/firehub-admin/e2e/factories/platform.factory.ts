import type {
  PageResponse,
  PlatformAccountResponse,
  PlatformAuditLogResponse,
  PlatformMeResponse,
  PlatformTokenResponse,
  PlatformUserResponse,
  TenantMemberResponse,
  TenantSummaryResponse,
} from '../../src/types/platform';

/** 모든 플랫폼 권한을 가진 SUPER_ADMIN 세션. 화면별 스펙이 필요한 만큼 덜어 쓴다. */
export const ALL_PLATFORM_PERMISSIONS = [
  'platform:tenant:create',
  'platform:tenant:read',
  'platform:tenant:suspend',
  'platform:member:read',
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
    createdAt: '2026-03-04T00:21:14Z',
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

export function createAccount(overrides: Partial<PlatformAccountResponse> = {}): PlatformAccountResponse {
  return {
    id: 10,
    username: 'kim@example.com',
    email: 'kim@example.com',
    name: '김소방',
    active: true,
    operator: false,
    membershipCount: 1,
    createdAt: '2026-03-04T00:21:14Z',
    ...overrides,
  };
}

/** 계정 목록 페이지 응답(WD-47). 기본은 한 페이지짜리 — 여러 쪽을 흉내 내려면 overrides 로 totalElements·totalPages 를 준다. */
export function createAccountPage(
  content: PlatformAccountResponse[],
  overrides: Partial<PageResponse<PlatformAccountResponse>> = {},
): PageResponse<PlatformAccountResponse> {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length > 0 ? 1 : 0, ...overrides };
}

/** 플랫폼 감사 로그 한 행(WD-4). 기본값은 운영자의 전역 계정 비활성화 조치. */
export function createAuditLog(overrides: Partial<PlatformAuditLogResponse> = {}): PlatformAuditLogResponse {
  return {
    id: 501,
    userId: 1,
    username: 'ops@example.com',
    actionType: 'ACCOUNT_DEACTIVATE',
    resource: 'user',
    resourceId: '10',
    description: '전역 계정 비활성화(모든 워크스페이스 로그인 차단, refresh 세션 폐기)',
    actionTime: '2026-10-01T00:15:30Z',
    ipAddress: null,
    userAgent: null,
    result: 'SUCCESS',
    errorMessage: null,
    metadata: { plane: 'platform', targetUsername: 'kim@example.com' },
    ...overrides,
  };
}

/** 감사 로그 페이지 응답. 기본은 한 페이지짜리. */
export function createAuditPage(
  content: PlatformAuditLogResponse[],
  overrides: Partial<PageResponse<PlatformAuditLogResponse>> = {},
): PageResponse<PlatformAuditLogResponse> {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length > 0 ? 1 : 0, ...overrides };
}
