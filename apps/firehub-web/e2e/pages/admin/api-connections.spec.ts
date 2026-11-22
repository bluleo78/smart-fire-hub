import type { Page } from '@playwright/test';

import { isExtraHeaderKey } from '../../../src/lib/api-connection-extra-headers';
import { API_BASE_URL_PLACEHOLDER } from '../../../src/lib/api-connection-form';
import type { ApiConnectionResponse } from '../../../src/types/api-connection';
import { createApiConnection, createApiConnections, createBasicApiConnection } from '../../factories/admin.factory';
import {
  setupAdminAuth,
  setupApiConnectionDetailMocks,
  setupApiConnectionListMocks,
  setupBasicApiConnectionDetailMocks,
} from '../../fixtures/admin.fixture';
import { mockApi, type MockApiCapture } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { holdApi } from '../../fixtures/hold-api';
import { expectTableErrorAlert } from '../../fixtures/table-error-alert';
import {
  expectBadgesFitDeclaredWidth,
  expectKMatchesDeclaredWidths,
  expectLongValueTruncated,
  expectTableFits,
  expectValueNotTruncated,
  TABLE_LAYOUT_WIDTHS,
} from '../../fixtures/table-layout';

/**
 * 연결 재조회(path 로 가는 GET) 응답을 기다리는 Promise(WD-777) — 재조회를 일으키는 동작 전에 만들어 두고, 동작 뒤 await 한다.
 */
function waitForRefetch(page: Page, path: string) {
  return page.waitForResponse((res) => res.request().method() === 'GET' && new URL(res.url()).pathname === path);
}

/**
 * 캡처한 PUT 중 index 번째(기본 0 이 아니라 1 — 첫 번째는 기본 정보 저장)의 authConfig 에서 추가 헤더 키만 꺼낸다(WD-788·WD-796).
 * 추가 헤더 키 판정은 화면과 같은 isExtraHeaderKey 를 써, 접두사 규칙이 바뀌어도 시험이 따로 어긋나지 않게 한다.
 */
function savedExtraHeaders(capture: { requests: { payload: unknown }[] }, index = 1) {
  const authConfig = (capture.requests[index].payload as { authConfig: Record<string, string> }).authConfig;
  return Object.fromEntries(Object.entries(authConfig).filter(([key]) => isExtraHeaderKey(key)));
}

/**
 * 인증 편집 중 기본 정보를 저장해 연결을 다시 불러오게 한다(WD-753 리뷰·WD-763) — 이름 칸에 refetched.name 을 넣고 「저장」을 누른다.
 * 저장 PUT·이후 GET 이 refetched 를 돌려준다(나중에 등록한 route 가 이긴다). 재조회 응답이 도착하고 저장 토스트가 보일 때까지 기다린 뒤,
 * PUT 캡처(이어서 하는 인증 저장 payload 검증용)를 돌려준다.
 * successToasts 를 주면 저장 토스트가 그 개수만큼 떠 있는지 본다 — 앞 저장 토스트가 남아 있는 연속 저장에서 같은 문구 토스트가 둘 이상이라
 * 단일 요소 검사(strict mode)가 깨지지 않게 한다(WD-804 리뷰). 주지 않으면 예전처럼 하나만 보이는지 본다.
 */
async function saveInfoAndRefetch(page: Page, refetched: ApiConnectionResponse, successToasts?: number) {
  const path = `/api/v1/api-connections/${refetched.id}`;
  await mockApi(page, 'GET', path, refetched);
  const capture = await mockApi(page, 'PUT', path, refetched, { capture: true });
  const refetchedResponse = waitForRefetch(page, path);
  await page.getByLabel('연결 이름').fill(refetched.name);
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await capture.waitForRequest();
  await refetchedResponse;
  const successToast = page.getByText('연결 정보가 업데이트되었습니다.');
  if (successToasts === undefined) await expect(successToast).toBeVisible();
  else await expect(successToast).toHaveCount(successToasts);
  return capture;
}

/** X-Id 를 지운 사용자에게 x-id 로 바뀌었다고 알리는 토스트 문구 전체(WD-804 반려) */
const RENAME_NOTICE_X_ID = '지운 헤더 X-Id 이(가) 다른 곳에서 x-id(으)로 바뀌었습니다. 지운 대로 두며, 저장하면 서버에서도 지워집니다.';

/**
 * 「지운 헤더 … 바뀌었습니다」 안내가 뜨지 않았음을 확인한다(WD-804 반려).
 * 토스트는 재조회 병합 뒤 조금 늦게 그려질 수 있어, 곧바로 세면 안내가 떠도 통과한다 — 잠깐 기다린 뒤 센다.
 */
async function expectNoRenameNotice(page: Page) {
  await page.waitForTimeout(500);
  // toHaveCount(0) 는 안 뜰 때까지 재시도해, 떴다가 4초 뒤 저절로 닫히는 토스트도 통과시킨다 — 한 번만 세어 그 순간 없음을 본다
  expect(await page.getByText(/지운 헤더 .* 바뀌었습니다/).count()).toBe(0);
}

/**
 * API 연결 페이지 E2E 테스트
 * - 목록 렌더링, 생성 폼, 수정, 삭제를 검증한다.
 * - 관리 화면 권한 가드(AdminOrPermissionRoute) 통과와 화면 안 버튼 노출을 위해 ADMIN 역할·관리 권한 코드로 users/me를 오버라이드한다.
 */
