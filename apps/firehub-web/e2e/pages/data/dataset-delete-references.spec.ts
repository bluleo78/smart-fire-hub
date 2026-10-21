import type { Route } from '@playwright/test';

import { createDatasetReferences } from '../../factories/dataset.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { mockDatasetReferences, setupDatasetMocks } from '../../fixtures/dataset.fixture';

/**
 * WD-143: 데이터셋 삭제 확인 창이 "이 데이터셋을 쓰는 곳"을 보여 준다.
 * 파이프라인 입력으로 쓰이는 데이터셋을 지우면 입력 연결(pipeline_step_input)이 CASCADE 로 조용히 빠지고, 대시보드 위젯은 원본을 잃는다.
 * 창을 열 때 GET /datasets/{id}/references 를 불러 쓰는 곳을 알리고 확인받는다 — 막지는 않는다(출력 데이터셋은 서버가 409 로 막는다).
 */
test.describe('데이터셋 삭제 — 쓰는 곳 안내 (WD-143)', () => {
  /** 목록에서 '데이터셋 1' 행의 휴지통을 눌러 확인 창을 연다. */
  async function openDeleteDialog(page: import('@playwright/test').Page) {
    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: '데이터셋 관리' })).toBeVisible();
    await page.getByRole('row', { name: /데이터셋 1/ }).getByRole('button', { name: '삭제' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toBeVisible();
    return dialog;
  }

  test('쓰는 곳이 있으면 종류별 목록·링크를 보이고, 확인하면 DELETE 를 보낸다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await mockDatasetReferences(
      page,
      createDatasetReferences({
        // 파이프라인 4개 → 앞 3개만 링크, 나머지는 "외 1개"
        pipelines: [
          { id: 10, name: '일일 적재' },
          { id: 11, name: '주간 집계' },
          { id: 12, name: '월간 보고' },
          { id: 13, name: '연간 정산' },
        ],
        dashboards: [{ id: 20, name: '영업 현황' }],
        // 조회자가 볼 수 없는(남의 비공개) 대시보드 — 이름 없이 개수만 온다. 총계에는 든다.
        hiddenDashboardCount: 2,
        proactiveJobs: [{ id: 30, name: '아침 브리핑' }],
        triggers: [{ id: 40, name: '변경 감지', pipelineId: 10, pipelineName: '일일 적재' }],
      }),
    );
    const deleteCapture = await mockApi(page, 'DELETE', '/api/v1/datasets/1', {}, { capture: true });

    const refRequest = page.waitForRequest((r) => r.url().includes('/references'));
    const dialog = await openDeleteDialog(page);
    // 삭제 대상 데이터셋의 참조를 부른다
    expect(new URL((await refRequest).url()).pathname).toBe('/api/v1/datasets/1/references');

    const box = dialog.getByTestId('dataset-references');
    await expect(box.getByText('이 데이터셋을 쓰는 곳 9곳', { exact: true })).toBeVisible();
    await expect(box).toContainText('삭제하면 아래 항목이 동작하지 않거나 입력 연결이 빠질 수 있습니다.');
    const lines = box.getByRole('listitem');
    await expect(lines).toHaveCount(4);
    await expect(lines.nth(0)).toHaveText('파이프라인 4개: 일일 적재, 주간 집계, 월간 보고 외 1개');
    await expect(lines.nth(1)).toHaveText('대시보드 3개: 영업 현황, 볼 수 없는 대시보드 2개');
    await expect(lines.nth(2)).toHaveText('스마트 작업 1개: 아침 브리핑');
    await expect(lines.nth(3)).toHaveText('트리거 1개: 변경 감지 (일일 적재)');

    // 이름은 해당 화면 링크 — 4번째 파이프라인은 줄여서 링크가 없다
    await expect(box.getByRole('link', { name: '주간 집계' })).toHaveAttribute('href', '/pipelines/11');
    await expect(box.getByRole('link', { name: '연간 정산' })).toHaveCount(0);
    await expect(box.getByRole('link', { name: '영업 현황' })).toHaveAttribute('href', '/analytics/dashboards/20');
    // 볼 수 없는 대시보드는 링크가 없다 — 대시보드 링크는 볼 수 있는 1개뿐
    await expect(box.getByRole('link', { name: /볼 수 없는/ })).toHaveCount(0);
    await expect(box.locator('a[href^="/analytics/dashboards/"]')).toHaveCount(1);
    await expect(box.getByRole('link', { name: '아침 브리핑' })).toHaveAttribute('href', '/ai-insights/jobs/30');
    // 트리거는 자기 화면이 없어 소속 파이프라인으로 보낸다
    await expect(box.getByRole('link', { name: '변경 감지 (일일 적재)' })).toHaveAttribute('href', '/pipelines/10');

    // 막지 않는다 — 확인하면 실제 삭제 요청이 나간다
    const confirm = dialog.getByRole('button', { name: '삭제' });
    await expect(confirm).toBeEnabled();
    await confirm.click();
    const req = await deleteCapture.waitForRequest();
    expect(req.url.pathname).toBe('/api/v1/datasets/1');
    await expect(page.getByText('데이터셋 "데이터셋 1"가 삭제되었습니다.')).toBeVisible();
  });

  test('참조를 불러오는 동안에는 삭제 버튼이 꺼져 있고, 응답이 오면 켜진다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    // 응답을 붙잡아 두었다가 단언 뒤에 풀어 준다 — 시간 지연이 아니라 명시적 해제로 로딩 상태를 고정한다.
    let release: (() => void) | null = null;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/references',
      async (route: Route) => {
        await held;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createDatasetReferences({ pipelines: [{ id: 10, name: '일일 적재' }] })),
        });
      },
    );

    const dialog = await openDeleteDialog(page);
    await expect(dialog.getByRole('status')).toHaveText('이 데이터셋을 쓰는 곳을 확인하는 중…');
    await expect(dialog.getByRole('button', { name: '삭제' })).toBeDisabled();

    release!();
    await expect(dialog.getByTestId('dataset-references')).toContainText('파이프라인 1개: 일일 적재');
    await expect(dialog.getByRole('button', { name: '삭제' })).toBeEnabled();
  });

  test('쓰는 곳이 없으면 경고 상자 없이 "확인되지 않았습니다" 한 줄만 보인다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    const deleteCapture = await mockApi(page, 'DELETE', '/api/v1/datasets/1', {}, { capture: true });

    const dialog = await openDeleteDialog(page);
    await expect(dialog.getByTestId('dataset-references-none')).toHaveText('이 데이터셋을 쓰는 곳이 확인되지 않았습니다.');
    await expect(dialog.getByTestId('dataset-references')).toHaveCount(0);

    await dialog.getByRole('button', { name: '삭제' }).click();
    const req = await deleteCapture.waitForRequest();
    expect(req.url.pathname).toBe('/api/v1/datasets/1');
  });

  test('참조 조회가 실패하면 안내를 보이고 삭제는 그대로 할 수 있다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await mockDatasetReferences(page, createDatasetReferences(), 500);
    const deleteCapture = await mockApi(page, 'DELETE', '/api/v1/datasets/1', {}, { capture: true });

    const dialog = await openDeleteDialog(page);
    const box = dialog.getByTestId('dataset-references');
    await expect(box.getByText('참조 정보를 불러오지 못했습니다')).toBeVisible();
    await expect(box).toContainText('삭제 후 동작하지 않거나 입력 연결이 빠질 수 있습니다.');

    // 서버가 최종 판단한다 — 조회 장애가 삭제를 막지 않는다
    await dialog.getByRole('button', { name: '삭제' }).click();
    const req = await deleteCapture.waitForRequest();
    expect(req.url.pathname).toBe('/api/v1/datasets/1');
  });

  test('창 안의 이름 링크를 누르면 그 화면으로 이동하고 창·오버레이가 남지 않는다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await mockDatasetReferences(page, createDatasetReferences({ pipelines: [{ id: 10, name: '일일 적재' }] }));

    const dialog = await openDeleteDialog(page);
    await dialog.getByRole('link', { name: '일일 적재' }).click();

    await expect(page).toHaveURL(/\/pipelines\/10$/);
    // 열린 채 라우트가 바뀌어 창이 언마운트돼도 Radix 의 스크롤 잠금·포인터 차단이 body 에 남지 않아야 한다
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    await expect.poll(() => page.evaluate(() => document.body.style.pointerEvents)).not.toBe('none');
  });
});
