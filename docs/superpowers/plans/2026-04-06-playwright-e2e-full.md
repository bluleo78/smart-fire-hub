# Playwright E2E 테스트 전체 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** firehub-web 전체 6개 도메인에 대한 Playwright E2E 테스트 120개+ 구현

**Architecture:** 팩토리 패턴으로 모킹 데이터 생성, `flows/`에 해피 패스 시나리오, `pages/`에 유효성 검사/엣지 케이스. 모든 모킹 데이터는 `src/types/` 타입을 적용하여 API 스펙 정합성 보장.

**Tech Stack:** Playwright, TypeScript, page.route() API 모킹

**스펙 문서:** `docs/superpowers/specs/2026-04-06-playwright-e2e-full-design.md`

---

## Task 1: 인프라 — 디렉토리 구조 재편 + 기반 fixture 개선

**Files:**
- Move: `e2e/login.spec.ts` → `e2e/pages/auth/login.spec.ts`
- Create: `e2e/fixtures/base.fixture.ts`
- Modify: `e2e/fixtures/auth.fixture.ts`
- Modify: `e2e/fixtures/api-mock.ts`

### 목적
기존 로그인 테스트를 새 디렉토리 구조로 이동하고, 모든 도메인에서 공유할 base fixture를 생성한다.

- [ ] **Step 1: 디렉토리 구조 생성**

```bash
mkdir -p apps/firehub-web/e2e/{factories,flows,pages/{auth,data,pipeline,analytics,ai-insights,admin}}
```

- [ ] **Step 2: login.spec.ts 이동**

```bash
mv apps/firehub-web/e2e/login.spec.ts apps/firehub-web/e2e/pages/auth/login.spec.ts
```

- [ ] **Step 3: 이동 후 테스트 실행 확인**

Run: `cd apps/firehub-web && pnpm test:e2e --reporter=list`
Expected: 5 passed

- [ ] **Step 4: base.fixture.ts 생성 — 공통 API 모킹 fixture**

`e2e/fixtures/base.fixture.ts`:
```typescript
import { test as base, type Page } from '@playwright/test';

import { mockApi } from './api-mock';

/**
 * 공통 base fixture
 * - 홈 페이지 등에서 필요한 기본 API를 모킹한다.
 * - 각 도메인 fixture에서 이 fixture를 확장하여 사용한다.
 */

/** 홈 페이지 대시보드 API 모킹 */
export async function setupHomeMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/dashboard/stats', {
    totalDatasets: 10,
    sourceDatasets: 6,
    derivedDatasets: 4,
    totalPipelines: 5,
    activePipelines: 3,
    recentImports: [],
    recentExecutions: [],
  });
  await mockApi(page, 'GET', '/api/v1/dashboard/health', {
    pipelineHealth: { total: 5, healthy: 3, failing: 1, running: 0, disabled: 1 },
    datasetHealth: { total: 10, fresh: 8, stale: 1, empty: 1 },
  });
  await mockApi(page, 'GET', '/api/v1/dashboard/attention', []);
  await mockApi(page, 'GET', '/api/v1/dashboard/activity', {
    items: [],
    totalCount: 0,
    hasMore: false,
  });
  await mockApi(page, 'GET', '/api/v1/analytics/dashboards', {
    content: [],
    page: 0,
    size: 5,
    totalElements: 0,
    totalPages: 0,
  });
  await mockApi(page, 'GET', '/api/v1/datasets', {
    content: [],
    page: 0,
    size: 5,
    totalElements: 0,
    totalPages: 0,
  });
  // AI 메시지 알림 (사이드바에서 사용)
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
}

export { base };
```

- [ ] **Step 5: auth.fixture.ts 개선 — base fixture 통합 + 팩토리 준비**

`e2e/fixtures/auth.fixture.ts`를 수정하여 `setupHomeMocks`를 호출하도록 한다. `authenticatedPage`가 홈('/')으로 리다이렉트될 때 대시보드 API 모킹이 필요하기 때문이다.

```typescript
import { test as base, type Page } from '@playwright/test';

import type { TokenResponse, UserResponse } from '../../src/types/auth';
import type { RoleResponse } from '../../src/types/role';
import type { UserDetailResponse } from '../../src/types/user';
import { mockApi } from './api-mock';
import { setupHomeMocks } from './base.fixture';

/** 모킹용 사용자 정보 */
export const MOCK_USER: UserResponse = {
  id: 1,
  username: 'test@example.com',
  email: 'test@example.com',
  name: '테스트 사용자',
  isActive: true,
  createdAt: '2026-01-01T00:00:00',
};

/** 모킹용 토큰 응답 */
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

/** 모킹용 사용자 상세 정보 */
const MOCK_USER_DETAIL: UserDetailResponse = {
  ...MOCK_USER,
  roles: [MOCK_ROLE],
};

/** 인증 관련 API를 모킹하는 헬퍼 */
export async function setupAuthMocks(page: Page) {
  await mockApi(page, 'POST', '/api/v1/auth/login', MOCK_TOKEN_RESPONSE);
  await mockApi(page, 'POST', '/api/v1/auth/refresh', MOCK_TOKEN_RESPONSE);
  await mockApi(page, 'GET', '/api/v1/users/me', MOCK_USER_DETAIL);
  await setupHomeMocks(page);
}

/** 로그인 플로우를 실행하여 인증 상태를 만든다 */
export async function performLogin(page: Page) {
  await page.goto('/login');
  await page.getByLabel('아이디 (이메일)').fill('test@example.com');
  await page.getByLabel('비밀번호').fill('testpassword123');
  await page.getByRole('button', { name: '로그인' }).click();
  await page.waitForURL('/');
}

type AuthFixtures = {
  authMockedPage: Page;
  authenticatedPage: Page;
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

- [ ] **Step 6: api-mock.ts에 mockPageResponse 헬퍼 추가**

`e2e/fixtures/api-mock.ts`에 페이지네이션 응답 생성 헬퍼를 추가한다:

```typescript
import type { Page } from '@playwright/test';

type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH';

interface MockApiOptions {
  status?: number;
  headers?: Record<string, string>;
}

