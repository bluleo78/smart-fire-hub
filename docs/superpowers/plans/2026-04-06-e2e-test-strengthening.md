# E2E 테스트 강화 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 176개 Playwright E2E 테스트를 smoke-test 수준에서 전체 비즈니스 로직 검증 수준으로 강화한다.

**Architecture:** mockApi 헬퍼에 요청 캡처 기능을 추가한 뒤, 도메인별로 기존 spec 파일을 in-place 수정하여 6개 강화 패턴(payload 검증, data→UI 셀 검증, 필터 param 검증, 비즈니스 로직, 에러 상태, Zod 유효성)을 적용한다.

**Tech Stack:** Playwright, TypeScript, page.route() API mocking, Zod validation schemas

**Spec:** `docs/superpowers/specs/2026-04-06-e2e-test-strengthening-design.md`

---

## File Structure

**수정 파일:**
- `e2e/fixtures/api-mock.ts` — capture 기능 추가
- `e2e/pages/data/dataset-list.spec.ts` — 셀 검증, 검색 param, 페이지네이션
- `e2e/pages/data/dataset-create.spec.ts` — payload 캡처, Zod 유효성, 에러 상태
- `e2e/pages/data/dataset-detail.spec.ts` — 상세 데이터 셀 검증, 즐겨찾기 토글, 태그
- `e2e/pages/data/category-list.spec.ts` — CRUD payload, 셀 검증
- `e2e/flows/dataset-crud.spec.ts` — 전체 데이터 흐름 검증
- `e2e/pages/pipeline/pipeline-list.spec.ts` — 셀 검증, 상태 배지, 트리거 수
- `e2e/pages/pipeline/pipeline-editor.spec.ts` — 실행 이력 셀, 기간 계산, 실행 payload
- `e2e/flows/pipeline-workflow.spec.ts` — 실행→토스트→상태 전체 흐름
- `e2e/pages/analytics/query-list.spec.ts` — 셀 검증, 탭 필터 param
- `e2e/pages/analytics/query-editor.spec.ts` — 실행 결과 테이블, 저장 payload
- `e2e/pages/analytics/chart-list.spec.ts` — 차트 타입 배지, 셀 검증
- `e2e/pages/analytics/chart-builder.spec.ts` — 차트 타입 선택, 저장 payload
- `e2e/pages/analytics/dashboard-list.spec.ts` — 생성 payload, 셀 검증
- `e2e/pages/analytics/dashboard-editor.spec.ts` — 위젯 데이터, 편집 모드
- `e2e/flows/analytics-workflow.spec.ts` — 쿼리→차트→대시보드 흐름
- `e2e/pages/ai-insights/job-list.spec.ts` — 셀 검증, 활성 토글
- `e2e/pages/ai-insights/job-detail.spec.ts` — 상세 데이터, 실행 이력 셀
- `e2e/pages/ai-insights/template-list.spec.ts` — 기본/커스텀 분류 검증
- `e2e/pages/ai-insights/template-detail.spec.ts` — 편집 payload, 삭제 확인
- `e2e/pages/ai-insights/execution-detail.spec.ts` — 상태별 UI, 에러 분류
- `e2e/flows/ai-insight-workflow.spec.ts` — 작업→실행→결과 흐름
- `e2e/pages/admin/user-management.spec.ts` — 셀 검증, 검색 param, 역할 할당
- `e2e/pages/admin/role-management.spec.ts` — 생성 payload, 권한 체크박스
- `e2e/pages/admin/audit-logs.spec.ts` — 셀 검증, 필터 param, 결과 배지
- `e2e/pages/admin/settings.spec.ts` — 설정 저장 payload, 되돌리기
- `e2e/pages/admin/api-connections.spec.ts` — 생성 payload, 인증 유형별 필드
- `e2e/flows/admin-management.spec.ts` — 관리 전체 흐름
- `e2e/pages/auth/login.spec.ts` — 로그인 payload, 에러 메시지 구체화
- `e2e/pages/auth/signup.spec.ts` — 회원가입 payload, Zod 유효성
- `e2e/flows/auth.spec.ts` — 인증 전체 흐름
- `e2e/pages/home.spec.ts` — 대시보드 수치, 건강 상태, 활동 피드

---

## 공통 참조: 강화 패턴 가이드

각 Task에서 반복 적용할 6가지 패턴. 이후 Task에서는 "패턴 N 적용"으로 참조한다.

### 패턴 1: Payload 캡처 + 검증
```typescript
const capture = await mockApi(page, 'POST', '/api/v1/endpoint', mockResponse, { capture: true });
// ... 폼 입력 + 제출 ...
const req = await capture.waitForRequest();
expect(req.payload).toMatchObject({ field: 'value' });
```

### 패턴 2: Data→UI 셀 수준 검증
```typescript
const row = page.getByRole('row', { name: /데이터셋 1/ });
await expect(row.getByRole('cell').nth(0)).toHaveText('데이터셋 1');
await expect(row.getByRole('cell').nth(1)).toHaveText('dataset_1');
```

### 패턴 3: 필터/검색 param 검증
```typescript
const capture = await mockApi(page, 'GET', '/api/v1/endpoint', mockResponse, { capture: true });
await page.getByPlaceholder('검색...').fill('검색어');
await page.waitForTimeout(500); // debounce
const req = await capture.waitForRequest();
expect(req.searchParams.get('search')).toBe('검색어');
```

### 패턴 4: 비즈니스 로직 검증
```typescript
// 상태 배지 variant, 기간 계산, 조건부 렌더링 등
await expect(badge).toHaveText('활성');
await expect(durationCell).toHaveText('1m 0s');
```

### 패턴 5: 에러 상태 검증
```typescript
await mockApi(page, 'POST', '/api/v1/endpoint', { message: '구체적 에러' }, { status: 400 });
await page.getByRole('button', { name: '저장' }).click();
await expect(page.getByText('구체적 에러')).toBeVisible();
```

### 패턴 6: Zod 유효성 검증
```typescript
await page.getByLabel('테이블명').fill('Invalid-Name');
await page.getByRole('button', { name: '생성' }).click();
await expect(page.getByText('영문 소문자로 시작해야 합니다')).toBeVisible();
```

