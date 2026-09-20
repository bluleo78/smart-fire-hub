import type { Page } from '@playwright/test';

import type { AiCredentialResponse, AiCredentialUpsertPayload, ResolvedSettingResponse } from '@/types/settings';

import {
  createAiCredential,
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
import { createOntologyGraph, createOntologySchema, createSecondOntologySchema } from '../factories/ontology.factory';
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

/**
 * 목록 자체 또는 "호출 시점에 목록을 만드는 함수" 둘 다 받는다 — 재정의 해제 후 재조회처럼 응답이
 * 바뀌는 경우를 위해.
 *
 * 함수형은 목록 대신 {@link SETTINGS_FETCH_ERROR} 를 돌려줄 수 있다. 조회 **실패**를 재현하는
 * 시나리오(최초 로드 실패, 저장 후 재조회 실패)가 그 하나를 표현하지 못해 스펙마다 프리픽스·메서드
 * 분기를 손으로 다시 구현하고 있었다 — 그 분기 계약이 세 곳에 흩어지던 것을 여기 하나로 되돌린다.
 */
type SettingsSource =
  | ResolvedSettingResponse[]
  | (() => ResolvedSettingResponse[] | typeof SETTINGS_FETCH_ERROR);

/** 이 값을 돌려주면 해당 GET 이 500 으로 떨어진다. */
export const SETTINGS_FETCH_ERROR = 'error' as const;

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
      const resolved = resolveSource(source);
      if (resolved === SETTINGS_FETCH_ERROR) {
        return route.fulfill({ status: 500, contentType: 'application/json', body: '{}' });
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(resolved),
      });
    },
  );
}

/**
 * `GET/PUT/DELETE /api/v1/settings/ai-credential` 모킹 — Task 12 이 새로 여는 전용 문서 자원.
 *
 * 옛 3키(`ai.agent_type`/`ai.api_key`/`ai.cli_oauth_token`)는 `setupSettingsMocks` 의 `prefix=ai`
 * 목록에 얹혔지만(문자열 키·값 배열), `ai.credential` 은 하위 필드(secret)를 가진 JSON 문서 하나라
 * 경로 자체가 다르고(`/settings/ai-credential`), 세 메서드(GET/PUT/DELETE)가 그 경로 하나를
 * 공유한다 — `prefix` 분기가 필요 없는 대신, 메서드 분기가 필요하다.
 *
 * `get` 은 `setupSettingsMocks` 의 `SettingsSource` 와 같은 규약(고정값 | 재평가 함수)을 따른다.
 * `PUT`/`DELETE` 는 호출 자체가 브리프의 핵심 단언 대상이라("라디오만 바꾸고 저장하지 않으면
 * DELETE 가 없다") 캡처해 반환한다 — `captureOverrideDeletes` 와 같은 이유지만, 그쪽은 키마다
 * 다른 경로(`/settings/overrides/{key}`)에 3번 나가는 번들이고 이쪽은 단일 문서라 한 번만 나간다.
 *
 * 반환하는 `calls` 는 <b>같은 객체를 계속 변형한다</b> — 스펙이 `get` 함수 안에서
 * `calls.deleteCount > 0 ? 저장후상태 : 저장전상태` 처럼 자기 자신을 참조해 "저장한 뒤에만 새
 * 값을 준다"는 재조회 응답을 만들 수 있다(호출 시점에는 이미 `const calls = await
 * mockAiCredential(...)` 대입이 끝나 있으므로 TDZ 걱정 없이 안전하다).
 */
