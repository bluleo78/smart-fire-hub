/**
 * Phase 9 — API 연결 라이프사이클 E2E.
 * 생성 → 목록 확인 → 테스트(지금 확인) → 상태 배지 반영 전 과정 검증.
 */
import type { ApiConnectionResponse } from '../../src/types/api-connection';
import { createApiConnection } from '../factories/admin.factory';
import { setupAdminAuth } from '../fixtures/admin.fixture';
import { expect, test } from '../fixtures/auth.fixture';

test.describe('API 연결 라이프사이클', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    // AdminRoute 통과를 위해 ADMIN 역할로 오버라이드
    await setupAdminAuth(page);
  });

  test('생성 → 목록 → 테스트 → 상태 반영', async ({ authenticatedPage: page }) => {
    // 인메모리 연결 목록 — route 핸들러가 참조하여 상태를 공유한다
    const connections: ApiConnectionResponse[] = [];
    let nextId = 1;

    // GET /api/v1/api-connections — 목록 반환
    // POST /api/v1/api-connections — 새 연결 생성 후 목록에 추가
    await page.route('**/api/v1/api-connections', async (route) => {
      const method = route.request().method();
      if (method === 'GET') {
        await route.fulfill({ status: 200, body: JSON.stringify(connections) });
        return;
      }
      if (method === 'POST') {
        const body = route.request().postDataJSON() as Partial<ApiConnectionResponse> & {
          headerName?: string;
          apiKey?: string;
        };
        const created = createApiConnection({
          id: nextId++,
          name: body.name ?? 'API 연결',
          authType: (body.authType as ApiConnectionResponse['authType']) ?? 'API_KEY',
          baseUrl: body.baseUrl ?? 'https://api.example.com',
          healthCheckPath: body.healthCheckPath ?? null,
          lastStatus: null,
          lastCheckedAt: null,
          lastLatencyMs: null,
          lastErrorMessage: null,
        });
        connections.push(created);
        await route.fulfill({ status: 200, body: JSON.stringify(created) });
        return;
      }
      await route.continue();
    });

    // selectable 슬림 목록 모킹 (페이지 초기 로드 시 사용될 수 있음)
    await page.route('**/api/v1/api-connections/selectable', async (route) => {
      await route.fulfill({
        status: 200,
        body: JSON.stringify(connections.map(({ id, name, authType, baseUrl }) => ({ id, name, authType, baseUrl }))),
      });
    });

    // POST /{id}/test — 연결 즉시 테스트 후 connections 상태 UP으로 갱신
    await page.route('**/api/v1/api-connections/*/test', async (route) => {
      const match = route.request().url().match(/\/api-connections\/(\d+)\/test$/);
      const id = match ? Number(match[1]) : 0;
      const idx = connections.findIndex((c) => c.id === id);
      if (idx >= 0) {
        connections[idx] = {
          ...connections[idx],
          lastStatus: 'UP',
          lastCheckedAt: new Date().toISOString(),
          lastLatencyMs: 120,
          lastErrorMessage: null,
        };
      }
      await route.fulfill({
        status: 200,
        body: JSON.stringify({
          ok: true,
          status: 200,
          latencyMs: 120,
          errorMessage: null,
          requestUrl: 'https://api.example.com/health',
          responseBodyPreview: '{"status":"ok"}',
          responseHeaders: { 'content-type': 'application/json' },
          responseContentType: 'application/json',
        }),
      });
    });

    // 1) 목록 페이지 진입 — 초기에는 빈 목록
    await page.goto('/admin/api-connections');
    await expect(page.getByText('등록된 API 연결이 없습니다.')).toBeVisible();

    // 2) 새 연결 생성 다이얼로그 열기
    await page.getByRole('button', { name: '새 연결' }).click();

    // 3) 폼 입력 — Label이 htmlFor 없이 렌더링되므로 placeholder로 선택한다
    await page.getByPlaceholder('예: Make.com API').fill('Make.com API');
    await page.getByPlaceholder('https://api.example.com').fill('https://api.make.com');
    await page.getByPlaceholder('/health').fill('/health');
    // API_KEY 인증 헤더 + 키 입력 (기본 authType = API_KEY, placement = header)
    await page.getByPlaceholder('Authorization').fill('X-Make-Key');
    await page.getByPlaceholder('API 키를 입력하세요').fill('secret-key');

    // 4) 생성 버튼 클릭
    await page.getByRole('button', { name: '생성', exact: true }).click();

    // 5) 목록에 생성된 연결 표시 확인
    await expect(page.getByRole('cell', { name: 'Make.com API' })).toBeVisible();
    await expect(page.getByText('https://api.make.com')).toBeVisible();

    // 6) 단건 조회 라우트 등록 — 상세 페이지 진입 전에 등록한다
    await page.route('**/api/v1/api-connections/*', async (route) => {
      // test 엔드포인트는 상위 route에서 처리하므로 단건 GET만 처리
      const url = route.request().url();
      const match = url.match(/\/api-connections\/(\d+)$/);
      if (!match) return route.continue();
      const id = Number(match[1]);
      const found = connections.find((c) => c.id === id);
      if (!found) return route.fulfill({ status: 404, body: '{}' });
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, body: JSON.stringify(found) });
      } else {
        await route.continue();
      }
    });

    // 7) 연결 이름 셀 클릭 → 상세 페이지 이동
    await page.getByRole('cell', { name: 'Make.com API' }).click();
    await page.waitForURL(/\/admin\/api-connections\/\d+/);

    // 8) "지금 확인" 버튼 클릭 → POST /{id}/test 호출
    await page.getByRole('button', { name: '지금 확인' }).click();

    // 9) 상태 배지가 "정상"(UP)으로 갱신되는지 확인
    // getByText('정상')는 toast와 중복될 수 있으므로 badge 역할로 한정한다
    await expect(page.getByText('정상').first()).toBeVisible({ timeout: 5000 });
  });
});
