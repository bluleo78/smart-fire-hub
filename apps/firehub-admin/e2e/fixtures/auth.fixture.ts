import { type Page, test as base } from '@playwright/test';

import { AUTH_FLAG_KEY } from '../../src/api/client';
import { createPlatformMe, createTokenResponse } from '../factories/platform.factory';
import { mockApi } from './api-mock';

/**
 * 인증 상태 만들기 — firehub-web 과 같은 전략이다. storageState 픽스처가 없으므로
 * (1) localStorage 플래그를 주입하고 (2) refresh/me 를 모킹한다.
 * AuthProvider 는 플래그가 있을 때만 refresh 를 시도하므로 이 둘이 짝이어야 한다.
 */
async function setupAuthMocks(page: Page, permissions?: string[]) {
  const me = permissions ? createPlatformMe({ permissions }) : createPlatformMe();
  await mockApi(page, 'POST', '/api/platform/auth/login', createTokenResponse());
  await mockApi(page, 'POST', '/api/platform/auth/refresh', createTokenResponse());
  await mockApi(page, 'POST', '/api/platform/auth/logout', {});
  await mockApi(page, 'GET', '/api/platform/auth/me', me);
}

async function enterAuthenticatedState(page: Page) {
  // addInitScript 의 함수는 브라우저 컨텍스트에서 직렬화되어 실행된다 — Node 쪽 클로저를
  // 그대로 못 읽으므로 키 이름은 두 번째 인자로 넘긴다(R-5: 앱 소스와 달라지면 안 된다).
  await page.addInitScript((key: string) => {
    localStorage.setItem(key, 'true');
  }, AUTH_FLAG_KEY);
}

type AuthFixtures = {
  /** 인증 API 만 모킹된 페이지. 로그인 화면 자체를 테스트할 때 쓴다. */
  authMockedPage: Page;
  /** 이미 로그인된 상태의 페이지. 모든 권한을 가진다. */
  authenticatedPage: Page;
};

/* eslint-disable react-hooks/rules-of-hooks -- Playwright fixture 의 use() 는 React Hook 이 아니다 */
export const test = base.extend<AuthFixtures>({
  authMockedPage: async ({ page }, use) => {
    await setupAuthMocks(page);
    await use(page);
  },
  authenticatedPage: async ({ page }, use) => {
    await setupAuthMocks(page);
    await enterAuthenticatedState(page);
    await use(page);
  },
});
/* eslint-enable react-hooks/rules-of-hooks */

/** 권한을 좁힌 세션을 만든다. 권한별 렌더 분기를 검증하는 스펙이 goto 이전에 부른다. */
export async function loginAs(page: Page, permissions: string[]) {
  await setupAuthMocks(page, permissions);
  await enterAuthenticatedState(page);
}

export { expect } from '@playwright/test';
