import { type Page,test as base } from '@playwright/test';
import MCR from 'monocart-coverage-reports';

import type { TokenResponse, UserResponse } from '../../src/types/auth';
import type { RoleResponse } from '../../src/types/role';
import type { UserDetailResponse } from '../../src/types/user';
import { coverageOptions } from '../coverage-config';
import { mockApi } from './api-mock';
import { setupHomeMocks } from './base.fixture';

/**
 * 인증 모킹 테스트 fixture
 * - 로그인 API 응답을 모킹하여 인증된 상태의 page를 제공한다.
 * - 백엔드 없이 로그인이 필요한 페이지 테스트가 가능하다.
 * - src/types의 타입을 적용하여 API 스펙 변경 시 컴파일 에러로 감지한다.
 */

/** 모킹용 사용자 정보 — UserResponse 타입으로 API 스펙 정합성 보장 */
export const MOCK_USER: UserResponse = {
  id: 1,
  username: 'test@example.com',
  email: 'test@example.com',
  name: '테스트 사용자',
  isActive: true,
  createdAt: '2026-01-01T00:00:00',
};

/** 모킹용 토큰 응답 — TokenResponse 타입으로 API 스펙 정합성 보장 / 다른 테스트에서 재사용 가능하도록 export */
export const MOCK_TOKEN_RESPONSE: TokenResponse = {
  accessToken: 'mock-jwt-access-token',
  tokenType: 'Bearer',
  expiresIn: 3600,
};

/** 모킹용 역할 정보 */
const MOCK_ROLE: RoleResponse = {
  id: 1,
  name: 'USER',
  description: '일반 사용자',
  isSystem: true,
};

/** 모킹용 사용자 상세 정보 (roles 포함) — UserDetailResponse 타입으로 API 스펙 정합성 보장 */
const MOCK_USER_DETAIL: UserDetailResponse = {
  ...MOCK_USER,
  roles: [MOCK_ROLE],
};

/**
 * 인증 관련 API를 모킹하는 헬퍼
 * - POST /api/v1/auth/login → 토큰 응답
 * - POST /api/v1/auth/refresh → 토큰 갱신 응답
 * - GET /api/v1/users/me → 사용자 상세 정보
 * - 홈 대시보드 API도 함께 모킹 — 로그인 성공 후 '/'로 리다이렉트 시 필요
 */
async function setupAuthMocks(page: Page) {
  await mockApi(page, 'POST', '/api/v1/auth/login', MOCK_TOKEN_RESPONSE);
  await mockApi(page, 'POST', '/api/v1/auth/refresh', MOCK_TOKEN_RESPONSE);
  await mockApi(page, 'GET', '/api/v1/users/me', MOCK_USER_DETAIL);
  // 로그인 후 홈으로 리다이렉트될 때 대시보드 API 호출을 모킹
  await setupHomeMocks(page);
}

/**
 * localStorage 플래그 주입으로 로그인 UI 없이 인증 상태를 만든다.
 *
 * AuthContext는 localStorage의 'hasSession' 플래그가 있을 때만 refresh를 시도한다.
 * addInitScript로 페이지 로드 전에 플래그를 주입하면, 앱이 마운트될 때 refresh 모킹을
 * 통해 토큰을 획득하여 UI 로그인 플로우 없이 인증 상태 진입이 가능하다.
 */
async function enterAuthenticatedState(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('hasSession', 'true');
  });
  // addInitScript는 모든 navigation에 적용되므로 별도 이동 불필요.
  // 각 테스트가 자신의 타겟 페이지로 이동할 때 hasSession 플래그가 주입된다.
}

/** 커스텀 fixture 타입 정의 */
type AuthFixtures = {
  /** 인증 API가 모킹된 page (로그인 전) */
  authMockedPage: Page;
  /** 로그인까지 완료된 인증 상태의 page */
  authenticatedPage: Page;
  /**
   * V8 JS 커버리지 자동 수집 fixture
   * - auto: true로 모든 테스트에서 활성화
   * - Chromium에서만 동작 (page.coverage API는 Chromium 전용)
   * - 테스트 종료 시 monocart-coverage-reports로 누적 merge
   */
  autoCoverage: void;
};

/**
 * 인증 fixture를 포함한 확장 테스트 객체
 * - authMockedPage: 인증 API만 모킹 (로그인 페이지 테스트용)
 * - authenticatedPage: 로그인까지 완료 (인증이 필요한 페이지 테스트용)
 */
/* eslint-disable react-hooks/rules-of-hooks -- Playwright fixture의 use()는 React Hook이 아님 */
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

  /**
   * 모든 테스트에 자동 적용되는 V8 커버리지 수집기
   * - Chromium 프로젝트에서만 동작하며, 그 외 브라우저는 no-op
   * - 테스트 시작 시 JS 커버리지 수집 시작, 종료 시 monocart에 add + generate
   */
  autoCoverage: [
    async ({ page, browserName }, use) => {
      const isChromium = browserName === 'chromium';
      if (isChromium) {
        // resetOnNavigation: false — 페이지 이동해도 누적 수집
        await page.coverage.startJSCoverage({ resetOnNavigation: false });
      }
      await use();
      if (isChromium) {
        const coverageEntries = await page.coverage.stopJSCoverage();
        // add 만 호출 — 실제 리포트 생성은 globalTeardown 에서 수행한다.
        // 이렇게 해야 여러 테스트/워커의 데이터가 outputDir/.cache 에 누적되어 merge 된다.
        const mcr = MCR(coverageOptions);
        await mcr.add(coverageEntries);
      }
    },
    { auto: true },
  ],
});
/* eslint-enable react-hooks/rules-of-hooks */

export { expect } from '@playwright/test';
