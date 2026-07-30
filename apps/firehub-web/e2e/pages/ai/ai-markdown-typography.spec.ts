/**
 * AI 응답 마크다운 타이포그래피 회귀 테스트 (#335)
 *
 * `@tailwindcss/typography` 플러그인이 미설치라 `prose*` 클래스가 전부 no-op이 되어
 * 표/목록/헤딩이 무스타일로 렌더되던 결함의 회귀 방지.
 *
 * "요소가 보이는가"만 검증하면 플러그인이 다시 빠져도 통과하므로,
 * 이슈에 기록된 것과 동일한 계산 스타일(td 패딩, ul 마커, 헤딩 크기)을 직접 단언한다.
 */
import { expect, test } from '../../fixtures/auth.fixture';

type Page = import('@playwright/test').Page;

const chipLocator = (page: Page) => page.getByRole('button', { name: /AI 상태/ });

/** AI 세션 + SSE 응답을 모킹하고 메시지를 전송한다 */
async function sendMessageWithResponse(page: Page, userMessage: string, markdown: string) {
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 1,
          sessionId: 'typo-session',
          title: null,
          createdAt: '2026-07-30T00:00:00Z',
          updatedAt: '2026-07-30T00:00:00Z',
        }),
      });
    },
  );
  await page.route(
    (url) => url.pathname === '/api/v1/ai/chat',
    (route) =>
      route.fulfill({
        status: 200,
        headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
        body: [
          'data: {"type":"init","sessionId":"typo-session"}\n\n',
          `data: ${JSON.stringify({ type: 'text', content: markdown })}\n\n`,
          'data: {"type":"done","inputTokens":10}\n\n',
        ].join(''),
      }),
  );

  await page.goto('/', { waitUntil: 'commit' });
  await chipLocator(page).click();
  const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
  await chatInput.waitFor({ state: 'visible', timeout: 5000 });
  await chatInput.fill(userMessage);
  await chatInput.press('Enter');
}

const TABLE_MARKDOWN = [
  '| id | name | storageType |',
  '| --- | --- | --- |',
  '| 3 | 고객 정보 | TABLE |',
  '| 7 | 고객 데이터 | TABLE |',
].join('\n');

const RICH_MARKDOWN = [
  '## 데이터셋 현황 요약',
  '',
  '- 전체: 30개',
  '- 저장 방식: TABLE 25개',
  '',
  '> 인용 블록',
].join('\n');

test.describe('AI 마크다운 타이포그래피 (#335)', () => {
  test('마크다운 표의 셀에 좌우 패딩과 행 구분선이 적용된다', async ({ authenticatedPage: page }) => {
    await sendMessageWithResponse(page, '표 렌더링 테스트', TABLE_MARKDOWN);

    const table = page.locator('.prose table');
    await expect(table).toBeVisible({ timeout: 10_000 });
    await expect(table.getByRole('cell', { name: '고객 정보' })).toBeVisible();

    // 첫 컬럼은 typography 규칙상 padding-left가 0이므로, 컬럼이 붙는 원인이었던
    // 두 번째 셀의 좌우 패딩을 검증한다 (수정 전: 상하좌우 전부 0px).
    const cellBox = await table.locator('tbody tr').first().locator('td').nth(1).evaluate((el) => {
      const s = getComputedStyle(el);
      return { left: s.paddingLeft, right: s.paddingRight, top: s.paddingTop };
    });
    expect(parseFloat(cellBox.left)).toBeGreaterThan(0);
    expect(parseFloat(cellBox.right)).toBeGreaterThan(0);
    expect(parseFloat(cellBox.top)).toBeGreaterThan(0);

    // 행 구분선 — 수정 전에는 0px
    const rowBorder = await table
      .locator('tbody tr')
      .first()
      .evaluate((el) => getComputedStyle(el).borderBottomWidth);
    expect(parseFloat(rowBorder)).toBeGreaterThan(0);
  });

  test('목록에 불릿 마커와 들여쓰기가, 헤딩에 본문보다 큰 글자 크기가 적용된다', async ({
    authenticatedPage: page,
  }) => {
    await sendMessageWithResponse(page, '목록 렌더링 테스트', RICH_MARKDOWN);

    const prose = page.locator('.prose').first();
    await expect(prose.getByRole('heading', { name: '데이터셋 현황 요약' })).toBeVisible({ timeout: 10_000 });

    // 목록: 수정 전 list-style-type: none / padding-left: 0px
    const list = await prose.locator('ul').first().evaluate((el) => {
      const s = getComputedStyle(el);
      return { marker: s.listStyleType, padLeft: s.paddingLeft };
    });
    expect(list.marker).not.toBe('none');
    expect(parseFloat(list.padLeft)).toBeGreaterThan(0);

    // 헤딩: 수정 전 본문과 동일한 크기라 위계가 소실됐다
    const headingSize = await prose
      .getByRole('heading', { name: '데이터셋 현황 요약' })
      .evaluate((el) => parseFloat(getComputedStyle(el).fontSize));
    const listItemSize = await prose
      .locator('li')
      .first()
      .evaluate((el) => parseFloat(getComputedStyle(el).fontSize));
    expect(headingSize).toBeGreaterThan(listItemSize);

    // 인용: 수정 전 border-left-width 0px
    const quoteBorder = await prose
      .locator('blockquote')
      .first()
      .evaluate((el) => parseFloat(getComputedStyle(el).borderLeftWidth));
    expect(quoteBorder).toBeGreaterThan(0);
  });

  test('본문 색은 시맨틱 토큰(부모 색)을 따르고 인라인 코드에 백틱이 노출되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await sendMessageWithResponse(page, '토큰 테스트', '상태는 `CERTIFIED` 입니다.\n\n- 항목');

    const prose = page.locator('.prose').first();
    await expect(prose.locator('code')).toHaveText('CERTIFIED', { timeout: 10_000 });

    // typography 기본값은 gray 팔레트를 하드코딩한다. 토큰 재매핑(currentColor)이
    // 살아 있으면 prose 본문 색이 말풍선(부모) 색과 같아야 한다.
    const colors = await prose.evaluate((el) => {
      const parent = el.parentElement as HTMLElement;
      const li = el.querySelector('li') as HTMLElement;
      return { parent: getComputedStyle(parent).color, li: getComputedStyle(li).color };
    });
    expect(colors.li).toBe(colors.parent);

    // typography가 삽입하는 code::before/after 백틱이 제거됐는지
    const backticks = await prose.locator('code').evaluate((el) => ({
      before: getComputedStyle(el, '::before').content,
      after: getComputedStyle(el, '::after').content,
    }));
    expect(backticks.before).toBe('none');
    expect(backticks.after).toBe('none');
  });
});
