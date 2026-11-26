import type { Page } from '@playwright/test';

import { createOntologyGraph } from '../../factories/ontology.factory';
import { mockOntologyGraph, setupAdminAuth, setupOntologyMocks } from '../../fixtures/admin.fixture';
import { expect, test } from '../../fixtures/auth.fixture';

// 패널 안 「타입 검색」 입력 — 실제 역할(searchbox/textbox)에 상관없이 라벨로 찾는다.
const searchInput = (page: Page) => page.getByTestId('type-filter-panel').getByLabel('타입 검색');

/**
 * 지식 모델 탭 타입 필터 패널 검색 0건 안내 E2E (WD-1128)
 * - 패널 안 「타입 검색」에서 일치하는 타입이 없으면 빈 패널 대신 공용 SearchNoResult 를 보인다.
 * - 지식 모델 탭에는 툴바 「이름 검색」이 없어(WD-915) 툴바 검색 값 불변 단언은 생략한다.
 */
test.describe('지식 모델 타입 필터 검색 0건 (WD-1128)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await expect(page.getByTestId('type-filter-panel')).toBeVisible();
    await expect(page.getByTestId('type-filter-list').getByRole('button')).toHaveCount(6);
  });

  test('타입 검색 0건이면 검색어를 인용하고 검색 초기화로 되돌린다 (WD-1128)', async ({ authenticatedPage: page }) => {
    const panel = page.getByTestId('type-filter-panel');
    const input = searchInput(page);
    await input.fill('zzzznotfound');

    await expect(panel.getByText("'zzzznotfound'에 대한 결과가 없습니다.")).toBeVisible();
    await expect(panel.getByText('다른 키워드로 검색해 보세요.')).toBeVisible();
    // 스크린리더 안내: role=status 안에 문구가 있다.
    await expect(panel.getByRole('status').filter({ hasText: "'zzzznotfound'에 대한 결과가 없습니다." })).toHaveCount(1);

    await panel.getByRole('button', { name: '검색 초기화' }).click();
    await expect(input).toHaveValue('');
    await expect(page.getByTestId('type-filter-list').getByRole('button')).toHaveCount(6);
    await expect(input).toBeFocused();
  });

  test('공백뿐인 검색어는 0건 안내 없이 전체 타입을 보인다 (WD-1128)', async ({ authenticatedPage: page }) => {
    const panel = page.getByTestId('type-filter-panel');
    await searchInput(page).fill('   ');
    // 타입 버튼이 보인 뒤(상태가 가라앉은 뒤)에 부정 단언을 한다.
    await expect(page.getByTestId('type-filter-list').getByRole('button')).toHaveCount(6);
    await expect(panel.getByRole('button', { name: '검색 초기화' })).toHaveCount(0);
    await expect(panel.getByRole('status')).toHaveCount(0);
  });

  test('긴 검색어도 패널 밖으로 넘치지 않는다 (WD-1128)', async ({ authenticatedPage: page }) => {
    const panel = page.getByTestId('type-filter-panel');
    await searchInput(page).fill('x'.repeat(80));
    await expect(panel.getByText('다른 키워드로 검색해 보세요.')).toBeVisible();
    // 넘침은 목록 영역(overflow-y-auto 라 가로 넘침이 스크롤로 드러난다)에서 본다.
    const fits = await page.getByTestId('type-filter-list').evaluate((el) => el.scrollWidth <= el.clientWidth);
    expect(fits).toBe(true);
  });

  test('대소문자 섞인 부분 검색어는 일치하는 타입을 보이고 0건 안내를 띄우지 않는다 (WD-1128)', async ({ authenticatedPage: page }) => {
    const panel = page.getByTestId('type-filter-panel');
    await searchInput(page).fill('bUILd');
    await expect(page.getByTestId('type-filter-list').getByRole('button', { name: /Building/ })).toBeVisible();
    await expect(panel.getByRole('button', { name: '검색 초기화' })).toHaveCount(0);
    await expect(panel.getByRole('status')).toHaveCount(0);
  });
});

// 그래프 탐색 탭 — 캔버스 위 안내(InstanceGraph)와 패널 안내가 따로 있어도 패널 검색만 바뀐다.
test.describe('그래프 탐색 탭 타입 필터 검색 0건 (WD-1128)', () => {
  test('패널 0건 안내는 캔버스 노드 수를 바꾸지 않고, 검색 초기화로 되돌리면 초점이 타입 검색에 간다 (WD-1128)', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await mockOntologyGraph(page, createOntologyGraph());
    await page.goto('/knowledge-graph/explore');
    const graph = page.getByTestId('instance-graph');
    await expect(graph).not.toHaveAttribute('data-node-count', '0', { timeout: 20_000 });
    const before = await graph.getAttribute('data-node-count');
    const list = page.getByTestId('type-filter-list');
    await expect(list.getByRole('button')).toHaveCount(6);

    const input = searchInput(page);
    await input.fill('zzzznotfound');
    const group = page.getByRole('group', { name: '타입 검색 결과' });
    await expect(group.getByText("'zzzznotfound'에 대한 결과가 없습니다.")).toBeVisible();
    await expect(graph).toHaveAttribute('data-node-count', before ?? '');

    await group.getByRole('button', { name: '검색 초기화' }).click();
    await expect(input).toHaveValue('');
    await expect(list.getByRole('button')).toHaveCount(6);
    await expect(input).toBeFocused();
  });
});
