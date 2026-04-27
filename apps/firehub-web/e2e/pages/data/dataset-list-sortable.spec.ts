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