export async function mockAiCredential(
  page: Page,
  get: AiCredentialResponse | (() => AiCredentialResponse) = createAiCredential(),
): Promise<{ puts: AiCredentialUpsertPayload[]; deleteCount: number }> {
  const calls: { puts: AiCredentialUpsertPayload[]; deleteCount: number } = { puts: [], deleteCount: 0 };
  await page.route(
    (url) => url.pathname === '/api/v1/settings/ai-credential',
    (route) => {
      const method = route.request().method();
      if (method === 'GET') {
        const data = typeof get === 'function' ? get() : get;
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(data) });
      }
      if (method === 'PUT') {
        calls.puts.push(route.request().postDataJSON() as AiCredentialUpsertPayload);
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      }
      if (method === 'DELETE') {
        calls.deleteCount += 1;
        return route.fulfill({ status: 204 });
      }
      return route.fallback();
    },
  );
  return calls;
}

/** `GET /api/v1/ai/auth-status` 의 경로. 네 곳이 같은 문자열을 따로 적고 있었다. */
export const AI_AUTH_STATUS_PATH = '/api/v1/ai/auth-status';

/**
 * 인증 확인 응답 한 벌. <b>`SettingsPage` 의 `authStatus` 상태와 같은 모양이어야 한다</b> —
 * 그 타입은 `subscriptionType` 까지 갖는데 스펙의 손수 만든 본문들은 그걸 보내지 않고 있었다.
 * 모양이 네 군데 흩어져 있으면 계약이 바뀔 때 한 곳만 고치고 넘어간다.
 */
export interface AiAuthStatus {
  valid: boolean;
  email?: string;
  subscriptionType?: string;
}

/**
 * 위 응답을 `route.fulfill` 인자로 만든다.
 *
 * 라우팅을 스스로 해야 하는 스펙(호출 순서를 직접 다루는 경쟁 상태 테스트)도 <b>본문만은</b>
 * 이걸 쓰게 해서, 라우트 핸들러의 타이밍을 건드리지 않고 모양의 중복만 없앤다.
 */
export function aiAuthStatusResponse(status: AiAuthStatus) {
  return { status: 200, contentType: 'application/json', body: JSON.stringify(status) };
}

/**
 * `GET /api/v1/ai/auth-status` 모킹 — <b>값을 함수로 받는다</b>.
 *
 * <b>왜 `mockApi` 를 못 쓰나</b>: 그쪽은 본문이 고정이라 도중에 바꿀 수 없다. 이 화면의 테스트는
 * 대부분 "조작 <b>전</b>에는 인증됨, <b>후</b>에는 아님"을 확인하므로 같은 라우트가 호출 시점마다
 * 다른 답을 줘야 한다. 그래서 스펙마다 같은 블록을 손으로 다시 적고 있었다(세 벌).
 *
 * 값이 아니라 함수를 받는 것은 `setupSettingsMocks` 의 `SettingsSource` 와 <b>같은 규약</b>이다 —
 * 호출부는 `let` 하나를 뒤집으면 되고, 플래그와 응답이 따로 놀 자리가 없다.
 */
export async function mockAiAuthStatus(page: Page, resolve: () => AiAuthStatus) {
  await page.route(
    (url) => url.pathname === AI_AUTH_STATUS_PATH,
    (route) => route.fulfill(aiAuthStatusResponse(resolve())),
  );
}

/**
 * `DELETE /api/v1/settings/overrides/{key}` 캡처 라우트.
 *
 * <b>왜 `mockApi` 를 못 쓰나</b>: `mockApi` 는 pathname **완전 일치**인데 이 경로는 키가 뒤에
 * 붙는 프리픽스 매칭이고, DELETE 는 204 no-content 라 본문이 없다(`mockApi` 는 항상 JSON 본문을
 * 붙인다). 그래서 직접 라우팅해야 하는데, 그 결과 같은 블록이 `settings.spec.ts` 에 5벌 있었다.
 *
 * @param failOn 이 키로 끝나는 DELETE 만 500 을 준다 — 번들 해제의 **부분 실패**를 재현한다.
 *   실패한 키는 `deletedPaths` 에 담기지 않는다(이름이 사실이어야 한다).
 * @returns 성공한 DELETE 의 pathname 이 순서대로 쌓이는 배열. 재조회 응답을 분기해야 하는
 *   테스트는 별도 boolean 대신 `deletedPaths.length > 0` 을 읽으면 된다 — 플래그와 배열이
 *   따로 놀 여지가 사라진다.
 */