test.describe('API 연결 페이지', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    // 관리 화면 권한 가드(AdminOrPermissionRoute) 통과와 화면 안 버튼 노출을 위해 ADMIN 역할·관리 권한 코드로 오버라이드
    await setupAdminAuth(page);
  });

  // WD-978: 첫 조회가 실패하면 빈 목록(「등록된 API 연결이 없습니다.」·만들기 버튼)이 아니라 공용 실패 안내를 보인다.
  // 정확한 경로만 가로채므로 /selectable 등 하위 경로는 성공 그대로다. 앱이 한 번 재시도하므로 500 mock 은 계속 등록돼 있다.
  test('목록 조회가 실패하면 빈 목록 대신 실패 안내를 보인다 (WD-978)', async ({ authenticatedPage: page }) => {
    await setupApiConnectionListMocks(page);
    await mockApi(page, 'GET', '/api/v1/api-connections', { message: '서버 오류' }, { status: 500 });
    await page.goto('/admin/api-connections');

    await expectTableErrorAlert(page, '서버 오류');
    await expect(page.getByText('등록된 API 연결이 없습니다.')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '새 API 연결 만들기' })).toHaveCount(0);
  });

  test('목록 조회가 403 이면 권한 없음 안내를 흐린 톤으로 보인다 (WD-978 리뷰 후속)', async ({ authenticatedPage: page }) => {
    // 권한 문제는 다시 해도 같으므로 일시 장애(빨간 실패 안내)처럼 보이지 않게 권한 안내로 구분한다
    await setupApiConnectionListMocks(page);
    await mockApi(page, 'GET', '/api/v1/api-connections', { message: '권한이 없습니다' }, { status: 403 });
    await page.goto('/admin/api-connections');

    await expectTableErrorAlert(page, 'API 연결 목록을 볼 권한이 없습니다. 관리자에게 권한을 요청하세요.');
    await expect(page.getByRole('alert').getByText('API 연결 목록을 볼 권한이 없습니다. 관리자에게 권한을 요청하세요.')).toHaveClass(/text-muted-foreground/);
    await expect(page.getByText('등록된 API 연결이 없습니다.')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '새 API 연결 만들기' })).toHaveCount(0);
  });

  test('API 연결 목록이 올바르게 렌더링된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await setupApiConnectionListMocks(page);
    await page.goto('/admin/api-connections');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: 'API 연결 관리' })).toBeVisible();

    // 테이블 헤더 확인
    await expect(page.getByRole('columnheader', { name: '이름' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '인증 유형' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '설명' })).toBeVisible();

    // 팩토리로 생성된 연결 목록 확인
    await expect(page.getByRole('cell', { name: '공공 데이터 API', exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: '내부 서비스 API', exact: true })).toBeVisible();

    // 행 개수 확인: 헤더 1행 + 데이터 2행 = 3행
    await expect(page.getByRole('row')).toHaveCount(3);

    // 인증 유형 배지 확인 — UI에서 API_KEY는 'API Key', BEARER는 'Bearer'로 렌더링된다
    const rows = page.getByRole('row');
    await expect(rows.filter({ hasText: '공공 데이터 API' }).getByText('API Key')).toBeVisible();
    await expect(rows.filter({ hasText: '내부 서비스 API' }).getByText('Bearer')).toBeVisible();
  });

  // 회귀 가드(WD-356): 375 폭에서 「전체 갱신」「새 연결」 버튼이 제목을 밀어 「API 연결 관 / 리」처럼 음절 중간에서 끊겼다.
  // 이제 제목은 한 줄(36px)로 남고, 자리가 모자라면 버튼 묶음이 제목 아래 줄로 내려간다.
  test('모바일 폭(375)에서 제목이 쪼개지지 않고 버튼이 다음 줄로 내려간다 (WD-356)', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 375, height: 700 });
    await setupApiConnectionListMocks(page);
    await page.goto('/admin/api-connections');

    const heading = page.getByRole('heading', { name: 'API 연결 관리' });
    await expect(heading).toBeVisible();
    const h = (await heading.boundingBox())!;
    // line-height 36px — 두 줄이면 72px
    expect(h.height).toBeLessThanOrEqual(40);

    const newButton = page.getByRole('button', { name: '새 연결' });
    await expect(newButton).toBeVisible();
    const b = (await newButton.boundingBox())!;
    // 버튼이 화면 안에 있다(가로로 넘치지 않음)
    expect(b.x + b.width).toBeLessThanOrEqual(375);
  });

  test('빈 목록일 때 빈 상태 메시지를 표시한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/api-connections', []);
    await page.goto('/admin/api-connections');

    await expect(page.getByText('등록된 API 연결이 없습니다.')).toBeVisible();
  });

  test('빈 목록에서 만들기 버튼을 누르면 새 API 연결 창이 열린다 (WD-621)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/api-connections', []);
    await page.goto('/admin/api-connections');

    const emptyRow = page.getByRole('row').filter({ hasText: '등록된 API 연결이 없습니다.' });
    await emptyRow.getByRole('button', { name: '새 API 연결 만들기' }).click();
    // 머리말 「새 연결」과 같은 생성 창이 열린다
    await expect(page.getByRole('dialog').getByRole('heading', { name: '새 API 연결' })).toBeVisible();
  });

  test('쓰기 권한(apiconnection:write)이 없으면 빈 목록에 만들기 버튼을 두지 않는다 (WD-621)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/auth/me/permissions', ['apiconnection:read']);
    await mockApi(page, 'GET', '/api/v1/api-connections', []);
    await page.goto('/admin/api-connections');

    await expect(page.getByText('등록된 API 연결이 없습니다.')).toBeVisible();
    await expect(page.getByRole('button', { name: '새 API 연결 만들기' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '새 연결' })).toHaveCount(0);
  });

  test('새 연결 버튼 클릭 시 생성 다이얼로그가 열린다', async ({ authenticatedPage: page }) => {
    await setupApiConnectionListMocks(page);

    // 연결 생성 POST API 캡처 설정 — goto 이전에 등록해야 한다
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/api-connections',
      { id: 10, name: '새 API 연결', authType: 'API_KEY' },
      { capture: true },
    );

    await page.goto('/admin/api-connections');

    // "새 연결" 버튼 클릭
    await page.getByRole('button', { name: '새 연결' }).click();

    // 생성 다이얼로그 열림 확인
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByText('새 API 연결')).toBeVisible();

    // 이름 필드 확인 및 입력 — placeholder로 접근
    const nameInput = page.getByPlaceholder('예: Make.com API');
    await expect(nameInput).toBeVisible();
    await nameInput.fill('새 API 연결');

    // Base URL 입력 — Phase 9에서 추가된 필수 필드
    await page.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true }).fill('https://api.example.com');

    // API_KEY 인증 유형(기본값): 헤더 이름과 키 값도 필수 입력
    // placeholder='Authorization' → 헤더 이름 필드
    await page.getByPlaceholder('Authorization').fill('X-API-Key');
    // placeholder='API 키를 입력하세요' → 키 값 필드
    await page.getByPlaceholder('API 키를 입력하세요').fill('test-api-key-12345');

    // 생성 버튼 클릭 → POST /api/v1/api-connections 호출
    await page.getByRole('button', { name: '만들기' }).click();

    // API payload 검증 — 입력한 이름과 authType이 전달되어야 한다
    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({ name: '새 API 연결' });
    // authType은 기본값(API_KEY)이 전달된다
    expect((req.payload as Record<string, unknown>).authType).toBeTruthy();
  });

  // WD-887: 플레이스홀더가 값처럼 보여 옮겨 적은 예시가 막히던 문제 — 이제 "예:" 예시임을 드러낸다
  test('WD-887: Base URL 플레이스홀더는 바꿔 넣을 예시(예:)이고 그대로 넣으면 클라이언트에서 막힌다', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionListMocks(page);
    let postCount = 0;
    await page.route('**/api/v1/api-connections', (route) => {
      if (route.request().method() === 'POST') {
        postCount += 1;
        return route.fulfill({ status: 201, json: { id: 10, name: 'x', authType: 'API_KEY' } });
      }
      return route.fallback();
    });
    await page.goto('/admin/api-connections');
    await page.getByRole('button', { name: '새 연결' }).click();

    const baseUrlInput = page.getByLabel('Base URL');
    await expect(baseUrlInput).toHaveAttribute('placeholder', API_BASE_URL_PLACEHOLDER);

    await page.getByPlaceholder('예: Make.com API').fill('예시 옮겨 적기');
    await page.getByPlaceholder('Authorization').fill('X-API-Key');
    await page.getByPlaceholder('API 키를 입력하세요').fill('test-api-key-12345');
    // 플레이스홀더 문구를 그대로 옮겨 적는다
    await baseUrlInput.fill(API_BASE_URL_PLACEHOLDER);
    await page.getByRole('button', { name: '만들기' }).click();

    await expect(page.getByRole('alert').filter({ hasText: 'Base URL' })).toBeVisible();
    expect(postCount).toBe(0);
  });

  test('생성 다이얼로그에서 API Key 인증 유형 선택 시 관련 필드가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionListMocks(page);
    await page.goto('/admin/api-connections');

    await page.getByRole('button', { name: '새 연결' }).click();

    // 기본값이 API_KEY이므로 헤더 이름 필드가 표시되는지 확인
    // (Label: "헤더 이름" 또는 "파라미터 이름" — 기본 placement는 'header')
    await expect(page.getByText('헤더 이름')).toBeVisible();
    await expect(page.getByText('키 값')).toBeVisible();
  });

  test('API 연결 행 클릭 시 상세 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupApiConnectionListMocks(page);
    await setupApiConnectionDetailMocks(page, 1);

    await page.goto('/admin/api-connections');

    // 첫 번째 연결 이름 셀 클릭
    await page.getByRole('cell', { name: '공공 데이터 API', exact: true }).click();

    // 상세 페이지로 이동 확인
    await expect(page).toHaveURL(/\/admin\/api-connections\/1/);
  });

  test('API 연결 상세 페이지에서 기본 정보가 표시된다', async ({ authenticatedPage: page }) => {
    await setupApiConnectionDetailMocks(page, 1);
    await page.goto('/admin/api-connections/1');

    // 상세 페이지 제목 확인
    await expect(page.getByRole('heading', { name: 'API 연결 상세' })).toBeVisible();

    // 기본 정보 카드 확인
    await expect(page.getByText('기본 정보')).toBeVisible();

    // 저장 버튼 확인
    await expect(page.getByRole('button', { name: '저장' })).toBeVisible();

    // setupApiConnectionDetailMocks(1)은 createApiConnections()[0]을 반환 — name='공공 데이터 API'
    // <Label>연결 이름</Label>에 htmlFor가 없으므로, 기본 정보 카드 첫 번째 textbox로 검증한다
    // 기본 정보 카드 내 첫 번째 Input이 연결 이름 필드이다
    const nameInput = page.getByRole('textbox').first();
    await expect(nameInput).toHaveValue('공공 데이터 API');
  });

  test('API 연결 상세 페이지에서 인증 정보 변경 버튼이 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionDetailMocks(page, 1);
    await page.goto('/admin/api-connections/1');

    // 인증 설정 카드 확인
    await expect(page.getByText('인증 설정')).toBeVisible();

    // 인증 정보 변경 버튼 확인
    await expect(page.getByRole('button', { name: '인증 정보 변경' })).toBeVisible();
  });

  test('"지금 확인" 클릭 시 응답 본문/상태코드/요청 URL이 카드로 노출된다 (#76)', async ({
    authenticatedPage: page,
  }) => {
    // 상세 모킹 + test 응답을 4xx/응답 본문 포함으로 오버라이드
    const detail = (await import('../../factories/admin.factory')).createApiConnections()[0];
    await mockApi(page, 'GET', '/api/v1/api-connections/1', { ...detail, id: 1 });
    await mockApi(page, 'POST', '/api/v1/api-connections/1/test', {
      ok: false,
      status: 401,
      latencyMs: 87,
      errorMessage: 'HTTP 401',
      requestUrl: 'https://api.example.com/health?token=secret',
      responseBodyPreview: '{"error":"unauthorized","detail":"missing api key"}',
      responseHeaders: { 'content-type': 'application/json', authorization: '****' },
      responseContentType: 'application/json',
    });

    await page.goto('/admin/api-connections/1');
    // 결과 카드는 클릭 전엔 보이지 않아야 한다
    await expect(page.getByTestId('test-result-card')).toHaveCount(0);

    await page.getByRole('button', { name: '지금 확인' }).click();

    // 카드 등장 + 핵심 데이터 노출 검증 (단순 visible이 아닌 텍스트/속성 검증)
    const card = page.getByTestId('test-result-card');
    await expect(card).toBeVisible();
    await expect(card.getByTestId('test-result-status')).toHaveText('실패');
    await expect(card).toContainText('HTTP 401');
    await expect(card).toContainText('87ms');
    // 요청 URL 노출
    await expect(card).toContainText('https://api.example.com/health?token=secret');
    // 응답 본문(JSON pretty-print) 노출
    const body = card.getByTestId('test-result-body');
    await expect(body).toContainText('"error": "unauthorized"');
    await expect(body).toContainText('"detail": "missing api key"');
    // 헤더 details 안의 마스킹 검증
    await card.getByTestId('test-result-headers').click();
    await expect(card.getByTestId('test-result-headers')).toContainText('authorization');
    await expect(card.getByTestId('test-result-headers')).toContainText('****');
  });

  test('목록에서 삭제 버튼 클릭 시 확인 다이얼로그가 열린다', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionListMocks(page);

    // 첫 번째 연결(id=1) 삭제 DELETE API 캡처 설정
    const capture = await mockApi(page, 'DELETE', '/api/v1/api-connections/1', {}, { capture: true });

    await page.goto('/admin/api-connections');

    // 테이블 행의 삭제 트리거 버튼 클릭 — Outline + sm size 버튼
    // 목록 행마다 DeleteConfirmDialog trigger 버튼이 있으므로 첫 번째 행 버튼 클릭
    const rows = page.getByRole('row').filter({ has: page.getByRole('button') });
    const firstDataRow = rows.first();
    await firstDataRow.getByRole('button').click();

    // 삭제 확인 다이얼로그 열림 확인
    await expect(page.getByRole('alertdialog')).toBeVisible();

    // 확인 버튼 클릭 → DELETE API 호출 검증
    const confirmButton = page.getByRole('alertdialog').getByRole('button', { name: /삭제|확인/ });
    await confirmButton.click();

    // DELETE API가 실제로 호출되었는지 확인
    const req = await capture.waitForRequest();
    expect(req).toBeTruthy();
  });

  test('상세 페이지에서 인증 정보 변경 — API_KEY PUT payload 검증', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionDetailMocks(page, 1);

    // PUT /api/v1/api-connections/1 캡처
    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/api-connections/1',
      { id: 1, name: '공공 데이터 API', authType: 'API_KEY' },
      { capture: true },
    );

    await page.goto('/admin/api-connections/1');
    await expect(page.getByRole('heading', { name: 'API 연결 상세' })).toBeVisible();

    // "인증 정보 변경" 버튼 클릭 → 편집 모드 진입
    await page.getByRole('button', { name: '인증 정보 변경' }).click();

    // 헤더 이름 입력 (placeholder="Authorization")
    await page.getByPlaceholder('Authorization').fill('X-Custom-Key');
    // 새 API 키 입력 (type="password", placeholder="새 API 키를 입력하세요")
    await page.getByPlaceholder('새 API 키를 입력하세요').fill('super-secret-key');

    // 인증 저장
    await page.getByRole('button', { name: '인증 저장' }).click();

    // PUT payload 검증
    const req = await updateCapture.waitForRequest();
    expect(req.payload).toMatchObject({
      authType: 'API_KEY',
      authConfig: expect.objectContaining({
        headerName: 'X-Custom-Key',
        apiKey: 'super-secret-key',
      }),
    });
  });

  test('상세 페이지에서 인증 유형을 BEARER로 변경 — token PUT payload 검증', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionDetailMocks(page, 1);

    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/api-connections/1',
      { id: 1, name: '공공 데이터 API', authType: 'BEARER' },
      { capture: true },
    );

    await page.goto('/admin/api-connections/1');
    await expect(page.getByRole('heading', { name: 'API 연결 상세' })).toBeVisible();

    // 인증 정보 변경 모드 진입
    await page.getByRole('button', { name: '인증 정보 변경' }).click();

    // 인증 유형을 BEARER로 변경
    await page.getByRole('combobox').first().click();
    await page.getByRole('option', { name: 'Bearer Token' }).click();

    // 토큰 입력 (placeholder="새 토큰을 입력하세요")
    await page.getByPlaceholder('새 토큰을 입력하세요').fill('my-bearer-token-xyz');

    await page.getByRole('button', { name: '인증 저장' }).click();

    const req = await updateCapture.waitForRequest();
    expect(req.payload).toMatchObject({
      authType: 'BEARER',
      authConfig: expect.objectContaining({ token: 'my-bearer-token-xyz' }),
    });
  });

  test('상세 페이지에서 연결 삭제 — DELETE 호출 후 목록으로 이동', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionDetailMocks(page, 1);
    await setupApiConnectionListMocks(page);

    let deleteCalled = false;
    await page.route(
      (url) => url.pathname === '/api/v1/api-connections/1',
      (route) => {
        if (route.request().method() === 'DELETE') {
          deleteCalled = true;
          return route.fulfill({ status: 204, body: '' });
        }
        return route.fallback();
      },
    );

    await page.goto('/admin/api-connections/1');
    await expect(page.getByRole('heading', { name: 'API 연결 상세' })).toBeVisible();

    // "이 연결 삭제" 버튼 클릭
    await page.getByRole('button', { name: '이 연결 삭제' }).click();

    // 삭제 확인 다이얼로그
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await page.getByRole('alertdialog').getByRole('button', { name: /삭제|확인/ }).click();

    await expect.poll(() => deleteCalled).toBe(true);
  });

  test('인증 정보 변경 취소 — 편집 모드가 닫힌다', async ({ authenticatedPage: page }) => {
    await setupApiConnectionDetailMocks(page, 1);
    await page.goto('/admin/api-connections/1');
    await expect(page.getByRole('button', { name: '인증 정보 변경' })).toBeVisible();

    // 편집 모드 진입
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    await expect(page.getByRole('button', { name: '인증 저장' })).toBeVisible();

    // 취소 클릭 → 읽기 전용 뷰로 복귀
    await page.getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('button', { name: '인증 정보 변경' })).toBeVisible();
    await expect(page.getByRole('button', { name: '인증 저장' })).not.toBeVisible();
  });

  test('기본 정보 저장 — 이름·설명 PUT payload 검증', async ({ authenticatedPage: page }) => {
    await setupApiConnectionDetailMocks(page, 1);

    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/api-connections/1',
      { id: 1, name: '수정된 API 이름', authType: 'API_KEY' },
      { capture: true },
    );

    await page.goto('/admin/api-connections/1');
    await expect(page.getByRole('heading', { name: 'API 연결 상세' })).toBeVisible();

    // 연결 이름 수정 — 첫 번째 textbox가 이름 필드
    const nameInput = page.getByRole('textbox').first();
    await nameInput.clear();
    await nameInput.fill('수정된 API 이름');

    // 설명 수정 — placeholder="설명 (선택)" 필드
    const descInput = page.getByLabel('설명 (선택)', { exact: true });
    await descInput.clear();
    await descInput.fill('새 설명 텍스트');

    // 저장 버튼 클릭 → PUT /api/v1/api-connections/1 호출
    await page.getByRole('button', { name: '저장' }).click();

    const req = await updateCapture.waitForRequest();
    expect(req.payload).toMatchObject({
      name: '수정된 API 이름',
      description: '새 설명 텍스트',
    });
  });

  /**
   * 이슈 #551 회귀 방지 — API 연결 상세 페이지 미저장 변경 이탈 가드.
   * useUnsavedChangesGuard(#86 도입) 가 이 페이지에도 적용되어야 사이드바 이동 시
   * 경고 없이 입력값이 사라지는 것을 방지한다.
   */
  test.describe('이슈 #551 — 미저장 변경 가드', () => {
    test('설명 필드 dirty 상태에서 사이드바 메뉴 클릭 시 이탈 다이얼로그가 표시된다', async ({
      authenticatedPage: page,
    }) => {
      await setupApiConnectionDetailMocks(page, 1);
      await page.goto('/admin/api-connections/1');
      await expect(page.getByRole('heading', { name: 'API 연결 상세' })).toBeVisible();

      // dirty 상태 만들기 — 설명 필드만 수정 (저장 버튼은 누르지 않음)
      const descInput = page.getByLabel('설명 (선택)', { exact: true });
      await descInput.fill('PE-TEST-unsaved-change-probe');

      // 사이드바 "사용자 관리" 링크 클릭
      await page.getByRole('navigation').getByRole('link', { name: '사용자 관리' }).click();

      await expect(page.getByRole('alertdialog')).toBeVisible();
      await expect(
        page.getByText('저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?'),
      ).toBeVisible();
      // URL은 그대로 상세 페이지 유지 (즉시 이동되지 않아야 함)
      expect(new URL(page.url()).pathname).toBe('/admin/api-connections/1');
    });

    test('이탈 다이얼로그에서 취소 클릭 시 페이지에 머무르고 입력값이 보존된다', async ({
      authenticatedPage: page,
    }) => {
      await setupApiConnectionDetailMocks(page, 1);
      await page.goto('/admin/api-connections/1');

      const descInput = page.getByLabel('설명 (선택)', { exact: true });
      await descInput.fill('PE-TEST-unsaved-change-probe');

      await page.getByRole('navigation').getByRole('link', { name: '사용자 관리' }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();

      await page.getByRole('button', { name: '취소' }).click();

      // 다이얼로그 닫힘 + 페이지 잔류 + 입력값 보존
      await expect(page.getByRole('alertdialog')).not.toBeVisible();
      expect(new URL(page.url()).pathname).toBe('/admin/api-connections/1');
      await expect(descInput).toHaveValue('PE-TEST-unsaved-change-probe');
    });

    test('이탈 다이얼로그에서 이탈 클릭 시 목적지 페이지로 이동한다', async ({
      authenticatedPage: page,
    }) => {
      await setupApiConnectionDetailMocks(page, 1);
      await page.goto('/admin/api-connections/1');

      const descInput = page.getByLabel('설명 (선택)', { exact: true });
      await descInput.fill('PE-TEST-unsaved-change-probe');

      await page.getByRole('navigation').getByRole('link', { name: '사용자 관리' }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();

      await page.getByRole('button', { name: '이탈' }).click();

      await expect(page).toHaveURL(/\/admin\/users$/);
    });

    test('저장 성공 후에는 사이드바 이동 시 이탈 다이얼로그가 표시되지 않는다', async ({
      authenticatedPage: page,
    }) => {
      await setupApiConnectionDetailMocks(page, 1);
      await mockApi(
        page,
        'PUT',
        '/api/v1/api-connections/1',
        { id: 1, name: '공공 데이터 API', authType: 'API_KEY' },
      );

      await page.goto('/admin/api-connections/1');

      const descInput = page.getByLabel('설명 (선택)', { exact: true });
      await descInput.fill('저장된 설명');
      await page.getByRole('button', { name: '저장' }).click();

      // 저장 성공 토스트 확인 후 dirty 해제 여부 검증
      await expect(page.getByText('연결 정보가 업데이트되었습니다.')).toBeVisible();

      await page.getByRole('navigation').getByRole('link', { name: '사용자 관리' }).click();

      // dirty 가 해제되었으므로 가드 없이 바로 이동해야 한다
      await expect(page).toHaveURL(/\/admin\/users$/);
    });

    /**
     * 이슈 #634 회귀 방지 — 헤더의 "목록으로 돌아가기" 버튼은 <a href>가 아니라
     * navigate()를 직접 호출하는 <button>이라 클릭 캡처 기반 가드(<a> 탐지)를 우회했었다.
     * requestNavigate로 교체된 이후에는 dirty 상태에서 이 버튼을 눌러도 가드가 동작해야 한다.
     */
    test('설명 필드 dirty 상태에서 "목록으로 돌아가기" 버튼 클릭 시 이탈 다이얼로그가 표시된다 (#634)', async ({
      authenticatedPage: page,
    }) => {
      await setupApiConnectionDetailMocks(page, 1);
      await page.goto('/admin/api-connections/1');
      await expect(page.getByRole('heading', { name: 'API 연결 상세' })).toBeVisible();

      const descInput = page.getByLabel('설명 (선택)', { exact: true });
      await descInput.fill('PE-TEST-back-button-probe');

      await page.getByRole('button', { name: '목록으로 돌아가기' }).click();

      await expect(page.getByRole('alertdialog')).toBeVisible();
      await expect(
        page.getByText('저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?'),
      ).toBeVisible();
      // 즉시 이동하지 않고 상세 페이지에 머물러야 한다
      expect(new URL(page.url()).pathname).toBe('/admin/api-connections/1');

      // "이탈" 클릭 시 실제로 목록 페이지로 이동
      await page.getByRole('button', { name: '이탈' }).click();
      await expect(page).toHaveURL(/\/admin\/api-connections$/);
    });

    test('설명 필드 dirty 상태에서 "목록으로 돌아가기" 이탈 다이얼로그의 취소 클릭 시 입력값이 보존된다 (#634)', async ({
      authenticatedPage: page,
    }) => {
      await setupApiConnectionDetailMocks(page, 1);
      await page.goto('/admin/api-connections/1');

      const descInput = page.getByLabel('설명 (선택)', { exact: true });
      await descInput.fill('PE-TEST-back-button-probe');

      await page.getByRole('button', { name: '목록으로 돌아가기' }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();

      await page.getByRole('button', { name: '취소' }).click();

      await expect(page.getByRole('alertdialog')).not.toBeVisible();
      expect(new URL(page.url()).pathname).toBe('/admin/api-connections/1');
      await expect(descInput).toHaveValue('PE-TEST-back-button-probe');
    });
  });

  test('헬스체크 경로 비우고 저장 → PUT payload 에 빈 문자열 전달 (#115)', async ({
    authenticatedPage: page,
  }) => {
    // backend 가 빈 문자열을 명시적 clear sentinel 로 인식하므로 frontend 도 빈 문자열을 그대로 보내야 한다.
    // 기존 버그: `trim() || undefined` 가 빈 값을 undefined 로 만들어 필드가 omit 되고 backend 는 미변경으로 해석.
    await setupApiConnectionDetailMocks(page, 1);

    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/api-connections/1',
      { id: 1, name: '기상청 ASOS', authType: 'API_KEY' },
      { capture: true },
    );

    await page.goto('/admin/api-connections/1');
    await expect(page.getByRole('heading', { name: 'API 연결 상세' })).toBeVisible();

    // 헬스체크 경로 입력란을 비운다 (placeholder 또는 label 기준 셀렉터 — 검증 가능한 안정 셀렉터 사용)
    const hcInput = page.getByLabel('헬스체크 경로 (선택)', { exact: true });
    await hcInput.clear();

    await page.getByRole('button', { name: '저장' }).click();

    const req = await updateCapture.waitForRequest();
    // 빈 문자열로 명시적 전송되어야 backend 가 SQL NULL 로 clear 한다
    expect(req.payload).toMatchObject({ healthCheckPath: '' });
  });

  test('인증 유형 API_KEY + query placement → paramName PUT payload 검증', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionDetailMocks(page, 1);

    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/api-connections/1',
      { id: 1, name: '공공 데이터 API', authType: 'API_KEY' },
      { capture: true },
    );

    await page.goto('/admin/api-connections/1');
    await expect(page.getByRole('heading', { name: 'API 연결 상세' })).toBeVisible();

    // 인증 정보 변경 모드 진입
    await page.getByRole('button', { name: '인증 정보 변경' }).click();

    // 위치(placement)를 "Query Parameter"로 변경 — 두 번째 combobox가 placement select
    const combos = page.getByRole('combobox');
    await combos.nth(1).click();
    await page.getByRole('option', { name: 'Query Parameter' }).click();

    // 파라미터 이름 입력 (placeholder="api_key")
    await page.getByPlaceholder('api_key').fill('my_api_key_param');
    // 키 값 입력 (type="password")
    await page.getByPlaceholder('새 API 키를 입력하세요').fill('secret-value-789');

    await page.getByRole('button', { name: '인증 저장' }).click();

    const req = await updateCapture.waitForRequest();
    expect(req.payload).toMatchObject({
      authType: 'API_KEY',
      authConfig: expect.objectContaining({
        placement: 'query',
        paramName: 'my_api_key_param',
        apiKey: 'secret-value-789',
      }),
    });
  });

  test('인증 정보 변경 — 헤더 이름만 고치고 키 칸을 비우면 저장된 가린 값을 그대로 보내 키를 유지한다 (WD-480)', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionDetailMocks(page, 1);
    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/api-connections/1',
      { id: 1, name: '공공 데이터 API', authType: 'API_KEY' },
      { capture: true },
    );

    await page.goto('/admin/api-connections/1');
    await page.getByRole('button', { name: '인증 정보 변경' }).click();

    // 저장된 키가 있으면 비워 둬도 된다는 안내가 보인다
    await expect(page.getByText('비워 두면 저장된 키를 그대로 사용합니다.')).toBeVisible();

    // 헤더 이름만 바꾸고 키 값 칸은 비워 둔다
    await page.getByPlaceholder('Authorization').fill('X-Fixed-Key');
    await page.getByRole('button', { name: '인증 저장' }).click();

    // 입력 오류 없이 PUT 이 나가고, apiKey 는 조회로 받은 가린 값 그대로다(서버가 저장 원문으로 채움)
    const req = await updateCapture.waitForRequest();
    expect(req.payload).toMatchObject({
      authType: 'API_KEY',
      authConfig: { placement: 'header', headerName: 'X-Fixed-Key', apiKey: '***masked***' },
    });
    // 키 칸 인라인 오류도 없어야 한다(WD-561) — 저장 성공으로 편집 모드가 닫히므로 문구 부재로 본다
    await expect(page.getByText('API 키를 입력하세요.')).toHaveCount(0);
  });

  test('인증 정보 변경 — 위치를 Query 로 바꾸고 파라미터 이름만 고쳐도 키 칸을 비워 저장된다 (WD-480)', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionDetailMocks(page, 1);
    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/api-connections/1',
      { id: 1, name: '공공 데이터 API', authType: 'API_KEY' },
      { capture: true },
    );

    await page.goto('/admin/api-connections/1');
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    await page.getByRole('combobox').nth(1).click();
    await page.getByRole('option', { name: 'Query Parameter' }).click();
    await page.getByPlaceholder('api_key').fill('token_param');
    await page.getByRole('button', { name: '인증 저장' }).click();

    const req = await updateCapture.waitForRequest();
    expect(req.payload).toMatchObject({
      authType: 'API_KEY',
      authConfig: { placement: 'query', paramName: 'token_param', apiKey: '***masked***' },
    });
  });

  test('인증 정보 변경 — 키를 새로 입력하면 가린 값이 아니라 입력한 값이 전송된다 (WD-480)', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionDetailMocks(page, 1);
    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/api-connections/1',
      { id: 1, name: '공공 데이터 API', authType: 'API_KEY' },
      { capture: true },
    );

    await page.goto('/admin/api-connections/1');
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    await page.getByPlaceholder('Authorization').fill('X-Fixed-Key');
    await page.getByPlaceholder('새 API 키를 입력하세요').fill('brand-new-key');
    await page.getByRole('button', { name: '인증 저장' }).click();

    const req = await updateCapture.waitForRequest();
    expect(req.payload).toMatchObject({ authConfig: { apiKey: 'brand-new-key' } });
  });

  test('인증 정보 변경 — 인증 방식이 BEARER 였던 연결을 API_KEY 로 바꿀 때는 키 칸이 비면 막힌다 (WD-480)', async ({
    authenticatedPage: page,
  }) => {
    // 저장된 API 키가 없으므로 「유지」할 값이 없다 — 안내 문구도 없어야 한다
    await mockApi(
      page,
      'GET',
      '/api/v1/api-connections/1',
      createApiConnection({ id: 1, authType: 'BEARER', maskedAuthConfig: { token: '***masked***' } }),
    );
    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/api-connections/1',
      { id: 1, authType: 'API_KEY' },
      { capture: true },
    );

    await page.goto('/admin/api-connections/1');
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    // 편집 모드의 기본 인증 방식을 API Key 로 바꾼다
    await page.getByRole('combobox').first().click();
    await page.getByRole('option', { name: 'API Key' }).click();
    await expect(page.getByText('비워 두면 저장된 키를 그대로 사용합니다.')).toHaveCount(0);
    await page.getByPlaceholder('Authorization').fill('X-Key');
    await page.getByRole('button', { name: '인증 저장' }).click();

    // 키 칸에 인라인 오류가 뜨고 그 칸으로 포커스가 간다(WD-561)
    const keyInput = page.getByPlaceholder('새 API 키를 입력하세요');
    await expect(keyInput).toHaveAccessibleDescription('API 키를 입력하세요.');
    await expect(keyInput).toBeFocused();
    expect(updateCapture.requests?.length ?? 0).toBe(0);
  });

  test('생성 폼 — Bearer Token 타입 선택 시 token 필드로 생성', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionListMocks(page);

    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/api-connections',
      { id: 20, name: 'Bearer 테스트', authType: 'BEARER' },
      { capture: true },
    );

    await page.goto('/admin/api-connections');
    await page.getByRole('button', { name: '새 연결' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // 이름 입력
    await page.getByPlaceholder('예: Make.com API').fill('Bearer 테스트');

    // Base URL 입력 — Phase 9에서 추가된 필수 필드
    await page.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true }).fill('https://api.example.com');

    // 인증 유형 → Bearer Token으로 변경
    await page.getByRole('combobox').first().click();
    await page.getByRole('option', { name: 'Bearer Token' }).click();

    // Bearer Token 입력 필드가 표시되어야 한다
    await expect(page.getByPlaceholder('토큰을 입력하세요')).toBeVisible();
    await page.getByPlaceholder('토큰을 입력하세요').fill('my-bearer-token-abc');

    await page.getByRole('button', { name: '만들기' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({
      name: 'Bearer 테스트',
      authType: 'BEARER',
      authConfig: expect.objectContaining({ token: 'my-bearer-token-abc' }),
    });
  });

  test('생성 폼 — API Key + Query 위치 선택 시 paramName 필드로 생성', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionListMocks(page);

    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/api-connections',
      { id: 21, name: 'Query API', authType: 'API_KEY' },
      { capture: true },
    );

    await page.goto('/admin/api-connections');
    await page.getByRole('button', { name: '새 연결' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    await page.getByPlaceholder('예: Make.com API').fill('Query API');

    // Base URL 입력 — Phase 9에서 추가된 필수 필드
    await page.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true }).fill('https://api.example.com');

    // API_KEY 유형(기본값), 위치를 Query Parameter로 변경
    const combos = page.getByRole('combobox');
    await combos.nth(1).click();
    await page.getByRole('option', { name: 'Query Parameter' }).click();

    // 파라미터 이름 필드 (placeholder='api_key')
    await page.getByPlaceholder('api_key').fill('my_param');
    await page.getByPlaceholder('API 키를 입력하세요').fill('secret-123');

    await page.getByRole('button', { name: '만들기' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({
      name: 'Query API',
      authType: 'API_KEY',
      authConfig: expect.objectContaining({
        placement: 'query',
        paramName: 'my_param',
        apiKey: 'secret-123',
      }),
    });
  });

  test('생성 폼 — 이름 미입력 시 이름 칸에 인라인 오류가 표시되고 API가 호출되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionListMocks(page);
    await page.goto('/admin/api-connections');
    await page.getByRole('button', { name: '새 연결' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // 이름 없이 바로 생성 클릭
    await page.getByRole('button', { name: '만들기' }).click();

    // 유효성 검사 인라인 오류 확인(WD-561 — 토스트 대신 칸 아래 role=alert)
    await expect(page.getByRole('alert').filter({ hasText: '연결 이름을 입력하세요.' })).toBeVisible();
    // 다이얼로그는 여전히 열려있어야 한다
    await expect(page.getByRole('dialog')).toBeVisible();
  });

  test('생성 폼 — API_KEY header 타입에서 헤더 이름/키 미입력 시 각 칸에 인라인 오류', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionListMocks(page);
    await page.goto('/admin/api-connections');
    await page.getByRole('button', { name: '새 연결' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // 이름과 baseUrl 입력, 헤더이름·키값 비워두고 생성 시도
    await page.getByPlaceholder('예: Make.com API').fill('헤더 검증 테스트');
    // Base URL 입력 — 없으면 baseUrl 검증이 먼저 실패하여 헤더 검증까지 도달하지 못한다
    await page.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true }).fill('https://api.example.com');
    await page.getByRole('button', { name: '만들기' }).click();

    // 두 칸 오류가 따로 뜬다(WD-561)
    await expect(page.getByPlaceholder('Authorization')).toHaveAccessibleDescription('헤더 이름을 입력하세요.');
    await expect(page.getByPlaceholder('API 키를 입력하세요')).toHaveAccessibleDescription('API 키를 입력하세요.');
    await expect(page.getByRole('dialog')).toBeVisible();
  });

  test('생성 시 baseUrl을 포함하여 API에 전달된다', async ({ authenticatedPage: page }) => {
    await setupApiConnectionListMocks(page);

    let capturedBody: unknown;
    await page.route(
      (url) => url.pathname === '/api/v1/api-connections',
      (route) => {
        if (route.request().method() === 'POST') {
          capturedBody = route.request().postDataJSON();
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ ...capturedBody as object, id: 99 }),
          });
        }
        return route.continue();
      },
    );

    await page.goto('/admin/api-connections');
    await page.getByRole('button', { name: '새 연결' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    await page.getByPlaceholder('예: Make.com API').fill('Test Connection');
    await page.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true }).fill('https://api.example.com');
    await page.getByPlaceholder('/health').fill('/health');
    await page.getByPlaceholder('Authorization').fill('X-API-Key');
    await page.getByPlaceholder('API 키를 입력하세요').fill('test-key-123');

    await page.getByRole('button', { name: '만들기' }).click();

    // 생성 요청이 도착할 때까지 기다린다(고정 대기 대신 — WD-257)
    await expect
      .poll(() => capturedBody)
      .toMatchObject({
        name: 'Test Connection',
        baseUrl: 'https://api.example.com',
        healthCheckPath: '/health',
      });
  });

  test('리스트에 Base URL 컬럼과 상태 배지가 표시된다', async ({ authenticatedPage: page }) => {
    // DOWN 상태 연결 포함해 모킹
    await mockApi(page, 'GET', '/api/v1/api-connections', [
      {
        id: 1,
        name: '정상 연결',
        authType: 'API_KEY',
        baseUrl: 'https://api.example.com',
        healthCheckPath: '/health',
        lastStatus: 'UP',
        lastCheckedAt: '2026-04-15T10:00:00Z',
        lastLatencyMs: 100,
        lastErrorMessage: null,
        maskedAuthConfig: {},
        description: null,
        createdBy: 1,
        createdAt: '2026-04-15T09:00:00Z',
        updatedAt: '2026-04-15T09:00:00Z',
      },
      {
        id: 2,
        name: '이상 연결',
        authType: 'BEARER',
        baseUrl: 'https://broken.example.com',
        healthCheckPath: '/health',
        lastStatus: 'DOWN',
        lastCheckedAt: '2026-04-15T10:00:00Z',
        lastLatencyMs: null,
        lastErrorMessage: 'Connection refused',
        maskedAuthConfig: {},
        description: null,
        createdBy: 1,
        createdAt: '2026-04-15T09:00:00Z',
        updatedAt: '2026-04-15T09:00:00Z',
      },
    ]);

    await page.goto('/admin/api-connections');

    // Base URL 컬럼 데이터 표시 확인
    await expect(page.getByText('https://api.example.com')).toBeVisible();
    // DOWN 상태 배지 확인 — "이상 연결" 셀과 구분하기 위해 badge role로 한정
    const rows = page.getByRole('row');
    await expect(rows.filter({ hasText: '이상 연결' }).getByText('이상', { exact: true })).toBeVisible();
    // UP 상태 배지 확인
    await expect(rows.filter({ hasText: '정상 연결' }).getByText('정상', { exact: true })).toBeVisible();
  });

  test('전체 갱신 버튼 클릭 시 refresh-all API가 호출된다', async ({ authenticatedPage: page }) => {
    await setupApiConnectionListMocks(page);

    let called = false;
    await page.route(
      (url) => url.pathname === '/api/v1/api-connections/refresh-all',
      (route) => {
        called = true;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ jobId: 'test-job-abc' }),
        });
      },
    );

    await page.goto('/admin/api-connections');
    await page.getByRole('button', { name: '전체 갱신' }).click();

    // 호출될 때까지 기다린다(고정 대기 대신 — WD-257)
    await expect.poll(() => called).toBe(true);
  });

  /**
   * 확인 시각 title 은 브라우저 시간대로 그려지므로 서울로 고정한다(WD-472) — 기대값 「7:00:00 확인」은 10:00Z 의 KST 표기라,
   * 고정하지 않으면 시간대가 KST 가 아닌 컴퓨터(UTC 등)에서 늘 실패했다.
   */
  test.describe('전체 갱신 확인 시각 (서울 시간대)', () => {
    test.use({ timezoneId: 'Asia/Seoul' });

    test('전체 갱신 완료 시 Job 진행 상태를 추적해 상태 배지가 자동 갱신된다 (#548)', async ({
      authenticatedPage: page,
    }) => {
      // 이슈 #548 회귀 테스트:
      // 백엔드 refreshAllAsync는 jobId만 반환하는 비동기 Job이라, 트리거 응답 직후 1회
      // invalidate만으로는 실제 헬스체크 완료 결과가 반영되지 않는다. useApiConnectionsRefreshJob이
      // jobId를 추적하다가 COMPLETED 시점에 목록을 다시 불러오는지 검증한다.
      let listCallCount = 0;
      await page.route(
        (url) => url.pathname === '/api/v1/api-connections',
        (route) => {
          if (route.request().method() !== 'GET') return route.fallback();
          listCallCount += 1;
          // 첫 호출(페이지 진입) = 갱신 전 확인 시각, 두 번째 호출(Job 완료 후 invalidate) = 갱신된 확인 시각
          const checkedAt = listCallCount === 1 ? '2026-04-15T10:00:00Z' : '2026-04-15T10:05:00Z';
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify([
              createApiConnection({ id: 1, name: '공공 데이터 API', lastStatus: 'UP', lastCheckedAt: checkedAt }),
            ]),
          });
        },
      );
      await mockApi(page, 'GET', '/api/v1/api-connections/selectable', []);
      await mockApi(page, 'POST', '/api/v1/api-connections/refresh-all', { jobId: 'job-548' });

      // Job 진행 SSE 스트림 — 완료 이벤트를 즉시 반환하도록 모킹 (REST 폴백 없이 SSE 한 줄로 종결)
      await page.route(
        (url) => url.pathname === '/api/v1/jobs/job-548/progress',
        (route) =>
          route.fulfill({
            status: 200,
            contentType: 'text/event-stream',
            body: 'data: {"jobId":"job-548","jobType":"API_CONNECTION_REFRESH","stage":"COMPLETED","progress":100}\n\n',
          }),
      );

      await page.goto('/admin/api-connections');
      await expect(page.getByRole('heading', { name: 'API 연결 관리' })).toBeVisible();

      const statusBadge = page
        .getByRole('row')
        .filter({ hasText: '공공 데이터 API' })
        .getByText('정상', { exact: true });

      // 갱신 전: 초기 확인 시각이 title(tooltip)로 노출된다.
      // Node(테스트 러너)의 ICU 로캘 포맷이 브라우저와 다를 수 있어(오후 vs PM) 값 자체를
      // 하드코딩하지 않고, "갱신 전후 title이 실제로 달라지는지"를 핵심으로 검증한다.
      const beforeTitle = await statusBadge.getAttribute('title');
      expect(beforeTitle).toContain('2026. 4. 15.');
      expect(beforeTitle).toContain('7:00:00 확인');

      await page.getByRole('button', { name: '전체 갱신' }).click();

      // Job COMPLETED → 목록이 재조회되어 확인 시각 title이 갱신돼야 한다
      await expect
        .poll(() => statusBadge.getAttribute('title'))
        .not.toBe(beforeTitle);
      const afterTitle = await statusBadge.getAttribute('title');
      expect(afterTitle).toContain('7:05:00 확인');

      // 완료 토스트도 노출되어야 한다
      await expect(page.getByText('전체 연결 상태 갱신이 완료되었습니다.')).toBeVisible();

      // 목록이 두 번(진입 시 + Job 완료 후 invalidate) 호출되었는지 확인
      expect(listCallCount).toBeGreaterThanOrEqual(2);
    });
  });

  test('전체 갱신 Job 진행 조회가 403 이면 재시도를 멈추고 버튼을 되돌리며 한국어 문구를 보인다 (WD-203)', async ({
    authenticatedPage: page,
  }) => {
    // WD-203 회귀 테스트: 진행 SSE·상태 조회가 403 을 주면 예전엔 오류를 삼키고 영원히 재시도해
    // 버튼이 「갱신 중...」에 멈췄다. 이제는 즉시 멈추고 서버 문구를 토스트로 보여야 한다.
    await mockApi(page, 'GET', '/api/v1/api-connections', [
      createApiConnection({ id: 1, name: '공공 데이터 API', lastStatus: 'UP' }),
    ]);
    await mockApi(page, 'GET', '/api/v1/api-connections/selectable', []);
    await mockApi(page, 'POST', '/api/v1/api-connections/refresh-all', { jobId: 'job-203' });

    let jobRequests = 0;
    const forbidden = JSON.stringify({
      status: 403,
      error: 'Forbidden',
      message: '이 작업을 시작한 사용자만 진행 상황을 볼 수 있습니다.',
    });
    await page.route(
      (url) => url.pathname.startsWith('/api/v1/jobs/job-203/'),
      (route) => {
        jobRequests += 1;
        return route.fulfill({ status: 403, contentType: 'application/json', body: forbidden });
      },
    );

    await page.goto('/admin/api-connections');
    await page.getByRole('button', { name: '전체 갱신' }).click();

    await expect(page.getByText('이 작업을 시작한 사용자만 진행 상황을 볼 수 있습니다.')).toBeVisible();
    // 「갱신 중...」이 풀리고 다시 누를 수 있어야 한다
    await expect(page.getByRole('button', { name: '전체 갱신' })).toBeEnabled();

    // 재연결 백오프 첫 주기(3초)를 넘겨 기다려도 추가 요청이 없어야 한다(요청 1회로 끝)
    await page.waitForTimeout(4000);
    expect(jobRequests).toBe(1);
  });

  test('상세 페이지에서 지금 확인 버튼 클릭 시 test API가 호출된다', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionDetailMocks(page, 1);

    let testCalled = false;
    await page.route(
      (url) => url.pathname === '/api/v1/api-connections/1/test',
      (route) => {
        if (route.request().method() === 'POST') {
          testCalled = true;
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ ok: true, status: 200, latencyMs: 80, errorMessage: null }),
          });
        }
        return route.continue();
      },
    );

    await page.goto('/admin/api-connections/1');
    await expect(page.getByRole('heading', { name: 'API 연결 상세' })).toBeVisible();

    // 연결 상태 카드의 "지금 확인" 버튼
    await page.getByRole('button', { name: '지금 확인' }).click();

    // 호출될 때까지 기다린다(고정 대기 대신 — WD-257)
    await expect.poll(() => testCalled).toBe(true);
  });

  test('baseUrl 미입력 시 Base URL 칸에 인라인 오류가 표시된다', async ({ authenticatedPage: page }) => {
    await setupApiConnectionListMocks(page);
    await page.goto('/admin/api-connections');
    await page.getByRole('button', { name: '새 연결' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // 이름만 입력하고 baseUrl 없이 생성 시도
    await page.getByPlaceholder('예: Make.com API').fill('URL 없는 연결');
    await page.getByPlaceholder('Authorization').fill('X-Key');
    await page.getByPlaceholder('API 키를 입력하세요').fill('key-value');
    await page.getByRole('button', { name: '만들기' }).click();

    // 빈 값은 형식 안내가 아니라 빈 값 안내로 보인다(WD-561)
    await expect(page.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true })).toHaveAccessibleDescription('Base URL을 입력하세요.');
    await expect(page.getByRole('dialog')).toBeVisible();
  });

  test('생성 폼 — BEARER 타입에서 토큰 미입력 시 토큰 칸에 인라인 오류', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionListMocks(page);
    await page.goto('/admin/api-connections');
    await page.getByRole('button', { name: '새 연결' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    await page.getByPlaceholder('예: Make.com API').fill('Bearer 검증 테스트');
    // Base URL 입력 — 없으면 baseUrl 검증이 먼저 실패하여 토큰 검증까지 도달하지 못한다
    await page.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true }).fill('https://api.example.com');
    // Bearer Token으로 변경
    await page.getByRole('combobox').first().click();
    await page.getByRole('option', { name: 'Bearer Token' }).click();
    // 토큰 입력 없이 생성 시도
    await page.getByRole('button', { name: '만들기' }).click();

    await expect(page.getByPlaceholder('토큰을 입력하세요')).toHaveAccessibleDescription('Bearer 토큰을 입력하세요.');
    await expect(page.getByRole('dialog')).toBeVisible();
  });

  // (WD-639) 생성·저장 전 테스트·상세 저장이 한 헬퍼로 빈 비밀을 판정한다 — 서버처럼 「Bearer 」 접두어만 있는 토큰도 빈 토큰이라
  // 서버를 부르지 않고 같은 문구로 안내한다. 예전에는 화면마다 trim() 만 봐서 「Bearer 」 가 통과해 서버 400 에서야 거절됐다.
  test('「Bearer 」 접두어만 있는 토큰은 생성·연결 테스트 모두 서버를 부르지 않고 같은 안내를 보인다 (WD-639)', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionListMocks(page);
    const createCapture = await mockApi(
      page,
      'POST',
      '/api/v1/api-connections',
      { id: 21, name: 'WD639', authType: 'BEARER' },
      { capture: true },
    );
    const testCapture = await mockApi(
      page,
      'POST',
      '/api/v1/api-connections/test',
      {
        ok: true,
        status: 200,
        latencyMs: 10,
        errorMessage: null,
        requestUrl: 'https://api.example.com',
        responseBodyPreview: null,
        responseHeaders: {},
        responseContentType: null,
      },
      { capture: true },
    );

    await page.goto('/admin/api-connections');
    await page.getByRole('button', { name: '새 연결' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.getByPlaceholder('예: Make.com API').fill('WD639');
    await page.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true }).fill('https://api.example.com');
    await page.getByRole('combobox').first().click();
    await page.getByRole('option', { name: 'Bearer Token' }).click();
    const tokenInput = page.getByRole('textbox', { name: 'Bearer Token' });

    // 만들기(WD-561)와 연결 테스트(WD-666) 모두 토큰 칸 인라인 오류로 같은 문구를 안내한다 — 그 뒤에 요청이 없었음을 본다.
    const missingText = 'Bearer 토큰을 입력하세요.';

    // 접두어만(공백 포함) — 생성·연결 테스트 모두 막힌다
    await tokenInput.fill('Bearer   ');
    await page.getByRole('button', { name: '만들기' }).click();
    await expect(tokenInput).toHaveAccessibleDescription(missingText);
    // 오류를 지우려고 한 글자 고쳤다가 되돌린 뒤 연결 테스트 — 테스트도 같은 인라인 오류를 다시 단다(토스트 없음)
    await tokenInput.fill('Bearer  ');
    await expect(tokenInput).not.toHaveAccessibleDescription(missingText);
    await page.getByRole('button', { name: '연결 테스트' }).click();
    await expect(tokenInput).toHaveAccessibleDescription(missingText);
    await expect(page.locator('[data-sonner-toast]').filter({ hasText: missingText })).toHaveCount(0);
    // 겹친 접두어도 빈 토큰이다 — 입력하면 오류가 지워지고, 다시 만들기를 누르면 같은 오류가 다시 붙는다
    await tokenInput.fill('bearer bearer');
    await expect(tokenInput).not.toHaveAccessibleDescription(missingText);
    await page.getByRole('button', { name: '만들기' }).click();
    await expect(tokenInput).toHaveAccessibleDescription(missingText);
    expect(createCapture.requests).toHaveLength(0);
    expect(testCapture.requests).toHaveLength(0);
    await expect(page.getByRole('dialog')).toBeVisible();

    // 접두어 + 실제 토큰은 통과한다(회귀) — 접두어 정리는 서버 몫이라 입력 그대로 보낸다
    await tokenInput.fill('Bearer real-token');
    await page.getByRole('button', { name: '만들기' }).click();
    const req = await createCapture.waitForRequest();
    expect(req.payload).toMatchObject({ authType: 'BEARER', authConfig: { token: 'Bearer real-token' } });
  });

  test('상세 페이지 인증 저장 — 「Bearer 」 만 있는 토큰은 PUT 없이 안내한다 (WD-639)', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionDetailMocks(page, 1);
    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/api-connections/1',
      { id: 1, name: '공공 데이터 API', authType: 'BEARER' },
      { capture: true },
    );

    await page.goto('/admin/api-connections/1');
    await expect(page.getByRole('heading', { name: 'API 연결 상세' })).toBeVisible();
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    await page.getByRole('combobox').first().click();
    await page.getByRole('option', { name: 'Bearer Token' }).click();

    await page.getByPlaceholder('새 토큰을 입력하세요').fill('Bearer ');
    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect(page.getByText('Bearer 토큰을 입력하세요.')).toBeVisible();
    expect(updateCapture.requests).toHaveLength(0);

    await page.getByPlaceholder('새 토큰을 입력하세요').fill('Bearer fresh-token');
    await page.getByRole('button', { name: '인증 저장' }).click();
    const req = await updateCapture.waitForRequest();
    expect(req.payload).toMatchObject({ authType: 'BEARER', authConfig: { token: 'Bearer fresh-token' } });
  });

  test('존재하지 않는 ID 접근 시 toast 에러 후 목록으로 이동한다 (refs #37)', async ({
    authenticatedPage: page,
  }) => {
    // 목록 페이지 모킹 (리다이렉트 목적지)
    await setupApiConnectionListMocks(page);

    // 존재하지 않는 ID 9999에 대해 404 응답 모킹
    await page.route(
      (url) => url.pathname === '/api/v1/api-connections/9999',
      (route) => {
        if (route.request().method() === 'GET') {
          return route.fulfill({
            status: 404,
            contentType: 'application/json',
            body: JSON.stringify({ message: '연결을 찾을 수 없습니다.' }),
          });
        }
        return route.continue();
      },
    );

    await page.goto('/admin/api-connections/9999');

    // toast 에러 메시지 확인
    await expect(page.getByText('연결 정보를 불러오는 데 실패했습니다.')).toBeVisible();

    // 목록 페이지로 리다이렉트되어야 한다
    await expect(page).toHaveURL(/\/admin\/api-connections$/);
  });

  test('뒤로가기 버튼 클릭 시 목록 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupApiConnectionDetailMocks(page, 1);
    await setupApiConnectionListMocks(page);
    await page.goto('/admin/api-connections/1');
    await expect(page.getByRole('heading', { name: 'API 연결 상세' })).toBeVisible();

    // ArrowLeft ghost icon 버튼 — variant="ghost" size="icon", 헤딩 바로 왼쪽에 위치
    // 'API 연결 상세' 헤딩 컨테이너의 첫 번째 버튼
    const headerRow = page.locator('div.flex.items-center.gap-4').first();
    await headerRow.getByRole('button').first().click();

    await expect(page).toHaveURL(/\/admin\/api-connections$/);
  });

  // (#90) 생성 다이얼로그의 "연결 테스트" 버튼 — payload만 보내 dry-run 헬스체크 후 결과 인라인 표시
  test('생성 다이얼로그에서 "연결 테스트" 클릭 시 dry-run 호출 + 결과 노출 (#90)', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionListMocks(page);

    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/api-connections/test',
      {
        ok: true,
        status: 200,
        latencyMs: 142,
        errorMessage: null,
        requestUrl: 'https://api.example.com/health',
        responseBodyPreview: '{"status":"OK"}',
        responseHeaders: { 'content-type': 'application/json' },
        responseContentType: 'application/json',
      },
      { capture: true },
    );

    await page.goto('/admin/api-connections');
    await page.getByRole('button', { name: '새 연결' }).click();

    // 입력값 채우기
    await page.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true }).fill('https://api.example.com');
    await page.getByPlaceholder('/health').fill('/health');
    await page.getByPlaceholder('Authorization').fill('X-API-Key');
    await page.getByPlaceholder('API 키를 입력하세요').fill('dry-run-key');

    await page.getByRole('button', { name: '연결 테스트' }).click();

    // payload 검증 — dry-run 엔드포인트로 입력값 그대로 전달되어야 한다
    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({
      authType: 'API_KEY',
      baseUrl: 'https://api.example.com',
      healthCheckPath: '/health',
    });
    expect((req.payload as { authConfig: Record<string, string> }).authConfig).toMatchObject({
      headerName: 'X-API-Key',
      apiKey: 'dry-run-key',
    });

    // 결과 인라인 표시 — 성공 표시와 응답 메타가 다이얼로그 안에 노출되어야 한다
    await expect(page.getByText('연결 성공')).toBeVisible();
    await expect(page.getByText(/HTTP 200/)).toBeVisible();
    await expect(page.getByText(/142ms/)).toBeVisible();
    await expect(page.getByText('https://api.example.com/health')).toBeVisible();
  });

  // (WD-623) 저장 전 "연결 테스트" — 비밀 값이 비면 서버를 부르지 않고 서버와 같은 안내를 토스트로 보인다.
  // 서버도 빈 비밀의 저장 전 테스트를 400 으로 거절하므로(대상 서버 401/403 대신 무엇을 빠뜨렸는지 안내) 화면에서 먼저 막는다.
  test('생성 다이얼로그 "연결 테스트" — API 키·Bearer 토큰이 비면 그 칸 안내만 보이고 test API 를 부르지 않는다 (WD-623·WD-666)', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionListMocks(page);

    // test API 호출 횟수를 센다 — 비밀이 빈 동안에는 0, 토큰을 채운 뒤에만 1 이어야 한다.
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/api-connections/test',
      {
        ok: true,
        status: 200,
        latencyMs: 10,
        errorMessage: null,
        requestUrl: 'https://api.example.com',
        responseBodyPreview: null,
        responseHeaders: {},
        responseContentType: null,
      },
      { capture: true },
    );

    await page.goto('/admin/api-connections');
    await page.getByRole('button', { name: '새 연결' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true }).fill('https://api.example.com');

    // API_KEY(기본) — 헤더 이름만 있고 키가 비면 막힌다
    await page.getByPlaceholder('Authorization').fill('X-API-Key');
    await page.getByRole('button', { name: '연결 테스트' }).click();
    await expect(page.getByText('API 키를 입력하세요.')).toBeVisible();

    // BEARER — 공백만 있는 토큰도 빈 값으로 보고 막는다
    await page.getByRole('combobox').first().click();
    await page.getByRole('option', { name: 'Bearer Token' }).click();
    const tokenInput = page.getByRole('textbox', { name: 'Bearer Token' });
    await tokenInput.fill('   ');
    await page.getByRole('button', { name: '연결 테스트' }).click();
    await expect(page.getByText('Bearer 토큰을 입력하세요.')).toBeVisible();
    expect(capture.requests).toHaveLength(0);

    // 토큰을 채우면 정상 호출되고 토큰이 payload 에 실린다(회귀)
    await tokenInput.fill('real-token');
    await page.getByRole('button', { name: '연결 테스트' }).click();
    await expect(page.getByText('연결 성공')).toBeVisible();
    expect(capture.requests).toHaveLength(1);
    expect(capture.requests[0].payload).toMatchObject({ authType: 'BEARER', authConfig: { token: 'real-token' } });
  });

  // (#92) 마스킹 키 옆 도움말 아이콘 — 보안 정책 안내 tooltip 노출 검증
  test('마스킹된 키 값 옆 도움말 아이콘에 보안 정책 tooltip이 노출된다 (#92)', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionDetailMocks(page, 1);
    await page.goto('/admin/api-connections/1');

    // 마스킹 뷰 + 도움말 버튼 노출 확인
    const helpButton = page.getByTestId('masked-key-help');
    await expect(helpButton).toBeVisible();
    await expect(helpButton).toHaveAttribute('aria-label', '키 값 마스킹 정책 안내');

    // hover 시 tooltip 콘텐츠 노출
    await helpButton.hover();
    await expect(
      page.getByText('보안 정책상 마지막 4자리만 표시됩니다.', { exact: false }),
    ).toBeVisible();
  });

  test('잘못된 Base URL 저장 시 한국어 검증 에러 토스트가 표시된다 (#125)', async ({
    authenticatedPage: page,
  }) => {
    // 이슈 #125 회귀 테스트:
    // 서버가 422/400 검증 오류를 반환할 때 errors 필드의 한국어 메시지를 토스트에 표시해야 한다.
    // 기존에는 최상위 message "Validation failed" (영문)가 그대로 노출됨.
    await setupApiConnectionDetailMocks(page, 1);

    // PUT API가 400 + errors 필드(한국어)를 반환하도록 모킹
    await page.route(
      (url) => url.pathname === '/api/v1/api-connections/1',
      (route) => {
        if (route.request().method() === 'PUT') {
          return route.fulfill({
            status: 400,
            contentType: 'application/json',
            body: JSON.stringify({
              status: 400,
              error: 'Bad Request',
              message: 'Validation failed',
              errors: { baseUrl: 'http:// 또는 https://로 시작하는 URL이어야 합니다' },
            }),
          });
        }
        return route.fallback();
      },
    );

    await page.goto('/admin/api-connections/1');
    await expect(page.getByRole('heading', { name: 'API 연결 상세' })).toBeVisible();

    // Base URL에 잘못된 값 입력 후 저장
    const baseUrlInput = page.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true });
    await baseUrlInput.clear();
    // 클라이언트 형식 검사(WD-909)를 통과하는 값이어야 서버 400 문구 경로를 계속 덮는다
    await baseUrlInput.fill('https://rejected-by-server.example');
    await page.getByRole('button', { name: '저장' }).click();

    // 한국어 검증 메시지가 토스트로 표시되어야 한다 (영문 "Validation failed" 아님)
    await expect(
      page.getByText('http:// 또는 https://로 시작하는 URL이어야 합니다'),
    ).toBeVisible();
    // 영문 메시지는 토스트에 노출되지 않아야 한다
    await expect(page.getByText('Validation failed')).not.toBeVisible();
  });

  test('Base URL 비워서 저장 시 에러 토스트 표시 — API 미호출 검증 (#124)', async ({
    authenticatedPage: page,
  }) => {
    // 이슈 #124 회귀 테스트:
    // Base URL을 지운 뒤 저장 클릭 시 클라이언트 검증이 에러 토스트를 표시하고
    // PUT API를 호출하지 않아야 한다 (기존에는 성공 토스트가 뜨고 서버에서 무시됨).
    await setupApiConnectionDetailMocks(page, 1);

    // PUT API 캡처 설정 — 이 요청이 발생하면 안 된다
    let putCalled = false;
    await page.route(
      (url) => url.pathname === '/api/v1/api-connections/1',
      (route) => {
        if (route.request().method() === 'PUT') {
          putCalled = true;
          return route.fulfill({ status: 200, body: JSON.stringify({ id: 1 }) });
        }
        return route.fallback();
      },
    );

    await page.goto('/admin/api-connections/1');
    await expect(page.getByRole('heading', { name: 'API 연결 상세' })).toBeVisible();

    // Base URL 필드를 비운다 (placeholder=API_BASE_URL_PLACEHOLDER)
    const baseUrlInput = page.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true });
    await baseUrlInput.clear();

    // 저장 버튼 클릭
    await page.getByRole('button', { name: '저장' }).click();

    // Base URL 칸에 인라인 오류가 표시되어야 한다(WD-561 — 토스트 대신)
    await expect(baseUrlInput).toHaveAccessibleDescription('Base URL을 입력하세요.');

    // PUT API가 호출되지 않아야 한다 (short wait after click to be sure)
    await page.waitForTimeout(300);
    expect(putCalled).toBe(false);
  });

  test('상세 편집 — Base URL 형식이 틀리면 인라인 오류·포커스로 막고 고치면 지워진다 (WD-909)', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionDetailMocks(page, 1);

    // PUT payload 를 캡처한다 — 틀린 값일 때는 0건이어야 한다
    const putBodies: Array<{ baseUrl?: string }> = [];
    await page.route(
      (url) => url.pathname === '/api/v1/api-connections/1',
      (route) => {
        if (route.request().method() === 'PUT') {
          putBodies.push(route.request().postDataJSON());
          return route.fulfill({ status: 200, json: { id: 1 } });
        }
        return route.fallback();
      },
    );

    await page.goto('/admin/api-connections/1');
    await expect(page.getByRole('heading', { name: 'API 연결 상세' })).toBeVisible();

    const baseUrlInput = page.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true });
    await baseUrlInput.fill(API_BASE_URL_PLACEHOLDER);
    await page.getByRole('button', { name: '저장' }).click();

    await expect(baseUrlInput).toHaveAccessibleDescription('Base URL은 http 또는 https로 시작해야 합니다.');
    await expect(baseUrlInput).toHaveAttribute('aria-invalid', 'true');
    await expect(baseUrlInput).toBeFocused();
    expect(putBodies).toHaveLength(0);

    // 정상 값으로 고치면 오류가 지워지고 그 값이 저장된다
    await baseUrlInput.fill('https://fixed.example.com');
    await expect(baseUrlInput).not.toHaveAttribute('aria-invalid', 'true');
    await page.getByRole('button', { name: '저장' }).click();
    await expect.poll(() => putBodies.length).toBe(1);
    expect(putBodies[0].baseUrl).toBe('https://fixed.example.com');
  });

  // (#647) 동일 테넌트 내 이름 중복 시 백엔드가 409로 거부 — 토스트 노출 + 다이얼로그 유지 검증
  test('생성 폼 — 이름 중복 시 서버 409 오류가 토스트로 표시되고 다이얼로그가 닫히지 않는다 (#647)', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionListMocks(page);

    // 백엔드 ApiConnectionNameAlreadyExistsException → 409 응답 모킹
    await page.route(
      (url) => url.pathname === '/api/v1/api-connections',
      (route) => {
        if (route.request().method() === 'POST') {
          return route.fulfill({
            status: 409,
            contentType: 'application/json',
            body: JSON.stringify({
              status: 409,
              error: 'Conflict',
              message: '이미 사용 중인 이름입니다: 공공 데이터 API',
              errors: null,
            }),
          });
        }
        return route.fallback();
      },
    );

    await page.goto('/admin/api-connections');
    await page.getByRole('button', { name: '새 연결' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // 기존 연결과 동일한 이름으로 생성 시도
    await page.getByPlaceholder('예: Make.com API').fill('공공 데이터 API');
    await page.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true }).fill('https://dup.example.com');
    await page.getByPlaceholder('Authorization').fill('X-API-Key');
    await page.getByPlaceholder('API 키를 입력하세요').fill('dup-key');

    await page.getByRole('button', { name: '만들기' }).click();

    // 백엔드 409 메시지가 토스트로 그대로 노출되어야 한다
    await expect(page.getByText('이미 사용 중인 이름입니다: 공공 데이터 API')).toBeVisible();
    // 거부되었으므로 다이얼로그는 닫히지 않고 입력값도 유지되어야 한다
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByPlaceholder('예: Make.com API')).toHaveValue('공공 데이터 API');
  });

  // (#647) 이름이 같은 다른 연결과 혼동해 잘못 삭제하지 않도록 삭제 확인 다이얼로그에 ID/Base URL을 표시
  test('목록 삭제 확인 다이얼로그에 ID와 Base URL이 함께 표시된다 (#647)', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionListMocks(page);
    await page.goto('/admin/api-connections');

    const rows = page.getByRole('row').filter({ has: page.getByRole('button') });
    await rows.first().getByRole('button').click();

    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toBeVisible();
    // 이름만이 아니라 ID/Base URL도 함께 노출되어야 오삭제를 방지할 수 있다
    await expect(dialog).toContainText('ID: 1');
    await expect(dialog).toContainText('https://api.example.com');
  });

  test('상세 페이지 삭제 확인 다이얼로그에도 ID와 Base URL이 함께 표시된다 (#647)', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionDetailMocks(page, 1);
    await page.goto('/admin/api-connections/1');

    await page.getByRole('button', { name: '이 연결 삭제' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('ID: 1');
    await expect(dialog).toContainText('https://api.example.com');
  });

  test('폼 라벨이 입력 요소와 연결돼 있다 — 접근 가능한 이름 + 라벨 클릭 포커스 (#432)', async ({
    authenticatedPage: page,
  }) => {
    // 이슈 #432 회귀 테스트:
    // 이전에는 <Label> 이 입력과 형제로만 놓여 있어(htmlFor/id 부재) 스크린리더가
    // 필드 이름을 읽지 못했고 라벨을 클릭해도 포커스가 가지 않았다.
    // 정적 게이트(src/styles/design-guideline-gate.test.ts)가 소스 형태를 막고,
    // 이 테스트는 **실제 브라우저에서 연결이 성립하는지**를 확인한다 —
    // htmlFor 가 있어도 가리키는 id 가 없으면 게이트는 통과하지만 접근성은 여전히 깨진다.
    await setupApiConnectionListMocks(page);
    await page.goto('/admin/api-connections');

    await page.getByRole('button', { name: '새 연결' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();

    // 1) 접근 가능한 이름으로 입력을 찾을 수 있어야 한다 (연결 전에는 매칭 자체가 불가능했다).
    // 필수 칸 라벨은 FormField 가 「*」를 덧붙이므로(WD-561) 접두로 찾는다
    const nameInput = page.getByLabel(/^연결 이름/);
    await nameInput.fill('접근성 검증용 연결');
    await expect(nameInput).toHaveValue('접근성 검증용 연결');

    // 2) 라벨을 클릭하면 그 입력이 포커스를 받아야 한다.
    //    Select 트리거가 아니라 일반 <Input> 으로 검증한다 — Select 는 클릭이
    //    드롭다운을 여는 부수효과가 있어 포커스 단언이 흐려진다.
    const baseUrlInput = page.getByLabel(/^Base URL/);
    await dialog.locator('label', { hasText: 'Base URL' }).click();
    await expect(baseUrlInput).toBeFocused();

    // 3) 다이얼로그의 모든 <label> 이 실재하는 컨트롤을 가리켜야 한다 (dangling htmlFor 방지).
    //    id 중복도 함께 본다 — useId() 대신 하드코딩 id 로 되돌아가면 여기서 잡힌다.
    const associations = await dialog.evaluate((root) => {
      const labels = [...root.querySelectorAll('label')];
      const ids = [...root.querySelectorAll('[id]')].map((el) => el.id);
      return {
        unlinked: labels.filter((l) => !l.htmlFor).map((l) => l.textContent?.trim() ?? ''),
        dangling: labels
          .filter((l) => l.htmlFor && !root.querySelector(`#${CSS.escape(l.htmlFor)}`))
          .map((l) => l.htmlFor),
        duplicatedIds: [...new Set(ids.filter((v, i) => ids.indexOf(v) !== i))],
      };
    });
    expect(associations.unlinked).toEqual([]);
    expect(associations.dangling).toEqual([]);
    expect(associations.duplicatedIds).toEqual([]);
  });
});

/**
 * API 연결 목록 — 띄어쓰기 없는 긴 이름·Base URL·설명 (WD-559, WD-634)
 * - 긴 값이 칸을 늘려 삭제 칸이 화면 밖으로 밀렸다. 1280·1024 두 폭에서 표가 들어맞고 말줄임된다.
 */
test.describe('API 연결 목록 긴 값 말줄임 (WD-559)', () => {
  const LONG_NAME = 'A'.repeat(100);
  // 150자 URL — 'https://api.example.com/'(24자) + 126자
  const LONG_URL = `https://api.example.com/${'p'.repeat(126)}`;
  const LONG_DESC = '설'.repeat(100);
  const NAME = '기상청 단기예보 API 연결';
  const URL = 'https://apis.data.go.kr/1360000';
  const DESC = '기상 예보 조회용 키';

  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  /** 평범한 길이의 이름·URL·설명 한 행으로 목록을 연다 */
  async function openNormal(page: Page, width: number) {
    await page.setViewportSize({ width, height: 800 });
    await setupApiConnectionListMocks(page);
    await mockApi(page, 'GET', '/api/v1/api-connections', [
      createApiConnection({ id: 1, name: NAME, baseUrl: URL, description: DESC }),
    ]);
    await page.goto('/admin/api-connections');
    const link = page.getByRole('link', { name: NAME });
    const row = page.getByRole('row').filter({ has: link });
    await expectTableFits(row.getByRole('button', { name: `${NAME} 삭제` }));
    return [link, row.getByText(URL, { exact: true }), row.getByText(DESC, { exact: true })] as const;
  }

  for (const width of TABLE_LAYOUT_WIDTHS) {
    test(`${width}px 에서 긴 이름·URL·설명이 있어도 표가 넘치지 않고 삭제 버튼까지 보인다`, async ({
      authenticatedPage: page,
    }) => {
      await page.setViewportSize({ width, height: 800 });
      await setupApiConnectionListMocks(page);
      await mockApi(page, 'GET', '/api/v1/api-connections', [
        // WD-650: 한 번도 확인하지 않은 연결(lastStatus null)은 상태 칸의 가장 넓은 값 「미확인」을 그린다
        createApiConnection({
          id: 1,
          name: LONG_NAME,
          baseUrl: LONG_URL,
          description: LONG_DESC,
          lastStatus: null,
          lastCheckedAt: null,
        }),
        createApiConnection({ id: 2, name: '짧은 연결' }),
      ]);
      await page.goto('/admin/api-connections');

      const link = page.getByRole('link', { name: LONG_NAME });
      const row = page.getByRole('row').filter({ has: link });
      // 상태 칸 배지가 선언 폭(--k 에 들어간 값) 안이어야 다른 칸의 여유와 상관없이 표가 들어맞는다
      await expectBadgesFitDeclaredWidth(row.getByRole('cell').filter({ has: page.getByText('미확인', { exact: true }) }));
      await expectTableFits(row.getByRole('button', { name: `${LONG_NAME} 삭제` }));
      // WD-650: --k(짧은 칸 선언 폭의 합)가 헤더 w-[..] 와 어긋나면 긴 값 상한이 틀어진다
      await expectKMatchesDeclaredWidths(row.getByRole('button', { name: `${LONG_NAME} 삭제` }));
      await expectLongValueTruncated(link, LONG_NAME);
      await expectLongValueTruncated(row.getByText(LONG_URL, { exact: true }), LONG_URL);
      await expectLongValueTruncated(row.getByText(LONG_DESC, { exact: true }), LONG_DESC);
    });
  }

  test('1280px 에서 평범한 길이의 이름·URL·설명은 잘리지 않는다', async ({ authenticatedPage: page }) => {
    for (const el of await openNormal(page, 1280)) await expectValueNotTruncated(el);
  });

  // 1024px 은 짧은 칸(인증 유형·상태·생성일·삭제)을 빼고 남는 폭이 평범한 이름·URL·설명의 합보다 좁아 셋이 다 보일 수 없다 —
  // 표가 들어맞고 잘린 값의 전체가 title 로 남는지만 본다.
  test('1024px 에서 평범한 길이의 이름·URL·설명이 있어도 표가 들어맞고 전체 값은 title 로 남는다', async ({
    authenticatedPage: page,
  }) => {
    const [link, url, desc] = await openNormal(page, 1024);
    await expect(link).toHaveAttribute('title', NAME);
    await expect(url).toHaveAttribute('title', URL);
    await expect(desc).toHaveAttribute('title', DESC);
  });
});

/**
 * WD-561 — API 연결 생성·편집 화면의 필수값 오류를 토스트가 아닌 필드 인라인 오류로 보인다.
 * 예전에는 첫 실패 하나만 사라지는 토스트로 알려, 어느 칸이 문제인지·다른 칸도 틀렸는지 알 수 없었다.
 * 이제 제출 시 모든 칸을 한꺼번에 검사해 칸 아래에 role=alert 문구를 달고(aria-invalid·aria-describedby 연결),
 * 화면 순서상 첫 오류 칸으로 포커스를 옮긴다.
 */
test.describe('API 연결 필수값 인라인 오류 (WD-561)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  /** 새 연결 다이얼로그를 연다 */
  async function openCreateDialog(page: import('@playwright/test').Page) {
    await setupApiConnectionListMocks(page);
    await page.goto('/admin/api-connections');
    await page.getByRole('button', { name: '새 연결' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    return dialog;
  }

  test('빈 채로 만들기 — 모든 필수 칸에 인라인 오류가 한꺼번에 뜨고 이름 칸으로 포커스, 토스트·요청 없음', async ({
    authenticatedPage: page,
  }) => {
    const capture = await mockApi(page, 'POST', '/api/v1/api-connections', { id: 30 }, { capture: true });
    const dialog = await openCreateDialog(page);

    await dialog.getByRole('button', { name: '만들기' }).click();

    const nameInput = dialog.getByPlaceholder('예: Make.com API');
    const baseUrlInput = dialog.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true });
    const headerInput = dialog.getByPlaceholder('Authorization');
    const keyInput = dialog.getByPlaceholder('API 키를 입력하세요');

    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
    await expect(nameInput).toHaveAccessibleDescription('연결 이름을 입력하세요.');
    await expect(baseUrlInput).toHaveAttribute('aria-invalid', 'true');
    await expect(baseUrlInput).toHaveAccessibleDescription('Base URL을 입력하세요.');
    await expect(headerInput).toHaveAttribute('aria-invalid', 'true');
    await expect(headerInput).toHaveAccessibleDescription('헤더 이름을 입력하세요.');
    await expect(keyInput).toHaveAttribute('aria-invalid', 'true');
    await expect(keyInput).toHaveAccessibleDescription('API 키를 입력하세요.');
    await expect(dialog.getByRole('alert')).toHaveCount(4);

    // 화면 순서상 첫 오류 칸(연결 이름)으로 포커스
    await expect(nameInput).toBeFocused();
    // 검증 실패는 토스트로 알리지 않는다
    await expect(page.locator('[data-sonner-toast]')).toHaveCount(0);
    expect(capture.requests?.length ?? 0).toBe(0);
    // 필수 칸은 * 로 표시한다
    await expect(dialog.locator('label', { hasText: '연결 이름' })).toContainText('*');
    await expect(dialog.locator('label', { hasText: '헤더 이름' })).toContainText('*');
    await expect(dialog.locator('label', { hasText: '키 값' })).toContainText('*');
  });

  test('오류 뒤 한 칸을 고치면 그 칸 오류만 사라지고, 모두 고치면 생성된다', async ({
    authenticatedPage: page,
  }) => {
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/api-connections',
      { id: 31, name: '고친 연결', authType: 'API_KEY' },
      { capture: true },
    );
    const dialog = await openCreateDialog(page);
    await dialog.getByRole('button', { name: '만들기' }).click();

    const nameInput = dialog.getByPlaceholder('예: Make.com API');
    const baseUrlInput = dialog.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true });
    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');

    // 이름만 고친다 → 이름 오류만 사라지고 Base URL 오류는 남는다
    await nameInput.fill('고친 연결');
    await expect(nameInput).not.toHaveAttribute('aria-invalid', 'true');
    await expect(dialog.getByText('연결 이름을 입력하세요.')).toHaveCount(0);
    await expect(baseUrlInput).toHaveAttribute('aria-invalid', 'true');
    await expect(dialog.getByText('Base URL을 입력하세요.')).toBeVisible();
    // 입력 중 리마운트로 포커스를 잃지 않는다
    await expect(nameInput).toBeFocused();

    await baseUrlInput.fill('https://api.example.com');
    await dialog.getByPlaceholder('Authorization').fill('X-API-Key');
    await dialog.getByPlaceholder('API 키를 입력하세요').fill('key-1');
    await expect(dialog.getByRole('alert')).toHaveCount(0);
    await dialog.getByRole('button', { name: '만들기' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({
      name: '고친 연결',
      baseUrl: 'https://api.example.com',
      authConfig: { placement: 'header', headerName: 'X-API-Key', apiKey: 'key-1' },
    });
    await expect(page.getByText('API 연결이 생성되었습니다.')).toBeVisible();
  });

  test('이름만 채우고 Base URL 형식이 틀리면 Base URL 오류만 뜨고 그 칸으로 포커스, 공백만인 이름은 빈 값 오류', async ({
    authenticatedPage: page,
  }) => {
    const dialog = await openCreateDialog(page);
    const nameInput = dialog.getByPlaceholder('예: Make.com API');
    const baseUrlInput = dialog.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true });

    await nameInput.fill('형식 검증');
    await baseUrlInput.fill('ftp://api.example.com');
    await dialog.getByPlaceholder('Authorization').fill('X-API-Key');
    await dialog.getByPlaceholder('API 키를 입력하세요').fill('key-1');
    await dialog.getByRole('button', { name: '만들기' }).click();

    await expect(baseUrlInput).toHaveAccessibleDescription('Base URL은 http 또는 https로 시작해야 합니다.');
    await expect(dialog.getByRole('alert')).toHaveCount(1);
    await expect(baseUrlInput).toBeFocused();
    await expect(nameInput).not.toHaveAttribute('aria-invalid', 'true');

    // 공백만 입력한 이름은 빈 값으로 본다 — 이름 + Base URL 두 오류, 포커스는 이름
    await nameInput.fill('   ');
    await dialog.getByRole('button', { name: '만들기' }).click();
    await expect(nameInput).toHaveAccessibleDescription('연결 이름을 입력하세요.');
    await expect(dialog.getByRole('alert')).toHaveCount(2);
    await expect(nameInput).toBeFocused();
  });

  test('헬스체크 경로 형식 오류는 도움말 설명을 지키며 인라인으로 뜬다', async ({ authenticatedPage: page }) => {
    const dialog = await openCreateDialog(page);
    await dialog.getByPlaceholder('예: Make.com API').fill('헬스체크 검증');
    await dialog.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true }).fill('https://api.example.com');
    const healthInput = dialog.getByPlaceholder('/health');
    await healthInput.fill('health');
    await dialog.getByPlaceholder('Authorization').fill('X-API-Key');
    await dialog.getByPlaceholder('API 키를 입력하세요').fill('key-1');
    await dialog.getByRole('button', { name: '만들기' }).click();

    await expect(healthInput).toHaveAttribute('aria-invalid', 'true');
    // 기존 도움말 + 오류 문구가 함께 읽힌다
    await expect(healthInput).toHaveAccessibleDescription(/10분마다 자동 상태 점검.*헬스체크 경로는 \/로 시작해야 합니다\./);
    await expect(healthInput).toBeFocused();
  });

  test('인증 유형을 바꾸면 숨겨진 칸의 오류가 지워지고, Bearer 토큰 누락은 토큰 칸에 뜬다', async ({
    authenticatedPage: page,
  }) => {
    const dialog = await openCreateDialog(page);
    await dialog.getByPlaceholder('예: Make.com API').fill('Bearer 검증');
    await dialog.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true }).fill('https://api.example.com');
    await dialog.getByRole('button', { name: '만들기' }).click();
    await expect(dialog.getByText('헤더 이름을 입력하세요.')).toBeVisible();
    await expect(dialog.getByText('API 키를 입력하세요.')).toBeVisible();

    // Bearer 로 바꾸면 헤더 이름·키 값 오류는 사라진다
    await dialog.getByRole('combobox').first().click();
    await page.getByRole('option', { name: 'Bearer Token' }).click();
    await expect(dialog.getByRole('alert')).toHaveCount(0);

    await dialog.getByRole('button', { name: '만들기' }).click();
    const tokenInput = dialog.getByPlaceholder('토큰을 입력하세요');
    await expect(tokenInput).toHaveAccessibleDescription('Bearer 토큰을 입력하세요.');
    await expect(tokenInput).toBeFocused();
    await expect(page.locator('[data-sonner-toast]')).toHaveCount(0);
  });

  test('위치(Header→Query)를 바꾸면 인증 칸 오류가 지워지고 파라미터 이름 오류로 다시 뜬다', async ({
    authenticatedPage: page,
  }) => {
    const dialog = await openCreateDialog(page);
    await dialog.getByPlaceholder('예: Make.com API').fill('Query 검증');
    await dialog.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true }).fill('https://api.example.com');
    await dialog.getByRole('button', { name: '만들기' }).click();
    await expect(dialog.getByText('헤더 이름을 입력하세요.')).toBeVisible();

    await dialog.getByRole('combobox').nth(1).click();
    await page.getByRole('option', { name: 'Query Parameter' }).click();
    await expect(dialog.getByRole('alert')).toHaveCount(0);

    await dialog.getByRole('button', { name: '만들기' }).click();
    const paramInput = dialog.getByPlaceholder('api_key');
    await expect(paramInput).toHaveAccessibleDescription('파라미터 이름을 입력하세요.');
    await expect(paramInput).toBeFocused();
  });

  test('다이얼로그를 닫았다 다시 열면 오류가 남아 있지 않다', async ({ authenticatedPage: page }) => {
    const dialog = await openCreateDialog(page);
    await dialog.getByRole('button', { name: '만들기' }).click();
    await expect(dialog.getByRole('alert')).toHaveCount(4);

    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    await page.getByRole('button', { name: '새 연결' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByRole('dialog').getByRole('alert')).toHaveCount(0);
    await expect(page.getByRole('dialog').getByPlaceholder('예: Make.com API')).not.toHaveAttribute('aria-invalid', 'true');
  });

  test('편집 화면 — 이름·Base URL 을 비우고 저장하면 두 칸에 인라인 오류, 이름으로 포커스, PUT·토스트 없음', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionDetailMocks(page, 1);
    const updateCapture = await mockApi(page, 'PUT', '/api/v1/api-connections/1', { id: 1 }, { capture: true });
    await page.goto('/admin/api-connections/1');
    await expect(page.getByRole('heading', { name: 'API 연결 상세' })).toBeVisible();

    const nameInput = page.getByLabel(/^연결 이름/);
    const baseUrlInput = page.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true });
    await nameInput.fill('   ');
    await baseUrlInput.clear();
    await page.getByRole('button', { name: '저장', exact: true }).click();

    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
    await expect(nameInput).toHaveAccessibleDescription('연결 이름을 입력하세요.');
    await expect(baseUrlInput).toHaveAccessibleDescription('Base URL을 입력하세요.');
    await expect(nameInput).toBeFocused();
    await expect(page.locator('[data-sonner-toast]')).toHaveCount(0);
    expect(updateCapture.requests?.length ?? 0).toBe(0);

    // 고치면 오류가 사라지고 저장된다
    await nameInput.fill('고친 이름');
    await expect(nameInput).not.toHaveAttribute('aria-invalid', 'true');
    await baseUrlInput.fill('https://fixed.example.com');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    const req = await updateCapture.waitForRequest();
    expect(req.payload).toMatchObject({ name: '고친 이름', baseUrl: 'https://fixed.example.com' });
  });

  test('편집 화면 — 인증 헤더 이름을 비우면 인라인 오류, 저장된 키가 있으면 키 칸 비움은 오류가 아니다 (WD-480 유지)', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionDetailMocks(page, 1);
    const updateCapture = await mockApi(page, 'PUT', '/api/v1/api-connections/1', { id: 1 }, { capture: true });
    await page.goto('/admin/api-connections/1');
    await page.getByRole('button', { name: '인증 정보 변경' }).click();

    const headerInput = page.getByPlaceholder('Authorization');
    const keyInput = page.getByPlaceholder('새 API 키를 입력하세요');
    await headerInput.clear();
    await page.getByRole('button', { name: '인증 저장' }).click();

    await expect(headerInput).toHaveAccessibleDescription('헤더 이름을 입력하세요.');
    await expect(headerInput).toBeFocused();
    // 저장된 키를 유지하므로 키 칸은 오류가 아니다
    await expect(keyInput).not.toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByRole('alert')).toHaveCount(1);
    await expect(page.locator('[data-sonner-toast]')).toHaveCount(0);
    expect(updateCapture.requests?.length ?? 0).toBe(0);

    await headerInput.fill('X-Fixed');
    await expect(page.getByRole('alert')).toHaveCount(0);
    await page.getByRole('button', { name: '인증 저장' }).click();
    const req = await updateCapture.waitForRequest();
    expect(req.payload).toMatchObject({
      authConfig: { placement: 'header', headerName: 'X-Fixed', apiKey: '***masked***' },
    });
  });

  test('편집 화면 — 인증 편집을 취소하면 인증 칸 오류가 지워진다', async ({ authenticatedPage: page }) => {
    await setupApiConnectionDetailMocks(page, 1);
    await page.goto('/admin/api-connections/1');
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    await page.getByPlaceholder('Authorization').clear();
    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect(page.getByText('헤더 이름을 입력하세요.')).toBeVisible();

    await page.getByRole('button', { name: '취소' }).click();
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    await expect(page.getByRole('alert')).toHaveCount(0);
  });
});

