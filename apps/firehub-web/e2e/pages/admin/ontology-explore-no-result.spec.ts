import { createOntologyGraph } from '../../factories/ontology.factory';
import { mockOntologyGraph, setupAdminAuth, setupOntologyMocks } from '../../fixtures/admin.fixture';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * (WD-1127) 그래프 탐색 검색 0건 오버레이 — 목록의 검색 0건 표준(검색어 인용·다음 행동 안내·「검색 초기화」)과
 * 같은 안내를 보이고, 초기화 뒤 노드가 다시 보이며 초점이 「이름 검색」 입력으로 돌아와야 한다.
 */
async function openExplore(page: import('@playwright/test').Page) {
  await setupOntologyMocks(page);
  await mockOntologyGraph(page, createOntologyGraph());
  await page.goto('/knowledge-graph/explore');
  await expect(page.getByTestId('instance-graph')).not.toHaveAttribute('data-node-count', '0', { timeout: 20_000 });
}

test.describe('그래프 탐색 검색 0건 (WD-1127)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  test('검색 0건 — 검색어 인용·안내 문구·「검색 초기화」로 복구하고 초점이 검색 입력에 간다', async ({ authenticatedPage: page }) => {
    await openExplore(page);
    const search = page.getByPlaceholder('이름 검색');
    await search.fill('zzzznotfound');

    const overlay = page.getByTestId('instance-graph-no-results');
    await expect(overlay).toContainText("'zzzznotfound'");
    await expect(overlay).toContainText('에 대한 결과가 없습니다.');
    await expect(overlay).toContainText('다른 키워드로 검색해 보세요.');
    const reset = overlay.getByRole('button', { name: '검색 초기화' });
    await expect(reset).toBeVisible();

    await reset.click();
    await expect(search).toHaveValue('');
    await expect(search).toBeFocused();
    await expect(overlay).toHaveCount(0);
    const count = await page.getByTestId('instance-graph').getAttribute('data-node-count');
    expect(Number(count)).toBeGreaterThan(0);
  });

  test('앞뒤 공백이 섞인 검색어도 인용은 trim 한 값이다', async ({ authenticatedPage: page }) => {
    await openExplore(page);
    await page.getByPlaceholder('이름 검색').fill('  zzzznotfound  ');
    await expect(page.getByTestId('instance-graph-no-results')).toContainText("'zzzznotfound'");
  });

  test('매우 긴 검색어도 「검색 초기화」 버튼이 잘리지 않고 보인다', async ({ authenticatedPage: page }) => {
    await openExplore(page);
    await page.getByPlaceholder('이름 검색').fill('z'.repeat(60));
    await expect(
      page.getByTestId('instance-graph-no-results').getByRole('button', { name: '검색 초기화' }),
    ).toBeVisible();
    // (WD-1127) 공백 없는 긴 검색어가 오버레이 오른쪽 끝을 넘지 않고 감기는지 — 문구 상자 우측 ≤ 오버레이 우측.
    const overlay = page.getByTestId('instance-graph-no-results');
    const textBox = await overlay.getByText('에 대한 결과가 없습니다.').locator('..').boundingBox();
    const overlayBox = await overlay.boundingBox();
    expect(textBox!.x + textBox!.width).toBeLessThanOrEqual(overlayBox!.x + overlayBox!.width + 0.5);
  });

  test('타입 필터가 걸린 상태의 검색 0건 — 검색 쪽 안내(「타입 필터 초기화」 없음)', async ({ authenticatedPage: page }) => {
    await openExplore(page);
    const buildingType = page.getByRole('button', { name: /^Building/ });
    await buildingType.click();
    // 타입 필터가 실제로 걸렸는지 확인한 뒤 검색한다 — 전체 활성에서 첫 클릭은 그 타입만 끈다(#404)
    await expect(buildingType).toHaveAttribute('aria-pressed', 'false');
    await page.getByPlaceholder('이름 검색').fill('zzzznotfound');
    const overlay = page.getByTestId('instance-graph-no-results');
    await expect(overlay.getByRole('button', { name: '검색 초기화' })).toBeVisible();
    await expect(overlay.getByRole('button', { name: '타입 필터 초기화' })).toHaveCount(0);
  });

  test('검색어가 다른 타입 노드에만 맞아 타입 필터가 0건으로 만들면 타입 안내 — 「타입 필터 초기화」로 복구하고 초점이 검색 입력에 간다', async ({ authenticatedPage: page }) => {
    await openExplore(page);
    const buildingType = page.getByRole('button', { name: /^Building/ });
    await buildingType.click();
    // 전체 활성에서 첫 클릭은 Building 만 끈다(#404) — 끈 타입의 노드('강남타워')만 맞는 검색어를 쓴다
    await expect(buildingType).toHaveAttribute('aria-pressed', 'false');
    const search = page.getByPlaceholder('이름 검색');
    await search.fill('강남타워');

    const overlay = page.getByTestId('instance-graph-no-results');
    await expect(overlay).toContainText('선택한 타입에 맞는 노드가 없습니다.');
    await expect(overlay.getByRole('button', { name: '검색 초기화' })).toHaveCount(0);
    const reset = overlay.getByRole('button', { name: '타입 필터 초기화' });
    await expect(reset).toBeVisible();

    await reset.click();
    await expect(overlay).toHaveCount(0);
    await expect(search).toBeFocused();
    const count = await page.getByTestId('instance-graph').getAttribute('data-node-count');
    expect(Number(count)).toBeGreaterThan(0);
  });
});
