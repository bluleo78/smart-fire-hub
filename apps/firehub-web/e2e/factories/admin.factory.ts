/**
 * 관리자 도메인 모킹 데이터 팩토리
 * src/types/api-connection.ts, auditLog.ts, role.ts, settings.ts 타입 기반으로 테스트용 객체를 생성한다.
 * overrides 파라미터로 특정 필드만 덮어쓸 수 있다.
 */

import type { ApiConnectionResponse } from '@/types/api-connection';
import type { AuditLogResponse } from '@/types/auditLog';
import type { PermissionResponse, RoleDetailResponse } from '@/types/role';
import type {
  AiClassifyCredentialResponse,
  AiCredentialResponse,
  ResolvedSettingResponse,
} from '@/types/settings';

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
 * 기본값을 `overridden: false, tenantEditable: true`(= 저장한 적 없음 + 편집 가능)로 둔다.
 * 두 플래그를 항상 채워 실제 서버 응답과 같은 형태를 유지한다 — AI 탭의 "기본값" 힌트가
 * `overridden` 을 읽으므로, 플래그 없는 픽스처는 서버가 만들 수 없는 응답을 시험하게 된다.
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
 * `GET /settings?prefix=ai` 응답 재현 — AI 동작 설정은 <b>테넌트 전용</b>이다.
 *
 * 서버는 6키를 <b>항상</b> 내려준다. 테넌트가 저장한 적 없는 키는 코드 기본값(백엔드
 * `AiBehaviorDefaults`)을 `value` 로, `overridden:false` 로 준다. `description`·`updatedAt` 은 null,
 * `tenantEditable` 은 언제나 true 다. 기본은 "아무것도 저장 안 함"(6키 전부 기본값)이고, 저장된
 * 상태가 필요한 테스트는 `patch` 로 그 키만 `{ overridden: true, value }` 로 바꾼다.
 *
 * `ai.model` 값은 `MODEL_OPTIONS` 에 실재하는 코드여야 한다. 목록에 없는 코드를 주면 Select 가
 * placeholder 를 렌더해 모델 표시에 대한 단언이 무의미해진다(되돌리지 말 것).
 */
export function createAiSettings(
  patch: Partial<Record<string, Partial<ResolvedSettingResponse>>> = {},
): ResolvedSettingResponse[] {
  const aiDefault = (key: string, value: string) =>
    createResolvedSetting({ key, value, description: null, updatedAt: null, overridden: false, tenantEditable: true });
  const base: ResolvedSettingResponse[] = [
    aiDefault('ai.model', 'claude-sonnet-5'),
    aiDefault('ai.max_turns', '10'),
    aiDefault(
      'ai.system_prompt',
      '당신은 Smart Fire Hub의 AI 어시스턴트입니다.\n응답은 한국어로 하고, 마크다운 형식을 사용하세요.',
    ),
    aiDefault('ai.temperature', '1.0'),
    aiDefault('ai.max_tokens', '16384'),
    aiDefault('ai.session_max_tokens', '50000'),
  ];
  return base.map((s) => (patch[s.key] ? { ...s, ...patch[s.key] } : s));
}

/**
 * `GET /settings/ai-credential` 응답 재현 — Task 12(타입형 AI 자격증명 전용 문서)의 유일한 소비처.
 * `createAiSettings`(동작 설정 6키)와는 다른 자원이다.
 *
 * 기본값은 `sdk` · 설정됨(`configured:true`) · 비밀 둘 다 설정됨(값이 있는 상태) — 값이 있어야 "현재 값이
 * 설정되어 있습니다" 힌트처럼 손대지 않아도 화면에 보이는 무언가를 만들 수 있다. 비밀 "값" 자체는 서버가 절대
 * 돌려주지 않으므로(`secretFieldNames` 는 이름만) 이 팩토리도 값을 갖지 않는다.
 */
export function createAiCredential(overrides?: Partial<AiCredentialResponse>): AiCredentialResponse {
  return {
    agentType: 'sdk',
    payload: {},
    secretFieldNames: ['oauthToken', 'apiKey'],
    configured: true,
    ...overrides,
  };
}

/**
 * `GET /settings/ai-classify-credential` 응답(#707). 기본은 **미설정** — 배포 직후 모든 테넌트의
 * 상태이고, 이때 AI 분류는 AI 에이전트 설정을 통째로 쓴다.
 */
export function createAiClassifyCredential(
  overrides?: Partial<AiClassifyCredentialResponse>,
): AiClassifyCredentialResponse {
  return { agentType: 'sdk', payload: {}, secretFieldNames: [], configured: false, model: '', ...overrides };
}

/**
 * 워크스페이스가 저장한 SMTP 설정 6키 — `GET /settings?prefix=smtp` 응답(#712).
 *
 * - SMTP 는 워크스페이스 전용이라 서버는 **저장된 키만** 내려준다. 저장된 키는 전부
 *   `overridden: true`·`tenantEditable: true` 이고, 플랫폼 행이 없으므로 `description`/`updatedAt` 은 null 이다.
 * - 미설정 워크스페이스는 **빈 배열**이다 — `createSmtpSettings` 대신 `[]` 를 쓴다.
 * - `smtp.password` 는 서버가 마스킹해서 준다. 길이 8(`****` + 마지막 4글자)인 것도 의도다:
 *   백엔드 센티널 판정이 `EncryptionService.maskValue` 의 형태(길이 4 또는 8)만 드롭하므로, 그 밖의
 *   길이는 서버가 만들 수 없는 마스크다.
 * - `patch` 로 키별 값을 바꾼다(예: `{ 'smtp.password': { value: '' } }` = 비밀번호 없는 릴레이).
 */
export function createSmtpSettings(
  patch: Partial<Record<string, Partial<ResolvedSettingResponse>>> = {},
): ResolvedSettingResponse[] {
  const saved = (key: string, value: string) =>
    createResolvedSetting({ key, value, description: null, updatedAt: null, overridden: true, tenantEditable: true });
  const base: ResolvedSettingResponse[] = [
    saved('smtp.host', 'smtp.gmail.com'),
    saved('smtp.port', '587'),
    saved('smtp.username', 'user@example.com'),
    saved('smtp.password', '****3f2a'),
    saved('smtp.starttls', 'true'),
    saved('smtp.from_address', 'noreply@example.com'),
  ];
  return base.map((s) => (patch[s.key] ? { ...s, ...patch[s.key] } : s));
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
