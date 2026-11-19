import type { Page } from '@playwright/test';

import { createQueryResult } from '../../factories/analytics.factory';
import { setupNewChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { CHART_SURFACE } from '../../fixtures/chart-selectors';

/**
 * 차트 빌더 미리보기 memo E2E (WD-821)
 *
 * 미리보기는 같은 입력이면 다시 그리지 않는 MemoChartRenderer 를 쓴다. memo 가 과해 두 가지를 놓치면 안 된다.
 *  1) 「저장」 대화상자처럼 부모 상태만 바뀌는 동안·뒤에도 미리보기가 그대로 남아야 한다.
 *  2) 차트 종류·Y축 같은 설정이 바뀌면 낡은 그림이 아니라 새 설정으로 갱신돼야 한다.
 */

/** 숫자 컬럼이 둘(매출·비용)인 결과로 쿼리 실행 모킹 후 미리보기가 그려질 때까지 진행한다 */
async function renderBarPreview(page: Page) {
  await setupNewChartBuilderMocks(page);
  await mockApi(
    page,
    'POST',
    '/api/v1/analytics/queries/1/execute',
    createQueryResult({
      columns: ['지역', '매출', '비용'],
      rows: [
        { 지역: '서울지역', 매출: 100, 비용: 30 },
        { 지역: '부산지역', 매출: 200, 비용: 60 },
      ],
      totalRows: 2,
    }),
  );
  await page.goto('/analytics/charts/new');
  await page.getByRole('combobox', { name: '저장된 쿼리' }).click();
  await page.getByRole('option', { name: '저장 쿼리 1' }).click();
  await page.getByRole('button', { name: '쿼리 실행' }).click();
  await expect(page.getByText(/행 로드됨/).first()).toBeVisible();
  // 실행하면 컬럼 구성에 맞춰 차트 종류가 자동 추천된다(여기서는 산점도) — 막대로 못박아 시작점을 고정한다
  await page.getByRole('group', { name: '차트 타입' }).getByRole('button', { name: '막대 차트' }).click();
  // 자동 추천 축은 결과 컬럼 구성에 따라 달라지므로 X축을 지역으로 못박는다 — 막대·눈금이 항상 같은 모양으로 그려지게 한다
  await page.getByRole('combobox', { name: 'X축' }).click();
  await page.getByRole('option', { name: '지역', exact: true }).click();
  // 막대가 실제로 그려진 뒤부터 비교한다
  await expect(page.locator('.recharts-surface').first()).toBeVisible();
  await expect(page.locator('.recharts-bar-rectangle').first()).toBeVisible();
}

test.describe('차트 빌더 미리보기 — 저장 대화상자·설정 변경 (WD-821)', () => {
  test('저장 대화상자를 열고 닫아도 미리보기 차트가 그대로 남는다', async ({
    authenticatedPage: page,
  }) => {
    await renderBarPreview(page);
    // 대화상자 오버레이 안의 svg 와 섞이지 않도록 미리보기 컨테이너로 범위를 좁힌다
    const preview = page.getByTestId('chart-builder-preview');
    const svg = preview.locator('.recharts-surface');
    // 툴팁(role=status)에도 같은 글자가 뜰 수 있어 차트 본체 svg 의 X축 눈금(첫 행 값)만 짚는다
    // WD-826: 차트는 figure 안에서 aria-hidden 이고 accessibilityLayer=false 라 role=application 도 없다 —
    // 범례 아이콘 svg 와 섞이지 않게 recharts-wrapper 바로 아래 svg(차트 본체)로 짚는다
    const tick = preview.locator(CHART_SURFACE).getByText('서울지역', { exact: true });

    await expect(tick).toBeVisible();
    const svgCount = await svg.count();
    // 범례 아이콘도 svg 라 개수만으론 약하다 — 실제 막대가 몇 개 그려졌는지도 함께 기록한다
    const bars = preview.locator('.recharts-bar-rectangle');
    const barCount = await bars.count();
    expect(barCount).toBeGreaterThan(0);
    expect(svgCount).toBeGreaterThan(0);

    // 헤더 「저장」 → 대화상자가 열린 동안에도 미리보기는 그대로
    await page.getByRole('button', { name: '저장', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expect(svg.first()).toBeVisible();

    // 「취소」로 닫은 뒤에도 같은 그림(svg 개수·X축 눈금)이 남는다
    await dialog.getByRole('button', { name: '취소' }).click();
    await expect(dialog).toHaveCount(0);
    await expect(svg).toHaveCount(svgCount);
    await expect(bars).toHaveCount(barCount);
    await expect(tick).toBeVisible();
  });

  test('저장 대화상자에서 이름을 입력해도 미리보기가 다시 그려지지 않는다 (WD-826)', async ({
    authenticatedPage: page,
  }) => {
    await renderBarPreview(page);
    const preview = page.getByTestId('chart-builder-preview');
    await expect(preview.locator('.recharts-bar-rectangle').first()).toBeVisible();
    await page.getByRole('button', { name: '저장', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    // 진입 애니메이션 등이 가라앉은 뒤부터 미리보기 하위 트리의 모든 변경(속성·자식·글자)을 센다
    await page.waitForTimeout(1500);
    await preview.evaluate((el) => {
      const w = window as unknown as { __previewMutations: number };
      w.__previewMutations = 0;
      new MutationObserver((records) => {
        w.__previewMutations += records.length;
      }).observe(el, { subtree: true, attributes: true, childList: true, characterData: true });
    });

    // 이름 입력은 saveForm.name 을 글자마다 바꾼다 — 접근 이름(figure aria-label)과 차트가 그대로여야 한다
    await dialog.getByLabel('이름').fill('입력 중인 이름');
    await page.waitForTimeout(500);
    expect(await page.evaluate(() => (window as unknown as { __previewMutations: number }).__previewMutations)).toBe(0);
  });

  test('차트 종류·Y축을 바꾸면 미리보기가 새 설정으로 바뀐다', async ({ authenticatedPage: page }) => {
    await renderBarPreview(page);

    // 1) 차트 종류: 막대 → 선 — 막대 그림이 남지 않고 선이 나타난다
    await page.getByRole('group', { name: '차트 타입' }).getByRole('button', { name: '선 차트' }).click();
    await expect(page.locator('.recharts-line').first()).toBeVisible();
    await expect(page.locator('.recharts-bar-rectangle')).toHaveCount(0);

    // 2) Y축: 숫자 컬럼 둘을 모두 고르면 선이 둘, 「매출」을 풀면 「비용」 선 하나만 남는다
    //    (자동 선택된 컬럼에 기대지 않도록 두 컬럼을 명시적으로 켠 뒤 하나를 끈다)
    const yGroup = page.getByRole('group', { name: 'Y축 (다중 선택)' });
    const legend = page.locator('.recharts-legend-item-text');
    await yGroup.getByLabel('매출').check();
    await yGroup.getByLabel('비용').check();
    await expect(page.locator('.recharts-line')).toHaveCount(2);
    await yGroup.getByLabel('매출').uncheck();
    await expect(legend).toHaveText(['비용']);
    await expect(page.locator('.recharts-line')).toHaveCount(1);
  });
});
