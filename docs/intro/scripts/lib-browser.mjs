/**
 * 촬영 스크립트 공통 — 브라우저 기동·로그인·브랜드 주입.
 *
 * - Playwright 는 firehub-web 의 devDependency 를 그대로 쓴다(툴체인을 늘리지 않기 위해).
 * - 브라우저는 시스템 크롬(channel 'chrome') — 이 머신에서 `playwright install` 은 압축 해제에서 멈춘다.
 * - 브랜드: 웹은 /config.js(window.__APP_CONFIG__)로 이름을 읽는다. 배포처럼 이 파일만 바꿔 끼워
 *   고객용 제품 이름(Gen:iA Data)으로 찍는다 — 코드 기본값을 고치지 않는다.
 */
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const ROOT = path.resolve(HERE, '../../..');
const require = createRequire(path.join(ROOT, 'apps/firehub-web/package.json'));
export const { chromium } = require('@playwright/test');

export const WEB = process.env.INTRO_WEB ?? 'http://localhost:5273';
export const BRAND = 'Gen:iA Data';

const CONFIG_JS = `(function(){var c={brandName:${JSON.stringify(BRAND)},logoUrl:null,faviconUrl:'/vite.svg'};window.__APP_CONFIG__=c;document.title=c.brandName;})();`;

/** 데스크톱 규격: 1440×810(16:9) · 배율 2 → 2880×1620. */
export async function openDesktop({ headless = true } = {}) {
  const browser = await chromium.launch({ channel: 'chrome', headless });
  const context = await browser.newContext({
    viewport: { width: 1440, height: 810 },
    deviceScaleFactor: 2,
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    colorScheme: 'light',
  });
  await context.route('**/config.js', (route) => route.fulfill({ contentType: 'application/javascript', body: CONFIG_JS }));
  const page = await context.newPage();
  return { browser, context, page };
}

/** 로그인 화면을 거쳐 들어간다(access token 이 메모리에만 있어 저장소 주입으로 흉내 낼 수 없다). */
export async function loginUi(page, username, password) {
  await page.goto(`${WEB}/login`);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('button[type=submit]').click();
  await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 20000 });
  await settle(page);
}

/** 화면이 자리를 잡을 때까지 — 네트워크 정지 + 애니메이션 여유. */
export async function settle(page, ms = 1200) {
  await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(ms);
}
