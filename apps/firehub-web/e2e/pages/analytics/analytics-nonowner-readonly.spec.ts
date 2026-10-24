import type { Page } from '@playwright/test';

import {
  createChart,
  createChartListItem,
  createDashboardListItem,
  createQueryResult,
  createSavedQuery,
  createSavedQueryListItem,
} from '../../factories/analytics.factory';
import {
  setupChartBuilderMocks,
  setupDashboardEditorMocks,
  setupQueryEditorMocks,
  setupQueryExecuteMock,
} from '../../fixtures/analytics.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, MOCK_USER, test } from '../../fixtures/auth.fixture';

/**
 * WD-196 — 남이 공유한 대시보드·차트·저장 쿼리는 읽기 전용으로 보인다.
 *
 * 서버는 수정·삭제를 작성자에게만 허용한다(남의 요청은 존재를 숨기는 404). 예전 화면은 소유 여부를 보지 않고 편집·저장·삭제 버튼을 그려,
 * 누르면 「Dashboard not found: 2」 같은 영어 원문 토스트가 떴다. 이제 응답의 작성자 id(createdBy)를 로그인 사용자(MOCK_USER.id)와
 * 비교해 버튼을 숨기고 안내를 보인다. 보기·실행·새로고침·PDF 같은 읽기 기능은 그대로다.
 */
const OTHER_USER_ID = MOCK_USER.id + 1;
const OWNER_NAME = '작성자B';

/** 화면에서 수정 요청(PUT/PATCH/DELETE)이 나가는지 센다 — 읽기 전용 화면에서는 0이어야 한다 */
function trackWrites(page: Page, pathPrefix: string) {
  const writes: string[] = [];
  page.on('request', (req) => {
    if (['PUT', 'PATCH', 'DELETE'].includes(req.method()) && new URL(req.url()).pathname.startsWith(pathPrefix)) {
      writes.push(`${req.method()} ${req.url()}`);
    }
  });
  return writes;
}

