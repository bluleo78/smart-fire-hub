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

  test('목표 기반 새 작업: 자동 양식 이름이 20번까지 쓰였으면 한 번 읽은 목록으로 「양식 (21)」을 바로 보낸다 (WD-837)', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    // 「<작업> 양식」~「(20)」은 이미 있다 — 서버는 그 이름 모두에 409 를 낸다
    const used = ['주간 화재 추이 양식', ...Array.from({ length: 19 }, (_, i) => `주간 화재 추이 양식 (${i + 2})`)];
    let listReads = 0;
    const templateNames: string[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/templates',
      (route) => {
        const req = route.request();
        if (req.method() === 'GET') {
          // 화면 자신의 목록 조회(기본 크기)와 이름 읽기(size=200)를 가른다 — 후자만 센다
          if (new URL(req.url()).searchParams.get('size') === '200') listReads += 1;
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(used.map((name, i) => createTemplate({ id: 100 + i, name }))) });
        }
        const name = (req.postDataJSON() as { name: string }).name;
        templateNames.push(name);
        return used.includes(name)
          ? route.fulfill({ status: 409, contentType: 'application/json', body: duplicateBody(TEMPLATE_MSG) })
          : route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(createTemplate({ id: 78 })) });
      },
    );
    const jobBodies = await mockPostRejectingFirst(page, '/api/v1/proactive/jobs', 0, JOB_MSG, createJob({ id: 99, name: '주간 화재 추이' }));
    await page.goto('/ai-insights/jobs/new');

    await page.locator('#mode-goal').click();
    await page.getByLabel('비즈니스 질문').fill('주간 화재 추이');
    await page.getByRole('button', { name: '템플릿 자동 생성' }).click();
    await page.getByRole('button', { name: '만들기', exact: true }).click();

    await expect.poll(() => jobBodies.length).toBe(1);
    // 첫 409 뒤 목록을 한 번 읽고 비어 있는 가장 작은 번호(21)로 바로 간다 — 2 부터 20 까지 순차로 두드리지 않는다
    expect(templateNames).toEqual(['주간 화재 추이 양식', '주간 화재 추이 양식 (21)']);
    expect(listReads).toBe(1);
    expect(jobBodies[0]).toMatchObject({ templateId: 78 });
  });

  test('목표 기반 새 작업: 자동 양식 이름을 끝내 못 정하면 안내를 토스트로 보이고 작업을 만들지 않는다 (WD-837)', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    await mockPostRejectingFirst(page, '/api/v1/proactive/templates', 999, TEMPLATE_MSG, {});
    const jobBodies = await mockPostRejectingFirst(page, '/api/v1/proactive/jobs', 0, JOB_MSG, createJob({ id: 99 }));
    await page.goto('/ai-insights/jobs/new');

    await page.locator('#mode-goal').click();
    await page.getByLabel('비즈니스 질문').fill('주간 화재 추이');
    await page.getByRole('button', { name: '템플릿 자동 생성' }).click();
    await page.getByRole('button', { name: '만들기', exact: true }).click();

    await expect(
      page.getByText('자동 양식 이름을 정하지 못했습니다. 쓰지 않는 「주간 화재 추이 양식」 양식을 지우거나 작업 이름을 바꾼 뒤 다시 저장하세요.'),
    ).toBeVisible();
    // 사용자 입력이 아닌 이름이라 이름 칸 오류로 가지 않고, 작업도 만들지 않는다
    await expect(page.getByText(TEMPLATE_MSG)).toHaveCount(0);
    expect(jobBodies).toHaveLength(0);
  });

  test('작업 복제: 목록을 열어 둔 캐시가 있으면 409 뒤 작업 목록을 다시 읽지 않는다 (WD-837)', async ({ authenticatedPage: page }) => {
    let jobListReads = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/jobs',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        jobListReads += 1;
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([createJob({ id: 1, name: '잡 1', enabled: true })]) });
      },
    );
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    const cloned = createJob({ id: 2, name: '잡 1 (복사본 2)', enabled: false });
    const bodies = await mockPostRejectingFirst(page, '/api/v1/proactive/jobs', 1, JOB_MSG, cloned);
    // (나중에 등록한 라우트가 먼저 실행되므로 mockPostRejectingFirst 뒤에 둔다) 재시도(두 번째) POST 를 받는 순간의 목록 GET 수를 기록한다 — 성공 뒤 onSuccess 의 목록 무효화 GET 과 섞이지 않게 하려는 것이다.
    // 캐시가 없으면 재시도 POST 전에 목록 GET 이 한 번 더 나가 2 가 되므로 회귀를 그대로 잡는다.
    let readsAtRetry = -1;
    let posts = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/jobs',
      (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        posts += 1;
        if (posts === 2) readsAtRetry = jobListReads;
        return route.fallback();
      },
    );
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/2', cloned);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/2/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);

    await page.goto('/ai-insights/jobs');
    await expect(page.getByRole('button', { name: '복제' })).toBeVisible();
    expect(jobListReads).toBe(1);
    await page.getByRole('button', { name: '복제' }).click();

    await expect.poll(() => bodies.length).toBe(2);
    // 목록을 연 1회가 전부 — 409 뒤 이름을 얻으려고 목록을 또 읽지 않는다(캐시 재사용)
    expect(readsAtRetry).toBe(1);
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
