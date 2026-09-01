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

  // S2 Task 3 리뷰 IMP-4: 요소 편집기 모드(3-pane: 아웃라인 w-64 + 캔버스 + 인스펙터 w-80)는
  // 읽기 모드보다 고정폭 패널이 하나 더 많아 320px에서 새로 리플로우가 깨질 수 있다.
  // mainOverflow는 조상의 overflow-hidden에 가려 0을 보고하면서도 실제로는 캔버스가 폭 0으로
  // 밀려나고 인스펙터가 화면 밖으로 잘려 나가는 상태를 놓칠 수 있었다(구현 중 실측) — 그래서
  // mainOverflow 0인지 뿐 아니라 캔버스가 실제로 화면 안에서 양의 폭을 갖는지도 함께 확인한다.
  // `> 0`은 캔버스가 1px로 밀려나도 통과하는 반쪽짜리 단언이었다(M-3, S2 최종 리뷰) — 실제 구현
  // (xl:flex/xl:block, #403로 sm에서 완화)이 보장하는 것을 그대로 단언한다: 아웃라인/인스펙터가 실제로 숨겨져 320px
  // 대부분을 캔버스가 차지하는지(실측 248px 안팎 — 컨테이너 padding 등을 뺀 값이라 320에 못
  // 미치지만 리플로우가 깨졌을 때의 0~수px과는 자릿수가 다르다) + 폭이 있는지 함께 확인한다.
  test('요소 편집기 모드(3-pane)도 320px에서 가로 스크롤 없이 표시된다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.setViewportSize({ width: 320, height: 800 });
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();

    expect(await mainOverflow(page)).toBe(0);
    // 좁은 화면에서는 아웃라인/인스펙터를 접어 캔버스에 폭을 몰아준다 — 캔버스가 뷰포트 안에서
    // 실제로 넓게 보여야 한다(1px로 밀려나 있으면 안 된다).
    const canvasBox = await page.getByTestId('schema-graph').boundingBox();
    expect(canvasBox!.width).toBeGreaterThan(200);
    await expect(page.getByTestId('model-outline')).toBeHidden();
    await expect(page.getByTestId('model-inspector')).toBeHidden();
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
 * 지식그래프 툴바 — 태블릿 폭(~700px) 겹침 회귀 가드 (#402).
 *
 * 원인은 `<h1>지식그래프</h1>`에 whitespace-nowrap이 없던 것 — 컨테이너의 sm:flex-nowrap은
 * 형제 요소 사이의 줄바꿈만 막을 뿐, 개별 자식의 텍스트 자체가 CJK 글자 단위로 줄어드는 것은
 * 막지 못해 h1이 12px로 찌그러지고 햄버거 아이콘·탭 레이블과 겹쳤다. 수정은 h1에
 * whitespace-nowrap shrink-0을 추가하고, 툴바 전체의 줄바꿈 브레이크포인트를 sm(640px)에서
 * md(768px)로 완화해 640~768px 대역에서 항목이 실제로 들어갈 자리가 없을 때는 줄바꿈하도록 했다.
 */
test.describe('지식그래프 툴바 태블릿 폭 겹침 (#402)', () => {
  /**
   * h1 제목의 실측값 + 툴바 자체가 뷰포트 폭 안에 들어왔는지.
   *
   * mainOverflow(<main> 전체 기준)는 이 페이지의 ReactFlow 캔버스가 초기 노드 배치→dagre
   * 레이아웃 정착 사이에 일시적으로 폭을 넓게 잡는 타이밍 특성이 있어(병렬 워커로 CPU가
   * 몰릴 때만 재현) 이 이슈와 무관한 이유로 플레이크가 난다 — 그래서 여기서는 캔버스를
   * 제외한 툴바 자체의 scrollWidth/clientWidth로 판정 범위를 좁힌다.
   */
  const titleFit = (page: import('@playwright/test').Page) =>
    page.locator('h1').evaluate((h1) => {
      const toolbar = h1.closest('div')!;
      return {
        text: h1.textContent,
        whiteSpace: getComputedStyle(h1).whiteSpace,
        width: Math.round(h1.getBoundingClientRect().width),
        toolbarWrap: getComputedStyle(toolbar).flexWrap,
        toolbarFits: toolbar.scrollWidth <= toolbar.clientWidth + 1,
      };
    });

  test('700px(태블릿 폭)에서 제목이 줄바꿈되지 않고 찌그러지지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.setViewportSize({ width: 700, height: 1000 });
    await page.goto('/knowledge-graph/model');
    await expect(page.locator('[data-slot=tabs-list]')).toBeVisible();

    const title = await titleFit(page);
    // 공허한 통과 방지 — 실제 텍스트가 렌더링된 상태에서 폭이 붕괴(≈12px)하지 않았는지 확인한다.
    expect(title.text).toBe('지식그래프');
    expect(title.whiteSpace).toBe('nowrap');
    expect(title.width).toBeGreaterThan(40);
    expect(title.toolbarFits).toBe(true);
  });

  test('데스크톱(1280px)에서는 툴바가 기존대로 한 줄(h-12)로 유지된다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    // 병렬 워커 간 기본 뷰포트 크기가 흔들리는 걸 피하려고 명시적으로 고정한다.
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.goto('/knowledge-graph/model');
    await expect(page.locator('[data-slot=tabs-list]')).toBeVisible();

    const title = await titleFit(page);
    // 브레이크포인트 완화(sm→md)가 데스크톱 표현을 바꾸지 않아야 한다 — 1280px는 완화 전(sm)과
    // 완화 후(md) 기준 모두 nowrap 구간이라 이 값 자체는 변경으로 바뀌지 않는다. 스키마 탭은
    // 온톨로지 선택기(고정 220px)+버튼 여럿이 붙어 툴바 실폭이 빠듯한 기존 상태라, toolbarFits는
    // 병렬 워커의 폰트 메트릭 타이밍에 따라 경계선에서 흔들려(#402와 무관한 기존 특성) 여기서는
    // 단언하지 않는다 — 이 이슈의 회귀 지표는 wrap 여부와 h-12 높이 유지다.
    expect(title.toolbarWrap).toBe('nowrap');
    const toolbarHeight = await page
      .locator('h1')
      .evaluate((h1) => Math.round(h1.closest('div')!.getBoundingClientRect().height));
    expect(toolbarHeight).toBeLessThan(55);
  });
});

