import { createSavedQueryList } from '../factories/analytics.factory';
import { createPipelines } from '../factories/pipeline.factory';
import { createSynonymReviewItem } from '../factories/reviewItem.factory';
import { setupAdminAuth, setupAuditLogMocks, setupOntologyMocks } from '../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';

/**
 * 접근성 회귀 가드 — 리플로우(#345)와 동작 줄이기(#344).
 *
 * - #345 (WCAG SC 1.4.10 Reflow): 320 CSS px 폭에서 2차원 스크롤이 없어야 한다.
 *   데이터 표는 자체 overflow-x-auto 컨테이너를 가지므로 SC 예외에 해당 — 판정 기준은 <main> 자체의 가로 초과다.
 * - #344 (WCAG SC 2.3.3): prefers-reduced-motion: reduce 에서 키프레임 모션이 감쇠되어야 하되,
 *   포커스 가시성을 담당하는 transition 은 살아 있어야 한다.
 */

/** <main>이 가로로 넘치는 픽셀 수. 0이면 리플로우 만족. */
const mainOverflow = (page: import('@playwright/test').Page) =>
  page.evaluate(() => {
    const m = document.querySelector('main');
    return m ? m.scrollWidth - m.clientWidth : -1;
  });

test.describe('320px 리플로우 (#345)', () => {
  test('AI 검수 인박스가 320px에서 가로 스크롤 없이 표시된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem()]);
    await page.setViewportSize({ width: 320, height: 800 });
    await page.goto('/knowledge-graph/review');
    await expect(page.getByText('전기적 요인')).toBeVisible();

    expect(await mainOverflow(page)).toBe(0);
    // 원인이 탭 목록이었으므로, 탭이 실제로 줄바꿈해 컨테이너 폭 안에 들어왔는지도 확인한다.
    const list = page.locator('[data-slot=tabs-list]');
    const fits = await list.evaluate((el) => el.scrollWidth <= el.clientWidth + 1);
    expect(fits).toBe(true);
  });

  test('지식 모델 페이지가 320px에서 가로 스크롤 없이 표시된다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.setViewportSize({ width: 320, height: 800 });
    await page.goto('/knowledge-graph/model');
    await expect(page.locator('[data-slot=tabs-list]')).toBeVisible();

    expect(await mainOverflow(page)).toBe(0);
  });

  test('데스크톱(1280px)에서는 탭이 기존대로 한 줄로 유지된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem()]);
    await page.goto('/knowledge-graph/review');
    await expect(page.getByText('전기적 요인')).toBeVisible();

    // 줄바꿈 도입이 데스크톱 표현을 바꾸지 않아야 한다 — 탭 5개가 한 줄(높이 36px)에 그대로 있어야 한다.
    const box = await page.locator('[data-slot=tabs-list]').boundingBox();
    expect(box?.height).toBeLessThan(45);
    expect(await mainOverflow(page)).toBe(0);
  });
});

/**
 * 감사 로그 날짜 범위 필터 리플로우 회귀 가드 (#357).
 *
 * 원인은 `w-[150px]` date Input 2개 + `~` 구분자를 담은 묶음의 min-content 폭이 325px로 고정되어
 * 320px 뷰포트의 가용 폭(272px)을 넘긴 것. 표는 shadcn Table이 자체 overflow-x-auto 래퍼를
 * 렌더하므로 원인이 아니다(SC 1.4.10 예외).
 */
