import type { Locator, Page } from '@playwright/test';

import { createDataset } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 여러 요소를 한 작업(한 evaluate) 안에서 차례로 클릭한다 — 클릭 사이에 React 렌더가 끼지 않게 하려는 WD-950 시험용.
 * 못 찾는 요소가 있으면 조용히 넘어가지 않고 throw 한다(elementHandle 이 찾지 못하면 시간 초과로 던진다).
 */
async function clickInOneTask(page: Page, targets: Locator[]) {
  const handles = [];
  for (const target of targets) handles.push(await target.elementHandle());
  await page.evaluate((els) => {
    for (const el of els) (el as HTMLElement).click();
  }, handles);
}

/**
 * 데이터셋 목록 — URL 쿼리 파라미터 동기화 (#94) E2E 테스트.
 *
 * 검색·필터·정렬 상태가 URL 쿼리에 반영되어 새로고침/공유 시 복원되어야 한다.
 */
test.describe('데이터셋 목록 — URL 동기화', () => {
  const datasets = [
    createDataset({ id: 1, name: '고객 데이터셋', storageType: 'TABLE', originType: 'SOURCE', status: 'CERTIFIED' }),
    createDataset({ id: 2, name: '주문 데이터셋', storageType: 'TABLE', originType: 'DERIVED', status: 'NONE' }),
  ];

  async function setupCommon(page: import('@playwright/test').Page) {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(datasets));
  }

  test('검색어 입력 시 URL 에 q 파라미터가 추가되고 새로고침 시 복원된다 (#94)', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    await page.getByPlaceholder('데이터셋 검색...').fill('고객');

    // URL 에 q=고객 이 반영되는지 검증
    await expect.poll(() => new URL(page.url()).searchParams.get('q')).toBe('고객');

    // 새로고침 후에도 검색어가 입력란에 복원되는지 검증
    await page.reload();
    await expect(page.getByPlaceholder('데이터셋 검색...')).toHaveValue('고객');
  });

  test('상태 필터 URL 직접 진입 시 SELECT 가 해당 값으로 복원된다 (#94)', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);

    await page.goto('/data/datasets?status=CERTIFIED');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    const statusCombobox = page.getByRole('combobox').filter({ hasText: /전체 상태|인증됨|사용 중단|상태 없음/ }).first();
    await expect(statusCombobox).toContainText('인증됨');
  });

  test('즐겨찾기 토글 시 URL 에 favorite=true 가 추가되고 해제 시 제거된다 (#94)', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    const favoriteButton = page.getByRole('button', { name: '즐겨찾기', exact: true });
    await expect(favoriteButton).toHaveAttribute('aria-pressed', 'false');
    await favoriteButton.click();
    await expect.poll(() => new URL(page.url()).searchParams.get('favorite')).toBe('true');
    // 주소는 화면이 다시 그려지기 전에 먼저 바뀐다 — 버튼이 켜짐으로 그려진 뒤에 다시 눌러야 「끄기」로 처리된다.
    // 주소만 보고 바로 누르면 부하가 큰 전체 실행에서 낡은 화면의 「켜기」가 한 번 더 돌아 favorite 이 남았다.
    await expect(favoriteButton).toHaveAttribute('aria-pressed', 'true');

    await favoriteButton.click();
    await expect.poll(() => new URL(page.url()).searchParams.get('favorite')).toBeNull();
    await expect(favoriteButton).toHaveAttribute('aria-pressed', 'false');
  });

  /**
   * 이슈 WD-950 회귀 방지 — 화면이 다시 그려지기 전에 필터 둘을 잇달아 눌러도 둘 다 주소에 남아야 한다.
   * react-router 의 setSearchParams 함수형 갱신은 prev 로 렌더 시점 스냅샷을 주므로, 한 작업 안의 두 번째 호출이
   * 첫 호출을 덮어썼다. 두 클릭을 한 evaluate 안에서 DOM 으로 연달아 눌러 사이에 렌더가 끼지 않게 한다.
   */
  test('필터 둘을 화면 반영 전에 잇달아 눌러도 둘 다 주소에 남는다 (WD-950)', async ({
    authenticatedPage: page,
  }) => {
    const urls: URL[] = [];
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        urls.push(new URL(route.request().url()));
        return route.fulfill({ json: createPageResponse(datasets) });
      },
    );

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    const favoriteButton = page.getByRole('button', { name: '즐겨찾기', exact: true });
    const tempButton = page.getByRole('button', { name: '임시 데이터셋 보기' });
    await expect(favoriteButton).toHaveAttribute('aria-pressed', 'false');

    // 즐겨찾기 + 임시 데이터셋 보기 — 클릭 한 번씩으로 바뀌는 두 필터를 한 작업 안에서 연달아 누른다
    await clickInOneTask(page, [favoriteButton, tempButton]);

    await expect(favoriteButton).toHaveAttribute('aria-pressed', 'true');
    await expect(tempButton).toHaveAttribute('aria-pressed', 'true');
    const params = () => new URL(page.url()).searchParams;
    expect(params().get('favorite')).toBe('true');
    expect(params().get('showTemp')).toBe('1');

    // 마지막 목록 요청에도 두 조건이 함께 실려야 한다
    await expect.poll(() => {
      const last = urls[urls.length - 1];
      return last ? [last.searchParams.get('favoriteOnly'), last.searchParams.get('includeTemp')] : null;
    }).toEqual(['true', 'true']);
  });

  /**
   * 이슈 WD-950 회귀 방지 — 뒤로가기(POP)로 주소가 바뀐 뒤 필터를 누르면 뒤로가기 전 주소의 키가 되살아나면 안 된다.
   * 마지막으로 쓴 주소를 담는 ref 가 POP 으로 바뀐 주소에 맞춰 되맞춰지는지 확인한다.
   */
  test('뒤로가기로 주소가 바뀐 뒤 필터를 누르면 뒤로가기 전 주소가 되살아나지 않는다 (WD-950)', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);

    await page.goto('/data/datasets?sort=name&order=asc');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    const favoriteButton = page.getByRole('button', { name: '즐겨찾기', exact: true });
    const tempButton = page.getByRole('button', { name: '임시 데이터셋 보기' });
    const params = () => new URL(page.url()).searchParams;

    // 필터 변경은 replace 라 뒤로가기 항목이 안 생긴다 — 같은 화면이 마운트된 채 주소만 push 로 바뀌게 사이드바 링크를 쓴다.
    // 사이드바 「데이터셋」 → /data/datasets (정렬 없음, push), 이어서 즐겨찾기를 켜 그 항목을 replace 로 덮는다.
    await page.locator('a[href="/data/datasets"]').first().click();
    await expect.poll(() => params().get('sort')).toBeNull();
    await favoriteButton.click();
    await expect(favoriteButton).toHaveAttribute('aria-pressed', 'true');

    // 뒤로가기(POP) → 처음 주소(정렬만, favorite 없음). 화면이 마운트된 채 주소만 바뀐다
    await page.goBack();
    await expect.poll(() => params().get('sort')).toBe('name');
    await expect(favoriteButton).toHaveAttribute('aria-pressed', 'false');
    expect(params().get('favorite')).toBeNull();

    // 다른 필터를 누르면 POP 뒤 주소 + 이 필터만 — favorite 이 되살아나지 않는다
    await tempButton.click();
    await expect(tempButton).toHaveAttribute('aria-pressed', 'true');
    expect(params().get('showTemp')).toBe('1');
    expect(params().get('sort')).toBe('name');
    expect(params().get('order')).toBe('asc');
    expect(params().get('favorite')).toBeNull();
    await expect(favoriteButton).toHaveAttribute('aria-pressed', 'false');
  });

  /**
   * 이슈 WD-950 리뷰 회귀 방지 — 즐겨찾기 버튼을 렌더 전에 두 번 누르면 켰다 꺼져 주소에 favorite 이 없어야 한다.
   * 토글이 렌더 값이 아니라 마지막으로 쓴 주소로 다음 값을 정하는지 확인한다.
   */
  test('즐겨찾기를 화면 반영 전에 두 번 눌러도 꺼진다 (WD-950)', async ({ authenticatedPage: page }) => {
    await setupCommon(page);

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    const favoriteButton = page.getByRole('button', { name: '즐겨찾기', exact: true });
    await expect(favoriteButton).toHaveAttribute('aria-pressed', 'false');

    await clickInOneTask(page, [favoriteButton, favoriteButton]);

    await expect(favoriteButton).toHaveAttribute('aria-pressed', 'false');
    expect(new URL(page.url()).searchParams.get('favorite')).toBeNull();
  });

  /**
   * 이슈 WD-950 리뷰 회귀 방지 — 클릭 직후 렌더 전에 외부 이동(사이드바 링크, 쿼리 없음)이 오면
   * 이동 전에 쓴 favorite 이 되살아나지 않아야 한다 (같은 쿼리 문자열이어도 location.key 로 외부 이동을 가른다).
   */
  test('즐겨찾기 직후 사이드바 링크로 이동하면 favorite 이 되살아나지 않는다 (WD-950)', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    const favoriteButton = page.getByRole('button', { name: '즐겨찾기', exact: true });
    const tempButton = page.getByRole('button', { name: '임시 데이터셋 보기' });

    await clickInOneTask(page, [favoriteButton, page.locator('a[href="/data/datasets"]').first()]);

    await expect(favoriteButton).toHaveAttribute('aria-pressed', 'false');
    await tempButton.click();
    await expect(tempButton).toHaveAttribute('aria-pressed', 'true');
    const params = new URL(page.url()).searchParams;
    expect(params.get('showTemp')).toBe('1');
    expect(params.get('favorite')).toBeNull();
  });
});
