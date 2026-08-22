/**
 * 관리자 도메인 모킹 데이터 팩토리
 * src/types/api-connection.ts, auditLog.ts, role.ts, settings.ts 타입 기반으로 테스트용 객체를 생성한다.
 * overrides 파라미터로 특정 필드만 덮어쓸 수 있다.
 */

import type { ApiConnectionResponse } from '@/types/api-connection';
import type { AuditLogResponse } from '@/types/auditLog';
import type { PermissionResponse, RoleDetailResponse } from '@/types/role';
import type { ResolvedSettingResponse } from '@/types/settings';

/** 권한(Permission) 응답 객체 생성 */
export function createPermission(overrides?: Partial<PermissionResponse>): PermissionResponse {
  return {
    id: 1,
    code: 'DATASET_READ',
    description: '데이터셋 조회 권한',
    category: 'DATASET',
    ...overrides,
  };
}

/** 권한 목록을 포함한 역할 상세 응답 객체 생성 */
export function createRoleDetail(overrides?: Partial<RoleDetailResponse>): RoleDetailResponse {
  return {
    id: 1,
    name: 'USER',
    description: '일반 사용자 역할',
    isSystem: true,
    permissions: [
      createPermission(),
      createPermission({ id: 2, code: 'DATASET_WRITE', description: '데이터셋 수정 권한' }),
    ],
    ...overrides,
  };
}

/** 감사 로그(AuditLog) 응답 객체 생성 */
export function createAuditLog(overrides?: Partial<AuditLogResponse>): AuditLogResponse {
  return {
    id: 1,
    userId: 1,
    username: 'testuser',
    actionType: 'CREATE',
    resource: 'DATASET',
    resourceId: '1',
    description: '데이터셋을 생성했습니다.',
    actionTime: '2024-01-01T00:00:00Z',
    ipAddress: '127.0.0.1',
    userAgent: 'Mozilla/5.0',
    result: 'SUCCESS',
    errorMessage: null,
    metadata: null,
    ...overrides,
  };
}

/** API 연결(ApiConnection) 응답 객체 생성 */
export function createApiConnection(overrides?: Partial<ApiConnectionResponse>): ApiConnectionResponse {
  return {
    id: 1,
    name: '테스트 API 연결',
    description: '테스트용 외부 API 연결',
    authType: 'API_KEY',
    maskedAuthConfig: { apiKey: '***masked***' },
    baseUrl: 'https://api.example.com',
    healthCheckPath: '/health',
    lastStatus: 'UP',
    lastCheckedAt: '2026-04-15T10:00:00Z',
    lastLatencyMs: 120,
    lastErrorMessage: null,
    createdBy: 1,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
    ...overrides,
  };
}

/**
 * `GET /api/v1/settings?prefix=...` 가 내려주는 해석된 설정 1건.
 *
 * 기본값을 `overridden: false, tenantEditable: true`(= 상속 중 + 편집 가능)로 둔다.
 * 두 플래그를 빠뜨리면 `resolveSettingFieldState` 가 `tenantEditable` falsy 를 보고 전 필드를
 * **잠김**으로 판정하므로, 플래그 없는 픽스처로 쓴 스펙은 결함을 정상으로 고정해 버린다.
 */
export function createResolvedSetting(
  overrides?: Partial<ResolvedSettingResponse>,
): ResolvedSettingResponse {
  return {
    key: 'app.name',
    value: 'Smart Fire Hub',
    description: '애플리케이션 이름',
    updatedAt: '2024-01-01T00:00:00Z',
    overridden: false,
    tenantEditable: true,
    ...overrides,
  };
}

