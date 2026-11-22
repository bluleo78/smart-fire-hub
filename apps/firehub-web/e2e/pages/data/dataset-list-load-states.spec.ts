import type { Page } from '@playwright/test';

import { createCategories, createDataset, createDatasets } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions } from '../../fixtures/dataset.fixture';
import { holdRequests } from '../../fixtures/hold-api';
import { expectTableErrorAlert } from '../../fixtures/table-error-alert';

/**
 * 데이터셋 목록 — 조회 실패·범위 밖 페이지 표시 (WD-146)
 *
 * 예전에는 목록 조회가 실패해도(서버 400·500·403) 「데이터셋이 없습니다」가 떴고, 낡은 북마크(?page=999)는
 * 빈 목록과 「총 15건 중 9991-15」를 보였다. 조회 실패는 오류 안내 + 「다시 시도」로, 범위 밖 페이지는 마지막 페이지로 옮긴다.
 */
test.beforeEach(async ({ authenticatedPage: page }) => {
  await grantDatasetPermissions(page);
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
});

/** 15건(size 10 → 2페이지)을 page 파라미터대로 돌려주는 목록 모킹. 범위 밖 페이지는 서버처럼 빈 content 를 준다. */
async function mockFifteenDatasets(page: Page) {
  const all = Array.from({ length: 15 }, (_, i) =>
    createDataset({ id: i + 1, name: `데이터셋 ${i + 1}`, tableName: `dataset_${i + 1}` }),
  );
  const requestedPages: string[] = [];
  await page.route(
    (url) => url.pathname === '/api/v1/datasets',
    (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const p = Number(new URL(route.request().url()).searchParams.get('page') ?? '0');
      requestedPages.push(String(p));
      const content = all.slice(p * 10, p * 10 + 10);
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(createPageResponse(content, { page: p, totalElements: 15, totalPages: 2 })),
      });
    },
  );
  return requestedPages;
}

test('WD-931 건수 「총 15건 중 1-10」 이 role=status 영역 안에 보인다', async ({ authenticatedPage: page }) => {
  await mockFifteenDatasets(page);
  await page.goto('/data/datasets');
  await expect(page.getByRole('status').filter({ hasText: '총 15건 중 1-10' })).toBeVisible();
});

