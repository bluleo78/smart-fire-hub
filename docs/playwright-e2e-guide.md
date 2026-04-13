# Playwright E2E 테스트 가이드

> Smart Fire Hub의 프론트엔드 E2E 테스트 구조와 패턴을 다른 프로젝트에 동일하게 적용하기 위한 실전 가이드.

---

## 목차

1. [핵심 철학](#1-핵심-철학)
2. [디렉토리 구조](#2-디렉토리-구조)
3. [초기 셋업](#3-초기-셋업)
4. [Building Block 1 — api-mock.ts](#4-building-block-1--api-mockts)
5. [Building Block 2 — Factories (모킹 데이터)](#5-building-block-2--factories-모킹-데이터)
6. [Building Block 3 — Fixtures (API 모킹 헬퍼)](#6-building-block-3--fixtures-api-모킹-헬퍼)
7. [테스트 작성 — Pages (개별 페이지)](#7-테스트-작성--pages-개별-페이지)
8. [테스트 작성 — Flows (통합 시나리오)](#8-테스트-작성--flows-통합-시나리오)
9. [검증 품질 기준](#9-검증-품질-기준)
10. [자주 쓰는 패턴 레퍼런스](#10-자주-쓰는-패턴-레퍼런스)
11. [새 도메인 추가 체크리스트](#11-새-도메인-추가-체크리스트)

---

## 1. 핵심 철학

### 백엔드 없이 동작

`page.route()`로 API 요청을 브라우저 레벨에서 가로채 모킹한다. Spring Boot, Node.js, PostgreSQL 등 백엔드 인프라 없이 프론트엔드 dev 서버만으로 E2E 테스트가 완전히 실행된다.

```
[Playwright] → [Vite dev server] → [page.route() intercepts /api/*]
                                         ↓
                                   [Mock response 즉시 반환]
```

**장점:**
- CI/CD에서 DB, 백엔드 컨테이너 불필요 → 속도 향상
- 테스트 데이터를 코드로 완전히 제어 — 서버 상태에 의존하지 않음
- 에러 케이스(500, 401, 404) 재현이 trivial

### 타입 안전성

모킹 데이터는 `src/types/`에 정의된 실제 API 응답 TypeScript 타입을 그대로 사용한다. API 스펙이 바뀌면 팩토리 함수에서 컴파일 에러가 발생하여 테스트 데이터가 실제 스펙에서 벗어나는 것을 빌드 시점에 잡는다.

### "요소가 보이는가?"만으로는 부족하다

테스트는 **입력 → API payload → API 응답 → UI 반영** 전체 파이프라인을 검증해야 한다. 요소가 화면에 보이는지만 확인하는 스모크 테스트는 비즈니스 로직 결함을 잡지 못한다.

---

## 2. 디렉토리 구조

```
e2e/
├── factories/              # 모킹 데이터 생성 함수 (도메인별)
│   ├── auth.factory.ts
│   ├── product.factory.ts  # 예시: 신규 도메인
│   └── ...
│
├── fixtures/               # API 모킹 헬퍼 + Playwright fixture
│   ├── api-mock.ts         # ★ 핵심: mockApi(), mockApis(), createPageResponse()
│   ├── auth.fixture.ts     # authMockedPage, authenticatedPage fixture
│   ├── base.fixture.ts     # 공통 API 모킹 (홈 대시보드 등)
│   ├── product.fixture.ts  # 예시: 신규 도메인 모킹 헬퍼
│   └── ...
│
├── flows/                  # 유저 플로우 통합 시나리오
│   ├── auth.spec.ts        # 로그인→홈→로그아웃
│   ├── product-crud.spec.ts
│   └── ...
│
└── pages/                  # 개별 페이지 상세 테스트
    ├── auth/
    │   ├── login.spec.ts
    │   └── signup.spec.ts
    ├── product/
    │   ├── product-list.spec.ts
    │   ├── product-detail.spec.ts
    │   └── product-create.spec.ts
    └── ...
```

**pages vs flows 구분:**
| | `pages/` | `flows/` |
|---|---|---|
| 목적 | 개별 페이지의 엣지 케이스, 유효성 검사, 에러 처리 | 여러 페이지에 걸친 해피 패스 연속 시나리오 |
| 테스트 수 | 페이지당 5~10개 | 플로우당 2~4개 |
| 예시 | 검색 파라미터 검증, 500 에러 처리 | 목록→상세→생성→완료 |

---

## 3. 초기 셋업

### 3-1. 패키지 설치

```bash
pnpm add -D @playwright/test
npx playwright install chromium
```

### 3-2. playwright.config.ts

```typescript
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: 'html',

  use: {
    baseURL: 'http://localhost:5173',
    trace: 'on-first-retry',    // 실패 시 트레이스 수집
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  // dev 서버 자동 기동 — CI에서는 기존 서버 재사용 안 함
  webServer: {
    command: 'pnpm dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
  },
});
```

### 3-3. package.json scripts

```json
{
  "scripts": {
    "test:e2e": "playwright test",
    "test:e2e:ui": "playwright test --ui",
    "test:e2e:headed": "playwright test --headed"
  }
}
```

### 3-4. tsconfig.e2e.json (타입 체크 분리)

```json
{
  "extends": "./tsconfig.json",
  "include": ["e2e/**/*.ts"],
  "compilerOptions": {
    "types": ["@playwright/test"]
  }
}
```

```bash
# E2E 코드 타입 체크
npx tsc -p tsconfig.e2e.json --noEmit
```

---

## 4. Building Block 1 — api-mock.ts

모든 테스트의 기반이 되는 핵심 헬퍼. **그대로 복사**해서 `e2e/fixtures/api-mock.ts`에 넣는다.

```typescript
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
  requests: CapturedRequest[];
  lastRequest: () => CapturedRequest | undefined;
  /** 다음 요청이 올 때까지 대기 (최대 10초) */
  waitForRequest: () => Promise<CapturedRequest>;
}

interface MockApiOptions {
  status?: number;
  headers?: Record<string, string>;
  capture?: boolean;
}

/**
 * API 엔드포인트를 모킹한다. capture: true 옵션 사용 시 요청을 캡처할 수 있다.
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

  const captured: CapturedRequest[] = [];
  let resolveWaiter: ((req: CapturedRequest) => void) | null = null;

  await page.route(
    (url) => url.pathname === path,
    (route) => {
      if (route.request().method() !== method) {
        return route.fallback();
      }

      if (capture) {
        const reqUrl = new URL(route.request().url());
        let payload: unknown = null;
        try {
          payload = route.request().postDataJSON();
        } catch {
          payload = route.request().postData() ?? null;
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
        if (captured.length > 0) {
          return Promise.resolve(captured[captured.length - 1]);
        }
        return new Promise<CapturedRequest>((resolve, reject) => {
          resolveWaiter = resolve;
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
 * 페이지네이션 응답 객체를 생성한다.
 * Spring Boot Page / 커스텀 PageResponse 등 프로젝트 응답 형식에 맞게 수정한다.
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
```

### mockApi 동작 원리

| 기능 | 설명 |
|------|------|
| `page.route(url => url.pathname === path, ...)` | pathname 기반 매칭. glob 패턴보다 정확함 |
| `route.fallback()` | method가 다르면 다음 route 핸들러에 위임 (GET/POST 분리 모킹 가능) |
| `capture: true` | 요청을 캡처하여 payload/searchParams 검증 가능 |
| 나중에 등록된 route 우선 적용 | fixture 모킹을 테스트 내에서 `capture: true`로 덮어쓸 수 있음 |

---

## 5. Building Block 2 — Factories (모킹 데이터)

### 기본 패턴

```typescript
// e2e/factories/product.factory.ts

import type { ProductResponse, ProductDetailResponse } from '@/types/product';

/** 상품 응답 객체 생성 — overrides로 특정 필드만 덮어쓸 수 있다 */
export function createProduct(overrides?: Partial<ProductResponse>): ProductResponse {
  return {
    id: 1,
    name: '테스트 상품',
    price: 10000,
    category: 'electronics',
    createdAt: '2024-01-01T00:00:00Z',
    ...overrides,  // 마지막에 오버라이드 적용
  };
}

/** 상품 목록 생성 */
export function createProducts(count: number): ProductResponse[] {
  return Array.from({ length: count }, (_, i) =>
    createProduct({ id: i + 1, name: `상품 ${i + 1}` }),
  );
}

/** 상품 상세 응답 객체 생성 (관계 데이터 포함) */
export function createProductDetail(overrides?: Partial<ProductDetailResponse>): ProductDetailResponse {
  return {
    id: 1,
    name: '테스트 상품',
    price: 10000,
    description: '상세 설명',
    category: 'electronics',
    images: [],
    reviews: [],
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: null,
    ...overrides,
  };
}
```

### 핵심 원칙

**1. 타입을 반드시 명시한다**

```typescript
// ❌ 타입 없음 — API 스펙 변경 시 silent failure
export function createProduct(overrides?: object) {
  return { id: 1, name: '상품', ...overrides };
}

// ✅ 타입 명시 — API 스펙 변경 시 컴파일 에러로 감지
export function createProduct(overrides?: Partial<ProductResponse>): ProductResponse {
  return { id: 1, name: '상품', ...overrides };
}
```

**2. overrides는 마지막에 spread한다**

```typescript
// ✅ 올바른 순서: 기본값 먼저, overrides 나중
return {
  id: 1,
  name: '기본값',
  ...overrides,   // overrides가 기본값을 덮어씀
};
```

**3. 중첩 객체는 별도 팩토리로 분리한다**

```typescript
// category가 중첩 객체인 경우
export function createCategory(overrides?: Partial<CategoryResponse>): CategoryResponse {
  return { id: 1, name: '기본 카테고리', ...overrides };
}

export function createProduct(overrides?: Partial<ProductResponse>): ProductResponse {
  return {
    id: 1,
    name: '상품',
    category: createCategory(),  // 중첩 팩토리 재사용
    ...overrides,
  };
}
```

---

## 6. Building Block 3 — Fixtures (API 모킹 헬퍼)

### 6-1. auth.fixture.ts — 인증 fixture (필수)

프로젝트의 인증 구조에 맞게 수정하여 사용한다.

```typescript
// e2e/fixtures/auth.fixture.ts
import { type Page, test as base } from '@playwright/test';
import type { UserResponse, TokenResponse } from '@/types/auth';
import { mockApi } from './api-mock';
import { setupHomeMocks } from './base.fixture';

/** 모킹용 토큰 응답 */
export const MOCK_TOKEN_RESPONSE: TokenResponse = {
  accessToken: 'mock-jwt-access-token',
  tokenType: 'Bearer',
  expiresIn: 3600,
};

/** 모킹용 사용자 정보 */
export const MOCK_USER: UserResponse = {
  id: 1,
  email: 'test@example.com',
  name: '테스트 사용자',
};

/**
 * 인증 관련 API 모킹 설정
 * - 프로젝트의 실제 인증 엔드포인트에 맞게 수정한다
 */
async function setupAuthMocks(page: Page) {
  await mockApi(page, 'POST', '/api/v1/auth/login', MOCK_TOKEN_RESPONSE);
  await mockApi(page, 'POST', '/api/v1/auth/refresh', MOCK_TOKEN_RESPONSE);
  await mockApi(page, 'GET', '/api/v1/users/me', MOCK_USER);
  // 로그인 후 리다이렉트될 홈 페이지 API도 함께 모킹
  await setupHomeMocks(page);
}

/**
 * 로그인 플로우 실행 — 프로젝트의 실제 로그인 UI에 맞게 수정한다
 */
async function performLogin(page: Page) {
  await page.goto('/login');
  await page.getByLabel('이메일').fill('test@example.com');
  await page.getByLabel('비밀번호').fill('testpassword123');
  await page.getByRole('button', { name: '로그인' }).click();
  await page.waitForURL('/');  // 로그인 후 리다이렉트 URL
}

type AuthFixtures = {
  authMockedPage: Page;       // 인증 API 모킹만 (로그인 페이지 테스트용)
  authenticatedPage: Page;    // 로그인 완료 상태 (보호된 페이지 테스트용)
};

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
```

### 6-2. base.fixture.ts — 공통 모킹

로그인 성공 후 항상 진입하는 홈/대시보드 페이지의 API를 모킹한다.

```typescript
// e2e/fixtures/base.fixture.ts
import type { Page } from '@playwright/test';
import { mockApi } from './api-mock';

/**
 * 홈 페이지 API 모킹
 * - 로그인 후 리다이렉트되는 '/' 페이지가 호출하는 API를 모킹한다
 * - 모든 인증 fixture에서 공통으로 사용
 */
export async function setupHomeMocks(page: Page) {
  // 프로젝트의 홈 페이지 API에 맞게 수정
  await mockApi(page, 'GET', '/api/v1/dashboard/stats', { totalUsers: 10, activeProjects: 5 });
  await mockApi(page, 'GET', '/api/v1/notifications', []);
}
```

### 6-3. 도메인별 fixture

```typescript
// e2e/fixtures/product.fixture.ts
import type { Page } from '@playwright/test';
import { createProducts, createProductDetail } from '../factories/product.factory';
import { createPageResponse, mockApi } from './api-mock';

/** 상품 목록 페이지 API 모킹 */
export async function setupProductMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/products', createPageResponse(createProducts(5)));
  await mockApi(page, 'GET', '/api/v1/product-categories', [
    { id: 1, name: '전자기기' },
    { id: 2, name: '의류' },
  ]);
}

/** 상품 상세 페이지 API 모킹 */
export async function setupProductDetailMocks(page: Page, productId = 1) {
  await mockApi(page, 'GET', `/api/v1/products/${productId}`, createProductDetail({ id: productId }));
  await mockApi(page, 'GET', `/api/v1/products/${productId}/reviews`, createPageResponse([]));
}
```

---

## 7. 테스트 작성 — Pages (개별 페이지)

### 기본 구조

```typescript
// e2e/pages/product/product-list.spec.ts

import { createProducts } from '../../factories/product.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';  // ★ auth.fixture의 test 사용
import { setupProductMocks } from '../../fixtures/product.fixture';

test.describe('상품 목록 페이지', () => {

  // 테스트 1: 기본 렌더링
  test('상품 목록이 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupProductMocks(page);          // fixture로 API 모킹
    await page.goto('/products');           // 페이지 이동

    await expect(page.getByRole('heading', { name: '상품 목록' })).toBeVisible();

    // 데이터 행 개수 검증 (헤더 1 + 데이터 5)
    await expect(page.getByRole('row')).toHaveCount(6);

    // 셀 단위 데이터 검증
    const firstRow = page.getByRole('row', { name: /상품 1/ });
    await expect(firstRow.getByText('전자기기')).toBeVisible();
  });

  // 테스트 2: API 파라미터 검증 (capture 패턴)
  test('검색 입력 시 search 파라미터가 API에 전달된다', async ({ authenticatedPage: page }) => {
    await setupProductMocks(page);
    await page.goto('/products');
    await expect(page.getByRole('heading', { name: '상품 목록' })).toBeVisible();

    // 이 시점 이후의 요청만 캡처하기 위해 재모킹
    const capture = await mockApi(page, 'GET', '/api/v1/products', createPageResponse([]), {
      capture: true,
    });

    await page.getByPlaceholder('상품 검색...').fill('노트북');
    const req = await capture.waitForRequest();

    // ★ query param 검증
    expect(req.searchParams.get('search')).toBe('노트북');
  });

  // 테스트 3: 빈 상태
  test('빈 목록일 때 빈 상태 메시지를 표시한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/products', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/product-categories', []);
    await page.goto('/products');

    await expect(page.getByText('상품이 없습니다.')).toBeVisible();
  });

  // 테스트 4: 에러 처리
  test('서버 에러(500) 시 에러 상태를 표시한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/products', {}, { status: 500 });
    await mockApi(page, 'GET', '/api/v1/product-categories', []);
    await page.goto('/products');

    await expect(page.getByText('상품이 없습니다.')).toBeVisible();
  });

  // 테스트 5: 네비게이션
  test('상품 행 클릭 시 상세 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupProductMocks(page);
    await mockApi(page, 'GET', '/api/v1/products/1', createProductDetail({ id: 1 }));

    await page.goto('/products');
    await page.getByRole('cell', { name: '상품 1', exact: true }).click();

    await expect(page).toHaveURL(/\/products\/1/);
  });
});
```

### 로그인 페이지 패턴 (인증 불필요 페이지)

```typescript
// authMockedPage: 인증 API만 모킹 (로그인 전 상태)
test('로그인 성공 시 홈으로 이동하고 payload를 검증한다', async ({ authMockedPage: page }) => {
  await page.goto('/login');

  // fixture 모킹을 capture: true로 덮어씀
  const capture = await mockApi(
    page,
    'POST',
    '/api/v1/auth/login',
    { accessToken: 'token', tokenType: 'Bearer', expiresIn: 3600 },
    { capture: true },
  );

  await page.getByLabel('이메일').fill('user@example.com');
  await page.getByLabel('비밀번호').fill('password123');
  await page.getByRole('button', { name: '로그인' }).click();

  // ★ POST payload 검증
  const req = await capture.waitForRequest();
  expect(req.payload).toMatchObject({
    username: 'user@example.com',
    password: 'password123',
  });

  // ★ 리다이렉트 검증
  await page.waitForURL('/');
  await expect(page).toHaveURL('/');
});
```

### 폼 생성 페이지 패턴

```typescript
test('상품 생성 후 상세 페이지로 이동한다', async ({ authenticatedPage: page }) => {
  await mockApi(page, 'GET', '/api/v1/product-categories', [{ id: 1, name: '전자기기' }]);

  // ★ POST API를 capture: true로 모킹
  const capture = await mockApi(
    page,
    'POST',
    '/api/v1/products',
    { id: 42, name: '신규 상품', price: 50000 },  // 생성 후 응답
    { capture: true },
  );

  await page.goto('/products/new');

  // 폼 입력
  await page.getByLabel('상품명').fill('신규 상품');
  await page.getByLabel('가격').fill('50000');
  await page.getByRole('combobox', { name: '카테고리' }).selectOption('1');
  await page.getByRole('button', { name: '저장' }).click();

  // ★ API payload 검증
  const req = await capture.waitForRequest();
  expect(req.payload).toMatchObject({
    name: '신규 상품',
    price: 50000,
    categoryId: 1,
  });

  // ★ 생성 후 리다이렉트 검증
  await expect(page).toHaveURL(/\/products\/42/);
});
```

---

## 8. 테스트 작성 — Flows (통합 시나리오)

해피 패스 전체를 연결하여 페이지 간 네비게이션과 상태 전달을 검증한다.

```typescript
// e2e/flows/product-crud.spec.ts

import { createProductDetail, createProducts } from '../../factories/product.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupProductMocks } from '../../fixtures/product.fixture';

test.describe('상품 CRUD 플로우', () => {

  test('목록에서 상품 클릭 → 상세 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupProductMocks(page);
    await mockApi(page, 'GET', '/api/v1/products/1', createProductDetail({ id: 1, name: '상품 1' }));

    await page.goto('/products');
    await page.getByRole('cell', { name: '상품 1', exact: true }).click();

    await expect(page).toHaveURL(/\/products\/1/);
    await expect(page.getByRole('heading', { name: '상품 1' })).toBeVisible();
  });

  test('상품 생성 → 취소 → 목록으로 돌아온다', async ({ authenticatedPage: page }) => {
    await setupProductMocks(page);
    await mockApi(page, 'GET', '/api/v1/product-categories', []);

    await page.goto('/products');
    await page.getByRole('link', { name: '상품 추가' }).click();
    await expect(page).toHaveURL('/products/new');

    await page.getByRole('button', { name: '취소' }).click();
    await expect(page).toHaveURL('/products');
  });

  test('상품 생성 완료 후 상세 페이지에서 정보가 표시된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/product-categories', [{ id: 1, name: '전자기기' }]);
    await mockApi(page, 'POST', '/api/v1/products', createProductDetail({ id: 99, name: '최신 상품' }));
    await mockApi(page, 'GET', '/api/v1/products/99', createProductDetail({ id: 99, name: '최신 상품' }));
    await mockApi(page, 'GET', '/api/v1/products/99/reviews', createPageResponse([]));

    await page.goto('/products/new');
    await page.getByLabel('상품명').fill('최신 상품');
    await page.getByRole('button', { name: '저장' }).click();

    await expect(page).toHaveURL(/\/products\/99/);
    await expect(page.getByRole('heading', { name: '최신 상품' })).toBeVisible();
  });
});
```

---

## 9. 검증 품질 기준

### 6가지 검증 항목

| # | 항목 | 방법 |
|---|------|------|
| 1 | **폼 입력 → API payload** | `capture: true` + `waitForRequest()` + `expect(req.payload).toMatchObject(...)` |
| 2 | **API 응답 → UI 반영** | `getByRole('cell')`, `getByText()` 등으로 모킹 데이터가 렌더링되는지 셀 단위 확인 |
| 3 | **필터/검색 → API 파라미터** | `capture: true` + `expect(req.searchParams.get('key')).toBe(value)` |
| 4 | **상태 변경 → UI 즉시 반영** | 버튼 클릭 후 `aria-label`, `disabled`, 텍스트 변경 등 확인 |
| 5 | **에러 처리** | `{ status: 400/401/500 }` 모킹 후 에러 메시지 표시 확인 |
| 6 | **유효성 검사** | 빈 폼 제출 후 Zod/폼 라이브러리 에러 메시지 확인 |

### 나쁜 예 vs 좋은 예

```typescript
// ❌ 나쁜 예 — 요소 존재만 확인 (스모크 테스트)
await expect(page.getByText('상품 1')).toBeVisible();

// ✅ 좋은 예 — 전체 파이프라인 검증
// 1. 입력
await page.getByLabel('상품명').fill('노트북');

// 2. API payload 검증
const req = await capture.waitForRequest();
expect(req.payload).toMatchObject({ name: '노트북' });

// 3. UI 반영 검증 (행의 특정 셀)
const row = page.getByRole('row', { name: /노트북/ });
await expect(row.getByText('전자기기')).toBeVisible();  // category

// 4. 네비게이션 검증
await expect(page).toHaveURL(/\/products\/\d+/);
```

---

## 10. 자주 쓰는 패턴 레퍼런스

### 응답 지연으로 로딩 상태 검증

```typescript
// page.route 직접 사용으로 딜레이 구현
await page.route('**/api/v1/auth/login', async (route) => {
  if (route.request().method() === 'POST') {
    await new Promise<void>((resolve) => { setTimeout(resolve, 800); });
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ accessToken: 'token' }),
    });
  }
  return route.continue();
});

await page.getByRole('button', { name: '로그인' }).click();

// 로딩 중 버튼 비활성화 검증
await expect(page.getByRole('button', { name: '로그인 중...' })).toBeDisabled();
```

### 동적 경로 모킹

```typescript
// /api/v1/products/1, /api/v1/products/2 등 동적 경로
await page.route(/\/api\/v1\/products\/\d+/, (route) => {
  const id = parseInt(route.request().url().split('/').pop()!);
  return route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(createProductDetail({ id })),
  });
});
```

### 관리자 페이지 — AdminRoute 우회

```typescript
// admin.fixture.ts
async function setupAdminMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/users/me', {
    ...MOCK_USER,
    roles: [{ id: 1, name: 'ADMIN', isSystem: true }],  // ADMIN 역할 부여
  });
}

// 테스트에서 사용
test('관리자 페이지가 렌더링된다', async ({ page }) => {
  await setupAdminMocks(page);
  await setupAuthMocks(page);
  await performLogin(page);
  await page.goto('/admin/users');
  // ...
});
```

### 페이지네이션 테스트

```typescript
test('페이지네이션이 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
  await mockApi(
    page,
    'GET',
    '/api/v1/products',
    createPageResponse(
      createProducts(10),
      { totalElements: 35, totalPages: 4 },  // 35개, 4페이지
    ),
  );
  await mockApi(page, 'GET', '/api/v1/product-categories', []);

  await page.goto('/products');

  // 데이터 행 10개 + 헤더 1개 = 11개
  await expect(page.getByRole('row')).toHaveCount(11);

  // 다음/이전 버튼 렌더링
  await expect(page.getByRole('button', { name: /다음/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /이전/ })).toBeVisible();
});
```

### 셀렉터 우선순위

```typescript
// 우선순위 순서대로 사용
page.getByRole('button', { name: '저장' })     // 1순위: ARIA role
page.getByLabel('이메일')                       // 2순위: label
page.getByPlaceholder('검색...')               // 3순위: placeholder
page.getByText('저장')                          // 4순위: text (다른 방법 불가할 때)
page.getByTestId('submit-btn')                  // 마지막: data-testid (필요한 곳만)
```

### 이미 로그인된 상태 리다이렉트 검증

```typescript
test('로그인된 상태에서 /login 접근 시 홈으로 리다이렉트된다', async ({
  authenticatedPage: page,  // 이미 로그인 완료 상태
}) => {
  await page.goto('/login');
  await expect(page).toHaveURL('/');
});
```

---

## 11. 새 도메인 추가 체크리스트

새로운 도메인(예: 주문, 결제, 리뷰 등)의 페이지를 추가할 때:

```
[ ] 1. e2e/factories/{domain}.factory.ts 생성
       - 도메인 응답 타입 import (src/types/{domain}.ts)
       - create{Entity}(), create{Entity}Detail(), create{Entities}(count) 함수 작성
       - 모든 함수에 overrides?: Partial<T> 파라미터 포함

[ ] 2. e2e/fixtures/{domain}.fixture.ts 생성
       - setup{Domain}Mocks(page): Page 함수 — 목록 페이지 API 모킹
       - setup{Domain}DetailMocks(page, id): 상세 페이지 API 모킹
       - mockApi, createPageResponse는 api-mock.ts에서 import

[ ] 3. e2e/pages/{domain}/{page-name}.spec.ts 작성
       - test/expect는 auth.fixture.ts에서 import
       - authenticatedPage fixture 사용 (인증 필요 페이지)
       - 6가지 검증 항목 커버:
         [ ] 기본 렌더링 (행 개수, 셀 데이터)
         [ ] API 파라미터 검증 (검색, 필터)
         [ ] 빈 상태 처리
         [ ] 에러 처리 (500)
         [ ] 네비게이션 (행 클릭 → 상세)
         [ ] 유효성 검사 (폼 페이지)

[ ] 4. e2e/flows/{domain}-crud.spec.ts 작성 (선택)
       - 목록 → 상세 → 생성 → 결과 연속 시나리오

[ ] 5. 타입 체크 확인
       npx tsc -p tsconfig.e2e.json --noEmit

[ ] 6. E2E 테스트 실행
       pnpm test:e2e --grep "{도메인 이름}"
```

---

## 빠른 참조 — import 패턴

```typescript
// 모든 테스트 파일 상단 import 패턴
import { create{Entity}, create{Entities} } from '../../factories/{domain}.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';  // ★ @playwright/test 대신 이것을 사용
import { setup{Domain}Mocks } from '../../fixtures/{domain}.fixture';
```

> **주의**: `@playwright/test`에서 직접 `test`, `expect`를 import하면 `authenticatedPage` 같은 커스텀 fixture를 사용할 수 없다. 반드시 `auth.fixture.ts`에서 re-export한 `test`, `expect`를 사용한다.
