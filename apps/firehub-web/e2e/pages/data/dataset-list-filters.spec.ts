import { createCategory, createDataset } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions } from '../../fixtures/dataset.fixture';

/**
 * 데이터셋 목록 — 필터/미리보기 E2E 테스트
 *
 * DatasetListPage 의 storageType/originType / statusFilter / favoriteOnly / preview 모달
 * 분기와 useDatasets 훅의 파라미터 전달 경로를 커버한다. (기존 dataset-list.spec.ts
 * 에서는 기본 렌더링/삭제/페이지네이션만 다룬다.)
 */
test.describe('데이터셋 목록 — 필터 및 미리보기', () => {
  const datasets = [
    createDataset({
      id: 1,
      name: '원본 데이터셋 — 인증',
      storageType: 'TABLE',
      originType: 'SOURCE',
      status: 'CERTIFIED',
      isFavorite: true,
      tags: ['공공', '화재', '2026', '중요', '정기'],
    }),
    createDataset({
      id: 2,
      name: '파생 데이터셋 — 사용중단',
      storageType: 'TABLE',
      originType: 'DERIVED',
      status: 'DEPRECATED',
      isFavorite: false,
      tags: [],
    }),
    createDataset({
      id: 3,
      name: '임시 데이터셋',
      storageType: 'TABLE',
      originType: 'TEMP',
      status: 'NONE',
      isFavorite: false,
      tags: ['임시'],
    }),
  ];

  async function captureListRequests(page: import('@playwright/test').Page) {
    const urls: URL[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        urls.push(new URL(route.request().url()));
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPageResponse(datasets)),
        });
      },
    );
    return urls;
  }

  async function setupCommon(page: import('@playwright/test').Page) {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  }

  test('CERTIFIED/DEPRECATED 뱃지가 각각 표시되고 출처 뱃지도 렌더된다', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    await captureListRequests(page);

    await page.goto('/data/datasets');

    // CERTIFIED → "Certified" 뱃지
    await expect(page.getByText(/Certified/)).toBeVisible();
    // DEPRECATED → "Deprecated" 뱃지
    await expect(page.getByText(/Deprecated/)).toBeVisible();
    // originType: SOURCE → "원본", DERIVED → "파생", TEMP → "임시"
    await expect(page.getByText('원본', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('파생', { exact: true }).first()).toBeVisible();
    // "임시"는 tag 뱃지와 출처 뱃지 양쪽에 존재 가능 — 최소 1개 이상
    await expect(page.getByText('임시', { exact: true }).first()).toBeVisible();

    // 태그가 3개 초과면 "+N" 뱃지
    await expect(page.getByText('+2')).toBeVisible();
  });

  test('출처 SELECT 변경 시 API 에 originType 파라미터가 전달된다', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    const urls = await captureListRequests(page);

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    // 출처 SELECT — "전체 출처" 트리거로 식별
    const originCombobox = page.getByRole('combobox').filter({ hasText: /전체 출처/ }).first();
    await originCombobox.click();
    await page.getByRole('option', { name: '원본' }).click();

    // API 가 originType=SOURCE 로 재호출되었는지 확인
    await expect.poll(() => urls.some((u) => u.searchParams.get('originType') === 'SOURCE')).toBeTruthy();
  });

  test('DOCUMENT 유형 데이터셋이 "문서" 배지로 렌더링된다', async ({
    authenticatedPage: page,
  }) => {
    // DOCUMENT 유형 데이터셋 1건을 포함한 목록을 모킹한다
    const documentDataset = createDataset({
      id: 10,
      name: '문서 데이터셋 — 소방청 고시',
      storageType: 'DOCUMENT',
      originType: 'SOURCE',
      status: 'NONE',
      isFavorite: false,
      tags: [],
    });

    await setupCommon(page);
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPageResponse([documentDataset])),
        }),
    );

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    // DOCUMENT 유형 → "문서" 배지가 해당 행에 표시되어야 한다
    const docRow = page.getByRole('row', { name: /문서 데이터셋 — 소방청 고시/ });
    await expect(docRow).toBeVisible();
    await expect(docRow.getByText('문서', { exact: true })).toBeVisible();
  });

  test('저장방식 필터에서 "문서" 선택 시 API 에 storageType=DOCUMENT 가 전달된다', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    const urls = await captureListRequests(page);

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    // 저장방식 SELECT 드롭다운 열기 — "전체 저장방식" 트리거로 식별 후 "문서" 옵션 선택
    const storageCombobox = page
      .getByRole('combobox')
      .filter({ hasText: /전체 저장방식/ })
      .first();
    await storageCombobox.click();
    await page.getByRole('option', { name: '문서' }).click();

    // API 재호출 시 storageType=DOCUMENT 쿼리 파라미터가 포함되어야 한다
    await expect
      .poll(() => urls.some((u) => u.searchParams.get('storageType') === 'DOCUMENT'))
      .toBeTruthy();
  });

  /**
   * WD-760 — 저장방식 필터에 「파일」이 없어 FILE 데이터셋만 골라 볼 수 없었다.
   * 선택지는 이제 저장 방식 라벨 맵에서 만들어지므로 순서는 기존(전체·테이블·문서) 뒤에 「파일」이 붙고,
   * 각 선택이 URL·목록 API 의 storageType 으로 이어져 그 저장 방식 데이터셋만 보이는지 확인한다.
   */
  test.describe('저장방식 필터 — 파일 (WD-760)', () => {
    const mixed = [
      createDataset({ id: 21, name: '출동 통계 테이블', storageType: 'TABLE', originType: 'SOURCE', tags: [] }),
      createDataset({ id: 22, name: '점검 보고서 문서', storageType: 'DOCUMENT', originType: 'SOURCE', tags: [] }),
      createDataset({ id: 23, name: '현장 사진 파일', storageType: 'FILE', originType: 'SOURCE', tags: [] }),
    ];

    /** 목록 API 모킹 — 서버처럼 storageType 파라미터로 걸러 돌려주고, 요청 URL 을 모은다 */
    async function mockFilteredList(page: import('@playwright/test').Page) {
      const urls: URL[] = [];
      await page.route(
        (url) => url.pathname === '/api/v1/datasets',
        (route) => {
          const url = new URL(route.request().url());
          urls.push(url);
          const storageType = url.searchParams.get('storageType');
          const content = storageType ? mixed.filter((d) => d.storageType === storageType) : mixed;
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(createPageResponse(content)),
          });
        },
      );
      return urls;
    }

    /** 저장방식 필터에서 옵션을 고른다 */
    async function chooseStorage(page: import('@playwright/test').Page, label: string) {
      await page.getByRole('combobox', { name: '저장 방식 필터' }).click();
      await page.getByRole('option', { name: label, exact: true }).click();
    }

    test('선택지는 전체 저장방식·테이블·문서·파일 순서다', async ({ authenticatedPage: page }) => {
      await setupCommon(page);
      await mockFilteredList(page);
      await page.goto('/data/datasets');

      await page.getByRole('combobox', { name: '저장 방식 필터' }).click();
      await expect(page.getByRole('option')).toHaveText(['전체 저장방식', '테이블', '문서', '파일']);
    });

    test('「파일」 선택 시 URL·API 에 storageType=FILE 이 실리고 FILE 데이터셋만 보인다', async ({
      authenticatedPage: page,
    }) => {
      await setupCommon(page);
      const urls = await mockFilteredList(page);
      await page.goto('/data/datasets');
      // 필터 전에는 세 저장 방식이 모두 보인다
      await expect(page.getByRole('row', { name: /출동 통계 테이블/ })).toBeVisible();
      await expect(page.getByRole('row', { name: /현장 사진 파일/ })).toBeVisible();

      await chooseStorage(page, '파일');

      // 화면 URL 과 목록 API 요청 모두 storageType=FILE
      await expect(page).toHaveURL(/[?&]storageType=FILE(&|$)/);
      await expect.poll(() => urls.some((u) => u.searchParams.get('storageType') === 'FILE')).toBeTruthy();
      // 목록에는 FILE 데이터셋만 남고, 저장 방식 배지는 「파일」
      const fileRow = page.getByRole('row', { name: /현장 사진 파일/ });
      await expect(fileRow).toBeVisible();
      await expect(fileRow.getByText('파일', { exact: true })).toBeVisible();
      await expect(page.getByRole('row', { name: /출동 통계 테이블/ })).toHaveCount(0);
      await expect(page.getByRole('row', { name: /점검 보고서 문서/ })).toHaveCount(0);
      // 고른 값이 필터 상자에 보인다
      await expect(page.getByRole('combobox', { name: '저장 방식 필터' })).toHaveText('파일');
    });

    test('「테이블」·「문서」 선택도 그대로 storageType 으로 거른다', async ({ authenticatedPage: page }) => {
      await setupCommon(page);
      const urls = await mockFilteredList(page);
      await page.goto('/data/datasets');
      await expect(page.getByRole('row', { name: /현장 사진 파일/ })).toBeVisible();

      await chooseStorage(page, '테이블');
      await expect(page).toHaveURL(/[?&]storageType=TABLE(&|$)/);
      await expect.poll(() => urls.some((u) => u.searchParams.get('storageType') === 'TABLE')).toBeTruthy();
      await expect(page.getByRole('row', { name: /출동 통계 테이블/ })).toBeVisible();
      await expect(page.getByRole('row', { name: /현장 사진 파일/ })).toHaveCount(0);
      await expect(page.getByRole('row', { name: /점검 보고서 문서/ })).toHaveCount(0);

      await chooseStorage(page, '문서');
      await expect(page).toHaveURL(/[?&]storageType=DOCUMENT(&|$)/);
      await expect.poll(() => urls.some((u) => u.searchParams.get('storageType') === 'DOCUMENT')).toBeTruthy();
      await expect(page.getByRole('row', { name: /점검 보고서 문서/ })).toBeVisible();
      await expect(page.getByRole('row', { name: /출동 통계 테이블/ })).toHaveCount(0);
      await expect(page.getByRole('row', { name: /현장 사진 파일/ })).toHaveCount(0);
    });

    test('URL 로 storageType=FILE 을 열면 필터가 「파일」로 복원되고 API 에도 실린다', async ({
      authenticatedPage: page,
    }) => {
      await setupCommon(page);
      const urls = await mockFilteredList(page);
      await page.goto('/data/datasets?storageType=FILE');

      await expect(page.getByRole('combobox', { name: '저장 방식 필터' })).toHaveText('파일');
      await expect.poll(() => urls.some((u) => u.searchParams.get('storageType') === 'FILE')).toBeTruthy();
      await expect(page.getByRole('row', { name: /현장 사진 파일/ })).toBeVisible();
      await expect(page.getByRole('row', { name: /출동 통계 테이블/ })).toHaveCount(0);
    });
  });

  test('즐겨찾기 필터 버튼 클릭 시 API 에 favoriteOnly=true 가 전달된다', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    const urls = await captureListRequests(page);

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    // "즐겨찾기" 필터 버튼 클릭 (exact: true 로 행 안의 "즐겨찾기 추가"와 구분)
    await page.getByRole('button', { name: '즐겨찾기', exact: true }).click();

    await expect.poll(() => urls.some((u) => u.searchParams.get('favoriteOnly') === 'true')).toBeTruthy();
  });

  test('미리보기 버튼 클릭 시 DatasetPreviewModal 이 열린다', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    await captureListRequests(page);

    // 미리보기 모달이 내부적으로 /datasets/1 과 /datasets/1/data 를 요청할 수 있으므로 모킹
    await mockApi(page, 'GET', '/api/v1/datasets/1', {
      ...datasets[0],
      columns: [],
      sourceType: 'MANUAL',
      sourceConfig: {},
      rowCount: 0,
      updatedAt: '2026-01-01T00:00:00Z',
    });
    await mockApi(page, 'GET', '/api/v1/datasets/1/data', {
      columns: [],
      rows: [],
      page: 0,
      size: 50,
      totalElements: 0,
      totalPages: 0,
    });

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    // 첫 번째 행의 "미리보기" 버튼 — hover-opacity 이므로 force click
    const previewBtn = page.getByRole('button', { name: '미리보기' }).first();
    await previewBtn.click({ force: true });

    // 모달이 열리면 어떤 형태로든 dialog role 이 생긴다
    await expect(page.getByRole('dialog')).toBeVisible();
  });

  /**
   * 이슈 #111: 미리보기 다이얼로그 샘플 크기 셀렉트 회귀 방지.
   * - 기본 5행 + 셀렉트 변경(50) 시 size 파라미터가 50으로 API 재호출되는지 검증.
   * - 컬럼 헤더에 dataType 보조 텍스트가 노출되는지 검증.
   */
  test('미리보기 다이얼로그 — 샘플 크기 셀렉트 변경 시 size 파라미터가 API에 전달된다 (#111)', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    await captureListRequests(page);

    await mockApi(page, 'GET', '/api/v1/datasets/1', {
      ...datasets[0],
      columns: [],
      sourceType: 'MANUAL',
      sourceConfig: {},
      rowCount: 0,
      updatedAt: '2026-01-01T00:00:00Z',
    });

    // /datasets/1/data 캡처 — size 파라미터가 변경되는지 검증
    const dataUrls: URL[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/data',
      (route) => {
        dataUrls.push(new URL(route.request().url()));
        const size = Number(new URL(route.request().url()).searchParams.get('size') ?? 0);
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            columns: [
              { id: 1, columnName: 'name', displayName: '이름', dataType: 'VARCHAR', maxLength: 50, isNullable: false, isIndexed: false, isPrimaryKey: false, description: null, columnOrder: 0 },
            ],
            rows: Array.from({ length: size }, (_, i) => ({ name: `row${i}` })),
            page: 0,
            size,
            totalElements: 100,
            totalPages: Math.ceil(100 / Math.max(size, 1)),
          }),
        });
      },
    );

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    const previewBtn = page.getByRole('button', { name: '미리보기' }).first();
    await previewBtn.click({ force: true });
    await expect(page.getByRole('dialog')).toBeVisible();

    // 초기 진입: size=5 가 전달되어야 함
    await expect.poll(() => dataUrls.some((u) => u.searchParams.get('size') === '5')).toBeTruthy();

    // dataType 보조 표시 확인 (컬럼 헤더에 'VARCHAR' 노출)
    await expect(page.getByRole('dialog').getByText('VARCHAR')).toBeVisible();

    // 샘플 크기 셀렉트 변경 → 50
    await page.getByLabel('샘플 크기').click();
    await page.getByRole('option', { name: '50행' }).click();

    // size=50 으로 재호출되는지 확인
    await expect.poll(() => dataUrls.some((u) => u.searchParams.get('size') === '50')).toBeTruthy();
  });

  /**
   * 이슈 #667: 미리보기 다이얼로그가 데이터 탭(DatasetDataTab)과 동일하게 formatCellValue로
   * TIMESTAMP/BOOLEAN 값을 가공해 표시하는지 회귀 방지.
   * - 기존 버그: String(value)만 사용해 TIMESTAMP는 원문 ISO(밀리초+오프셋 포함), BOOLEAN은 "true"/"false" 그대로 노출.
   * - 수정 후: TIMESTAMP는 로컬 표기(연/월/일 + 오전/오후 시:분), BOOLEAN은 ✓/✗ 로 표시.
   * - 회귀 확인: VARCHAR 컬럼은 원문 그대로 표시되어야 한다(과도한 가공 없음).
   */
  test('미리보기 다이얼로그 — TIMESTAMP/BOOLEAN 값이 데이터 탭과 동일하게 포맷된다 (#667)', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    await captureListRequests(page);

    await mockApi(page, 'GET', '/api/v1/datasets/1', {
      ...datasets[0],
      columns: [],
      sourceType: 'MANUAL',
      sourceConfig: {},
      rowCount: 0,
      updatedAt: '2026-01-01T00:00:00Z',
    });

    await mockApi(page, 'GET', '/api/v1/datasets/1/data', {
      columns: [
        { id: 1, columnName: 'occurredAt', displayName: '발생일시', dataType: 'TIMESTAMP', maxLength: null, isNullable: false, isIndexed: false, isPrimaryKey: false, description: null, columnOrder: 0 },
        { id: 2, columnName: 'extinguished', displayName: '진화 완료', dataType: 'BOOLEAN', maxLength: null, isNullable: false, isIndexed: false, isPrimaryKey: false, description: null, columnOrder: 1 },
        { id: 3, columnName: 'address', displayName: '주소', dataType: 'VARCHAR', maxLength: 200, isNullable: false, isIndexed: false, isPrimaryKey: false, description: null, columnOrder: 2 },
      ],
      rows: [
        { occurredAt: '2026-05-17T01:00:00.000+00:00', extinguished: true, address: '서울특별시 중구' },
      ],
      page: 0,
      size: 5,
      totalElements: 1,
      totalPages: 1,
    });

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    const previewBtn = page.getByRole('button', { name: '미리보기' }).first();
    await previewBtn.click({ force: true });
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();

    // TIMESTAMP: 원문 ISO 문자열이 그대로 노출되면 안 되고, formatCellValue 와 동일한 로직(브라우저의
    // Intl 구현)으로 가공된 값(로컬 타임존 기준)이 보여야 한다 — Node의 ICU 데이터는 브라우저와
    // 다를 수 있으므로 브라우저 컨텍스트에서 직접 기대값을 계산한다.
    const expectedTimestamp = await page.evaluate(() =>
      new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' }).format(
        // eslint-disable-next-line no-restricted-syntax -- 오프셋을 명시한 고정 문자열 — 브라우저 존 해석 오류 없음(#691 대상 아님)
        new Date('2026-05-17T01:00:00.000+00:00'),
      ),
    );
    await expect(dialog.getByText('2026-05-17T01:00:00.000+00:00')).toHaveCount(0);
    await expect(dialog.getByText(expectedTimestamp, { exact: true })).toBeVisible();

    // BOOLEAN: 원문 "true" 문자열이 아니라 체크마크로 표시되어야 한다
    await expect(dialog.getByText('true', { exact: true })).toHaveCount(0);
    await expect(dialog.getByText('✓', { exact: true })).toBeVisible();

    // 회귀 확인: VARCHAR 는 원문 그대로 표시(과도한 가공 없음)
    await expect(dialog.getByText('서울특별시 중구')).toBeVisible();
  });

  test('저장 방식·출처·상태 필터에 이름이 있어 값을 고른 뒤에도 무엇을 거르는 상자인지 읽힌다 (WD-398)', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    const urls = await captureListRequests(page);
    await page.goto('/data/datasets');

    for (const name of ['저장 방식 필터', '출처 필터', '상태 필터', '보안 등급 필터']) {
      await expect(page.getByRole('combobox', { name })).toBeVisible();
    }
    // 값을 바꾼 뒤에도 같은 이름으로 찾히고, 고른 값이 보인다
    await page.getByRole('combobox', { name: '출처 필터' }).click();
    await page.getByRole('option', { name: '원본' }).click();
    await expect.poll(() => urls.some((u) => u.searchParams.get('originType') === 'SOURCE')).toBeTruthy();
    await expect(page.getByRole('combobox', { name: '출처 필터' })).toHaveText('원본');
  });
});