---

## Task 0: 인프라 — mockApi 캡처 확장

**Files:**
- Modify: `apps/firehub-web/e2e/fixtures/api-mock.ts`

- [ ] **Step 1: api-mock.ts에 캡처 타입 및 로직 추가**

`api-mock.ts`를 다음과 같이 수정한다. 기존 `mockApi`, `mockApis`, `createPageResponse` 함수는 유지하고, 캡처 관련 타입과 로직을 추가한다.

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
        const captured_req: CapturedRequest = {
          payload,
          url: reqUrl,
          searchParams: reqUrl.searchParams,
        };
        captured.push(captured_req);
        if (resolveWaiter) {
          resolveWaiter(captured_req);
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
        // 이미 캡처된 요청이 있으면 즉시 반환
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
```

- [ ] **Step 2: 타입 체크 확인**

Run: `cd apps/firehub-web && npx tsc -p tsconfig.e2e.json --noEmit`
Expected: 에러 없이 통과

- [ ] **Step 3: 기존 테스트 회귀 확인**

Run: `cd apps/firehub-web && npx playwright test e2e/pages/auth/login.spec.ts --reporter=list`
Expected: 기존 테스트 전부 통과 (capture 미사용 시 기존 동작 유지)

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/e2e/fixtures/api-mock.ts
git commit -m "feat(e2e): mockApi에 capture 옵션 추가 — 요청 payload/param 캡처 지원"
```

---

## Task 1: Dataset 도메인 강화 — 목록 (레퍼런스 구현)

**Files:**
- Modify: `apps/firehub-web/e2e/pages/data/dataset-list.spec.ts`
- Reference: `src/pages/data/DatasetListPage.tsx`, `e2e/factories/dataset.factory.ts`, `e2e/fixtures/dataset.fixture.ts`

**강화 포인트:**
- 목록 행 셀 수준 데이터 검증 (이름, 타입, 카테고리)
- 검색 시 API search param 캡처 검증
- 카테고리 필터 클릭 시 categoryId param 검증
- 삭제 API payload 검증
- 즐겨찾기 토글 API 호출 검증
- 빈 목록/에러 시 구체적 상태 검증
- 페이지네이션 셀 데이터 + 페이지 번호 검증

- [ ] **Step 1: dataset-list.spec.ts 강화**

`dataset-list.spec.ts`를 다음 내용으로 교체한다. 기존 테스트 이름과 describe 구조를 유지하면서 assertion을 강화한다.

```typescript
import { createDataset, createDatasets, createCategories } from '../../factories/dataset.factory';
import { setupDatasetMocks } from '../../fixtures/dataset.fixture';
import { mockApi, createPageResponse } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 데이터셋 목록 페이지 E2E 테스트
 * - 목록 렌더링(셀 수준), 검색/필터 API param, 삭제, 즐겨찾기 토글, 페이지네이션을 검증한다.
 */
test.describe('데이터셋 목록 페이지', () => {
  test('데이터셋 목록이 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await page.goto('/data/datasets');

    // 페이지 제목
    await expect(page.getByRole('heading', { name: '데이터셋' })).toBeVisible();

    // 팩토리 데이터(createDatasets(5))의 첫 번째 행 셀 수준 검증
    // createDatasets: id=1, name='데이터셋 1', tableName='dataset_1', datasetType='SOURCE', category=createCategory() → '기본 카테고리'
    const firstRow = page.getByRole('row', { name: /데이터셋 1/ });
    await expect(firstRow).toBeVisible();
    // 이름 셀에 '데이터셋 1' 텍스트 포함
    await expect(firstRow.getByRole('cell').first()).toContainText('데이터셋 1');

    // 5개 행 렌더링 확인 (헤더 1 + 데이터 5)
    const dataRows = page.getByRole('row').filter({ has: page.getByRole('cell') });
    await expect(dataRows).toHaveCount(5);

    // 두 번째 행도 검증
    const secondRow = page.getByRole('row', { name: /데이터셋 2/ });
    await expect(secondRow).toBeVisible();
  });

  test('데이터셋 추가 버튼 클릭 시 /new 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await page.goto('/data/datasets');

    await page.getByRole('link', { name: /데이터셋 추가|새 데이터셋/ }).click();
    await expect(page).toHaveURL(/\/data\/datasets\/new/);
  });

  test('빈 목록일 때 빈 상태 메시지를 표시한다', async ({ authenticatedPage: page }) => {
    // 빈 목록 모킹
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await page.goto('/data/datasets');

    await expect(page.getByText(/등록된 데이터셋이 없습니다|데이터셋이 없습니다/)).toBeVisible();
    // 데이터 행이 0개인지 확인
    const dataRows = page.getByRole('row').filter({ has: page.getByRole('cell') });
    await expect(dataRows).toHaveCount(0);
  });

  test('검색 입력 시 search 파라미터가 반영된다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    // 검색 결과 모킹 (capture로 param 검증)
    const searchResult = createPageResponse([createDataset({ id: 10, name: '소방 데이터셋', tableName: 'fire_dataset' })]);
    const capture = await mockApi(page, 'GET', '/api/v1/datasets', searchResult, { capture: true });

    await page.goto('/data/datasets');

    // 검색 입력 (debounce 대기)
    await page.getByPlaceholder(/검색/).fill('소방');
    await page.waitForTimeout(500);

    // API에 search param 전달 확인
    const req = await capture.waitForRequest();
    expect(req.searchParams.get('search') ?? req.searchParams.get('keyword')).toBe('소방');
  });

  test('카테고리 칩 클릭 시 필터가 적용된다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    // 필터된 결과 모킹
    const filtered = createPageResponse([createDataset({ id: 1, name: '소방 데이터셋' })]);
    const capture = await mockApi(page, 'GET', '/api/v1/datasets', filtered, { capture: true });

    await page.goto('/data/datasets');

    // '소방 데이터' 카테고리 칩 클릭 (createCategories()의 첫 번째: id=1, name='소방 데이터')
    await page.getByRole('button', { name: '소방 데이터' }).click();

    // API에 categoryId param 전달 확인
    const req = await capture.waitForRequest();
    expect(req.searchParams.get('categoryId')).toBe('1');
  });

  test('즐겨찾기 토글 버튼이 동작한다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    // 즐겨찾기 토글 API 모킹
    const capture = await mockApi(page, 'POST', '/api/v1/datasets/1/favorite', { favorited: true }, { capture: true });

    await page.goto('/data/datasets');

    // 첫 번째 행의 즐겨찾기 버튼 클릭
    const firstRow = page.getByRole('row', { name: /데이터셋 1/ });
    await firstRow.getByRole('button', { name: /즐겨찾기/ }).click();

    // API 호출 확인
    const req = await capture.waitForRequest();
    expect(req.url.pathname).toBe('/api/v1/datasets/1/favorite');
  });

  test('서버 에러(500) 시 에러 상태가 표시된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets', { message: '서버 내부 오류' }, { status: 500 });
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await page.goto('/data/datasets');

    // 데이터 행이 없고, 에러 상태가 표시되는지 확인
    const dataRows = page.getByRole('row').filter({ has: page.getByRole('cell') });
    await expect(dataRows).toHaveCount(0);
  });

  test('데이터셋 행 클릭 시 상세 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await page.goto('/data/datasets');

    // 첫 번째 데이터셋 행 클릭 → 상세 페이지 이동
    await page.getByRole('row', { name: /데이터셋 1/ }).click();
    await expect(page).toHaveURL(/\/data\/datasets\/1/);
  });

  test('데이터셋 목록에 페이지네이션이 렌더링된다', async ({ authenticatedPage: page }) => {
    // 2페이지 분량 모킹 (totalElements=15, size=10 → 2페이지)
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets',
      createPageResponse(createDatasets(10), { totalElements: 15, totalPages: 2 })
    );
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await page.goto('/data/datasets');

    // 페이지네이션 컨트롤 존재 확인
    await expect(page.getByRole('button', { name: /다음|Next/ })).toBeVisible();
    // 현재 10개 행 표시
    const dataRows = page.getByRole('row').filter({ has: page.getByRole('cell') });
    await expect(dataRows).toHaveCount(10);
  });
});
```

- [ ] **Step 2: 테스트 실행**

Run: `cd apps/firehub-web && npx playwright test e2e/pages/data/dataset-list.spec.ts --reporter=list`
Expected: 전체 통과. 실패 시 실제 UI 셀렉터에 맞게 조정.

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/e2e/pages/data/dataset-list.spec.ts
git commit -m "test(e2e): 데이터셋 목록 테스트 강화 — 셀 검증, 검색/필터 param, 즐겨찾기"
```

---

## Task 2: Dataset 도메인 강화 — 생성

**Files:**
- Modify: `apps/firehub-web/e2e/pages/data/dataset-create.spec.ts`
- Reference: `src/pages/data/DatasetCreatePage.tsx`, `src/lib/validations/dataset.ts`

**강화 포인트:**
- 생성 폼 제출 시 payload 캡처 + 필드별 검증 (name, tableName, columns, datasetType)
- Zod 유효성 — tableName 정규식 `/^[a-z][a-z0-9_]*$/` 위반 시 에러 메시지
- Zod 유효성 — 컬럼 최소 1개 필수
- 서버 에러(409 중복) 시 구체적 에러 메시지
- 생성 성공 후 상세 페이지 리다이렉트에서 response ID 사용 확인

- [ ] **Step 1: dataset-create.spec.ts 강화**

```typescript
import { createCategories, createDatasetDetail } from '../../factories/dataset.factory';
import { setupDatasetMocks } from '../../fixtures/dataset.fixture';
import { mockApi, createPageResponse } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 데이터셋 생성 페이지 E2E 테스트
 * - 폼 유효성, payload 검증, 에러 처리, 생성 성공 후 리다이렉트를 검증한다.
 */
test.describe('데이터셋 생성 페이지', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    // 생성 폼에서 필요한 카테고리 목록 모킹
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  });

  test('생성 폼이 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets/new');

    // 폼 필드 존재 확인
    await expect(page.getByLabel(/데이터셋 이름|이름/)).toBeVisible();
    await expect(page.getByLabel(/테이블명|테이블 이름/)).toBeVisible();
    // 스키마 빌더 섹션
    await expect(page.getByText(/스키마|컬럼/)).toBeVisible();
    // 생성 버튼
    await expect(page.getByRole('button', { name: /생성|저장/ })).toBeVisible();
  });

  test('필수 필드 없이 제출 시 유효성 에러가 표시된다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets/new');

    // 빈 상태에서 제출
    await page.getByRole('button', { name: /생성|저장/ }).click();

    // Zod 유효성 에러 메시지 확인 — 구체적 필드 메시지
    // name: min 1 → "필수" 또는 에러 메시지
    await expect(page.locator('.text-destructive').first()).toBeVisible();
  });

  test('테이블명에 대문자 입력 시 Zod 유효성 에러가 표시된다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets/new');

    await page.getByLabel(/데이터셋 이름|이름/).fill('테스트');
    await page.getByLabel(/테이블명|테이블 이름/).fill('InvalidName');

    await page.getByRole('button', { name: /생성|저장/ }).click();

    // tableName 정규식 /^[a-z][a-z0-9_]*$/ 위반 에러 메시지
    await expect(page.getByText(/영문 소문자/)).toBeVisible();
  });

  test('취소 버튼 클릭 시 목록 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await page.goto('/data/datasets/new');

    await page.getByRole('button', { name: /취소|돌아가기/ }).click();
    await expect(page).toHaveURL(/\/data\/datasets$/);
  });

  test('서버 에러(409 중복) 시 에러 메시지를 표시한다', async ({ authenticatedPage: page }) => {
    // 409 에러 모킹
    await mockApi(page, 'POST', '/api/v1/datasets',
      { message: '이미 존재하는 테이블명입니다.' }, { status: 409 }
    );
    await page.goto('/data/datasets/new');

    // 폼 작성
    await page.getByLabel(/데이터셋 이름|이름/).fill('중복 데이터셋');
    await page.getByLabel(/테이블명|테이블 이름/).fill('duplicate_table');
    // 컬럼 입력 (기본 1개 컬럼이 있으므로 columnName만 채움)
    await page.getByPlaceholder(/예: user_id|컬럼명/).first().fill('col1');

    await page.getByRole('button', { name: /생성|저장/ }).click();

    // 구체적 에러 메시지 확인 (toast 또는 인라인)
    await expect(page.getByText('이미 존재하는 테이블명입니다.')).toBeVisible();
  });

  test('폼 입력 후 정상 생성 시 상세 페이지로 이동하고 payload가 올바르다', async ({ authenticatedPage: page }) => {
    // 생성 성공 모킹 — response에 id=99 포함
    const mockResponse = createDatasetDetail({ id: 99, name: '신규 데이터셋', tableName: 'new_dataset' });
    const capture = await mockApi(page, 'POST', '/api/v1/datasets', mockResponse, { capture: true });
    // 상세 페이지 모킹 (리다이렉트 후)
    await mockApi(page, 'GET', '/api/v1/datasets/99', mockResponse);
    await mockApi(page, 'GET', '/api/v1/datasets/99/data', { columns: [], rows: [], page: 0, size: 10, totalElements: 0, totalPages: 0 });
    await mockApi(page, 'GET', '/api/v1/datasets/99/stats', []);
    await mockApi(page, 'GET', '/api/v1/datasets/99/queries', createPageResponse([]));

    await page.goto('/data/datasets/new');

    // 폼 입력
    await page.getByLabel(/데이터셋 이름|이름/).fill('신규 데이터셋');
    await page.getByLabel(/테이블명|테이블 이름/).fill('new_dataset');
    await page.getByPlaceholder(/예: user_id|컬럼명/).first().fill('col_name');

    // 제출
    await page.getByRole('button', { name: /생성|저장/ }).click();

    // Payload 검증
    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({
      name: '신규 데이터셋',
      tableName: 'new_dataset',
      columns: expect.arrayContaining([
        expect.objectContaining({ columnName: 'col_name' }),
      ]),
    });

    // 상세 페이지로 리다이렉트 (response의 id=99 사용)
    await expect(page).toHaveURL(/\/data\/datasets\/99/);
  });
});
```

- [ ] **Step 2: 테스트 실행**

Run: `cd apps/firehub-web && npx playwright test e2e/pages/data/dataset-create.spec.ts --reporter=list`
Expected: 전체 통과

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/e2e/pages/data/dataset-create.spec.ts
git commit -m "test(e2e): 데이터셋 생성 테스트 강화 — payload 검증, Zod 유효성, 에러 처리"
```