test.describe('대시보드 — 남이 공유한 대시보드는 보기 전용 (WD-196)', () => {
  test('편집·차트 추가 버튼 없이 「보기 전용」 배지를 보이고, 새로고침·PDF·전체화면은 그대로다', async ({
    authenticatedPage: page,
  }) => {
    await setupDashboardEditorMocks(page, 2, {
      name: '공유 현황판',
      isShared: true,
      createdBy: OTHER_USER_ID,
      createdByName: OWNER_NAME,
    });
    const writes = trackWrites(page, '/api/v1/analytics/dashboards');

    await page.goto('/analytics/dashboards/2');
    await expect(page.getByRole('heading', { name: '공유 현황판' })).toBeVisible();

    // 위젯은 그대로 보인다(읽기)
    await expect(page.getByText('항목 A')).toBeVisible();
    // 편집 진입점이 없다
    await expect(page.getByRole('button', { name: '편집' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '차트 추가' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '완료' })).toHaveCount(0);
    // 읽기 전용 배지 + 누가 공유했는지(툴팁)
    const badge = page.getByTestId('dashboard-readonly-badge');
    await expect(badge).toHaveText('보기 전용');
    await expect(badge).toHaveAttribute('title', `${OWNER_NAME}님이 공유한 대시보드입니다. 작성자만 고칠 수 있습니다.`);
    // 조회 성격 기능은 남는다
    // 툴바 새로고침(위젯 카드에도 같은 이름의 버튼이 있어 첫 번째 = 툴바)
    const toolbarRefresh = page.getByRole('button', { name: '새로고침' }).first();
    await expect(toolbarRefresh).toBeVisible();
    await expect(page.getByRole('button', { name: 'PDF로 내보내기' })).toBeVisible();
    await expect(page.getByRole('button', { name: '공유 링크 복사' })).toBeVisible();
    await expect(page.getByRole('button', { name: '전체화면' })).toBeVisible();

    // 새로고침을 눌러도 수정 요청은 나가지 않는다
    await toolbarRefresh.click();
    await expect(page.getByText('항목 A')).toBeVisible();
    expect(writes).toEqual([]);
  });

  test('위젯이 없는 남의 대시보드는 「편집 모드」를 권하지 않는다', async ({ authenticatedPage: page }) => {
    await setupDashboardEditorMocks(page, 2, {
      name: '빈 공유 현황판',
      isShared: true,
      widgets: [],
      createdBy: OTHER_USER_ID,
      createdByName: OWNER_NAME,
    });

    await page.goto('/analytics/dashboards/2');
    await expect(page.getByText('위젯이 없습니다.')).toBeVisible();
    await expect(page.getByText('작성자가 아직 차트를 추가하지 않았습니다.')).toBeVisible();
    await expect(page.getByText('편집 모드에서 차트를 추가하세요.')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '편집 모드' })).toHaveCount(0);
  });

  test('내 대시보드는 지금처럼 편집할 수 있다(양성 대조)', async ({ authenticatedPage: page }) => {
    await setupDashboardEditorMocks(page, 1, { name: '내 현황판', createdBy: MOCK_USER.id });

    await page.goto('/analytics/dashboards/1');
    await expect(page.getByRole('heading', { name: '내 현황판' })).toBeVisible();
    await expect(page.getByTestId('dashboard-readonly-badge')).toHaveCount(0);
    await page.getByRole('button', { name: '편집' }).click();
    await expect(page.getByRole('button', { name: '차트 추가' })).toBeVisible();
    await expect(page.getByRole('button', { name: '완료' })).toBeVisible();
  });

  test('없는 대시보드 주소는 화면 안내와 같은 한국어 문구를 보인다', async ({ authenticatedPage: page }) => {
    // 서버(WD-196)는 없는 대시보드·남의 비공개 대시보드를 같은 한국어 404 로 답한다 — 웹은 그 문구를 그대로 토스트한다.
    // WD-240: 서버 문구에서 번호를 뺐다(「…: 999」 → 「… 이미 삭제되었을 수 있습니다.」).
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/dashboards/999',
      { status: 404, error: 'Not Found', message: '대시보드를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.' },
      { status: 404 },
    );

    await page.goto('/analytics/dashboards/999');
    await expect(page.getByText('대시보드를 찾을 수 없습니다.', { exact: true })).toBeVisible();
    await expect(page.getByText('대시보드를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.')).toBeVisible();
    await expect(page.getByText(/찾을 수 없습니다: 999/)).toHaveCount(0);
    await expect(page.getByText(/Dashboard not found/)).toHaveCount(0);
  });
});

test.describe('차트 빌더 — 남이 공유한 차트는 저장할 수 없다 (WD-196)', () => {
  test('저장 버튼 대신 안내를 보이고, 설정을 바꿔도 「미저장 변경사항」·이탈 경고가 뜨지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupChartBuilderMocks(page, 2);
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/charts/2',
      createChart({ id: 2, name: '공유 차트', isShared: true, createdBy: OTHER_USER_ID, createdByName: OWNER_NAME }),
    );
    const writes = trackWrites(page, '/api/v1/analytics/charts');

    await page.goto('/analytics/charts/2');
    await expect(page.getByRole('heading', { name: '공유 차트' })).toBeVisible();

    await expect(page.getByRole('button', { name: '저장', exact: true })).toHaveCount(0);
    await expect(page.getByTestId('chart-shared-readonly')).toHaveText(
      `${OWNER_NAME}님이 공유한 차트입니다 — 설정을 바꿔 볼 수는 있지만 저장·이름 변경은 작성자만 할 수 있습니다.`,
    );
    // 남의 공유 차트를 내 대시보드에 넣는 것은 허용된다(WD-195)
    await expect(page.getByRole('button', { name: '대시보드에 추가' })).toBeEnabled();

    // 설정을 바꿔 보는 것은 되지만 저장할 수 없으니 미저장 경고는 없다 — 바로 목록으로 나간다
    await page.getByRole('button', { name: '선 차트' }).click();
    await expect(page.getByText('미저장 변경사항')).toHaveCount(0);
    await page.getByRole('button', { name: '목록으로 돌아가기' }).click();
    await expect(page).toHaveURL('/analytics/charts');
    expect(writes).toEqual([]);
  });

  test('「대시보드에 추가」 창은 내 대시보드만 고르게 한다 — 남의 공유 대시보드는 목록에 없다', async ({
    authenticatedPage: page,
  }) => {
    await setupChartBuilderMocks(page, 1);
    // 목록 API 는 내 것 + 남이 공유한 것을 함께 준다(보기용) — 위젯은 작성자만 넣을 수 있으므로 창에서는 걸러야 한다
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/dashboards',
      createPageResponse([
        createDashboardListItem({ id: 10, name: '내 대시보드' }),
        createDashboardListItem({ id: 20, name: '남의 공유 대시보드', isShared: true, createdBy: OTHER_USER_ID }),
      ]),
    );

    await page.goto('/analytics/charts/1');
    await page.getByRole('button', { name: '대시보드에 추가' }).click();
    const dialog = page.getByRole('dialog', { name: '대시보드에 추가' });
    await expect(dialog.getByRole('button', { name: '내 대시보드' })).toBeVisible();
    await expect(dialog.getByText('남의 공유 대시보드')).toHaveCount(0);
  });

  test('내 차트는 지금처럼 저장 버튼이 있고 안내가 없다(양성 대조)', async ({ authenticatedPage: page }) => {
    await setupChartBuilderMocks(page, 1);

    await page.goto('/analytics/charts/1');
    await expect(page.getByRole('heading', { name: '테스트 차트' })).toBeVisible();
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeVisible();
    await expect(page.getByTestId('chart-shared-readonly')).toHaveCount(0);
  });
});