/**
 * AI 설정 탭이 실제 서버에서 받는 목록을 재현한다 — 혼합 상태(상속 5 + 잠금 3)가 기본이다.
 *
 * - `ai.agent_type` / `ai.api_key` / `ai.cli_oauth_token` → 플랫폼 전용(`tenantEditable: false`).
 *   실행 형태·과금 주체·비밀값이라 테넌트가 바꿀 수 없다.
 * - 나머지 5키 → 편집 가능 + 상속 중(`overridden: false`). 재정의 상태가 필요한 테스트는
 *   `patch` 로 그 키만 `overridden: true` 로 바꾼다.
 * - **`ai.session_max_tokens` 는 일부러 넣지 않는다.** 어떤 마이그레이션도 이 키를 시드하지 않아
 *   프리픽스 조회 응답에서 빠진다(플랫폼 행도 오버라이드도 없음). 여기에 넣으면 실제로 존재할 수
 *   없는 상태를 테스트하게 된다 — 화면은 "내장 기본값" 배지 + 50000 을 보여야 한다.
 * - `ai.model` 값은 `MODEL_OPTIONS` 에 실재하는 코드여야 한다. 목록에 없는 코드를 주면 Select 가
 *   placeholder 를 렌더해 모델 표시에 대한 단언이 무의미해진다(되돌리지 말 것).
 */
export function createAiSettings(
  patch: Partial<Record<string, Partial<ResolvedSettingResponse>>> = {},
): ResolvedSettingResponse[] {
  const base: ResolvedSettingResponse[] = [
    createResolvedSetting({ key: 'ai.agent_type', value: 'sdk', description: '에이전트 유형', tenantEditable: false }),
    createResolvedSetting({ key: 'ai.api_key', value: '****masked****', description: 'API 키', tenantEditable: false }),
    createResolvedSetting({ key: 'ai.cli_oauth_token', value: '', description: 'OAuth 토큰', tenantEditable: false }),
    createResolvedSetting({ key: 'ai.model', value: 'claude-sonnet-5', description: '모델' }),
    createResolvedSetting({ key: 'ai.max_turns', value: '10', description: '최대 턴 수' }),
    createResolvedSetting({ key: 'ai.temperature', value: '1.0', description: 'Temperature' }),
    createResolvedSetting({ key: 'ai.max_tokens', value: '16384', description: '최대 응답 토큰' }),
    createResolvedSetting({
      key: 'ai.system_prompt',
      value: '당신은 도움이 되는 AI 어시스턴트입니다.',
      description: '시스템 프롬프트',
    }),
  ];
  return base.map((s) => (patch[s.key] ? { ...s, ...patch[s.key] } : s));
}

/** SMTP **연결 번들** 5키 — 백엔드 `SettingsService.SMTP_CONNECTION_KEYS` 와 같은 집합이다. */
const SMTP_CONNECTION_KEYS = [
  'smtp.host',
  'smtp.port',
  'smtp.username',
  'smtp.password',
  'smtp.starttls',
];

/**
 * SMTP 설정 6키 — P7-c1 에서 **테넌트 오버라이드 허용**으로 재분류되어 전부 `tenantEditable: true` 다.
 *
 * - 기본은 전 키 상속 중(`overridden: false`). `smtp.from_address` 는 키 단위 상속이므로 그 키의
 *   재정의 상태는 `patch` 로 만든다.
 * - `smtp.password` 는 백엔드가 **오버라이드 값까지 마스킹**해서 준다(Task 2). 그래서 픽스처도
 *   평문이 아니라 `****` 형태를 준다 — 평문을 주면 "화면이 비밀번호를 그대로 받는다"는 존재할 수
 *   없는 상태를 테스트하게 되고, 마스크 센티널이 저장에서 빠지는 경로도 재현되지 않는다.
 *   길이 8(`****` + 마지막 4글자)인 것도 의도다: Task 5 가 백엔드 센티널 판정을
 *   `EncryptionService.maskValue` 의 **형태**(길이 4 또는 8)로 좁혔으므로, 그 밖의 길이는 서버가
 *   만들 수 없는 마스크다.
 *
 * **`connectionOverridden` 노브(Task 5)**: 연결 5키는 서버가 **원자적으로** 해석한다 — 하나라도
 * 테넌트 행이 있으면 5키 전부가 `overridden: true` 로 내려오고, **행이 없는 키는 플랫폼 값이
 * 아니라 `value: ''`** 다. 이 노브에 값을 준 키만 그 값을 갖고 나머지 연결 키는 빈 값이 된다.
 * `patch` 로 `smtp.host` 하나만 `overridden: true` 로 만드는 것은 **서버가 만들 수 없는 응답**이라
 * 그렇게 쓰면 안 된다.
 */