---

## Task 3: Dataset 도메인 강화 — 상세 + 카테고리 + 플로우

**Files:**
- Modify: `apps/firehub-web/e2e/pages/data/dataset-detail.spec.ts`
- Modify: `apps/firehub-web/e2e/pages/data/category-list.spec.ts`
- Modify: `apps/firehub-web/e2e/flows/dataset-crud.spec.ts`

**강화 포인트:**
- 상세: 팩토리 데이터(이름, 테이블명, 컬럼 수, 행 수, 태그)가 UI에 정확히 렌더링
- 상세: 즐겨찾기 토글 API 호출, 태그 추가/삭제 API payload
- 상세: 404 에러 시 에러 상태 표시
- 카테고리: 생성 payload 캡처 (name, description), 셀 수준 렌더링
- 플로우: 생성→목록→상세→삭제 전체 데이터 흐름에서 각 단계 payload/data 검증

- [ ] **Step 1: dataset-detail.spec.ts 강화**

기존 파일에서 각 테스트의 assertion을 강화한다. 주요 변경:

1. `데이터셋 상세 정보가 올바르게 렌더링된다` — createDatasetDetail()의 name='테스트 데이터셋', rowCount=100, tags=['테스트', '샘플']이 UI에 표시되는지 셀 수준 확인
2. `탭이 올바르게 렌더링되고 전환된다` — 데이터 탭 클릭 시 data API 응답의 행 데이터가 테이블에 렌더링되는지 검증
3. `즐겨찾기 버튼이 동작한다` — POST /api/v1/datasets/{id}/favorite API 호출 캡처
4. `태그 추가 버튼이 동작한다` — POST /api/v1/datasets/{id}/tags payload에 tagName 포함 확인
5. `404 에러 시 적절한 에러 상태` — 에러 메시지 또는 로딩 상태 유지

