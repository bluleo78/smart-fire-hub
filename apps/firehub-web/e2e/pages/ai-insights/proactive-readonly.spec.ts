import type { Page } from '@playwright/test';

import { createJob, createTemplate } from '../../factories/ai-insight.factory';
import {
  mockProactivePermissions,
  setupJobDetailMocks,
  setupJobListMocks,
  setupNewJobMocks,
  setupTemplateDetailMocks,
  setupTemplateListMocks,
} from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 스마트 작업·리포트 양식 조회 전용 사용자 E2E (WD-133).
 * - 서버는 생성·수정·삭제·복제·지금 실행을 proactive:write 로 막는다. 기본 USER 역할은 proactive:read 만 있어,
 *   예전에는 버튼이 다 보이고 폼을 끝까지 채운 뒤에야 403 토스트를 받았다.
 * - 권한이 없으면 쓰기 진입점이 숨고(활성 스위치는 상태 표시라 비활성으로 남는다) 안내 한 줄이 보여야 한다.
 */

// 작업 화면은 「끄기·삭제는 지금 권한으로도 된다」를 덧붙인다 (WD-192) — 쓰기 권한 없는 작성자에게 버튼이 둘만 남는 이유
const JOBS_NOTICE =
  '스마트 작업을 만들거나 바꾸려면 스마트 작업 쓰기 권한이 필요합니다. 관리자에게 권한을 요청하세요. 내 작업을 끄거나 삭제하는 것은 지금 권한으로도 할 수 있습니다.';
const TEMPLATES_NOTICE = '리포트 양식을 만들거나 바꾸려면 스마트 작업 쓰기 권한이 필요합니다. 관리자에게 권한을 요청하세요.';

/**
 * /api/v1/proactive/* 로 나간 쓰기 요청(GET 이외)을 기록한다 — 조회 전용 화면에서 어떤 조작도 서버 쓰기로 이어지지 않음을 단언한다.
 * 기록만 하고 fallback 하므로 다른 mockApi 응답은 그대로 쓰인다.
 */
async function recordProactiveWrites(page: Page): Promise<string[]> {
  const writes: string[] = [];
  await page.route(
    (url) => url.pathname.startsWith('/api/v1/proactive/'),
    (route) => {
      const method = route.request().method();
      if (method !== 'GET') writes.push(`${method} ${new URL(route.request().url()).pathname}`);
      return route.fallback();
    },
  );
  return writes;
}

