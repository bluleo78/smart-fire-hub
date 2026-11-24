import { createCategories, createDataset } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { clickInOneTask } from '../../fixtures/click-in-one-task';
import { grantDatasetPermissions, mockDatasetReferences } from '../../fixtures/dataset.fixture';

/**
 * WD-1062: 최종 주소가 ms 동안 expected(pathname+search) 그대로 유지되는지 100ms 간격으로 표집한다.
 * 왜: expect.poll(...).toBe 는 첫 표집에서 통과해 버려, 그 뒤 늦게 일어나는 낡은 page 쓰기를 놓친다.
 */
async function expectUrlStays(page: import('@playwright/test').Page, expected: string, ms = 1000) {
  const deadline = Date.now() + ms;
  do {
    const url = new URL(page.url());
    expect(url.pathname + url.search).toBe(expected);
    await page.waitForTimeout(100);
  } while (Date.now() < deadline);
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

  /**
   * setupCommon 과 같은 모킹에 목록 요청 주소를 모은다 — 화면 주소뿐 아니라 실제로 나간 조회 조건까지 단언하려고(WD-1061).
   */
  async function setupCapturingList(page: import('@playwright/test').Page) {
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
    return urls;
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
   * 이슈 WD-950 — 뒤로가기(POP)로 주소가 바뀐 뒤 필터를 누르면 뒤로가기 전 주소의 키가 되살아나면 안 된다(기본 동작 확인).
   * 모든 클릭이 렌더 뒤라 대기 목록이 비어 있어 고치기 전 코드도 통과한다 — 렌더 전 쓰기와 POP 이 겨루는 경우는 아래
   * 「즐겨찾기 클릭과 뒤로가기가 겨뤄도…」 시험이 맡는다.
   * (그 시험의 판정력 한계와 결정적 커버리지 위치는 그 시험 머리 주석 — WD-1047.)
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

  /**
   * 이슈 WD-1061 — 외부 이동(사이드바 링크)은 history 를 즉시 쓰지만 커밋·layout effect 는 transition 렌더 뒤라,
   * 그 사이 클릭이 낡은 기준(지운 favorite)을 읽어 되살린다. 한 작업 안에서 즐겨찾기 → 사이드바 → 임시 보기를 누른다.
   */
  test('즐겨찾기·사이드바 이동·임시 보기를 한 작업에서 누르면 favorite 이 되살아나지 않는다 (WD-1061)', async ({
    authenticatedPage: page,
  }) => {
    const urls = await setupCapturingList(page);

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    const favoriteButton = page.getByRole('button', { name: '즐겨찾기', exact: true });
    const tempButton = page.getByRole('button', { name: '임시 데이터셋 보기' });

    await clickInOneTask(page, [favoriteButton, page.locator('a[href="/data/datasets"]').first(), tempButton]);

    await expect(tempButton).toHaveAttribute('aria-pressed', 'true');
    const params = new URL(page.url()).searchParams;
    expect(params.get('showTemp')).toBe('1');
    expect(params.get('favorite')).toBeNull();

    // 마지막 목록 요청도 임시 포함만 싣고 즐겨찾기 조건(favoriteOnly)은 빠져야 한다
    await expect.poll(() => {
      const last = urls[urls.length - 1];
      return last ? [last.searchParams.get('includeTemp'), last.searchParams.get('favoriteOnly')] : null;
    }).toEqual(['true', null]);
  });

  /** 이슈 WD-1061 — 같은 갈래를 정렬로: 사이드바 이동(정렬 없는 주소) 직후 임시 보기가 지운 sort 를 되살리지 않아야 한다. */
  test('정렬된 목록에서 사이드바 이동·임시 보기를 한 작업에서 누르면 sort 가 되살아나지 않는다 (WD-1061)', async ({
    authenticatedPage: page,
  }) => {
    const urls = await setupCapturingList(page);

    await page.goto('/data/datasets?sort=name&order=asc');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    const tempButton = page.getByRole('button', { name: '임시 데이터셋 보기' });

    await clickInOneTask(page, [page.locator('a[href="/data/datasets"]').first(), tempButton]);

    await expect(tempButton).toHaveAttribute('aria-pressed', 'true');
    const params = new URL(page.url()).searchParams;
    expect(params.get('showTemp')).toBe('1');
    expect(params.get('sort')).toBeNull();
    expect(params.get('order')).toBeNull();

    // 마지막 목록 요청 — 임시 포함만 싣는다. 정렬은 화면에서 하므로(서버 정렬 조건 sortBy·sortDir 를 싣지 않는다)
    // 요청에 정렬 조건이 끼지 않았는지와 즐겨찾기 조건이 없는지를 함께 본다
    await expect.poll(() => {
      const last = urls[urls.length - 1];
      return last
        ? [
            last.searchParams.get('includeTemp'),
            last.searchParams.get('favoriteOnly'),
            last.searchParams.get('sortBy'),
            last.searchParams.get('sortDir'),
          ]
        : null;
    }).toEqual(['true', null, null, null]);
  });

  /**
   * 이슈 WD-950 리뷰 회귀 방지 — 클릭이 화면 반영 전(대기 목록이 안 빈 상태)에 뒤로가기(POP)와 겨루는 경우.
   * 같은 작업 안에서 즐겨찾기 클릭 + history.back() 을 하면 쓴 주소(favorite=true)는 렌더에 오지 못하고 POP 주소로 덮인다.
   * 이때 대기 목록을 비우지 않으면 다음 필터가 favorite 을 되살리고 뒤로 온 정렬을 지운다.
   * WD-1047 — 실제 뒤로가기는 비동기라 대개 favorite 커밋이 POP 보다 먼저 끝나 위 겨루기가 일어나지 않는다.
   * WD-1053 — React 19.2 는 popstate 이벤트 안에서 시작한 transition 을 동기로 렌더·커밋하고, react-router 7.13 의
   * BrowserRouter 는 history.listen 안에서 startTransition(setState) 를 부른다. 그래서 라우터의 popstate 리스너가 끝난 뒤에는
   * POP 렌더가 이미 커밋돼 있고, popstate 뒤 첫 타이머 작업(setTimeout 0) 시점에도 정렬 안내가 DOM 에 있다.
   * 그래서 이 시험은 뒤로가기 뒤 기본 동작(정렬 복원·다음 필터)만 지키고, 「POP 시 대기 목록 비우기」를 뺀 변이는
   * 잡지 못한다(그 갈래를 지운 변이에서 10회 모두 통과).
   * WD-1061 — 이제 getLatestParams 가 실제 주소와 대조해 어긋나면 대기 목록을 비우므로, 「POP 시 대기 목록 비우기」 갈래를
   * 지워도 「즐겨찾기 직후 사이드바 링크로 이동하면…」 시험은 통과한다(두 장치가 겹쳐 지킨다 — 그 갈래 하나만 지키는 시험은 없다).
   * 이 시험은 여전히 POP 렌더 커밋 뒤에 다음 필터를 누르므로 「커밋 전 POP + 다음 필터」 경로는 직접 겨루지 않는다.
   */
  test('즐겨찾기 클릭과 뒤로가기가 겨뤄도 뒤로 온 주소 위에 다음 필터가 쌓인다 (WD-950)', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);

    // POP 스냅샷 기록 한 건의 모양 — init script·평가 쪽 캐스트에서 같이 쓴다(타입만이라 런타임 영향 없음)
    type PopRecord = { pressed: string | null; url: string; noticeNextTask: boolean | null };

    // WD-1015 스냅샷 — POP 시점의 화면 상태와 마지막 쓰기를 기록한다(겨루기가 실제로 났는지 annotation 으로 남긴다).
    // (i) popstate 마다 textContent 이 즐겨찾기 인 버튼의 aria-pressed 와 그 시점의 location.href 를 쌓고,
    // (ii) history.replaceState 를 감싸 세 번째 인자(주소)를 기록한 뒤 원본을 그대로 호출한다(원본 반환값 유지).
    // 리스너·감싸기는 addEventListener/wrap 만 써서 페이지 코드를 바꾸지 않고, SPA 탐색에서도 남는다.
    await page.addInitScript(() => {
      const w = window as unknown as {
        __wd1015Pop: PopRecord[];
        __wd1015LastWrite: string | null;
      };
      w.__wd1015Pop = [];
      window.addEventListener('popstate', () => {
        const 즐겨찾기버튼 = [...document.querySelectorAll('button')].find((b) => b.textContent === '즐겨찾기');
        const 항목: PopRecord = {
          pressed: 즐겨찾기버튼?.getAttribute('aria-pressed') ?? null,
          url: location.href,
          noticeNextTask: null,
        };
        w.__wd1015Pop.push(항목);
        // WD-1053 — popstate 뒤 첫 타이머 작업(setTimeout 0) 시점에 정렬 안내가 DOM 에 있는지 기록한다.
        // queueMicrotask 는 첫 리스너에서 넣어도 리스너 콜백 사이에 비워지므로 항상 라우터 리스너보다 먼저 돌아
        // POP 커밋 전 시점을 보게 된다. 그래서 쓰지 않고 setTimeout(0) 으로 작업 경계를 넘긴다.
        setTimeout(() => {
          항목.noticeNextTask = document.body.textContent?.includes('현재 페이지 내 정렬이 적용됩니다') ?? false;
        }, 0);
      });
      w.__wd1015LastWrite = null;
      const 원본_replaceState = history.replaceState.bind(history);
      history.replaceState = (data, unused, url) => {
        w.__wd1015LastWrite = url == null ? null : String(url);
        return 원본_replaceState(data, unused, url);
      };
    });

    await page.goto('/data/datasets?sort=name&order=asc');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    const favoriteButton = page.getByRole('button', { name: '즐겨찾기', exact: true });
    const tempButton = page.getByRole('button', { name: '임시 데이터셋 보기' });
    const params = () => new URL(page.url()).searchParams;

    // 사이드바 링크(push)로 정렬 없는 주소를 history 에 쌓는다 — 뒤로가기 목적지(정렬 있는 주소)를 만든다
    await page.locator('a[href="/data/datasets"]').first().click();
    await expect.poll(() => params().get('sort')).toBeNull();
    // 정렬이 켜졌을 때만 보이는 안내 — 앞 화면(정렬 없음)에는 없음을 전제로 고정한다(커밋 확인은 noticeNextTask, WD-1053)
    const sortNotice = page.getByText('현재 페이지 내 정렬이 적용됩니다');
    await expect(sortNotice).toBeHidden();

    // 한 작업 안에서 즐겨찾기 클릭(replace) 직후 뒤로가기 — 클릭이 쓴 주소는 렌더에 오기 전에 POP 이 덮는다
    await clickInOneTask(page, [favoriteButton], { thenHistoryBack: true });

    // 전제 단언 — POP 이 실제로 왔고 그 전에 favorite 쓰기가 있었다. 겨루기가 났는지(pressedAtPop)는 단언하지 않는다(WD-1047).
    const { pops, lastWrite } = await page.evaluate(() => {
      const w = window as unknown as {
        __wd1015Pop: PopRecord[];
        __wd1015LastWrite: string | null;
      };
      return { pops: w.__wd1015Pop, lastWrite: w.__wd1015LastWrite };
    });
    expect(pops.length).toBeGreaterThan(0); // 스냅샷이 안 잡히면 이 시험의 근거 자체가 없다
    expect(String(lastWrite)).toContain('favorite=true'); // POP 직전에 favorite 쓰기가 있었다
    // 관찰 기록만 남긴다(리뷰·리포트에서 재확인용)
    test.info().annotations.push({ type: 'wd1015-race', description: `pressedAtPop=${pops[0]?.pressed}` });

    await expect.poll(() => params().get('sort')).toBe('name');
    expect(params().get('favorite')).toBeNull();
    // WD-1053 — 렌더 대기 없이 바로 다음 필터를 누르는 근거(위 문서 주석의 POP 동기 커밋)를 아래 단언이 지킨다.
    // React·react-router 를 올려 실패하면 제품 회귀가 아니라 클릭 전 렌더 대기가 필요한지 다시 보라는 신호다.
    // 커밋이 스케줄러 작업(MessageChannel)으로 밀리면 타이머와의 순서가 브라우저에 달려 실패 없이 지나갈 수도 있다.
    // 이 겨루기를 만든 뒤로가기는 기록의 마지막 POP 이다
    const readNoticeAtTimer = () =>
      page.evaluate(() => (window as unknown as { __wd1015Pop: PopRecord[] }).__wd1015Pop.at(-1)?.noticeNextTask ?? null);
    // 기록이 채워질 때까지(null 아님) 기다린 뒤 값을 한 번 읽어 즉시 단언한다
    await expect.poll(readNoticeAtTimer).not.toBeNull();
    expect(
      await readNoticeAtTimer(),
      'popstate 뒤 첫 타이머 작업에 POP 렌더가 커밋돼 있지 않음(정렬 안내가 DOM 에 없음)',
    ).toBe(true);
    // (커밋 전 클릭이 favorite 을 되살리던 제품 쪽 겨루기는 WD-1061 에서 getLatestParams 의 실제 주소 대조로 막았다 —
    //  결정적 시험은 「…한 작업에서 누르면 favorite 이 되살아나지 않는다 (WD-1061)」)

    // 다음 필터 — POP 뒤 주소 + 이 필터만. favorite 이 되살아나거나 정렬이 사라지면 안 된다
    await tempButton.click();
    await expect(tempButton).toHaveAttribute('aria-pressed', 'true');
    expect(params().get('showTemp')).toBe('1');
    expect(params().get('sort')).toBe('name');
    expect(params().get('order')).toBe('asc');
    expect(params().get('favorite')).toBeNull();
    await expect(favoriteButton).toHaveAttribute('aria-pressed', 'false');
  });

  /**
   * 이슈 WD-950 리뷰 회귀 방지 — 주소의 categoryId 가 ?categoryId=03 처럼 숫자와 문자열이 다르게 쓰인 경우.
   * 눌림 표시는 Number 비교라 눌린 상태인데, 토글이 문자열 비교면 눌린 칩을 눌러도 꺼지지 않는다.
   */
  test('?categoryId=03 으로 눌린 분류 칩을 누르면 꺼진다 (WD-950)', async ({ authenticatedPage: page }) => {
    // web CLAUDE.md 필터/검색 → API 파라미터 검증 — 칩을 끈 뒤 재호출에 categoryId 가 없어야 한다
    const urls: URL[] = [];
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        urls.push(new URL(route.request().url()));
        return route.fulfill({ json: createPageResponse(datasets) });
      },
    );

    await page.goto('/data/datasets?categoryId=03');
    const chip = page.getByRole('button', { name: '기타', exact: true });
    await expect(chip).toHaveAttribute('aria-pressed', 'true');

    // 칩 클릭 전 — 목록 요청이 최소 한 번은 잡혀 있어야(공허 단언 방지) 마지막 요청에 categoryId 가 있어야 한다
    await expect.poll(() => urls.length).toBeGreaterThan(0);
    expect(urls[urls.length - 1].searchParams.get('categoryId')).not.toBeNull();

    const 요청수_클릭_전 = urls.length;
    await chip.click();

    await expect(chip).toHaveAttribute('aria-pressed', 'false');
    expect(new URL(page.url()).searchParams.get('categoryId')).toBeNull();

    // 칩을 끈 뒤 재호출이 있어야 하고, 그 마지막 요청에는 categoryId 가 없어야 한다
    await expect.poll(() => urls.length).toBeGreaterThan(요청수_클릭_전);
    expect(urls[urls.length - 1].searchParams.get('categoryId')).toBeNull();
  });

  /**
   * 이슈 WD-950 리뷰 회귀 방지 — 3회 연타로 만든 중복 항목([A,"",A])이 남아 있으면, 파라미터 없는 외부 이동이 도착 판정에 걸려
   * 대기 목록이 안 비워지고 낡은 favorite=true 가 다음 필터의 기준이 된다 — indexOf 로 되돌리면 이 시험이 favorite 이 되살아나는 것으로 실패한다.
   */
  test('즐겨찾기를 화면 반영 전에 세 번 누른 뒤 외부 이동을 거쳐도 낡은 favorite 이 다음 필터에서 되살아나지 않는다 (WD-950)', async ({ authenticatedPage: page }) => {
    await setupCommon(page);

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    const favoriteButton = page.getByRole('button', { name: '즐겨찾기', exact: true });
    const tempButton = page.getByRole('button', { name: '임시 데이터셋 보기' });
    const params = () => new URL(page.url()).searchParams;

    // 3회 연타 — 한 작업 안에서 쓴 주소가 [A,"",A] 로 중복 쌓인다(A = favorite=true, "" = 끈 상태)
    await clickInOneTask(page, [favoriteButton, favoriteButton, favoriteButton]);
    await expect(favoriteButton).toHaveAttribute('aria-pressed', 'true');
    expect(params().get('favorite')).toBe('true');

    // 외부 이동 — 파라미터 없는 주소를 히스토리에 쌓는다. 그 주소 문자열이 대기 목록의 빈 문자열 항목과 같아지는 자리다
    await page.locator('a[href="/data/datasets"]').first().click();
    await expect.poll(() => params().get('favorite')).toBeNull();
    await expect(favoriteButton).toHaveAttribute('aria-pressed', 'false');

    // 다음 필터 — 대기 목록이 이때 안 비워지면 낡은 favorite=true 가 기준이 되어 favorite 이 되살아난다(변이 검사 지점)
    await tempButton.click();
    await expect(tempButton).toHaveAttribute('aria-pressed', 'true');
    expect(params().get('showTemp')).toBe('1');
    expect(params().get('favorite')).toBeNull();
    await expect(favoriteButton).toHaveAttribute('aria-pressed', 'false');

    // 같은 칩 한 번에 끄기(기존 마지막 3줄) — 2번 단계(외부 이동) 뒤 favorite 이 꺼져 있으니 먼저 켜 두고 기존 단언을 그대로 이어 간다.
    // 어느 쪽 토글이든 showTemp=1 이 남아야 한다.
    await favoriteButton.click();
    await expect(favoriteButton).toHaveAttribute('aria-pressed', 'true');
    expect(params().get('favorite')).toBe('true');

    await favoriteButton.click();
    await expect(favoriteButton).toHaveAttribute('aria-pressed', 'false');
    expect(params().get('favorite')).toBeNull();
    expect(params().get('showTemp')).toBe('1');
  });

  /**
   * 이슈 WD-1062 — 범위 밖 페이지 보정 effect 가 옛 응답의 totalPages 로 계산한 page 를, 그 사이 외부 이동(사이드바 링크)으로 이미 떠난 주소에 쓰지 않아야 한다.
   * 재현: page=5 목록 응답을 앱 안(XHR 래핑)에서 붙잡아 두었다가, 한 브라우저 태스크에서 사이드바 링크 클릭 직후 응답을 푼다.
   * 보정 effect 의 가드가 없으면 최종 주소가 /data/datasets?page=2 로 오염된다(가드 제거 시 6/6 실패 확인).
   */
  test('범위 밖 페이지 보정 직전에 사이드바로 이동하면 보정이 새 주소에 page 를 쓰지 않는다 (WD-1062)', async ({ authenticatedPage: page }) => {
    const urls: URL[] = [];
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        const url = new URL(route.request().url());
        urls.push(url);
        // page=5 는 범위 밖 — 데이터는 3쪽(총 30건)뿐이라 빈 응답. 그 밖은 일반 목록
        if (url.searchParams.get('page') === '5') {
          return route.fulfill({ json: createPageResponse([], { page: 5, size: 10, totalElements: 30, totalPages: 3 }) });
        }
        return route.fulfill({ json: createPageResponse(datasets) });
      },
    );

    // page=5 목록 응답을 앱 안에서 붙잡아 둔다 — XHR 처리기(axios)로 가는 이벤트 전달을 window.__wd1062Deliver() 가 부를 때까지 미룬다
    await page.addInitScript(() => {
      const w = window as unknown as { __wd1062Pending: Array<() => void>; __wd1062Deliver: () => void };
      w.__wd1062Pending = [];
      w.__wd1062Deliver = () => {
        const queued = w.__wd1062Pending.splice(0);
        for (const run of queued) run();
      };
      const urlOf = new WeakMap<XMLHttpRequest, string>();
      const origOpen = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function (this: XMLHttpRequest, method: string, url: string | URL, ...rest: unknown[]) {
        urlOf.set(this, String(url));
        return (origOpen as (...a: unknown[]) => void).call(this, method, url, ...rest);
      } as typeof XMLHttpRequest.prototype.open;
      for (const name of ['onreadystatechange', 'onloadend', 'onload'] as const) {
        // onload·onloadend 는 상위 프로토타입(XMLHttpRequestEventTarget)에 있어 체인을 거슬러 올라가 찾는다
        let owner: object | null = XMLHttpRequest.prototype;
        while (owner && !Object.getOwnPropertyDescriptor(owner, name)) owner = Object.getPrototypeOf(owner);
        const desc = Object.getOwnPropertyDescriptor(owner!, name)!;
        Object.defineProperty(owner!, name, {
          ...desc,
          set(this: XMLHttpRequest, fn: ((ev: Event) => void) | null) {
            desc.set!.call(
              this,
              fn &&
                ((ev: Event) => {
                  if ((urlOf.get(this) ?? '').includes('page=5') && this.readyState === 4) w.__wd1062Pending.push(() => fn.call(this, ev));
                  else fn.call(this, ev);
                }),
            );
          },
        });
      }
    });

    // 붙잡은 응답 때문에 load 가 늦으므로 기다리지 않고 시작만 한다 — 실패는 아래에서 await 해 시험 실패로 드러낸다
    const navigation = page.goto('/data/datasets?page=5');
    await page.waitForFunction(() => (window as unknown as { __wd1062Pending?: unknown[] }).__wd1062Pending?.length);
    // 한 브라우저 태스크 안에서 사이드바 이동을 시작하고 곧바로 붙잡은 응답을 푼다 — 응답 렌더·보정 effect 가 아직 커밋되지 않은 이동 transition 과 겹친다
    await page.evaluate(() => {
      const w = window as unknown as { __wd1062Deliver: () => void; __sidebarClicked?: boolean };
      // 링크가 실제로 있어 눌렸을 때만 표식을 남긴다 — 사이드바가 바뀌어 클릭이 없으면 아래 표식 단언이 실패한다
      const link = document.querySelector<HTMLAnchorElement>('a[href="/data/datasets"]');
      if (link) {
        link.click();
        w.__sidebarClicked = true;
      }
      w.__wd1062Deliver();
    });

    // 이동 뒤 주소에 page 가 없고 경로가 그대로이며, 보정이 늦게 써도 잡히도록 안정적으로 유지되는지 폴링한다
    await expect
      .poll(() => new URL(page.url()).pathname + new URL(page.url()).search)
      .toBe('/data/datasets');
    await expectUrlStays(page, '/data/datasets');
    await navigation;
    // 트리거(사이드바 클릭)가 실제로 일어났어야 이 시험이 의미가 있다
    expect(await page.evaluate(() => (window as unknown as { __sidebarClicked?: boolean }).__sidebarClicked)).toBe(true);
  });

  /**
   * WD-1062 삭제 시험 공통 준비 — 3쪽(page=2)이 마지막 1건(id 21)뿐인 목록, 붙잡아 둘 DELETE 응답, 진입·삭제 확인 클릭까지 한다.
   * 왜: 이 한 건을 지우면 보정이 page=1 을 쓰려 하므로, 응답을 늦춘 사이 주소를 바꿔 낡은 보정을 재현한다.
   * releaseDelete 로 DELETE 응답을 풀고, 삭제 요청이 나갈 때까지 기다린 뒤 돌아온다.
   */
  async function setupGatedLastPageDelete(page: import('@playwright/test').Page) {
    await grantDatasetPermissions(page);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockDatasetReferences(page);
    const lastItem = createDataset({ id: 21, name: '스물한번째 데이터셋', tableName: 'dataset_21' });
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        const requestedPage = new URL(route.request().url()).searchParams.get('page') ?? '0';
        const body =
          requestedPage === '2'
            ? createPageResponse([lastItem], { page: 2, size: 10, totalElements: 21, totalPages: 3 })
            : createPageResponse(datasets, { page: Number(requestedPage), size: 10, totalElements: 21, totalPages: 3 });
        return route.fulfill({ json: body });
      },
    );
    let releaseDelete: () => void = () => {};
    const deleteGate = new Promise<void>((resolve) => {
      releaseDelete = resolve;
    });
    let deleteRequested = false;
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/21',
      async (route) => {
        if (route.request().method() !== 'DELETE') return route.fallback();
        deleteRequested = true;
        await deleteGate;
        return route.fulfill({ json: {} });
      },
    );

    await page.goto('/data/datasets?page=2');
    const row = page.getByRole('row', { name: /스물한번째 데이터셋/ });
    await row.getByRole('button', { name: '삭제' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();
    await expect.poll(() => deleteRequested).toBe(true);
    return { releaseDelete };
  }

  /**
   * 이슈 WD-1062 — 삭제 응답을 기다리는 사이 필터를 바꾸면, 삭제 뒤 page 보정이 이미 바뀐 주소(page 없음)에 page 를 되써서는 안 된다.
   * 왜: handleDelete 의 보정은 await 뒤에 실행돼 클릭 시점의 page·행 수로 계산한다. 그 사이 주소가 바뀌었으면 그 계산은 낡은 값이다.
   */
  test('삭제 응답 전에 필터를 바꾸면 삭제 뒤 page 보정이 새 필터 주소에 쓰지 않는다 (WD-1062)', async ({ authenticatedPage: page }) => {
    const { releaseDelete } = await setupGatedLastPageDelete(page);

    // 확인 창이 열려 있어도 동작하도록 한 태스크 안에서 즐겨찾기 필터를 누른다
    await clickInOneTask(page, [page.getByRole('button', { name: '즐겨찾기', exact: true })]);
    await expect
      .poll(() => {
        const params = new URL(page.url()).searchParams;
        return [params.get('favorite'), params.get('page')];
      })
      .toEqual(['true', null]);

    releaseDelete();
    await expect(page.getByText(/삭제되었습니다/)).toBeVisible();
    await expectUrlStays(page, '/data/datasets?favorite=true');
  });

  /**
   * 이슈 WD-1062 — 삭제 응답을 기다리는 사이 사이드바로 다른 화면(/pipelines)에 떠났다면, 삭제 뒤 page 보정이 그 화면 주소에 page 를 쓰지 않아야 한다.
   * 왜: 보정은 await 뒤에 실행되므로 이미 떠난 경로에 ?page=1 을 덧붙이면 엉뚱한 화면 주소가 오염된다.
   */
  test('삭제 응답 전에 다른 화면으로 떠나면 삭제 뒤 page 보정이 그 주소에 쓰지 않는다 (WD-1062)', async ({ authenticatedPage: page }) => {
    const { releaseDelete } = await setupGatedLastPageDelete(page);

    // 사이드바의 파이프라인 링크로 떠난다 — 확인 창 잔여 상태와 무관하게 누르려고 한 태스크 안에서 클릭한다
    await clickInOneTask(page, [page.locator('a[href="/pipelines"]').first()]);
    await expect.poll(() => new URL(page.url()).pathname).toBe('/pipelines');

    releaseDelete();
    await expect(page.getByText(/삭제되었습니다/)).toBeVisible();
    // 보정이 /pipelines 에 page 를 덧붙이지 않는다
    await expectUrlStays(page, '/pipelines');
  });
});