test.describe('320px 리플로우 — 감사 로그 날짜 필터 (#357)', () => {
  /**
   * 날짜 범위 묶음(시작 날짜 Input의 부모)의 실측값.
   *
   * 넘치는 flex 자식은 스스로 늘어날 뿐 스크롤되지 않아 `scrollWidth === clientWidth`가 되므로,
   * 묶음 자체의 `scrollWidth` 비교는 수정 전후 모두 통과하는 공허한 단언이다.
   * 부모(필터 바) 폭과 직접 비교해야 초과를 잡을 수 있다.
   */
  const dateGroupFit = (page: import('@playwright/test').Page) =>
    page.getByLabel('시작 날짜').evaluate((input) => {
      const group = input.parentElement!;
      return {
        groupWidth: Math.round(group.getBoundingClientRect().width),
        parentWidth: group.parentElement!.clientWidth,
        wrap: getComputedStyle(group).flexWrap,
        inputWidth: Math.round(input.getBoundingClientRect().width),
      };
    });

  test('감사 로그가 320px에서 가로 스크롤 없이 표시된다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupAuditLogMocks(page);
    await page.setViewportSize({ width: 320, height: 800 });
    await page.goto('/admin/audit-logs');
    // 모킹이 어긋나 필터 바 자체가 안 그려지면 overflow 단언이 공허하게 통과하므로 먼저 확인한다.
    await expect(page.getByLabel('시작 날짜')).toBeVisible();
    await expect(page.getByLabel('종료 날짜')).toBeVisible();

    const group = await dateGroupFit(page);
    // 수정 메커니즘 자체를 명시적으로 고정한다 — mainOverflow만 보면 컨테이너 폭이 넉넉해지는
    // 무관한 변경으로 가드가 조용히 공허해질 수 있다.
    expect(group.wrap).toBe('wrap');
    expect(group.groupWidth).toBeLessThanOrEqual(group.parentWidth + 1);
    // 좁은 폭에서 날짜를 실제로 읽고 고를 수 있어야 한다 — 폭 축소가 아닌 줄바꿈으로 해결한 이유.
    // flex-1로 줄이면 123px가 되어 date input 내부 텍스트가 잘린다.
    expect(group.inputWidth).toBe(150);
    expect(await mainOverflow(page)).toBe(0);
  });

  test('데스크톱(1280px)에서는 날짜 필터가 기존대로 한 줄 150px씩 유지된다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupAuditLogMocks(page);
    await page.goto('/admin/audit-logs');
    await expect(page.getByLabel('시작 날짜')).toBeVisible();

    // 줄바꿈은 max-sm 한정이어야 한다 — 데스크톱 표현(150px 2개 + `~` 한 줄)은 그대로다.
    const group = await dateGroupFit(page);
    expect(group.wrap).toBe('nowrap');
    expect(group.inputWidth).toBe(150);
    const endWidth = await page
      .getByLabel('종료 날짜')
      .evaluate((el) => Math.round(el.getBoundingClientRect().width));
    expect(endWidth).toBe(150);
    expect(await mainOverflow(page)).toBe(0);
  });
});

/**
 * SimplePagination 리플로우 회귀 가드 (#356).
 *
 * 페이지네이션 nav 폭은 표시 페이지 버튼 수에 비례해 커진다(처음/이전/다음/마지막 4개 + 페이지 버튼).
 * 페이지 수가 적으면 320px 안에 우연히 들어가므로, 회귀 가드가 공허해지지 않도록
 * 테스트는 **총 12페이지**(생략 부호 분기까지 타는 크기)를 모킹해 최대 폭 상태를 만든다.
 */
