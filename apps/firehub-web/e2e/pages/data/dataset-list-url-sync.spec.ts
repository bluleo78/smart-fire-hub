import { createCategories, createDataset } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { clickInOneTask } from '../../fixtures/click-in-one-task';

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

    // WD-1015 스냅샷 — POP 시점의 화면 상태와 마지막 쓰기를 기록한다(겨루기가 실제로 났는지 annotation 으로 남긴다).
    // (i) popstate 마다 textContent 이 즐겨찾기 인 버튼의 aria-pressed 와 그 시점의 location.href 를 쌓고,
    // (ii) history.replaceState 를 감싸 세 번째 인자(주소)를 기록한 뒤 원본을 그대로 호출한다(원본 반환값 유지).
    // 리스너·감싸기는 addEventListener/wrap 만 써서 페이지 코드를 바꾸지 않고, SPA 탐색에서도 남는다.
    await page.addInitScript(() => {
      const w = window as unknown as {
        __wd1015Pop: { pressed: string | null; url: string }[];
        __wd1015LastWrite: string | null;
      };
      w.__wd1015Pop = [];
      window.addEventListener('popstate', () => {
        const 즐겨찾기버튼 = [...document.querySelectorAll('button')].find((b) => b.textContent === '즐겨찾기');
        w.__wd1015Pop.push({
          pressed: 즐겨찾기버튼?.getAttribute('aria-pressed') ?? null,
          url: location.href,
        });
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
    // WD-1047 — 정렬이 켜졌을 때만 보이는 안내(DatasetListPage 정렬 범위 안내)를 POP 렌더 커밋 신호로 쓴다.
    // 앞 화면(정렬 없음)에는 없음을 고정한다 — 뒤로가기 뒤에 뜨면 POP 렌더 커밋의 증거다
    const sortNotice = page.getByText('현재 페이지 내 정렬이 적용됩니다');
    await expect(sortNotice).toBeHidden();

    // 한 작업 안에서 즐겨찾기 클릭(replace) 직후 뒤로가기 — 클릭이 쓴 주소는 렌더에 오기 전에 POP 이 덮는다
    await clickInOneTask(page, [favoriteButton], { thenHistoryBack: true });

    // 전제 단언 — POP 이 실제로 왔고 그 전에 favorite 쓰기가 있었다. 겨루기가 났는지(pressedAtPop)는 단언하지 않는다(WD-1047).
    const { pops, lastWrite } = await page.evaluate(() => {
      const w = window as unknown as {
        __wd1015Pop: { pressed: string | null; url: string }[];
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
    // WD-1047 모드 2 — 주소만 보면 POP 렌더 커밋 전에 다음 클릭이 들어가 대기 목록의 favorite 이 기준이 된다.
    // 렌더 신호(정렬 안내 문구)가 보일 때까지 기다려 POP 렌더 커밋 뒤에 다음 필터를 누른다.
    // (커밋 전 클릭이 favorite 을 되살리던 제품 쪽 겨루기는 WD-1061 에서 getLatestParams 의 실제 주소 대조로 막았다 —
    //  결정적 시험은 「…한 작업에서 누르면 favorite 이 되살아나지 않는다 (WD-1061)」)
    await expect(sortNotice).toBeVisible();

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
});