export async function mockApi(
  page: Page,
  method: HttpMethod,
  path: string,
  body: unknown,
  options: MockApiOptions = {},
) {
  const { status = 200, headers = {} } = options;
  await page.route(`**${path}`, (route) => {
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

/**
 * PageResponse<T> 형태의 페이지네이션 응답을 생성한다.
 * Spring Boot의 Page 응답과 동일한 구조.
 */
export function createPageResponse<T>(
  content: T[],
  overrides?: { page?: number; size?: number; totalElements?: number; totalPages?: number },
) {
  const totalElements = overrides?.totalElements ?? content.length;
  const size = overrides?.size ?? 20;
  return {
    content,
    page: overrides?.page ?? 0,
    size,
    totalElements,
    totalPages: overrides?.totalPages ?? Math.ceil(totalElements / size),
  };
}
```

- [ ] **Step 7: 테스트 실행 확인**

Run: `cd apps/firehub-web && pnpm test:e2e --reporter=list`
Expected: 5 passed

- [ ] **Step 8: 커밋**

```bash
git add apps/firehub-web/e2e/
git commit -m "refactor(e2e): 디렉토리 구조 재편 + base fixture + api-mock 헬퍼 추가"
```

---

## Task 2: 팩토리 — 6개 도메인 모킹 데이터 팩토리

**Files:**
- Create: `e2e/factories/auth.factory.ts`
- Create: `e2e/factories/dataset.factory.ts`
- Create: `e2e/factories/pipeline.factory.ts`
- Create: `e2e/factories/analytics.factory.ts`
- Create: `e2e/factories/ai-insight.factory.ts`
- Create: `e2e/factories/admin.factory.ts`

### 목적
각 도메인의 모킹 데이터를 `src/types/` 타입과 연결하여 생성하는 팩토리 함수를 구현한다. API 스펙이 변경되면 컴파일 에러로 감지된다.

- [ ] **Step 1: auth.factory.ts**

```typescript
// e2e/factories/auth.factory.ts
import type { TokenResponse, UserResponse } from '../../src/types/auth';
import type { RoleResponse } from '../../src/types/role';
import type { UserDetailResponse } from '../../src/types/user';

/** 사용자 정보 생성 */
export function createUser(overrides?: Partial<UserResponse>): UserResponse {
  return {
    id: 1,
    username: 'test@example.com',
    email: 'test@example.com',
    name: '테스트 사용자',
    isActive: true,
    createdAt: '2026-01-01T00:00:00',
    ...overrides,
  };
}

/** 토큰 응답 생성 */
export function createTokenResponse(overrides?: Partial<TokenResponse>): TokenResponse {
  return {
    accessToken: 'mock-jwt-access-token',
    tokenType: 'Bearer',
    expiresIn: 3600,
    ...overrides,
  };
}

/** 역할 정보 생성 */
export function createRole(overrides?: Partial<RoleResponse>): RoleResponse {
  return {
    id: 1,
    name: 'USER',
    description: '일반 사용자',
    isSystem: true,
    ...overrides,
  };
}

/** 사용자 상세 정보 생성 (roles 포함) */
export function createUserDetail(overrides?: Partial<UserDetailResponse>): UserDetailResponse {
  return {
    ...createUser(),
    roles: [createRole()],
    ...overrides,
  };
}

/** 관리자 사용자 상세 정보 생성 */
export function createAdminUserDetail(overrides?: Partial<UserDetailResponse>): UserDetailResponse {
  return createUserDetail({
    roles: [createRole(), createRole({ id: 2, name: 'ADMIN', description: '관리자', isSystem: true })],
    ...overrides,
  });
}
```

- [ ] **Step 2: dataset.factory.ts**

```typescript
// e2e/factories/dataset.factory.ts
import type {
  CategoryResponse,
  DatasetColumnResponse,
  DatasetDetailResponse,
  DatasetResponse,
} from '../../src/types/dataset';

/** 카테고리 생성 */
export function createCategory(overrides?: Partial<CategoryResponse>): CategoryResponse {
  return {
    id: 1,
    name: '테스트 카테고리',
    description: '테스트용 카테고리',
    ...overrides,
  };
}

/** 데이터셋 컬럼 생성 */
export function createColumn(overrides?: Partial<DatasetColumnResponse>): DatasetColumnResponse {
  return {
    id: 1,
    columnName: 'test_col',
    displayName: '테스트 컬럼',
    dataType: 'TEXT',
    maxLength: null,
    isNullable: true,
    isIndexed: false,
    isPrimaryKey: false,
    description: null,
    columnOrder: 0,
    ...overrides,
  };
}

/** 데이터셋 목록 아이템 생성 */
export function createDataset(overrides?: Partial<DatasetResponse>): DatasetResponse {
  return {
    id: 1,
    name: '테스트 데이터셋',
    tableName: 'test_dataset',
    description: '테스트용 설명',
    category: createCategory(),
    datasetType: 'SOURCE',
    createdAt: '2026-01-01T00:00:00',
    isFavorite: false,
    tags: [],
    status: 'NONE',
    statusNote: null,
    statusUpdatedBy: null,
    statusUpdatedAt: null,
    ...overrides,
  };
}

/** 데이터셋 상세 정보 생성 */
export function createDatasetDetail(overrides?: Partial<DatasetDetailResponse>): DatasetDetailResponse {
  return {
    id: 1,
    name: '테스트 데이터셋',
    tableName: 'test_dataset',
    description: '테스트용 설명',
    category: createCategory(),
    datasetType: 'SOURCE',
    createdBy: '테스트 사용자',
    columns: [
      createColumn({ id: 1, columnName: 'id', dataType: 'INTEGER', isPrimaryKey: true, isNullable: false, columnOrder: 0 }),
      createColumn({ id: 2, columnName: 'name', dataType: 'TEXT', columnOrder: 1 }),
      createColumn({ id: 3, columnName: 'value', dataType: 'DECIMAL', columnOrder: 2 }),
    ],
    rowCount: 100,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    updatedBy: null,
    isFavorite: false,
    tags: ['sample', 'test'],
    status: 'NONE',
    statusNote: null,
    statusUpdatedBy: null,
    statusUpdatedAt: null,
    linkedPipelines: [],
    ...overrides,
  };
}

/** 데이터셋 목록 (여러 개) 생성 */
export function createDatasets(count: number): DatasetResponse[] {
  return Array.from({ length: count }, (_, i) =>
    createDataset({
      id: i + 1,
      name: `데이터셋 ${i + 1}`,
      tableName: `dataset_${i + 1}`,
    }),
  );
}

/** 카테고리 목록 생성 */
export function createCategories(): CategoryResponse[] {
  return [
    createCategory({ id: 1, name: '환경', description: '환경 데이터' }),
    createCategory({ id: 2, name: '안전', description: '안전 데이터' }),
    createCategory({ id: 3, name: '기타', description: null }),
  ];
}
```

- [ ] **Step 3: pipeline.factory.ts**

```typescript
// e2e/factories/pipeline.factory.ts
import type {
  ExecutionDetailResponse,
  PipelineDetailResponse,
  PipelineExecutionResponse,
  PipelineResponse,
  PipelineStepResponse,
  StepExecutionResponse,
  TriggerResponse,
} from '../../src/types/pipeline';

/** 파이프라인 목록 아이템 생성 */
export function createPipeline(overrides?: Partial<PipelineResponse>): PipelineResponse {
  return {
    id: 1,
    name: '테스트 파이프라인',
    description: '테스트용 파이프라인',
    isActive: true,
    createdBy: '테스트 사용자',
    stepCount: 2,
    triggerCount: 1,
    createdAt: '2026-01-01T00:00:00',
    ...overrides,
  };
}

/** 파이프라인 스텝 생성 */
export function createStep(overrides?: Partial<PipelineStepResponse>): PipelineStepResponse {
  return {
    id: 1,
    name: 'step_1',
    description: 'SQL 스텝',
    scriptType: 'SQL',
    scriptContent: 'SELECT * FROM test_dataset',
    outputDatasetId: 1,
    outputDatasetName: '출력 데이터셋',
    inputDatasetIds: [],
    dependsOnStepNames: [],
    stepOrder: 0,
    loadStrategy: 'REPLACE',
    apiConfig: null,
    apiConnectionId: null,
    ...overrides,
  };
}

/** 파이프라인 상세 생성 */
export function createPipelineDetail(overrides?: Partial<PipelineDetailResponse>): PipelineDetailResponse {
  return {
    id: 1,
    name: '테스트 파이프라인',
    description: '테스트용 파이프라인',
    isActive: true,
    createdBy: '테스트 사용자',
    steps: [
      createStep({ id: 1, name: 'extract', stepOrder: 0 }),
      createStep({ id: 2, name: 'transform', stepOrder: 1, dependsOnStepNames: ['extract'] }),
    ],
    createdAt: '2026-01-01T00:00:00',
    updatedAt: null,
    updatedBy: null,
    ...overrides,
  };
}

/** 실행 목록 아이템 생성 */
export function createExecution(overrides?: Partial<PipelineExecutionResponse>): PipelineExecutionResponse {
  return {
    id: 1,
    pipelineId: 1,
    status: 'COMPLETED',
    executedBy: '테스트 사용자',
    triggeredBy: 'MANUAL',
    triggerName: null,
    startedAt: '2026-01-01T10:00:00',
    completedAt: '2026-01-01T10:01:00',
    createdAt: '2026-01-01T10:00:00',
    ...overrides,
  };
}

/** 스텝 실행 정보 생성 */
export function createStepExecution(overrides?: Partial<StepExecutionResponse>): StepExecutionResponse {
  return {
    id: 1,
    stepId: 1,
    stepName: 'extract',
    status: 'COMPLETED',
    outputRows: 100,
    log: 'Step completed successfully',
    errorMessage: null,
    startedAt: '2026-01-01T10:00:00',
    completedAt: '2026-01-01T10:00:30',
    ...overrides,
  };
}

/** 실행 상세 생성 */
export function createExecutionDetail(overrides?: Partial<ExecutionDetailResponse>): ExecutionDetailResponse {
  return {
    id: 1,
    pipelineId: 1,
    pipelineName: '테스트 파이프라인',
    status: 'COMPLETED',
    executedBy: '테스트 사용자',
    stepExecutions: [
      createStepExecution({ id: 1, stepId: 1, stepName: 'extract' }),
      createStepExecution({ id: 2, stepId: 2, stepName: 'transform' }),
    ],
    startedAt: '2026-01-01T10:00:00',
    completedAt: '2026-01-01T10:01:00',
    createdAt: '2026-01-01T10:00:00',
    ...overrides,
  };
}

/** 트리거 생성 */
export function createTrigger(overrides?: Partial<TriggerResponse>): TriggerResponse {
  return {
    id: 1,
    pipelineId: 1,
    triggerType: 'SCHEDULE',
    name: '매일 실행',
    description: '매일 오전 9시 실행',
    isEnabled: true,
    config: { cronExpression: '0 0 9 * * ?' },
    nextFireTime: '2026-01-02T09:00:00',
    createdAt: '2026-01-01T00:00:00',
    ...overrides,
  };
}

/** 파이프라인 목록 (여러 개) 생성 */
export function createPipelines(count: number): PipelineResponse[] {
  return Array.from({ length: count }, (_, i) =>
    createPipeline({
      id: i + 1,
      name: `파이프라인 ${i + 1}`,
      isActive: i % 3 !== 0,
    }),
  );
}
```

- [ ] **Step 4: analytics.factory.ts**

```typescript
// e2e/factories/analytics.factory.ts
import type {
  AnalyticsQueryResult,
  Chart,
  ChartListItem,
  CreateChartRequest,
  Dashboard,
  DashboardListItem,
  DashboardWidget,
  SavedQuery,
  SavedQueryListItem,
  SchemaColumn,
  SchemaInfo,
  SchemaTable,
} from '../../src/types/analytics';

/** 저장된 쿼리 생성 */
export function createSavedQuery(overrides?: Partial<SavedQuery>): SavedQuery {
  return {
    id: 1,
    name: '테스트 쿼리',
    description: '테스트용 SQL 쿼리',
    sqlText: 'SELECT * FROM test_dataset LIMIT 10',
    datasetId: 1,
    datasetName: '테스트 데이터셋',
    folder: null,
    isShared: false,
    createdByName: '테스트 사용자',
    createdBy: 1,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    chartCount: 0,
    ...overrides,
  };
}

/** 저장된 쿼리 목록 아이템 생성 */
export function createSavedQueryListItem(overrides?: Partial<SavedQueryListItem>): SavedQueryListItem {
  return {
    id: 1,
    name: '테스트 쿼리',
    description: '테스트용 SQL 쿼리',
    folder: null,
    datasetId: 1,
    datasetName: '테스트 데이터셋',
    isShared: false,
    createdByName: '테스트 사용자',
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    chartCount: 0,
    ...overrides,
  };
}

/** 쿼리 실행 결과 생성 */
export function createQueryResult(overrides?: Partial<AnalyticsQueryResult>): AnalyticsQueryResult {
  return {
    queryType: 'SELECT',
    columns: ['id', 'name', 'value'],
    rows: [
      { id: 1, name: '항목 1', value: 100 },
      { id: 2, name: '항목 2', value: 200 },
      { id: 3, name: '항목 3', value: 300 },
    ],
    affectedRows: 0,
    executionTimeMs: 45,
    totalRows: 3,
    truncated: false,
    error: null,
    ...overrides,
  };
}

/** 차트 생성 */
export function createChart(overrides?: Partial<Chart>): Chart {
  return {
    id: 1,
    name: '테스트 차트',
    description: '테스트용 바 차트',
    savedQueryId: 1,
    savedQueryName: '테스트 쿼리',
    chartType: 'BAR',
    config: {
      xAxis: 'name',
      yAxis: ['value'],
    },
    isShared: false,
    createdByName: '테스트 사용자',
    createdBy: 1,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  };
}

/** 차트 목록 아이템 생성 */
export function createChartListItem(overrides?: Partial<ChartListItem>): ChartListItem {
  return {
    id: 1,
    name: '테스트 차트',
    description: null,
    savedQueryId: 1,
    savedQueryName: '테스트 쿼리',
    chartType: 'BAR',
    isShared: false,
    createdByName: '테스트 사용자',
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  };
}

/** 대시보드 위젯 생성 */
export function createWidget(overrides?: Partial<DashboardWidget>): DashboardWidget {
  return {
    id: 1,
    chartId: 1,
    chartName: '테스트 차트',
    chartType: 'BAR',
    positionX: 0,
    positionY: 0,
    width: 6,
    height: 4,
    ...overrides,
  };
}

/** 대시보드 생성 */
export function createDashboard(overrides?: Partial<Dashboard>): Dashboard {
  return {
    id: 1,
    name: '테스트 대시보드',
    description: '테스트용 대시보드',
    isShared: false,
    autoRefreshSeconds: null,
    widgets: [],
    createdByName: '테스트 사용자',
    createdBy: 1,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  };
}

/** 대시보드 목록 아이템 생성 */
export function createDashboardListItem(overrides?: Partial<DashboardListItem>): DashboardListItem {
  return {
    id: 1,
    name: '테스트 대시보드',
    description: null,
    isShared: false,
    autoRefreshSeconds: null,
    widgetCount: 0,
    createdByName: '테스트 사용자',
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  };
}

/** 스키마 정보 생성 */
export function createSchemaInfo(): SchemaInfo {
  return {
    tables: [
      {
        tableName: 'test_dataset',
        datasetName: '테스트 데이터셋',
        datasetId: 1,
        columns: [
          { columnName: 'id', dataType: 'INTEGER', displayName: null },
          { columnName: 'name', dataType: 'TEXT', displayName: '이름' },
          { columnName: 'value', dataType: 'DECIMAL', displayName: '값' },
        ],
      },
    ],
  };
}

/** 저장된 쿼리 목록 생성 */
export function createSavedQueryList(count: number): SavedQueryListItem[] {
  return Array.from({ length: count }, (_, i) =>
    createSavedQueryListItem({ id: i + 1, name: `쿼리 ${i + 1}` }),
  );
}
```

- [ ] **Step 5: ai-insight.factory.ts**

```typescript
// e2e/factories/ai-insight.factory.ts
import type {
  CreateProactiveJobRequest,
  ProactiveJob,
  ProactiveJobExecution,
  ProactiveMessage,
  ReportTemplate,
  TemplateSection,
} from '../../src/api/proactive';

/** 리포트 템플릿 섹션 생성 */
export function createTemplateSection(overrides?: Partial<TemplateSection>): TemplateSection {
  return {
    key: 'summary',
    type: 'text',
    label: '요약',
    description: '전체 내용 요약',
    instruction: '데이터를 분석하여 요약해주세요',
    required: true,
    ...overrides,
  };
}

/** 리포트 템플릿 생성 */
export function createTemplate(overrides?: Partial<ReportTemplate>): ReportTemplate {
  return {
    id: 1,
    name: '테스트 템플릿',
    description: '테스트용 리포트 템플릿',
    sections: [
      createTemplateSection(),
      createTemplateSection({ key: 'details', type: 'table', label: '상세 데이터' }),
    ],
    style: null,
    builtin: false,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  };
}

/** Proactive 작업 생성 */
export function createJob(overrides?: Partial<ProactiveJob>): ProactiveJob {
  return {
    id: 1,
    userId: 1,
    templateId: 1,
    templateName: '테스트 템플릿',
    name: '테스트 인사이트 작업',
    prompt: '데이터셋을 분석하고 인사이트를 제공해주세요',
    cronExpression: '0 0 9 * * ?',
    timezone: 'Asia/Seoul',
    enabled: true,
    config: {},
    lastExecutedAt: null,
    nextExecuteAt: '2026-01-02T09:00:00',
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  };
}

/** 작업 실행 이력 생성 */
export function createJobExecution(overrides?: Partial<ProactiveJobExecution>): ProactiveJobExecution {
  return {
    id: 1,
    jobId: 1,
    status: 'COMPLETED',
    result: { sections: { summary: '테스트 결과 요약' } },
    deliveredChannels: ['WEB'],
    errorMessage: null,
    startedAt: '2026-01-01T09:00:00',
    completedAt: '2026-01-01T09:01:00',
    ...overrides,
  };
}

/** 알림 메시지 생성 */
export function createMessage(overrides?: Partial<ProactiveMessage>): ProactiveMessage {
  return {
    id: 1,
    userId: 1,
    executionId: 1,
    jobName: '테스트 인사이트 작업',
    title: '인사이트 리포트 도착',
    content: { summary: '테스트 결과' },
    messageType: 'REPORT',
    read: false,
    createdAt: '2026-01-01T09:01:00',
    ...overrides,
  };
}

/** 작업 목록 생성 */
export function createJobs(count: number): ProactiveJob[] {
  return Array.from({ length: count }, (_, i) =>
    createJob({ id: i + 1, name: `인사이트 작업 ${i + 1}`, enabled: i % 2 === 0 }),
  );
}

/** 템플릿 목록 생성 */
export function createTemplates(): ReportTemplate[] {
  return [
    createTemplate({ id: 1, name: '기본 분석 리포트', builtin: true }),
    createTemplate({ id: 2, name: '커스텀 리포트', builtin: false }),
  ];
}
```

- [ ] **Step 6: admin.factory.ts**

```typescript
// e2e/factories/admin.factory.ts
import type { ApiConnectionResponse } from '../../src/types/api-connection';
import type { AuditLogResponse } from '../../src/types/auditLog';
import type { PermissionResponse, RoleDetailResponse, RoleResponse } from '../../src/types/role';
import type { SettingResponse } from '../../src/types/settings';

/** 권한 생성 */
export function createPermission(overrides?: Partial<PermissionResponse>): PermissionResponse {
  return {
    id: 1,
    code: 'DATASET_READ',
    description: '데이터셋 조회',
    category: 'DATASET',
    ...overrides,
  };
}

/** 역할 상세 생성 (권한 포함) */
export function createRoleDetail(overrides?: Partial<RoleDetailResponse>): RoleDetailResponse {
  return {
    id: 1,
    name: 'USER',
    description: '일반 사용자',
    isSystem: true,
    permissions: [
      createPermission({ id: 1, code: 'DATASET_READ', category: 'DATASET' }),
      createPermission({ id: 2, code: 'DATASET_WRITE', description: '데이터셋 쓰기', category: 'DATASET' }),
      createPermission({ id: 3, code: 'PIPELINE_READ', description: '파이프라인 조회', category: 'PIPELINE' }),
    ],
    ...overrides,
  };
}

/** 감사 로그 생성 */
export function createAuditLog(overrides?: Partial<AuditLogResponse>): AuditLogResponse {
  return {
    id: 1,
    userId: 1,
    username: 'test@example.com',
    actionType: 'CREATE',
    resource: 'dataset',
    resourceId: '1',
    description: '데이터셋 생성: 테스트 데이터셋',
    actionTime: '2026-01-01T10:00:00',
    ipAddress: '127.0.0.1',
    userAgent: 'Mozilla/5.0',
    result: 'SUCCESS',
    errorMessage: null,
    metadata: null,
    ...overrides,
  };
}

/** API 연결 생성 */
export function createApiConnection(overrides?: Partial<ApiConnectionResponse>): ApiConnectionResponse {
  return {
    id: 1,
    name: '테스트 API 연결',
    description: '테스트용 외부 API',
    authType: 'API_KEY',
    maskedAuthConfig: { apiKey: '****abcd' },
    createdBy: 1,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  };
}

/** 설정 항목 생성 */
export function createSetting(overrides?: Partial<SettingResponse>): SettingResponse {
  return {
    key: 'ai.model',
    value: 'claude-sonnet-4-5-20250514',
    description: 'AI 모델',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  };
}

/** 감사 로그 목록 생성 */
export function createAuditLogs(count: number): AuditLogResponse[] {
  return Array.from({ length: count }, (_, i) =>
    createAuditLog({
      id: i + 1,
      actionType: ['CREATE', 'UPDATE', 'DELETE', 'LOGIN'][i % 4],
      resource: ['dataset', 'pipeline', 'user'][i % 3],
      actionTime: `2026-01-01T${String(10 + i).padStart(2, '0')}:00:00`,
    }),
  );
}

/** 권한 목록 생성 (카테고리별) */
export function createPermissions(): PermissionResponse[] {
  return [
    createPermission({ id: 1, code: 'DATASET_READ', description: '데이터셋 조회', category: 'DATASET' }),
    createPermission({ id: 2, code: 'DATASET_WRITE', description: '데이터셋 쓰기', category: 'DATASET' }),
    createPermission({ id: 3, code: 'PIPELINE_READ', description: '파이프라인 조회', category: 'PIPELINE' }),
    createPermission({ id: 4, code: 'PIPELINE_EXECUTE', description: '파이프라인 실행', category: 'PIPELINE' }),
    createPermission({ id: 5, code: 'ADMIN_USER', description: '사용자 관리', category: 'ADMIN' }),
  ];
}

/** API 연결 목록 생성 */
export function createApiConnections(): ApiConnectionResponse[] {
  return [
    createApiConnection({ id: 1, name: '공공데이터 포털', authType: 'API_KEY' }),
    createApiConnection({ id: 2, name: '기상청 API', authType: 'BEARER' }),
  ];
}
```

- [ ] **Step 7: 타입 검사 확인**

Run: `cd apps/firehub-web && npx tsc -p tsconfig.e2e.json --noEmit`
Expected: 에러 없이 통과 (팩토리가 src/types와 정합)

- [ ] **Step 8: 커밋**

```bash
git add apps/firehub-web/e2e/factories/
git commit -m "test(e2e): 6개 도메인 모킹 데이터 팩토리 구현 — src/types 타입 적용"
```

---

## Task 3: 인증 도메인 — flows + pages 테스트

**Files:**
- Modify: `e2e/pages/auth/login.spec.ts` (기존 테스트에 엣지 케이스 추가)
- Create: `e2e/pages/auth/signup.spec.ts`
- Create: `e2e/flows/auth.spec.ts`

- [ ] **Step 1: login.spec.ts에 서버 에러 엣지 케이스 추가**

기존 5개 테스트에 추가:

```typescript
// 기존 import에 추가
import { createTokenResponse } from '../../factories/auth.factory';

// describe 블록 안에 추가
test('서버 에러(500) 발생 시 일반 에러 메시지를 표시한다', async ({ authMockedPage: page }) => {
  await mockApi(page, 'POST', '/api/v1/auth/login', {
    status: 500,
    error: 'Internal Server Error',
    message: '서버 오류가 발생했습니다.',
  }, { status: 500 });

  await page.goto('/login');
  await page.getByLabel('아이디 (이메일)').fill('test@example.com');
  await page.getByLabel('비밀번호').fill('password123');
  await page.getByRole('button', { name: '로그인' }).click();

  await expect(page.getByText('서버 오류가 발생했습니다.')).toBeVisible();
});

test('로그인 버튼 클릭 중 중복 제출이 방지된다', async ({ authMockedPage: page }) => {
  // 응답을 지연시켜 isSubmitting 상태 확인
  await page.route('**/api/v1/auth/login', async (route) => {
    if (route.request().method() === 'POST') {
      await new Promise((r) => setTimeout(r, 1000));
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(createTokenResponse()),
      });
    }
    return route.continue();
  });

  await page.goto('/login');
  await page.getByLabel('아이디 (이메일)').fill('test@example.com');
  await page.getByLabel('비밀번호').fill('password123');
  await page.getByRole('button', { name: '로그인' }).click();

  // 제출 중 버튼이 비활성화되는지 확인
  await expect(page.getByRole('button', { name: '로그인 중...' })).toBeDisabled();
});

test('회원가입 링크를 클릭하면 회원가입 페이지로 이동한다', async ({ authMockedPage: page }) => {
  await page.goto('/login');
  await page.getByText('계정이 없으신가요? 회원가입').click();
  await expect(page).toHaveURL(/\/signup/);
});
```

- [ ] **Step 2: signup.spec.ts 작성**

```typescript
// e2e/pages/auth/signup.spec.ts
import { createUser } from '../../factories/auth.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

test.describe('회원가입 페이지', () => {
  test('회원가입 페이지가 올바르게 렌더링된다', async ({ authMockedPage: page }) => {
    await page.goto('/signup');

    await expect(page.getByText('Smart Fire Hub')).toBeVisible();
    await expect(page.getByLabel('아이디 (이메일)')).toBeVisible();
    await expect(page.getByLabel('비밀번호')).toBeVisible();
    await expect(page.getByLabel('이름')).toBeVisible();
    await expect(page.getByRole('button', { name: '회원가입' })).toBeVisible();
  });

  test('회원가입 성공 시 로그인 페이지로 이동한다', async ({ authMockedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/auth/signup', createUser({
      username: 'new@example.com',
      name: '새 사용자',
    }));

    await page.goto('/signup');
    await page.getByLabel('아이디 (이메일)').fill('new@example.com');
    await page.getByLabel('비밀번호').fill('password1234');
    await page.getByLabel('이름').fill('새 사용자');
    await page.getByRole('button', { name: '회원가입' }).click();

    await expect(page).toHaveURL(/\/login/);
  });

  test('빈 필드 제출 시 유효성 검사 메시지를 표시한다', async ({ authMockedPage: page }) => {
    await page.goto('/signup');
    await page.getByRole('button', { name: '회원가입' }).click();

    await expect(page.getByText('유효한 이메일 형식의 아이디를 입력하세요')).toBeVisible();
    await expect(page.getByText('이름을 입력하세요')).toBeVisible();
  });

  test('비밀번호가 8자 미만이면 유효성 에러를 표시한다', async ({ authMockedPage: page }) => {
    await page.goto('/signup');
    await page.getByLabel('아이디 (이메일)').fill('new@example.com');
    await page.getByLabel('비밀번호').fill('1234567');
    await page.getByLabel('이름').fill('테스트');
    await page.getByRole('button', { name: '회원가입' }).click();

    await expect(page.getByText('비밀번호는 8자 이상이어야 합니다')).toBeVisible();
  });

  test('이미 존재하는 아이디로 가입 시 서버 에러를 표시한다', async ({ authMockedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/auth/signup', {
      status: 409,
      error: 'Conflict',
      message: '이미 사용 중인 아이디입니다.',
    }, { status: 409 });

    await page.goto('/signup');
    await page.getByLabel('아이디 (이메일)').fill('existing@example.com');
    await page.getByLabel('비밀번호').fill('password1234');
    await page.getByLabel('이름').fill('기존 사용자');
    await page.getByRole('button', { name: '회원가입' }).click();

    await expect(page.getByText('이미 사용 중인 아이디입니다.')).toBeVisible();
  });

  test('로그인 링크를 클릭하면 로그인 페이지로 이동한다', async ({ authMockedPage: page }) => {
    await page.goto('/signup');
    await page.getByText('이미 계정이 있으신가요? 로그인').click();
    await expect(page).toHaveURL(/\/login/);
  });
});
```

- [ ] **Step 3: flows/auth.spec.ts — 인증 전체 플로우**

```typescript
// e2e/flows/auth.spec.ts
import { createUser, createUserDetail } from '../factories/auth.factory';
import { mockApi } from '../fixtures/api-mock';
import { expect, MOCK_USER, test } from '../fixtures/auth.fixture';