/**
 * WD-666 — WD-561 후속: 연결 테스트 인라인 오류·상세 화면 오류 초기화·키 칸 필수 표시/안내 일치·안내 aria 연결·필수 표시 정렬
 */
test.describe('API 연결 인라인 오류 후속 (WD-666)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  /** 저장 전 연결 테스트(dry-run) 응답 — 호출 횟수를 세려고 capture 한다 */
  async function mockDryRun(page: Page) {
    return mockApi(
      page,
      'POST',
      '/api/v1/api-connections/test',
      {
        ok: true,
        status: 200,
        latencyMs: 10,
        errorMessage: null,
        requestUrl: 'https://api.example.com',
        responseBodyPreview: null,
        responseHeaders: {},
        responseContentType: null,
      },
      { capture: true },
    );
  }

  test('연결 테스트 — 칸 검증 실패는 토스트가 아니라 그 칸 인라인 오류로 뜨고 첫 오류 칸으로 포커스, 요청 없음', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionListMocks(page);
    const capture = await mockDryRun(page);
    await page.goto('/admin/api-connections');
    await page.getByRole('button', { name: '새 연결' }).click();
    const dialog = page.getByRole('dialog');

    const baseUrlInput = dialog.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true });
    const healthInput = dialog.getByPlaceholder('/health');
    const keyInput = dialog.getByPlaceholder('API 키를 입력하세요');
    await baseUrlInput.fill('ftp://bad');
    await healthInput.fill('health');
    await dialog.getByRole('button', { name: '연결 테스트' }).click();

    await expect(baseUrlInput).toHaveAttribute('aria-invalid', 'true');
    await expect(baseUrlInput).toHaveAccessibleDescription('Base URL은 http 또는 https로 시작해야 합니다.');
    // 헬스체크 경로는 도움말 설명을 지킨 채 오류가 이어진다
    await expect(healthInput).toHaveAccessibleDescription(/헬스체크 경로는 \/로 시작해야 합니다\./);
    await expect(keyInput).toHaveAccessibleDescription('API 키를 입력하세요.');
    await expect(baseUrlInput).toBeFocused();
    await expect(page.locator('[data-sonner-toast]')).toHaveCount(0);
    expect(capture.requests).toHaveLength(0);
  });

  test('연결 테스트 — 앞선 만들기의 오래된 인라인 오류를 지우고, 통과하면 결과를 보인다', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionListMocks(page);
    const capture = await mockDryRun(page);
    await page.goto('/admin/api-connections');
    await page.getByRole('button', { name: '새 연결' }).click();
    const dialog = page.getByRole('dialog');

    // 빈 채로 만들기 → 네 칸 오류
    await dialog.getByRole('button', { name: '만들기' }).click();
    await expect(dialog.getByRole('alert')).toHaveCount(4);

    // 테스트에 필요한 칸만 채우고(이름·헤더 이름은 비운 채) 연결 테스트 — 오래된 오류가 모두 지워진다
    await dialog.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true }).fill('https://api.example.com');
    await dialog.getByPlaceholder('API 키를 입력하세요').fill('k-123');
    await dialog.getByRole('button', { name: '연결 테스트' }).click();
    await expect(dialog.getByText('연결 성공')).toBeVisible();
    await expect(dialog.getByRole('alert')).toHaveCount(0);
    await expect(dialog.getByPlaceholder('예: Make.com API')).not.toHaveAttribute('aria-invalid', 'true');
    expect(capture.requests).toHaveLength(1);
  });

  test('상세 화면 — 연결을 다시 불러와 폼이 새 값으로 채워지면 이전 값의 인라인 오류가 남지 않는다', async ({
    authenticatedPage: page,
  }) => {
    // 조회할 때마다 수정 시각·이름이 바뀐 연결을 돌려준다 — 저장 뒤 재조회가 폼을 실제로 다시 채우게 한다.
    // 첫 조회는 이름이 빈 연결이다(WD-777) — 재조회는 칸별 병합이라 고치지 않은 칸만 새 값을 받으므로, 오류도 「고치지 않은 칸」(서버가 준 빈 이름)에서 만든다.
    let fetchCount = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/api-connections/1',
      async (route) => {
        if (route.request().method() === 'GET') {
          fetchCount += 1;
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(
              createApiConnection({
                id: 1,
                name: fetchCount === 1 ? '' : `서버 이름 ${fetchCount}`,
                updatedAt: `2024-01-0${fetchCount}T00:00:00Z`,
              }),
            ),
          });
        }
        if (route.request().method() === 'PUT') {
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id: 1 }) });
        }
        return route.continue();
      },
    );
    await page.goto('/admin/api-connections/1');
    const nameInput = page.getByLabel(/^연결 이름/);
    await expect(nameInput).toHaveValue('');
    await expect(page.getByLabel('Base URL')).toHaveValue('https://api.example.com');

    // 기본 정보 오류(빈 이름)를 고치지 않은 채 저장해 만든다
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await expect(nameInput).toHaveAccessibleDescription('연결 이름을 입력하세요.');

    // 인증만 새 키로 저장 → 성공 뒤 재조회가 기본 정보 폼을 서버 값으로 다시 채운다
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    await page.getByPlaceholder('Authorization').fill('X-Key');
    await page.getByPlaceholder('새 API 키를 입력하세요').fill('new-key');
    await page.getByRole('button', { name: '인증 저장' }).click();
    // 저장 성공은 목록·상세 쿼리를 함께 무효화해 재조회가 한 번 이상 일어난다 — 첫 값이 아니기만 하면 된다
    await expect(nameInput).toHaveValue(/^서버 이름 [2-9]$/);

    // 채워진 이름 옆에 「연결 이름을 입력하세요.」가 남으면 안 된다
    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(nameInput).not.toHaveAttribute('aria-invalid', 'true');
  });

  test('상세 화면 — API_KEY 인데 저장된 키가 없으면 키 칸이 필수(*)로 보이고 유지 안내가 없으며, 비우면 키 칸 오류', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/api-connections/1',
      createApiConnection({ id: 1, authType: 'API_KEY', maskedAuthConfig: { placement: 'header', headerName: 'X-Key' } }),
    );
    const updateCapture = await mockApi(page, 'PUT', '/api/v1/api-connections/1', { id: 1 }, { capture: true });
    await page.goto('/admin/api-connections/1');
    await page.getByRole('button', { name: '인증 정보 변경' }).click();

    const keyInput = page.getByPlaceholder('새 API 키를 입력하세요');
    await expect(page.locator('label', { hasText: '키 값' })).toContainText('*');
    await expect(page.getByText('비워 두면 저장된 키를 그대로 사용합니다.')).toHaveCount(0);

    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect(keyInput).toHaveAccessibleDescription('API 키를 입력하세요.');
    await expect(keyInput).toBeFocused();
    expect(updateCapture.requests?.length ?? 0).toBe(0);
  });

  test('상세 화면 — 저장된 키가 있으면 키 칸은 * 가 없고, 유지 안내가 입력의 aria-describedby 로 이어진다', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionDetailMocks(page, 1);
    await page.goto('/admin/api-connections/1');
    await page.getByRole('button', { name: '인증 정보 변경' }).click();

    const keyInput = page.getByPlaceholder('새 API 키를 입력하세요');
    await expect(page.locator('label', { hasText: '키 값' })).not.toContainText('*');
    await expect(keyInput).toHaveAccessibleDescription('비워 두면 저장된 키를 그대로 사용합니다.');
  });

  test('상세 화면 — 필수·선택 표시가 생성 대화상자와 같다(필수 * · 선택 칸은 「(선택)」)', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionDetailMocks(page, 1);
    await page.goto('/admin/api-connections/1');
    await expect(page.getByRole('heading', { name: 'API 연결 상세' })).toBeVisible();

    await expect(page.locator('label', { hasText: '연결 이름' })).toContainText('*');
    await expect(page.locator('label', { hasText: 'Base URL' })).toContainText('*');
    // 선택 칸은 생성 대화상자처럼 라벨에 「(선택)」을 붙이고 * 는 없다
    await expect(page.getByLabel('설명 (선택)', { exact: true })).toBeVisible();
    await expect(page.getByLabel('헬스체크 경로 (선택)', { exact: true })).toBeVisible();

    // 인증 편집도 같다 — 헤더 이름·Bearer 토큰은 필수
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    await expect(page.locator('label', { hasText: '헤더 이름' })).toContainText('*');
    await page.getByRole('combobox').first().click();
    await page.getByRole('option', { name: 'Bearer Token' }).click();
    await expect(page.locator('label', { hasText: 'Bearer Token' })).toContainText('*');
  });
});

