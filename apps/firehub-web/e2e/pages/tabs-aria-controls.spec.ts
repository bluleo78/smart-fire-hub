import type { Page } from '@playwright/test';

import { createCategories } from '../factories/dataset.factory';
import { createSynonymReviewItem } from '../factories/reviewItem.factory';
import { setupChartListMocks, setupDashboardListMocks, setupQueryListMocks } from '../fixtures/analytics.fixture';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetDetailMocks } from '../fixtures/dataset.fixture';
import { expectSelectedTabControlsPanel } from '../fixtures/tabs-a11y';

/**
 * 탭 버튼의 aria-controls 가 실제 탭 패널을 가리키는지(WD-514, axe aria-valid-attr-value · WCAG 4.1.2/1.3.1).
 *
 * Radix Tabs 의 TabsTrigger 만 쓰고 내용을 상태로 조건부 렌더하면, 트리거의 aria-controls(`…-content-<value>`)가
 * 없는 id 를 가리키고 role=tabpanel 도 없다. 선택된 탭은 존재하는 role=tabpanel 을 가리켜야 하고, 그 패널의
 * aria-labelledby 는 탭의 id 여야 한다. 비선택 탭(aria-selected=false)의 패널은 언마운트돼도 axe 가 예외로 둔다.
 */

/** 데이터셋 1 상세 화면에 필요한 권한·API 모킹 */
async function setupDetail(page: Page) {
    await grantDatasetPermissions(page);
    await setupDatasetDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
}

test.describe('탭 aria-controls → tabpanel 연결 (WD-514)', () => {
  test('데이터셋 상세 — 기본 탭과 전환한 탭 모두 실제 탭 패널을 가리킨다', async ({ authenticatedPage: page }) => {
    await setupDetail(page);
    await page.goto('/data/datasets/1');

    const infoTab = page.getByRole('tab', { name: '정보' });
    await expectSelectedTabControlsPanel(page, infoTab);
    // 패널은 하나만 그려진다(비활성 탭 내용은 이전처럼 언마운트)
    await expect(page.getByRole('tabpanel')).toHaveCount(1);

    await page.getByRole('tab', { name: '필드' }).click();
    await expectSelectedTabControlsPanel(page, page.getByRole('tab', { name: '필드' }));
    await expect(page).toHaveURL(/[?&]tab=columns/);

    await page.getByRole('tab', { name: '이력' }).click();
    await expectSelectedTabControlsPanel(page, page.getByRole('tab', { name: '이력' }));
    await expect(page.getByRole('tabpanel')).toHaveCount(1);
  });

  test('데이터셋 상세 — ?tab=columns 로 바로 열어도 선택 탭이 패널을 가리킨다', async ({ authenticatedPage: page }) => {
    await setupDetail(page);
    await page.goto('/data/datasets/1?tab=columns');

    await expectSelectedTabControlsPanel(page, page.getByRole('tab', { name: '필드' }));
  });

  test('쿼리 목록 — 필터 탭을 클릭·화살표 키로 바꿔도 선택 탭이 목록 패널을 가리킨다', async ({
    authenticatedPage: page,
  }) => {
    await setupQueryListMocks(page, 3);
    await page.goto('/analytics/queries');

    const mine = page.getByRole('tab', { name: '내 쿼리' });
    await expectSelectedTabControlsPanel(page, mine);
    // 목록 표가 그 탭 패널 안에 있다
    await expect(page.getByRole('tabpanel').getByRole('table', { name: '저장된 쿼리 목록' })).toBeVisible();

    await page.getByRole('tab', { name: '공유됨' }).click();
    await expectSelectedTabControlsPanel(page, page.getByRole('tab', { name: '공유됨' }));
    await expect(page.getByRole('tabpanel')).toHaveCount(1);

    // 키보드(Radix 자동 활성화) — 왼쪽 화살표로 「내 쿼리」로 돌아가도 연결이 유지된다
    await page.getByRole('tab', { name: '공유됨' }).focus();
    await page.keyboard.press('ArrowLeft');
    await expect(mine).toBeFocused();
    await expectSelectedTabControlsPanel(page, mine);

    // 필터 패널은 Tab 정지가 아니다 — 탭에서 Tab 을 누르면 이전처럼 바로 검색 입력으로 간다(보이지 않는 포커스 없음)
    await expect(page.getByRole('tabpanel')).toHaveAttribute('tabindex', '-1');
    await page.keyboard.press('Tab');
    await expect(page.getByPlaceholder('쿼리 검색...')).toBeFocused();
  });

  test('차트 목록 — 필터 탭 전환 전후 모두 선택 탭이 목록 패널을 가리킨다', async ({ authenticatedPage: page }) => {
    await setupChartListMocks(page, 2);
    await page.goto('/analytics/charts');

    await expectSelectedTabControlsPanel(page, page.getByRole('tab', { name: '내 차트' }));
    await page.getByRole('tab', { name: '공유됨' }).click();
    await expectSelectedTabControlsPanel(page, page.getByRole('tab', { name: '공유됨' }));
    await expect(page.getByRole('tabpanel').getByRole('table', { name: '차트 목록' })).toBeVisible();
  });

  test('대시보드 목록 — 필터 탭 전환 전후 모두 선택 탭이 목록 패널을 가리킨다', async ({ authenticatedPage: page }) => {
    await setupDashboardListMocks(page, 2);
    await page.goto('/analytics/dashboards');

    await expectSelectedTabControlsPanel(page, page.getByRole('tab', { name: '내 대시보드' }));
    await expect(page.getByRole('tabpanel').getByText('테스트 대시보드 1')).toBeVisible();

    await page.getByRole('tab', { name: '공유됨' }).click();
    await expectSelectedTabControlsPanel(page, page.getByRole('tab', { name: '공유됨' }));
    await expect(page.getByRole('tabpanel')).toHaveCount(1);
  });

  test('AI 검수 인박스 — 종류 필터 탭 전환 전후 모두 선택 탭이 표 패널을 가리킨다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/auth/me/permissions', ['dataset:read', 'dataset:write']);
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem()]);
    await page.goto('/knowledge-graph/review');

    await expectSelectedTabControlsPanel(page, page.getByRole('tab', { name: '전체' }));
    await expect(page.getByRole('tabpanel').getByTestId('review-inbox-table')).toBeVisible();

    await page.getByRole('tab', { name: '동의어' }).click();
    await expectSelectedTabControlsPanel(page, page.getByRole('tab', { name: '동의어' }));
  });
});