test.describe('데이터셋 목록 — 조회 실패·범위 밖 페이지 (WD-146)', () => {
  test('범위 밖 페이지 주소(?page=999)는 마지막 페이지로 옮겨 남은 데이터셋을 보인다', async ({ authenticatedPage: page }) => {
    const requestedPages = await mockFifteenDatasets(page);

    await page.goto('/data/datasets?page=999');

    // 마지막 페이지(page=1)로 옮겨 11~15 를 보인다 — 빈 목록 안내가 아니다
    await expect(page).toHaveURL(/[?&]page=1(&|$)/);
    await expect(page.getByText('데이터셋 11', { exact: true })).toBeVisible();
    await expect(page.getByText('데이터셋 15', { exact: true })).toBeVisible();
    await expect(page.getByText('데이터셋이 없습니다.')).toHaveCount(0);
    // 깨진 범위(9991-15) 대신 실제 범위
    await expect(page.getByText('총 15건 중 11-15')).toBeVisible();
    await expect(page.getByText(/9991/)).toHaveCount(0);
    expect(requestedPages).toContain('999');
    expect(requestedPages).toContain('1');
  });

  test('0건이 된 목록의 뒷 페이지 주소는 첫 페이지로 옮겨 빈 목록 안내를 보인다', async ({ authenticatedPage: page }) => {
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPageResponse([], { totalElements: 0, totalPages: 0 })),
        }),
    );

    await page.goto('/data/datasets?page=3');

    await expect(page).not.toHaveURL(/page=/);
    await expect(page.getByText('데이터셋이 없습니다.')).toBeVisible();
  });

  test('숫자가 아닌 페이지 주소(?page=abc)는 첫 페이지로 읽어 page=0 으로 조회한다', async ({ authenticatedPage: page }) => {
    const requestedPages = await mockFifteenDatasets(page);

    await page.goto('/data/datasets?page=abc');

    await expect(page.getByText('데이터셋 1', { exact: true })).toBeVisible();
    await expect(page.getByText('총 15건 중 1-10')).toBeVisible();
    // 예전에는 page=NaN 을 보내 서버 400 → 빈 목록이었다
    expect(requestedPages.every((p) => p === '0')).toBe(true);
  });

  test('목록 조회가 실패하면 빈 목록 대신 서버 사유와 「다시 시도」를 보이고, 다시 시도하면 목록을 보인다', async ({ authenticatedPage: page }) => {
    let fail = true;
    let calls = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        calls += 1;
        if (fail) {
          return route.fulfill({
            status: 500,
            contentType: 'application/json',
            body: JSON.stringify({ status: 500, error: 'Internal Server Error', message: '일시적인 서버 오류입니다.' }),
          });
        }
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPageResponse(createDatasets(3))),
        });
      },
    );

    await page.goto('/data/datasets');

    await expectTableErrorAlert(page, '일시적인 서버 오류입니다.', { action: '다시 시도' });
    // 톤은 문구에만 있다 — 「다시 시도」 버튼 글자는 본문 글자색 그대로(빨갛게 물려받지 않는다)
    const retryButton = page.getByRole('cell').getByRole('button', { name: '다시 시도' });
    await expect(retryButton).toHaveCSS('color', await page.locator('body').evaluate((el) => getComputedStyle(el).color));
    await expect(page.getByText('데이터셋이 없습니다.')).toHaveCount(0);
    // 오류 상태에서는 「새 데이터셋 만들기」 빈 목록 안내도 없다
    await expect(page.getByRole('button', { name: '새 데이터셋 만들기' })).toHaveCount(0);

    // 성공으로 바꾸기 전에 한 번 더 실패 — 재시도 뒤에도 오류 행이 cell 역할로 다시 알려져야 한다.
    // 두 번째 실패 응답을 붙잡아 두고: 옛 alert 가 사라진 것을 먼저 확인한 뒤 풀어 줘서 새 alert 만 통과하게 한다
    const held = await holdRequests(page, '/api/v1/datasets', 'GET');
    await retryButton.click();
    await expect.poll(held.count).toBeGreaterThan(0);
    await expect(page.getByRole('alert').filter({ hasText: '일시적인 서버 오류입니다.' })).toHaveCount(0);
    held.release();
    await expectTableErrorAlert(page, '일시적인 서버 오류입니다.', { action: '다시 시도' });

    fail = false;
    const before = calls;
    await retryButton.click();
    await expect(page.getByText('데이터셋 1', { exact: true })).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: '일시적인 서버 오류입니다.' })).toHaveCount(0);
    expect(calls).toBeGreaterThan(before);
  });

  test('조회 권한 오류(403)는 권한 안내만 보이고 「다시 시도」를 두지 않는다', async ({ authenticatedPage: page }) => {
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) =>
        route.fulfill({
          status: 403,
          contentType: 'application/json',
          body: JSON.stringify({ status: 403, error: 'Forbidden', message: 'Access denied' }),
        }),
    );

    await page.goto('/data/datasets');

    await expectTableErrorAlert(page, '데이터셋 목록을 볼 권한이 없습니다. 관리자에게 권한을 요청하세요.');
    // alert 안만 보면 버튼이 밖에 있어도 늘 참이므로 문구를 품은 cell 안에서 센다
    const errorCell = page.getByRole('cell').filter({ hasText: '데이터셋 목록을 볼 권한이 없습니다.' });
    await expect(errorCell.getByRole('button', { name: '다시 시도' })).toHaveCount(0);
    await expect(page.getByText('데이터셋이 없습니다.')).toHaveCount(0);
  });
});

/**
 * 데이터셋 목록 — 「다시 시도」 포커스 (WD-957)
 *
 * 데이터가 없는 채로 「다시 시도」를 누르면 쿼리가 pending 으로 돌아가 실패 행이 사라지고 포커스가 body 로 떨어졌다.
 * 누르는 즉시 표로 옮겨 재조회 중에도 위치를 잃지 않게 하고, 다시 실패해 버튼이 돌아왔을 때만 버튼으로 되돌린다.
 */