/**
 * BASIC 인증(아이디·비밀번호)과 baseUrl 사용자 정보 처리(WD-681).
 * - 만들기: BASIC 을 고르면 아이디·비밀번호 칸이 나오고, authConfig { username, password } 로 보낸다. 목록 배지는 「Basic」.
 * - 상세: 비밀번호 칸을 비워 두면 조회로 받은 가린 비밀번호를 그대로 보내 서버가 저장값을 유지한다(API 키와 같은 규칙).
 * - baseUrl 의 사용자 정보는 화면이 떼거나 막지 않는다 — 서버가 옮기고·가리고·거절한다. 화면은 서버 값·문구를 그대로 보인다.
 */
test.describe('API 연결 BASIC 인증 (WD-681)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  /** 생성 대화상자를 열고 이름·Base URL 을 채운 뒤 인증 유형을 Basic 으로 고른다. */
  async function openCreateWithBasic(page: Page) {
    await page.goto('/admin/api-connections');
    await page.getByRole('button', { name: '새 연결' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.getByPlaceholder('예: Make.com API').fill('Basic 연결');
    await page.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true }).fill('https://api.example.com');
    await page.getByLabel('인증 유형').click();
    await page.getByRole('option', { name: 'Basic Auth' }).click();
  }

  test('만들기 — Basic 을 고르면 아이디·비밀번호로 authConfig 를 보내고, 목록에 「Basic」 배지가 보인다', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionListMocks(page);
    // 목록에 BASIC 연결 하나를 더 둔다 — 배지 문구 확인용(나중에 등록한 route 가 먼저 쓰인다)
    await mockApi(page, 'GET', '/api/v1/api-connections', [
      ...createApiConnections(),
      createBasicApiConnection({ id: 3, name: '사내 Basic API' }),
    ]);
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/api-connections',
      createBasicApiConnection({ id: 30, name: 'Basic 연결' }),
      { capture: true },
    );

    await openCreateWithBasic(page);
    // API 키·토큰 칸 대신 아이디·비밀번호 칸이 보인다
    await expect(page.getByPlaceholder('API 키를 입력하세요')).toHaveCount(0);
    await expect(page.getByPlaceholder('토큰을 입력하세요')).toHaveCount(0);
    await page.getByLabel('아이디').fill('svc-user');
    const password = page.getByLabel('비밀번호');
    await expect(password).toHaveAttribute('type', 'password');
    await password.fill('p@ss:word');
    await page.getByRole('button', { name: '만들기' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({
      name: 'Basic 연결',
      authType: 'BASIC',
      baseUrl: 'https://api.example.com',
      authConfig: { username: 'svc-user', password: 'p@ss:word' },
    });
    // 다른 방식의 칸 값이 섞여 나가지 않는다
    expect((req.payload as { authConfig: Record<string, string> }).authConfig).not.toHaveProperty('apiKey');
    expect((req.payload as { authConfig: Record<string, string> }).authConfig).not.toHaveProperty('token');

    const row = page.getByRole('row').filter({ hasText: '사내 Basic API' });
    await expect(row.getByText('Basic', { exact: true })).toBeVisible();
  });

  test('만들기 — 아이디·비밀번호가 비면 각 칸에 인라인 오류, 아이디에 콜론이 있으면 막고 요청을 보내지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionListMocks(page);
    const capture = await mockApi(page, 'POST', '/api/v1/api-connections', {}, { capture: true });

    await openCreateWithBasic(page);
    await page.getByRole('button', { name: '만들기' }).click();
    const username = page.getByLabel('아이디');
    await expect(username).toHaveAccessibleDescription('아이디를 입력하세요.');
    await expect(page.getByLabel('비밀번호')).toHaveAccessibleDescription('비밀번호를 입력하세요.');
    await expect(username).toBeFocused();

    // 서버와 같은 규칙 — Basic 아이디에는 콜론을 쓸 수 없다
    await username.fill('a:b');
    await page.getByLabel('비밀번호').fill('secret');
    await page.getByRole('button', { name: '만들기' }).click();
    await expect(username).toHaveAccessibleDescription('아이디에 콜론(:)을 넣을 수 없습니다.');
    expect(capture.requests?.length ?? 0).toBe(0);
  });

  test('만들기 — 연결 테스트도 Basic 아이디·비밀번호를 실어 보낸다', async ({ authenticatedPage: page }) => {
    await setupApiConnectionListMocks(page);
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/api-connections/test',
      {
        ok: true,
        status: 200,
        latencyMs: 30,
        errorMessage: null,
        requestUrl: 'https://api.example.com',
        responseBodyPreview: null,
        responseHeaders: {},
        responseContentType: null,
      },
      { capture: true },
    );

    await openCreateWithBasic(page);
    await page.getByLabel('아이디').fill('svc-user');
    await page.getByLabel('비밀번호').fill('secret');
    await page.getByRole('button', { name: '연결 테스트' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({
      authType: 'BASIC',
      authConfig: { username: 'svc-user', password: 'secret' },
    });
    await expect(page.getByText('연결 성공')).toBeVisible();
  });

  test('상세 — BASIC 연결의 아이디·가린 비밀번호를 보이고, 비밀번호를 비운 채 저장하면 가린 값을 그대로 보내 유지한다', async ({
    authenticatedPage: page,
  }) => {
    await setupBasicApiConnectionDetailMocks(page, 1);
    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/api-connections/1',
      createBasicApiConnection({ id: 1 }),
      { capture: true },
    );

    await page.goto('/admin/api-connections/1');
    await expect(page.getByRole('heading', { name: 'API 연결 상세' })).toBeVisible();
    // 읽기 전용 보기 — 아이디는 그대로, 비밀번호는 서버가 가린 고정 값(끝 글자 없음, WD-681 리뷰)
    await expect(page.getByText('svc-user')).toBeVisible();
    await expect(page.getByText('****', { exact: true })).toBeVisible();

    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    const username = page.getByLabel('아이디');
    await expect(username).toHaveValue('svc-user');
    const password = page.getByLabel('비밀번호');
    await expect(password).toHaveValue('');
    await expect(password).toHaveAccessibleDescription('비워 두면 저장된 비밀번호를 그대로 사용합니다.');
    await username.fill('svc-user-2');
    await page.getByRole('button', { name: '인증 저장' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({
      authType: 'BASIC',
      // 비워 둔 비밀번호 칸은 조회로 받은 고정 가림 값을 그대로 보내 서버가 저장 원문을 유지한다
      authConfig: { username: 'svc-user-2', password: '****' },
    });
  });

  test('상세 — 비밀번호를 새로 입력하면 입력값을 보내고, API Key 연결을 Basic 으로 바꿀 때는 비밀번호가 필수다', async ({
    authenticatedPage: page,
  }) => {
    // 저장된 API 키 연결 — 유지할 비밀번호가 없다
    await setupApiConnectionDetailMocks(page, 1);
    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/api-connections/1',
      createBasicApiConnection({ id: 1 }),
      { capture: true },
    );

    await page.goto('/admin/api-connections/1');
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    await page.getByLabel('인증 유형').click();
    await page.getByRole('option', { name: 'Basic Auth' }).click();
    await expect(page.getByText('비워 두면 저장된 비밀번호를 그대로 사용합니다.')).toHaveCount(0);
    await page.getByLabel('아이디').fill('new-user');
    await page.getByRole('button', { name: '인증 저장' }).click();
    const password = page.getByLabel('비밀번호');
    await expect(password).toHaveAccessibleDescription('비밀번호를 입력하세요.');
    await expect(password).toBeFocused();
    expect(capture.requests?.length ?? 0).toBe(0);

    await password.fill('brand-new-pw');
    await page.getByRole('button', { name: '인증 저장' }).click();
    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({
      authType: 'BASIC',
      authConfig: { username: 'new-user', password: 'brand-new-pw' },
    });
    // 인증 저장은 baseUrl 을 보내지 않는다 — 옛 연결(baseUrl 사용자 정보)을 Basic 으로 바꾸면 서버가 입력한 아이디·비밀번호를 쓰고
    // 저장된 사용자 정보는 baseUrl 에서 떼기만 한다(WD-681 리뷰, 충돌 400 없음).
    expect(req.payload).not.toHaveProperty('baseUrl');
  });

  test('상세 — 서버가 가린 baseUrl(아이디:****@)을 그대로 보이고, 고치지 않고 저장하면 그 값을 그대로 보낸다', async ({
    authenticatedPage: page,
  }) => {
    const maskedBaseUrl = 'https://legacy-user:****@legacy.example.com/v1';
    await mockApi(
      page,
      'GET',
      '/api/v1/api-connections/1',
      createApiConnection({ id: 1, name: '옛 연결', baseUrl: maskedBaseUrl }),
    );
    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/api-connections/1',
      createApiConnection({ id: 1, name: '옛 연결 수정', baseUrl: maskedBaseUrl }),
      { capture: true },
    );

    await page.goto('/admin/api-connections/1');
    const baseUrlInput = page.getByLabel('Base URL');
    await expect(baseUrlInput).toHaveValue(maskedBaseUrl);
    // 다른 칸만 고쳐 저장 — 화면은 가린 baseUrl 을 막거나 고치지 않는다(서버가 저장된 원래 값을 유지)
    await page.getByLabel('연결 이름').fill('옛 연결 수정');
    await page.getByRole('button', { name: '저장', exact: true }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({ name: '옛 연결 수정', baseUrl: maskedBaseUrl });
    await expect(page.getByText('연결 정보가 업데이트되었습니다.')).toBeVisible();
  });

  test('상세 — API Key 연결의 baseUrl 에 사용자 정보를 넣어 저장하면 서버 400 안내를 그대로 보인다', async ({
    authenticatedPage: page,
  }) => {
    const message = 'baseUrl 에 사용자 정보(아이디:비밀번호@)를 넣으려면 인증 방식을 BASIC 으로 고르세요';
    await setupApiConnectionDetailMocks(page, 1);
    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/api-connections/1',
      { status: 400, error: 'Bad Request', message },
      { status: 400, capture: true },
    );

    await page.goto('/admin/api-connections/1');
    await page.getByLabel('Base URL').fill('https://user:pass@api.example.com');
    await page.getByRole('button', { name: '저장', exact: true }).click();

    const req = await capture.waitForRequest();
    // 화면은 사용자 정보를 떼거나 막지 않고 그대로 보낸다 — 판정은 서버가 한다
    expect(req.payload).toMatchObject({ baseUrl: 'https://user:pass@api.example.com' });
    await expect(page.getByText(message)).toBeVisible();
  });
});