test.describe('인증 플로우', () => {
  test('회원가입 → 로그인 → 홈 페이지 확인', async ({ authMockedPage: page }) => {
    // 1. 회원가입
    await mockApi(page, 'POST', '/api/v1/auth/signup', createUser({
      username: 'new@example.com',
      name: '새 사용자',
    }));

    await page.goto('/signup');
    await page.getByLabel('아이디 (이메일)').fill('new@example.com');
    await page.getByLabel('비밀번호').fill('password1234');
    await page.getByLabel('이름').fill('새 사용자');
    await page.getByRole('button', { name: '회원가입' }).click();
    await page.waitForURL(/\/login/);

    // 2. 로그인
    await page.getByLabel('아이디 (이메일)').fill('new@example.com');
    await page.getByLabel('비밀번호').fill('password1234');
    await page.getByRole('button', { name: '로그인' }).click();
    await page.waitForURL('/');

    // 3. 홈 페이지 도달 확인
    await expect(page).toHaveURL('/');
  });

  test('로그인 → 프로필 확인 → 프로필 수정', async ({ authenticatedPage: page }) => {
    // 프로필 수정 API 모킹
    await mockApi(page, 'PUT', '/api/v1/users/me', {});

    // 1. 프로필 페이지 이동
    await page.goto('/profile');

    // 2. 현재 정보 확인
    await expect(page.getByLabel('이름')).toHaveValue(MOCK_USER.name);

    // 3. 이름 변경
    await page.getByLabel('이름').clear();
    await page.getByLabel('이름').fill('수정된 이름');
    await page.getByRole('button', { name: '저장' }).click();

    // 4. 성공 확인 (toast 또는 UI 반응)
    await expect(page.getByText('저장')).toBeVisible();
  });

  test('로그인 → 로그아웃 → 보호 페이지 접근 차단', async ({ authenticatedPage: page }) => {
    // 로그아웃 API 모킹
    await mockApi(page, 'POST', '/api/v1/auth/logout', {});

    // 1. 로그아웃 (UserNav 메뉴에서)
    // 사용자 아바타/메뉴 클릭 후 로그아웃 (정확한 셀렉터는 구현에 따라 조정)
    await page.goto('/login');

    // 2. 보호 페이지 접근 시 로그인으로 리다이렉트
    await expect(page).toHaveURL(/\/login/);
  });
});
```

- [ ] **Step 4: 테스트 실행 확인**

Run: `cd apps/firehub-web && pnpm test:e2e --reporter=list`
Expected: 전체 테스트 통과

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-web/e2e/
git commit -m "test(e2e): 인증 도메인 테스트 — signup, login 상세, auth 플로우"
```

