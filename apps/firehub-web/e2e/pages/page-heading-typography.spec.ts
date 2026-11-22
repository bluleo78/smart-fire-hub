import type { Locator, Page } from '@playwright/test';

import { createCategories, createDatasetDetail } from '../factories/dataset.factory';
import {
  setupAdminAuth,
  setupApiConnectionDetailMocks,
  setupApiConnectionListMocks,
  setupAuditLogMocks,
  setupOntologyMocks,
  setupRoleDetailMocks,
  setupRoleListMocks,
  setupSettingsMocks,
  setupUserDetailMocks,
  setupUserListMocks,
} from '../fixtures/admin.fixture';
import {
  mockProactivePermissions,
  setupJobListMocks,
  setupNewJobMocks,
  setupTemplateDetailMocks,
} from '../fixtures/ai-insight.fixture';
import {
  setupChartListMocks,
  setupDashboardListMocks,
  setupQueryListMocks,
} from '../fixtures/analytics.fixture';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetDetailMocks, setupDatasetMocks } from '../fixtures/dataset.fixture';
import { expectNoHorizontalOverflow, LONG_UNBROKEN_NAME } from '../fixtures/long-unbroken-name';
import { setupPipelineMocks } from '../fixtures/pipeline.fixture';

/**
 * 페이지 제목(h1) 표준 타이포그래피 sweep (WD-910, 특성 시험)
 *
 * 같은 h1 클래스 문자열이 여러 페이지에 복사돼 있던 것을 공용 컴포넌트(PageHeading)로 모으는 리팩터링의
 * 회귀 시험이다. 화면이 달라 보이지 않아야 하므로, 이전 전에도 통과해야 하고 이전 뒤에도 같은 값이어야 한다.
 * 경로 표를 반복해 각 화면의 레벨 1 제목이 표준(28px / 줄 높이 36px / 600 / 자간 -0.7px)인지 본다.
 * 화면마다 필요한 API 모킹은 기존 fixture 를 그대로 쓴다.
 */

/** 표준 h1 의 계산된 스타일 */
const STANDARD_H1 = {
  'font-size': '28px',
  'line-height': '36px',
  'font-weight': '600',
  'letter-spacing': '-0.7px',
} as const;

/**
 * 첫 진입 준비 대기(WD-878 패턴) — 병렬 부하 중에는 lazy 라우트의 첫 변환·렌더가 기본 10초를 넘길 수 있어
 * h1 을 30초까지 기다리고, 그만큼 테스트 예산도 늘린다.
 */
const READY_WAIT_MS = 30_000;

/** 관리자만 여는 화면의 준비 — 관리자로 인증한 뒤 그 화면의 모킹을 건다 */
const asAdmin = (setup: (page: Page) => Promise<unknown>) => async (page: Page) => {
  await setupAdminAuth(page);
  await setup(page);
};

/** h1 의 계산된 스타일이 표준인지 본다 */
async function expectStandardH1(h1: Locator) {
  for (const [prop, value] of Object.entries(STANDARD_H1)) {
    await expect(h1, prop).toHaveCSS(prop, value);
  }
}