/**
 * WD-753 — API 연결 추가 인증 헤더와 「점검 안 함」 배지.
 * 네이버 API HUB 처럼 인증 헤더가 둘 이상 필요한 API 를 연결 하나로 인증하도록, 연결 authConfig 에 `extraHeader:<이름>` 키로 비밀 헤더를 둔다.
 * 값은 비밀이라 화면에는 서버가 가린 값만 보이고, 편집에서 값을 비우면 가린 값을 그대로 되돌려 보내 저장값을 유지한다.
 */
test.describe('WD-753 추가 헤더', () => {
  const KEY_ID_HEADER = 'X-NCP-APIGW-API-KEY-ID';
  const EXTRA_KEY = `extraHeader:${KEY_ID_HEADER}`;

  /** 저장된 API 키·추가 헤더 하나를 가진 연결 — 서버는 두 값 모두 「****」+끝 4자로 가려 내려준다. */
  const connectionWithExtraHeader = () =>
    createApiConnection({
      id: 1,
      name: '네이버 API HUB',
      maskedAuthConfig: {
        placement: 'header',
        headerName: 'X-NCP-APIGW-API-KEY',
        apiKey: '****abcd',
        [EXTRA_KEY]: '****4567',
      },
    });

  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  /** 생성 대화상자를 열고 이름·Base URL·API 키 헤더 이름·키 값을 채운다. */
  async function openCreateFilled(page: Page) {
    await page.goto('/admin/api-connections');
    await page.getByRole('button', { name: '새 연결' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.getByPlaceholder('예: Make.com API').fill('네이버 API HUB');
    await page.getByPlaceholder(API_BASE_URL_PLACEHOLDER, { exact: true }).fill('https://naveropenapi.apigw.ntruss.com');
    await page.getByPlaceholder('Authorization').fill('X-NCP-APIGW-API-KEY');
    await page.getByPlaceholder('API 키를 입력하세요').fill('secret-key-abcd');
  }

  test('만들기 — 추가 헤더 이름·값을 authConfig 의 extraHeader: 키로 보낸다', async ({ authenticatedPage: page }) => {
    await setupApiConnectionListMocks(page);
    const capture = await mockApi(page, 'POST', '/api/v1/api-connections', connectionWithExtraHeader(), {
      capture: true,
    });

    await openCreateFilled(page);
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('추가 헤더', { exact: true })).toBeVisible();
    await expect(
      dialog.getByText(
        '인증 헤더가 둘 이상 필요한 API(예: 네이버 API HUB 키 ID)에 씁니다. 값은 암호화해 저장하고 화면에는 가려 보입니다.',
      ),
    ).toBeVisible();
    await dialog.getByRole('button', { name: '헤더 추가' }).click();
    await page.getByLabel('추가 헤더 이름 1').fill(KEY_ID_HEADER);
    const value = page.getByLabel('추가 헤더 값 1');
    // 비밀 값이라 가린 입력이다
    await expect(value).toHaveAttribute('type', 'password');
    await value.fill('keyid-4567');
    await page.getByRole('button', { name: '만들기' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({
      authType: 'API_KEY',
      authConfig: { headerName: 'X-NCP-APIGW-API-KEY', apiKey: 'secret-key-abcd', [EXTRA_KEY]: 'keyid-4567' },
    });
  });

  test('만들기 — 연결 테스트도 추가 헤더를 실어 보낸다', async ({ authenticatedPage: page }) => {
    await setupApiConnectionListMocks(page);
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/api-connections/test',
      {
        ok: true,
        status: 200,
        latencyMs: 30,
        errorMessage: null,
        requestUrl: 'https://naveropenapi.apigw.ntruss.com',
        responseBodyPreview: null,
        responseHeaders: {},
        responseContentType: null,
      },
      { capture: true },
    );

    await openCreateFilled(page);
    await page.getByRole('button', { name: '헤더 추가' }).click();
    await page.getByLabel('추가 헤더 이름 1').fill(KEY_ID_HEADER);
    await page.getByLabel('추가 헤더 값 1').fill('keyid-4567');
    await page.getByRole('button', { name: '연결 테스트' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({ authConfig: { [EXTRA_KEY]: 'keyid-4567' } });
  });

  test('만들기 — 이름 없이 값만 넣거나 값이 비거나 이름이 겹치면 행에 오류를 달고 보내지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionListMocks(page);
    const capture = await mockApi(page, 'POST', '/api/v1/api-connections', {}, { capture: true });

    await openCreateFilled(page);
    const add = page.getByRole('button', { name: '헤더 추가' });
    await add.click();
    await page.getByLabel('추가 헤더 값 1').fill('value-only');
    await page.getByRole('button', { name: '만들기' }).click();
    const name1 = page.getByLabel('추가 헤더 이름 1');
    await expect(name1).toHaveAccessibleDescription('헤더 이름을 입력하세요');
    await expect(name1).toBeFocused();

    await name1.fill('X-A');
    await add.click();
    await page.getByLabel('추가 헤더 이름 2').fill('x-a');
    await page.getByRole('button', { name: '만들기' }).click();
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveAccessibleDescription('이름이 겹칩니다');

    await page.getByLabel('추가 헤더 이름 2').fill('X-B');
    await page.getByRole('button', { name: '만들기' }).click();
    const value2 = page.getByLabel('추가 헤더 값 2');
    await expect(value2).toHaveAccessibleDescription('값을 입력하세요');
    await expect(value2).toBeFocused();
    expect(capture.requests?.length ?? 0).toBe(0);
  });

  test('만들기 — 한글 값은 행에 ASCII 오류를 달고 값 칸으로 포커스하며 보내지 않고, 탭이 든 값은 그대로 보낸다 (WD-780)', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionListMocks(page);
    const capture = await mockApi(page, 'POST', '/api/v1/api-connections', connectionWithExtraHeader(), {
      capture: true,
    });

    await openCreateFilled(page);
    await page.getByRole('button', { name: '헤더 추가' }).click();
    await page.getByLabel('추가 헤더 이름 1').fill(KEY_ID_HEADER);
    const value = page.getByLabel('추가 헤더 값 1');
    // 서버(AuthConfigValidator)가 400 으로 거절할 값 — 화면이 먼저 그 행에 오류를 달고 값 칸으로 포커스한다
    await value.fill('키아이디-4567');
    await page.getByRole('button', { name: '만들기' }).click();
    await expect(value).toHaveAccessibleDescription('영문·숫자·기호(ASCII)만 쓸 수 있습니다');
    await expect(value).toBeFocused();
    expect(capture.requests?.length ?? 0).toBe(0);

    // 값 가운데 탭은 HTTP 헤더 값으로 보낼 수 있어 서버처럼 통과한다
    await value.fill('keyid\t4567');
    await expect(value).not.toHaveAccessibleDescription('영문·숫자·기호(ASCII)만 쓸 수 있습니다');
    await page.getByRole('button', { name: '만들기' }).click();
    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({ authConfig: { [EXTRA_KEY]: 'keyid\t4567' } });
  });

  test('만들기 — 추가 헤더 행은 10개까지, 빈 행도 세어 10행이면 「헤더 추가」가 꺼진다 (WD-804 반려)', async ({ authenticatedPage: page }) => {
    await setupApiConnectionListMocks(page);
    await openCreateFilled(page);
    const add = page.getByRole('button', { name: '헤더 추가' });
    // 「헤더 추가」는 빈 행까지 센 행 수로 꺼진다 — 빈 행을 끝없이 더하지 못하게 한다(저장 검사는 보낼 행 수로 본다)
    for (let i = 1; i <= 9; i++) {
      await add.click();
      await page.getByLabel(`추가 헤더 이름 ${i}`).fill(`X-H${i}`);
    }
    await expect(add).toBeEnabled();
    // 빈 행 1개를 더해 10행이 되면 이름이 비어 있어도 꺼진다
    await add.click();
    await expect(page.getByLabel('추가 헤더 이름 10')).toHaveValue('');
    await expect(add).toBeDisabled();
    // 빈 행을 지우면 다시 켜진다
    await page.getByRole('button', { name: '추가 헤더 10 삭제' }).click();
    await expect(add).toBeEnabled();
  });

  test('상세 — 읽기 보기에 추가 헤더 이름과 가린 값만 보이고 원문은 없다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/api-connections/1', connectionWithExtraHeader());
    await page.goto('/admin/api-connections/1');
    await expect(page.getByRole('heading', { name: 'API 연결 상세' })).toBeVisible();
    await expect(page.getByText('추가 헤더', { exact: true })).toBeVisible();
    await expect(page.getByText(`${KEY_ID_HEADER}: ****4567`)).toBeVisible();
    await expect(page.getByText('keyid-4567')).toHaveCount(0);
    // 추가 헤더 키가 메인 인증 칸(헤더 이름·키 값)으로 새지 않는다
    await expect(page.getByText('X-NCP-APIGW-API-KEY', { exact: true })).toBeVisible();
    await expect(page.getByText('****abcd', { exact: true })).toBeVisible();
  });

  test('상세 — 값을 비운 채 저장하면 가린 값을 그대로 보내 저장값을 유지한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/api-connections/1', connectionWithExtraHeader());
    const capture = await mockApi(page, 'PUT', '/api/v1/api-connections/1', connectionWithExtraHeader(), {
      capture: true,
    });

    await page.goto('/admin/api-connections/1');
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    await expect(page.getByLabel('추가 헤더 이름 1')).toHaveValue(KEY_ID_HEADER);
    const value = page.getByLabel('추가 헤더 값 1');
    await expect(value).toHaveValue('');
    await expect(value).toHaveAttribute('placeholder', '저장된 값 유지 (****4567)');
    await page.getByRole('button', { name: '인증 저장' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({
      authType: 'API_KEY',
      authConfig: { apiKey: '****abcd', [EXTRA_KEY]: '****4567' },
    });
  });

  test('상세 — 대소문자가 다른 접두어(ExtraHeader:)로 저장된 추가 헤더도 행으로 보이고, 인증 저장 payload 에 남는다 (WD-772 리뷰)', async ({
    authenticatedPage: page,
  }) => {
    // AI 챗·API 로 접두어 대소문자를 달리 넣어 저장된 연결 — 서버는 이 키도 추가 헤더로 보내고 가린다(접두어 대소문자 무시).
    const legacy = createApiConnection({
      id: 1,
      name: '네이버 API HUB',
      maskedAuthConfig: {
        placement: 'header',
        headerName: 'X-NCP-APIGW-API-KEY',
        apiKey: '****abcd',
        [`ExtraHeader:${KEY_ID_HEADER}`]: '****4567',
      },
    });
    await mockApi(page, 'GET', '/api/v1/api-connections/1', legacy);
    const capture = await mockApi(page, 'PUT', '/api/v1/api-connections/1', legacy, { capture: true });

    await page.goto('/admin/api-connections/1');
    // 읽기 보기에 추가 헤더로 보인다
    await expect(page.getByText(`${KEY_ID_HEADER}: ****4567`)).toBeVisible();
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    await expect(page.getByLabel('추가 헤더 이름 1')).toHaveValue(KEY_ID_HEADER);
    await expect(page.getByLabel('추가 헤더 값 1')).toHaveAttribute('placeholder', '저장된 값 유지 (****4567)');
    await page.getByRole('button', { name: '인증 저장' }).click();

    // 정식 접두어 키로 가린 값을 보내 서버가 저장값을 유지한다 — 예전에는 행이 없어 authConfig 에서 빠져 조용히 지워졌다
    const req = await capture.waitForRequest();
    const payload = req.payload as { authConfig: Record<string, string> };
    // 대소문자 다른 옛 키가 메인 인증 칸 값으로 섞여 함께 나가지도 않는다(키 전체를 비교)
    expect(payload.authConfig).toEqual({
      authType: 'API_KEY',
      placement: 'header',
      headerName: 'X-NCP-APIGW-API-KEY',
      apiKey: '****abcd',
      [EXTRA_KEY]: '****4567',
    });
  });

  test('상세 — 새 값을 넣으면 그 값을, 행을 지우면 키 없이 보낸다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/api-connections/1', connectionWithExtraHeader());
    const capture = await mockApi(page, 'PUT', '/api/v1/api-connections/1', connectionWithExtraHeader(), {
      capture: true,
    });

    await page.goto('/admin/api-connections/1');
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    await page.getByLabel('추가 헤더 값 1').fill('keyid-9999');
    await page.getByRole('button', { name: '인증 저장' }).click();
    const first = await capture.waitForRequest();
    expect(first.payload).toMatchObject({ authConfig: { [EXTRA_KEY]: 'keyid-9999' } });

    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    await page.getByRole('button', { name: '추가 헤더 1 삭제' }).click();
    await expect(page.getByLabel('추가 헤더 이름 1')).toHaveCount(0);
    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect.poll(() => capture.requests.length).toBe(2);
    const second = capture.requests[1].payload as { authConfig: Record<string, string> };
    expect(second.authConfig).not.toHaveProperty(EXTRA_KEY);
    expect(second.authConfig).toMatchObject({ apiKey: '****abcd' });
  });

  test('상세 — 저장된 헤더의 이름을 바꾸면 새 헤더라 저장값 유지가 풀리고 새 값을 요구한다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/api-connections/1', connectionWithExtraHeader());
    const capture = await mockApi(page, 'PUT', '/api/v1/api-connections/1', connectionWithExtraHeader(), {
      capture: true,
    });

    await page.goto('/admin/api-connections/1');
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    await page.getByLabel('추가 헤더 이름 1').fill('X-Renamed');
    const value = page.getByLabel('추가 헤더 값 1');
    // 가린 값은 원래 이름의 저장값이라 새 이름으로 되돌릴 수 없다 — 유지 자리표시가 사라진다
    await expect(value).toHaveAttribute('placeholder', '값을 입력하세요');
    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect(value).toHaveAccessibleDescription('값을 입력하세요');
    await expect(value).toBeFocused();
    expect(capture.requests.length).toBe(0);
  });

  test('상세 — 바꾼 이름을 원래 이름(앞뒤 공백 무시)으로 되돌리면 다시 「저장된 값 유지」가 되고 가린 값을 보낸다 (WD-753 리뷰)', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/api-connections/1', connectionWithExtraHeader());
    const capture = await mockApi(page, 'PUT', '/api/v1/api-connections/1', connectionWithExtraHeader(), {
      capture: true,
    });

    await page.goto('/admin/api-connections/1');
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    const name = page.getByLabel('추가 헤더 이름 1');
    const value = page.getByLabel('추가 헤더 값 1');
    await name.fill('X-Renamed');
    await expect(value).toHaveAttribute('placeholder', '값을 입력하세요');
    // 원래 이름으로 되돌린다 — 앞뒤 공백은 서버도 떼므로 같은 헤더다
    await name.fill(` ${KEY_ID_HEADER} `);
    await expect(value).toHaveAttribute('placeholder', '저장된 값 유지 (****4567)');
    await page.getByRole('button', { name: '인증 저장' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({ authConfig: { [EXTRA_KEY]: '****4567' } });
  });

  test('상세 — 인증 편집 중 기본 정보를 저장해 연결이 다시 불러와져도 고치던 추가 헤더 행이 남는다 (WD-753 리뷰)', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/api-connections/1', connectionWithExtraHeader());
    await page.goto('/admin/api-connections/1');
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    await page.getByLabel('추가 헤더 값 1').fill('keyid-new-1');
    await page.getByRole('button', { name: '헤더 추가' }).click();
    await page.getByLabel('추가 헤더 이름 2').fill('X-Tenant');
    await page.getByLabel('추가 헤더 값 2').fill('tenant-1');

    // 기본 정보 저장 뒤 다시 불러온 연결은 이름만 바뀌었다(추가 헤더 저장값은 그대로)
    const capture = await saveInfoAndRefetch(page, { ...connectionWithExtraHeader(), name: '네이버 API HUB 2' });

    // 편집하던 행은 저장값으로 덮이지 않는다
    await expect(page.getByLabel('추가 헤더 이름 1')).toHaveValue(KEY_ID_HEADER);
    await expect(page.getByLabel('추가 헤더 값 1')).toHaveValue('keyid-new-1');
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveValue('X-Tenant');
    await expect(page.getByLabel('추가 헤더 값 2')).toHaveValue('tenant-1');

    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect.poll(() => capture.requests.length).toBe(2);
    expect(capture.requests[1].payload).toMatchObject({
      authConfig: { [EXTRA_KEY]: 'keyid-new-1', 'extraHeader:X-Tenant': 'tenant-1' },
    });
  });

  test('목록 — 헬스체크 경로가 없으면 상태와 상관없이 「점검 안 함」, 경로가 있고 상태가 없으면 「미확인」', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 1024, height: 800 });
    await setupApiConnectionListMocks(page);
    await mockApi(page, 'GET', '/api/v1/api-connections', [
      createApiConnection({ id: 1, name: '점검 없는 연결', healthCheckPath: null, lastStatus: null, lastCheckedAt: null }),
      createApiConnection({ id: 2, name: '아직 안 본 연결', lastStatus: null, lastCheckedAt: null }),
      // 경로를 비우기 전 점검 결과(이상)가 남은 연결 — 점검하지 않으니 지난 「이상」 대신 「점검 안 함」(WD-753 리뷰)
      createApiConnection({ id: 3, name: '경로 비운 연결', healthCheckPath: null, lastStatus: 'DOWN' }),
    ]);
    await page.goto('/admin/api-connections');

    const noCheckRow = page.getByRole('row').filter({ hasText: '점검 없는 연결' });
    const badge = noCheckRow.getByText('점검 안 함', { exact: true });
    await expect(badge).toBeVisible();
    await expect(badge).toHaveAttribute('title', '헬스체크 경로가 없어 자동 점검하지 않습니다');
    // 상태 칸 선언 폭(--k) 안에 배지가 들어가야 표가 넘치지 않는다(WD-650 규칙)
    await expectBadgesFitDeclaredWidth(
      noCheckRow.getByRole('cell').filter({ has: page.getByText('점검 안 함', { exact: true }) }),
    );
    await expectKMatchesDeclaredWidths(badge);
    const pendingRow = page.getByRole('row').filter({ hasText: '아직 안 본 연결' });
    await expect(pendingRow.getByText('미확인', { exact: true })).toBeVisible();
    const clearedRow = page.getByRole('row').filter({ hasText: '경로 비운 연결' });
    await expect(clearedRow.getByText('점검 안 함', { exact: true })).toBeVisible();
    await expect(clearedRow.getByText('이상', { exact: true })).toHaveCount(0);
  });

  test('상세 — 헬스체크 경로가 없으면 지난 상태(이상)가 있어도 「점검 안 함」 배지를 보인다', async ({ authenticatedPage: page }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/api-connections/1',
      createApiConnection({ id: 1, healthCheckPath: null, lastStatus: 'DOWN', lastLatencyMs: null }),
    );
    await page.goto('/admin/api-connections/1');
    const badge = page.getByText('점검 안 함', { exact: true });
    await expect(badge).toBeVisible();
    await expect(badge).toHaveAttribute('title', '헬스체크 경로가 없어 자동 점검하지 않습니다');
    await expect(page.getByText('미확인', { exact: true })).toHaveCount(0);
    await expect(page.getByText('이상', { exact: true })).toHaveCount(0);
  });
});

