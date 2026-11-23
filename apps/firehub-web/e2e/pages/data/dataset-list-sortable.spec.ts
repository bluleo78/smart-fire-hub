import type { Locator, Page } from '@playwright/test';

import { createCategories, createDatasets } from '../../factories/dataset.factory';
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
   * 이슈 #358 회귀 방지 — 렌더 전에 온 다음 클릭이 이전 렌더의 정렬로 전이를 계산하면 안 된다.
   * 하나씩 기다려 보낸 클릭 사이에는 렌더가 끝나 결함이 재현되지 않는다(CPU 스로틀은 부하에서 시간 초과만 만든다).
   * 그래서 한 브라우저 작업 안에서 DOM 클릭 두 번을 보낸다 — 실제 입력보다 엄격한 대역이다.
   * 덮는 것은 클릭 시점의 ref 전진까지다. 중간 렌더의 되감기를 막는 pendingSortRef 는 덮지 않는다.
   */
  test('렌더 전에 연속 클릭해도 정렬 해제까지 전이가 유실되지 않는다 (이슈 #358 회귀 방지)', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(createDatasets(3)));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets');
    // 목록이 다 그려진 뒤에 누른다 — 로드 중 렌더가 연속 클릭에 섞이지 않게
    await expect(page.getByRole('row').filter({ hasText: /^데이터셋\s/ })).toHaveCount(3);

    const nameHeader = page.getByRole('columnheader', { name: /이름/ });
    await nameHeader.getByRole('button').click(); // asc
    await expect(nameHeader).toHaveAttribute('aria-sort', 'ascending');

    // 2·3번째 클릭을 한 작업 안에서 — 사이에 렌더가 없어도 desc → none 에 도달해야 한다
    const nameSort = nameHeader.getByRole('button');
    await clickInOneTask(page, [nameSort, nameSort]);
    await expect(nameHeader).toHaveAttribute('aria-sort', 'none');
    await expect(page.getByText('현재 페이지 내 정렬이 적용됩니다')).not.toBeVisible();
    await expect(page).toHaveURL(/\/data\/datasets$/);
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

  /**
   * 이슈 WD-950 회귀 방지 — 렌더 전에 정렬과 필터를 잇달아 바꿔도 앞 변경이 주소에서 덮이지 않아야 한다.
   * react-router 의 setSearchParams 함수형 갱신은 prev 로 렌더 시점 스냅샷을 주므로,
   * 한 브라우저 작업 안에서 두 번 누르면 뒤 호출이 앞 호출을 덮어썼다(정렬이 사라지고 정렬 ref 는 오지 않을 값을 기다림).
   */
  test('정렬 직후 화면 반영 전에 즐겨찾기 필터를 눌러도 정렬이 남는다 (WD-950)', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(createDatasets(3)));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets');
    await expect(page.getByRole('row').filter({ hasText: /^데이터셋\s/ })).toHaveCount(3);

    const nameHeader = page.getByRole('columnheader', { name: /이름/ });
    const sortButton = nameHeader.getByRole('button');
    const favoriteButton = page.getByRole('button', { name: '즐겨찾기', exact: true });

    // 정렬 클릭과 필터 클릭을 한 작업 안에서 — 사이에 렌더가 없다
    await clickInOneTask(page, [sortButton, favoriteButton]);

    await expect(favoriteButton).toHaveAttribute('aria-pressed', 'true');
    await expect(nameHeader).toHaveAttribute('aria-sort', 'ascending');
    const params = () => new URL(page.url()).searchParams;
    expect(params().get('sort')).toBe('name');
    expect(params().get('order')).toBe('asc');
    expect(params().get('favorite')).toBe('true');

    // 정렬 ref 가 풀려 있어야 다음 클릭이 desc 로 이어진다
    await sortButton.click();
    await expect(nameHeader).toHaveAttribute('aria-sort', 'descending');
    expect(params().get('order')).toBe('desc');
    expect(params().get('favorite')).toBe('true');
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
