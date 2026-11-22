import type { Page } from '@playwright/test';

import { createPageResponse } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';

/**
 * 데이터셋 「변경 이력」 탭 페이지네이션 (WD-535).
 * 서버가 최신 순으로 자른 페이지({ content, page, size, totalElements, totalPages })만 그리고,
 * 한 페이지를 넘으면 SimplePagination(「총 N건」 포함)으로 page/size 를 바꿔 다시 요청하는지 본다.
 */

const DATASET_ID = 1;
const PAGE_SIZE = 20;

/** 가져오기 이력 한 건 — n 이 클수록 최신 */
function createImportRecord(n: number, datasetId = DATASET_ID, fileName?: string) {
  const pad = String(n).padStart(2, '0');
  return {
    id: n,
    datasetId,
    fileName: fileName ?? `history-${pad}.csv`,
    fileSize: 1024,
    fileType: 'text/csv',
    status: 'COMPLETED',
    totalRows: 1,
    successRows: 1,
    errorRows: 0,
    errorDetails: null,
    errorMessage: null,
    importedBy: 'testuser',
    startedAt: `2024-01-01T01:${pad}:00Z`,
    completedAt: `2024-01-01T01:${pad}:05Z`,
    createdAt: `2024-01-01T01:${pad}:00Z`,
  };
}

/**
 * 가져오기 이력 GET 을 실제 서버처럼 page/size 로 잘라 답한다(최신 순). 받은 요청의 쿼리를 기록해 돌려준다.
 * @param total 전체 이력 건수
 * @param options.datasetId 대상 데이터셋(기본 DATASET_ID)
 * @param options.gate 이 Promise 가 풀릴 때까지 응답을 붙잡는다 — 응답 전 화면 상태를 볼 때 쓴다
 * @param options.fileName n 번째 이력의 파일 이름(기본 history-NN.csv)
 */
async function mockPagedImports(
  page: Page,
  total: number,
  options: { datasetId?: number; gate?: Promise<void>; fileName?: (n: number) => string } = {},
) {
  const { datasetId = DATASET_ID, gate, fileName } = options;
  const requests: URLSearchParams[] = [];
  // 최신 순(id 큰 것부터) 전체 목록
  const all = Array.from({ length: total }, (_, i) =>
    createImportRecord(total - i, datasetId, fileName?.(total - i)),
  );
  await page.route(
    (url) => url.pathname === `/api/v1/datasets/${datasetId}/imports`,
    async (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const params = new URL(route.request().url()).searchParams;
      requests.push(params);
      if (gate) await gate;
      const p = Number(params.get('page') ?? 0);
      const s = Number(params.get('size') ?? PAGE_SIZE);
      const content = all.slice(p * s, p * s + s);
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(createPageResponse(content, { page: p, size: s, totalElements: total })),
      });
    },
  );
  return requests;
}

/** 이력 탭에 그려진 가져오기 이력 줄 */
function importRows(page: Page) {
  return page.getByText('testuser님이 데이터를 임포트했습니다');
}

test.beforeEach(async ({ authenticatedPage: page }) => {
  await grantDatasetPermissions(page);
});