/**
 * WD-763 — 인증 편집 중 연결이 다시 불러와져도(기본 정보·인증 저장 뒤 무효화, 마운트 뒤 staleTime 이 지난 배경 재조회) 고친 메인 인증 칸을 저장값으로 덮지 않는다.
 * 고치지 않은 칸은 재조회 값을 받고(옛 값에 고정돼 되써 넣지 않게), 메인 인증 칸만 고쳐도 미저장 경고가 뜨며, 다른 연결로 옮기면 편집을 닫는다(리뷰 후속).
 * 예전엔 인증 방식·키 위치·헤더 이름·파라미터 이름·Basic 아이디를 재조회 때마다 저장값으로 덮어,
 * 그대로 「인증 저장」을 누르면 고치던 값이 아니라 원래 값이 저장됐다(WD-753 은 추가 헤더 행만 지켰다).
 */
test.describe('WD-763 인증 편집 중 재조회', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  /** 기본 정보 저장으로 다시 불러올 때 쓰는 새 이름 — 다시 불러온 연결은 이름만 바뀐 같은 연결이다 */
  const RENAMED = '이름만 바뀐 연결';

  /** 헤더 X-Old·가린 키 ****abcd 로 저장된 API_KEY 연결 */
  function storedApiKeyConnection(headerName = 'X-Old', apiKey = '****abcd') {
    return createApiConnection({ id: 1, maskedAuthConfig: { placement: 'header', headerName, apiKey } });
  }

  /** 저장된 연결(기본: storedApiKeyConnection)을 모킹하고 상세를 연 뒤 「인증 정보 변경」으로 편집을 연다. */
  async function openAuthEdit(page: Page, stored: ApiConnectionResponse = storedApiKeyConnection()) {
    await mockApi(page, 'GET', '/api/v1/api-connections/1', stored);
    await page.goto('/admin/api-connections/1');
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    return stored;
  }

  /** 「인증 저장」을 누르고, PUT 캡처의 두 번째 요청(첫 요청은 앞선 저장) payload 가 expected 를 담는지 본다. */
  async function saveAuthAndExpectPayload(page: Page, capture: MockApiCapture, expected: Record<string, unknown>) {
    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect.poll(() => capture.requests.length).toBe(2);
    expect(capture.requests[1].payload).toMatchObject(expected);
  }

  test('API_KEY — 헤더 이름을 고치던 중 재조회돼도 그 값이 남고, 인증 저장 payload 에도 고친 값이 실린다', async ({
    authenticatedPage: page,
  }) => {
    const stored = await openAuthEdit(page);
    await expect(page.getByPlaceholder('Authorization')).toHaveValue('X-Old');
    await page.getByPlaceholder('Authorization').fill('X-New');

    const capture = await saveInfoAndRefetch(page, { ...stored, name: RENAMED });

    // 재조회 뒤에도 고치던 헤더 이름이 저장값(X-Old)으로 돌아가지 않는다
    await expect(page.getByPlaceholder('Authorization')).toHaveValue('X-New');
    await saveAuthAndExpectPayload(page, capture, {
      authType: 'API_KEY',
      // 키 칸은 비워 뒀으니 가린 값을 그대로 보내 저장 키를 유지한다
      authConfig: { headerName: 'X-New', apiKey: '****abcd' },
    });
  });

  test('API_KEY — 키 위치를 Query 로 바꾸고 파라미터 이름을 고치던 중 재조회돼도 둘 다 남는다', async ({
    authenticatedPage: page,
  }) => {
    const stored = await openAuthEdit(page);
    await page.getByLabel('위치', { exact: true }).click();
    await page.getByRole('option', { name: 'Query Parameter' }).click();
    await page.getByPlaceholder('api_key').fill('my_param');

    const capture = await saveInfoAndRefetch(page, { ...stored, name: RENAMED });

    // 위치가 header 로 되돌아가 파라미터 칸이 사라지면 안 된다
    await expect(page.getByPlaceholder('api_key')).toHaveValue('my_param');
    await saveAuthAndExpectPayload(page, capture, {
      authType: 'API_KEY',
      authConfig: { placement: 'query', paramName: 'my_param' },
    });
  });

  test('BASIC — 아이디를 고치던 중 재조회돼도 그 값이 남고, 인증 저장 payload 에 실린다', async ({
    authenticatedPage: page,
  }) => {
    const stored = await openAuthEdit(page, createBasicApiConnection({ id: 1 }));
    await expect(page.getByLabel('아이디')).toHaveValue('svc-user');
    await page.getByLabel('아이디').fill('svc-user-2');

    const capture = await saveInfoAndRefetch(page, { ...stored, name: RENAMED });

    await expect(page.getByLabel('아이디')).toHaveValue('svc-user-2');
    await saveAuthAndExpectPayload(page, capture, {
      authType: 'BASIC',
      authConfig: { username: 'svc-user-2', password: '****' },
    });
  });

  test('인증 방식을 API_KEY → BEARER 로 바꾸고 토큰을 넣던 중 재조회돼도 방식이 유지되고 BEARER 로 저장된다', async ({
    authenticatedPage: page,
  }) => {
    const stored = await openAuthEdit(page);
    await page.getByLabel('인증 유형', { exact: true }).click();
    await page.getByRole('option', { name: 'Bearer Token' }).click();
    await page.getByPlaceholder('새 토큰을 입력하세요').fill('my-bearer-token-xyz');

    const capture = await saveInfoAndRefetch(page, { ...stored, name: RENAMED });

    // 방식이 저장값(API_KEY)으로 되돌아가 토큰 칸이 사라지면 안 된다
    await expect(page.getByPlaceholder('새 토큰을 입력하세요')).toHaveValue('my-bearer-token-xyz');
    await saveAuthAndExpectPayload(page, capture, {
      authType: 'BEARER',
      authConfig: { token: 'my-bearer-token-xyz' },
    });
  });

  test('인증 편집을 취소하면 고치던 값이 저장값으로 돌아가고, 닫힌 뒤 재조회는 저장값을 반영한다', async ({
    authenticatedPage: page,
  }) => {
    await openAuthEdit(page);
    await page.getByPlaceholder('Authorization').fill('X-New');
    await page.getByRole('button', { name: '취소' }).click();

    // 편집을 닫은 뒤 재조회로 서버 값이 X-Changed 로 바뀌면 다시 열었을 때 그 저장값이 보인다
    await saveInfoAndRefetch(page, { ...storedApiKeyConnection('X-Changed'), name: RENAMED });

    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    await expect(page.getByPlaceholder('Authorization')).toHaveValue('X-Changed');
  });

  test('인증 저장 뒤 재조회 응답이 오기 전에 편집을 다시 열어도, 응답이 오면 칸이 새 저장값으로 바뀌고 다시 저장해도 옛 값을 되써 넣지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const saved = storedApiKeyConnection('X-New');
    await openAuthEdit(page);
    await page.getByPlaceholder('Authorization').fill('X-New');

    // 인증 저장 뒤 무효화로 오는 재조회 GET 을 붙잡아 둔다 — 저장 직후 편집을 다시 여는 틈을 만든다
    const capture = await mockApi(page, 'PUT', '/api/v1/api-connections/1', saved, { capture: true });
    const refetch = await holdApi(page, 'GET', '/api/v1/api-connections/1', 200, saved);
    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect(page.getByText('인증 정보가 업데이트되었습니다.')).toBeVisible();
    await expect.poll(() => refetch.requests.length).toBeGreaterThan(0);

    // 재조회가 아직 안 와 다시 연 편집은 옛 저장값(X-Old)으로 시작한다 — 고치지 않은 채로 둔다
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    await expect(page.getByPlaceholder('Authorization')).toHaveValue('X-Old');

    // 재조회 응답이 오면 고치지 않은 칸은 새 저장값을 받는다
    refetch.release();
    await expect(page.getByPlaceholder('Authorization')).toHaveValue('X-New');

    // 그대로 다시 저장하면 새 저장값이 실린다(옛 값 X-Old 를 되써 넣지 않는다)
    await saveAuthAndExpectPayload(page, capture, {
      authType: 'API_KEY',
      authConfig: { headerName: 'X-New', apiKey: '****abcd' },
    });
  });

  test('편집 중 아무것도 고치지 않았으면, 기본 정보 저장 재조회가 가져온 바뀐 인증 값(다른 관리자가 바꾼 헤더 이름)으로 칸이 바뀐다', async ({
    authenticatedPage: page,
  }) => {
    await openAuthEdit(page);
    await expect(page.getByPlaceholder('Authorization')).toHaveValue('X-Old');

    // 그사이 다른 관리자가 헤더 이름을 X-Other 로 바꿨다 — 기본 정보 저장 뒤 재조회가 그 값을 가져온다
    const capture = await saveInfoAndRefetch(page, { ...storedApiKeyConnection('X-Other', '****wxyz'), name: RENAMED });

    // 편집은 열린 채, 고치지 않은 칸은 서버의 새 값으로 바뀐다
    await expect(page.getByRole('button', { name: '인증 저장' })).toBeVisible();
    await expect(page.getByPlaceholder('Authorization')).toHaveValue('X-Other');
    // 키 칸을 비워 두면 새로 받은 연결의 가린 키로 유지한다
    await saveAuthAndExpectPayload(page, capture, {
      authType: 'API_KEY',
      authConfig: { headerName: 'X-Other', apiKey: '****wxyz' },
    });
  });

  test('인증 편집에서 헤더 이름만 고치고 사이드바로 나가려 하면 미저장 경고가 뜬다', async ({
    authenticatedPage: page,
  }) => {
    await openAuthEdit(page);
    await page.getByPlaceholder('Authorization').fill('X-New');

    await page.getByRole('navigation').getByRole('link', { name: '사용자 관리' }).click();

    await expect(page.getByRole('alertdialog')).toBeVisible();
    await expect(page.getByText('저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?')).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/admin/api-connections/1');
    // 남기를 고르면 고치던 값이 그대로다
    await page.getByRole('button', { name: '취소' }).click();
    await expect(page.getByPlaceholder('Authorization')).toHaveValue('X-New');
  });

  test('인증 편집 중 앱 안에서 다른 연결 주소로 옮기면 편집이 닫히고, 입력하던 키가 새 연결에 남지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const first = createApiConnection({
      id: 1,
      name: '첫 번째 연결',
      maskedAuthConfig: { placement: 'header', headerName: 'X-First', apiKey: '****abcd' },
    });
    const second = createApiConnection({
      id: 2,
      name: '두 번째 연결',
      maskedAuthConfig: { placement: 'header', headerName: 'X-Second', apiKey: '****wxyz' },
    });
    await mockApi(page, 'GET', '/api/v1/api-connections/1', first);
    await mockApi(page, 'GET', '/api/v1/api-connections/2', second);
    await page.goto('/admin/api-connections/1');
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    await page.getByLabel('키 값').fill('secret-for-first');

    // 라우트 요소는 :id 로 key 되지 않는다 — 페이지가 본문을 새 id 로 다시 마운트하는지, 앱 안 링크(검색·AI 패널 등)로 옮기는 경우를 흉내 내 본다
    await page.evaluate(() => {
      const link = document.createElement('a');
      link.href = '/admin/api-connections/2';
      link.textContent = '두 번째 연결로';
      document.body.appendChild(link);
    });
    await page.getByRole('link', { name: '두 번째 연결로' }).click();
    // 키를 입력 중이라 미저장 경고가 먼저 뜬다 — 이탈을 고른다
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await page.getByRole('button', { name: '이탈' }).click();

    await expect(page).toHaveURL(/\/admin\/api-connections\/2$/);
    await expect(page.getByLabel('연결 이름')).toHaveValue('두 번째 연결');
    // 편집이 닫혀 읽기 보기로 돌아온다
    await expect(page.getByRole('button', { name: '인증 정보 변경' })).toBeVisible();
    await expect(page.getByRole('button', { name: '인증 저장' })).toHaveCount(0);
    // 다시 열면 새 연결 값으로 시작하고, 첫 연결용으로 입력하던 키는 비어 있다
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    await expect(page.getByPlaceholder('Authorization')).toHaveValue('X-Second');
    await expect(page.getByLabel('키 값')).toHaveValue('');
  });
});

/**
 * WD-777 — 재조회 때 기본 정보·인증 칸을 칸별로 병합한다(3-way: 지금 값 == 이전 기준이면 새 값, 다르면 내 값).
 * 기준(스냅샷)은 늘 마지막으로 받은 서버 응답이라, 옛 가린 키를 보내지 않고, 추가 헤더는 내용으로 비교하며, 바뀐 칸 오류만 지운다.
 * 예전엔 기본 정보를 재조회마다 통째로 덮고, 인증은 「하나라도 고쳤으면 전체 보존」이라 고치지 않은 칸의 새 값·새 가린 키를 받지 못했다.
 */
