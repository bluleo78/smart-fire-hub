import type { Page } from '@playwright/test';

import type { BrandingConfig } from '../../src/types/branding';

/**
 * 런타임 브랜딩(/config.js) 모킹 헬퍼.
 * 사이트별 브랜딩은 배포 시 /config.js 파일 교체(마운트)로 바뀌므로, 테스트는 page.route 로 그 파일 응답을 대체한다.
 * branding.spec(화이트라벨)·page-title.spec(탭 제목, WD-580)이 같은 스텁을 쓴다.
 */

/**
 * /config.js 응답을 주어진 브랜딩으로 대체한다.
 * 본문은 실제 public/config.js 와 같은 동작을 흉내낸다 — 빈 brandName 이면 탭 제목을 건드리지 않고(#325), 파비콘 link 를 맞춘다.
 */
export async function mockBrandingConfig(page: Page, cfg: BrandingConfig) {
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