test.describe('데이터셋 변경 이력 페이지네이션 (WD-535)', () => {
  test('25건이면 첫 페이지 20건과 총 건수를 보이고, 다음 페이지에서 page=1 로 나머지 5건을 불러온다', async ({
    authenticatedPage: page,
  }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    const requests = await mockPagedImports(page, 25);

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '이력' }).click();

    // 첫 페이지: 최신 20건(history-25 ~ history-06)과 「총 25건」
    await expect(importRows(page)).toHaveCount(20);
    await expect(page.getByText('history-25.csv', { exact: false })).toBeVisible();
    await expect(page.getByText('history-06.csv', { exact: false })).toBeVisible();
    await expect(page.getByText('history-05.csv', { exact: false })).toHaveCount(0);
    await expect(page.getByText(/총 25건/)).toBeVisible();
    // 서버에 page/size 를 보낸다
    expect(requests.some((r) => r.get('page') === '0' && r.get('size') === String(PAGE_SIZE))).toBe(true);
    // 데이터셋 생성 사건은 가장 오래된 일이라 마지막 페이지에만 보인다
    await expect(page.getByText('데이터셋을 생성했습니다')).toHaveCount(0);

    const pagination = page.getByRole('navigation', { name: '페이지네이션' });
    await expect(pagination).toBeVisible();

    // 다음 페이지 → page=1 요청, 나머지 5건
    await Promise.all([
      page.waitForRequest((req) => {
        const url = new URL(req.url());
        return (
          url.pathname === `/api/v1/datasets/${DATASET_ID}/imports` &&
          url.searchParams.get('page') === '1' &&
          url.searchParams.get('size') === String(PAGE_SIZE)
        );
      }),
      pagination.getByRole('button', { name: '다음 페이지' }).click(),
    ]);

    await expect(importRows(page)).toHaveCount(5);
    await expect(page.getByText('history-05.csv', { exact: false })).toBeVisible();
    await expect(page.getByText('history-01.csv', { exact: false })).toBeVisible();
    await expect(page.getByText('history-25.csv', { exact: false })).toHaveCount(0);
    await expect(pagination.getByRole('button', { name: '2 페이지' })).toHaveAttribute('aria-current', 'page');
    await expect(page.getByText('데이터셋을 생성했습니다')).toBeVisible();
  });

  /**
   * 다른 데이터셋으로 옮겨 가면 이전 데이터셋의 이력을 자리 표시로 남기지 않는다(WD-535).
   * 상세 페이지가 다시 마운트되지 않도록(마운트되면 쿼리 관찰자가 새로 생겨 자리 표시가 원래 없다) B 상세를 먼저 캐시에 올려 두고,
   * 이력 탭을 연 채 SPA 이동(pushState + popstate)으로 A → B 로 옮긴 뒤 B 의 이력 응답을 늦춘다.
   */
  test('다른 데이터셋으로 이동하면 응답이 오기 전에도 이전 데이터셋의 이력을 보이지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const OTHER_ID = 2;
    await setupDatasetDetailMocks(page, DATASET_ID);
    await setupDatasetDetailMocks(page, OTHER_ID);
    await mockPagedImports(page, 3);
    // B 의 이력 응답은 검증이 끝날 때까지 붙잡아 둔다 — 그 사이 화면이 A 의 이력을 보이는지 본다.
    let releaseOther: () => void = () => {};
    const otherGate = new Promise<void>((resolve) => {
      releaseOther = resolve;
    });
    const otherRequests = await mockPagedImports(page, 1, {
      datasetId: OTHER_ID,
      gate: otherGate,
      fileName: () => 'other-dataset.csv',
    });

    // B 상세를 먼저 열어(기본 탭) 캐시에 올린다 — B 의 이력은 아직 요청하지 않는다.
    await page.goto(`/data/datasets/${OTHER_ID}`);
    await expect(page.getByRole('tab', { name: '이력' })).toBeVisible();
    const spaNavigate = (path: string) =>
      page.evaluate((p) => {
        window.history.pushState({}, '', p);
        window.dispatchEvent(new PopStateEvent('popstate'));
      }, path);

    // A 의 이력 탭
    await spaNavigate(`/data/datasets/${DATASET_ID}?tab=history`);
    await expect(page.getByText('history-03.csv', { exact: false })).toBeVisible();

    // B 의 이력 탭으로 이동 — B 응답은 아직 붙잡혀 있다
    await spaNavigate(`/data/datasets/${OTHER_ID}?tab=history`);
    await expect.poll(() => otherRequests.length).toBeGreaterThan(0);
    await expect(page.getByText('history-03.csv', { exact: false })).toHaveCount(0);
    await expect(importRows(page)).toHaveCount(0);

    // 응답이 오면 B 의 이력이 보인다
    releaseOther();
    await expect(page.getByText('other-dataset.csv', { exact: false })).toBeVisible();
    await expect(importRows(page)).toHaveCount(1);
  });

  test('이력이 한 페이지 이하면 페이지네이션을 보이지 않는다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    await mockPagedImports(page, 3);

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '이력' }).click();

    await expect(importRows(page)).toHaveCount(3);
    await expect(page.getByText('데이터셋을 생성했습니다')).toBeVisible();
    await expect(page.getByRole('navigation', { name: '페이지네이션' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '다음 페이지' })).toHaveCount(0);
  });
});