test.describe('스마트 작업 — 쓰기 권한 없는 사용자 (WD-133)', () => {
  test('작업 목록에서 추가·복제·지금 실행이 숨고 활성 스위치는 끄기만 된다 (WD-192)', async ({ authenticatedPage: page }) => {
    // 조회 전용 사용자에게 보이는 작업은 모두 자기 작업이다(서버가 작성자 한정) — 쓰기 권한을 잃은 작성자다.
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [
      createJob({ id: 1, name: '잡 1', enabled: true }),
      createJob({ id: 2, name: '잡 2', enabled: false }),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    // 다른 setup 헬퍼보다 뒤에 등록해야 조회 전용 권한이 유지된다
    await mockProactivePermissions(page, { canWrite: false });
    const disableCapture = await mockApi(page, 'POST', '/api/v1/proactive/jobs/1/disable', {}, { capture: true });
    const writes = await recordProactiveWrites(page);

    await page.goto('/ai-insights/jobs');

    // 목록 자체는 보인다(조회는 가능)
    const table = page.getByRole('table', { name: '스마트 작업 목록' });
    await expect(table.getByText('잡 1')).toBeVisible();
    await expect(table.getByText('잡 2')).toBeVisible();
    await expect(page.getByTestId('jobs-readonly-notice')).toHaveText(JOBS_NOTICE);

    // 쓰기 진입점이 없다
    await expect(page.getByRole('button', { name: '작업 추가' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '복제' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '지금 실행' })).toHaveCount(0);

    // 꺼진 작업은 켤 수 없다 — 억지로 눌러도 상태가 바뀌지 않고, 클릭이 행으로 새어 상세로 이동하지도 않는다
    const offSwitch = page.getByRole('switch', { name: '잡 2 활성화' });
    await expect(offSwitch).toBeDisabled();
    await offSwitch.click({ force: true });
    await expect(offSwitch).not.toBeChecked();
    await expect(page).toHaveURL(/\/ai-insights\/jobs$/);
    expect(writes).toEqual([]);

    // 켜진 작업은 끌 수 있다 — 쓰기 권한이 필요한 PUT 이 아니라 끄기 전용 POST 로 간다
    const onSwitch = page.getByRole('switch', { name: '잡 1 활성화' });
    await expect(onSwitch).toBeEnabled();
    await onSwitch.click();
    await disableCapture.waitForRequest();
    expect(writes).toEqual(['POST /api/v1/proactive/jobs/1/disable']);
  });

  test('작업이 없을 때 빈 상태에 「첫 작업 만들기」 대신 안내만 보인다', async ({ authenticatedPage: page }) => {
    await setupJobListMocks(page, 0);
    await mockProactivePermissions(page, { canWrite: false });

    await page.goto('/ai-insights/jobs');

    await expect(page.getByText('스마트 작업 없음')).toBeVisible();
    await expect(page.getByTestId('jobs-readonly-notice')).toHaveText(JOBS_NOTICE);
    await expect(page.getByRole('button', { name: '첫 작업 만들기' })).toHaveCount(0);
  });

  test('작업 상세에서 복제·지금 실행·편집이 숨고 끄기·삭제만 보인다 (WD-192)', async ({ authenticatedPage: page }) => {
    await setupJobDetailMocks(page, 1);
    await mockProactivePermissions(page, { canWrite: false });
    const writes = await recordProactiveWrites(page);

    await page.goto('/ai-insights/jobs/1');

    await expect(page.getByRole('heading', { name: '매일 현황 리포트' })).toBeVisible();
    await expect(page.getByTestId('job-readonly-notice')).toHaveText(JOBS_NOTICE);
    for (const name of ['복제', '지금 실행', '편집']) {
      await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0);
    }
    // 쓰기 권한을 잃은 작성자도 자기 작업은 정리할 수 있다
    await expect(page.getByRole('button', { name: '끄기', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '삭제', exact: true })).toBeVisible();
    // 조회 탭은 그대로 쓸 수 있다
    await page.getByRole('tab', { name: '실행 이력' }).click();
    await expect(page).toHaveURL(/tab=executions/);
    expect(writes).toEqual([]);
  });

  test('새 작업 주소로 직접 들어오면 폼 대신 안내가 보인다', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', []);
    await mockProactivePermissions(page, { canWrite: false });

    await page.goto('/ai-insights/jobs/new');

    await expect(page.getByTestId('job-readonly-notice')).toHaveText(JOBS_NOTICE);
    // 채워도 저장할 수 없는 폼은 아예 그려지지 않는다
    await expect(page.getByLabel('작업 이름 *')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '생성' })).toHaveCount(0);

    await page.getByRole('button', { name: '목록으로 돌아가기' }).click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs$/);
  });

  test('리포트가 없고 작업도 없으면 「스마트 작업 만들기」 CTA 를 보이지 않는다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/reports', []);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', []);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockProactivePermissions(page, { canWrite: false });

    await page.goto('/ai-insights/reports');

    await expect(page.getByText('스마트 작업을 만들면 생성된 리포트가 여기에 쌓입니다.')).toBeVisible();
    await expect(page.getByRole('button', { name: '스마트 작업 만들기' })).toHaveCount(0);
  });
});