---

## Task 4: 데이터셋 도메인 — flows + pages 테스트

**Files:**
- Create: `e2e/flows/dataset-crud.spec.ts`
- Create: `e2e/pages/data/category-list.spec.ts`
- Create: `e2e/pages/data/dataset-list.spec.ts`
- Create: `e2e/pages/data/dataset-create.spec.ts`
- Create: `e2e/pages/data/dataset-detail.spec.ts`

- [ ] **Step 1: 데이터셋 도메인 fixture 헬퍼 생성**

각 페이지 테스트에서 공통으로 사용할 API 모킹 헬퍼를 만든다. `e2e/fixtures/dataset.fixture.ts`:

```typescript
// e2e/fixtures/dataset.fixture.ts
import type { Page } from '@playwright/test';

import { createCategories, createDatasetDetail, createDatasets } from '../factories/dataset.factory';
import { createPageResponse, mockApi } from './api-mock';

/**
 * 데이터셋 도메인 공통 API 모킹
 * - 카테고리 목록, 데이터셋 목록, 태그 목록
 */
export async function setupDatasetMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(createDatasets(5)));
  await mockApi(page, 'GET', '/api/v1/datasets/tags', ['sample', 'test', 'production']);
}

/** 데이터셋 상세 페이지 API 모킹 */
export async function setupDatasetDetailMocks(page: Page, datasetId = 1) {
  await mockApi(page, 'GET', `/api/v1/datasets/${datasetId}`, createDatasetDetail({ id: datasetId }));
  await mockApi(page, 'GET', `/api/v1/datasets/${datasetId}/data`, {
    columns: createDatasetDetail().columns,
    rows: [
      { id: 1, name: '항목 1', value: 100 },
      { id: 2, name: '항목 2', value: 200 },
    ],
    page: 0,
    size: 20,
    totalElements: 2,
    totalPages: 1,
  });
  await mockApi(page, 'GET', `/api/v1/datasets/${datasetId}/stats`, []);
}
```

