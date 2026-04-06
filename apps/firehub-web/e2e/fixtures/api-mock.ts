import type { Page } from '@playwright/test';

/**
 * API 모킹 유틸리티
 * - Playwright의 page.route()를 사용하여 백엔드 API 요청을 가로채고 모킹 응답 반환
 * - 실제 백엔드(Spring Boot + PostgreSQL) 없이 프론트엔드 E2E 테스트 가능
 */

type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH';

interface MockApiOptions {
  /** HTTP 상태 코드 (기본값: 200) */
  status?: number;
  /** 응답 헤더 */
  headers?: Record<string, string>;
}

/**
 * 특정 API 엔드포인트를 모킹한다.
 * @param page - Playwright Page 객체
 * @param method - HTTP 메서드
 * @param path - API 경로 (예: '/api/v1/auth/login')
 * @param body - 응답 본문 (JSON 직렬화)
 * @param options - 상태 코드, 헤더 등 추가 옵션
 */
export async function mockApi(
  page: Page,
  method: HttpMethod,
  path: string,
  body: unknown,
  options: MockApiOptions = {},
) {
  const { status = 200, headers = {} } = options;

  await page.route(`**${path}`, (route) => {
    // 지정된 HTTP 메서드만 가로채고, 나머지는 통과시킨다
    if (route.request().method() === method) {
      return route.fulfill({
        status,
        contentType: 'application/json',
        headers,
        body: JSON.stringify(body),
      });
    }
    return route.continue();
  });
}

/**
 * 여러 API 엔드포인트를 한 번에 모킹한다.
 * @param page - Playwright Page 객체
 * @param mocks - 모킹할 API 목록
 */
export async function mockApis(
  page: Page,
  mocks: Array<{
    method: HttpMethod;
    path: string;
    body: unknown;
    options?: MockApiOptions;
  }>,
) {
  for (const mock of mocks) {
    await mockApi(page, mock.method, mock.path, mock.body, mock.options);
  }
}