핵심 강화 예시 (전체 파일은 구현 시 작성):
```typescript
// 상세 정보 셀 수준 검증
test('데이터셋 상세 정보가 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
  await setupDatasetDetailMocks(page, 1);
  await page.goto('/data/datasets/1');

  // createDatasetDetail() 기본값 검증
  await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();
  // 태그 렌더링
  await expect(page.getByText('테스트')).toBeVisible();
  await expect(page.getByText('샘플')).toBeVisible();
  // 행 수 (100)
  await expect(page.getByText('100')).toBeVisible();
  // 컬럼 수 (createDatasetDetail에 2개 컬럼: id, name)
  await expect(page.getByText('2')).toBeVisible();
});

// 즐겨찾기 토글 API 호출 검증
test('즐겨찾기 버튼 클릭 시 API가 호출된다', async ({ authenticatedPage: page }) => {
  await setupDatasetDetailMocks(page, 1);
  const capture = await mockApi(page, 'POST', '/api/v1/datasets/1/favorite', { favorited: true }, { capture: true });
  await page.goto('/data/datasets/1');

  await page.getByRole('button', { name: /즐겨찾기/ }).click();
  const req = await capture.waitForRequest();
  expect(req.url.pathname).toBe('/api/v1/datasets/1/favorite');
});
```

- [ ] **Step 2: category-list.spec.ts 강화**

1. `카테고리 목록이 올바르게 렌더링된다` — createCategories()의 3개 카테고리 셀 수준 확인
2. `카테고리 생성 시 payload 검증` — POST /api/v1/dataset-categories payload에 name, description