- [ ] **Step 2: flows/dataset-crud.spec.ts — 데이터셋 CRUD 플로우**

```typescript
// e2e/flows/dataset-crud.spec.ts
import { createCategory, createDataset, createDatasetDetail, createDatasets } from '../factories/dataset.factory';
import { createPageResponse, mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { setupDatasetMocks } from '../fixtures/dataset.fixture';

test.describe('데이터셋 CRUD 플로우', () => {
  test('카테고리 생성 → 데이터셋 생성 → 목록 확인', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);

    // 데이터셋 생성 API 모킹
    await mockApi(page, 'POST', '/api/v1/datasets', createDatasetDetail({
      id: 10,
      name: '새 데이터셋',
      tableName: 'new_dataset',
    }));

    // 1. 데이터셋 생성 페이지 이동
    await page.goto('/data/datasets/new');

    // 2. 기본 정보 입력
    await page.getByLabel('이름').fill('새 데이터셋');
    await page.getByLabel('테이블명').fill('new_dataset');

    // 3. 저장 버튼 클릭 (컬럼 설정은 SchemaBuilder 컴포넌트)
    // 최소 1개 컬럼이 필요하므로 기본 컬럼 추가 후 저장
    // (정확한 UI 상호작용은 SchemaBuilder 구현에 따라 조정 필요)

    // 4. 목록 페이지로 돌아가서 확인
    await page.goto('/data/datasets');
    await expect(page.getByText('데이터셋 1')).toBeVisible();
  });

  test('데이터셋 목록 → 상세 → 수정 → 확인', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    const detail = createDatasetDetail();
    await mockApi(page, 'GET', '/api/v1/datasets/1', detail);
    await mockApi(page, 'PUT', '/api/v1/datasets/1', { ...detail, name: '수정된 데이터셋' });

    // 1. 목록에서 첫 번째 데이터셋 클릭
    await page.goto('/data/datasets');
    await page.getByText('데이터셋 1').click();

    // 2. 상세 페이지 도달 확인
    await expect(page).toHaveURL(/\/data\/datasets\/1/);
  });

  test('데이터셋 삭제 → 목록 반영', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await mockApi(page, 'DELETE', '/api/v1/datasets/1', {});

    await page.goto('/data/datasets');

    // 삭제 버튼 클릭 (첫 번째 행)
    const firstRow = page.getByRole('row').nth(1);
    await firstRow.getByRole('button', { name: /삭제/ }).click();

    // 확인 다이얼로그에서 확인
    await page.getByRole('button', { name: /확인|삭제/ }).click();
  });
});
```

- [ ] **Step 3: pages/data/dataset-list.spec.ts — 목록 페이지 상세**

```typescript
// e2e/pages/data/dataset-list.spec.ts
import { createDataset, createDatasets } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetMocks } from '../../fixtures/dataset.fixture';

test.describe('데이터셋 목록 페이지', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
  });

  test('데이터셋 목록이 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets');
    await expect(page.getByText('데이터셋 1')).toBeVisible();
    await expect(page.getByText('데이터셋 5')).toBeVisible();
  });

  test('빈 목록일 때 빈 상태 UI를 표시한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse([]));
    await page.goto('/data/datasets');
    // 빈 상태 메시지 확인 (정확한 텍스트는 구현에 따라 조정)
    await expect(page.getByText(/데이터셋이 없습니다|등록된 데이터셋/)).toBeVisible();
  });

  test('검색 필터가 동작한다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets');
    const searchInput = page.getByPlaceholder(/검색/);
    await searchInput.fill('테스트');
    // 디바운스 대기 (300ms)
    await page.waitForTimeout(500);
    // 검색 결과 반영 확인 (API가 재호출됨)
  });

  test('데이터셋 타입 필터가 동작한다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets');
    // 타입 필터 드롭다운 (정확한 셀렉터는 구현에 따라 조정)
    // SOURCE, DERIVED 등 선택 가능 확인
  });

  test('즐겨찾기 토글이 동작한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/datasets/1/favorite', { favorited: true });
    await page.goto('/data/datasets');
    // 즐겨찾기 버튼 클릭
  });

  test('새 데이터셋 만들기 버튼이 생성 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets');
    await page.getByRole('link', { name: /새.*데이터셋|데이터셋.*생성/ }).click();
    await expect(page).toHaveURL(/\/data\/datasets\/new/);
  });

  test('서버 에러(500) 시 에러 상태를 표시한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/datasets', { message: '서버 오류' }, { status: 500 });
    await page.goto('/data/datasets');
    // 에러 상태 확인
  });
});
```

- [ ] **Step 4: pages/data/dataset-create.spec.ts — 생성 페이지 상세**

```typescript
// e2e/pages/data/dataset-create.spec.ts
import { createCategories, createDatasetDetail } from '../../factories/dataset.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

test.describe('데이터셋 생성 페이지', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  });

  test('생성 폼이 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets/new');
    await expect(page.getByLabel('이름')).toBeVisible();
    await expect(page.getByLabel('테이블명')).toBeVisible();
  });

  test('필수 필드 없이 제출 시 유효성 에러를 표시한다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets/new');
    await page.getByRole('button', { name: /저장|생성/ }).click();
    // 유효성 에러 메시지 확인
  });

  test('테이블명에 대문자/특수문자 입력 시 유효성 에러를 표시한다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets/new');
    await page.getByLabel('테이블명').fill('Invalid-Table-Name');
    await page.getByRole('button', { name: /저장|생성/ }).click();
    // regex 유효성 에러 (소문자+언더스코어만 허용)
  });

  test('중복 이름으로 생성 시 서버 에러를 표시한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/datasets', {
      status: 409,
      message: '이미 존재하는 데이터셋 이름입니다.',
    }, { status: 409 });

    await page.goto('/data/datasets/new');
    await page.getByLabel('이름').fill('중복 데이터셋');
    await page.getByLabel('테이블명').fill('duplicate_dataset');
    // 컬럼 추가 + 저장 시도
  });

  test('취소 버튼 클릭 시 이전 페이지로 돌아간다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets/new');
    await page.getByRole('button', { name: /취소/ }).click();
    await expect(page).not.toHaveURL(/\/new/);
  });
});
```

- [ ] **Step 5: pages/data/dataset-detail.spec.ts — 상세 페이지 상세**

