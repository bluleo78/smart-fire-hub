import type { Page } from '@playwright/test';

import { createJob, createTemplate } from '../../factories/ai-insight.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 스마트 작업 조회 권한(proactive:read)이 없는 사용자 E2E (WD-137).
 * - 서버는 리포트·양식·작업 조회를 proactive:read 로 막아 403 을 준다. 예전 화면은 이를 「리포트 없음」·「커스텀 템플릿 없음」·
 *   「잠시 후 다시 시도」·「작업을 찾을 수 없습니다」로 그려 사용자가 권한 문제인 줄 몰랐다.
 * - 이제 403 이면 자물쇠 권한 안내만 보이고, 오해를 부르는 문구·생성 CTA·쓰기 안내는 보이지 않아야 한다.
 * - 기본 인증 fixture 가 권한을 [] 로 모킹하므로 proactive:* 가 하나도 없는 사용자다(mockProactivePermissions 불필요).
 * - 403 이 아닌 실패(500)는 빈 상태가 아니라 불러오기 실패 안내를 보여야 한다.
 */

const FORBIDDEN_BODY = {
  status: 403,
  error: 'Forbidden',
  message: '이 작업을 할 권한이 없습니다. 관리자에게 권한을 요청하세요. (필요 권한: proactive:read)',
};
const SERVER_ERROR_BODY = { status: 500, error: 'Internal Server Error', message: '서버 오류' };

/** 화면 주어별 기대 안내 문구 — ProactiveReadForbiddenNotice 의 문장과 같아야 한다. */
const notice = (subjectWithParticle: string) =>
  `${subjectWithParticle} 보려면 스마트 작업 조회 권한이 필요합니다. 관리자에게 권한을 요청하세요.`;

/** 쓰기 안내(WD-133) testId — 조회가 막힌 화면에서 권한 안내와 함께 뜨면 무엇이 막혔는지 헷갈린다. */
const WRITE_NOTICE_IDS = [
  'jobs-readonly-notice',
  'job-readonly-notice',
  'templates-readonly-notice',
  'template-readonly-notice',
];

/** 조회 API 들을 403 으로 막는다 — 실제 서버처럼 리포트·양식·작업 조회가 모두 같은 권한으로 막힌 상태. */
async function forbidProactiveReads(page: Page, paths: string[]) {
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
  for (const path of paths) {
    await mockApi(page, 'GET', path, FORBIDDEN_BODY, { status: 403 });
  }
}

async function expectNoWriteNotice(page: Page) {
  for (const id of WRITE_NOTICE_IDS) {
    await expect(page.getByTestId(id)).toHaveCount(0);
  }
}

test.describe('스마트 작업 — 조회 권한 없는 사용자 (WD-137)', () => {
  test('생성된 리포트 탭은 빈 상태·만들기 CTA 대신 권한 안내를 보인다', async ({ authenticatedPage: page }) => {
    await forbidProactiveReads(page, ['/api/v1/proactive/reports', '/api/v1/proactive/jobs']);

    await page.goto('/ai-insights/reports');

    await expect(page.getByTestId('reports-forbidden')).toHaveText(notice('리포트를'));
    await expect(page.getByText('스마트 작업을 만들면 생성된 리포트가 여기에 쌓입니다.')).toHaveCount(0);
    await expect(page.getByText('아직 생성된 리포트가 없습니다.')).toHaveCount(0);
    await expect(page.getByText('리포트 목록을 불러오지 못했습니다.')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '스마트 작업 만들기' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '스마트 작업 보기' })).toHaveCount(0);
    await expectNoWriteNotice(page);
  });

  test('리포트 양식 탭은 「커스텀 템플릿 없음」 대신 권한 안내만 보인다', async ({ authenticatedPage: page }) => {
    await forbidProactiveReads(page, ['/api/v1/proactive/templates']);

    await page.goto('/ai-insights/reports?tab=templates');

    await expect(page.getByTestId('templates-forbidden')).toHaveText(notice('리포트 양식을'));
    await expect(page.getByText('커스텀 템플릿 없음')).toHaveCount(0);
    await expect(page.getByText('기본 템플릿', { exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '템플릿 추가' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '첫 템플릿 만들기' })).toHaveCount(0);
    await expectNoWriteNotice(page);
  });

  test('작업 목록은 「잠시 후 다시 시도」 대신 권한 안내를 보인다', async ({ authenticatedPage: page }) => {
    await forbidProactiveReads(page, ['/api/v1/proactive/jobs']);

    await page.goto('/ai-insights/jobs');

    await expect(page.getByTestId('jobs-forbidden')).toHaveText(notice('스마트 작업을'));
    await expect(page.getByText('작업 목록을 불러오지 못했습니다')).toHaveCount(0);
    await expect(page.getByText('잠시 후 다시 시도해 주세요.')).toHaveCount(0);
    await expect(page.getByText('스마트 작업 없음')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '작업 추가' })).toHaveCount(0);
    await expectNoWriteNotice(page);
  });

  test('작업 상세는 「작업을 찾을 수 없습니다」 대신 권한 안내를 보인다', async ({ authenticatedPage: page }) => {
    await forbidProactiveReads(page, ['/api/v1/proactive/jobs/1', '/api/v1/proactive/templates']);

    await page.goto('/ai-insights/jobs/1');

    await expect(page.getByTestId('job-forbidden')).toHaveText(notice('스마트 작업을'));
    await expect(page.getByText('작업을 찾을 수 없습니다')).toHaveCount(0);
    await expect(page.getByText('요청하신 작업이 존재하지 않거나 삭제되었습니다.')).toHaveCount(0);
    for (const name of ['편집', '삭제', '복제', '지금 실행']) {
      await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0);
    }
    await expectNoWriteNotice(page);

    // 목록으로 돌아가는 길은 남아 있다
    await page.getByRole('button', { name: '목록으로' }).click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs$/);
  });

  test('작업 실행 이력 탭은 「실행 이력이 없습니다」 대신 권한 안내를 보인다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', createJob({ id: 1 }));
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
    await forbidProactiveReads(page, ['/api/v1/proactive/jobs/1/executions']);

    await page.goto('/ai-insights/jobs/1?tab=executions');

    await expect(page.getByTestId('executions-forbidden')).toHaveText(notice('실행 이력을'));
    await expect(page.getByText('실행 이력이 없습니다.')).toHaveCount(0);
  });

  test('양식 상세는 실패 토스트·목록 이동 대신 이 화면에 권한 안내를 보인다', async ({ authenticatedPage: page }) => {
    await forbidProactiveReads(page, [
      '/api/v1/proactive/templates/1',
      '/api/v1/proactive/templates',
      '/api/v1/proactive/jobs',
    ]);

    await page.goto('/ai-insights/templates/1');

    await expect(page.getByTestId('template-forbidden')).toHaveText(notice('리포트 양식을'));
    // 예전에는 토스트 후 목록으로 튕겼다 — 머물러 있어야 하고 실패 토스트도 없어야 한다
    await expect(page).toHaveURL(/\/ai-insights\/templates\/1$/);
    await expect(page.getByText('템플릿 정보를 불러오는데 실패했습니다.')).toHaveCount(0);
    await expect(page.getByText('존재하지 않는 템플릿입니다.')).toHaveCount(0);
    for (const name of ['편집', '삭제', '복제']) {
      await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0);
    }
    await expectNoWriteNotice(page);
  });

  test('실행 상세는 「실행 정보를 찾을 수 없습니다」 대신 권한 안내를 보인다', async ({ authenticatedPage: page }) => {
    await forbidProactiveReads(page, ['/api/v1/proactive/jobs/1/executions/1']);

    await page.goto('/ai-insights/jobs/1/executions/1');

    await expect(page.getByTestId('execution-forbidden')).toHaveText(notice('실행 결과를'));
    await expect(page.getByText('실행 정보를 찾을 수 없습니다.')).toHaveCount(0);
  });

  test('리포트 뷰어는 「리포트를 불러올 수 없습니다」 대신 권한 안내를 보인다', async ({ authenticatedPage: page }) => {
    await forbidProactiveReads(page, ['/api/v1/proactive/jobs/1/executions/1/html']);

    await page.goto('/ai-insights/jobs/1/executions/1/report');

    await expect(page.getByTestId('report-forbidden')).toHaveText(notice('리포트를'));
    await expect(page.getByText('리포트를 불러올 수 없습니다.')).toHaveCount(0);
    await expect(page.getByText('리포트가 없습니다.')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '인쇄' })).toBeDisabled();
  });
});

