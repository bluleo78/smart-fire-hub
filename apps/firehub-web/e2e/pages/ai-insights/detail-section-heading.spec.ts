import type { Page } from '@playwright/test';

import { createJob, createJobExecution, createTemplate } from '../../factories/ai-insight.factory';
import {
  mockProactivePermissions,
  setupJobDetailMocks,
  setupTemplateDetailMocks,
} from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 스마트 작업 상세(개요·모니터링 탭)·리포트 양식 상세 — 섹션 제목 위계 E2E 회귀 테스트 (WD-811)
 *
 * 섹션 제목이 h3 + text-sm(14px) 이라 본문 글자와 크기가 같아 위계가 보이지 않았다.
 * 데이터셋 상세(DatasetInfoTab)와 같이 h2 + text-xl(20px) 로 통일했는지 검증한다.
 */

test.beforeEach(async ({ authenticatedPage: page }) => {
  await mockProactivePermissions(page);
});

/** 이름마다 h2 제목이 20px(heading-card)로 그려지는지 확인한다 — toHaveCSS 가 요소가 나타날 때까지 기다린다. */
async function expectSectionHeadings(page: Page, names: string[]) {
  for (const name of names) {
    await expect(page.getByRole('heading', { level: 2, name, exact: true })).toHaveCSS('font-size', '20px');
  }
}

/** 공용 작업 상세 모킹 위에 lastExecution 이 있는 작업을 덮어 「마지막 실행 상태」 섹션도 그려지게 한다(나중 등록이 우선). */
async function mockJobDetailWithLastExecution(page: Page) {
  await setupJobDetailMocks(page, 1);
  await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', createJob({ id: 1, lastExecution: createJobExecution() }));
}

test.describe('상세 섹션 제목 위계 (WD-811)', () => {
  test('스마트 작업 개요 탭 섹션 제목이 h2 20px 이다 (WD-811)', async ({ authenticatedPage: page }) => {
    await mockJobDetailWithLastExecution(page);
    await page.goto('/ai-insights/jobs/1');

    await expectSectionHeadings(page, ['기본 정보', '실행 주기', '분석 프롬프트', '전달 채널', '마지막 실행 상태']);
  });

  test('스마트 작업 모니터링 탭 섹션 제목이 h2 20px 이다 (WD-811)', async ({ authenticatedPage: page }) => {
    await mockJobDetailWithLastExecution(page);
    await page.goto('/ai-insights/jobs/1?tab=monitoring');

    await expectSectionHeadings(page, ['이상 탐지', '최근 이상 탐지']);
  });

  test('리포트 양식 상세 섹션 제목이 보기·편집 모드 모두 h2 20px 이다 (WD-811)', async ({
    authenticatedPage: page,
  }) => {
    // 편집 버튼은 builtin: false 양식에만 보여, 공용 모킹 위에 커스텀 양식을 덮는다
    await setupTemplateDetailMocks(page, 4);
    const template = createTemplate({ id: 4, name: '편집용 템플릿', builtin: false });
    await mockApi(page, 'GET', '/api/v1/proactive/templates/4', template);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
    await page.goto('/ai-insights/templates/4');

    // 보기 모드
    await expectSectionHeadings(page, ['섹션 구조', '섹션 구조 미리보기']);
    await expect(page.getByRole('heading', { level: 3 })).toHaveCount(0);

    // 편집 모드 — 빌더 왼쪽 「섹션 구조」 제목도 같은 h2 20px
    await page.getByRole('button', { name: '편집' }).click();
    await expect(page.getByRole('tab', { name: '빌더' })).toBeVisible();
    await expectSectionHeadings(page, ['섹션 구조']);
    await expect(page.getByRole('heading', { level: 3 })).toHaveCount(0);
  });
});