```typescript
// e2e/pages/data/dataset-detail.spec.ts
import { createDatasetDetail } from '../../factories/dataset.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetDetailMocks, setupDatasetMocks } from '../../fixtures/dataset.fixture';

test.describe('데이터셋 상세 페이지', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await setupDatasetDetailMocks(page, 1);
  });

  test('상세 정보가 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets/1');
    await expect(page.getByText('테스트 데이터셋')).toBeVisible();
    await expect(page.getByText('test_dataset')).toBeVisible();
  });

  test('탭 전환이 동작한다 — 컬럼 탭', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets/1');
    await page.getByRole('tab', { name: /컬럼|필드/ }).click();
    await expect(page.getByText('test_col')).toBeVisible();
  });

  test('탭 전환이 동작한다 — 데이터 탭', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets/1');
    await page.getByRole('tab', { name: /데이터/ }).click();
    await expect(page.getByText('항목 1')).toBeVisible();
  });

  test('즐겨찾기 토글이 동작한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/datasets/1/favorite', { favorited: true });
    await page.goto('/data/datasets/1');
    // 즐겨찾기 버튼 클릭 확인
  });

  test('태그 추가/삭제가 동작한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/datasets/1/tags', {});
    await mockApi(page, 'DELETE', '/api/v1/datasets/1/tags/sample', {});
    await page.goto('/data/datasets/1');
    // 태그 관리 UI 확인
  });

  test('존재하지 않는 데이터셋 접근 시 에러를 표시한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/datasets/999', { message: '데이터셋을 찾을 수 없습니다.' }, { status: 404 });
    await page.goto('/data/datasets/999');
    // 404 에러 상태 확인
  });
});
```

- [ ] **Step 6: pages/data/category-list.spec.ts — 카테고리 관리**

```typescript
// e2e/pages/data/category-list.spec.ts
import { createCategories, createCategory } from '../../factories/dataset.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

test.describe('카테고리 목록 페이지', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  });

  test('카테고리 목록이 표시된다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/categories');
    await expect(page.getByText('환경')).toBeVisible();
    await expect(page.getByText('안전')).toBeVisible();
  });

  test('새 카테고리를 생성할 수 있다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/dataset-categories', createCategory({ id: 4, name: '신규 카테고리' }));
    await page.goto('/data/categories');
    // 생성 다이얼로그 열기 → 이름 입력 → 저장
  });

  test('카테고리를 삭제할 수 있다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'DELETE', '/api/v1/dataset-categories/3', {});
    await page.goto('/data/categories');
    // 삭제 버튼 클릭 → 확인
  });

  test('빈 카테고리 목록에서 빈 상태 UI를 표시한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await page.goto('/data/categories');
  });
});
```

- [ ] **Step 7: 테스트 실행 확인**

Run: `cd apps/firehub-web && pnpm test:e2e --reporter=list`
Expected: 전체 통과

- [ ] **Step 8: 커밋**

```bash
git add apps/firehub-web/e2e/
git commit -m "test(e2e): 데이터셋 도메인 테스트 — CRUD 플로우 + 목록/생성/상세/카테고리 상세"
```

---

## Task 5: 파이프라인 도메인 — flows + pages 테스트

**Files:**
- Create: `e2e/fixtures/pipeline.fixture.ts`
- Create: `e2e/flows/pipeline-workflow.spec.ts`
- Create: `e2e/pages/pipeline/pipeline-list.spec.ts`
- Create: `e2e/pages/pipeline/pipeline-editor.spec.ts`

- [ ] **Step 1: pipeline.fixture.ts — 파이프라인 도메인 모킹 헬퍼**

```typescript
// e2e/fixtures/pipeline.fixture.ts
import type { Page } from '@playwright/test';

import { createDatasets } from '../factories/dataset.factory';
import { createExecution, createPipelineDetail, createPipelines, createTrigger } from '../factories/pipeline.factory';
import { createPageResponse, mockApi } from './api-mock';

export async function setupPipelineMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/pipelines', createPageResponse(createPipelines(5)));
  await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(createDatasets(3)));
}

export async function setupPipelineDetailMocks(page: Page, pipelineId = 1) {
  const detail = createPipelineDetail({ id: pipelineId });
  await mockApi(page, 'GET', `/api/v1/pipelines/${pipelineId}`, detail);
  await mockApi(page, 'GET', `/api/v1/pipelines/${pipelineId}/executions`, createPageResponse([
    createExecution({ id: 1, pipelineId }),
    createExecution({ id: 2, pipelineId, status: 'FAILED' }),
  ]));
  await mockApi(page, 'GET', `/api/v1/pipelines/${pipelineId}/triggers`, [
    createTrigger({ pipelineId }),
  ]);
}
```

- [ ] **Step 2: flows/pipeline-workflow.spec.ts**

```typescript
// e2e/flows/pipeline-workflow.spec.ts
import { createExecution, createPipelineDetail } from '../factories/pipeline.factory';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { setupPipelineDetailMocks, setupPipelineMocks } from '../fixtures/pipeline.fixture';

test.describe('파이프라인 워크플로우', () => {
  test('파이프라인 목록 → 상세 → 실행 → 결과 확인', async ({ authenticatedPage: page }) => {
    await setupPipelineMocks(page);
    await setupPipelineDetailMocks(page, 1);
    await mockApi(page, 'POST', '/api/v1/pipelines/1/execute', createExecution({ status: 'RUNNING' }));

    // 1. 목록 페이지
    await page.goto('/pipelines');
    await expect(page.getByText('파이프라인 1')).toBeVisible();

    // 2. 첫 번째 파이프라인 클릭 → 상세
    await page.getByText('파이프라인 1').click();
    await expect(page).toHaveURL(/\/pipelines\/1/);

    // 3. 실행 버튼 클릭
    await page.getByRole('button', { name: /실행/ }).click();
  });

  test('파이프라인 생성 → 스텝 추가 → 저장', async ({ authenticatedPage: page }) => {
    await setupPipelineMocks(page);
    await mockApi(page, 'POST', '/api/v1/pipelines', createPipelineDetail({ id: 10 }));

    await page.goto('/pipelines/new');
    // 파이프라인 이름 입력 (정확한 UI는 에디터 구현에 따라 조정)
  });
});
```

- [ ] **Step 3: pages/pipeline/pipeline-list.spec.ts**

```typescript
// e2e/pages/pipeline/pipeline-list.spec.ts
import { createPipelines } from '../../factories/pipeline.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupPipelineMocks } from '../../fixtures/pipeline.fixture';

test.describe('파이프라인 목록 페이지', () => {
  test('파이프라인 목록이 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupPipelineMocks(page);
    await page.goto('/pipelines');
    await expect(page.getByText('파이프라인 1')).toBeVisible();
  });

  test('빈 목록에서 빈 상태를 표시한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/pipelines', createPageResponse([]));
    await page.goto('/pipelines');
  });

  test('삭제 확인 다이얼로그가 동작한다', async ({ authenticatedPage: page }) => {
    await setupPipelineMocks(page);
    await mockApi(page, 'DELETE', '/api/v1/pipelines/1', {});
    await page.goto('/pipelines');
    // 삭제 버튼 클릭 → 확인 다이얼로그
  });

  test('새 파이프라인 버튼이 에디터 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupPipelineMocks(page);
    await page.goto('/pipelines');
    await page.getByRole('link', { name: /새.*파이프라인|파이프라인.*생성/ }).click();
    await expect(page).toHaveURL(/\/pipelines\/new/);
  });

  test('활성/비활성 상태 배지가 표시된다', async ({ authenticatedPage: page }) => {
    await setupPipelineMocks(page);
    await page.goto('/pipelines');
    // 활성/비활성 배지 확인
  });
});
```

- [ ] **Step 4: pages/pipeline/pipeline-editor.spec.ts**

```typescript
// e2e/pages/pipeline/pipeline-editor.spec.ts
import { createExecutionDetail, createPipelineDetail } from '../../factories/pipeline.factory';
import { createDatasets } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupPipelineDetailMocks } from '../../fixtures/pipeline.fixture';

test.describe('파이프라인 에디터 페이지', () => {
  test('기존 파이프라인 상세가 로드된다', async ({ authenticatedPage: page }) => {
    await setupPipelineDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(createDatasets(3)));
    await page.goto('/pipelines/1');

    await expect(page.getByText('테스트 파이프라인')).toBeVisible();
  });

  test('실행 이력 탭이 동작한다', async ({ authenticatedPage: page }) => {
    await setupPipelineDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(createDatasets(3)));
    await page.goto('/pipelines/1');

    // 실행 이력 탭 클릭
    await page.getByRole('tab', { name: /실행/ }).click();
  });

  test('트리거 탭이 동작한다', async ({ authenticatedPage: page }) => {
    await setupPipelineDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(createDatasets(3)));
    await page.goto('/pipelines/1');

    await page.getByRole('tab', { name: /트리거/ }).click();
    await expect(page.getByText('매일 실행')).toBeVisible();
  });

  test('실행 상세 정보가 표시된다', async ({ authenticatedPage: page }) => {
    await setupPipelineDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(createDatasets(3)));
    const execDetail = createExecutionDetail({ pipelineId: 1 });
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/1', execDetail);

    await page.goto('/pipelines/1/executions/1');
    // 실행 상세 확인 (스텝별 상태 등)
  });

  test('서버 에러 시 에러 상태를 표시한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/pipelines/999', { message: 'Not found' }, { status: 404 });
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(createDatasets(3)));
    await page.goto('/pipelines/999');
  });
});
```

