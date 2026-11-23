import type { Page } from '@playwright/test';

import { createJob, createTemplate, createTemplateSection } from '../../factories/ai-insight.factory';
import {
  mockProactivePermissions,
  setupJobDetailMocks,
  setupNewJobMocks,
} from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 스마트 작업 상세 ↔ 새 작업 — 같은 화면(컴포넌트)이 재사용될 때 편집 상태를 다시 잡는지 검증 (WD-996)
 * - 새 작업 폼에서 만든 뒤 상세로 가고, 브라우저 뒤로/앞으로로 오가도 읽기/편집 모드가 경로에 맞아야 한다.
 */
test.beforeEach(async ({ authenticatedPage: page }) => {
  await mockProactivePermissions(page);
});

/** 새 작업 폼에서 필수 칸을 채워 「만들기」 → 상세(id=1) 읽기 모드까지 간다. */
async function createAndLandOnDetail(page: Page) {
  await setupNewJobMocks(page);
  await setupJobDetailMocks(page, 1);
  await mockApi(page, 'POST', '/api/v1/proactive/jobs', createJob({ id: 1 }));

  await page.goto('/ai-insights/jobs/new');
  await page.locator('#job-name').fill('새 작업');
  await page.locator('#mode-manual').click();
  await page.locator('#job-prompt').fill('프롬프트');
  await page.getByRole('button', { name: '만들기', exact: true }).click();
  await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
  await expect(page.getByRole('button', { name: '편집', exact: true })).toBeVisible();
}

