import { setupQueryEditorMocks, setupQueryExecuteMock } from '../../fixtures/analytics.fixture';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 쿼리 편집기 — 휴대폰 폭 배치(WD-326).
 * 390 폭에서 테이블 목록이 펼쳐진 채 시작하면 SQL 편집기·결과가 100px 남짓으로 눌리고, 결과 머리글의 「결과」가 쪼개지며
 * 「차트로 만들기」가 화면 밖으로 밀렸다. 좁은 화면에서는 목록을 접은 채 시작하고, 펼치면 편집기 위에 쌓이며,
 * 결과 머리글은 줄바꿈되어 버튼이 화면 안에 들어오는지 확인한다.
 */
test.describe('쿼리 편집기 — 휴대폰 폭 (WD-326)', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('테이블 목록을 접은 채 시작하고, 실행 결과 머리글·「차트로 만들기」가 화면 안에 있으며 가로로 넘치지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupQueryEditorMocks(page, 1);
    await setupQueryExecuteMock(page);
    await page.goto('/analytics/queries/1');
    await expect(page.getByText('테스트 쿼리')).toBeVisible();

    // 접힌 채 시작 — 「테이블 보기」만 보이고 목록 패널은 화면에 없다
    await expect(page.getByRole('button', { name: '테이블 보기' })).toBeVisible();
    await expect(page.getByTestId('query-schema-panel')).toBeHidden();

    await page.getByRole('button', { name: '실행' }).click();
    await expect(page.getByRole('columnheader', { name: 'id' })).toBeVisible();

    // 「결과」가 한 줄(쪼개지지 않음) — 글자 높이가 한 줄 높이를 넘지 않는다
    const resultLabel = page.getByText('결과', { exact: true });
    const labelBox = await resultLabel.boundingBox();
    expect(labelBox!.height).toBeLessThan(30);

    // 「차트로 만들기」가 화면(390) 안에 있다
    const chartButton = page.getByRole('button', { name: '차트로 만들기' });
    const btn = await chartButton.boundingBox();
    expect(btn!.x).toBeGreaterThanOrEqual(0);
    expect(btn!.x + btn!.width).toBeLessThanOrEqual(390);

    // 본문이 가로로 넘치지 않는다
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });

  test('「테이블 보기」를 누르면 목록이 편집기 위에 화면 폭으로 쌓이고, 다시 접을 수 있다', async ({
    authenticatedPage: page,
  }) => {
    await setupQueryEditorMocks(page, 1);
    await page.goto('/analytics/queries/1');

    await page.getByRole('button', { name: '테이블 보기' }).click();
    const panel = page.getByTestId('query-schema-panel');
    await expect(panel).toBeVisible();
    await expect(panel.getByText('테이블 목록')).toBeVisible();

    // 옆이 아니라 위에 쌓인다 — 패널 아래쪽이 토글 버튼보다 위에 있고, 폭은 화면 폭에 가깝다
    const panelBox = (await panel.boundingBox())!;
    const toggleBox = (await page.getByRole('button', { name: '테이블 숨기기' }).boundingBox())!;
    expect(panelBox.y + panelBox.height).toBeLessThanOrEqual(toggleBox.y + 1);
    expect(panelBox.width).toBeGreaterThan(300);

    await page.getByRole('button', { name: '테이블 숨기기' }).click();
    await expect(panel).toBeHidden();
  });
});

test.describe('쿼리 편집기 — 넓은 화면은 그대로 (WD-326)', () => {
  test('데스크톱 폭에서는 테이블 목록이 펼쳐진 채 편집기 옆에 있다', async ({ authenticatedPage: page }) => {
    await setupQueryEditorMocks(page, 1);
    await page.goto('/analytics/queries/1');
    await expect(page.getByRole('button', { name: '테이블 숨기기' })).toBeVisible();
    const panelBox = (await page.getByTestId('query-schema-panel').boundingBox())!;
    const toggleBox = (await page.getByRole('button', { name: '테이블 숨기기' }).boundingBox())!;
    // 옆에 놓인다 — 토글 버튼이 패널 오른쪽에 있다
    expect(toggleBox.x).toBeGreaterThan(panelBox.x + panelBox.width - 1);
  });
});
