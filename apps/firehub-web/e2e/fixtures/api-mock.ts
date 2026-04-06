import type { Page } from '@playwright/test';

type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH';

/** 캡처된 요청 정보 */
interface CapturedRequest {
  payload: unknown;
  url: URL;
  searchParams: URLSearchParams;
}

/** mockApi capture: true 반환 타입 */
export interface MockApiCapture {
  /** 캡처된 모든 요청 배열 */
  requests: CapturedRequest[];
  /** 마지막 캡처된 요청 */
  lastRequest: () => CapturedRequest | undefined;
  /** 다음 요청이 올 때까지 대기 (최대 10초) */
  waitForRequest: () => Promise<CapturedRequest>;
}

interface MockApiOptions {
  status?: number;
  headers?: Record<string, string>;
  /** true로 설정하면 MockApiCapture 객체를 반환하여 요청 캡처가 가능하다 */
  capture?: boolean;
}

/**
 * API 엔드포인트를 모킹한다. capture: true 옵션 사용 시 요청을 캡처할 수 있다.
 * @returns capture: true인 경우 MockApiCapture 반환, 아니면 void
 */
export async function mockApi(
  page: Page,
  method: HttpMethod,
  path: string,
  body: unknown,
  options: MockApiOptions & { capture: true },
): Promise<MockApiCapture>;
export async function mockApi(
  page: Page,
  method: HttpMethod,
  path: string,
  body: unknown,
  options?: MockApiOptions,
): Promise<void>;
export async function mockApi(
  page: Page,
  method: HttpMethod,
  path: string,
  body: unknown,
  options: MockApiOptions = {},
): Promise<MockApiCapture | void> {
  const { status = 200, headers = {}, capture = false } = options;

  // 캡처 객체 초기화
  const captured: CapturedRequest[] = [];
  let resolveWaiter: ((req: CapturedRequest) => void) | null = null;

  await page.route(
    (url) => url.pathname === path,
    (route) => {
      if (route.request().method() !== method) {
        return route.fallback();
      }

      // 요청 캡처
      if (capture) {
        const reqUrl = new URL(route.request().url());
        let payload: unknown = null;
        try {
          payload = route.request().postDataJSON();
        } catch {
          // GET 등 body가 없는 요청
          const postData = route.request().postData();
          payload = postData ?? null;
        }
        const capturedReq: CapturedRequest = {
          payload,
          url: reqUrl,
          searchParams: reqUrl.searchParams,
        };
        captured.push(capturedReq);
        if (resolveWaiter) {
          resolveWaiter(capturedReq);
          resolveWaiter = null;
        }
      }

      return route.fulfill({
        status,
        contentType: 'application/json',
        headers,
        body: JSON.stringify(body),
      });
    },
  );

  if (capture) {
    return {
      requests: captured,
      lastRequest: () => captured[captured.length - 1],
      waitForRequest: () => {
        // 이미 캡처된 요청이 있으면 마지막 반환
        if (captured.length > 0) {
          return Promise.resolve(captured[captured.length - 1]);
        }
        return new Promise<CapturedRequest>((resolve, reject) => {
          resolveWaiter = resolve;
          // 10초 타임아웃
          setTimeout(() => {
            resolveWaiter = null;
            reject(new Error(`mockApi capture timeout: ${method} ${path}`));
          }, 10_000);
        });
      },
    };
  }
}

/**
 * 여러 API 엔드포인트를 한 번에 모킹한다.
 */
export async function mockApis(
  page: Page,
  mocks: Array<{ method: HttpMethod; path: string; body: unknown; options?: MockApiOptions }>,
): Promise<void> {
  for (const mock of mocks) {
    await mockApi(page, mock.method, mock.path, mock.body, mock.options);
  }
}

/**
 * Spring Boot PageResponse 형태의 응답 객체를 생성한다.
 */
export function createPageResponse<T>(
  content: T[],
  overrides?: { page?: number; size?: number; totalElements?: number; totalPages?: number },
) {
  const page = overrides?.page ?? 0;
  const size = overrides?.size ?? 10;
  const totalElements = overrides?.totalElements ?? content.length;
  const totalPages = overrides?.totalPages ?? Math.ceil(totalElements / size);
  return { content, page, size, totalElements, totalPages };
}
