/**
 * 스마트 작업 — 공백만 입력한 작업 이름·분석 프롬프트 막기 E2E (WD-500)
 *
 * 결함: 이름·프롬프트를 공백만으로 채우면 스키마가 길이만 봐 통과했거나, 「생성/저장」이 꺼져도 칸 아래 문구가 없어 이유를 알 수 없었다.
 * 이제 공백만 값은 빈 값과 같이 칸 아래 한글 문구로 안내하고 요청을 보내지 않으며, 앞뒤 공백은 잘라 저장한다.
 */

import type { Page } from '@playwright/test';

import type { CreateProactiveJobRequest } from '@/api/proactive';

import { createJob, createTemplate } from '../../factories/ai-insight.factory';
import { mockProactivePermissions, setupJobDetailMocks, setupNewJobMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

const NAME_REQUIRED = '작업 이름을 입력하세요';
const PROMPT_REQUIRED = '분석 프롬프트를 입력하세요';

/** 작업 생성(POST)·수정(PUT) 요청을 모두 캡처한다 — 「요청이 나가지 않음」을 셀 수 있게 한다. */
async function captureJobWrites(page: Page, jobId = 1) {
  const posts: CreateProactiveJobRequest[] = [];
  const puts: unknown[] = [];
  await page.route(
    (url) => url.pathname === '/api/v1/proactive/jobs',
    (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      posts.push(route.request().postDataJSON() as CreateProactiveJobRequest);
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(createJob({ id: 99, name: '생성된 작업' })),
      });
    },
  );
  await page.route(
    (url) => url.pathname === `/api/v1/proactive/jobs/${jobId}`,
    (route) => {
      if (route.request().method() !== 'PUT') return route.fallback();
      puts.push(route.request().postDataJSON());
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ updatedAt: '2026-11-06T10:00:00.000000' }),
      });
    },
  );
  return { posts, puts };
}

const createButton = (page: Page) => page.getByRole('button', { name: '생성', exact: true });
const saveButton = (page: Page) => page.getByRole('button', { name: '저장', exact: true });

test.describe('스마트 작업 — 공백만 입력한 이름·프롬프트 (WD-500)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    // 생성·편집 버튼은 쓰기 권한이 있을 때만 보인다(WD-133)
    await mockProactivePermissions(page);
  });

  test('새 작업: 두 칸을 공백만으로 채우면 칸 아래 문구가 보이고 「생성」이 꺼지며 요청이 나가지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewJobMocks(page);
    const writes = await captureJobWrites(page);
    await page.goto('/ai-insights/jobs/new');

    // 새 작업은 수동 모드로 시작한다 — 이름·프롬프트 칸이 바로 보인다
    await page.locator('#job-name').fill('   ');
    await page.locator('#job-prompt').fill('   ');
    await page.locator('#job-prompt').blur();

    // 문구가 각 칸 바로 아래(같은 묶음 안)에 보인다
    await expect(page.locator('#job-name').locator('..').getByText(NAME_REQUIRED)).toBeVisible();
    await expect(page.locator('#job-prompt').locator('..').getByText(PROMPT_REQUIRED)).toBeVisible();
    await expect(createButton(page)).toBeDisabled();

    // 정상 값을 넣으면 문구가 사라지고 「생성」이 다시 켜진다
    await page.locator('#job-name').fill('일일 리포트');
    await page.locator('#job-prompt').fill('어제 결과를 요약하세요');
    await expect(page.getByText(NAME_REQUIRED)).toHaveCount(0);
    await expect(page.getByText(PROMPT_REQUIRED)).toHaveCount(0);
    await expect(createButton(page)).toBeEnabled();
    // 정상 값으로 「생성」하면 그때 처음으로 요청이 한 번 나간다 — 공백만이던 동안은 나가지 않았다
    await createButton(page).click();
    await expect.poll(() => writes.posts.length).toBe(1);
  });

  test('새 작업: 탭·줄바꿈만 있는 프롬프트도 빈 값으로 막는다', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    const writes = await captureJobWrites(page);
    await page.goto('/ai-insights/jobs/new');

    await page.locator('#job-name').fill('일일 리포트');
    await page.locator('#job-prompt').fill('\t\n \n');
    await expect(page.getByText(PROMPT_REQUIRED)).toBeVisible();
    await expect(createButton(page)).toBeDisabled();
    expect(writes.posts).toHaveLength(0);
  });

  test('새 작업: 앞뒤 공백이 있는 값으로 생성하면 잘라 낸 이름·프롬프트가 전송된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewJobMocks(page);
    const writes = await captureJobWrites(page);
    await page.goto('/ai-insights/jobs/new');

    await page.locator('#job-name').fill('  일일 리포트  ');
    await page.locator('#job-prompt').fill('\n  어제 결과를 요약하세요  \n');
    await expect(createButton(page)).toBeEnabled();
    await createButton(page).click();

    await expect.poll(() => writes.posts.length).toBe(1);
    expect(writes.posts[0]).toMatchObject({ name: '일일 리포트', prompt: '어제 결과를 요약하세요' });
  });

  test('목표 기반: 이름이 공백만이면 비었다고 보고 질문으로 자동 이름을 채워 바로 생성된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewJobMocks(page);
    // 목표 기반 생성은 섹션 구성을 리포트 양식으로 먼저 저장한다(WD-297)
    await mockApi(page, 'POST', '/api/v1/proactive/templates', createTemplate({ id: 77 }));
    const writes = await captureJobWrites(page);
    await page.goto('/ai-insights/jobs/new');

    await page.locator('#job-name').fill('   ');
    await page.locator('#mode-goal').click();
    await page.getByLabel('비즈니스 질문').fill('지난주 대비 화재 건수 변화는?');
    await page.getByRole('button', { name: '템플릿 자동 생성' }).click();

    // 공백만 이름은 자동 이름으로 바뀌고 문구 없이 「생성」이 켜진다(전에는 공백이 남아 자동 이름을 건너뛰었다)
    await expect(page.locator('#job-name')).toHaveValue('지난주 대비 화재 건수 변화는?');
    await expect(page.getByText(NAME_REQUIRED)).toHaveCount(0);
    await expect(createButton(page)).toBeEnabled();
    await createButton(page).click();

    await expect.poll(() => writes.posts.length).toBe(1);
    expect(writes.posts[0]).toMatchObject({ name: '지난주 대비 화재 건수 변화는?', templateId: 77 });
  });

  test('기존 작업 편집: 이름을 공백만으로 바꾸면 문구가 보이고 「저장」이 꺼지며 PUT 이 나가지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupJobDetailMocks(page, 1);
    const writes = await captureJobWrites(page, 1);
    await page.goto('/ai-insights/jobs/1');
    await expect(page.getByRole('heading', { name: '매일 현황 리포트' })).toBeVisible();

    await page.getByRole('button', { name: '편집' }).click();
    await expect(page.locator('#job-name')).toHaveValue('매일 현황 리포트');
    await page.locator('#job-name').fill('   ');

    await expect(page.locator('#job-name').locator('..').getByText(NAME_REQUIRED)).toBeVisible();
    await expect(saveButton(page)).toBeDisabled();

    // 이름을 되살리면 저장할 수 있고, 앞뒤 공백은 잘라 보낸다
    await page.locator('#job-name').fill(' 고친 이름 ');
    await expect(page.getByText(NAME_REQUIRED)).toHaveCount(0);
    await expect(saveButton(page)).toBeEnabled();
    await saveButton(page).click();
    await expect.poll(() => writes.puts.length).toBe(1);
    expect(writes.puts[0]).toMatchObject({ name: '고친 이름' });
  });
});
