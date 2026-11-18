/**
 * 스마트 작업 새 폼 — 「만들기」가 꺼진 이유 안내 E2E (WD-812)
 *
 * 결함: 새 작업 폼은 이름·프롬프트가 비면 「만들기」가 꺼지지만 이유를 알려 주지 않았다.
 * 이제 비어 있는 칸 아래에 흐린 안내 문구가 보이고, 입력 칸에 aria-describedby 로 이어진다.
 * 오류 문구가 보이는 칸(공백만 입력)에는 안내를 그리지 않고, 기존 작업 수정 화면에는 안내가 없다.
 */

import { mockProactivePermissions, setupJobDetailMocks, setupNewJobMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

const NAME_HINT = '작업 이름을 입력하면 만들 수 있습니다.';
const PROMPT_HINT = '분석 프롬프트를 입력하면 만들 수 있습니다.';
const GOAL_HINT = '비즈니스 질문을 쓰고 「템플릿 자동 생성」을 누르면 만들 수 있습니다.';
const NAME_REQUIRED = '작업 이름을 입력하세요';

test.describe('스마트 작업 새 폼 — 「만들기」 비활성 안내 (WD-812)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await mockProactivePermissions(page);
  });

  test('새 작업: 열자마자 이름·프롬프트 아래 안내가 보이고 칸에 이어진다', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    await page.goto('/ai-insights/jobs/new');

    await expect(page.getByText(NAME_HINT)).toBeVisible();
    await expect(page.locator('#job-name')).toHaveAttribute('aria-describedby', /job-name-hint/);
    await expect(page.getByText(PROMPT_HINT)).toBeVisible();
    await expect(page.locator('#job-prompt')).toHaveAttribute('aria-describedby', /job-prompt-hint/);
    await expect(page.getByRole('button', { name: '만들기', exact: true })).toBeDisabled();
  });

  test('새 작업: 칸을 채우면 그 칸의 안내만 사라지고, 둘 다 채우면 만들기가 켜진다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewJobMocks(page);
    await page.goto('/ai-insights/jobs/new');

    await page.locator('#job-name').fill('일일 리포트');
    await expect(page.getByText(NAME_HINT)).toHaveCount(0);
    await expect(page.getByText(PROMPT_HINT)).toBeVisible();

    await page.locator('#job-prompt').fill('어제 결과를 요약하세요');
    await expect(page.getByText(NAME_HINT)).toHaveCount(0);
    await expect(page.getByText(PROMPT_HINT)).toHaveCount(0);
    await expect(page.getByRole('button', { name: '만들기', exact: true })).toBeEnabled();
  });

  test('새 작업: 공백만 넣으면 안내 대신 오류 문구만 보인다', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    await page.goto('/ai-insights/jobs/new');

    await page.locator('#job-name').fill('   ');
    await expect(page.getByText(NAME_REQUIRED)).toBeVisible();
    await expect(page.getByText(NAME_HINT)).toHaveCount(0);
  });

  test('새 작업 목표 모드: 프롬프트가 비면 질문 칸 아래 안내가 보이고 질문 칸에 이어진다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewJobMocks(page);
    await page.goto('/ai-insights/jobs/new');

    await page.locator('#mode-goal').click();
    await expect(page.getByText(GOAL_HINT)).toBeVisible();
    await expect(page.locator('#business-question')).toHaveAttribute('aria-describedby', /business-question-hint/);
    // 목표 모드에서는 이름만 써도 만들기가 켜지지 않으므로 이름 안내는 감춘다
    await expect(page.getByText(NAME_HINT)).toHaveCount(0);
  });

  test('새 작업 목표 모드: 「템플릿 자동 생성」 뒤에는 안내가 모두 사라지고 만들기가 켜진다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewJobMocks(page);
    await mockApi(page, 'POST', '/api/v1/proactive/templates/generate', {
      sections: [{ key: 'summary', type: 'text', label: '핵심 요약', instruction: '요약하세요.', required: true }],
    });
    await page.goto('/ai-insights/jobs/new');

    await page.locator('#mode-goal').click();
    await page.locator('#business-question').fill('지난주 대비 화재 건수 변화는?');
    await page.getByRole('button', { name: '템플릿 자동 생성' }).click();

    await expect(page.getByText('자동 생성된 템플릿 (1개 섹션)')).toBeVisible();
    await expect(page.getByText(GOAL_HINT)).toHaveCount(0);
    await expect(page.getByText(NAME_HINT)).toHaveCount(0);
    await expect(page.getByRole('button', { name: '만들기', exact: true })).toBeEnabled();
  });

  test('새 작업 목표 모드: 템플릿을 생성하는 동안에는 목표 안내도 이름 안내도 보이지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewJobMocks(page);
    // 응답을 늦춰 「생성 중...」 상태를 붙잡는다
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/templates/generate',
      async (route) => {
        await new Promise((resolve) => setTimeout(resolve, 1500));
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            sections: [{ key: 'summary', type: 'text', label: '핵심 요약', instruction: '요약하세요.', required: true }],
          }),
        });
      },
    );
    await page.goto('/ai-insights/jobs/new');

    await page.locator('#mode-goal').click();
    await page.locator('#business-question').fill('지난주 대비 화재 건수 변화는?');
    await page.getByRole('button', { name: '템플릿 자동 생성' }).click();

    // 생성 중 — 버튼이 「생성 중...」으로 꺼지고 목표 안내·이름 안내는 모두 감춰진다
    await expect(page.getByRole('button', { name: '생성 중...' })).toBeDisabled();
    await expect(page.getByText(GOAL_HINT)).toHaveCount(0);
    await expect(page.getByText(NAME_HINT)).toHaveCount(0);

    await expect(page.getByText('자동 생성된 템플릿 (1개 섹션)')).toBeVisible();
  });

  test('기존 작업 수정 화면에는 안내가 없다', async ({ authenticatedPage: page }) => {
    await setupJobDetailMocks(page, 1);
    await page.goto('/ai-insights/jobs/1');
    await expect(page.getByRole('heading', { name: '매일 현황 리포트' })).toBeVisible();
    await page.getByRole('button', { name: '편집' }).click();
    await expect(page.locator('#job-name')).toHaveValue('매일 현황 리포트');

    await expect(page.getByText(NAME_HINT)).toHaveCount(0);
    await expect(page.getByText(PROMPT_HINT)).toHaveCount(0);
    await expect(page.getByText(GOAL_HINT)).toHaveCount(0);
  });
});