test.describe('데이터셋 목록 — 「다시 시도」 포커스 (WD-957)', () => {
  type Outcome = { status: number; body: unknown };
  const serverError: Outcome = {
    status: 500,
    body: { status: 500, error: 'Internal Server Error', message: '일시적인 서버 오류입니다.' },
  };

  const threeDatasets: Outcome = { status: 200, body: createPageResponse(createDatasets(3)) };

  /** 500 으로 진입 → 응답을 outcome 으로 바꾸고 붙잡은 뒤 「다시 시도」를 눌러, 재조회 중 표에 포커스가 있는 상태까지 만든다. */
  async function failThenRetry(page: Page, outcome: Outcome, via: 'keyboard' | 'mouse' = 'keyboard') {
    let current: Outcome = serverError;
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        return route.fulfill({ status: current.status, contentType: 'application/json', body: JSON.stringify(current.body) });
      },
    );
    await page.goto('/data/datasets');
    const table = page.getByRole('table', { name: '데이터셋 목록' });
    const retry = page.getByRole('cell').getByRole('button', { name: '다시 시도' });
    await expect(retry).toBeVisible();

    current = outcome;
    const held = await holdRequests(page, '/api/v1/datasets', 'GET');
    if (via === 'mouse') {
      await retry.click();
    } else {
      await retry.focus();
      await page.keyboard.press('Enter');
    }
    await expect.poll(held.count).toBeGreaterThan(0);
    await expect(table).toBeFocused();
    return {
      table,
      retry,
      held,
      setOutcome: (next: Outcome) => {
        current = next;
      },
    };
  }

  test('재조회 중 표에 포커스가 보이고, 다시 실패하면 「다시 시도」로 돌아온다', async ({ authenticatedPage: page }) => {
    const { table, retry, held } = await failThenRetry(page, serverError);
    // 포커스 표시: --ring 원색 2px 안쪽 outline(스크롤 상자에 잘리지 않게 -2px)
    const ring = await page.evaluate(() => {
      const probe = document.createElement('span');
      probe.style.color = 'var(--ring)';
      document.body.appendChild(probe);
      const color = getComputedStyle(probe).color;
      probe.remove();
      return color;
    });
    await expect(table).toHaveCSS('outline-style', 'solid');
    await expect(table).toHaveCSS('outline-width', '2px');
    await expect(table).toHaveCSS('outline-offset', '-2px');
    await expect(table).toHaveCSS('outline-color', ring);

    held.release();
    await expect(retry).toBeVisible();
    await expect(retry).toBeFocused();
  });

  test('성공하면 목록 표에 포커스가 남는다', async ({ authenticatedPage: page }) => {
    const { table, held } = await failThenRetry(page, threeDatasets);
    held.release();
    await expect(page.getByText('데이터셋 1', { exact: true })).toBeVisible();
    await expect(table).toBeFocused();
  });

  test('빈 목록으로 성공해도 표에 남는다', async ({ authenticatedPage: page }) => {
    const { table, held } = await failThenRetry(page, { status: 200, body: createPageResponse([]) });
    held.release();
    await expect(page.getByText('데이터셋이 없습니다.')).toBeVisible();
    await expect(table).toBeFocused();
  });

  test('권한 없음으로 확정되면 표에 남는다', async ({ authenticatedPage: page }) => {
    const { table, held } = await failThenRetry(page, {
      status: 403,
      body: { status: 403, error: 'Forbidden', message: 'Access denied' },
    });
    held.release();
    await expectTableErrorAlert(page, '데이터셋 목록을 볼 권한이 없습니다. 관리자에게 권한을 요청하세요.');
    await expect(page.getByRole('button', { name: '다시 시도' })).toHaveCount(0);
    await expect(table).toBeFocused();
  });

  test('재조회 중 사용자가 옮긴 포커스는 빼앗지 않는다', async ({ authenticatedPage: page }) => {
    const { retry, held } = await failThenRetry(page, serverError);
    const search = page.getByPlaceholder('데이터셋 검색...');
    await search.focus();
    held.release();
    await expect(retry).toBeVisible();
    await expect(search).toBeFocused();
  });

  test('성공한 뒤의 무관한 실패는 포커스를 옮기지 않는다', async ({ authenticatedPage: page }) => {
    const { table, retry, held, setOutcome } = await failThenRetry(page, threeDatasets);
    held.release();
    await expect(page.getByText('데이터셋 1', { exact: true })).toBeVisible();
    await expect(table).toBeFocused();

    // 다른 검색어 조회가 실패해도, 이미 끝난 「다시 시도」의 포커스 이동이 되살아나지 않는다
    setOutcome(serverError);
    await page.evaluate(() => {
      history.pushState({}, '', '/data/datasets?q=zzz');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await expect(retry).toBeVisible({ timeout: 15000 });
    await expect(table).toBeFocused();
  });

  test('마우스로 눌러 다시 실패해도 버튼에 포커스가 있다', async ({ authenticatedPage: page }) => {
    const { table, retry, held } = await failThenRetry(page, serverError, 'mouse');
    // 마우스 클릭이 옮긴 포커스는 :focus-visible 이 아니라 표에 outline 이 그려지지 않는다
    await expect(table).toHaveCSS('outline-style', 'none');
    held.release();
    await expect(retry).toBeVisible();
    await expect(retry).toBeFocused();
  });

  test('표는 재시도 뒤 포커스가 떠나면 다시 포커스를 받지 않는다', async ({ authenticatedPage: page }) => {
    const { table, held } = await failThenRetry(page, threeDatasets);
    held.release();
    await expect(page.getByText('데이터셋 1', { exact: true })).toBeVisible();
    await expect(table).toBeFocused();
    await expect(table).toHaveAttribute('tabindex', '-1');

    // 포커스가 떠난 뒤 표를 마우스로 누르면 그때 tabindex 를 뗀다 — 늘 두면 표 빈 곳 클릭이 포커스를 가져간다
    await page.keyboard.press('Tab');
    await page.getByRole('columnheader', { name: '유형' }).click();
    await expect(table).not.toHaveAttribute('tabindex', /.*/);
    await expect(table).not.toBeFocused();
  });

  test('재조회 중 창(단축키 도움말)을 열었다 닫아도 표로 돌아오고 「다시 시도」로 이어진다', async ({ authenticatedPage: page }) => {
    const { table, retry, held } = await failThenRetry(page, serverError);
    await page.keyboard.press('Shift+?');
    const dialog = page.getByRole('dialog', { name: '키보드 단축키' });
    await expect(dialog).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    // 창이 되돌려 줄 자리(표)가 그사이 포커스를 못 받게 되면 body 로 떨어진다
    await expect(table).toBeFocused();
    // 포커스를 쥔 표를 눌러도 놓지 않는다
    await page.getByRole('columnheader', { name: '유형' }).click();
    await expect(table).toBeFocused();
    held.release();
    await expect(retry).toBeFocused();
  });

  test('재조회 중 다른 창에 다녀와도 포커스가 남아 「다시 시도」로 돌아온다', async ({ authenticatedPage: page }) => {
    const { table, retry, held } = await failThenRetry(page, serverError);
    // 창을 떠나면 브라우저는 activeElement 를 그대로 둔 채 그 요소에 blur 만 보낸다 — headless 는 창 포커스를 잃지 않아 그 blur 를 직접 보낸다
    await table.evaluate((el) => el.dispatchEvent(new FocusEvent('blur')));
    await expect(table).toHaveAttribute('tabindex', '-1');
    await expect(table).toBeFocused();
    held.release();
    await expect(retry).toBeFocused();
  });
});

