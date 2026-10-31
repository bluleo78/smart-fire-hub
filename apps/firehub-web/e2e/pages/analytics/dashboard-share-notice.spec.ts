import type { DashboardShareImpact } from '../../../src/types/analytics';
import { createDashboardListItem } from '../../factories/analytics.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 대시보드 「공개 대시보드」 스위치 안내(WD-325).
 * 공유를 켜면 위젯이 쓰는 내 비공개 차트·저장 쿼리가 함께 공개되고, 꺼도 되돌리지 않는다 — 설정 창이 서버가 센 개수(share-impact)로
 * 켤 때는 새로 공개될 것을, 끌 때는 계속 공개로 남는 것을 알리는지 확인한다.
 */
test.describe('대시보드 공유 안내 (WD-325)', () => {
  const impact: DashboardShareImpact = {
    privateChartCount: 2,
    privateQueryCount: 1,
    sharedChartCount: 3,
    sharedQueryCount: 0,
  };

  test('비공개 대시보드에서 공개를 켜면 함께 공개될 내 차트·저장 쿼리 개수를 알리고, 저장은 isShared=true 로 간다', async ({
    authenticatedPage: page,
  }) => {
    const dashboard = createDashboardListItem({ id: 1, name: '비공개 대시보드', isShared: false });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards', createPageResponse([dashboard]));
    const impactCapture = await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1/share-impact', impact, {
      capture: true,
    });
    const putCapture = await mockApi(page, 'PUT', '/api/v1/analytics/dashboards/1', { ...dashboard, isShared: true }, {
      capture: true,
    });

    await page.goto('/analytics/dashboards');
    await page.getByRole('row', { name: /비공개 대시보드/ }).getByLabel('설정').click();
    const dialog = page.getByRole('dialog', { name: '대시보드 설정' });
    await expect(dialog).toBeVisible();
    await impactCapture.waitForRequest();

    // 끈 상태에서는 안내가 없다(비공개 → 비공개)
    await expect(dialog.getByTestId('dashboard-share-notice')).toHaveCount(0);

    await dialog.getByLabel('공개 대시보드').click();
    await expect(dialog.getByTestId('dashboard-share-notice')).toHaveText(
      '이 대시보드의 내 차트 2개·저장 쿼리 1개도 함께 공개됩니다. 앞으로 넣는 내 차트·저장 쿼리도 공개됩니다.',
    );

    await dialog.getByRole('button', { name: '저장' }).click();
    expect((await putCapture.waitForRequest()).payload).toMatchObject({ isShared: true });
  });

  test('공개 대시보드에서 공개를 끄면 계속 공개로 남는 내 차트 개수와 되돌리는 방법을 알린다(0개 쪽은 빼고)', async ({
    authenticatedPage: page,
  }) => {
    const dashboard = createDashboardListItem({ id: 1, name: '공개 대시보드A', isShared: true });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards', createPageResponse([dashboard]));
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1/share-impact', impact);

    await page.goto('/analytics/dashboards');
    await page.getByRole('row', { name: /공개 대시보드A/ }).getByLabel('설정').click();
    const dialog = page.getByRole('dialog', { name: '대시보드 설정' });

    // 켜진 채로는 앞으로 넣는 것도 공개된다고만
    await expect(dialog.getByTestId('dashboard-share-notice')).toHaveText(
      '이 대시보드에 넣는 내 차트·저장 쿼리도 함께 공개됩니다.',
    );

    await dialog.getByLabel('공개 대시보드').click();
    await expect(dialog.getByTestId('dashboard-share-notice')).toHaveText(
      '함께 공개된 내 차트 3개는 계속 공개 상태입니다. 차트·저장 쿼리 목록에서 각각 비공개로 바꿀 수 있습니다.',
    );
  });

  test('개수 조회가 실패해도 설정 창은 쓸 수 있고, 켤 때는 개수 없는 안내로 물러난다', async ({ authenticatedPage: page }) => {
    const dashboard = createDashboardListItem({ id: 1, name: '조회 실패 대시보드', isShared: false });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards', createPageResponse([dashboard]));
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1/share-impact', { message: '오류' }, { status: 500 });

    await page.goto('/analytics/dashboards');
    await page.getByRole('row', { name: /조회 실패 대시보드/ }).getByLabel('설정').click();
    const dialog = page.getByRole('dialog', { name: '대시보드 설정' });
    await dialog.getByLabel('공개 대시보드').click();
    await expect(dialog.getByTestId('dashboard-share-notice')).toHaveText(
      '이 대시보드에 넣는 내 차트·저장 쿼리도 함께 공개됩니다.',
    );
  });

  test('새 대시보드 만들기에서 공개를 켜면 넣는 차트·저장 쿼리도 함께 공개된다고 알린다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards', createPageResponse([]));
    await page.goto('/analytics/dashboards');
    await page.getByRole('button', { name: /새 대시보드/ }).first().click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByTestId('dashboard-share-notice')).toHaveCount(0);
    await dialog.getByLabel('공개 대시보드').click();
    await expect(dialog.getByTestId('dashboard-share-notice')).toHaveText(
      '이 대시보드에 넣는 내 차트·저장 쿼리도 함께 공개됩니다.',
    );
  });
});