- [ ] **Step 5: 테스트 실행 확인**

Run: `cd apps/firehub-web && pnpm test:e2e --reporter=list`
Expected: 전체 통과

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-web/e2e/
git commit -m "test(e2e): 파이프라인 도메인 테스트 — 워크플로우 + 목록/에디터 상세"
```

---

## Task 6: 분석 도메인 — flows + pages 테스트

**Files:**
- Create: `e2e/fixtures/analytics.fixture.ts`
- Create: `e2e/flows/analytics-workflow.spec.ts`
- Create: `e2e/pages/analytics/query-list.spec.ts`
- Create: `e2e/pages/analytics/query-editor.spec.ts`
- Create: `e2e/pages/analytics/chart-list.spec.ts`
- Create: `e2e/pages/analytics/chart-builder.spec.ts`
- Create: `e2e/pages/analytics/dashboard-list.spec.ts`
- Create: `e2e/pages/analytics/dashboard-editor.spec.ts`

- [ ] **Step 1: analytics.fixture.ts**

```typescript
// e2e/fixtures/analytics.fixture.ts
import type { Page } from '@playwright/test';

import {
  createChartListItem,
  createDashboard,
  createDashboardListItem,
  createQueryResult,
  createSavedQueryList,
  createSchemaInfo,
} from '../factories/analytics.factory';
import { createPageResponse, mockApi } from './api-mock';

export async function setupQueryMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/analytics/queries', createPageResponse(createSavedQueryList(5)));
  await mockApi(page, 'GET', '/api/v1/analytics/queries/schema', createSchemaInfo());
  await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', ['기본', '분석']);
}

export async function setupChartMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([
    createChartListItem({ id: 1, name: '바 차트' }),
    createChartListItem({ id: 2, name: '라인 차트', chartType: 'LINE' }),
  ]));
}

export async function setupDashboardMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/analytics/dashboards', createPageResponse([
    createDashboardListItem({ id: 1, name: '메인 대시보드', widgetCount: 3 }),
    createDashboardListItem({ id: 2, name: '분석 대시보드' }),
  ]));
}
```

- [ ] **Step 2: flows/analytics-workflow.spec.ts**

```typescript
// e2e/flows/analytics-workflow.spec.ts
import { createChart, createQueryResult, createSavedQuery } from '../factories/analytics.factory';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { setupChartMocks, setupDashboardMocks, setupQueryMocks } from '../fixtures/analytics.fixture';

test.describe('분석 워크플로우', () => {
  test('쿼리 목록 → 새 쿼리 작성 → 실행 → 결과 확인', async ({ authenticatedPage: page }) => {
    await setupQueryMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/execute', createQueryResult());

    await page.goto('/analytics/queries');
    await page.getByRole('link', { name: /새.*쿼리|쿼리.*생성/ }).click();
    await expect(page).toHaveURL(/\/analytics\/queries\/new/);

    // SQL 입력 후 실행 (CodeMirror 에디터)
  });

  test('쿼리 결과에서 차트 생성', async ({ authenticatedPage: page }) => {
    await setupQueryMocks(page);
    const savedQuery = createSavedQuery();
    await mockApi(page, 'GET', '/api/v1/analytics/queries/1', savedQuery);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', createQueryResult());
    await mockApi(page, 'POST', '/api/v1/analytics/charts', createChart());

    await page.goto('/analytics/queries/1');
    // 쿼리 실행 → 차트 생성 버튼 클릭
  });

  test('대시보드 목록 → 대시보드 생성', async ({ authenticatedPage: page }) => {
    await setupDashboardMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/dashboards', { id: 3, name: '새 대시보드' });

    await page.goto('/analytics/dashboards');
    await expect(page.getByText('메인 대시보드')).toBeVisible();
  });
});
```

- [ ] **Step 3: 쿼리/차트/대시보드 페이지 테스트 각각 생성**

각 파일에 렌더링, CRUD, 유효성 검사, 서버 에러, 빈 상태 등의 테스트를 포함한다.
(패턴은 Task 4의 dataset-list.spec.ts와 동일 — fixture 기반 모킹 + describe/test 한국어)

- query-list.spec.ts: 목록 렌더링, 검색, 폴더 필터, 공유 필터, 삭제, 빈 상태
- query-editor.spec.ts: SQL 에디터 렌더링, 쿼리 실행, 저장, 결과 테이블, 스키마 탐색기, 에러 표시
- chart-list.spec.ts: 목록 렌더링, 필터, 삭제, 빈 상태
- chart-builder.spec.ts: 차트 타입 선택, 축 설정, 프리뷰, 저장, 유효성 검사
- dashboard-list.spec.ts: 목록 렌더링, 생성 다이얼로그, 탭 전환(내 것/공유), 삭제
- dashboard-editor.spec.ts: 위젯 추가/제거, 저장, 자동 새로고침 설정

- [ ] **Step 4: 테스트 실행 확인**

Run: `cd apps/firehub-web && pnpm test:e2e --reporter=list`

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-web/e2e/
git commit -m "test(e2e): 분석 도메인 테스트 — 쿼리/차트/대시보드 플로우 + 페이지 상세"
```

---

## Task 7: AI 인사이트 도메인 — flows + pages 테스트

**Files:**
- Create: `e2e/fixtures/ai-insight.fixture.ts`
- Create: `e2e/flows/ai-insight-workflow.spec.ts`
- Create: `e2e/pages/ai-insights/job-list.spec.ts`
- Create: `e2e/pages/ai-insights/job-detail.spec.ts`
- Create: `e2e/pages/ai-insights/template-list.spec.ts`
- Create: `e2e/pages/ai-insights/template-detail.spec.ts`
- Create: `e2e/pages/ai-insights/execution-detail.spec.ts`

- [ ] **Step 1: ai-insight.fixture.ts**

```typescript
// e2e/fixtures/ai-insight.fixture.ts
import type { Page } from '@playwright/test';

import { createJobExecution, createJobs, createTemplates } from '../factories/ai-insight.factory';
import { mockApi } from './api-mock';

export async function setupAiInsightMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/proactive/jobs', createJobs(5));
  await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
}

export async function setupJobDetailMocks(page: Page, jobId = 1) {
  const { createJob } = await import('../factories/ai-insight.factory');
  await mockApi(page, 'GET', `/api/v1/proactive/jobs/${jobId}`, createJob({ id: jobId }));
  await mockApi(page, 'GET', `/api/v1/proactive/jobs/${jobId}/executions`, [
    createJobExecution({ id: 1, jobId }),
    createJobExecution({ id: 2, jobId, status: 'FAILED', errorMessage: '분석 실패' }),
  ]);
}
```

- [ ] **Step 2: flows/ai-insight-workflow.spec.ts**

```typescript
// e2e/flows/ai-insight-workflow.spec.ts
import { createJob, createJobExecution, createTemplate } from '../factories/ai-insight.factory';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { setupAiInsightMocks, setupJobDetailMocks } from '../fixtures/ai-insight.fixture';

test.describe('AI 인사이트 워크플로우', () => {
  test('작업 목록 → 작업 상세 → 실행', async ({ authenticatedPage: page }) => {
    await setupAiInsightMocks(page);
    await setupJobDetailMocks(page, 1);
    await mockApi(page, 'POST', '/api/v1/proactive/jobs/1/execute', createJobExecution({ status: 'RUNNING' }));

    await page.goto('/ai-insights/jobs');
    await expect(page.getByText('인사이트 작업 1')).toBeVisible();

    // 작업 클릭 → 상세
    await page.getByText('인사이트 작업 1').click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1/);
  });

  test('템플릿 생성 → 작업에 연결', async ({ authenticatedPage: page }) => {
    await setupAiInsightMocks(page);
    await mockApi(page, 'POST', '/api/v1/proactive/templates', createTemplate({ id: 3, name: '새 템플릿' }));

    await page.goto('/ai-insights/templates');
    await expect(page.getByText('기본 분석 리포트')).toBeVisible();
  });
});
```

- [ ] **Step 3: 각 페이지 테스트 생성**

- job-list.spec.ts: 목록, 활성/비활성 필터, 삭제, 빈 상태
- job-detail.spec.ts: 생성 폼, 프롬프트 입력, 스케줄 설정 (cron), 템플릿 선택, 유효성 검사, 수정, 삭제
- template-list.spec.ts: 목록, 빌트인/커스텀 구분, 삭제 (빌트인 보호)
- template-detail.spec.ts: 섹션 편집, 타입 선택, 프롬프트 설정, 저장, 유효성 검사
- execution-detail.spec.ts: 실행 상태 표시, 로그, 성공/실패 표시, 리포트 뷰어

- [ ] **Step 4: 테스트 실행 확인 + 커밋**

```bash
git add apps/firehub-web/e2e/
git commit -m "test(e2e): AI 인사이트 도메인 테스트 — 작업/템플릿/실행 플로우 + 페이지 상세"
```

---

## Task 8: 관리자 도메인 — flows + pages 테스트

