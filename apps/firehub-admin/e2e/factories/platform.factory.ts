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
 * 서버 시드 18행. `ai.session_max_tokens` 는 **일부러 빠져 있다** — 어떤 마이그레이션도 시드하지 않는다.
 *
 * `.spec.ts` 가 아니라 여기 두는 이유(리뷰 L2): 스펙 파일에서 `export` 하면 Playwright 가 그
 * 파일을 테스트 파일로도 등록해 다른 스펙이 이걸 `import` 하는 순간 settings.spec.ts 의 테스트가
 * 함께 중복 등록된다(실측: 프로브 스펙 하나만 지정해 돌려도 settings 9건이 같이 실행됐다).
 * 팩토리 모듈은 애초에 테스트를 export 하지 않으므로 이 문제가 없다.
 */
export const SEEDED_18: SettingResponse[] = [
  createSetting('ai.model', 'claude-sonnet-5', 'AI 에이전트 사용 모델'),
  createSetting('ai.max_turns', '20', '최대 턴 수'),
  createSetting('ai.system_prompt', '당신은 소방 데이터 분석가입니다.', '시스템 프롬프트'),
  createSetting('ai.temperature', '0.7', '샘플링 온도'),
  createSetting('ai.max_tokens', '8192', '최대 응답 토큰'),
  createSetting('ai.api_key', '****ab12', 'Anthropic API Key'),
  createSetting('ai.agent_type', 'cli', '에이전트 유형'),
  createSetting('ai.cli_oauth_token', '', 'CLI OAuth 토큰'),
  createSetting('smtp.host', 'smtp.example.com', 'SMTP 호스트'),
  createSetting('smtp.port', '587', 'SMTP 포트'),
  createSetting('smtp.username', 'mailer', 'SMTP 사용자'),
  createSetting('smtp.password', '****cd34', 'SMTP 비밀번호'),
  createSetting('smtp.starttls', 'true', 'STARTTLS 사용 여부'),
  createSetting('smtp.from_address', 'no-reply@example.com', '보낸 사람 주소'),
  createSetting('embedding.provider', 'OLLAMA', '임베딩 provider'),
  createSetting('embedding.model', 'bge-m3', '임베딩 모델'),
  createSetting('embedding.base_url', 'http://localhost:11434', '임베딩 base URL'),
  createSetting('embedding.api_key', '', '임베딩 API Key'),
];