test.describe('320px 리플로우 — 페이지네이션 (#356)', () => {
  /** 페이지네이션 nav 자체가 컨테이너 폭 안에 들어왔는지 + 버튼이 몇 개인지 */
  const navFit = (page: import('@playwright/test').Page) =>
    page.locator('nav[aria-label="페이지네이션"]').evaluate((el) => ({
      fits: el.scrollWidth <= el.clientWidth + 1,
      buttons: el.children.length,
      wrap: getComputedStyle(el).flexWrap,
      height: Math.round(el.getBoundingClientRect().height),
    }));

  test('쿼리 목록(건수+사이즈 표시형)이 320px에서 페이지 버튼을 줄바꿈한다', async ({
    authenticatedPage: page,
  }) => {
    // 총 120건 / 페이지당 10건 = 12페이지 → 버튼이 최대치로 늘어난 상태
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/queries',
      createPageResponse(createSavedQueryList(10), { totalElements: 120, size: 10 }),
    );
    await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', []);
    await page.setViewportSize({ width: 320, height: 800 });
    await page.goto('/analytics/queries');
    await expect(page.locator('nav[aria-label="페이지네이션"]')).toBeVisible();

    const nav = await navFit(page);
    // 공허한 통과 방지 — 버튼이 실제로 8개 이상인 상태에서만 의미 있는 단언이다.
    expect(nav.buttons).toBeGreaterThanOrEqual(8);
    expect(nav.wrap).toBe('wrap');
    expect(nav.fits).toBe(true);
    expect(await mainOverflow(page)).toBe(0);
  });

  test('파이프라인 목록(네비게이션만 표시형)이 320px에서 페이지 버튼을 줄바꿈한다', async ({
    authenticatedPage: page,
  }) => {
    // SimplePagination은 건수/사이즈 정보 유무에 따라 상위 레이아웃이 달라지므로 두 분기를 모두 검증한다.
    await mockApi(
      page,
      'GET',
      '/api/v1/pipelines',
      createPageResponse(createPipelines(10), { totalElements: 120, size: 10 }),
    );
    await page.setViewportSize({ width: 320, height: 800 });
    await page.goto('/pipelines');
    await expect(page.locator('nav[aria-label="페이지네이션"]')).toBeVisible();

    const nav = await navFit(page);
    expect(nav.buttons).toBeGreaterThanOrEqual(8);
    expect(nav.fits).toBe(true);
    expect(await mainOverflow(page)).toBe(0);
  });

  test('데스크톱(1280px)에서는 페이지네이션이 기존대로 한 줄로 유지된다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/queries',
      createPageResponse(createSavedQueryList(10), { totalElements: 120, size: 10 }),
    );
    await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', []);
    await page.goto('/analytics/queries');
    await expect(page.locator('nav[aria-label="페이지네이션"]')).toBeVisible();

    // 줄바꿈은 max-sm 한정이어야 한다 — 공용 컴포넌트라 7개 목록 페이지의 데스크톱 표현이 걸려 있다.
    const nav = await navFit(page);
    expect(nav.wrap).toBe('nowrap');
    expect(nav.height).toBeLessThan(45);
    expect(await mainOverflow(page)).toBe(0);
  });
});

test.describe('동작 줄이기 (#344)', () => {
  test('reduce 설정에서 확인 다이얼로그의 확대 애니메이션이 감쇠된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem()]);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.goto('/knowledge-graph/review');

    await page.getByRole('button', { name: /승인/ }).click();
    const dialog = page.getByTestId('review-decide-confirm');
    await expect(dialog).toBeVisible();

    const motion = await dialog.evaluate((el) => {
      const cs = getComputedStyle(el);
      return { name: cs.animationName, duration: parseFloat(cs.animationDuration) };
    });
    // 애니메이션 자체는 걸려 있되(enter) 즉시 완료되어야 한다.
    expect(motion.name).not.toBe('none');
    expect(motion.duration).toBeLessThan(0.01);
  });

  test('reduce 설정에서도 포커스 가시성용 transition은 살아 있다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem()]);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.goto('/knowledge-graph/review');

    // 탭 트리거의 transition을 함께 죽이면 활성 인디케이터/포커스 링 표현이 사라져
    // SC 2.4.7 쪽에 결함을 새로 심게 된다 — 의도적으로 남긴 부분의 회귀 가드.
    const trans = await page.locator('[data-slot=tabs-trigger]').first()
      .evaluate((el) => parseFloat(getComputedStyle(el).transitionDuration));
    expect(trans).toBeGreaterThan(0);
  });

  test('기본(no-preference) 설정에서는 다이얼로그 모션이 그대로 재생된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem()]);
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await page.goto('/knowledge-graph/review');

    await page.getByRole('button', { name: /승인/ }).click();
    const duration = await page.getByTestId('review-decide-confirm')
      .evaluate((el) => parseFloat(getComputedStyle(el).animationDuration));
    // 감쇠 규칙이 미디어 쿼리 밖으로 새어 나가 모든 사용자의 모션을 죽이지 않았는지 확인한다.
    expect(duration).toBeGreaterThan(0.05);
  });
});