test.describe('리포트 양식 — 쓰기 권한 없는 사용자 (WD-133)', () => {
  test('양식 탭에서 「템플릿 추가」가 숨고 안내가 보인다', async ({ authenticatedPage: page }) => {
    await setupTemplateListMocks(page);
    await mockProactivePermissions(page, { canWrite: false });

    await page.goto('/ai-insights/reports?tab=templates');

    // 템플릿 목록은 보인다
    await expect(page.getByText('일일 현황 리포트')).toBeVisible();
    await expect(page.getByText('주간 통계 리포트')).toBeVisible();
    await expect(page.getByTestId('templates-readonly-notice')).toHaveText(TEMPLATES_NOTICE);
    await expect(page.getByRole('button', { name: '템플릿 추가' })).toHaveCount(0);
  });

  test('커스텀 양식이 없을 때 「첫 템플릿 만들기」를 보이지 않는다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [createTemplate({ id: 1, builtin: true })]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockProactivePermissions(page, { canWrite: false });

    await page.goto('/ai-insights/reports?tab=templates');

    await expect(page.getByText('커스텀 템플릿 없음')).toBeVisible();
    await expect(page.getByRole('button', { name: '첫 템플릿 만들기' })).toHaveCount(0);
  });

  test('커스텀 양식 상세에서 복제·편집·삭제가 숨고 안내가 보인다', async ({ authenticatedPage: page }) => {
    const template = createTemplate({ id: 2, name: '주간 통계 리포트', builtin: false });
    await setupTemplateDetailMocks(page, 2);
    await mockApi(page, 'GET', '/api/v1/proactive/templates/2', template);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
    await mockProactivePermissions(page, { canWrite: false });
    const writes = await recordProactiveWrites(page);

    await page.goto('/ai-insights/templates/2');

    await expect(page.getByRole('heading', { name: '주간 통계 리포트' })).toBeVisible();
    await expect(page.getByText('커스텀', { exact: true })).toBeVisible();
    await expect(page.getByTestId('template-readonly-notice')).toHaveText(TEMPLATES_NOTICE);
    for (const name of ['복제', '편집', '삭제']) {
      await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0);
    }
    expect(writes).toEqual([]);
  });

  test('새 양식 주소로 직접 들어오면 빌더 대신 안내가 보인다', async ({ authenticatedPage: page }) => {
    await setupTemplateListMocks(page);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', []);
    await mockProactivePermissions(page, { canWrite: false });

    await page.goto('/ai-insights/templates/new');

    await expect(page.getByTestId('template-readonly-notice')).toHaveText(TEMPLATES_NOTICE);
    await expect(page.getByLabel('이름')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '생성' })).toHaveCount(0);

    await page.getByRole('button', { name: '목록으로 돌아가기' }).click();
    await expect(page).toHaveURL(/\/ai-insights\/reports\?tab=templates$/);
  });
});

test.describe('쓰기 권한 있는 사용자 (WD-133 대조군)', () => {
  test('작업 목록·상세에 쓰기 버튼이 보이고 조회 전용 안내는 없다', async ({ authenticatedPage: page }) => {
    await setupJobListMocks(page, 1);
    await setupJobDetailMocks(page, 1);
    await mockProactivePermissions(page, { canWrite: true });

    await page.goto('/ai-insights/jobs');
    await expect(page.getByRole('button', { name: '작업 추가' })).toBeVisible();
    await expect(page.getByRole('button', { name: '복제' })).toBeVisible();
    await expect(page.getByRole('button', { name: '지금 실행' })).toBeVisible();
    await expect(page.getByRole('switch')).toBeEnabled();
    await expect(page.getByTestId('jobs-readonly-notice')).toHaveCount(0);

    await page.goto('/ai-insights/jobs/1');
    await expect(page.getByRole('heading', { name: '매일 현황 리포트' })).toBeVisible();
    for (const name of ['복제', '지금 실행', '편집', '삭제']) {
      await expect(page.getByRole('button', { name, exact: true })).toBeVisible();
    }
    // 쓰기 권한자는 헤더의 끄기 버튼 대신 편집·스위치로 다룬다(WD-192 끄기 버튼은 쓰기 권한 없는 작성자 전용)
    await expect(page.getByRole('button', { name: '끄기', exact: true })).toHaveCount(0);
    await expect(page.getByTestId('job-readonly-notice')).toHaveCount(0);
  });

  test('양식 탭·상세에 쓰기 버튼이 보이고 조회 전용 안내는 없다', async ({ authenticatedPage: page }) => {
    const template = createTemplate({ id: 2, name: '주간 통계 리포트', builtin: false });
    await setupTemplateListMocks(page);
    await mockApi(page, 'GET', '/api/v1/proactive/templates/2', template);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', []);
    await mockProactivePermissions(page, { canWrite: true });

    await page.goto('/ai-insights/reports?tab=templates');
    await expect(page.getByRole('button', { name: '템플릿 추가' })).toBeVisible();
    await expect(page.getByTestId('templates-readonly-notice')).toHaveCount(0);

    await page.goto('/ai-insights/templates/2');
    await expect(page.getByRole('heading', { name: '주간 통계 리포트' })).toBeVisible();
    for (const name of ['복제', '편집', '삭제']) {
      await expect(page.getByRole('button', { name, exact: true })).toBeVisible();
    }
    await expect(page.getByTestId('template-readonly-notice')).toHaveCount(0);
  });
});