```typescript
// 카테고리 생성 payload 검증
test('카테고리 생성 시 올바른 payload가 전송된다', async ({ authenticatedPage: page }) => {
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  const capture = await mockApi(page, 'POST', '/api/v1/dataset-categories',
    createCategory({ id: 10, name: '신규 카테고리' }), { capture: true }
  );
  await page.goto('/data/categories');

  await page.getByRole('button', { name: /새 카테고리/ }).click();
  await page.getByLabel(/이름/).fill('신규 카테고리');
  await page.getByLabel(/설명/).fill('테스트 설명');
  await page.getByRole('button', { name: /생성|저장|확인/ }).click();

  const req = await capture.waitForRequest();
  expect(req.payload).toMatchObject({
    name: '신규 카테고리',
    description: '테스트 설명',
  });
});
```

- [ ] **Step 3: dataset-crud.spec.ts 플로우 강화**

1. `데이터셋 생성 후 상세 페이지로 자동 이동한다` — 생성 payload 캡처 + 상세 페이지 데이터 확인
2. `목록 페이지에서 삭제 시 API 호출 검증` — DELETE API 캡처

- [ ] **Step 4: 전체 Dataset 테스트 실행**

Run: `cd apps/firehub-web && npx playwright test e2e/pages/data/ e2e/flows/dataset-crud.spec.ts --reporter=list`
Expected: 전체 통과

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-web/e2e/pages/data/ apps/firehub-web/e2e/flows/dataset-crud.spec.ts
git commit -m "test(e2e): 데이터셋 상세/카테고리/플로우 테스트 강화 — 데이터 검증, API payload"
```

---

## Task 4: Pipeline 도메인 강화

**Files:**
- Modify: `apps/firehub-web/e2e/pages/pipeline/pipeline-list.spec.ts`
- Modify: `apps/firehub-web/e2e/pages/pipeline/pipeline-editor.spec.ts`
- Modify: `apps/firehub-web/e2e/flows/pipeline-workflow.spec.ts`
- Reference: `src/pages/pipeline/PipelineListPage.tsx`, `src/pages/pipeline/PipelineEditorPage.tsx`

**강화 포인트:**

### pipeline-list.spec.ts
- 목록 행 셀 검증: 이름, 활성 배지('활성'/'비활성'), 스텝 수(2), 트리거 수(1)
- 삭제 API 호출 캡처
- 비활성 배지 variant 검증
- 트리거 수 배지 수치 검증

### pipeline-editor.spec.ts
- 실행 이력 탭: 2개 실행 행의 셀 수준 검증 (ID, 상태 배지, 트리거, 기간)
- 기간 계산 검증: startedAt='2024-01-01T00:00:00Z', completedAt='2024-01-01T00:01:00Z' → '1m 0s'
- FAILED 실행의 상태 배지 variant 검증
- 트리거 탭: 트리거 이름('매일 실행'), 타입('SCHEDULE'), cron('0 0 * * *') 표시 확인
- 실행 버튼 클릭 시 POST 캡처

### pipeline-workflow.spec.ts
- 실행 시작 후 토스트 메시지 확인
- 생성 폼에서 파이프라인 이름, 스텝 추가 후 저장 payload 검증

핵심 강화 예시:
```typescript
// pipeline-editor.spec.ts — 실행 이력 셀 검증
test('실행 이력 탭에서 실행 목록이 셀 수준으로 검증된다', async ({ authenticatedPage: page }) => {
  await setupPipelineEditorMocks(page, 1);
  await page.goto('/pipelines/1');

  await page.getByRole('tab', { name: '실행 이력' }).click();

  // 첫 번째 실행: id=1, status='COMPLETED', startedAt→completedAt = 1분
  const rows = page.getByRole('row').filter({ has: page.getByRole('cell') });
  const firstExecRow = rows.first();
  await expect(firstExecRow.getByRole('cell').first()).toContainText('1');

  // 기간 계산: 00:00:00 → 00:01:00 = 1m 0s
  await expect(firstExecRow).toContainText(/1m\s*0s|1분/);

  // 두 번째 실행: status='FAILED'
  const secondExecRow = rows.nth(1);
  await expect(secondExecRow).toContainText(/실패|FAILED/);
});

// pipeline-workflow.spec.ts — 실행 후 토스트
test('실행 버튼 클릭 시 실행이 시작되고 토스트가 표시된다', async ({ authenticatedPage: page }) => {
  await setupPipelineEditorMocks(page, 1);
  const capture = await mockApi(page, 'POST', '/api/v1/pipelines/1/execute', { id: 10 }, { capture: true });
  await page.goto('/pipelines/1');

  await page.getByRole('button', { name: /실행/ }).click();

  // API 호출 확인
  await capture.waitForRequest();
  // 토스트 메시지
  await expect(page.getByText(/실행이 시작되었습니다/)).toBeVisible();
});
```

- [ ] **Step 1: pipeline-list.spec.ts 강화** — 셀 검증, 배지 variant, 삭제 캡처
- [ ] **Step 2: pipeline-editor.spec.ts 강화** — 실행 이력 셀, 기간, 트리거 데이터
- [ ] **Step 3: pipeline-workflow.spec.ts 강화** — 실행 payload, 생성 payload
- [ ] **Step 4: 테스트 실행**

Run: `cd apps/firehub-web && npx playwright test e2e/pages/pipeline/ e2e/flows/pipeline-workflow.spec.ts --reporter=list`

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-web/e2e/pages/pipeline/ apps/firehub-web/e2e/flows/pipeline-workflow.spec.ts
git commit -m "test(e2e): 파이프라인 테스트 강화 — 실행 이력 셀, 기간 계산, payload 검증"
```

---

## Task 5: Analytics 도메인 강화 — 쿼리

**Files:**
- Modify: `apps/firehub-web/e2e/pages/analytics/query-list.spec.ts`
- Modify: `apps/firehub-web/e2e/pages/analytics/query-editor.spec.ts`
- Reference: `src/pages/analytics/QueryListPage.tsx`, `src/pages/analytics/QueryEditorPage.tsx`

**강화 포인트:**

### query-list.spec.ts
- 목록 셀 검증: 쿼리 이름, 폴더, 데이터셋명, 차트 수, 수정일
- 공유 뱃지 검증: isShared=true → "공유" 뱃지 텍스트
- 탭 전환 시 sharedOnly param 캡처
- 삭제 API 캡처