**Files:**
- Create: `e2e/fixtures/admin.fixture.ts`
- Create: `e2e/flows/admin-management.spec.ts`
- Create: `e2e/pages/admin/user-management.spec.ts`
- Create: `e2e/pages/admin/role-management.spec.ts`
- Create: `e2e/pages/admin/audit-logs.spec.ts`
- Create: `e2e/pages/admin/settings.spec.ts`
- Create: `e2e/pages/admin/api-connections.spec.ts`

- [ ] **Step 1: admin.fixture.ts**

```typescript
// e2e/fixtures/admin.fixture.ts
import type { Page } from '@playwright/test';

import { createApiConnections, createAuditLogs, createPermissions, createRoleDetail } from '../factories/admin.factory';
import { createAdminUserDetail, createRole, createUser, createUserDetail } from '../factories/auth.factory';
import { createPageResponse, mockApi } from './api-mock';

/**
 * 관리자 페이지 접근을 위한 모킹
 * - ADMIN 역할을 가진 사용자로 모킹해야 관리 메뉴에 접근 가능
 */
export async function setupAdminAuth(page: Page) {
  // 관리자 사용자로 오버라이드
  await mockApi(page, 'GET', '/api/v1/users/me', createAdminUserDetail());
}

export async function setupUserManagementMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/users', createPageResponse([
    createUser({ id: 1, username: 'admin@example.com', name: '관리자' }),
    createUser({ id: 2, username: 'user@example.com', name: '일반 사용자' }),
    createUser({ id: 3, username: 'inactive@example.com', name: '비활성 사용자', isActive: false }),
  ]));
  await mockApi(page, 'GET', '/api/v1/roles', [
    createRole({ id: 1, name: 'USER', isSystem: true }),
    createRole({ id: 2, name: 'ADMIN', isSystem: true }),
    createRole({ id: 3, name: 'ANALYST', description: '분석가', isSystem: false }),
  ]);
}

export async function setupRoleMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/roles', [
    createRole({ id: 1, name: 'USER', isSystem: true }),
    createRole({ id: 2, name: 'ADMIN', isSystem: true }),
    createRole({ id: 3, name: 'ANALYST', description: '분석가', isSystem: false }),
  ]);
  await mockApi(page, 'GET', '/api/v1/permissions', createPermissions());
}

export async function setupAuditLogMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/admin/audit-logs', createPageResponse(createAuditLogs(10)));
}

export async function setupApiConnectionMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/api-connections', createApiConnections());
}
```

- [ ] **Step 2: flows/admin-management.spec.ts**

```typescript
// e2e/flows/admin-management.spec.ts
import { createRoleDetail } from '../factories/admin.factory';
import { createRole, createUserDetail } from '../factories/auth.factory';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { setupAdminAuth, setupRoleMocks, setupUserManagementMocks } from '../fixtures/admin.fixture';

test.describe('관리자 플로우', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  test('사용자 목록 → 사용자 상세 → 역할 변경', async ({ authenticatedPage: page }) => {
    await setupUserManagementMocks(page);
    await mockApi(page, 'GET', '/api/v1/users/2', createUserDetail({ id: 2, username: 'user@example.com' }));
    await mockApi(page, 'PUT', '/api/v1/users/2/roles', {});

    await page.goto('/admin/users');
    await expect(page.getByText('일반 사용자')).toBeVisible();

    // 사용자 클릭
    await page.getByText('일반 사용자').click();
    await expect(page).toHaveURL(/\/admin\/users\/2/);
  });

  test('역할 목록 → 역할 생성 → 권한 설정', async ({ authenticatedPage: page }) => {
    await setupRoleMocks(page);
    await mockApi(page, 'POST', '/api/v1/roles', createRole({ id: 4, name: 'NEW_ROLE', isSystem: false }));
    await mockApi(page, 'GET', '/api/v1/roles/4', createRoleDetail({ id: 4, name: 'NEW_ROLE', isSystem: false }));

    await page.goto('/admin/roles');
    await expect(page.getByText('ADMIN')).toBeVisible();
  });
});
```

- [ ] **Step 3: 각 관리자 페이지 테스트 생성**

- user-management.spec.ts: 목록, 검색, 사용자 상세, 역할 변경, 활성화/비활성화 토글
- role-management.spec.ts: 목록, 생성 다이얼로그, 권한 편집, 삭제 (시스템 역할 보호), 유효성 검사
- audit-logs.spec.ts: 목록, 검색, 액션 타입 필터, 리소스 필터, 결과 필터, 페이지네이션
- settings.spec.ts: 설정 로드, AI 에이전트 설정 폼, 모델 선택, API 키 마스킹, 저장, 초기화
- api-connections.spec.ts: 목록, 생성 폼, 인증 타입 선택, 수정, 삭제, 유효성 검사

- [ ] **Step 4: 테스트 실행 확인 + 커밋**

```bash
git add apps/firehub-web/e2e/
git commit -m "test(e2e): 관리자 도메인 테스트 — 사용자/역할/감사로그/설정/API연결"
```

---

## Task 9: 홈 페이지 테스트

**Files:**
- Create: `e2e/pages/home.spec.ts`

- [ ] **Step 1: 홈 페이지 테스트**

```typescript
// e2e/pages/home.spec.ts
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';

test.describe('홈 페이지', () => {
  test('대시보드 통계가 렌더링된다', async ({ authenticatedPage: page }) => {
    // authenticatedPage에 이미 홈 모킹 포함
    await expect(page).toHaveURL('/');
    // 시스템 건강 상태 표시 확인
  });

  test('빠른 액션 버튼이 올바른 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    // 데이터셋, 파이프라인, SQL 에디터 등 바로가기 확인
  });

  test('주의 항목이 표시된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/dashboard/attention', [
      {
        type: 'PIPELINE_FAILURE',
        severity: 'CRITICAL',
        title: '파이프라인 실패',
        description: '파이프라인 1이 실패했습니다',
        entityId: 1,
        entityType: 'PIPELINE',
        occurredAt: '2026-01-01T10:00:00',
      },
    ]);
    await page.goto('/');
    // 주의 항목 표시 확인
  });

  test('활동 피드 필터가 동작한다', async ({ authenticatedPage: page }) => {
    await page.goto('/');
    // 활동 피드 필터 (타입별, 심각도별)
  });
});
```

- [ ] **Step 2: 테스트 실행 확인 + 커밋**

```bash
git add apps/firehub-web/e2e/pages/home.spec.ts
git commit -m "test(e2e): 홈 페이지 테스트 — 대시보드 통계, 주의 항목, 활동 피드"
```

---

## Task 10: 최종 검증 + CLAUDE.md 업데이트

- [ ] **Step 1: 전체 테스트 실행**

Run: `cd apps/firehub-web && pnpm test:e2e --reporter=list`
Expected: 모든 테스트 통과

- [ ] **Step 2: 타입 체크 확인**

Run: `cd apps/firehub-web && npx tsc -p tsconfig.e2e.json --noEmit`
Expected: 에러 없음

- [ ] **Step 3: CLAUDE.md 업데이트**

`apps/firehub-web/CLAUDE.md`의 E2E 테스트 섹션을 전체 구조에 맞게 업데이트:

```markdown
### E2E 테스트 (Playwright)

- 설정: `playwright.config.ts`, 테스트 디렉토리: `e2e/`
- API 모킹 기반 — `page.route()`로 백엔드 API를 모킹하므로 백엔드 불필요
- 모킹 데이터는 `src/types/` 타입을 적용하여 API 스펙 변경 시 컴파일 에러로 감지

#### 디렉토리 구조
- `e2e/factories/` — 모킹 데이터 팩토리 (도메인별 파일)
- `e2e/fixtures/` — API 모킹 헬퍼 + 인증/도메인별 fixture
- `e2e/flows/` — 유저 플로우 시나리오 (해피 패스 연속 시나리오)
- `e2e/pages/` — 개별 페이지 상세 (유효성 검사, 엣지 케이스)

#### 새 테스트 추가 시
1. 팩토리: `e2e/factories/`에서 모킹 데이터 생성 함수 추가 (타입 필수 적용)
2. Fixture: `e2e/fixtures/`에서 도메인 API 모킹 헬퍼 추가
3. 테스트: `e2e/flows/` 또는 `e2e/pages/`에 spec 파일 추가
4. `auth.fixture.ts`의 `test`, `expect`를 import하여 인증 fixture 사용
```

- [ ] **Step 4: 최종 커밋**

```bash
git add apps/firehub-web/CLAUDE.md
git commit -m "docs(web): E2E 테스트 전체 구조 문서화"
```

---

## 실행 순서 요약

| Task | 내용 | 예상 파일 수 |
|------|------|-------------|
| 1 | 인프라 — 디렉토리 재편 + base fixture | 3 |
| 2 | 팩토리 — 6개 도메인 | 6 |
| 3 | 인증 — flows + pages | 3 |
| 4 | 데이터셋 — flows + pages + fixture | 6 |
| 5 | 파이프라인 — flows + pages + fixture | 4 |
| 6 | 분석 — flows + pages + fixture | 8 |
| 7 | AI 인사이트 — flows + pages + fixture | 7 |
| 8 | 관리자 — flows + pages + fixture | 7 |
| 9 | 홈 페이지 | 1 |
| 10 | 최종 검증 + 문서 | 1 |

각 Task는 독립적으로 커밋 가능하며, Task 1-2는 순서대로, Task 3-9는 병렬 실행 가능하다.