test.describe('WD-777 재조회 칸별 병합', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  const PATH = '/api/v1/api-connections/1';
  /** 기본 정보 저장으로 다시 불러올 때 쓰는 새 이름 */
  const RENAMED = '이름만 바뀐 연결';
  /** 다른 관리자가 그사이 더한 추가 헤더(가린 값) */
  const TENANT_HEADER = { 'extraHeader:X-Tenant': '****t001' };

  /** 헤더 headerName·가린 키 apiKey 로 저장된 API_KEY 연결 */
  function storedApiKeyConnection(headerName = 'X-Old', apiKey = '****abcd', extra: Record<string, string> = {}) {
    return createApiConnection({ id: 1, maskedAuthConfig: { placement: 'header', headerName, apiKey, ...extra } });
  }

  /** 저장된 연결을 모킹하고 상세를 연 뒤 「인증 정보 변경」으로 편집을 연다. */
  async function openAuthEdit(page: Page, stored: ApiConnectionResponse = storedApiKeyConnection()) {
    await mockApi(page, 'GET', PATH, stored);
    await page.goto('/admin/api-connections/1');
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    return stored;
  }

  test('설명을 고친 채 인증 저장 → 재조회 뒤에도 설명은 내 값·미저장 경고, 고치지 않은 이름은 새 값', async ({
    authenticatedPage: page,
  }) => {
    const stored = await openAuthEdit(page);
    await page.getByLabel('설명 (선택)', { exact: true }).fill('저장 안 한 설명');

    // 인증 저장 뒤 재조회가 다른 관리자가 바꾼 이름을 가져온다(설명은 서버 값 그대로)
    const refetched = { ...stored, name: '다른 관리자가 바꾼 이름' };
    await mockApi(page, 'GET', PATH, refetched);
    await mockApi(page, 'PUT', PATH, refetched);
    const refetchedResponse = waitForRefetch(page, PATH);
    await page.getByRole('button', { name: '인증 저장' }).click();
    await refetchedResponse;
    await expect(page.getByText('인증 정보가 업데이트되었습니다.')).toBeVisible();

    // 고치지 않은 이름은 새 값을 받고, 고친 설명은 내 값이 남는다
    await expect(page.getByLabel('연결 이름')).toHaveValue('다른 관리자가 바꾼 이름');
    await expect(page.getByLabel('설명 (선택)', { exact: true })).toHaveValue('저장 안 한 설명');
    // 설명은 여전히 저장 안 한 변경 — 나가려 하면 경고가 뜬다
    await page.getByRole('navigation').getByRole('link', { name: '사용자 관리' }).click();
    await expect(page.getByRole('alertdialog')).toBeVisible();
  });

  test('새 키 저장 뒤 재조회가 늦을 때 편집을 열고 헤더 이름만 바꿔도, 재조회가 오면 저장 payload 가 새 가린 키를 보낸다', async ({
    authenticatedPage: page,
  }) => {
    // 첫 저장 키는 ****abcd, 새 키 저장 뒤 서버는 ****wxyz 로 가려 내려준다
    const saved = storedApiKeyConnection('X-Old', '****wxyz');
    await openAuthEdit(page);
    await page.getByLabel('키 값').fill('brand-new-key-wxyz');

    const capture = await mockApi(page, 'PUT', PATH, saved, { capture: true });
    const refetch = await holdApi(page, 'GET', PATH, 200, saved);
    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect(page.getByText('인증 정보가 업데이트되었습니다.')).toBeVisible();
    await expect.poll(() => refetch.requests.length).toBeGreaterThan(0);

    // 재조회 전에 다시 연 편집에서 헤더 이름만 바꾼다(옛 응답 기준)
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    await page.getByPlaceholder('Authorization').fill('X-New');

    const refetchedResponse = waitForRefetch(page, PATH);
    refetch.release();
    await refetchedResponse;
    // 고친 헤더 이름은 남는다
    await expect(page.getByPlaceholder('Authorization')).toHaveValue('X-New');

    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect.poll(() => capture.requests.length).toBe(2);
    // 키 칸을 비웠으니 새 응답의 가린 키(****wxyz)를 보낸다 — 옛 ****abcd 를 보내면 서버가 400 으로 거절한다
    expect(capture.requests[1].payload).toMatchObject({
      authType: 'API_KEY',
      authConfig: { headerName: 'X-New', apiKey: '****wxyz' },
    });
  });

  test('헤더 추가 → 빈 행 삭제 뒤 다른 추가 헤더로 재조회되면 추가 헤더가 새 값으로 바뀐다', async ({
    authenticatedPage: page,
  }) => {
    const stored = await openAuthEdit(page);
    await page.getByRole('button', { name: '헤더 추가' }).click();
    await page.getByRole('button', { name: '추가 헤더 1 삭제' }).click();
    await expect(page.getByLabel('추가 헤더 이름 1')).toHaveCount(0);

    await saveInfoAndRefetch(page, {
      ...stored,
      name: RENAMED,
      maskedAuthConfig: { ...stored.maskedAuthConfig, ...TENANT_HEADER },
    });

    // 행 내용이 처음과 같으니(빈 목록) 고치지 않은 것 — 새 추가 헤더를 받는다
    await expect(page.getByLabel('추가 헤더 이름 1')).toHaveValue('X-Tenant');
  });

  test('헤더 이름을 고치는 중 재조회되면 헤더 이름은 내 값, 다른 칸은 새 값이고, 헤더 이름을 되돌려도 다시 채우지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await openAuthEdit(page);
    await page.getByPlaceholder('Authorization').fill('X-New');

    // 서버가 헤더 이름과 추가 헤더를 바꿨다
    await saveInfoAndRefetch(page, { ...storedApiKeyConnection('X-Server', '****abcd', TENANT_HEADER), name: RENAMED });

    await expect(page.getByPlaceholder('Authorization')).toHaveValue('X-New');
    await expect(page.getByLabel('추가 헤더 이름 1')).toHaveValue('X-Tenant');

    // 추가 헤더 값을 입력한 뒤 헤더 이름을 처음 값으로 되돌린다 — 되돌린 순간 칸을 다시 채우지 않는다
    await page.getByLabel('추가 헤더 값 1').fill('tenant-typed');
    await page.getByPlaceholder('Authorization').fill('X-Old');
    await expect(page.getByPlaceholder('Authorization')).toHaveValue('X-Old');
    await expect(page.getByLabel('추가 헤더 값 1')).toHaveValue('tenant-typed');
    // 지금 기준 값(재조회로 온 X-Server)으로 되돌려도 마찬가지다(WD-777 리뷰) — 헤더 이름이 기준과 같아진 순간에도 다시 채우지 않는다
    await page.getByPlaceholder('Authorization').fill('X-Server');
    await expect(page.getByPlaceholder('Authorization')).toHaveValue('X-Server');
    await expect(page.getByLabel('추가 헤더 이름 1')).toHaveValue('X-Tenant');
    await expect(page.getByLabel('추가 헤더 값 1')).toHaveValue('tenant-typed');
  });

  test('추가 헤더 행을 하나 더한 채 재조회돼도, 손대지 않은 저장 헤더는 새 가린 값으로 저장 요청에 실린다 (WD-777 리뷰)', async ({
    authenticatedPage: page,
  }) => {
    const stored = await openAuthEdit(page, storedApiKeyConnection('X-Old', '****abcd', { 'extraHeader:X-Key-Id': '****k001' }));
    await page.getByRole('button', { name: '헤더 추가' }).click();
    await page.getByLabel('추가 헤더 이름 2').fill('X-Extra');
    await page.getByLabel('추가 헤더 값 2').fill('extra-1');

    // 그사이 X-Key-Id 값이 새로 저장돼 재조회가 새 가린 값(****k002)을 가져온다
    const capture = await saveInfoAndRefetch(page, {
      ...stored,
      name: RENAMED,
      maskedAuthConfig: { ...stored.maskedAuthConfig, 'extraHeader:X-Key-Id': '****k002' },
    });
    // 행을 더해 고친 덩어리라 내가 더한 행은 남는다
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveValue('X-Extra');

    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect.poll(() => capture.requests.length).toBe(2);
    // 손대지 않은 X-Key-Id 는 새 가린 값을 보낸다 — 옛 ****k001 을 보내면 서버가 400 으로 거절한다
    expect(capture.requests[1].payload).toMatchObject({
      authConfig: { 'extraHeader:X-Key-Id': '****k002', 'extraHeader:X-Extra': 'extra-1' },
    });
  });

  test('빈 추가 헤더 행만 더해도 고친 것이라 나가려 하면 미저장 경고가 뜬다 (WD-777)', async ({
    authenticatedPage: page,
  }) => {
    await openAuthEdit(page);
    await page.getByRole('button', { name: '헤더 추가' }).click();
    await expect(page.getByLabel('추가 헤더 이름 1')).toHaveValue('');

    await page.getByRole('navigation').getByRole('link', { name: '사용자 관리' }).click();
    await expect(page.getByRole('alertdialog')).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/admin/api-connections/1');
  });

  test('헤더 이름 오류를 띄운 채 다른 칸만 바뀐 재조회가 와도 헤더 이름 오류가 남는다', async ({
    authenticatedPage: page,
  }) => {
    // 헤더 이름이 비어 저장된 연결 — 칸을 비운 채 인증 저장을 눌러 인라인 오류를 띄운다
    const stored = await openAuthEdit(page, storedApiKeyConnection(''));
    const headerInput = page.getByPlaceholder('Authorization');
    await expect(headerInput).toHaveValue('');
    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect(headerInput).toHaveAccessibleDescription('헤더 이름을 입력하세요.');

    // 재조회는 이름·추가 헤더만 바꾼다(헤더 이름은 그대로 빈 값)
    await saveInfoAndRefetch(page, {
      ...stored,
      name: RENAMED,
      maskedAuthConfig: { ...stored.maskedAuthConfig, ...TENANT_HEADER },
    });
    await expect(page.getByLabel('추가 헤더 이름 1')).toHaveValue('X-Tenant');

    // 바뀌지 않은 헤더 이름 칸은 여전히 틀려 오류가 남는다
    await expect(headerInput).toHaveAccessibleDescription('헤더 이름을 입력하세요.');
  });
});

/**
 * WD-788 — 편집 중 재조회 병합 후속(PR #1044 리뷰).
 * - 고친 추가 헤더 덩어리 안의 저장 행이 서버에서 지워졌으면, 손대지 않은 행은 빼고 손댄 행은 새 행으로 바꿔 지워진 헤더의 가린 값을 보내지 않는다.
 * - 재조회로 인증 방식이 바뀌면(사용자는 방식을 고치지 않음) 방식에 딸린 칸을 새 값으로 함께 바꿔, 숨은 칸이 dirty 로 남지 않게 한다.
 */
test.describe('WD-788 재조회 병합 후속', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  const PATH = '/api/v1/api-connections/1';
  /** 기본 정보 저장으로 다시 불러올 때 쓰는 새 이름 */
  const RENAMED = '이름만 바뀐 연결';

  /** 헤더 X-Old·가린 키 ****abcd 와 추가 헤더 extra 로 저장된 API_KEY 연결 */
  function storedApiKeyConnection(extra: Record<string, string> = {}) {
    return createApiConnection({
      id: 1,
      maskedAuthConfig: { placement: 'header', headerName: 'X-Old', apiKey: '****abcd', ...extra },
    });
  }

  /** 저장된 연결을 모킹하고 상세를 연 뒤 「인증 정보 변경」으로 편집을 연다. */
  async function openAuthEdit(page: Page, stored: ApiConnectionResponse) {
    await mockApi(page, 'GET', PATH, stored);
    await page.goto('/admin/api-connections/1');
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    return stored;
  }

  test('고친 추가 헤더 덩어리에서 다른 곳이 지운 헤더 — 손대지 않은 행은 빠지고, 손댄 행은 새 행이라 가린 값을 보내지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const stored = await openAuthEdit(
      page,
      storedApiKeyConnection({
        'extraHeader:X-Key-Id': '****k001',
        'extraHeader:X-Gone-A': '****a001',
        'extraHeader:X-Gone-B': '****b001',
      }),
    );
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveValue('X-Gone-A');
    // X-Gone-B 는 이름 뒤에 공백만 붙여 손댄다 — 같은 헤더라 값을 비워도 가린 값(****b001)을 유지하려 든다
    await page.getByLabel('추가 헤더 이름 3').fill('X-Gone-B ');

    // 그사이 다른 관리자가 X-Gone-A·X-Gone-B 를 지웠다
    const capture = await saveInfoAndRefetch(page, {
      ...stored,
      name: RENAMED,
      maskedAuthConfig: { placement: 'header', headerName: 'X-Old', apiKey: '****abcd', 'extraHeader:X-Key-Id': '****k001' },
    });

    // 목록을 맞췄다고 알린다
    await expect(page.getByText(/다른 곳에서 추가 헤더\(X-Gone-A, X-Gone-B\)가 지워져 목록을 맞췄습니다/)).toBeVisible();
    // 손대지 않은 X-Gone-A 는 빠지고, 손댄 X-Gone-B 는 이름을 지킨 채 남는다
    await expect(page.getByLabel('추가 헤더 이름 1')).toHaveValue('X-Key-Id');
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveValue('X-Gone-B ');
    await expect(page.getByLabel('추가 헤더 이름 3')).toHaveCount(0);

    // 손댄 행은 새 행이라 값이 비면 저장 때 「값 필수」로 잡고 보내지 않는다(지워진 헤더의 가린 값을 보내지 않는다)
    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect(page.getByLabel('추가 헤더 값 2')).toHaveAccessibleDescription('값을 입력하세요');
    expect(capture.requests).toHaveLength(1);

    // 값을 넣으면 그 값으로 보내고, 지워진 X-Gone-A 는 싣지 않는다
    await page.getByLabel('추가 헤더 값 2').fill('b-typed');
    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect.poll(() => capture.requests.length).toBe(2);
    expect(savedExtraHeaders(capture)).toEqual({
      'extraHeader:X-Key-Id': '****k001',
      'extraHeader:X-Gone-B': 'b-typed',
    });
  });

  test('이름 공백만 바꾸고 값을 비운 저장 행은 재조회의 새 가린 값을 보낸다(옛 가린 값을 보내 400 이 나지 않게)', async ({
    authenticatedPage: page,
  }) => {
    const stored = await openAuthEdit(page, storedApiKeyConnection({ 'extraHeader:X-Key-Id': '****k001' }));
    // 이름 뒤에 공백만 붙여 손댄다 — 같은 헤더라 값을 비우면 저장된 값을 유지하려고 가린 값을 보낸다
    await page.getByLabel('추가 헤더 이름 1').fill('X-Key-Id ');

    // 그사이 다른 관리자가 X-Key-Id 값을 바꿔 저장해 가린 값이 ****k002 가 됐다
    const capture = await saveInfoAndRefetch(page, {
      ...stored,
      name: RENAMED,
      maskedAuthConfig: { placement: 'header', headerName: 'X-Old', apiKey: '****abcd', 'extraHeader:X-Key-Id': '****k002' },
    });
    await expect(page.getByLabel('연결 이름')).toHaveValue(RENAMED);

    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect.poll(() => capture.requests.length).toBe(2);
    const authConfig = (capture.requests[1].payload as { authConfig: Record<string, string> }).authConfig;
    // 옛 가린 값(****k001)이 아니라 새 기준의 가린 값을 보낸다
    expect(authConfig['extraHeader:X-Key-Id']).toBe('****k002');
  });

  /** API_KEY 편집에서 헤더 이름을 고친 뒤, 다른 관리자가 BASIC 으로 바꾼 연결로 재조회되게 한다. */
  async function editHeaderThenRefetchAsBasic(page: Page) {
    await openAuthEdit(page, storedApiKeyConnection());
    await page.getByPlaceholder('Authorization').fill('X-New');
    return saveInfoAndRefetch(page, createBasicApiConnection({ id: 1, name: RENAMED }));
  }

  test('재조회로 인증 방식이 바뀌면 고친 딸린 칸도 새 값으로 바꾸고 알리며, 저장 payload 에 옛 방식 칸이 없다', async ({
    authenticatedPage: page,
  }) => {
    const capture = await editHeaderThenRefetchAsBasic(page);

    await expect(
      page.getByText('다른 곳에서 인증 방식이 바뀌어(Basic Auth), 고친 인증 칸을 새 값으로 바꿨습니다.'),
    ).toBeVisible();
    await expect(page.getByLabel('아이디')).toHaveValue('svc-user');

    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect.poll(() => capture.requests.length).toBe(2);
    // 숨은 헤더 이름(X-New)이 남지 않고 새 기준의 BASIC 값만 보낸다(비밀번호는 비웠으니 가린 값으로 유지)
    expect(capture.requests[1].payload).toEqual({
      authType: 'BASIC',
      authConfig: { authType: 'BASIC', username: 'svc-user', password: '****' },
    });
  });

  test('재조회로 인증 방식이 바뀐 뒤 숨은 칸이 dirty 로 남지 않아, 나가도 미저장 경고가 뜨지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await editHeaderThenRefetchAsBasic(page);
    await expect(page.getByLabel('아이디')).toHaveValue('svc-user');

    await page.getByRole('navigation').getByRole('link', { name: '사용자 관리' }).click();
    await expect(page).toHaveURL(/\/admin\/users/);
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
  });
});

/**
 * WD-796 — 편집 중 재조회 병합 후속(PR #1047 리뷰).
 * - 고친 추가 헤더 덩어리에서, 다른 곳이 더한 저장 헤더(이름 변경의 새 이름 포함)는 새 기준 순서 자리에 넣고 알린다 — 예전엔 버려져 인증 저장이 그 헤더를 지웠다.
 * - 사용자가 덩어리에서 지운 저장 헤더는 이전 기준에 있으므로 재조회로 되살리지 않는다.
 * - 인증 방식이 바뀌었다는 안내는 방식 이름을 괄호로 감싸 조사가 틀리지 않게 한다(「API Key으로」가 아니다).
 */