export async function captureOverrideDeletes(
  page: Page,
  options: { failOn?: string } = {},
): Promise<{ deletedPaths: string[] }> {
  const deletedPaths: string[] = [];
  await page.route(
    (url) => url.pathname.startsWith('/api/v1/settings/overrides/'),
    (route) => {
      if (route.request().method() !== 'DELETE') return route.fallback();
      const pathname = new URL(route.request().url()).pathname;
      if (options.failOn !== undefined && pathname.endsWith(options.failOn)) {
        return route.fulfill({ status: 500 });
      }
      deletedPaths.push(pathname);
      return route.fulfill({ status: 204 });
    },
  );
  return { deletedPaths };
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
 * - createOntologySummaries()가 기본으로 active 온톨로지 2개(id=1,2)를 내려주므로, 그래프 탐색 탭의
 *   타입 필터가 active 온톨로지 전체를 합쳐 조회하는(#677) 이상 id=2도 모킹해야 한다 — 안 하면 매 테스트마다
 *   백엔드 없는 환경에서 실패하는 네트워크 요청이 발생한다. entities를 비워 기존 count(6) 단언들이
 *   깨지지 않게 한다(합집합에 새 타입을 보태지 않음). 여러 온톨로지의 타입을 실제로 합치는 동작은
 *   ontology.spec.ts의 전용 테스트가 별도로 검증한다.
 */
/**
 * 인스턴스 그래프 경로 매처 — GET /api/v1/ontology/{id}/graph.
 *
 * id 자리를 `\d+`로 좁히지 않는 것이 중요하다: 스코프 게이트가 깨지면 id가 undefined/null인 채로
 * 요청이 나가는데, 숫자만 매칭하면 그 요청이 이 매처를 그냥 통과해 "요청 0건" 단언이 공허해진다
 * (실제로 그렇게 만들어 한 번 겪었다). 정상 호출부의 id는 항상 숫자라 넓혀도 부작용이 없다.
 */
const ONTOLOGY_GRAPH_PATH_RE = /^\/api\/v1\/ontology\/[^/]+\/graph$/;

/**
 * page.route 에 넘길 매처는 반드시 **술어**여야 한다 — RegExp 를 그대로 주면 Playwright 가 그것을
 * pathname 이 아니라 전체 URL(`http://localhost:5199/api/...`)에 매칭해서, `^\/api` 로 앵커된 패턴은
 * 아무것도 잡지 못하고 모킹이 조용히 통째로 무력화된다(실제로 그렇게 만들어 온톨로지 스펙이 절반
 * 넘게 깨졌다).
 */
export const ONTOLOGY_GRAPH_PATH = (url: URL) => ONTOLOGY_GRAPH_PATH_RE.test(url.pathname);

/**
 * 인스턴스 그래프(GET /api/v1/ontology/{id}/graph) 모킹 헬퍼.
 *
 * 그래프 조회가 온톨로지 스코프로 바뀌면서 경로에 id가 들어간다 — 테스트가 온톨로지를 전환하면
 * 경로도 함께 바뀌므로, id를 고정한 exact-path 모킹(mockApi)으로는 전환 시나리오가 깨진다.
 *
 * body는 고정값이거나 pathname을 받는 함수다(setupSettingsMocks의 SettingsSource와 같은 규약) —
 * 온톨로지별로 다른 그래프를 내려주는 시나리오가 이것으로 해결된다. 반환하는 requestedPaths는
 * 실제로 도달한 요청 경로 기록이고(captureOverrideDeletes와 같은 규약), 호출 여부·0건 단언에 쓴다.
 */
type OntologyGraphSource = object | ((pathname: string) => unknown);

export async function mockOntologyGraph(
  page: Page,
  body: OntologyGraphSource,
  options?: { status?: number },
): Promise<{ requestedPaths: string[] }> {
  const requestedPaths: string[] = [];
  await page.route(ONTOLOGY_GRAPH_PATH, (route) => {
    if (route.request().method() !== 'GET') return route.fallback();
    const pathname = new URL(route.request().url()).pathname;
    requestedPaths.push(pathname);
    return route.fulfill({
      status: options?.status ?? 200,
      contentType: 'application/json',
      body: JSON.stringify(typeof body === 'function' ? body(pathname) : body),
    });
  });
  return { requestedPaths };
}

/**
 * "처음 N회는 실패, 그 뒤 성공" 라우트. failCount 기본값이 2인 이유: TanStack Query 기본 retry:1
 * 때문에 최초 로드가 요청 + 자동 재시도로 2번 나가고, 그 둘을 모두 실패시켜야 에러 UI가 노출된다.
 * 이후 사용자가 "다시 시도"를 눌러 발생한 refetch부터 성공한다. 재요청이 실제로 일어났는지
 * 확인할 수 있게 호출 횟수 카운터를 반환한다.
 */
export async function mockFailThenSucceed(
  page: Page,
  // 정확한 pathname 문자열이거나 술어. RegExp 를 받지 않는 이유는 ONTOLOGY_GRAPH_PATH 주석 참고.
  matcher: string | ((url: URL) => boolean),
  opts: { errorBody: unknown; okBody: unknown; failCount?: number },
): Promise<{ calls: number }> {
  const counter = { calls: 0 };
  const failCount = opts.failCount ?? 2;
  await page.route(
    typeof matcher === 'string' ? (url) => url.pathname === matcher : matcher,
    (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      counter.calls += 1;
      const fail = counter.calls <= failCount;
      return route.fulfill({
        status: fail ? 500 : 200,
        contentType: 'application/json',
        body: JSON.stringify(fail ? opts.errorBody : opts.okBody),
      });
    },
  );
  return counter;
}

export async function setupOntologyMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/ontology', createOntologySchema());
  await mockOntologyGraph(page, createOntologyGraph());
  await mockApi(page, 'GET', '/api/v1/ontologies', createOntologySummaries());
  await mockApi(page, 'GET', '/api/v1/ontology/1', createOntologySchema());
  await mockApi(page, 'GET', '/api/v1/ontology/2', createSecondOntologySchema());
}

/**
 * 온톨로지 인스턴스 그래프 조회 실패 모킹 — 스키마·목록은 정상이고 그래프만 500.
 *
 * 기본 모킹을 먼저 깔고 그래프만 덮어쓴다: page.route 는 나중에 등록한 것이 앞을 가린다.
 * 목록 모킹이 반드시 있어야 하는 이유는 그래프 조회가 선택된 온톨로지가 있을 때만 일어나기
 * 때문이다(useOntologyGraph 의 enabled) — 목록이 비면 "지식 모델 없음" 빈 상태로 빠져 에러 UI에
 * 닿지 못한다. 아래 재시도 모킹도 같은 이유로 setupOntologyMocks 를 깐다.
 */
export async function setupOntologyGraphErrorMock(page: Page) {
  await setupOntologyMocks(page);
  await mockOntologyGraph(page, { message: '그래프 조회 실패' }, { status: 500 });
}

/** 온톨로지 인스턴스 그래프 재시도(refetch) 모킹 — 초기 로드는 실패, 수동 재시도부터 성공. */
export async function setupOntologyGraphRetryMock(page: Page) {
  await setupOntologyMocks(page);
  return mockFailThenSucceed(page, ONTOLOGY_GRAPH_PATH, {
    errorBody: { message: '그래프 조회 실패' },
    okBody: createOntologyGraph(),
  });
}
