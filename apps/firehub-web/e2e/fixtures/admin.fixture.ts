import type { Page } from '@playwright/test';

import type { ResolvedSettingResponse } from '@/types/settings';

import {
  createAiSettings,
  createApiConnections,
  createAuditLogs,
  createEmbeddingSettings,
  createPermissions,
  createRoleDetail,
  createSmtpSettings,
} from '../factories/admin.factory';
import { createAdminUserDetail, createRole, createUser, createUserDetail } from '../factories/auth.factory';
import { createOntologySummaries } from '../factories/mapping.factory';
import { createOntologyGraph, createOntologySchema } from '../factories/ontology.factory';
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
  // 감사 로그 페이지의 사용자 dropdown(#89) 등 다양한 admin 페이지가 GET /users 를 호출하므로
  // 기본 빈 목록을 모킹해 unhandled request로 실네트워크에 빠지지 않도록 한다. 개별 테스트는
  // 필요시 setupUserListMocks 등으로 오버라이드한다.
  await mockApi(page, 'GET', '/api/v1/users', createPageResponse([]));
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
  // 사용자 dropdown 필터 (#89)에서 사용하는 사용자 목록 모킹.
  // 감사 로그 페이지가 마운트되자마자 GET /users?size=100 을 호출하므로 빈 목록이라도 모킹해야 함.
  const users = [
    createUser({ id: 1, name: '관리자', username: 'admin', email: 'admin@example.com' }),
    createUser({ id: 2, name: '테스트 사용자', username: 'testuser', email: 'test@example.com' }),
  ];
  await mockApi(page, 'GET', '/api/v1/users', createPageResponse(users));
}

/** 목록 자체 또는 "호출 시점에 목록을 만드는 함수" 둘 다 받는다 — 재정의 해제 후 재조회처럼 응답이 바뀌는 경우를 위해. */
type SettingsSource = ResolvedSettingResponse[] | (() => ResolvedSettingResponse[]);

const resolveSource = (source: SettingsSource) =>
  typeof source === 'function' ? source() : source;

/**
 * 설정 페이지 API 모킹 — `GET /api/v1/settings` 를 `prefix` 쿼리로 분기한다.
 *
 * 실제 백엔드와 같은 한 엔드포인트를 쓰되 prefix 로 다른 목록을 주는 이유: AI·이메일·임베딩 세 탭이
 * 같은 경로를 서로 다른 prefix 로 호출하므로, path 만 보는 `mockApi` 로는 셋을 구분할 수 없다.
 * GET 이 아닌 메서드(PUT/DELETE)는 `route.fallback()` 으로 다음 핸들러(캡처용 모킹)에 넘긴다.
 *
 * 어느 항목에 함수를 넘기면 호출 시점마다 평가되므로, DELETE 후 재조회에서 "그 키만 상속으로
 * 돌아온" 응답을 줄 수 있다.
 *
 * <b>모르는 prefix 는 던진다.</b> 예전에는 `embedding` 이 아니면 전부 AI 목록으로 흘렸는데,
 * P7-c1 에서 이메일 탭이 `prefix=smtp` 로 옮겨오자 그 폴백이 **AI 설정 6건을 SMTP 응답인 척**
 * 돌려주게 됐다 — 스펙은 "SMTP 필드가 비어 있다"로 실패하고, 원인은 화면이 아니라 픽스처다.
 * 라우팅되지 않은 prefix 를 조용히 다른 목록으로 대체하면 그 진단이 매번 늦어진다.
 */
export async function setupSettingsMocks(
  page: Page,
  options: { ai?: SettingsSource; embedding?: SettingsSource; smtp?: SettingsSource } = {},
) {
  const sources: Record<string, SettingsSource> = {
    ai: options.ai ?? createAiSettings(),
    embedding: options.embedding ?? createEmbeddingSettings(),
    smtp: options.smtp ?? createSmtpSettings(),
  };
  await page.route(
    (url) => url.pathname === '/api/v1/settings',
    (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const prefix = new URL(route.request().url()).searchParams.get('prefix') ?? '';
      const source = sources[prefix];
      if (!source) {
        // 라우트 핸들러에서 throw 하면 요청이 그대로 매달려 "타임아웃"으로만 보인다. 이유를 본문에
        // 실어 500 으로 끊어야 실패 화면에서 원인이 읽힌다.
        return route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({
            message: `설정 모킹에 등록되지 않은 prefix: "${prefix}" — 픽스처에 목록을 추가하세요.`,
          }),
        });
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(resolveSource(source)),
      });
    },
  );
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

/**
 * 온톨로지 시각화 페이지 API 모킹
 * - 스키마(GET /api/v1/ontology)와 인스턴스 그래프(GET /api/v1/ontology/graph)를 모킹한다.
 * - 온톨로지 선택기(Task 9)가 목록(GET /api/v1/ontologies)과 기본 선택(id=1) by-id 조회를
 *   항상 호출하므로 이 둘도 함께 모킹한다 — 없으면 선택기가 비어 편집 버튼 등 스키마 탭
 *   컨트롤이 전부 노출되지 않는다.
 */
export async function setupOntologyMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/ontology', createOntologySchema());
  await mockApi(page, 'GET', '/api/v1/ontology/graph', createOntologyGraph());
  await mockApi(page, 'GET', '/api/v1/ontologies', createOntologySummaries());
  await mockApi(page, 'GET', '/api/v1/ontology/1', createOntologySchema());
}

/**
 * 온톨로지 인스턴스 그래프 조회 실패 모킹
 * - 스키마는 정상 응답하되, 그래프 엔드포인트만 500을 반환해 에러 상태 UI를 검증할 수 있게 한다.
 */
export async function setupOntologyGraphErrorMock(page: Page) {
  await mockApi(page, 'GET', '/api/v1/ontology', createOntologySchema());
  await mockApi(page, 'GET', '/api/v1/ontology/graph', { message: '그래프 조회 실패' }, { status: 500 });
}

/**
 * 온톨로지 인스턴스 그래프 재시도(refetch) 모킹
 * - 초기 로드(TanStack Query retry:1 → 최초 요청 + 자동 재시도 1회 = 2회)는 모두 500으로 실패시켜 에러 UI를 노출하고,
 *   그 이후(사용자 "다시 시도" 클릭에 의한 refetch)부터는 200 정상 그래프를 반환한다.
 * - 실제 재요청이 발생했는지 검증할 수 있도록 총 호출 횟수를 담은 카운터 객체를 반환한다.
 */
export async function setupOntologyGraphRetryMock(page: Page) {
  await mockApi(page, 'GET', '/api/v1/ontology', createOntologySchema());

  const counter = { calls: 0 };
  const graphBody = createOntologyGraph();
  await page.route(
    (url) => url.pathname === '/api/v1/ontology/graph',
    (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      counter.calls += 1;
      // 최초 로드의 요청+자동 재시도(2회)는 실패, 이후 수동 refetch부터 성공.
      const fail = counter.calls <= 2;
      return route.fulfill({
        status: fail ? 500 : 200,
        contentType: 'application/json',
        body: JSON.stringify(fail ? { message: '그래프 조회 실패' } : graphBody),
      });
    },
  );
  return counter;
}