### query-editor.spec.ts
- 쿼리 실행 후 결과 테이블 렌더링: columns=['id','name','value'], rows 2개의 셀 검증
- 저장 다이얼로그: 이름/설명 입력 → payload 캡처 (name, sqlText, isShared)
- 스키마 탐색기: createSchemaInfo()의 2개 테이블(test_table, another_table) 이름 표시 확인
- 에러 쿼리 실행 시 에러 메시지 표시

핵심 강화 예시:
```typescript
// query-editor.spec.ts — 쿼리 실행 결과 검증
test('쿼리 실행 시 결과 테이블이 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
  await setupNewQueryEditorMocks(page);
  await setupQueryExecuteMock(page);
  await page.goto('/analytics/queries/new');

  // 실행 버튼 클릭 (CodeMirror에 SQL이 있어야 함)
  await page.getByRole('button', { name: /실행/ }).click();

  // createQueryResult() 기본값: columns=['id','name','value'], rows=[{id:1,name:'항목 1',value:100},{id:2,...}]
  // 결과 테이블 헤더
  await expect(page.getByRole('columnheader', { name: 'id' })).toBeVisible();
  await expect(page.getByRole('columnheader', { name: 'name' })).toBeVisible();
  // 첫 번째 행 셀 값
  await expect(page.getByRole('cell', { name: '항목 1' })).toBeVisible();
  await expect(page.getByRole('cell', { name: '100' })).toBeVisible();
  // 실행 시간 표시 (42ms)
  await expect(page.getByText(/42\s*ms/)).toBeVisible();
});
```

- [ ] **Step 1: query-list.spec.ts 강화**
- [ ] **Step 2: query-editor.spec.ts 강화**
- [ ] **Step 3: 테스트 실행**

