import type { Page } from '@playwright/test';

import { createDatasets } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetMocks } from '../../fixtures/dataset.fixture';

/**
 * 데이터셋 상세 — 존재하지 않는 ID 처리
 *
 * #96: 404 로 dataset === undefined 가 되어도 스켈레톤 분기를 타 영원히 스켈레톤이 보이던 버그 — isError 분기를 추가했다.
 * WD-581: 그 분기가 토스트 + 목록 이동이라 주소가 바뀌어, 토스트가 사라지면 공유받은 링크가 왜 목록으로 왔는지 알 수 없었다.
 *   이제 첫 조회 404 는 주소를 그대로 두고 화면 안에 「데이터셋을 찾을 수 없습니다.」와 「목록으로」를 보인다(토스트 없음).
 *   500·네트워크 같은 다른 실패는 예전처럼 토스트 + 목록 이동을 유지한다.
 */
test.describe('데이터셋 상세 — 존재하지 않는 ID 처리 (#96, WD-581)', () => {
  /** 「최근 접근」 이력 저장 키 — 인증 fixture 의 사용자(1)·테넌트(1) 기준 (WD-390 테스트와 같은 키) */
  const RECENT_KEY = 'sfh-recent-datasets:1:1';
  const readRecentIds = (page: Page) =>
    page.evaluate(
      (key) => (JSON.parse(localStorage.getItem(key) ?? '[]') as { id: number }[]).map((d) => d.id),
      RECENT_KEY,
    );
  /** 페이지를 열기 전에 「최근 접근」 이력을 심는다 — 방문 시각은 지금으로 채운다 */
  const seedRecent = (page: Page, items: { id: number; name: string; tableName: string }[]) =>
    page.addInitScript(
      ({ key, list }) => localStorage.setItem(key, JSON.stringify(list)),
      { key: RECENT_KEY, list: items.map((item) => ({ ...item, accessedAt: new Date().toISOString() })) },
    );

  test('404 면 주소를 그대로 두고 화면 안 안내를 보이며, 토스트는 없고 「목록으로」로 목록에 간다', async ({
    authenticatedPage: page,
  }) => {
    // 「목록으로」 뒤에 그려질 데이터셋 목록
    await setupDatasetMocks(page);
    await mockApi(page, 'GET', '/api/v1/datasets/999999', { message: '데이터셋을 찾을 수 없습니다.' }, { status: 404 });

    await page.goto('/data/datasets/999999');

    // 스크린리더가 제목으로 찾을 수 있는 화면 안 안내
    await expect(page.getByRole('heading', { name: '데이터셋을 찾을 수 없습니다.' })).toBeVisible();
    // 주소가 그대로 남는다 — 목록으로 튕기지 않는다
    await expect(page).toHaveURL(/\/data\/datasets\/999999$/);
    // 같은 내용의 토스트는 띄우지 않는다
    await expect(page.locator('[data-sonner-toast]')).toHaveCount(0);

    await page.getByRole('button', { name: '목록으로' }).click();
    await expect(page).toHaveURL(/\/data\/datasets$/);
    await expect(page.getByRole('heading', { name: '데이터셋을 찾을 수 없습니다.' })).toHaveCount(0);
  });

  test('404 면 화면 안 안내로 바뀌어도 그 데이터셋은 「최근 접근」 이력에서 빠진다 (WD-390)', async ({
    authenticatedPage: page,
  }) => {
    await seedRecent(page, [
      { id: 999999, name: '지워진 데이터셋', tableName: 'gone_ds' },
      { id: 2, name: '데이터셋 2', tableName: 'dataset_2' },
    ]);
    await setupDatasetMocks(page);
    await mockApi(page, 'GET', '/api/v1/datasets/999999', { message: '데이터셋을 찾을 수 없습니다.' }, { status: 404 });

    await page.goto('/data/datasets/999999');

    await expect(page.getByRole('heading', { name: '데이터셋을 찾을 수 없습니다.' })).toBeVisible();
    await expect.poll(() => readRecentIds(page)).toEqual([2]);
  });

  test('목록에서 연 데이터셋이 404 면 「목록으로」에서 목록을 다시 받아 지워진 행이 남지 않는다 (WD-581)', async ({
    authenticatedPage: page,
  }) => {
    await setupDatasetMocks(page);
    // 목록 캐시(staleTime 30초)가 살아 있는 동안 돌아오므로, 무효화하지 않으면 목록 GET 이 다시 나가지 않는다
    const listCapture = await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(createDatasets(5)), {
      capture: true,
    });
    await mockApi(page, 'GET', '/api/v1/datasets/1', { message: '데이터셋을 찾을 수 없습니다.' }, { status: 404 });

    await page.goto('/data/datasets');
    await expect(page.getByRole('cell', { name: '데이터셋 1', exact: true })).toBeVisible();
    await expect.poll(() => listCapture.requests.length).toBe(1);

    // 행을 눌러 상세로 간다(앱 안 이동이라 목록 캐시가 유지된다)
    await page.getByRole('cell', { name: '데이터셋 1', exact: true }).click();
    await expect(page.getByRole('heading', { name: '데이터셋을 찾을 수 없습니다.' })).toBeVisible();
    await page.getByRole('button', { name: '목록으로' }).click();

    await expect(page).toHaveURL(/\/data\/datasets$/);
    await expect.poll(() => listCapture.requests.length).toBeGreaterThan(1);
  });

  test('404 데이터셋에서 다른 404 데이터셋으로 옮겨 가도 두 번째 것도 「최근 접근」에서 빠진다 (WD-581)', async ({
    authenticatedPage: page,
  }) => {
    await seedRecent(page, [
      { id: 801, name: '지워진 1', tableName: 'gone_1' },
      { id: 802, name: '지워진 2', tableName: 'gone_2' },
      { id: 2, name: '데이터셋 2', tableName: 'dataset_2' },
    ]);
    await setupDatasetMocks(page);
    await mockApi(page, 'GET', '/api/v1/datasets/801', { message: '데이터셋을 찾을 수 없습니다.' }, { status: 404 });
    await mockApi(page, 'GET', '/api/v1/datasets/802', { message: '데이터셋을 찾을 수 없습니다.' }, { status: 404 });

    await page.goto('/data/datasets/801');
    await expect(page.getByRole('heading', { name: '데이터셋을 찾을 수 없습니다.' })).toBeVisible();
    await expect.poll(() => readRecentIds(page)).toEqual([802, 2]);

    // 새로고침 없이(같은 페이지 컴포넌트 그대로) 다른 없는 데이터셋 주소로 옮긴다
    await page.evaluate(() => {
      window.history.pushState({}, '', '/data/datasets/802');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await expect(page).toHaveURL(/\/data\/datasets\/802$/);
    await expect.poll(() => readRecentIds(page)).toEqual([2]);
  });

  test('404 가 아닌 실패(500)는 예전처럼 토스트 후 목록으로 이동하고 이력은 지우지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await seedRecent(page, [{ id: 5, name: '데이터셋 5', tableName: 'dataset_5' }]);
    await setupDatasetMocks(page);
    await mockApi(page, 'GET', '/api/v1/datasets/5', { message: '서버 오류' }, { status: 500 });

    await page.goto('/data/datasets/5');

    await expect(page.locator('[data-sonner-toast]').filter({ hasText: '데이터셋을 찾을 수 없습니다.' }).first()).toBeVisible();
    await expect(page).toHaveURL(/\/data\/datasets$/);
    await expect(page.getByRole('heading', { name: '데이터셋을 찾을 수 없습니다.' })).toHaveCount(0);
    // 일시적 실패는 「최근 접근」에서 빼지 않는다 (WD-390)
    expect(await readRecentIds(page)).toEqual([5]);
  });
});
