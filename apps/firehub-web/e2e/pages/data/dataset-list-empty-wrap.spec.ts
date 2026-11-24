import type { Locator, Page } from '@playwright/test';

import { createCategories, createDatasets } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions } from '../../fixtures/dataset.fixture';
import { expectClearOfFade, expectInside, lineHeightOf, rectOf } from '../../fixtures/table-visible-width';

/**
 * 데이터셋 목록 — 검색 0건 빈 행의 긴 검색어 줄바꿈 (WD-1012)
 *
 * 표 스크롤 상자가 좁은 화면에서 가로로 스크롤될 때, 긴 검색어가 든 안내 문구가 한 줄로 늘어나
 * 「검색 초기화」와 함께 보이는 폭 밖으로 잘렸다. 안내는 보이는 폭 안에서 감겨야 한다.
 */
const KW = 'monthly-incident-report-for-regional-fire-station-2026';

test.beforeEach(async ({ authenticatedPage: page }) => {
  await grantDatasetPermissions(page);
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse([]));
});

/**
 * 검색어로 진입해 「검색 초기화」가 보일 때까지 기다린다.
 * URL ?q= 의 검색어가 목록 조회의 search 파라미터로 그대로 갔는지도 확인한다 — 0건 안내가 그 검색어의 결과여야 한다.
 * 지연 낭독 노드 갈아끼움(WD-815)이 끝난 뒤에 값을 돌려준다 — 갈아끼기 전에 재면 WD-1063 플레이크로 돌아온다.
 */
async function gotoEmpty(page: Page, keyword: string = KW) {
  const listRequest = page.waitForRequest((req) => {
    const url = new URL(req.url());
    return url.pathname === '/api/v1/datasets' && url.searchParams.get('search') === keyword;
  });
  await page.goto('/data/datasets?q=' + encodeURIComponent(keyword));
  await listRequest;
  const reset = page.getByRole('button', { name: '검색 초기화' });
  await expect(reset).toBeVisible();
  const status = page.getByRole('status').filter({ hasText: '에 대한 결과가 없습니다' });
  // 안내 문구는 마운트 뒤 ANNOUNCE_DELAY_MS(100ms)에 노드를 갈아끼운다(지연 낭독, WD-815) —
  // 갈아끼우는 순간에 재면 떨어져 나간 노드를 잡아 높이 0·줄높이 normal 로 나와 헛실패한다(WD-1063).
  // 준비가 끝나면 pending 노드의 aria-hidden 이 없어지니 그때까지 기다린 뒤에 잰다.
  // DelayedStatus 가 갈아끼우는 바로 밑 자식(:scope >)에만 대응 — 그 노드 안쪽 깊은 곳의 장식용
  // aria-hidden 은 세지 않아 대기 참조가 갈아끼움 노드 하나로 고정된다(대기 타임아웃 방지).
  await expect(status.locator(':scope > [aria-hidden]')).toHaveCount(0);
  return {
    box: page.locator('[data-slot="table-container"]'),
    status,
    reset,
  };
}

test.describe('데이터셋 목록 — 빈 행 줄바꿈 (WD-1012)', () => {
  // 하이픈이 있는 검색어(이슈 재현)와, 줄바꿈 기회가 없어 글자 단위로 꺾여야 하는 공백·하이픈 없는 긴 낱말
  for (const [name, keyword] of [
    ['긴 검색어', KW],
    ['공백·하이픈 없는 긴 낱말 검색어', 'monthlyincidentreportforregionalfirestation2026abcdefghij'],
  ] as const) {
    test(`375px ${name} 안내는 여러 줄로 감기고 안내·「검색 초기화」가 보이는 폭 안·페이드 밖`, async ({ authenticatedPage: page }) => {
      await page.setViewportSize({ width: 375, height: 720 });
      const { box, status, reset } = await gotoEmpty(page, keyword);
      const b = await rectOf(box);
      const s = await rectOf(status);
      const firstP = status.locator('p').first();
      // 안내 첫 문단(검색어 포함)이 한 줄을 넘어 감겨야 한다
      expect((await rectOf(firstP)).height).toBeGreaterThan((await lineHeightOf(firstP)) * 1.5);
      expectInside(s, b);
      expectInside(await rectOf(reset), b);
      await expectClearOfFade(page, s);
      await expectClearOfFade(page, await rectOf(reset));
    });
  }

  test('375px 표를 오른쪽 끝까지 밀어도 안내와 「검색 초기화」가 보인다', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 375, height: 720 });
    const { box, status, reset } = await gotoEmpty(page);
    expect(await box.evaluate((el) => el.scrollWidth > el.clientWidth)).toBe(true);
    await box.evaluate((el) => {
      el.scrollLeft = el.scrollWidth;
    });
    expect(await box.evaluate((el) => el.scrollLeft)).toBeGreaterThan(0);
    const b = await rectOf(box);
    expectInside(await rectOf(status), b);
    expectInside(await rectOf(reset), b);
    await expectClearOfFade(page, await rectOf(status));
    await expectClearOfFade(page, await rectOf(reset));
  });

  test('빈 행이 있으면 스크롤 상자가 크기 컨테이너, 검색 초기화로 목록이 보이면 아니다', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 375, height: 720 });
    const containerType = (l: Locator) => l.evaluate((el) => getComputedStyle(el).containerType);
    const { box, reset } = await gotoEmpty(page);
    expect(await containerType(box)).toBe('inline-size');

    const datasets = createDatasets(2);
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(datasets));
    await reset.click();
    await expect(page.getByText(datasets[0].name)).toBeVisible();
    expect(await containerType(box)).toBe('normal');
  });

  test('데스크톱 1280px 에서는 안내·「검색 초기화」가 셀 가운데', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    const { reset } = await gotoEmpty(page);
    const r = await rectOf(reset);
    const td = await rectOf(page.locator('td[colspan]').filter({ has: reset }));
    expect(Math.abs((r.left + r.right) / 2 - (td.left + td.right) / 2)).toBeLessThanOrEqual(1);
  });
});