test.describe('쿼리 편집기 — 남이 공유한 쿼리는 복제해서 고친다 (WD-196)', () => {
  async function setupSharedQuery(page: Page) {
    await setupQueryEditorMocks(page, 3);
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/queries/3',
      createSavedQuery({
        id: 3,
        name: '공유 쿼리',
        sqlText: 'SELECT 1 AS shared',
        isShared: true,
        createdBy: OTHER_USER_ID,
        createdByName: OWNER_NAME,
      }),
    );
  }

  test('저장 버튼 대신 안내를 보이고, 실행은 그대로 된다', async ({ authenticatedPage: page }) => {
    await setupSharedQuery(page);
    await setupQueryExecuteMock(page);
    const writes = trackWrites(page, '/api/v1/analytics/queries');
    const exec = await mockApi(page, 'POST', '/api/v1/analytics/queries/execute', createQueryResult(), {
      capture: true,
    });

    await page.goto('/analytics/queries/3');
    await expect(page.getByRole('heading', { name: '공유 쿼리' })).toBeVisible();
    await expect(page.getByRole('button', { name: '저장', exact: true })).toHaveCount(0);
    await expect(page.getByTestId('query-shared-readonly')).toContainText(
      `${OWNER_NAME}님이 공유한 쿼리입니다 — 실행해 볼 수 있고, 고치려면 복제해서 내 쿼리로 만드세요.`,
    );

    // SQL 을 고쳐 실행해 볼 수 있다 — 저장할 수 없으니 미저장 표시는 없다
    await page.locator('.cm-content').click();
    await page.keyboard.press('End');
    await page.keyboard.type(' + 1');
    await expect(page.getByText('미저장 변경사항')).toHaveCount(0);
    await page.getByRole('button', { name: '실행', exact: true }).click();
    const req = await exec.waitForRequest();
    expect(req.payload).toMatchObject({ sql: 'SELECT 1 AS shared + 1' });
    expect(writes).toEqual([]);
  });

  test('「복제」를 누르면 서버 복제 후 내 사본 편집기로 옮겨 저장할 수 있다', async ({ authenticatedPage: page }) => {
    await setupSharedQuery(page);
    const clone = await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/3/clone',
      createSavedQuery({ id: 31, name: '공유 쿼리 (복사본)', sqlText: 'SELECT 1 AS shared', isShared: false }),
      { capture: true },
    );
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/queries/31',
      createSavedQuery({ id: 31, name: '공유 쿼리 (복사본)', sqlText: 'SELECT 1 AS shared', isShared: false }),
    );

    await page.goto('/analytics/queries/3');
    await page.getByTestId('query-shared-readonly').getByRole('button', { name: '복제' }).click();

    await clone.waitForRequest();
    await expect(page).toHaveURL('/analytics/queries/31');
    await expect(page.getByText('복사본을 만들었습니다: 공유 쿼리 (복사본)')).toBeVisible();
    await expect(page.getByRole('heading', { name: '공유 쿼리 (복사본)' })).toBeVisible();
    // 사본은 내 것 — 안내가 사라지고 저장 버튼이 돌아온다
    await expect(page.getByTestId('query-shared-readonly')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeVisible();
  });

  test('내 쿼리는 지금처럼 저장 버튼이 있고 안내가 없다(양성 대조)', async ({ authenticatedPage: page }) => {
    await setupQueryEditorMocks(page, 1);

    await page.goto('/analytics/queries/1');
    await expect(page.getByRole('heading', { name: '테스트 쿼리' })).toBeVisible();
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeVisible();
    await expect(page.getByTestId('query-shared-readonly')).toHaveCount(0);
  });
});