export function createSmtpSettings(
  patch: Partial<Record<string, Partial<ResolvedSettingResponse>>> = {},
  options: { connectionOverridden?: Record<string, string> } = {},
): ResolvedSettingResponse[] {
  const base: ResolvedSettingResponse[] = [
    createResolvedSetting({ key: 'smtp.host', value: 'smtp.gmail.com', description: '발신 메일 서버 주소' }),
    createResolvedSetting({ key: 'smtp.port', value: '587', description: '포트' }),
    createResolvedSetting({ key: 'smtp.username', value: 'user@example.com', description: '사용자 이름' }),
    createResolvedSetting({ key: 'smtp.password', value: '****3f2a', description: '비밀번호' }),
    createResolvedSetting({ key: 'smtp.starttls', value: 'true', description: 'STARTTLS 사용' }),
    createResolvedSetting({
      key: 'smtp.from_address',
      value: 'noreply@example.com',
      description: '발신자 주소',
    }),
  ];
  const bundle = options.connectionOverridden;
  return base.map((s) => {
    const withBundle =
      bundle && SMTP_CONNECTION_KEYS.includes(s.key)
        ? { ...s, overridden: true, value: bundle[s.key] ?? '' }
        : s;
    return patch[s.key] ? { ...withBundle, ...patch[s.key] } : withBundle;
  });
}

/**
 * 임베딩 설정 4키 — P7-b 이후 전부 플랫폼 전용이라 `tenantEditable: false` 로 내려온다.
 * `api_key` 는 백엔드가 마스킹해서 준다.
 */
export function createEmbeddingSettings(
  patch: Partial<Record<string, Partial<ResolvedSettingResponse>>> = {},
): ResolvedSettingResponse[] {
  const base: ResolvedSettingResponse[] = [
    createResolvedSetting({ key: 'embedding.provider', value: 'OLLAMA', description: 'provider', tenantEditable: false }),
    createResolvedSetting({ key: 'embedding.model', value: 'bge-m3', description: '모델', tenantEditable: false }),
    createResolvedSetting({
      key: 'embedding.base_url',
      value: 'http://host.docker.internal:11434',
      description: 'base url',
      tenantEditable: false,
    }),
    createResolvedSetting({ key: 'embedding.api_key', value: '****masked****', description: 'API 키', tenantEditable: false }),
  ];
  return base.map((s) => (patch[s.key] ? { ...s, ...patch[s.key] } : s));
}

/** AuditLogResponse 여러 개를 한 번에 생성 */
export function createAuditLogs(count: number): AuditLogResponse[] {
  return Array.from({ length: count }, (_, i) =>
    createAuditLog({
      id: i + 1,
      description: `감사 로그 ${i + 1}`,
    }),
  );
}

/** 기본 권한 목록 생성 (카테고리별 권한 포함) */
export function createPermissions(): PermissionResponse[] {
  return [
    createPermission({ id: 1, code: 'DATASET_READ', description: '데이터셋 조회', category: 'DATASET' }),
    createPermission({ id: 2, code: 'DATASET_WRITE', description: '데이터셋 수정', category: 'DATASET' }),
    createPermission({ id: 3, code: 'PIPELINE_READ', description: '파이프라인 조회', category: 'PIPELINE' }),
    createPermission({ id: 4, code: 'PIPELINE_WRITE', description: '파이프라인 수정', category: 'PIPELINE' }),
    createPermission({ id: 5, code: 'ADMIN_ACCESS', description: '관리자 페이지 접근', category: 'ADMIN' }),
  ];
}

/** API 연결 목록 생성 (2개) */
export function createApiConnections(): ApiConnectionResponse[] {
  return [
    createApiConnection({ id: 1, name: '공공 데이터 API', authType: 'API_KEY' }),
    createApiConnection({ id: 2, name: '내부 서비스 API', authType: 'BEARER', maskedAuthConfig: { token: '***masked***' } }),
  ];
}
