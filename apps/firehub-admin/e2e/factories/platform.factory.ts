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

/**
 * 플랫폼 설정 응답(서버 시드 4행: 임베딩 4). #712 로 SMTP 가 워크스페이스 전용이 되면서 서버
 * 플랫폼 응답에는 임베딩 키만 남았다. `embedding.api_key` 는 빈 값(미설정)이다 — 설정된 비밀
 * 상태가 필요한 테스트는 스펙 쪽에서 값을 덮어 쓴 사본을 만든다.
 *
 * AI(`ai.*`)·SMTP(`smtp.*`) 설정은 여기 없다 — 워크스페이스 전용이라 서버가 플랫폼 설정 응답에서 뺀다.
 *
 * `.spec.ts` 가 아니라 여기 두는 이유(리뷰 L2): 스펙 파일에서 `export` 하면 Playwright 가 그
 * 파일을 테스트 파일로도 등록해 다른 스펙이 이걸 `import` 하는 순간 settings.spec.ts 의 테스트가
 * 함께 중복 등록된다(실측: 프로브 스펙 하나만 지정해 돌려도 settings 9건이 같이 실행됐다).
 * 팩토리 모듈은 애초에 테스트를 export 하지 않으므로 이 문제가 없다.
 */
export const SEEDED_SETTINGS: SettingResponse[] = [
  createSetting('embedding.provider', 'OLLAMA', '임베딩 provider'),
  createSetting('embedding.model', 'bge-m3', '임베딩 모델'),
  createSetting('embedding.base_url', 'http://localhost:11434', '임베딩 base URL'),
  createSetting('embedding.api_key', '', '임베딩 API Key'),
];
