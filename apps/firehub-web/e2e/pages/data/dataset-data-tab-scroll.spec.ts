import { createCategories, createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 데이터 탭 이중 스크롤 회귀 가드.
 *
 * 배경: 테이블 스크롤러 높이를 `calc(100vh - 300px)` 매직넘버로 잡았는데, 위쪽 크롬
 * (브레드크럼+타이틀+파이프라인 칩+탭바+툴바)의 실제 높이보다 88px 작았다. 그 결과
 * `main`(88px)과 테이블(1636px)이 **동시에 스크롤**됐고, 테이블 끝에 닿으면 페이지가
 * 딸려 움직여 헤더가 흔들렸다.
 *
 * 핵심 단언은 "스크롤 가능한 세로 컨테이너가 정확히 하나"다. `main`이 조금이라도 넘치면
 * 그 순간 이중 스크롤이므로, 오버플로 0을 직접 확인한다.
 */
test.describe('데이터 탭 스크롤 구조', () => {
  const datasetDetail = createDatasetDetail({
    id: 1,
    rowCount: 200,
    columns: [
      createColumn({ id: 1, columnName: 'id', displayName: 'ID', dataType: 'INTEGER', isPrimaryKey: true }),
      createColumn({
        id: 2,
        columnName: 'name',
        displayName: '이름',
        dataType: 'TEXT',
        isPrimaryKey: false,
        columnOrder: 1,
      }),
    ],
  });

  /** 테이블이 확실히 넘치도록 충분한 행을 준다. */
  const rows = Array.from({ length: 200 }, (_, i) => ({
    _id: 1000 + i,
    id: i + 1,
    name: `행 ${i + 1}`,
  }));

  async function setupMocks(page: import('@playwright/test').Page) {
    await mockApi(page, 'GET', '/api/v1/datasets/1', datasetDetail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/1/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/1/stats', []);
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/data',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            columns: datasetDetail.columns,
            rows,
            page: 0,
            size: 200,
            totalElements: 200,
            totalPages: 1,
          }),
        })
    );
  }

  /** main 안(사이드바 제외)의 세로 스크롤 가능 컨테이너를 전부 센다. */
  async function countVerticalScrollers(page: import('@playwright/test').Page) {
    return page.evaluate(() => {
      const main = document.querySelector('main');
      if (!main) return { mainOverflow: -1, inner: [] as string[] };
      const inner = [...main.querySelectorAll('*')]
        .filter((el) => {
          const s = getComputedStyle(el);
          return /auto|scroll/.test(s.overflowY) && el.scrollHeight > el.clientHeight + 1;
        })
        .map((el) => (typeof el.className === 'string' ? el.className : el.tagName));
      return { mainOverflow: main.scrollHeight - main.clientHeight, inner };
    });
  }

  test('세로 스크롤러가 테이블 하나뿐이고 main은 넘치지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 700 });
    await setupMocks(page);

    await page.goto('/data/datasets/1?tab=data');
    await expect(page.getByRole('tab', { name: '데이터' })).toHaveAttribute('data-state', 'active');
    await expect(page.getByRole('heading', { name: /데이터 \(200행\)/ })).toBeVisible();
    await expect(page.getByText('행 1', { exact: true })).toBeVisible();

    const result = await expect
      .poll(async () => {
        const r = await countVerticalScrollers(page);
        // 테이블이 실제로 넘치는 상태가 될 때까지 기다린다 — 아니면 단언이 공허해진다
        return r.inner.length > 0 ? r : null;
      })
      .not.toBeNull()
      .then(() => countVerticalScrollers(page));

    // 테이블만 스크롤한다
    expect(result.inner).toHaveLength(1);
    // main은 한 픽셀도 넘치지 않는다 — 넘치는 순간 이중 스크롤이다
    expect(result.mainOverflow).toBe(0);
  });

  test('테이블 스크롤러 높이가 뷰포트 단위 매직넘버로 계산되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    // 위 오버플로 단언만으로는 `calc(100vh - 300px)`를 되돌려도 통과한다(뮤테이션으로 확인).
    // E2E 모킹 화면은 상단 크롬이 실제보다 짧아(파이프라인 칩 행 등이 없다) 300px 가정이
    // 우연히 들어맞기 때문이다. 결함이 재현되는 조건은 크롬 높이 > 236px인데 그건 실제 데이터에서만
    // 나온다. 따라서 **원인 자체**(뷰포트 단위로 남는 높이를 추정하는 것)를 직접 금지한다.
    await page.setViewportSize({ width: 1280, height: 700 });
    await setupMocks(page);
    await page.goto('/data/datasets/1?tab=data');
    await expect(page.getByText('행 1', { exact: true })).toBeVisible();

    const offenders = await page.evaluate(() => {
      const main = document.querySelector('main');
      if (!main) return ['main 없음'];
      return [...main.querySelectorAll<HTMLElement>('*')]
        .filter((el) => {
          const s = getComputedStyle(el);
          if (!/auto|scroll/.test(s.overflowY)) return false;
          // 인라인 스타일 원문에 vh/dvh가 남아 있으면 매직넘버 계산이다
          return /\d(?:d|s|l)?vh/.test(el.style.height + el.style.maxHeight);
        })
        .map((el) => `${el.tagName}: ${el.style.height} / ${el.style.maxHeight}`);
    });
    expect(offenders).toEqual([]);
  });

  test('가로축은 스크롤 컨테이너가 중첩되지 않는다', async ({ authenticatedPage: page }) => {
    // 바깥 래퍼의 overflow-x-auto는 안쪽이 100% 폭 블록이라 절대 발동하지 않는 죽은 코드였다.
    // 제거했으므로 가로 스크롤은 안쪽 하나만 담당해야 한다.
    await page.setViewportSize({ width: 1280, height: 700 });
    await setupMocks(page);
    await page.goto('/data/datasets/1?tab=data');
    await expect(page.getByText('행 1', { exact: true })).toBeVisible();

    const outerOverflowX = await page.evaluate(() => {
      // 테이블을 감싼 바깥 래퍼 = 테이블의 조상 중 `rounded-md border`를 가진 첫 엘리먼트
      const table = document.querySelector('main table');
      if (!table) return -1;
      const outer = table.closest('.rounded-md.border');
      if (!outer) return -1;
      // 테이블을 강제로 넓혀도 바깥은 넘치면 안 된다(안쪽이 스크롤을 흡수)
      const el = table as HTMLElement;
      const prev = el.style.width;
      el.style.width = '3000px';
      const v = outer.scrollWidth - outer.clientWidth;
      el.style.width = prev;
      return v;
    });
    expect(outerOverflowX).toBe(0);
  });

  /**
   * sticky thead + scroll-padding 계약.
   *
   * 스크롤 컨테이너 안에서 scrollIntoView(가상 스크롤의 scrollToIndex, 브라우저 찾기, 포커스 이동)로
   * 행을 이동시키면, scroll-padding이 없거나 헤더보다 작으면 대상 행이 sticky thead 뒤에 가려진다.
   * 픽셀 값을 직접 단언하지 않고 **가려지지 않는다**는 결과를 단언한다 — 헤더 높이가 바뀌어도
   * (정렬 버튼·분포 바 추가 등) 계약이 유지되는지 그대로 드러난다.
   */
  test('행을 scrollIntoView하면 sticky thead 뒤에 가려지지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 700 });
    await setupMocks(page);
    await page.goto('/data/datasets/1?tab=data');
    await expect(page.getByText('행 1', { exact: true })).toBeVisible();

    const result = await page.evaluate(() => {
      const thead = document.querySelector<HTMLElement>('main thead');
      const scroller = thead?.closest('table')?.parentElement;
      if (!thead || !scroller) return null;

      // 화면 밖에 있는 행 하나를 스크롤 컨테이너 안으로 끌어온다
      const rows = [...scroller.querySelectorAll<HTMLElement>('tbody tr')];
      const target = rows[rows.length - 1];
      if (!target) return null;
      target.scrollIntoView({ block: 'start', behavior: 'auto' });

      return {
        theadHeight: thead.offsetHeight,
        theadBottom: thead.getBoundingClientRect().bottom,
        rowTop: target.getBoundingClientRect().top,
      };
    });

    expect(result).not.toBeNull();
    // 대상 행의 상단이 헤더 하단보다 아래여야 한다(1px 여유는 서브픽셀 반올림)
    expect(
      result!.rowTop,
      `thead 높이=${result!.theadHeight}px — scroll-pt 값이 이보다 작으면 행이 가려진다`
    ).toBeGreaterThanOrEqual(result!.theadBottom - 1);
  });
});
