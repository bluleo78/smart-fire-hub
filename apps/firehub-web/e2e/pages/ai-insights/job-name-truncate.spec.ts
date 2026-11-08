import type { Locator } from '@playwright/test';

import {
  createAdminJobRow,
  createJob,
  createJobExecution,
  createReport,
} from '../../factories/ai-insight.factory';
import { mockProactiveAdminPermissions } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 긴 스마트 작업명 말줄임 E2E (WD-558).
 *
 * 공백 없는 긴 이름(서버 상한 200자)이 이름 칸을 수천 px 로 늘려
 * 오른쪽 칸들(활성 토글·실패 표시·액션)이 1280px 화면 밖으로 밀렸다.
 * 이름은 칸 폭에서 말줄임되고 전체 이름은 title 툴팁으로 볼 수 있어야 한다 —
 * 내 작업·워크스페이스 전체 두 탭과, 작업명을 배지로 보이는 리포트 목록 모두.
 */

const VIEWPORT_WIDTH = 1280;
/** 서버 상한(CreateProactiveJobRequest.NAME_MAX_LENGTH = 200) 꽉 채운, 공백 없는 이름 */
const LONG_NAME = 'A'.repeat(200);

test.use({ viewport: { width: VIEWPORT_WIDTH, height: 800 } });

/** 요소가 실제로 잘려(말줄임) 보이는지 — 내용 폭이 보이는 폭보다 넓다 */
async function isTruncated(locator: Locator): Promise<boolean> {
  return locator.evaluate((el) => el.scrollWidth > el.clientWidth);
}

/** 요소의 오른쪽 끝이 뷰포트 안에 있는지 단언한다 */
async function expectWithinViewport(locator: Locator) {
  const box = await locator.boundingBox();
  expect(box).not.toBeNull();
  expect(box!.x + box!.width).toBeLessThanOrEqual(VIEWPORT_WIDTH);
}

test.describe('긴 스마트 작업명 말줄임 (WD-558)', () => {
  test('내 작업 — 200자 이름이 말줄임되고 활성 토글·실패 표시가 화면 안에 남는다', async ({
    authenticatedPage: page,
  }) => {
    await mockProactiveAdminPermissions(page);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [
      createJob({
        id: 1,
        name: LONG_NAME,
        enabled: true,
        lastExecutedAt: new Date().toISOString(),
        lastExecution: createJobExecution({ status: 'FAILED', errorMessage: '연결 실패' }),
      }),
      createJob({ id: 2, name: '짧은 작업' }),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs');
    const table = page.getByRole('table', { name: '스마트 작업 목록' });
    const longRow = table.getByRole('row').filter({ has: page.getByTitle(LONG_NAME) });
    await expect(longRow).toHaveCount(1);

    // 전체 이름은 title 툴팁으로 남고, 화면에서는 실제로 잘려 보인다
    const nameEl = longRow.getByTitle(LONG_NAME);
    await expect(nameEl).toHaveText(LONG_NAME);
    expect(await isTruncated(nameEl)).toBe(true);

    // 활성 토글과 마지막 실행 실패 표시가 1280px 화면 안에 있다
    await expectWithinViewport(longRow.getByRole('switch', { name: `${LONG_NAME} 활성화` }));
    await expectWithinViewport(longRow.getByTestId('last-run-failed'));

    // 짧은 이름은 말줄임 없이 그대로 보인다
    const shortName = table.getByTitle('짧은 작업');
    await expect(shortName).toHaveText('짧은 작업');
    expect(await isTruncated(shortName)).toBe(false);
  });

  test('워크스페이스 전체 — 200자 이름이 말줄임되고 활성 칸이 화면 안에 남는다', async ({
    authenticatedPage: page,
  }) => {
    await mockProactiveAdminPermissions(page);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', []);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/admin/jobs', [
      createAdminJobRow({ id: 10, name: LONG_NAME, lastExecutionStatus: 'FAILED' }),
      createAdminJobRow({ id: 11, name: '짧은 작업' }),
    ]);

    await page.goto('/ai-insights/jobs?view=workspace');
    const longRow = page.getByTestId('workspace-job-10');
    await expect(longRow).toBeVisible();

    // 전체 이름은 title 툴팁으로 남고, 화면에서는 실제로 잘려 보인다
    const nameEl = longRow.getByTitle(LONG_NAME);
    await expect(nameEl).toHaveText(LONG_NAME);
    expect(await isTruncated(nameEl)).toBe(true);

    // 활성 배지와 끄기 버튼이 1280px 화면 안에 있다
    await expectWithinViewport(longRow.getByText('활성', { exact: true }));
    await expectWithinViewport(longRow.getByRole('button', { name: `${LONG_NAME} 끄기` }));

    // 짧은 이름은 말줄임 없이 그대로 보인다
    const shortName = page.getByTestId('workspace-job-11').getByTitle('짧은 작업');
    await expect(shortName).toHaveText('짧은 작업');
    expect(await isTruncated(shortName)).toBe(false);
  });

  test('리포트 목록 — 200자 작업명 배지가 말줄임되고 액션 칸이 화면 안에 남는다', async ({
    authenticatedPage: page,
  }) => {
    await mockProactiveAdminPermissions(page);
    await mockApi(page, 'GET', '/api/v1/proactive/reports', [
      createReport({ executionId: 11, jobId: 3, jobName: LONG_NAME }),
      createReport({ executionId: 12, jobId: 4, jobName: '짧은 작업', title: '짧은 리포트' }),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', []);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/reports');
    // 배지가 전체 이름을 title 로 갖고, 안쪽 글자가 실제로 잘려 보인다
    const longBadge = page.getByTitle(LONG_NAME);
    await expect(longBadge).toHaveText(LONG_NAME);
    expect(await isTruncated(longBadge.locator('span'))).toBe(true);

    // 마지막(액션) 칸의 버튼이 1280px 화면 안에 있다
    const longRow = page.getByRole('row').filter({ has: longBadge });
    await expectWithinViewport(longRow.getByRole('button', { name: '스마트 작업 보기' }));

    // 짧은 작업명 배지는 잘리지 않는다
    const shortBadge = page.getByTitle('짧은 작업');
    await expect(shortBadge).toHaveText('짧은 작업');
    expect(await isTruncated(shortBadge.locator('span'))).toBe(false);
  });
});