/**
 * 방향 버튼으로 끝 페이지에 닿아도 포커스가 유지되는지 검증 (WD-932).
 * disabled 로 잠그면 포커스가 실린 버튼이 blur 돼 body 로 떨어진다.
 * 이력 탭은 같은 데이터셋 안에서 페이지를 넘길 때 이전 데이터를 유지해 페이지네이션이 다시 마운트되지 않으므로
 * (감사 로그는 useAuditLogs placeholderData 로 해결했고, 다른 목록 화면은 후속 이슈다) 포커스 유지를 검증하기에 알맞다.
 * 주의: 이력 탭 onPageChange 는 같은 page 로 setState 해 요청이 안 나가므로 이 화면에선 끝 가드 회귀를 잡지 못한다.
 * 가드 자체는 단위 시험 src/components/ui/simple-pagination.test.tsx 가 검증하고, 여기선 포커스 유지만 확인한다.
 */
test.describe('데이터셋 이력 페이지네이션 — 방향 버튼 이동 뒤 포커스 유지 (WD-932)', () => {
  test('2페이지에서 「이전 페이지」를 Enter 로 누르면 1페이지로 가고 포커스가 그 버튼에 남는다 (WD-932)', async ({
    authenticatedPage: page,
  }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    const requests = await mockPagedImports(page, 25);
    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '이력' }).click();

    const nav = page.getByRole('navigation', { name: '페이지네이션' });
    await nav.getByRole('button', { name: '2 페이지' }).click();
    await expect.poll(() => requests.at(-1)?.get('page')).toBe('1');
    await expect(importRows(page)).toHaveCount(5);

    const prev = nav.getByRole('button', { name: '이전 페이지' });
    await prev.focus();
    await page.keyboard.press('Enter');
    // 1페이지는 캐시에서 바로 그려질 수 있어 요청 여부가 아니라 화면(20건)으로 이동을 확인한다
    await expect(importRows(page)).toHaveCount(20);

    await expect(prev).toBeFocused();
    await expect(prev).toHaveAttribute('aria-disabled', 'true');
    await expect(nav.getByRole('button', { name: '처음 페이지' })).toHaveAttribute('aria-disabled', 'true');
    await expect(nav.getByRole('button', { name: '다음 페이지' })).not.toHaveAttribute('aria-disabled');

    // 끝에서 한 번 더 눌러도 포커스는 그대로 — 「추가 요청 없음」은 프레임 몇 개만 기다려 비교하면 거짓 통과하므로
    // 여기서 단언하지 않는다(가드는 simple-pagination.test.tsx 단위 시험이 검증)
    await page.keyboard.press('Enter');
    await expect(prev).toBeFocused();
  });

  test('1페이지에서 「다음 페이지」로 끝에 닿아도 포커스가 남고, 흐린 버튼 클릭은 무시된다 (WD-932)', async ({
    authenticatedPage: page,
  }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    const requests = await mockPagedImports(page, 25);
    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '이력' }).click();
    await expect(importRows(page)).toHaveCount(20);

    const nav = page.getByRole('navigation', { name: '페이지네이션' });
    const next = nav.getByRole('button', { name: '다음 페이지' });
    await next.focus();
    await page.keyboard.press('Enter');
    await expect.poll(() => requests.at(-1)?.get('page')).toBe('1');
    await expect(importRows(page)).toHaveCount(5);
    await expect(next).toBeFocused();
    await expect(next).toHaveAttribute('aria-disabled', 'true');
    // 마지막 페이지 버튼도 같이 사용 불가로 알려야 한다
    await expect(nav.getByRole('button', { name: '마지막 페이지' })).toHaveAttribute('aria-disabled', 'true');

    // 흐려진 버튼에 클릭 이벤트가 닿아도(보조 기술·강제 클릭) 화면이 그대로인지 본다 — 뷰포트 위치와 무관하게 이벤트를 직접 보낸다(가드 검증은 위 describe 주석 참고).
    // 「추가 요청 없음」은 프레임 몇 개만 기다려 비교하면 거짓 통과하므로 단언하지 않는다 — 가드는 simple-pagination.test.tsx 단위 시험이 검증한다.
    await next.dispatchEvent('click');
    await expect(nav.getByRole('button', { name: '2 페이지' })).toHaveAttribute('aria-current', 'page');
  });
});