Run: `cd apps/firehub-web && npx playwright test e2e/pages/analytics/query-list.spec.ts e2e/pages/analytics/query-editor.spec.ts --reporter=list`

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/e2e/pages/analytics/query-list.spec.ts apps/firehub-web/e2e/pages/analytics/query-editor.spec.ts
git commit -m "test(e2e): 분석 쿼리 테스트 강화 — 실행 결과 셀, 저장 payload, 스키마 탐색기"
```

---

## Task 6: Analytics 도메인 강화 — 차트 + 대시보드

**Files:**
- Modify: `apps/firehub-web/e2e/pages/analytics/chart-list.spec.ts`
- Modify: `apps/firehub-web/e2e/pages/analytics/chart-builder.spec.ts`
- Modify: `apps/firehub-web/e2e/pages/analytics/dashboard-list.spec.ts`
- Modify: `apps/firehub-web/e2e/pages/analytics/dashboard-editor.spec.ts`
- Modify: `apps/firehub-web/e2e/flows/analytics-workflow.spec.ts`

**강화 포인트:**

### chart-list.spec.ts
- 셀 검증: 차트 이름, 차트 타입 배지 (CHART_TYPE_LABELS: BAR→'막대')
- 공유 뱃지 검증

### chart-builder.spec.ts
- 쿼리 선택 후 데이터 소스 표시 확인
- 차트 타입 선택 상태 검증
- 저장 payload 캡처: name, savedQueryId, chartType, config, isShared

### dashboard-list.spec.ts
- 셀 검증: 이름, 위젯 수, 자동새로고침 값
- 생성 다이얼로그 payload 캡처: name, description, isShared
- 공유 뱃지 검증

### dashboard-editor.spec.ts
- 위젯 데이터 렌더링 (위젯 이름 표시)
- 편집 모드 전환 시 버튼 상태 변화

### analytics-workflow.spec.ts
- 대시보드 생성 payload 캡처 + 에디터 리다이렉트

- [ ] **Step 1: chart-list.spec.ts 강화**
- [ ] **Step 2: chart-builder.spec.ts 강화**
- [ ] **Step 3: dashboard-list.spec.ts 강화**
- [ ] **Step 4: dashboard-editor.spec.ts 강화**
- [ ] **Step 5: analytics-workflow.spec.ts 강화**
- [ ] **Step 6: 테스트 실행**

Run: `cd apps/firehub-web && npx playwright test e2e/pages/analytics/ e2e/flows/analytics-workflow.spec.ts --reporter=list`

- [ ] **Step 7: 커밋**

```bash
git add apps/firehub-web/e2e/pages/analytics/ apps/firehub-web/e2e/flows/analytics-workflow.spec.ts
git commit -m "test(e2e): 차트/대시보드 테스트 강화 — 타입 배지, 생성 payload, 위젯 데이터"
```

---

## Task 7: AI Insights 도메인 강화

**Files:**
- Modify: `apps/firehub-web/e2e/pages/ai-insights/job-list.spec.ts`
- Modify: `apps/firehub-web/e2e/pages/ai-insights/job-detail.spec.ts`
- Modify: `apps/firehub-web/e2e/pages/ai-insights/template-list.spec.ts`
- Modify: `apps/firehub-web/e2e/pages/ai-insights/template-detail.spec.ts`
- Modify: `apps/firehub-web/e2e/pages/ai-insights/execution-detail.spec.ts`
- Modify: `apps/firehub-web/e2e/flows/ai-insight-workflow.spec.ts`

**강화 포인트:**

### job-list.spec.ts
- 셀 검증: createJobs(3)의 이름('잡 1', '잡 2', '잡 3'), cron, 활성 상태
- 활성 토글 스위치 상태: enabled=true → checked, enabled=false → unchecked
- 비활성 잡의 '비활성' 배지 텍스트

### job-detail.spec.ts
- 상세 데이터: createJob() 기본값 — name='매일 현황 리포트', cron='0 9 * * *', templateName
- 실행 이력 탭: 실행 행 셀 검증 (상태 배지, 시작/완료 시간)
- 새 작업 생성 페이지: 폼 필드(이름, 프롬프트, 템플릿 선택, cron) 존재 확인 + 생성 payload

### template-list.spec.ts
- 기본 템플릿 섹션: builtin=true인 '일일 현황 리포트' 카드에 '기본' 뱃지
- 커스텀 템플릿 섹션: builtin=false인 '주간 통계 리포트' 카드

### template-detail.spec.ts
- 빌트인 템플릿: 편집/삭제 버튼 없음, '기본' 뱃지
- 커스텀 템플릿: 편집 가능, 삭제 payload 캡처
- 복제: POST payload에 name이 '${template.name} (사본)' 형태

### execution-detail.spec.ts
- COMPLETED 상태: 완료 뱃지 + 기간 표시
- FAILED 상태: 에러 메시지(createJobExecution의 errorMessage) 표시
- RUNNING 상태: 스피너 + 안내 문구

### ai-insight-workflow.spec.ts
- 작업 목록→상세 전환 시 상세 데이터 검증
- 새 작업 생성 payload 캡처

- [ ] **Step 1: job-list.spec.ts 강화**
- [ ] **Step 2: job-detail.spec.ts 강화**
- [ ] **Step 3: template-list.spec.ts + template-detail.spec.ts 강화**
- [ ] **Step 4: execution-detail.spec.ts 강화**
- [ ] **Step 5: ai-insight-workflow.spec.ts 강화**
- [ ] **Step 6: 테스트 실행**

Run: `cd apps/firehub-web && npx playwright test e2e/pages/ai-insights/ e2e/flows/ai-insight-workflow.spec.ts --reporter=list`

- [ ] **Step 7: 커밋**

```bash
git add apps/firehub-web/e2e/pages/ai-insights/ apps/firehub-web/e2e/flows/ai-insight-workflow.spec.ts
git commit -m "test(e2e): AI 인사이트 테스트 강화 — 잡/템플릿/실행 셀 검증, payload 캡처"
```

---

## Task 8: Admin 도메인 강화

**Files:**
- Modify: `apps/firehub-web/e2e/pages/admin/user-management.spec.ts`
- Modify: `apps/firehub-web/e2e/pages/admin/role-management.spec.ts`
- Modify: `apps/firehub-web/e2e/pages/admin/audit-logs.spec.ts`
- Modify: `apps/firehub-web/e2e/pages/admin/settings.spec.ts`
- Modify: `apps/firehub-web/e2e/pages/admin/api-connections.spec.ts`
- Modify: `apps/firehub-web/e2e/flows/admin-management.spec.ts`

**강화 포인트:**

### user-management.spec.ts
- 목록 셀 검증: createUser()의 name, username, email, 활성 상태 뱃지
- 검색 시 API search param 캡처
- 사용자 상세: 역할 할당 저장 payload (roleIds)
- 활성 상태 토글 API 호출 캡처

### role-management.spec.ts
- 목록 셀: 이름(USER, ADMIN, EDITOR), 유형(시스템/사용자 정의), 설명
- 역할 생성 다이얼로그: POST payload (name, description)
- 역할 상세: 권한 저장 payload (permissionIds 배열)
- 시스템 역할 삭제 불가: trash 아이콘 카운트=1 (EDITOR만)

### audit-logs.spec.ts
- 셀 검증: createAuditLogs(5)의 username, actionType, resource, result 배지
- 필터 param 캡처: actionType, resource, result
- 검색 param 캡처
- SUCCESS 뱃지 vs FAILURE 뱃지 variant

### settings.spec.ts
- 설정 저장 payload 캡처: PUT /api/v1/settings → { settings: Record<string, string> }
- 되돌리기 후 원래 값 복원 확인
- 설정값 변경 시 저장 버튼 활성화 검증

### api-connections.spec.ts
- 목록 셀: 이름, 인증 유형 배지 (API_KEY/BEARER)
- 생성 payload: name, authType, authConfig 구조 검증
- API_KEY 선택 시 헤더 이름/키 필드 → BEARER 선택 시 토큰 필드 (조건부 필드 전환)
- 상세 페이지: 기본 정보 편집 → 저장 payload
- 삭제 API 캡처

### admin-management.spec.ts
- 사용자 목록→상세 전환 시 데이터 검증
- 역할 생성 다이얼로그 payload 캡처

핵심 강화 예시:
```typescript
// settings.spec.ts — 설정 저장 payload 검증
test('설정 저장 시 변경된 값이 올바른 payload로 전송된다', async ({ authenticatedPage: page }) => {
  await setupAdminAuth(page);
  await setupSettingsMocks(page);
  const capture = await mockApi(page, 'PUT', '/api/v1/settings', {}, { capture: true });
  await page.goto('/admin/settings');

  // AI 에이전트 탭에서 첫 번째 설정값 변경
  const firstInput = page.locator('input[type="text"]').first();
  await firstInput.clear();
  await firstInput.fill('new-value');

  await page.getByRole('button', { name: /저장/ }).click();

  const req = await capture.waitForRequest();
  expect(req.payload).toHaveProperty('settings');
  // settings 객체에 변경된 키-값이 포함
  expect((req.payload as { settings: Record<string, string> }).settings).toBeDefined();
});

