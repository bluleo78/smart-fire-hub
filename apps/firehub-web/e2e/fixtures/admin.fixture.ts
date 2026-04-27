import type { Page } from '@playwright/test';

import {
  createApiConnections,
  createAuditLogs,
  createPermissions,
  createRoleDetail,
  createSetting,
} from '../factories/admin.factory';
import { createAdminUserDetail, createRole, createUser, createUserDetail } from '../factories/auth.factory';
import { createPageResponse, mockApi } from './api-mock';

/**
 * 관리자 도메인 모킹 헬퍼
 * - 관리자 페이지(사용자/역할/감사 로그/설정/API 연결) 테스트에서 공통으로 사용하는 API 모킹 함수를 제공한다.
 * - AdminRoute는 ADMIN 역할을 가진 사용자만 접근 허용하므로, 각 헬퍼는 users/me 오버라이드를 포함한다.
 */

/**
 * 관리자 권한으로 users/me를 오버라이드한다.
 * - authenticatedPage는 기본적으로 USER 역할만 갖고 있어 AdminRoute를 통과하지 못한다.
 * - 이 함수를 beforeEach에서 호출하여 ADMIN 역할 사용자로 전환한다.
 */
export async function setupAdminAuth(page: Page) {
  // ADMIN 역할을 가진 사용자로 /api/v1/users/me를 오버라이드
  await mockApi(page, 'GET', '/api/v1/users/me', createAdminUserDetail());
}

/**
 * 사용자 목록 페이지 API 모킹
 * - 사용자 목록(페이지네이션)을 모킹한다.
 * @param count - 목록에 포함할 사용자 수 (기본값: 3)
 */
export async function setupUserListMocks(page: Page, count = 3) {
  const users = Array.from({ length: count }, (_, i) =>
    createUser({ id: i + 1, name: `사용자 ${i + 1}`, username: `user${i + 1}`, email: `user${i + 1}@example.com` }),
  );
  await mockApi(page, 'GET', '/api/v1/users', createPageResponse(users));
}

/**
 * 사용자 상세 페이지 API 모킹
 * - 단일 사용자 상세 정보와 역할 목록을 모킹한다.
 * @param userId - 모킹할 사용자 ID (기본값: 1)
 */
export async function setupUserDetailMocks(page: Page, userId = 1) {
  const userDetail = createUserDetail({
    id: userId,
    name: '테스트 사용자',
    username: `user${userId}`,
    email: `user${userId}@example.com`,
  });
  await mockApi(page, 'GET', `/api/v1/users/${userId}`, userDetail);
  // 역할 목록 (역할 할당 UI에서 사용)
  await mockApi(page, 'GET', '/api/v1/roles', [
    createRole({ id: 1, name: 'USER', description: '일반 사용자', isSystem: true }),
    createRole({ id: 2, name: 'ADMIN', description: '시스템 관리자', isSystem: true }),
  ]);
}

/**
 * 역할 목록 페이지 API 모킹
 * - 역할 목록(시스템/커스텀 혼합)을 모킹한다.
 */
export async function setupRoleListMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/roles', [
    createRole({ id: 1, name: 'USER', description: '일반 사용자', isSystem: true }),
    createRole({ id: 2, name: 'ADMIN', description: '시스템 관리자', isSystem: true }),
    createRole({ id: 3, name: 'EDITOR', description: '편집자', isSystem: false }),
  ]);
}

/**
 * 역할 상세 페이지 API 모킹
 * - 역할 상세 정보와 전체 권한 목록을 모킹한다.
 * @param roleId - 모킹할 역할 ID (기본값: 1)
 * @param isSystem - 시스템 역할 여부 (기본값: false)
 */
export async function setupRoleDetailMocks(page: Page, roleId = 1, isSystem = false) {
  const roleDetail = createRoleDetail({
    id: roleId,
    name: isSystem ? 'USER' : 'EDITOR',
    description: isSystem ? '일반 사용자 역할' : '편집자 역할',
    isSystem,
  });
  await mockApi(page, 'GET', `/api/v1/roles/${roleId}`, roleDetail);
  await mockApi(page, 'GET', '/api/v1/permissions', createPermissions());
}

/**
 * 감사 로그 목록 페이지 API 모킹
 * - 감사 로그 목록(페이지네이션)을 모킹한다.
 * @param count - 목록에 포함할 감사 로그 수 (기본값: 5)
 */
export async function setupAuditLogMocks(page: Page, count = 5) {
  await mockApi(
    page,
    'GET',
    '/api/v1/admin/audit-logs',
    createPageResponse(createAuditLogs(count)),
  );
}

/**
 * 설정 페이지 API 모킹
 * - AI 설정 목록을 모킹한다.
 */
export async function setupSettingsMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/settings', [
    createSetting({ key: 'ai.agent_type', value: 'sdk', description: '에이전트 유형' }),
    createSetting({ key: 'ai.model', value: 'claude-sonnet-4-6', description: '모델' }),
    createSetting({ key: 'ai.max_turns', value: '10', description: '최대 턴 수' }),
    createSetting({ key: 'ai.system_prompt', value: '당신은 도움이 되는 AI 어시스턴트입니다.', description: '시스템 프롬프트' }),
    createSetting({ key: 'ai.temperature', value: '1.0', description: 'Temperature' }),
    createSetting({ key: 'ai.max_tokens', value: '16384', description: '최대 응답 토큰' }),
    createSetting({ key: 'ai.session_max_tokens', value: '50000', description: '세션 최대 토큰' }),
    createSetting({ key: 'ai.api_key', value: '****masked****', description: 'API 키' }),
    createSetting({ key: 'ai.cli_oauth_token', value: '', description: 'OAuth 토큰' }),
  ]);
}

/**
 * API 연결 목록 페이지 API 모킹
 * - API 연결 목록 + selectable 슬림 목록을 모킹한다.
 */
export async function setupApiConnectionListMocks(page: Page) {
  const connections = createApiConnections();
  await mockApi(page, 'GET', '/api/v1/api-connections', connections);
  // selectable: 파이프라인 스텝 및 일반 사용자용 슬림 목록
  await mockApi(
    page,
    'GET',
    '/api/v1/api-connections/selectable',
    connections.map(({ id, name, authType, baseUrl }) => ({ id, name, authType, baseUrl })),
  );
  // refresh-all: 전체 갱신 트리거
  await mockApi(page, 'POST', '/api/v1/api-connections/refresh-all', { jobId: 'test-job-id' });
}

/**
 * API 연결 상세 페이지 API 모킹
 * - 단일 API 연결 상세 정보와 연결 테스트 엔드포인트를 모킹한다.
 * @param connectionId - 모킹할 API 연결 ID (기본값: 1)
 */
export async function setupApiConnectionDetailMocks(page: Page, connectionId = 1) {
  const connection = createApiConnections()[0];
  await mockApi(page, 'GET', `/api/v1/api-connections/${connectionId}`, {
    ...connection,
    id: connectionId,
  });
  // 연결 즉시 테스트 응답 모킹 (#76: 응답 본문/헤더/요청 URL 포함)
  await mockApi(page, 'POST', `/api/v1/api-connections/${connectionId}/test`, {
    ok: true,
    status: 200,
    latencyMs: 120,
    errorMessage: null,
    requestUrl: 'https://api.example.com/health',
    responseBodyPreview: '{"status":"ok"}',
    responseHeaders: { 'content-type': 'application/json' },
    responseContentType: 'application/json',
  });
}