/** 점검할 화면 — 경로, h1 이름, 그 화면이 뜨는 데 필요한 모킹 */
const ROUTES: { path: string; name: string; setup?: (page: Page) => Promise<void> }[] = [
  { path: '/', name: '홈' },
  { path: '/profile', name: '내 프로필' },
  {
    path: '/settings/channels',
    name: '알림 채널 설정',
    setup: (page) => mockApi(page, 'GET', '/api/v1/channels/settings', []),
  },
  {
    path: '/data/categories',
    name: '카테고리 관리',
    setup: (page) => mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories()),
  },
  { path: '/data/datasets', name: '데이터셋 관리', setup: setupDatasetMocks },
  {
    path: '/data/datasets/new',
    name: '새 데이터셋',
    // 생성 화면은 쓰기 권한이 있어야 열린다
    setup: async (page) => {
      await setupDatasetMocks(page);
      await grantDatasetPermissions(page);
    },
  },
  { path: '/pipelines', name: '파이프라인 관리', setup: (page) => setupPipelineMocks(page) },
  { path: '/analytics/queries', name: '저장된 쿼리', setup: (page) => setupQueryListMocks(page) },
  { path: '/analytics/charts', name: '차트', setup: (page) => setupChartListMocks(page) },
  { path: '/analytics/dashboards', name: '대시보드', setup: (page) => setupDashboardListMocks(page) },
  {
    path: '/ai-insights/jobs',
    name: '스마트 작업',
    setup: async (page) => {
      await mockProactivePermissions(page);
      await setupJobListMocks(page);
    },
  },
  {
    path: '/ai-insights/jobs/new',
    name: '새 스마트 작업',
    setup: async (page) => {
      await mockProactivePermissions(page);
      await setupNewJobMocks(page);
    },
  },
  {
    path: '/ai-insights/reports',
    name: '리포트',
    setup: async (page) => {
      await mockProactivePermissions(page);
      await mockApi(page, 'GET', '/api/v1/proactive/reports', []);
      await mockApi(page, 'GET', '/api/v1/proactive/jobs', []);
      await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    },
  },
  {
    path: '/ai-insights/templates/new',
    name: '새 템플릿',
    setup: async (page) => {
      await mockProactivePermissions(page);
      await setupTemplateDetailMocks(page);
    },
  },
  {
    path: '/knowledge-graph/explore',
    name: '지식그래프',
    setup: asAdmin(setupOntologyMocks),
  },
  {
    path: '/knowledge-graph/review',
    name: 'AI 검수',
    setup: (page) => mockApi(page, 'GET', '/api/v1/graphrag/review-items', []),
  },
  {
    path: '/admin/settings',
    name: '설정',
    setup: asAdmin(setupSettingsMocks),
  },
  {
    path: '/admin/users',
    name: '사용자 관리',
    setup: asAdmin(setupUserListMocks),
  },
  {
    path: '/admin/roles',
    name: '역할 관리',
    setup: asAdmin(setupRoleListMocks),
  },
  {
    path: '/admin/audit-logs',
    name: '감사 로그',
    setup: asAdmin(setupAuditLogMocks),
  },
  {
    path: '/admin/api-connections',
    name: 'API 연결 관리',
    setup: asAdmin(setupApiConnectionListMocks),
  },
  {
    path: '/admin/users/1',
    name: '사용자 상세',
    setup: asAdmin(setupUserDetailMocks),
  },
  {
    path: '/admin/roles/1',
    name: '역할 상세',
    setup: asAdmin(setupRoleDetailMocks),
  },
  {
    path: '/admin/api-connections/1',
    name: 'API 연결 상세',
    setup: asAdmin(setupApiConnectionDetailMocks),
  },
];

test.describe('페이지 제목 h1 표준 타이포그래피 (WD-910)', () => {
  for (const { path, name, setup } of ROUTES) {
    test(`${path} 의 제목 「${name}」 이 표준 28px / 36px / 600 / -0.7px 이다`, async ({
      authenticatedPage: page,
    }) => {
      test.info().setTimeout(test.info().timeout + READY_WAIT_MS);
      await setup?.(page);
      await page.goto(path);

      const h1 = page.getByRole('heading', { level: 1, name, exact: true });
      await expect(h1).toBeVisible({ timeout: READY_WAIT_MS });
      await expectStandardH1(h1);
    });
  }

  test('데이터셋 상세 제목이 표준이고, 375px 에서 공백 없는 긴 이름도 뷰포트를 넘지 않는다', async ({
    authenticatedPage: page,
  }) => {
    test.info().setTimeout(test.info().timeout + READY_WAIT_MS);
    await setupDatasetDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/1', createDatasetDetail({ id: 1, name: LONG_UNBROKEN_NAME }));
    await page.setViewportSize({ width: 375, height: 800 });
    await page.goto('/data/datasets/1');

    const h1 = page.getByRole('heading', { level: 1, name: LONG_UNBROKEN_NAME, exact: true });
    await expect(h1).toBeVisible({ timeout: READY_WAIT_MS });
    await expectStandardH1(h1);
    // 긴 이름은 줄바꿈되어 제목의 오른쪽 끝이 뷰포트 안이고 페이지·본문이 가로로 넘치지 않는다
    // 줄바꿈·레이아웃이 자리 잡기 전에 한 번만 재면 흔들리므로 통과할 때까지 제한 시간 안에서 다시 잰다
    await expect(async () => {
      const box = await h1.boundingBox();
      expect(box).not.toBeNull();
      expect(box!.x + box!.width, '제목 오른쪽 끝이 뷰포트 밖').toBeLessThanOrEqual(375);
      await expectNoHorizontalOverflow(page);
    }).toPass({ timeout: 10_000 });
  });
});