test.describe('리포트·실행 이력 — 403 이 아닌 조회 실패 (WD-137)', () => {
  test('생성된 리포트 탭은 서버 오류를 빈 상태가 아닌 불러오기 실패로 보인다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/reports', SERVER_ERROR_BODY, { status: 500 });
    // 실패 시 빈 상태 분기용 잡 조회를 내지 않는다 — 응답은 주되 호출되지 않아야 한다
    const jobsCapture = await mockApi(page, 'GET', '/api/v1/proactive/jobs', [], { capture: true });

    await page.goto('/ai-insights/reports');

    // 전역 retry 1회(백오프 약 1초)를 거친 뒤 오류가 확정된다
    await expect(page.getByText('리포트 목록을 불러오지 못했습니다.')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('스마트 작업을 만들면 생성된 리포트가 여기에 쌓입니다.')).toHaveCount(0);
    await expect(page.getByText('아직 생성된 리포트가 없습니다.')).toHaveCount(0);
    await expect(page.getByTestId('reports-forbidden')).toHaveCount(0);
    expect(jobsCapture.requests).toHaveLength(0);
  });

  test('리포트 양식 탭은 서버 오류를 「커스텀 템플릿 없음」이 아닌 불러오기 실패로 보인다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/templates', SERVER_ERROR_BODY, { status: 500 });

    await page.goto('/ai-insights/reports?tab=templates');

    await expect(page.getByTestId('templates-error')).toContainText('리포트 양식을 불러오지 못했습니다', {
      timeout: 10_000,
    });
    await expect(page.getByText('커스텀 템플릿 없음')).toHaveCount(0);
    await expect(page.getByTestId('templates-forbidden')).toHaveCount(0);
  });

  test('작업 실행 이력 탭은 서버 오류를 「실행 이력이 없습니다」가 아닌 불러오기 실패로 보인다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', createJob({ id: 1 }));
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', SERVER_ERROR_BODY, { status: 500 });

    await page.goto('/ai-insights/jobs/1?tab=executions');

    await expect(page.getByText('실행 이력을 불러오지 못했습니다.')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('실행 이력이 없습니다.')).toHaveCount(0);
    await expect(page.getByTestId('executions-forbidden')).toHaveCount(0);
  });

  test('양식 상세의 정상 조회는 그대로 보인다 — 권한 분기가 정상 경로를 막지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const template = createTemplate({ id: 1, name: '주간 요약 양식' });
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/templates/1', template);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', []);

    await page.goto('/ai-insights/templates/1');

    await expect(page.getByRole('heading', { name: '주간 요약 양식' })).toBeVisible();
    await expect(page.getByTestId('template-forbidden')).toHaveCount(0);
  });
});