/**
 * 지식 모델 3-pane 편집기 — sm~lg 태블릿·좁은 데스크톱 폭 캔버스 압착 회귀 가드 (#403).
 *
 * 원인은 ModelOutline(w-64)·인스펙터(w-80, 합 576px)가 `sm:flex`/`sm:block`으로 640px부터 즉시
 * 나타난 것 — 640~1024px 사이(태블릿 세로 폭 포함)에서는 두 패널이 동시에 보이면서 가운데 캔버스가
 * 200px 안팎으로 짓눌려 스키마 그래프(타입 노드·관계 엣지)를 사실상 읽을 수 없었다. 수정은 두 패널의
 * 브레이크포인트를 `sm`(640px)에서 `xl`(1280px)로 완화해, 그 미만 폭에서는 320px 리플로우와 동일하게
 * 캔버스에 폭을 몰아준다. `lg`(1024px)가 아니라 `xl`을 택한 이유는 AppLayout 사이드바(펼침 시 240px)
 * 까지 겹치면 1024px 뷰포트에서도 아웃라인+인스펙터+사이드바가 이미 816px를 차지해 캔버스가 여전히
 * 짓눌리는 것을 실측했기 때문이다(수정 전 lg 시도에서 이 테스트가 실패하며 드러남). 캔버스 wrapper에는
 * 방어적으로 `min-w-[280px]`도 추가했다.
 */
test.describe('지식 모델 3-pane 편집기 태블릿·좁은 데스크톱 폭 캔버스 압착 (#403)', () => {
  test('834px(iPad Air 세로)에서 아웃라인/인스펙터가 숨고 캔버스가 넓게 보인다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.setViewportSize({ width: 834, height: 1112 });
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();

    // 수정 전에는 이 폭에서 아웃라인/인스펙터가 sm:flex/sm:block으로 이미 나타나 캔버스가
    // 210px 안팎으로 압착됐다(#403 실측) — xl 완화 후에는 둘 다 숨고 캔버스가 뷰포트 대부분을 차지한다.
    await expect(page.getByTestId('model-outline')).toBeHidden();
    await expect(page.getByTestId('model-inspector')).toBeHidden();
    const canvasBox = await page.getByTestId('schema-graph').boundingBox();
    expect(canvasBox!.width).toBeGreaterThan(700);
  });

  // 1024px(구 lg 경계)에서는 AppLayout 사이드바(펼침 시 240px)까지 겹치면 3-pane을 보여줘도 캔버스가
  // 여전히 짓눌린다는 것을 실측으로 확인했다 — 그래서 이 폭에서는 계속 숨겨져야 한다(xl 미만).
  test('1024px(구 lg 경계)에서는 사이드바까지 감안해 여전히 아웃라인/인스펙터가 숨는다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.setViewportSize({ width: 1024, height: 900 });
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();

    await expect(page.getByTestId('model-outline')).toBeHidden();
    await expect(page.getByTestId('model-inspector')).toBeHidden();
  });

  test('1280px(xl 경계) 이상에서는 사이드바를 포함해도 3-pane이 모두 넉넉하게 보인다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();

    await expect(page.getByTestId('model-outline')).toBeVisible();
    await expect(page.getByTestId('model-inspector')).toBeVisible();
    const canvasBox = await page.getByTestId('schema-graph').boundingBox();
    // xl 기준폭(1280) - 사이드바(240, 펼침) - 아웃라인(256) - 인스펙터(320) - 여백 ≈ 400px대 —
    // 최소 하한(280px)보다 충분히 넓어야 한다.
    expect(canvasBox!.width).toBeGreaterThan(280);
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
