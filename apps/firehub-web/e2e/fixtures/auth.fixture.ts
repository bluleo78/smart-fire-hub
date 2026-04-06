import { type Page,test as base } from '@playwright/test';

import type { TokenResponse, UserResponse } from '../../src/types/auth';
import type { RoleResponse } from '../../src/types/role';
import type { UserDetailResponse } from '../../src/types/user';
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
 * 로그인 플로우를 실행하여 인증 상태를 만든다.
 * - 로그인 페이지 방문 → 자격증명 입력 → 로그인 버튼 클릭 → 홈 리다이렉트 대기
 */
async function performLogin(page: Page) {
  await page.goto('/login');
  await page.getByLabel('아이디 (이메일)').fill('test@example.com');
  await page.getByLabel('비밀번호').fill('testpassword123');
  await page.getByRole('button', { name: '로그인' }).click();
  // 로그인 성공 후 홈('/')으로 리다이렉트 대기
  await page.waitForURL('/');
}

/** 커스텀 fixture 타입 정의 */
type AuthFixtures = {
  /** 인증 API가 모킹된 page (로그인 전) */
  authMockedPage: Page;
  /** 로그인까지 완료된 인증 상태의 page */
  authenticatedPage: Page;
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
    await performLogin(page);
    await use(page);
  },
});

export { expect } from '@playwright/test';