test.describe('WD-796 재조회 병합 후속', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  const PATH = '/api/v1/api-connections/1';
  /** 기본 정보 저장으로 다시 불러올 때 쓰는 새 이름 */
  const RENAMED = '이름만 바뀐 연결';
  /** 메인 인증 칸(헤더 X-Old·가린 키 ****abcd) — 재조회 응답도 같은 값을 써 추가 헤더만 바뀌게 한다 */
  const MAIN_AUTH = { placement: 'header', headerName: 'X-Old', apiKey: '****abcd' };

  /** 메인 인증 칸과 추가 헤더 extra 로 저장된 API_KEY 연결 */
  function storedApiKeyConnection(extra: Record<string, string> = {}) {
    return createApiConnection({ id: 1, maskedAuthConfig: { ...MAIN_AUTH, ...extra } });
  }

  /** 저장된 연결을 모킹하고 상세를 연 뒤 「인증 정보 변경」으로 편집을 연다. */
  async function openAuthEdit(page: Page, stored: ApiConnectionResponse) {
    await mockApi(page, 'GET', PATH, stored);
    await page.goto('/admin/api-connections/1');
    await page.getByRole('button', { name: '인증 정보 변경' }).click();
    return stored;
  }

  /**
   * 저장 행 X-A·X-Id 로 인증 편집을 열고 X-Id 행(2번)을 지운다 — WD-804 반려 시험의 공통 준비.
   * 지운 뒤 2번 행이 사라진 것까지 확인해 이후 재조회가 「지운 상태」에서 시작하게 한다. 저장 응답을 만들 때 쓰도록 연결을 돌려준다.
   */
  async function openAuthEditAndDeleteXId(page: Page) {
    const stored = await openAuthEdit(
      page,
      storedApiKeyConnection({ 'extraHeader:X-A': '****a001', 'extraHeader:X-Id': '****i001' }),
    );
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveValue('X-Id');
    await page.getByRole('button', { name: '추가 헤더 2 삭제' }).click();
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveCount(0);
    return stored;
  }

  test('재조회로 인증 방식이 API Key 로 바뀌면 안내가 「바뀌어(API Key)」로 뜨고 「API Key으로」가 없다 (WD-796)', async ({
    authenticatedPage: page,
  }) => {
    // BASIC 편집에서 아이디를 고친 뒤, 다른 관리자가 API_KEY 로 바꾼 연결로 재조회된다
    await openAuthEdit(page, createBasicApiConnection({ id: 1 }));
    await page.getByLabel('아이디').fill('other-user');
    await saveInfoAndRefetch(page, { ...storedApiKeyConnection(), name: RENAMED });

    await expect(
      page.getByText('다른 곳에서 인증 방식이 바뀌어(API Key), 고친 인증 칸을 새 값으로 바꿨습니다.'),
    ).toBeVisible();
    await expect(page.getByText(/API Key으로/)).toHaveCount(0);
    // 딸린 칸은 새 방식의 기준 값이다
    await expect(page.getByPlaceholder('Authorization')).toHaveValue('X-Old');
  });

  test('고친 추가 헤더 덩어리에서 다른 곳이 더한 헤더는 목록에 들어가고 알리며, 저장 payload 에 남는다 (WD-796)', async ({
    authenticatedPage: page,
  }) => {
    const stored = await openAuthEdit(page, storedApiKeyConnection({ 'extraHeader:X-A': '****a001' }));
    await expect(page.getByLabel('추가 헤더 이름 1')).toHaveValue('X-A');
    // X-A 값을 고쳐 덩어리를 「고침」으로 만든다
    await page.getByLabel('추가 헤더 값 1').fill('a-typed');

    // 그사이 다른 관리자가 X-B 를 더했다
    const capture = await saveInfoAndRefetch(page, {
      ...stored,
      name: RENAMED,
      maskedAuthConfig: { ...MAIN_AUTH, 'extraHeader:X-A': '****a001', 'extraHeader:X-B': '****bbbb' },
    });

    await expect(page.getByText('다른 곳에서 추가 헤더(X-B)가 더해져 목록에 넣었습니다.')).toBeVisible();
    // 고친 X-A 는 그대로, 더한 X-B 는 새 기준 순서대로 X-A 뒤에 저장값 유지 행으로 들어간다
    await expect(page.getByLabel('추가 헤더 이름 1')).toHaveValue('X-A');
    await expect(page.getByLabel('추가 헤더 값 1')).toHaveValue('a-typed');
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveValue('X-B');
    await expect(page.getByLabel('추가 헤더 값 2')).toHaveAttribute('placeholder', '저장된 값 유지 (****bbbb)');

    // 인증 저장 — X-B 는 가린 값(유지 의미)으로 실려 다른 곳에서 더한 헤더를 지우지 않는다
    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect.poll(() => capture.requests.length).toBe(2);
    expect(savedExtraHeaders(capture)).toEqual({
      'extraHeader:X-A': 'a-typed',
      'extraHeader:X-B': '****bbbb',
    });
  });

  test('다른 곳에서 이름을 바꾼 추가 헤더(X-Id → x-id)는 지워짐·더해짐을 함께 알리고, 새 이름 행이 남는다 (WD-796)', async ({
    authenticatedPage: page,
  }) => {
    const stored = await openAuthEdit(
      page,
      storedApiKeyConnection({ 'extraHeader:X-A': '****a001', 'extraHeader:X-Id': '****i001' }),
    );
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveValue('X-Id');
    // X-A 만 고친다 — X-Id 행은 손대지 않는다
    await page.getByLabel('추가 헤더 값 1').fill('a-typed');

    // 그사이 다른 관리자가 X-Id 를 x-id 로 바꿔 저장했다(서버 키가 바뀐다)
    const capture = await saveInfoAndRefetch(page, {
      ...stored,
      name: RENAMED,
      maskedAuthConfig: { ...MAIN_AUTH, 'extraHeader:X-A': '****a001', 'extraHeader:x-id': '****i002' },
    });

    await expect(page.getByText('다른 곳에서 추가 헤더(X-Id)가 지워져 목록을 맞췄습니다.')).toBeVisible();
    await expect(page.getByText('다른 곳에서 추가 헤더(x-id)가 더해져 목록에 넣었습니다.')).toBeVisible();
    // 사용자가 지운 헤더가 아니므로 「지운 헤더 … 바뀌었습니다」 안내는 없다(WD-804 반려)
    await expectNoRenameNotice(page);
    // 손대지 않은 옛 이름 행은 빠지고 새 이름 행이 남는다
    await expect(page.getByLabel('추가 헤더 이름 1')).toHaveValue('X-A');
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveValue('x-id');
    await expect(page.getByLabel('추가 헤더 이름 3')).toHaveCount(0);

    // 저장 payload 에 옛 이름(X-Id)은 없고 새 이름이 새 가린 값으로 실린다
    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect.poll(() => capture.requests.length).toBe(2);
    expect(savedExtraHeaders(capture)).toEqual({
      'extraHeader:X-A': 'a-typed',
      'extraHeader:x-id': '****i002',
    });
  });

  test('사용자가 같은 이름의 새 행을 써 둔 채 그 헤더가 더해지면 행이 둘이 되어 저장이 중복 오류로 막힌다 (WD-796)', async ({
    authenticatedPage: page,
  }) => {
    const stored = await openAuthEdit(page, storedApiKeyConnection({ 'extraHeader:X-A': '****a001' }));
    // 사용자가 X-B 새 행을 써 둔다(저장 전)
    await page.getByRole('button', { name: '헤더 추가' }).click();
    await page.getByLabel('추가 헤더 이름 2').fill('X-B');
    await page.getByLabel('추가 헤더 값 2').fill('b-typed');

    // 그사이 다른 관리자가 같은 이름 X-B 를 더했다
    const capture = await saveInfoAndRefetch(page, {
      ...stored,
      name: RENAMED,
      maskedAuthConfig: { ...MAIN_AUTH, 'extraHeader:X-A': '****a001', 'extraHeader:X-B': '****bbbb' },
    });

    await expect(page.getByText('다른 곳에서 추가 헤더(X-B)가 더해져 목록에 넣었습니다.')).toBeVisible();
    // 사용자 새 행과 합치지 않아 X-B 가 두 행이다
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveValue('X-B');
    await expect(page.getByLabel('추가 헤더 값 2')).toHaveValue('b-typed');
    await expect(page.getByLabel('추가 헤더 이름 3')).toHaveValue('X-B');

    // 저장하면 뒤 행에 중복 오류가 붙고 인증 PUT 은 나가지 않는다 — 사용자가 어느 값을 남길지 고른다
    // PUT 이 늦게 나가는 경우도 잡게, 저장을 누르기 전부터 1초 동안 인증 PUT 을 지켜본다 — 오지 않아야 한다(WD-796)
    const lateAuthPut = page.waitForRequest(
      (req) => req.method() === 'PUT' && new URL(req.url()).pathname === PATH,
      { timeout: 1000 },
    );
    // 아래 단언이 1초를 넘겨도 처리되지 않은 rejection 으로 남지 않게 미리 받아 둔다 — 결과는 아래 rejects 단언이 본다(WD-796)
    lateAuthPut.catch(() => {});
    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect(page.getByLabel('추가 헤더 이름 3')).toHaveAccessibleDescription('이름이 겹칩니다');
    await expect(lateAuthPut).rejects.toThrow();
    expect(capture.requests).toHaveLength(1);
  });

  test('다른 곳이 더한 헤더는 새 기준 순서 자리에 들어가, 고친 행을 되돌리면 나가도 미저장 경고가 뜨지 않는다 (WD-796)', async ({
    authenticatedPage: page,
  }) => {
    const stored = await openAuthEdit(page, storedApiKeyConnection({ 'extraHeader:X-A': '****a001' }));
    await expect(page.getByLabel('추가 헤더 이름 1')).toHaveValue('X-A');
    // X-A 값을 고쳐 덩어리를 「고침」으로 만든다
    await page.getByLabel('추가 헤더 값 1').fill('a-typed');

    // 그사이 다른 관리자가 X-0 을 더했다 — 응답 키 순서(= extraHeaderRowsFromConfig 의 행 순서)에서 X-0 이 X-A 앞이다
    await saveInfoAndRefetch(page, {
      ...stored,
      name: RENAMED,
      maskedAuthConfig: { ...MAIN_AUTH, 'extraHeader:X-0': '****0000', 'extraHeader:X-A': '****a001' },
    });
    await expect(page.getByText('다른 곳에서 추가 헤더(X-0)가 더해져 목록에 넣었습니다.')).toBeVisible();

    // 끝이 아니라 새 기준 순서 자리(X-A 앞)에 들어간다
    await expect(page.getByLabel('추가 헤더 이름 1')).toHaveValue('X-0');
    await expect(page.getByLabel('추가 헤더 값 1')).toHaveAttribute('placeholder', '저장된 값 유지 (****0000)');
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveValue('X-A');
    await expect(page.getByLabel('추가 헤더 값 2')).toHaveValue('a-typed');

    // X-A 값을 비워 원래대로 되돌리면 새 기준과 순서까지 같아져, 나가도 미저장 경고가 뜨지 않는다
    await page.getByLabel('추가 헤더 값 2').fill('');
    await page.getByRole('navigation').getByRole('link', { name: '사용자 관리' }).click();
    await expect(page).toHaveURL(/\/admin\/users/);
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
  });

  test('사용자가 덩어리에서 지운 저장 헤더는 재조회로 되살아나지 않는다 (WD-796)', async ({
    authenticatedPage: page,
  }) => {
    const stored = await openAuthEdit(
      page,
      storedApiKeyConnection({ 'extraHeader:X-A': '****a001', 'extraHeader:X-Del': '****d001' }),
    );
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveValue('X-Del');
    // 사용자가 저장 헤더 X-Del 행을 지운다
    await page.getByRole('button', { name: '추가 헤더 2 삭제' }).click();
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveCount(0);

    // 재조회 응답에는 X-Del 이 (값이 바뀐 채) 여전히 있다 — 이전 기준에도 있던 헤더라 「더해진」 것이 아니다
    const capture = await saveInfoAndRefetch(page, {
      ...stored,
      name: RENAMED,
      maskedAuthConfig: { ...MAIN_AUTH, 'extraHeader:X-A': '****a001', 'extraHeader:X-Del': '****d002' },
    });
    await expect(page.getByLabel('연결 이름')).toHaveValue(RENAMED);

    // 지운 행은 돌아오지 않고, 더해졌다는 안내도 없다
    await expect(page.getByLabel('추가 헤더 이름 1')).toHaveValue('X-A');
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveCount(0);
    await expect(page.getByText(/가 더해져 목록에 넣었습니다/)).toHaveCount(0);
    // 같은 이름으로 돌아왔으니 이름 변경 안내도 없다(WD-804 반려)
    await expectNoRenameNotice(page);

    // 저장 payload 에도 X-Del 이 없다
    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect.poll(() => capture.requests.length).toBe(2);
    expect(savedExtraHeaders(capture)).toEqual({ 'extraHeader:X-A': '****a001' });
  });

  test('재조회가 더한 헤더로 보낼 행이 11개가 되면 저장 전에 11번째 행에 상한 오류가 보이고 인증 PUT 은 나가지 않는다 (WD-804)', async ({
    authenticatedPage: page,
  }) => {
    // 저장 헤더 X-1..X-9 (9개)
    const nine: Record<string, string> = {};
    for (let i = 1; i <= 9; i++) nine[`extraHeader:X-${i}`] = `****000${i}`;
    const stored = await openAuthEdit(page, storedApiKeyConnection(nine));
    // 사용자가 10번째 새 행을 채운다(덩어리를 「고침」으로 만들어 재조회 때 병합 경로를 타게 한다)
    await page.getByRole('button', { name: '헤더 추가' }).click();
    await page.getByLabel('추가 헤더 이름 10').fill('X-User');
    await page.getByLabel('추가 헤더 값 10').fill('u-typed');

    // 그사이 다른 관리자가 X-New 를 더했다 — 병합 결과가 11행이 된다(「헤더 추가」 버튼 비활성은 병합을 막지 못한다)
    const capture = await saveInfoAndRefetch(page, {
      ...stored,
      name: RENAMED,
      maskedAuthConfig: { ...MAIN_AUTH, ...nine, 'extraHeader:X-New': '****nnnn' },
    });
    await expect(page.getByText('다른 곳에서 추가 헤더(X-New)가 더해져 목록에 넣었습니다.')).toBeVisible();
    await expect(page.getByLabel('추가 헤더 이름 11')).toHaveValue('X-New');

    // 저장을 누르면 요청 전에 11번째 행에 상한 오류가 붙고 인증 PUT 은 나가지 않는다
    const lateAuthPut = page.waitForRequest(
      (req) => req.method() === 'PUT' && new URL(req.url()).pathname === PATH,
      { timeout: 1000 },
    );
    lateAuthPut.catch(() => {});
    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect(page.getByLabel('추가 헤더 이름 11')).toHaveAccessibleDescription(
      '추가 헤더는 최대 10개까지 넣을 수 있습니다',
    );
    await expect(page.getByLabel('추가 헤더 이름 10')).not.toHaveAccessibleDescription(/최대 10개/);
    await expect(lateAuthPut).rejects.toThrow();
    expect(capture.requests).toHaveLength(1);

    // 넘친 행이 아닌 다른 행(X-1)을 지워 10개로 맞추면 넘친 행(이제 10번째)의 상한 오류도 사라진다 — 낡은 오류가 남지 않는다(WD-804 리뷰)
    await page.getByRole('button', { name: '추가 헤더 1 삭제' }).click();
    await expect(page.getByLabel('추가 헤더 이름 10')).toHaveValue('X-New');
    await expect(page.getByLabel('추가 헤더 이름 10')).not.toHaveAccessibleDescription(/최대 10개/);
    await expect(page.getByText('추가 헤더는 최대 10개까지 넣을 수 있습니다')).toHaveCount(0);
    // 10행이면 「헤더 추가」는 꺼져 있다
    await expect(page.getByRole('button', { name: '헤더 추가' })).toBeDisabled();
  });

  test('사용자가 지운 X-Id 가 다른 곳에서 x-id 로 이름만 바뀌어 와도 되살아나지 않고, 이름이 바뀌었다는 안내가 한 번 뜬다 (WD-804 반려)', async ({
    authenticatedPage: page,
  }) => {
    // 사용자가 X-Id 행을 지운다
    const stored = await openAuthEditAndDeleteXId(page);

    // 재조회 응답에는 같은 헤더가 대소문자만 바뀐 x-id 로 있다 — 같은 HTTP 헤더라 「더해진」 것이 아니다
    const capture = await saveInfoAndRefetch(page, {
      ...stored,
      name: RENAMED,
      maskedAuthConfig: { ...MAIN_AUTH, 'extraHeader:X-A': '****a001', 'extraHeader:x-id': '****i001' },
    });
    await expect(page.getByLabel('연결 이름')).toHaveValue(RENAMED);

    await expect(page.getByLabel('추가 헤더 이름 1')).toHaveValue('X-A');
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveCount(0);
    await expect(page.getByText(/가 더해져 목록에 넣었습니다/)).toHaveCount(0);
    // 되살리지는 않지만, 지운 헤더가 다른 곳에서 이름이 바뀌었음을 한 번 알린다
    await expect(
      page.getByText(RENAME_NOTICE_X_ID, {
        exact: true,
      }),
    ).toHaveCount(1);

    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect.poll(() => capture.requests.length).toBe(2);
    expect(savedExtraHeaders(capture)).toEqual({ 'extraHeader:X-A': '****a001' });
  });

  test('지운 X-Id 자리에 같은 이름의 새 행을 써 두면 x-id 로 바뀌어 와도 「서버에서도 지워집니다」 안내를 띄우지 않는다 (WD-804 반려)', async ({
    authenticatedPage: page,
  }) => {
    const stored = await openAuthEditAndDeleteXId(page);
    // 같은 이름 X-Id 를 값과 함께 새 행으로 다시 넣는다 — 저장하면 이 행이 나간다
    await page.getByRole('button', { name: '헤더 추가' }).click();
    await page.getByLabel('추가 헤더 이름 2').fill('X-Id');
    await page.getByLabel('추가 헤더 값 2').fill('typed-new');

    await saveInfoAndRefetch(page, {
      ...stored,
      name: RENAMED,
      maskedAuthConfig: { ...MAIN_AUTH, 'extraHeader:X-A': '****a001', 'extraHeader:x-id': '****i001' },
    });
    await expect(page.getByLabel('연결 이름')).toHaveValue(RENAMED);

    // 사용자의 새 행이 그대로 남고, 서버에서 지워진다는 거짓 안내는 없다
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveValue('X-Id');
    await expect(page.getByLabel('추가 헤더 값 2')).toHaveValue('typed-new');
    await expect(page.getByLabel('추가 헤더 이름 3')).toHaveCount(0);
    await expectNoRenameNotice(page);
  });

  test('지운 X-Id 자리에 다른 저장 행(X-A)의 이름을 X-Id 로 고쳐 두면 x-id 로 바뀌어 와도 이름 변경 안내를 띄우지 않는다 (WD-804 반려)', async ({
    authenticatedPage: page,
  }) => {
    const stored = await openAuthEdit(
      page,
      storedApiKeyConnection({ 'extraHeader:X-A': '****a001', 'extraHeader:X-Id': '****i001' }),
    );
    // X-Id 행을 지우고, 저장 행 X-A 의 이름을 X-Id 로 고친다 — 저장하면 이 행이 X-Id 로 나간다
    await page.getByRole('button', { name: '추가 헤더 2 삭제' }).click();
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveCount(0);
    await page.getByLabel('추가 헤더 이름 1').fill('X-Id');
    await page.getByLabel('추가 헤더 값 1').fill('typed-new');

    // 그사이 다른 관리자가 X-Id 를 x-id 로 바꿨다
    await saveInfoAndRefetch(page, {
      ...stored,
      name: RENAMED,
      maskedAuthConfig: { ...MAIN_AUTH, 'extraHeader:X-A': '****a001', 'extraHeader:x-id': '****i001' },
    });
    await expect(page.getByLabel('연결 이름')).toHaveValue(RENAMED);

    // 결정적 신호: 사용자가 고친 행이 그대로 남아 있다(병합이 끝났다)
    await expect(page.getByLabel('추가 헤더 이름 1')).toHaveValue('X-Id');
    await expect(page.getByLabel('추가 헤더 값 1')).toHaveValue('typed-new');
    await expectNoRenameNotice(page);
  });

  test('지운 X-Id 가 있는 채 재조회가 X-Id 와 x-id 를 함께 돌려주면 이름 변경이 아니라 x-id 가 더해진 것이라 목록에 넣고 알리며, 저장 payload 에 x-id 가 남는다 (WD-804 반려)', async ({
    authenticatedPage: page,
  }) => {
    const stored = await openAuthEditAndDeleteXId(page);

    // 다른 관리자가 X-Id 는 그대로 두고 x-id 를 더했다 — 사용자가 지운 X-Id 는 지운 대로 두되, 더해진 x-id 는 말없이 지워지면 안 된다
    const capture = await saveInfoAndRefetch(page, {
      ...stored,
      name: RENAMED,
      maskedAuthConfig: {
        ...MAIN_AUTH,
        'extraHeader:X-A': '****a001',
        'extraHeader:X-Id': '****i002',
        'extraHeader:x-id': '****i003',
      },
    });
    await expect(page.getByLabel('연결 이름')).toHaveValue(RENAMED);

    // 더해진 x-id 는 새 기준 순서대로 X-A 뒤(2번)에 저장값 유지 행으로 들어가고, 지운 X-Id 는 돌아오지 않는다
    await expect(page.getByText('다른 곳에서 추가 헤더(x-id)가 더해져 목록에 넣었습니다.')).toBeVisible();
    await expect(page.getByLabel('추가 헤더 이름 1')).toHaveValue('X-A');
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveValue('x-id');
    await expect(page.getByLabel('추가 헤더 값 2')).toHaveAttribute('placeholder', '저장된 값 유지 (****i003)');
    await expect(page.getByLabel('추가 헤더 이름 3')).toHaveCount(0);
    // 이름이 바뀐 게 아니므로 이름 변경 안내는 없다
    await expectNoRenameNotice(page);

    // 인증 저장 — x-id 는 가린 값(유지 의미)으로 실리고, 사용자가 지운 X-Id 는 실리지 않는다
    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect.poll(() => capture.requests.length).toBe(2);
    const headers = savedExtraHeaders(capture);
    expect(headers).toEqual({ 'extraHeader:X-A': '****a001', 'extraHeader:x-id': '****i003' });
    expect(headers).not.toHaveProperty('extraHeader:X-Id');
  });

  test('지운 X-Id 가 X-ID 로 바뀌고 x-id 가 더해져 대소문자 변형이 둘이면 이름 변경이 아니라 모두 더해진 것이라 목록에 넣고, 저장 payload 에 둘 다 남는다 (WD-804 반려)', async ({
    authenticatedPage: page,
  }) => {
    const stored = await openAuthEditAndDeleteXId(page);

    // 다른 관리자가 X-Id 를 X-ID 로 바꾸고 x-id 를 더했다 — 어느 쪽이 옛 헤더의 새 이름인지 알 수 없으니 둘 다 보여 준다(말없이 지우지 않는다)
    const capture = await saveInfoAndRefetch(page, {
      ...stored,
      name: RENAMED,
      maskedAuthConfig: {
        ...MAIN_AUTH,
        'extraHeader:X-A': '****a001',
        'extraHeader:X-ID': '****i002',
        'extraHeader:x-id': '****i003',
      },
    });
    await expect(page.getByLabel('연결 이름')).toHaveValue(RENAMED);

    await expect(page.getByLabel('추가 헤더 이름 1')).toHaveValue('X-A');
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveValue('X-ID');
    await expect(page.getByLabel('추가 헤더 이름 3')).toHaveValue('x-id');
    await expect(page.getByText(/가 더해져 목록에 넣었습니다/)).toBeVisible();
    await expectNoRenameNotice(page);

    // 대소문자만 다른 두 헤더는 함께 저장할 수 없다(HTTP 헤더 이름은 대소문자를 가리지 않는다) — 말없이 하나를 버리지 않고
    // 「이름이 겹칩니다」로 저장을 막아 사용자가 둘 중 남길 것을 고르게 한다(인증 저장 요청은 나가지 않는다)
    const requestsBefore = capture.requests.length;
    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect(page.getByText('이름이 겹칩니다')).toBeVisible();
    expect(capture.requests.length).toBe(requestsBefore);

    // 사용자가 X-ID 를 지우면 x-id 가 남아 저장된다 — 다른 곳에서 더한 헤더가 사용자 모르게 사라지지 않는다
    await page.getByRole('button', { name: '추가 헤더 2 삭제' }).click();
    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect.poll(() => capture.requests.length).toBe(requestsBefore + 1);
    expect(savedExtraHeaders(capture)).toEqual({
      'extraHeader:X-A': '****a001',
      'extraHeader:x-id': '****i003',
    });
  });

  test('지운 X-Id 자리에 x-id 새 행을 써 두면 재조회가 X-Id 와 x-id 를 함께 돌려줘도 사용자의 행 하나만 남고 중복 오류 없이 저장된다 (WD-804 반려)', async ({
    authenticatedPage: page,
  }) => {
    const stored = await openAuthEditAndDeleteXId(page);
    await page.getByRole('button', { name: '헤더 추가' }).click();
    await page.getByLabel('추가 헤더 이름 2').fill('x-id');
    await page.getByLabel('추가 헤더 값 2').fill('typed-new');

    const capture = await saveInfoAndRefetch(page, {
      ...stored,
      name: RENAMED,
      maskedAuthConfig: {
        ...MAIN_AUTH,
        'extraHeader:X-A': '****a001',
        'extraHeader:X-Id': '****i002',
        'extraHeader:x-id': '****i003',
      },
    });
    await expect(page.getByLabel('연결 이름')).toHaveValue(RENAMED);

    // 사용자의 행만 남는다 — 같은 이름(대소문자 무시)의 저장 행을 더 끼우면 중복 검증이 저장을 막는다
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveValue('x-id');
    await expect(page.getByLabel('추가 헤더 값 2')).toHaveValue('typed-new');
    await expect(page.getByLabel('추가 헤더 이름 3')).toHaveCount(0);

    await page.getByRole('button', { name: '인증 저장' }).click();
    await expect.poll(() => capture.requests.length).toBe(2);
    const headers = savedExtraHeaders(capture);
    expect(headers).toEqual({ 'extraHeader:X-A': '****a001', 'extraHeader:x-id': 'typed-new' });
    expect(headers).not.toHaveProperty('extraHeader:X-Id');
  });

  test('지운 X-Id 가 x-id 로 바뀌고 X-New 가 더해진 재조회는 「더해져」 안내와 이름 변경 안내를 함께 보인다 (WD-804 반려)', async ({
    authenticatedPage: page,
  }) => {
    const stored = await openAuthEditAndDeleteXId(page);

    await saveInfoAndRefetch(page, {
      ...stored,
      name: RENAMED,
      maskedAuthConfig: {
        ...MAIN_AUTH,
        'extraHeader:X-A': '****a001',
        'extraHeader:x-id': '****i001',
        'extraHeader:X-New': '****n001',
      },
    });
    await expect(page.getByLabel('연결 이름')).toHaveValue(RENAMED);

    await expect(page.getByText('다른 곳에서 추가 헤더(X-New)가 더해져 목록에 넣었습니다.')).toBeVisible();
    await expect(
      page.getByText(RENAME_NOTICE_X_ID, {
        exact: true,
      }),
    ).toBeVisible();
    await expect(page.getByLabel('추가 헤더 이름 2')).toHaveValue('X-New');
    await expect(page.getByLabel('추가 헤더 이름 3')).toHaveCount(0);
  });

  test('연속 재조회가 서로 다른 헤더를 더하면 두 안내가 모두 보인다 — 같은 토스트 id 로 덮어쓰지 않는다 (WD-804)', async ({
    authenticatedPage: page,
  }) => {
    // 토스트 자동 닫힘(sonner 기본 4초)을 멈춘다 — sonner 는 문서가 숨겨져 있으면 닫힘 타이머를 돌리지 않으므로, 페이지가 뜨기 전에
    // document.hidden 을 참으로 둔다(WD-804 리뷰). 부하가 큰 실행에서 두 번째 저장이 4초를 넘겨 첫 안내가 저절로 닫혀 시험이 흔들리지 않게 한다.
    // Playwright 가짜 시계(page.clock)를 멈추면 TanStack Query 의 알림 예약(setTimeout 0)도 멈춰 재조회가 화면에 오지 않아 이 방법을 쓴다.
    // 앱은 문서 숨김을 보지 않고(창 포커스 재조회도 꺼져 있다) 이 시험에서만 쓴다.
    await page.addInitScript(() => {
      Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
    });
    const stored = await openAuthEdit(page, storedApiKeyConnection({ 'extraHeader:X-A': '****a001' }));
    // X-A 값을 고쳐 덩어리를 「고침」으로 만든다
    await page.getByLabel('추가 헤더 값 1').fill('a-typed');

    // 첫 재조회: 다른 곳이 X-B 를 더했다
    await saveInfoAndRefetch(page, {
      ...stored,
      name: RENAMED,
      maskedAuthConfig: { ...MAIN_AUTH, 'extraHeader:X-A': '****a001', 'extraHeader:X-B': '****bbbb' },
    });
    await expect(page.getByText('다른 곳에서 추가 헤더(X-B)가 더해져 목록에 넣었습니다.')).toBeVisible();

    // 첫 안내가 떠 있는 동안(닫힘 타이머가 멈춰 있다) 두 번째 재조회: 이번엔 X-C 가 더해졌다 — 첫 저장 토스트도 남아 있어 저장 토스트는 둘이다
    await saveInfoAndRefetch(
      page,
      {
        ...stored,
        name: `${RENAMED} 2`,
        maskedAuthConfig: {
          ...MAIN_AUTH,
          'extraHeader:X-A': '****a001',
          'extraHeader:X-B': '****bbbb',
          'extraHeader:X-C': '****cccc',
        },
      },
      2,
    );
    await expect(page.getByText('다른 곳에서 추가 헤더(X-C)가 더해져 목록에 넣었습니다.')).toBeVisible();
    // 첫 안내가 두 번째로 덮어써지지 않고 함께 남는다
    await expect(page.getByText('다른 곳에서 추가 헤더(X-B)가 더해져 목록에 넣었습니다.')).toBeVisible();
  });
});

test.describe('WD-886 마스킹 도움말 버튼 히트 영역', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    // 관리 화면 권한 가드 통과를 위해 ADMIN 으로 오버라이드한다
    await setupAdminAuth(page);
    await page.setViewportSize({ width: 1440, height: 900 });
  });

  test('키 값 도움말 버튼이 32×32 이상이고 행 높이는 늘지 않는다', async ({ authenticatedPage: page }) => {
    await setupApiConnectionDetailMocks(page, 1);
    await page.goto('/admin/api-connections/1');

    const help = page.getByTestId('masked-key-help');
    await expect(help).toBeVisible();
    const box = await help.boundingBox();
    expect(box!.width).toBeGreaterThanOrEqual(32);
    expect(box!.height).toBeGreaterThanOrEqual(32);
    // 히트 영역만 커지고 아이콘은 14px 그대로다
    const iconBox = await help.locator('svg').boundingBox();
    expect(iconBox!.width).toBeCloseTo(14, 0);

    // 음수 세로 여백 덕에 키 값 행 높이가 버튼 없는 「인증 유형」 행과 같다(1px 오차 허용)
    const valueBox = await page.getByText('***masked***', { exact: true }).boundingBox();
    const plainBox = await page.getByText('인증 유형', { exact: true }).boundingBox();
    expect(Math.abs(valueBox!.height - plainBox!.height)).toBeLessThanOrEqual(1);

    // 버튼 왼쪽 위 모서리 근처(아이콘 바깥 여백)에서도 툴팁이 열린다
    await help.hover({ position: { x: 2, y: 2 } });
    await expect(page.getByText('보안 정책상 마지막 4자리만 표시됩니다.', { exact: false })).toBeVisible();
  });

  test('토큰 도움말 버튼도 32×32 이상이고 행 높이는 늘지 않는다', async ({ authenticatedPage: page }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/api-connections/1',
      createApiConnection({ id: 1, authType: 'BEARER', maskedAuthConfig: { token: '***masked***' } }),
    );
    await page.goto('/admin/api-connections/1');

    const help = page.getByTestId('masked-token-help');
    await expect(help).toBeVisible();
    const box = await help.boundingBox();
    expect(box!.width).toBeGreaterThanOrEqual(32);
    expect(box!.height).toBeGreaterThanOrEqual(32);

    // 토큰 행 높이도 버튼 없는 「인증 유형」 행과 같다(1px 오차 허용)
    const valueBox = await page.getByText('***masked***', { exact: true }).boundingBox();
    const plainBox = await page.getByText('인증 유형', { exact: true }).boundingBox();
    expect(Math.abs(valueBox!.height - plainBox!.height)).toBeLessThanOrEqual(1);
  });
});
