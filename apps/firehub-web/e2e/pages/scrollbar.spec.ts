import { expect, test } from '../fixtures/auth.fixture';

/**
 * 스크롤바 전역 스타일 회귀 가드.
 *
 * 배경: 앱 전역 스크롤바 규칙이 없어 OS 기본 스크롤바가 그대로 노출됐다. macOS는 마우스가
 * 연결되면 15px 클래식 스크롤바를 상시 표시하는데, 데이터 테이블처럼 내부 스크롤러가 있는
 * 화면에서 콘텐츠 옆에 두꺼운 밝은 바가 붙어 보였다.
 *
 * 여기서 검증하는 것은 **브라우저가 실제로 해석한 결과**다 —
 * 토큰 값 자체의 대비(SC 1.4.11 3:1)는 `src/styles/design-tokens.contrast.test.ts`가 담당하고,
 * 이 spec은 규칙이 살아서 적용되는지와 `data-scrolling` 상태 전이만 본다.
 *
 * 주의: 헤드리스 Chromium은 오버레이 스크롤바 모드가 아니므로 실제 폭을 잴 수 있다.
 * 다만 폭 측정은 플랫폼 편차가 있어, 여기서는 "OS 기본(15px 내외)보다 확실히 얇다"만 단언한다.
 */

test.describe('스크롤바 전역 스타일', () => {
  test('스크롤 컨테이너의 스크롤바가 OS 기본보다 얇다', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/');
    await expect(page.locator('main')).toBeVisible();

    // 브라우저가 의사요소에 실제로 해석해 넣은 값을 읽는다.
    // 레이아웃 폭(offsetWidth - clientWidth)으로 재면 오버레이 스크롤바 환경에서 항상 0이라
    // 규칙을 통째로 지워도 통과하는 무의미한 단언이 된다 — 그래서 의사요소를 직접 본다.
    const pseudo = await page.evaluate(() => {
      const probe = document.createElement('div');
      probe.style.cssText =
        'width:100px;height:100px;overflow-y:scroll;position:absolute;top:-9999px';
      probe.innerHTML = '<div style="height:500px"></div>';
      document.body.appendChild(probe);
      const bar = getComputedStyle(probe, '::-webkit-scrollbar').width;
      const thumb = getComputedStyle(probe, '::-webkit-scrollbar-thumb').backgroundColor;
      probe.remove();
      return { bar, thumb };
    });

    // 규칙이 죽으면 OS 기본(macOS 15px / Windows 17px)이 나온다.
    expect(pseudo.bar).toBe('8px');
    // thumb이 투명하면 "스크롤 가능" 어포던스가 사라진다 — 유휴 상태에서도 색이 있어야 한다.
    expect(pseudo.thumb).not.toBe('rgba(0, 0, 0, 0)');
    expect(pseudo.thumb).not.toBe('transparent');
  });

  test('Firefox용 프로퍼티와 WebKit 의사요소가 함께 적용된다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/');
    // Firefox는 ::-webkit-*를 무시하므로 scrollbar-width가 없으면 그쪽에서 두꺼운 바가 남는다.
    // 그리고 `scrollbar-width`는 **상속되지 않는다** — html에만 걸면 중첩 컨테이너가 전부 누락된다.
    // 정작 두껍다고 지적된 곳이 중첩 컨테이너이므로 루트가 아니라 **내부 스크롤러**에서 확인한다.
    await expect(page.locator('aside nav')).toBeVisible();
    const widths = await page.evaluate(() => {
      const nested = document.querySelector('aside nav');
      return {
        root: getComputedStyle(document.documentElement).scrollbarWidth,
        nested: nested ? getComputedStyle(nested).scrollbarWidth : null,
      };
    });
    expect(widths.root).toBe('thin');
    expect(widths.nested).toBe('thin');

    // thumb 색은 시맨틱 토큰이어야 한다 — 하드코딩 회색이 들어오면 테마 전환을 못 따라간다.
    const thumb = await page.evaluate(() =>
      getComputedStyle(document.documentElement).getPropertyValue('--scrollbar-thumb').trim()
    );
    expect(thumb).not.toBe('');
  });

  test('스크롤하면 data-scrolling이 붙고 멎으면 사라진다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/');
    // 사이드바 nav는 항목 수가 뷰포트를 넘어 어떤 화면에서도 스크롤 가능하다
    // (main은 모킹 데이터 양에 따라 스크롤이 안 생길 수 있어 기준으로 쓰지 않는다).
    const nav = page.locator('aside nav');
    await expect(nav).toBeVisible();
    await expect
      .poll(async () => nav.evaluate((el) => el.scrollHeight - el.clientHeight))
      .toBeGreaterThan(0);

    await nav.evaluate((el) => {
      el.scrollTop = 30;
    });

    // scroll 이벤트는 버블링하지 않는다 — 캡처 단계 전역 리스너가 죽으면 여기서 실패한다.
    await expect(nav).toHaveAttribute('data-scrolling', 'true');

    // 유휴 800ms 뒤 해제 (여유를 두고 폴링)
    await expect
      .poll(async () => nav.evaluate((el) => el.hasAttribute('data-scrolling')), {
        timeout: 3000,
      })
      .toBe(false);
  });

  test('한 컨테이너를 스크롤해도 다른 컨테이너는 강조되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    // 엘리먼트별 타이머가 아니라 전역 단일 타이머로 구현하면 중첩 스크롤러끼리
    // 서로의 상태를 지운다(데이터 탭의 main + 내부 테이블이 실제 사례).
    await page.goto('/');
    const nav = page.locator('aside nav');
    await expect(nav).toBeVisible();

    await nav.evaluate((el) => {
      el.scrollTop = 30;
    });
    await expect(nav).toHaveAttribute('data-scrolling', 'true');
    await expect(page.locator('main')).not.toHaveAttribute('data-scrolling', 'true');
  });
});
