import { setupNewQueryEditorMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { holdApi } from '../../fixtures/hold-api';

/**
 * 쿼리 편집기 왼쪽 테이블 목록 세 상태 (WD-912)
 * - 예전에는 목록이 비면 무조건 「불러오는 중...」이라, 0건 응답과 조회 실패도 영원히 로딩처럼 보였다.
 * - 이제 불러오는 중 / 빈 상태 / 오류(+다시 시도)를 구분해 보인다.
 */
const SCHEMA_PATH = '/api/v1/analytics/queries/schema';
const LOADING = '테이블 정보를 불러오는 중...';
const EMPTY_TITLE = '표시할 테이블이 없습니다.';
const EMPTY_HINT = '볼 수 있는 데이터셋이 생기면 여기에 테이블과 컬럼이 보입니다.';
const ERROR_TITLE = '테이블 정보를 불러오지 못했습니다.';

test.describe('쿼리 편집기 테이블 목록 상태 (WD-912)', () => {
  test('테이블 0건이면 불러오는 중 대신 빈 상태 안내를 보인다', async ({ authenticatedPage: page }) => {
    await setupNewQueryEditorMocks(page);
    await mockApi(page, 'GET', SCHEMA_PATH, { tables: [], dialect: 'POSTGRESQL', schema: 'data' });
    await page.goto('/analytics/queries/new');

    await expect(page.getByText(EMPTY_TITLE)).toBeVisible();
    await expect(page.getByText(EMPTY_HINT)).toBeVisible();
    await expect(page.getByText(LOADING)).toHaveCount(0);
  });

  test('조회가 실패하면 오류와 다시 시도를 보이고, 다시 시도로 목록을 불러온다', async ({ authenticatedPage: page }) => {
    await setupNewQueryEditorMocks(page);
    await mockApi(page, 'GET', SCHEMA_PATH, { message: '스키마 조회 실패(테스트)' }, { status: 500 });
    await page.goto('/analytics/queries/new');

    // 전역 retry 가 1회라 오류는 약 1초 뒤에 나타난다(WD-912).
    const alert = page.getByRole('alert');
    await expect(alert).toContainText(ERROR_TITLE, { timeout: 10000 });
    await expect(alert).toContainText('스키마 조회 실패(테스트)');
    await expect(page.getByText(LOADING)).toHaveCount(0);

    const retry = page.getByRole('button', { name: '다시 시도' });
    const box = await retry.boundingBox();
    expect(box?.height ?? 0).toBeGreaterThanOrEqual(32);

    // 같은 경로를 다시 잡으면 나중 route 가 우선한다. 다시 시도 응답을 붙잡아 진행 중 상태를 확인한다.
    const { release } = await holdApi(page, 'GET', SCHEMA_PATH, 200, {
      tables: [
        {
          tableName: 't_orders',
          datasetName: '주문',
          datasetId: 1,
          columns: [{ columnName: 'region', dataType: 'VARCHAR', displayName: '지역' }],
        },
      ],
    });
    // 키보드로 누른다 — native disabled 면 포커스가 body 로 떨어지므로 aria-disabled 잠금(PendingButton)이어야 한다.
    await retry.focus();
    await page.keyboard.press('Enter');
    await expect(retry).toHaveAttribute('aria-disabled', 'true');
    await expect(retry).toBeFocused();

    release();
    await expect(page.getByRole('button', { name: 't_orders', exact: true })).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
  });

  test('응답을 기다리는 동안에는 불러오는 중만 보인다', async ({ authenticatedPage: page }) => {
    await setupNewQueryEditorMocks(page);
    const { release } = await holdApi(page, 'GET', SCHEMA_PATH, 200, { tables: [] });
    await page.goto('/analytics/queries/new');

    await expect(page.getByRole('status').filter({ hasText: LOADING })).toBeVisible();
    await expect(page.getByText(EMPTY_TITLE)).toHaveCount(0);
    await expect(page.getByRole('alert')).toHaveCount(0);

    release();
    await expect(page.getByText(EMPTY_TITLE)).toBeVisible();
  });
});