/**
 * 데이터셋 목록 — 오프라인 연결 대기 (WD-966)
 *
 * 오류 뒤 오프라인에서 「다시 시도」를 누르면 쿼리가 pending + paused 라 로딩도 실패도 아니어서 「데이터셋이 없습니다」로 떨어졌다.
 * 연결 대기 안내를 보이고, 연결이 돌아오면 자동으로 다시 불러와 목록을 채운다.
 */
test.describe('데이터셋 목록 — 오프라인 연결 대기 (WD-966)', () => {
  // 재시도 지연(TanStack 기본 첫 재시도 1초)보다 충분히 긴 대기 — 멈춘 재시도가 이 시간 동안 요청을 내지 않는지 본다
  const RETRY_HOLD_MS = 2500;
  const OFFLINE_TEXT = '인터넷 연결이 없어 데이터셋 목록을 불러오지 못하고 있습니다. 연결되면 자동으로 다시 불러옵니다.';

  /** 데이터셋 목록 GET 을 항상 200(3건)으로 응답시킨다 — 오프라인 전환 전후 목록 채움 검증용 */
  async function mockDatasetsOk(page: Page) {
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPageResponse(createDatasets(3))),
        });
      },
    );
  }

  test('오류 뒤 오프라인에서 다시 시도하면 빈 목록 대신 연결 대기 안내를 보이고, 연결되면 목록을 채운다', async ({ authenticatedPage: page }) => {
    let fail = true;
    let calls = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        calls += 1;
        if (fail) {
          return route.fulfill({
            status: 500,
            contentType: 'application/json',
            body: JSON.stringify({ status: 500, error: 'Internal Server Error', message: '일시적인 서버 오류입니다.' }),
          });
        }
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPageResponse(createDatasets(3))),
        });
      },
    );

    await page.goto('/data/datasets');
    await expectTableErrorAlert(page, '일시적인 서버 오류입니다.', { action: '다시 시도' });
    const before = calls;

    await page.context().setOffline(true);
    await page.getByRole('button', { name: '다시 시도' }).click();

    await expect(page.getByText(OFFLINE_TEXT)).toBeVisible();
    await expect(page.getByText('데이터셋이 없습니다.')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '새 데이터셋 만들기' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '다시 시도' })).toHaveCount(0);
    expect(calls).toBe(before);

    fail = false;
    await page.context().setOffline(false);
    await expect(page.getByText('데이터셋 1', { exact: true })).toBeVisible();
    await expect(page.getByText(OFFLINE_TEXT)).toHaveCount(0);
    expect(calls).toBeGreaterThan(before);
  });

  test('첫 진입부터 오프라인이면 빈 목록 안내 대신 연결 대기 안내를 보이고, 연결되면 목록을 채운다', async ({ authenticatedPage: page }) => {
    await mockDatasetsOk(page);

    // 온라인에서 보기 설정만 다른 주소(?size=20 — 필터가 아니라 쿼리 키만 다르다)를 먼저 열어 페이지·AI 패널 지연 조각을 받아 둔다.
    // 서버(dev/preview)에 의존하는 모듈 경로 import 없이도, 오프라인 이동 때 조각 로딩 실패로 전체 화면 오류가 나지 않는다
    await page.goto('/data/datasets?size=20');
    await expect(page.getByText('데이터셋 1', { exact: true })).toBeVisible();

    await page.context().setOffline(true);
    // 사이드바 링크로 size 10 주소(캐시 없는 쿼리 키)로 SPA 이동 — 첫 조회가 오프라인에서 대기한다
    await page.getByRole('link', { name: '데이터셋', exact: true }).first().click();
    await expect(page).not.toHaveURL(/size=/);

    await expect(page.getByText(OFFLINE_TEXT)).toBeVisible();
    // 실패가 아닌 중립 대기 안내라 role=status(polite) 영역 안에 있어야 한다
    await expect(page.getByRole('status').filter({ hasText: OFFLINE_TEXT })).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: OFFLINE_TEXT })).toHaveCount(0);
    await expect(page.getByText('데이터셋이 없습니다.')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '새 데이터셋 만들기' })).toHaveCount(0);

    await page.context().setOffline(false);
    await expect(page.getByText('데이터셋 1', { exact: true })).toBeVisible();
    await expect(page.getByText(OFFLINE_TEXT)).toHaveCount(0);
  });

  test('목록을 본 뒤 오프라인에서 필터를 바꾸면 연결 대기 안내를 보인다', async ({ authenticatedPage: page }) => {
    await mockDatasetsOk(page);
    await page.goto('/data/datasets');
    await expect(page.getByText('데이터셋 1', { exact: true })).toBeVisible();

    await page.context().setOffline(true);
    await page.getByRole('combobox', { name: '저장 방식 필터' }).click();
    // 현재 값(전체)이 아닌 다른 선택지를 이름으로 고른다 — 새 조건의 조회는 캐시가 없어 오프라인에서 대기한다
    await page.getByRole('option', { name: '문서', exact: true }).click();

    await expect(page.getByText(OFFLINE_TEXT)).toBeVisible();
    await expect(page.getByText('조건에 맞는 데이터셋이 없습니다.')).toHaveCount(0);
    await expect(page.getByText('데이터셋이 없습니다.')).toHaveCount(0);

    // 연결이 돌아오면 고른 storageType 으로 GET 이 나가고 목록이 다시 그려지며 안내가 사라진다
    const recovered = page.waitForRequest(
      (req) => req.method() === 'GET' && new URL(req.url()).pathname === '/api/v1/datasets' && new URL(req.url()).searchParams.has('storageType'),
    );
    await page.context().setOffline(false);
    const req = await recovered;
    expect(new URL(req.url()).searchParams.get('storageType')).toBeTruthy();
    await expect(page.getByText('데이터셋 1', { exact: true })).toBeVisible();
    await expect(page.getByText(OFFLINE_TEXT)).toHaveCount(0);
  });

  test('연결 복구 뒤 다시 실패하면 오류 행과 「다시 시도」로 돌아오고 포커스가 버튼에 있다', async ({ authenticatedPage: page }) => {
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        return route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ status: 500, error: 'Internal Server Error', message: '일시적인 서버 오류입니다.' }),
        });
      },
    );
    await page.goto('/data/datasets');
    await expectTableErrorAlert(page, '일시적인 서버 오류입니다.', { action: '다시 시도' });

    await page.context().setOffline(true);
    await page.getByRole('button', { name: '다시 시도' }).focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(OFFLINE_TEXT)).toBeVisible();

    // 연결이 돌아와 재조회가 다시 500 이면 오류 행·버튼·포커스가 돌아와야 한다
    await page.context().setOffline(false);
    await expectTableErrorAlert(page, '일시적인 서버 오류입니다.', { action: '다시 시도' });
    await expect(page.getByText(OFFLINE_TEXT)).toHaveCount(0);
    await expect(page.getByRole('button', { name: '다시 시도' })).toBeFocused();
  });

  test('첫 요청 실패 뒤 재시도를 탭 숨김으로 멈추면 연결 없음 안내 대신 불러오는 중을 보인다', async ({ authenticatedPage: page }) => {
    let calls = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        calls += 1;
        // 첫 요청만 실패시켜 재시도 대기에 들어가게 한다
        if (calls === 1) {
          return route.fulfill({
            status: 500,
            contentType: 'application/json',
            body: JSON.stringify({ status: 500, error: 'Internal Server Error', message: '일시적인 서버 오류입니다.' }),
          });
        }
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPageResponse(createDatasets(3))),
        });
      },
    );

    // 처음부터 탭이 숨겨진 상태로 연다 — 첫 요청은 나가고, 실패 뒤 재시도는 focusManager 에 의해 paused 가 된다
    await page.addInitScript(() => {
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
      Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
    });
    await page.goto('/data/datasets');
    await expect.poll(() => calls).toBe(1);

    // 양성 신호: 표 안 스켈레톤(TableSkeletonRows 가 표에 거는 aria-busy)이 보이고 연결 없음 안내는 없다
    await expect(page.locator('table[aria-busy="true"] tbody tr[aria-hidden="true"]').first()).toBeVisible();
    await expect(page.getByText(OFFLINE_TEXT)).toHaveCount(0);
    // 재시도 지연(기본 1초)보다 충분히 긴 시간 동안에도 재시도 요청이 나가지 않아야 한다(멈춘 상태 유지)
    await page.waitForTimeout(RETRY_HOLD_MS);
    expect(calls).toBe(1);
    await expect(page.locator('table[aria-busy="true"] tbody tr[aria-hidden="true"]').first()).toBeVisible();
    await expect(page.getByText(OFFLINE_TEXT)).toHaveCount(0);
    await expect(page.getByText('데이터셋이 없습니다.')).toHaveCount(0);
    await expect(page.getByRole('status').filter({ hasText: '인터넷 연결이 없어' })).toHaveCount(0);

    // 탭이 다시 보이면 재시도가 나가 목록을 채운다
    await page.evaluate(() => {
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' });
      Object.defineProperty(document, 'hidden', { configurable: true, get: () => false });
      // 실제 브라우저처럼 버블링하는 이벤트로 보낸다(TanStack 은 window 에서 듣는다)
      document.dispatchEvent(new Event('visibilitychange', { bubbles: true }));
    });
    await expect(page.getByText('데이터셋 1', { exact: true })).toBeVisible();
    expect(calls).toBeGreaterThan(1);
  });
});