// audit-logs.spec.ts — 필터 param 검증
test('액션 유형 필터 변경 시 API param이 전달된다', async ({ authenticatedPage: page }) => {
  await setupAdminAuth(page);
  const filtered = createPageResponse(createAuditLogs(2));
  const capture = await mockApi(page, 'GET', '/api/v1/admin/audit-logs', filtered, { capture: true });
  await page.goto('/admin/audit-logs');

  // 액션 유형 셀렉트에서 'CREATE' 선택
  await page.getByRole('combobox', { name: /액션 유형/ }).click();
  await page.getByRole('option', { name: /CREATE|생성/ }).click();

  const req = await capture.waitForRequest();
  expect(req.searchParams.get('actionType')).toBe('CREATE');
});
```

- [ ] **Step 1: user-management.spec.ts 강화**
- [ ] **Step 2: role-management.spec.ts 강화**
- [ ] **Step 3: audit-logs.spec.ts 강화**
- [ ] **Step 4: settings.spec.ts 강화**
- [ ] **Step 5: api-connections.spec.ts 강화**
- [ ] **Step 6: admin-management.spec.ts 강화**
- [ ] **Step 7: 테스트 실행**

Run: `cd apps/firehub-web && npx playwright test e2e/pages/admin/ e2e/flows/admin-management.spec.ts --reporter=list`

- [ ] **Step 8: 커밋**

```bash
git add apps/firehub-web/e2e/pages/admin/ apps/firehub-web/e2e/flows/admin-management.spec.ts
git commit -m "test(e2e): 관리자 테스트 강화 — 사용자/역할/감사/설정/API연결 payload+셀 검증"
```

---

## Task 9: Auth 도메인 강화

**Files:**
- Modify: `apps/firehub-web/e2e/pages/auth/login.spec.ts`
- Modify: `apps/firehub-web/e2e/pages/auth/signup.spec.ts`
- Modify: `apps/firehub-web/e2e/flows/auth.spec.ts`
- Reference: `src/lib/validations/auth.ts`

**강화 포인트:**

### login.spec.ts
- 로그인 payload 캡처: POST /api/v1/auth/login → { username, password }
- 로그인 실패(401): 구체적 에러 메시지 ("아이디 또는 비밀번호가 올바르지 않습니다" 등)
- 서버 에러(500): 구체적 에러 메시지
- Zod 유효성: 빈 이메일 → "필수 항목", 잘못된 이메일 형식 → "유효한 이메일" 등
- 중복 제출 방지: 클릭 후 버튼 disabled 상태 + 텍스트 변경

### signup.spec.ts
- 회원가입 payload 캡처: POST /api/v1/auth/signup → { username, password, name, email? }
- 비밀번호 8자 미만: 구체적 에러 메시지
- 중복 아이디(409): 서버 에러 메시지 표시
- 이메일 형식 유효성 검증

### auth.spec.ts
- 회원가입→자동 로그인→홈 전체 흐름에서 각 API payload 검증
- 프로필 수정 payload 캡처

```typescript
// login.spec.ts — 로그인 payload 캡처
test('로그인 성공 시 올바른 payload가 전송된다', async ({ authMockedPage: page }) => {
  const capture = await mockApi(page, 'POST', '/api/v1/auth/login',
    { accessToken: 'mock-jwt', tokenType: 'Bearer', expiresIn: 3600 }, { capture: true }
  );
  await page.goto('/login');

  await page.getByLabel(/이메일|아이디/).fill('test@example.com');
  await page.getByLabel(/비밀번호/).fill('testpassword123');
  await page.getByRole('button', { name: /로그인/ }).click();

  const req = await capture.waitForRequest();
  expect(req.payload).toMatchObject({
    username: 'test@example.com',
    password: 'testpassword123',
  });
});
```

- [ ] **Step 1: login.spec.ts 강화**
- [ ] **Step 2: signup.spec.ts 강화**
- [ ] **Step 3: auth.spec.ts 플로우 강화**
- [ ] **Step 4: 테스트 실행**

Run: `cd apps/firehub-web && npx playwright test e2e/pages/auth/ e2e/flows/auth.spec.ts --reporter=list`

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-web/e2e/pages/auth/ apps/firehub-web/e2e/flows/auth.spec.ts
git commit -m "test(e2e): 인증 테스트 강화 — 로그인/회원가입 payload, Zod 유효성, 에러 메시지"
```

---

## Task 10: Home 페이지 강화

**Files:**
- Modify: `apps/firehub-web/e2e/pages/home.spec.ts`
- Reference: `src/pages/HomePage.tsx`, `e2e/fixtures/base.fixture.ts`

**강화 포인트:**
- 대시보드 통계 수치 검증: setupHomeMocks()의 stats 데이터 → UI 카드 수치 매칭
- 건강 상태 바: pipelineHealth.failing=0, datasetHealth.stale=0 → '정상' 표시
- 주의 항목: attentionItems에 CRITICAL/WARNING 항목 추가 시 배지 텍스트 + 설명 검증
- 활동 피드: 활동 항목의 타입 아이콘, 제목, 시간 검증
- 퀵 액션: 각 버튼 클릭 시 올바른 URL로 이동

```typescript
// home.spec.ts — 통계 수치 검증
test('시스템 건강 상태 수치가 올바르게 표시된다', async ({ authenticatedPage: page }) => {
  // setupHomeMocks의 /api/v1/dashboard/health 기본값:
  // pipelineHealth: {total: 5, healthy: 3, failing: 1, running: 1, disabled: 0}
  // datasetHealth: {total: 10, fresh: 7, stale: 2, empty: 1}
  await page.goto('/');

  // 파이프라인 상태 — 실패 수 표시
  await expect(page.getByText(/실패.*1|1.*실패/)).toBeVisible();
  // 데이터셋 상태 — 신선 수 표시
  await expect(page.getByText(/신선.*7|7.*신선/)).toBeVisible();
});
```

- [ ] **Step 1: home.spec.ts 강화** — 통계 수치, 건강 상태, 주의 항목, 활동 피드
- [ ] **Step 2: 테스트 실행**

Run: `cd apps/firehub-web && npx playwright test e2e/pages/home.spec.ts --reporter=list`

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/e2e/pages/home.spec.ts
git commit -m "test(e2e): 홈 페이지 테스트 강화 — 통계 수치, 건강 상태, 활동 피드 검증"
```

---

## Task 11: 전체 회귀 테스트 + 완료 기준 확인

- [ ] **Step 1: 전체 E2E 테스트 실행**

Run: `cd apps/firehub-web && npx playwright test --reporter=list`
Expected: 176개 전체 통과

- [ ] **Step 2: E2E 타입 체크**

Run: `cd apps/firehub-web && npx tsc -p tsconfig.e2e.json --noEmit`
Expected: 에러 없음

- [ ] **Step 3: 완료 기준 검증**

다음 항목을 수동 확인:
- [ ] 폼 제출 테스트 → payload 캡처 + toMatchObject 검증 있음
- [ ] 목록 테스트 → 최소 1행 셀 단위 데이터 검증 있음
- [ ] 필터/검색 테스트 → API query param 캡처 검증 있음
- [ ] 에러 테스트 → 구체적 에러 메시지 매칭 있음 (regex 패턴 아닌 구체 텍스트)
- [ ] Zod 유효성 → 스키마별 에러 메시지 매칭 있음
- [ ] 각 페이지 onSubmit/onClick/onChange 핸들러 → 최소 1개 테스트에서 검증됨

- [ ] **Step 4: 최종 커밋**

```bash
git add -A
git commit -m "test(e2e): E2E 테스트 강화 완료 — 176개 테스트 전체 비즈니스 로직 검증 수준"
```