test.describe('목록 — 남의 공유 항목 줄에는 편집·설정·삭제가 없다 (WD-196)', () => {
  test('대시보드 목록: 남의 줄은 편집·설정·삭제가 없고 내 줄은 있다', async ({ authenticatedPage: page }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/dashboards',
      createPageResponse([
        createDashboardListItem({ id: 1, name: '내 대시보드' }),
        createDashboardListItem({
          id: 2,
          name: '남의 대시보드',
          isShared: true,
          createdBy: OTHER_USER_ID,
          createdByName: OWNER_NAME,
        }),
      ]),
    );

    await page.goto('/analytics/dashboards');
    const mine = page.getByRole('row').filter({ hasText: '내 대시보드' });
    const others = page.getByRole('row').filter({ hasText: '남의 대시보드' });
    await expect(others).toBeVisible();
    for (const label of ['편집', '설정', '삭제']) {
      await expect(mine.getByRole('button', { name: label })).toHaveCount(1);
      await expect(others.getByRole('button', { name: label })).toHaveCount(0);
    }
    // 줄 클릭으로 보기는 그대로
    await others.getByText('남의 대시보드').click();
    await expect(page).toHaveURL('/analytics/dashboards/2');
  });

  test('차트 목록: 남의 줄은 편집·삭제가 없고 내 줄은 있다', async ({ authenticatedPage: page }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/charts',
      createPageResponse([
        createChartListItem({ id: 1, name: '내 차트' }),
        createChartListItem({ id: 2, name: '남의 차트', isShared: true, createdBy: OTHER_USER_ID }),
      ]),
    );

    await page.goto('/analytics/charts');
    const mine = page.getByRole('row').filter({ hasText: '내 차트' });
    const others = page.getByRole('row').filter({ hasText: '남의 차트' });
    await expect(others).toBeVisible();
    for (const label of ['편집', '삭제']) {
      await expect(mine.getByRole('button', { name: label })).toHaveCount(1);
      await expect(others.getByRole('button', { name: label })).toHaveCount(0);
    }
  });

  test('쿼리 목록: 남의 줄은 실행만 있고 편집·삭제가 없다', async ({ authenticatedPage: page }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/queries',
      createPageResponse([
        createSavedQueryListItem({ id: 1, name: '내 쿼리' }),
        createSavedQueryListItem({ id: 2, name: '남의 쿼리', isShared: true, createdBy: OTHER_USER_ID }),
      ]),
    );
    await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', []);

    await page.goto('/analytics/queries');
    const mine = page.getByRole('row').filter({ hasText: '내 쿼리' });
    const others = page.getByRole('row').filter({ hasText: '남의 쿼리' });
    await expect(others).toBeVisible();
    await expect(others.getByRole('button', { name: '실행' })).toHaveCount(1);
    for (const label of ['편집', '삭제']) {
      await expect(mine.getByRole('button', { name: label })).toHaveCount(1);
      await expect(others.getByRole('button', { name: label })).toHaveCount(0);
    }
  });
});
