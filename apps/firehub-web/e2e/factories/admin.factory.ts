/**
 * 관리자 도메인 모킹 데이터 팩토리
 * src/types/api-connection.ts, auditLog.ts, role.ts, settings.ts 타입 기반으로 테스트용 객체를 생성한다.
 * overrides 파라미터로 특정 필드만 덮어쓸 수 있다.
 */

import type { ApiConnectionResponse } from '@/types/api-connection';
import type { AuditLogResponse } from '@/types/auditLog';
import type { PermissionResponse, RoleDetailResponse } from '@/types/role';
import type { SettingResponse } from '@/types/settings';

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
    createdBy: 1,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
    ...overrides,
  };
}

/** 설정(Setting) 응답 객체 생성 */
export function createSetting(overrides?: Partial<SettingResponse>): SettingResponse {
  return {
    key: 'app.name',
    value: 'Smart Fire Hub',
    description: '애플리케이션 이름',
    updatedAt: '2024-01-01T00:00:00Z',
    ...overrides,
  };
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