/**
 * WD-754 — 임시(TEMP) 데이터셋 기본 숨김 + 「임시 데이터셋 보기」 보기 옵션
 *
 * 서버는 includeTemp 기본값 false 로 TEMP 를 숨기고, originType 을 직접 고르면 그 값이 우선한다.
 * 모킹 서버가 이 계약대로 파라미터를 읽어 거르게 해, 화면이 보내는 요청 파라미터와 그 결과(행·건수)를 함께 검증한다.
 */
test.describe('데이터셋 목록 — 임시 데이터셋 보기 (WD-754)', () => {
  const all = [
    createDataset({ id: 11, name: '화재 출동 원본', originType: 'SOURCE', tags: [] }),
    createDataset({ id: 12, name: '월별 출동 집계', originType: 'DERIVED', tags: [] }),
    createDataset({ id: 13, name: 'tmp_step_정제', originType: 'TEMP', tags: [] }),
    createDataset({ id: 14, name: 'tmp_step_조인', originType: 'TEMP', tags: [] }),
  ];
  const tempNames = ['tmp_step_정제', 'tmp_step_조인'];
  const nonTempNames = ['화재 출동 원본', '월별 출동 집계'];
  const SHOW_TEMP = '임시 데이터셋 보기';

  /** 서버 계약대로 거르는 목록 모킹 — originType 우선, 없으면 includeTemp=true 일 때만 TEMP 포함. 요청 URL 을 모두 남긴다. */
  async function mockServerLikeList(page: import('@playwright/test').Page, datasets = all) {
    const urls: URL[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        const url = new URL(route.request().url());
        urls.push(url);
        const origin = url.searchParams.get('originType');
        const includeTemp = url.searchParams.get('includeTemp') === 'true';
        const content = origin
          ? datasets.filter((d) => d.originType === origin)
          : datasets.filter((d) => includeTemp || d.originType !== 'TEMP');
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPageResponse(content)),
        });
      },
    );
    return urls;
  }

  async function setupCommon(page: import('@playwright/test').Page) {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  }

  /** 목록 표의 데이터 행 이름들이 기대와 정확히 같은지(순서 무관) 확인 */
  async function expectRowNames(page: import('@playwright/test').Page, visible: string[], hidden: string[]) {
    const table = page.getByRole('table', { name: '데이터셋 목록' });
    for (const name of visible) await expect(table.getByRole('link', { name, exact: true })).toBeVisible();
    for (const name of hidden) await expect(table.getByRole('link', { name, exact: true })).toHaveCount(0);
  }

  test('기본 목록은 includeTemp 를 싣지 않고 임시 데이터셋을 보이지 않으며 건수도 숨긴 결과 기준이다', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    const urls = await mockServerLikeList(page);

    await page.goto('/data/datasets');

    await expectRowNames(page, nonTempNames, tempNames);
    await expect(page.getByText('총 2건')).toBeVisible();
    // 보기 옵션은 꺼진 상태로 보인다
    await expect(page.getByRole('button', { name: SHOW_TEMP })).toHaveAttribute('aria-pressed', 'false');
    // 모든 목록 요청이 includeTemp 없이(서버 기본 false) 나갔다
    expect(urls.length).toBeGreaterThan(0);
    expect(urls.every((u) => u.searchParams.get('includeTemp') === null)).toBe(true);
    await expect(page).not.toHaveURL(/showTemp=/);
  });

  test('「임시 데이터셋 보기」를 켜면 URL showTemp=1·요청 includeTemp=true 로 임시 데이터셋이 함께 보이고, 끄면 다시 숨는다', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    const urls = await mockServerLikeList(page);

    await page.goto('/data/datasets');
    await expectRowNames(page, nonTempNames, tempNames);

    const toggle = page.getByRole('button', { name: SHOW_TEMP });
    await toggle.click();

    await expect(page).toHaveURL(/[?&]showTemp=1(&|$)/);
    await expect(toggle).toHaveAttribute('aria-pressed', 'true');
    await expect.poll(() => urls.at(-1)?.searchParams.get('includeTemp')).toBe('true');
    await expectRowNames(page, [...nonTempNames, ...tempNames], []);
    await expect(page.getByText('총 4건')).toBeVisible();
    // 임시 행의 출처 배지가 「임시」로 그려진다
    await expect(page.getByRole('row', { name: /tmp_step_정제/ }).getByText('임시', { exact: true })).toBeVisible();

    // 다시 누르면 URL 에서 빠지고 처음(includeTemp 없음) 결과로 돌아가 임시가 숨는다.
    // 같은 조건의 첫 응답이 TanStack Query 캐시(staleTime 30s)에 있어 재요청은 안 나갈 수 있으므로 화면 결과로 확인한다.
    await toggle.click();
    await expect(page).not.toHaveURL(/showTemp=/);
    await expect(toggle).toHaveAttribute('aria-pressed', 'false');
    await expectRowNames(page, nonTempNames, tempNames);
    await expect(page.getByText('총 2건')).toBeVisible();
  });

  test('?showTemp=1 로 직접 들어오거나 새로고침해도 보기 옵션이 켜진 채 다른 필터와 함께 요청된다', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    const urls = await mockServerLikeList(page);

    await page.goto('/data/datasets?showTemp=1&storageType=TABLE');

    const toggle = page.getByRole('button', { name: SHOW_TEMP });
    await expect(toggle).toHaveAttribute('aria-pressed', 'true');
    await expectRowNames(page, [...nonTempNames, ...tempNames], []);
    await expect.poll(() => urls.at(-1)?.searchParams.get('includeTemp')).toBe('true');
    expect(urls.at(-1)?.searchParams.get('storageType')).toBe('TABLE');

    // 새로고침 — URL 이 단일 진실 소스라 그대로 복원된다
    const before = urls.length;
    await page.reload();
    await expect.poll(() => urls.length).toBeGreaterThan(before);
    await expect(toggle).toHaveAttribute('aria-pressed', 'true');
    expect(urls.at(-1)?.searchParams.get('includeTemp')).toBe('true');
    expect(urls.at(-1)?.searchParams.get('storageType')).toBe('TABLE');
    await expectRowNames(page, [...nonTempNames, ...tempNames], []);
    await expect(page).toHaveURL(/showTemp=1/);

    // 출처 필터를 고르면 서버가 그쪽을 우선하므로 includeTemp 는 싣지 않는다 — 같은 결과로 요청·캐시가 갈리지 않게
    await page.getByRole('combobox', { name: '출처 필터' }).click();
    await page.getByRole('option', { name: '원본' }).click();
    await expect.poll(() => urls.at(-1)?.searchParams.get('originType')).toBe('SOURCE');
    expect(urls.at(-1)?.searchParams.get('includeTemp')).toBeNull();
    await expect(page).toHaveURL(/showTemp=1/);
  });

  test('출처 필터가 「임시」면 보기 옵션이 비활성·안내되고 요청 originType=TEMP 로 임시만 보인다', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    const urls = await mockServerLikeList(page);

    await page.goto('/data/datasets');
    const toggle = page.getByRole('button', { name: SHOW_TEMP });
    await expect(toggle).toBeEnabled();

    await page.getByRole('combobox', { name: '출처 필터' }).click();
    await page.getByRole('option', { name: '임시' }).click();

    // 출처 「임시」가 보기 옵션보다 우선 — 보기 옵션은 꺼져 있어도 임시만 보인다
    await expect.poll(() => urls.at(-1)?.searchParams.get('originType')).toBe('TEMP');
    expect(urls.at(-1)?.searchParams.get('includeTemp')).toBeNull();
    await expectRowNames(page, tempNames, nonTempNames);
    await expect(page.getByText('총 2건')).toBeVisible();
    await expect(toggle).toBeDisabled();
    // 비활성 버튼은 포인터 이벤트가 없어 공용 래퍼(span role=group, 이름 '동작 — 사유')에 호버하면 사유 툴팁이 뜬다
    const hint = '출처 필터를 고르면 그 출처의 데이터셋만 보여 이 옵션은 쓰이지 않습니다';
    await page.getByRole('group', { name: `${SHOW_TEMP} — ${hint}`, exact: true }).hover();
    await expect(page.getByRole('tooltip')).toHaveText(hint);
    // 지금 목록에 임시가 보이므로 눌린 모양으로 보인다 — 실제 목록과 표시가 어긋나지 않게
    await expect(toggle).toHaveAttribute('aria-pressed', 'true');

    // 출처를 「전체」로 되돌리면 보기 옵션이 다시 살아나고 임시는 숨는다
    await page.getByRole('combobox', { name: '출처 필터' }).click();
    await page.getByRole('option', { name: '전체 출처' }).click();
    // 첫 진입 응답이 캐시에 있어 재요청은 안 나갈 수 있다 — URL 과 화면 결과로 확인한다
    await expect(page).not.toHaveURL(/originType=/);
    await expect(toggle).toBeEnabled();
    await expect(toggle).toHaveAttribute('aria-pressed', 'false');
    await expect(page.getByRole('group', { name: `${SHOW_TEMP} — ${hint}`, exact: true })).toHaveCount(0);
    await expectRowNames(page, nonTempNames, tempNames);
  });

  /**
   * WD-776 — 출처 필터가 「임시」가 아닌 값이어도 서버가 그 출처만 주고 includeTemp 를 무시한다.
   * 눌러도 목록이 바뀌지 않는 토글이 되지 않게 비활성 + 이유 툴팁을 보이고, 빈 목록 안내에서 「켜면 보입니다」 문장을 뺀다.
   */
  test('출처 필터가 「원본」이면 보기 옵션이 비활성·이유 안내되고, 빈 목록 안내에 토글 문장이 없다 (WD-776)', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    // 임시만 있는 테넌트 — 출처 「원본」으로 거르면 0건이 된다
    const urls = await mockServerLikeList(
      page,
      all.filter((d) => d.originType === 'TEMP'),
    );

    await page.goto('/data/datasets');
    const toggle = page.getByRole('button', { name: SHOW_TEMP });
    await expect(toggle).toBeEnabled();
    const toggleHint = page.getByText('임시 데이터셋은 「임시 데이터셋 보기」를 켜면 보입니다.');
    await expect(toggleHint).toBeVisible();

    await page.getByRole('combobox', { name: '출처 필터' }).click();
    await page.getByRole('option', { name: '원본' }).click();

    // 요청은 출처만 싣고 includeTemp 는 싣지 않는다(지금과 같다)
    await expect.poll(() => urls.at(-1)?.searchParams.get('originType')).toBe('SOURCE');
    expect(urls.at(-1)?.searchParams.get('includeTemp')).toBeNull();

    // 토글: 비활성 + 눌리지 않음 + 이유(접근 이름·툴팁)
    await expect(toggle).toBeDisabled();
    await expect(toggle).toHaveAttribute('aria-pressed', 'false');
    const reason = '출처 필터를 고르면 그 출처의 데이터셋만 보여 이 옵션은 쓰이지 않습니다';
    const wrapper = page.getByRole('group', { name: `${SHOW_TEMP} — ${reason}`, exact: true });
    await wrapper.hover();
    await expect(page.getByRole('tooltip')).toHaveText(reason);

    // 빈 목록 안내: 「데이터셋이 없습니다.」만 — 따를 수 없는 「켜면 보입니다」 문장은 없다
    // 출처 필터는 필터라 WD-879 이후엔 「조건에 맞는 데이터셋이 없습니다.」 로 읽힌다(임시 안내 문장은 여전히 없다)
    await expect(page.getByText('조건에 맞는 데이터셋이 없습니다.', { exact: true })).toBeVisible();
    await expect(toggleHint).toHaveCount(0);

    // 출처를 「전체」로 되돌리면 토글이 살아나고 안내 문장도 돌아온다
    await page.getByRole('combobox', { name: '출처 필터' }).click();
    await page.getByRole('option', { name: '전체 출처' }).click();
    await expect(page).not.toHaveURL(/originType=/);
    await expect(toggle).toBeEnabled();
    await expect(wrapper).toHaveCount(0);
    await expect(toggleHint).toBeVisible();
  });

  test('임시만 있어 기본 목록이 비면 「임시 데이터셋 보기」로 볼 수 있다고 안내하고, 켜면 안내 없이 임시가 보인다', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    // 임시 데이터셋만 있는 테넌트 — 서버 계약대로 기본은 0건
    await mockServerLikeList(
      page,
      all.filter((d) => d.originType === 'TEMP'),
    );

    await page.goto('/data/datasets');
    const hint = page.getByText('임시 데이터셋은 「임시 데이터셋 보기」를 켜면 보입니다.');
    await expect(hint).toBeVisible();

    await page.getByRole('button', { name: SHOW_TEMP }).click();
    await expectRowNames(page, tempNames, []);
    await expect(hint).toHaveCount(0);
  });
});

