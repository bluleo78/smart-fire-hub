/**
 * 스마트 작업·리포트 양식 이름 중복(409 DUPLICATE_NAME) E2E (WD-824)
 *
 * - 생성 폼(react-hook-form)은 서버 문구를 이름 칸 오류로 보이고 토스트는 띄우지 않는다.
 * - 복제는 서버 복제 API 없이 일반 생성 API 로 이름만 만들어 보내므로, 이미 복사본이 있으면 409 가 난다 —
 *   「(복사본)」 → 「(복사본 2)」 … 로 번호를 올려 다시 시도한다.
 * - 양식 커스텀 카드에 수정 일시가 보인다.
 */

import type { Page } from '@playwright/test';

import { createJob, createTemplate } from '../../factories/ai-insight.factory';
import { mockProactivePermissions, setupJobDetailMocks, setupNewJobMocks, setupTemplateDetailMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

const TEMPLATE_MSG = '이미 있는 리포트 양식 이름입니다. 다른 이름을 입력하세요.';
const JOB_MSG = '이미 있는 스마트 작업 이름입니다. 다른 이름을 입력하세요.';

const CLONE_EXHAUSTED_MSG = '복사본 이름을 정하지 못했습니다. 쓰지 않는 복사본을 지우거나 원본 이름을 바꾼 뒤 다시 복제하세요.';

const duplicateBody = (message: string) => JSON.stringify({ status: 409, error: 'Conflict', code: 'DUPLICATE_NAME', message });

/**
 * POST 를 가로채 앞의 `rejectFirst` 번은 409 DUPLICATE_NAME, 그 뒤는 `okBody` 로 응답한다. 받은 요청 본문을 모두 기록한다.
 */
async function mockPostRejectingFirst(page: Page, path: string, rejectFirst: number, message: string, okBody: unknown) {
  const bodies: { name: string }[] = [];
  await page.route(
    (url) => url.pathname === path,
    (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      bodies.push(route.request().postDataJSON() as { name: string });
      if (bodies.length <= rejectFirst) {
        return route.fulfill({ status: 409, contentType: 'application/json', body: duplicateBody(message) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(okBody) });
    },
  );
  return bodies;
}

test.beforeEach(async ({ authenticatedPage: page }) => {
  await mockProactivePermissions(page);
});

test.describe('이름 중복 409 — 스마트 작업·리포트 양식 (WD-824)', () => {
  test('새 양식: 서버 문구가 이름 칸 아래에 한 번 보이고 이동하지 않는다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    const bodies = await mockPostRejectingFirst(page, '/api/v1/proactive/templates', 99, TEMPLATE_MSG, {});
    await page.goto('/ai-insights/templates/new');

    await page.locator('#tpl-name').fill('겹치는 양식');
    await page.getByRole('button', { name: '만들기' }).click();

    await expect.poll(() => bodies.length).toBe(1);
    await expect(page.locator('#tpl-name').locator('../..').getByText(TEMPLATE_MSG)).toBeVisible();
    // 토스트로 같은 문구가 또 뜨지 않는다
    await expect(page.getByText(TEMPLATE_MSG)).toHaveCount(1);
    await expect(page).toHaveURL(/\/ai-insights\/templates\/new/);
  });

  test('새 작업: 서버 문구가 이름 칸 아래에 한 번 보인다', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    const bodies = await mockPostRejectingFirst(page, '/api/v1/proactive/jobs', 99, JOB_MSG, {});
    await page.goto('/ai-insights/jobs/new');

    await page.locator('#job-name').fill('겹치는 작업');
    await page.locator('#job-prompt').fill('어제 결과를 요약하세요');
    await page.getByRole('button', { name: '만들기', exact: true }).click();

    await expect.poll(() => bodies.length).toBe(1);
    await expect(page.locator('#job-name').locator('..').getByText(JOB_MSG)).toBeVisible();
    await expect(page.getByText(JOB_MSG)).toHaveCount(1);
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/new/);
  });

  test('목표 기반 새 작업: 자동 양식 이름이 겹치면 「양식 (2)」로 다시 저장해 연결하고 작업을 만든다', async ({ authenticatedPage: page }) => {
    // 작업을 지워도 양식은 남아 있어 같은 작업 이름을 다시 쓰면 사용자가 입력한 적 없는 양식 이름이 겹친다(WD-824)
    await setupNewJobMocks(page);
    const templateBodies = await mockPostRejectingFirst(page, '/api/v1/proactive/templates', 1, TEMPLATE_MSG, createTemplate({ id: 78 }));
    const jobBodies = await mockPostRejectingFirst(page, '/api/v1/proactive/jobs', 0, JOB_MSG, createJob({ id: 99, name: '주간 화재 추이' }));
    await page.goto('/ai-insights/jobs/new');

    await page.locator('#mode-goal').click();
    await page.getByLabel('비즈니스 질문').fill('주간 화재 추이');
    await page.getByRole('button', { name: '템플릿 자동 생성' }).click();
    await page.getByRole('button', { name: '만들기', exact: true }).click();

    await expect.poll(() => templateBodies.length).toBe(2);
    expect(templateBodies.map((b) => b.name)).toEqual(['주간 화재 추이 양식', '주간 화재 추이 양식 (2)']);
    await expect.poll(() => jobBodies.length).toBe(1);
    expect(jobBodies[0]).toMatchObject({ name: '주간 화재 추이', templateId: 78 });
    await expect(page).toHaveURL('/ai-insights/jobs/99');
    // 사용자가 정하지 않은 이름의 겹침이라 오류 문구를 보이지 않는다
    await expect(page.getByText(TEMPLATE_MSG)).toHaveCount(0);
  });

  test('양식 복제: 「(복사본)」이 겹치면 「(복사본 2)」로 다시 보내 성공한다', async ({ authenticatedPage: page }) => {
    await setupTemplateDetailMocks(page, 1);
    const created = createTemplate({ id: 10, name: '기본 리포트 템플릿 (복사본 2)', builtin: false });
    const bodies = await mockPostRejectingFirst(page, '/api/v1/proactive/templates', 1, TEMPLATE_MSG, created);
    await mockApi(page, 'GET', '/api/v1/proactive/templates/10', created);

    await page.goto('/ai-insights/templates/1');
    await expect(page.getByRole('heading', { name: '기본 리포트 템플릿' })).toBeVisible();
    await page.getByRole('button', { name: '복제' }).click();

    await expect(page).toHaveURL(/\/ai-insights\/templates\/10/);
    expect(bodies.map((b) => b.name)).toEqual(['기본 리포트 템플릿 (복사본)', '기본 리포트 템플릿 (복사본 2)']);
  });

  test('작업 복제: 「(복사본)」이 겹치면 「(복사본 2)」로 다시 보내 성공한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: 1, name: '잡 1', enabled: true })]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    const cloned = createJob({ id: 2, name: '잡 1 (복사본 2)', enabled: false });
    const bodies = await mockPostRejectingFirst(page, '/api/v1/proactive/jobs', 1, JOB_MSG, cloned);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/2', cloned);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/2/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);

    await page.goto('/ai-insights/jobs');
    await page.getByRole('button', { name: '복제' }).click();

    await expect(page).toHaveURL(/\/ai-insights\/jobs\/2/);
    expect(bodies.map((b) => b.name)).toEqual(['잡 1 (복사본)', '잡 1 (복사본 2)']);
    // 실패한 첫 시도는 사용자에게 보이지 않는다
    await expect(page.getByText(JOB_MSG)).toHaveCount(0);
  });

  test('양식 복제: 계속 겹쳐 시도 상한(20)을 넘기면 복사본 안내를 토스트로 보인다', async ({ authenticatedPage: page }) => {
    await setupTemplateDetailMocks(page, 1);
    const bodies = await mockPostRejectingFirst(page, '/api/v1/proactive/templates', 999, TEMPLATE_MSG, {});

    await page.goto('/ai-insights/templates/1');
    await page.getByRole('button', { name: '복제' }).click();

    // 서버 문구 대신 「쓰지 않는 복사본을 지우거나 원본 이름을 바꾸라」는 안내가 보인다
    await expect(page.getByText(CLONE_EXHAUSTED_MSG)).toBeVisible();
    await expect(page.getByText(TEMPLATE_MSG)).toHaveCount(0);
    expect(bodies).toHaveLength(20);
    expect(bodies[19].name).toBe('기본 리포트 템플릿 (복사본 20)');
  });

  test('양식 복제: 「(복사본)」~「(복사본 20)」이 있으면 목록을 읽어 「(복사본 21)」로 만든다', async ({ authenticatedPage: page }) => {
    await setupTemplateDetailMocks(page, 1);
    // 이미 있는 사본 이름 — 「(복사본)」, 「(복사본 2)」 ... 「(복사본 20)」
    const taken = ['기본 리포트 템플릿 (복사본)', ...Array.from({ length: 19 }, (_, i) => `기본 리포트 템플릿 (복사본 ${i + 2})`)];
    // 목록은 페이지 자체 조회와 복제 로더가 함께 쓰므로 원본 양식도 같이 돌려준다(뒤에 등록한 모킹이 앞선 것을 덮는다)
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [
      createTemplate({ id: 1 }),
      ...taken.map((name, i) => createTemplate({ id: 100 + i, name, builtin: false })),
    ]);
    const created = createTemplate({ id: 10, name: '기본 리포트 템플릿 (복사본 21)', builtin: false });
    const bodies: { name: string }[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/templates',
      (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        const { name } = route.request().postDataJSON() as { name: string };
        bodies.push({ name });
        if (taken.includes(name)) {
          return route.fulfill({ status: 409, contentType: 'application/json', body: duplicateBody(TEMPLATE_MSG) });
        }
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(created) });
      },
    );
    await mockApi(page, 'GET', '/api/v1/proactive/templates/10', created);

    await page.goto('/ai-insights/templates/1');
    await expect(page.getByRole('heading', { name: '기본 리포트 템플릿' })).toBeVisible();
    await page.getByRole('button', { name: '복제' }).click();

    await expect(page).toHaveURL(/\/ai-insights\/templates\/10/);
    expect(bodies.map((b) => b.name)).toEqual(['기본 리포트 템플릿 (복사본)', '기본 리포트 템플릿 (복사본 21)']);
    await expect(page.getByText(TEMPLATE_MSG)).toHaveCount(0);
    await expect(page.getByText(CLONE_EXHAUSTED_MSG)).toHaveCount(0);
  });

  test('작업 복제: 「(복사본)」~「(복사본 20)」이 있으면 목록을 읽어 「(복사본 21)」로 만든다', async ({ authenticatedPage: page }) => {
    const taken = ['잡 1 (복사본)', ...Array.from({ length: 19 }, (_, i) => `잡 1 (복사본 ${i + 2})`)];
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [
      createJob({ id: 1, name: '잡 1', enabled: true }),
      ...taken.map((name, i) => createJob({ id: 100 + i, name, enabled: false })),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    const cloned = createJob({ id: 2, name: '잡 1 (복사본 21)', enabled: false });
    const bodies: { name: string }[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/jobs',
      (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        const { name } = route.request().postDataJSON() as { name: string };
        bodies.push({ name });
        if (taken.includes(name)) {
          return route.fulfill({ status: 409, contentType: 'application/json', body: duplicateBody(JOB_MSG) });
        }
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(cloned) });
      },
    );
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/2', cloned);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/2/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);

    await page.goto('/ai-insights/jobs');
    // 목록에는 원본 외에 사본 행도 있어 「잡 1」 행의 복제 버튼만 누른다
    await page.getByRole('button', { name: '복제' }).first().click();

    await expect(page).toHaveURL(/\/ai-insights\/jobs\/2/);
    expect(bodies.map((b) => b.name)).toEqual(['잡 1 (복사본)', '잡 1 (복사본 21)']);
    await expect(page.getByText(JOB_MSG)).toHaveCount(0);
  });

  test('작업 수정: 이름을 겹치는 이름으로 바꾸면 이름 칸 아래에 보인다', async ({ authenticatedPage: page }) => {
    await setupJobDetailMocks(page, 1);
    // 수정 저장(PUT)이 409 DUPLICATE_NAME 으로 거부된다 — 이미 되는 동작(onError 의 applyDuplicateNameError)을 고정한다
    let putCount = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/jobs/1',
      (route) => {
        if (route.request().method() !== 'PUT') return route.fallback();
        putCount += 1;
        return route.fulfill({ status: 409, contentType: 'application/json', body: duplicateBody(JOB_MSG) });
      },
    );
    await page.goto('/ai-insights/jobs/1');

    await page.getByRole('button', { name: '편집' }).click();
    await page.locator('#job-name').fill('겹치는 이름');
    await page.getByRole('button', { name: '저장', exact: true }).click();

    await expect.poll(() => putCount).toBe(1);
    // 문구는 이름 칸 아래에 한 번만 보이고, 같은 문구의 토스트는 뜨지 않는다
    await expect(page.locator('#job-name').locator('..').getByText(JOB_MSG)).toBeVisible();
    await expect(page.getByText(JOB_MSG)).toHaveCount(1);
  });

  test('양식 목록: 커스텀 카드에 수정 일시가 보인다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [
      createTemplate({ id: 1, name: '기본 양식', builtin: true }),
      createTemplate({ id: 2, name: '내 양식', builtin: false, updatedAt: '2026-03-15T01:07:00' }),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await page.goto('/ai-insights/templates');

    const card = page.locator('[data-slot="card"]').filter({ hasText: '내 양식' });
    await expect(card).toContainText('수정 2026-03-15 01:07');
  });
});
