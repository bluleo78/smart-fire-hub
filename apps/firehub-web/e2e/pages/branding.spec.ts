import { expect, test } from '../fixtures/auth.fixture';

/**
 * 런타임 브랜딩(화이트라벨) E2E 테스트
 * - /config.js가 window.__APP_CONFIG__로 주입한 값이 탭 타이틀 · 로그인 · 사이드바에 반영되는지 검증.
 * - 기본값(미설정)은 기존 "Smart Fire Hub" 브랜드를 그대로 유지하는지도 함께 검증(회귀 방지).
 * - 사이트별 override는 /config.js 파일 교체(마운트)로 이뤄지므로 테스트는 page.route로 그 파일을 대체한다.
 */

/** 특정 브랜딩으로 /config.js 응답을 대체하는 헬퍼 (실제 배포의 파일 마운트를 흉내낸다) */
async function overrideConfig(
  page: import('@playwright/test').Page,
  cfg: { brandName: string; logoUrl: string | null; faviconUrl: string },
) {
  // 커스텀 로고는 실제로 로드 가능해야 한다 — 404면 BrandLogo가 기본 아이콘으로 폴백하므로(#325)
  // 이미지 렌더링을 검증하는 테스트가 성립하지 않는다. 404 폴백 케이스는 개별 테스트에서 덮어쓴다.
  if (cfg.logoUrl) {
    await page.route(`**${cfg.logoUrl}`, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'image/svg+xml',
        body: '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16"><rect width="16" height="16"/></svg>',
      }),
    );
  }
  await page.route('**/config.js', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/javascript',
      body: `
        window.__APP_CONFIG__ = ${JSON.stringify(cfg)};
        // 실제 config.js와 동일한 빈값 가드 — 빈 brandName이면 타이틀을 건드리지 않는다(#325).
        if (window.__APP_CONFIG__.brandName) { document.title = window.__APP_CONFIG__.brandName; }
        var l = document.querySelector("link[rel='icon']");
        if (!l) { l = document.createElement('link'); l.rel = 'icon'; document.head.appendChild(l); }
        l.href = window.__APP_CONFIG__.faviconUrl;
      `,
    }),
  );
}

test.describe('런타임 브랜딩 (화이트라벨)', () => {
  test('기본값: 미설정 시 Smart Fire Hub 브랜드가 유지된다', async ({ authMockedPage: page }) => {
    await page.goto('/login');

    // 탭 타이틀 기본값 유지
    await expect(page).toHaveTitle('Smart Fire Hub');
    // 로그인 화면 제목 기본값 유지
    await expect(page.getByText('Smart Fire Hub')).toBeVisible();
  });

  test('override: 커스텀 브랜드명·파비콘이 탭/로그인에 반영된다', async ({ authMockedPage: page }) => {
    await overrideConfig(page, {
      brandName: 'Acme Data',
      logoUrl: null,
      faviconUrl: '/branding/acme-fav.svg',
    });

    await page.goto('/login');

    // 탭 타이틀이 커스텀 브랜드로 교체
    await expect(page).toHaveTitle('Acme Data');
    // 로그인 화면 제목이 커스텀 브랜드로 교체
    await expect(page.getByText('Acme Data')).toBeVisible();
    // 벤더 기본 브랜드가 노출되지 않아야 함
    await expect(page.getByText('Smart Fire Hub')).toHaveCount(0);
    // 파비콘 link href가 커스텀 값으로 설정됨
    const faviconHref = await page.locator("link[rel='icon']").getAttribute('href');
    expect(faviconHref).toBe('/branding/acme-fav.svg');
  });

  test('override: logoUrl이 있으면 로그인 화면이 이미지 로고를 렌더링한다', async ({
    authMockedPage: page,
  }) => {
    await overrideConfig(page, {
      brandName: 'Acme Data',
      logoUrl: '/branding/acme-logo.svg',
      faviconUrl: '/branding/acme-fav.svg',
    });

    await page.goto('/login');

    // 로그인 카드에 로고 이미지가 alt=브랜드명 으로 렌더링되고 src가 설정됨
    const logo = page.locator('img[alt="Acme Data"]').first();
    await expect(logo).toHaveAttribute('src', '/branding/acme-logo.svg');
  });

  test('override: logoUrl이 있으면 사이드바가 이미지 로고를 렌더링한다', async ({
    authenticatedPage: page,
  }) => {
    await overrideConfig(page, {
      brandName: 'Acme Data',
      logoUrl: '/branding/acme-logo.svg',
      faviconUrl: '/branding/acme-fav.svg',
    });

    await page.goto('/');

    // 사이드바 브랜드명이 커스텀 값으로 표시
    await expect(page.getByText('Acme Data').first()).toBeVisible();
    // logoUrl 이미지가 alt=브랜드명 으로 렌더링되고 src가 설정됨 (기본 Flame 아이콘 대체)
    const logo = page.locator('img[alt="Acme Data"]').first();
    await expect(logo).toHaveAttribute('src', '/branding/acme-logo.svg');
  });

  test('오설정 방어: logoUrl이 404면 깨진 이미지 대신 기본 아이콘으로 폴백한다 (#325)', async ({
    authMockedPage: page,
  }) => {
    await overrideConfig(page, {
      brandName: 'Acme Data',
      logoUrl: '/branding/missing-logo.png',
      faviconUrl: '/vite.svg',
    });
    // 로고만 404로 응답 — 배포 시 경로 오타/파일 누락 상황을 재현한다.
    await page.route('**/branding/missing-logo.png', (route) => route.fulfill({ status: 404, body: '' }));

    await page.goto('/login');

    // 브랜드명은 그대로 살아있고
    await expect(page.getByText('Acme Data')).toBeVisible();
    // 깨진 <img>는 남지 않으며 기본 Flame 아이콘이 대신 렌더링된다
    await expect(page.locator('img[alt="Acme Data"]')).toHaveCount(0);
    await expect(page.locator('svg.logo-pulse').first()).toBeVisible();
  });

  test('오설정 방어: brandName이 빈 문자열이면 앱·탭 모두 기본 브랜드로 되돌아간다 (#325)', async ({
    authMockedPage: page,
  }) => {
    await overrideConfig(page, { brandName: '', logoUrl: null, faviconUrl: '/vite.svg' });

    await page.goto('/login');

    // 앱 브랜드가 공백으로 사라지지 않고, 탭 타이틀과 같은 값(기본 브랜드)으로 일치한다.
    // 수정 전에는 앱은 빈 문자열 / 탭만 index.html의 하드코딩 벤더명 → split-brand 상태였다.
    await expect(page).toHaveTitle('Smart Fire Hub');
    await expect(page.getByText('Smart Fire Hub')).toBeVisible();
  });

  test('기본값: logoUrl 미설정 시 사이드바가 이미지 로고를 쓰지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/');

    // 기본 브랜드명 유지
    await expect(page.getByText('Smart Fire Hub').first()).toBeVisible();
    // 이미지 로고가 아닌 기본 아이콘(Flame, svg)이므로 img[alt] 로고는 없어야 함
    await expect(page.locator('img[alt="Smart Fire Hub"]')).toHaveCount(0);
  });
});
