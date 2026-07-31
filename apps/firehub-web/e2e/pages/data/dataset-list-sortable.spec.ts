import { createCategories, createDatasets } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 이슈 #80 회귀 테스트 — 데이터셋 목록 컬럼 헤더 클릭 정렬
 * - shadcn/ui Table 에 추가한 SortableHeader 가
 *   1) aria-sort 속성을 올바르게 토글하고
 *   2) 클릭 시 행 순서를 실제로 바꾸는지(현재 페이지 내) 검증한다.
 */
test.describe('데이터셋 목록 — 컬럼 헤더 정렬 (#80)', () => {
  test('이름 헤더 클릭 시 aria-sort 가 토글되고 행 순서가 변경된다', async ({
    authenticatedPage: page,
  }) => {
    // 서버 응답 순서가 의도적으로 비정렬이 되도록 별칭(name)을 부여한다.
    // createDatasets(5) 의 기본 이름은 "데이터셋 1..5" 이므로 그대로 사용해도
    // 1차원 정렬 테스트가 가능하다 — 다만 desc 검증을 명확히 하기 위해
    // 일부러 역순(5→1)으로 받도록 모킹한다.
    const datasets = createDatasets(5).reverse();
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(datasets));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets');

    const nameHeader = page.getByRole('columnheader', { name: /이름/ });
    await expect(nameHeader).toBeVisible();

    // 초기 상태 — 정렬 미적용 → aria-sort="none"
    await expect(nameHeader).toHaveAttribute('aria-sort', 'none');

    // 모킹은 5→1 역순이므로, 첫 데이터 행은 "데이터셋 5"
    const dataRows = page.getByRole('row').filter({ hasText: /^데이터셋\s/ });
    await expect(dataRows.first()).toContainText('데이터셋 5');

    // 1차 클릭: 오름차순
    await nameHeader.getByRole('button').click();
    await expect(nameHeader).toHaveAttribute('aria-sort', 'ascending');
    await expect(dataRows.first()).toContainText('데이터셋 1');
    await expect(dataRows.last()).toContainText('데이터셋 5');

    // 2차 클릭: 내림차순
    await nameHeader.getByRole('button').click();
    await expect(nameHeader).toHaveAttribute('aria-sort', 'descending');
    await expect(dataRows.first()).toContainText('데이터셋 5');
    await expect(dataRows.last()).toContainText('데이터셋 1');

    // 3차 클릭: 정렬 해제 (원본 순서로 복귀)
    await nameHeader.getByRole('button').click();
    await expect(nameHeader).toHaveAttribute('aria-sort', 'none');
    await expect(dataRows.first()).toContainText('데이터셋 5');
  });

  test('정렬 활성 시 현재 페이지 내 정렬 안내 메시지가 표시된다 (이슈 #173 회귀 방지)', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(createDatasets(3)));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets');

    // 정렬 미적용 상태에서는 안내 메시지가 없어야 한다
    await expect(page.getByText('현재 페이지 내 정렬이 적용됩니다')).not.toBeVisible();

    // 이름 헤더 클릭 → 정렬 활성화
    const nameHeader = page.getByRole('columnheader', { name: /이름/ });
    await nameHeader.getByRole('button').click();

    // 안내 메시지가 표시되어야 한다
    await expect(page.getByText(/현재 페이지 내 정렬이 적용됩니다/)).toBeVisible();

    // 정렬 해제(3차 클릭) 시 안내 메시지가 사라져야 한다
    await nameHeader.getByRole('button').click(); // desc
    await nameHeader.getByRole('button').click(); // none
    await expect(page.getByText('현재 페이지 내 정렬이 적용됩니다')).not.toBeVisible();
  });

  /**
   * 이슈 #358 회귀 방지 — 렌더가 느린 환경에서 헤더를 연속 클릭해도 정렬 전이가 유실되지 않아야 한다.
   * CPU 스로틀로 리렌더를 지연시켜, 이전 렌더의 정렬 상태를 기준으로 다음 상태를 계산하던
   * 결함(asc→desc→desc 로 계산되어 3번째 클릭에도 정렬이 해제되지 않음)을 재현 가능하게 만든다.
   */
  test('리렌더가 지연되는 상황에서 연속 클릭해도 정렬 해제까지 전이가 유실되지 않는다 (이슈 #358 회귀 방지)', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(createDatasets(3)));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    const cdp = await page.context().newCDPSession(page);
    await cdp.send('Emulation.setCPUThrottlingRate', { rate: 6 });

    await page.goto('/data/datasets');

    const nameHeader = page.getByRole('columnheader', { name: /이름/ });
    await nameHeader.getByRole('button').click(); // asc
    await expect(nameHeader).toHaveAttribute('aria-sort', 'ascending');

    // 중간 상태를 기다리지 않고 연속 클릭 — desc 를 거쳐 정렬 해제(none)에 도달해야 한다
    await nameHeader.getByRole('button').click(); // desc
    await nameHeader.getByRole('button').click(); // none
    await expect(nameHeader).toHaveAttribute('aria-sort', 'none');
    await expect(page.getByText('현재 페이지 내 정렬이 적용됩니다')).not.toBeVisible();
    await expect(page).toHaveURL(/\/data\/datasets(\?|$)/);

    await cdp.send('Emulation.setCPUThrottlingRate', { rate: 1 });
  });

  /**
   * 이슈 #358 — 딥링크로 진입한 정렬 상태에서도 순환이 URL 기준으로 이어져야 한다.
   * (ref 기반 전이 기준값이 URL 과 어긋나면 desc 진입 후 한 번 클릭에 해제되지 않는다)
   */
  test('desc 상태로 직접 진입 후 한 번 클릭하면 정렬이 해제된다 (이슈 #358)', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(createDatasets(3)));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets?sort=name&order=desc');

    const nameHeader = page.getByRole('columnheader', { name: /이름/ });
    await expect(nameHeader).toHaveAttribute('aria-sort', 'descending');
    await expect(page.getByText(/현재 페이지 내 정렬이 적용됩니다/)).toBeVisible();

    await nameHeader.getByRole('button').click(); // none
    await expect(nameHeader).toHaveAttribute('aria-sort', 'none');
    await expect(page.getByText('현재 페이지 내 정렬이 적용됩니다')).not.toBeVisible();
  });

  /**
   * 이슈 #358 — 뒤로가기로 정렬 상태가 되돌아간 뒤의 클릭도 URL 기준으로 이어져야 한다.
   * (클릭 시점 기준값을 ref 로 들고 있으므로, 히스토리 이동 시 재동기화되는지 확인)
   */
  test('뒤로가기로 정렬 상태가 바뀐 뒤에도 순환이 URL 기준으로 이어진다 (이슈 #358)', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(createDatasets(3)));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets?sort=name&order=asc');
    const nameHeader = page.getByRole('columnheader', { name: /이름/ });
    await expect(nameHeader).toHaveAttribute('aria-sort', 'ascending');

    await page.goto('/data/datasets?sort=name&order=desc');
    await expect(nameHeader).toHaveAttribute('aria-sort', 'descending');

    // 뒤로 → asc 로 복귀. 이후 클릭은 URL(asc) 기준으로 desc → none 순환해야 한다
    await page.goBack();
    await expect(nameHeader).toHaveAttribute('aria-sort', 'ascending');
    await nameHeader.getByRole('button').click();
    await expect(nameHeader).toHaveAttribute('aria-sort', 'descending');
    await nameHeader.getByRole('button').click();
    await expect(nameHeader).toHaveAttribute('aria-sort', 'none');
  });

  test('생성일 헤더도 정렬 가능하며 다른 컬럼 클릭 시 이전 컬럼 aria-sort 가 해제된다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(
      page,
      'GET',
      '/api/v1/datasets',
      createPageResponse(createDatasets(3)),
    );
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets');

    const nameHeader = page.getByRole('columnheader', { name: /이름/ });
    const createdAtHeader = page.getByRole('columnheader', { name: /생성일/ });

    // 이름으로 정렬
    await nameHeader.getByRole('button').click();
    await expect(nameHeader).toHaveAttribute('aria-sort', 'ascending');
    await expect(createdAtHeader).toHaveAttribute('aria-sort', 'none');

    // 생성일 클릭 → 이름 헤더의 aria-sort 는 'none' 으로 해제되어야 함
    await createdAtHeader.getByRole('button').click();
    await expect(createdAtHeader).toHaveAttribute('aria-sort', 'ascending');
    await expect(nameHeader).toHaveAttribute('aria-sort', 'none');
  });
});