test.describe('필터 결과 0건 (WD-879)', () => {
  const EMPTY_CAT = 99;
  const FILTER_ZERO = '조건에 맞는 데이터셋이 없습니다.';
  const all = [
    createDataset({ id: 21, name: '화재 출동 원본', originType: 'SOURCE', tags: [] }),
    createDataset({ id: 22, name: '월별 출동 집계', originType: 'DERIVED', tags: [] }),
  ];

  test.beforeEach(async ({ authenticatedPage: page }) => {
    // 「새 데이터셋 만들기」 가 없는 이유가 권한이 아니라 필터 때문임을 보이려고 쓰기 권한을 준다
    await grantDatasetPermissions(page);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', [
      createCategory({ id: 1, name: '화재' }),
      createCategory({ id: EMPTY_CAT, name: '빈 카테고리' }),
    ]);
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  });

  /** 카테고리 99 와 즐겨찾기는 0건, 그 밖엔 전부 돌려주는 목록 모킹. 요청 URL 을 모두 남긴다. */
  async function mockZeroForFilters(page: import('@playwright/test').Page) {
    const urls: URL[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        const url = new URL(route.request().url());
        urls.push(url);
        const zero =
          url.searchParams.get('categoryId') === String(EMPTY_CAT) ||
          url.searchParams.get('favoriteOnly') === 'true' ||
          url.searchParams.get('q') !== null;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPageResponse(zero ? [] : all)),
        });
      },
    );
    return urls;
  }

  test('카테고리 필터로 0건이면 조건 문구와 필터 초기화를 보이고, 누르면 필터 없이 다시 불러 목록이 보인다', async ({
    authenticatedPage: page,
  }) => {
    const urls = await mockZeroForFilters(page);
    // 필터 없는 목록을 먼저 불러오면 그 응답이 쿼리 캐시(staleTime 30s)에 남아 초기화 때 재요청이 안 나간다.
    // 그래서 칩이 가리키는 URL 로 바로 열어 「필터 없이 다시 불러온다」를 요청으로 확인한다(칩 클릭은 즐겨찾기 시험이 같은 방식으로 덮는다).
    await page.goto(`/data/datasets?categoryId=${EMPTY_CAT}`);
    await expect(page.getByRole('button', { name: '빈 카테고리' })).toHaveAttribute('aria-pressed', 'true');

    await expect(page.getByText(FILTER_ZERO, { exact: false })).toBeVisible();
    await expect(page.getByRole('status').getByText(FILTER_ZERO, { exact: false })).toBeVisible();
    await expect(page.getByRole('button', { name: '필터 초기화' })).toBeVisible();
    await expect(page.getByRole('button', { name: '새 데이터셋 만들기' })).toHaveCount(0);

    await page.getByRole('button', { name: '필터 초기화' }).click();

    await expect.poll(() => urls.at(-1)?.searchParams.get('categoryId')).toBeNull();
    await expect(page).not.toHaveURL(/categoryId=/);
    await expect(page.getByRole('link', { name: '화재 출동 원본', exact: true })).toBeVisible();
  });

  test('즐겨찾기만 켜서 0건이어도 같은 조건 문구와 필터 초기화를 보인다', async ({ authenticatedPage: page }) => {
    await mockZeroForFilters(page);
    await page.goto('/data/datasets');
    await expect(page.getByRole('link', { name: '화재 출동 원본', exact: true })).toBeVisible();

    await page.getByRole('button', { name: '즐겨찾기', exact: true }).click();

    await expect(page.getByText(FILTER_ZERO, { exact: false })).toBeVisible();
    await expect(page.getByRole('button', { name: '필터 초기화' })).toBeVisible();
    await expect(page.getByRole('button', { name: '새 데이터셋 만들기' })).toHaveCount(0);

    // 필터 없는 목록이 이미 캐시에 있는 실제 흐름 — 초기화하면 (재요청 없이도) 목록이 돌아온다
    await page.getByRole('button', { name: '필터 초기화' }).click();
    await expect(page).not.toHaveURL(/favorite=/);
    await expect(page.getByRole('link', { name: '화재 출동 원본', exact: true })).toBeVisible();
    await expect(page.getByText(FILTER_ZERO, { exact: false })).toHaveCount(0);
  });

  test('필터 초기화는 여섯 필터를 지우고 정렬·페이지 크기·임시 보기는 남긴다', async ({ authenticatedPage: page }) => {
    await mockZeroForFilters(page);
    await page.goto(
      `/data/datasets?categoryId=${EMPTY_CAT}&favorite=true&storageType=TABLE&status=ACTIVE&securityLevel=1&sort=name&order=asc&size=20&showTemp=1`,
    );

    const reset = page.getByRole('button', { name: '필터 초기화' });
    await expect(reset).toBeVisible();
    await reset.click();

    await expect(page).not.toHaveURL(/categoryId=/);
    await expect(page).not.toHaveURL(/favorite=/);
    await expect(page).not.toHaveURL(/storageType=/);
    await expect(page).not.toHaveURL(/status=/);
    await expect(page).not.toHaveURL(/securityLevel=/);
    await expect(page).toHaveURL(/sort=name/);
    await expect(page).toHaveURL(/order=asc/);
    await expect(page).toHaveURL(/size=20/);
    await expect(page).toHaveURL(/showTemp=1/);
  });

  test('검색어와 필터가 함께 걸린 0건은 검색 결과 안내만 보인다', async ({ authenticatedPage: page }) => {
    await mockZeroForFilters(page);
    await page.goto(`/data/datasets?q=없는말&categoryId=${EMPTY_CAT}`);

    await expect(page.getByRole('button', { name: '검색 초기화' })).toBeVisible();
    await expect(page.getByRole('button', { name: '필터 초기화' })).toHaveCount(0);
  });
});