test.describe('스마트 작업 — 상세 ↔ 새 작업 화면 재사용 (WD-996)', () => {
  test('상세에서 뒤로 가 같은 화면 안 /jobs/new 로 오면 만들기 버튼과 빈 편집 칸이 보인다', async ({
    authenticatedPage: page,
  }) => {
    await createAndLandOnDetail(page);

    await page.goBack();

    await expect(page).toHaveURL(/\/ai-insights\/jobs\/new$/);
    await expect(page.getByRole('button', { name: '만들기', exact: true })).toBeVisible();
    const name = page.locator('#job-name');
    await expect(name).toBeEditable();
    await expect(name).toHaveValue('');
  });

  test('새 작업 폼에서 앞으로 가 상세로 오면 읽기 모드다', async ({ authenticatedPage: page }) => {
    await createAndLandOnDetail(page);
    await page.goBack();
    await expect(page.getByRole('button', { name: '만들기', exact: true })).toBeVisible();

    await page.locator('#job-name').fill('쓰다 만 이름');
    // 양성 단언: 이름을 입력하면 미저장 표시가 실제로 보여야 아래 0개 단언이 의미가 있다
    await expect(page.getByText('미저장 변경사항')).toBeVisible();
    await page.goForward();
    // 입력한 게 있어 가드의 이탈 확인 창이 뜬다 — 「이탈」로 확정해 상세로 이동한다
    await page.getByRole('alertdialog').getByRole('button', { name: '이탈' }).click();

    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
    await expect(page.getByRole('button', { name: '편집', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '만들기', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '저장', exact: true })).toHaveCount(0);
    await expect(page.getByText('미저장 변경사항')).toHaveCount(0);
  });

  /** 복제 시험용 목 — 원본(1)과 복사본(2) 상세를 준비한다. */
  async function setupCloneMocks(page: Page) {
    const copy = createJob({ id: 2, name: '매일 현황 리포트 (복사본)', enabled: false });
    await setupJobDetailMocks(page, 1);
    await mockApi(page, 'POST', '/api/v1/proactive/jobs', copy);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/2', copy);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/2/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/2/anomaly-events', []);
  }

  test('복제 뒤 새 작업 상세는 편집 모드다', async ({ authenticatedPage: page }) => {
    await setupCloneMocks(page);

    await page.goto('/ai-insights/jobs/1');
    await page.getByRole('button', { name: '복제' }).click();

    await expect(page).toHaveURL(/\/ai-insights\/jobs\/2\?tab=overview$/);
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeVisible();
  });

  test('복제 뒤 뒤로 가면 원본 작업은 읽기 모드다', async ({ authenticatedPage: page }) => {
    await setupCloneMocks(page);

    await page.goto('/ai-insights/jobs/1');
    await page.getByRole('button', { name: '복제' }).click();
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeVisible();

    await page.goBack();

    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
    await expect(page.getByRole('button', { name: '편집', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '저장', exact: true })).toHaveCount(0);
  });

  test('목표 기반 모드로 바꿔 만든 뒤 뒤로 가 새 작업으로 오면 기본 모드로 돌아온다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewJobMocks(page);
    await setupJobDetailMocks(page, 1);
    await mockApi(page, 'POST', '/api/v1/proactive/templates', createTemplate({ id: 77 }));
    // 템플릿 자동 생성 API — 정해진 섹션 구성으로 응답한다
    await mockApi(page, 'POST', '/api/v1/proactive/templates/generate', { sections: [createTemplateSection()] });
    const unmocked: string[] = [];
    // 모킹 안 된 /api 요청은 dev 서버 프록시가 5xx 로 돌려준다 — 그런 응답이 없는지 끝에서 확인한다
    page.on('response', (res) => {
      if (res.url().includes('/api/') && res.status() >= 500) unmocked.push(res.url());
    });
    await mockApi(page, 'POST', '/api/v1/proactive/jobs', createJob({ id: 1 }));
    await page.goto('/ai-insights/jobs/new');
    await page.locator('#job-name').fill('새 작업');
    await page.locator('#mode-goal').click();
    await expect(page.locator('#mode-goal')).toBeChecked();
    await page.locator('#business-question').fill('이번 주 화재 현황은 어떤가요');
    await page.getByRole('button', { name: '템플릿 자동 생성' }).click();
    await page.getByRole('button', { name: '만들기', exact: true }).click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
    // 상세가 실제로 그려진 뒤에 뒤로 간다 — URL 만 바뀐 채 곧바로 돌아가면 화면은 상세를 거치지 않아 재사용 상황이 아니다
    await expect(page.getByRole('button', { name: '편집', exact: true })).toBeVisible();

    await page.goBack();

    await expect(page).toHaveURL(/\/ai-insights\/jobs\/new$/);
    await expect(page.locator('#mode-manual')).toBeChecked();
    expect(unmocked).toEqual([]);
  });

  test('복제 뒤 취소로 읽기 모드가 된 다음 뒤로·앞으로 가도 복사본은 읽기 모드다', async ({
    authenticatedPage: page,
  }) => {
    await setupCloneMocks(page);

    await page.goto('/ai-insights/jobs/1');
    await page.getByRole('button', { name: '복제' }).click();
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '취소', exact: true }).click();
    await expect(page.getByRole('button', { name: '편집', exact: true })).toBeVisible();

    await page.goBack();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
    await page.goForward();

    await expect(page).toHaveURL(/\/ai-insights\/jobs\/2/);
    await expect(page.getByRole('button', { name: '편집', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '저장', exact: true })).toHaveCount(0);
  });
  test('저장 요청 중 뒤로 가 /jobs/new 로 오면 늦게 온 저장 응답이 새 작업 폼을 읽기 모드로 바꾸지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await createAndLandOnDetail(page);
    // 저장(PUT) 응답을 붙잡아 두었다가 화면을 옮긴 뒤 돌려준다
    let releaseSave: () => void = () => {};
    const saveHeld = new Promise<void>((resolve) => {
      releaseSave = resolve;
    });
    await page.route('**/api/v1/proactive/jobs/1', async (route) => {
      if (route.request().method() !== 'PUT') return route.fallback();
      await saveHeld;
      await route.fulfill({ json: createJob({ id: 1, name: '바꾼 이름' }) });
    });

    await page.getByRole('button', { name: '편집', exact: true }).click();
    await page.locator('#job-name').fill('바꾼 이름');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await page.goBack();
    // 미저장 표시가 남아 있으면 이탈 확인 창이 뜬다 — 「이탈」로 확정한다
    const leave = page.getByRole('alertdialog').getByRole('button', { name: '이탈' });
    if (await leave.isVisible().catch(() => false)) await leave.click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/new$/);
    // 저장 요청이 도는 동안은 같은 화면이라 「만드는 중...」으로 잠긴다 — 응답이 온 뒤를 본다
    await expect(page.getByRole('heading', { name: '새 스마트 작업' })).toBeVisible();

    releaseSave();
    await expect(page.getByText('작업이 저장되었습니다.')).toBeVisible();

    await expect(page.getByRole('button', { name: '만들기', exact: true })).toBeVisible();
    await expect(page.locator('#job-name')).toBeEditable();
  });
});
