import { createJob } from '../../factories/ai-insight.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 스마트 작업 목록 — cron 표기 일관성(#347) + 스케줄 미등록 노출(#354) E2E 회귀 테스트
 *
 * DB에는 5필드(Unix 표준)와 6필드(Spring, 초 포함) cron이 섞여 저장돼 있다. 표시 계층이
 * 문자열 완전일치 룩업만 쓰던 때는 6필드가 매칭되지 않아 원시 cron이 그대로 노출됐고,
 * 같은 스케줄이 한 화면에서 두 가지로 보였다(#347).
 *
 * 또한 5필드 잡은 스케줄러 등록에 실패해 영구 미실행이었는데, UI는 '활성'으로만 보여
 * 사용자가 알 방법이 없었다(#354). 등록 실패는 next_execute_at이 비는 것으로 관측된다.
 */

test.describe('스마트 작업 목록 — cron 표기 일관성 (#347)', () => {
  test('같은 스케줄은 5필드/6필드 어느 쪽으로 저장돼도 동일한 라벨로 보인다', async ({
    authenticatedPage: page,
  }) => {
    // #347에서 관찰된 실제 DB 상태 — 같은 '매일 오전 9시'가 두 표기로 저장돼 있다
    const fiveField = createJob({
      id: 1,
      name: '일일 매출 분석 리포트',
      cronExpression: '0 9 * * *',
    });
    const sixField = createJob({
      id: 23,
      name: 'minimal_test',
      cronExpression: '0 0 9 * * *',
    });

    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [fiveField, sixField]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await page.goto('/ai-insights/jobs');

    const fiveRow = page.getByRole('row').filter({ hasText: '일일 매출 분석 리포트' });
    const sixRow = page.getByRole('row').filter({ hasText: 'minimal_test' });

    // 두 행 모두 사람이 읽는 라벨이어야 한다
    await expect(fiveRow).toContainText('매일 오전 9시');
    await expect(sixRow).toContainText('매일 오전 9시');

    // 이 이슈의 현상 — 6필드 원문이 화면에 그대로 새어 나오면 안 된다
    await expect(sixRow).not.toContainText('0 0 9 * * *');
  });

  test('매시간 스케줄도 6필드 표기가 라벨로 변환된다', async ({ authenticatedPage: page }) => {
    // daily_kpi_report — DB에 '0 0 * * * *'(6필드 매시간)로 저장된 실제 행
    const job = createJob({ id: 25, name: 'daily_kpi_report', cronExpression: '0 0 * * * *' });

    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [job]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await page.goto('/ai-insights/jobs');

    const row = page.getByRole('row').filter({ hasText: 'daily_kpi_report' });
    await expect(row).toContainText('매시간');
    await expect(row).not.toContainText('0 0 * * * *');
  });

  test('해석할 수 없는 cron은 원문임이 드러나게 감싸서 보인다', async ({
    authenticatedPage: page,
  }) => {
    // 초가 0이 아닌 6필드는 5필드로 환원하면 의미가 바뀌므로 라벨을 만들지 않는다.
    // 원문을 그냥 흘리면 라벨처럼 읽히므로 '주기:' 접두어로 미해석임을 드러낸다.
    const job = createJob({ id: 30, name: '초 단위 작업', cronExpression: '30 0 9 * * *' });

    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [job]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await page.goto('/ai-insights/jobs');

    const row = page.getByRole('row').filter({ hasText: '초 단위 작업' });
    await expect(row).toContainText('주기: 30 0 9 * * *');
    // 초를 버리고 '매일 오전 9시'라 단정하면 틀린 값이다 — 이게 더 나쁜 실패
    await expect(row).not.toContainText('매일 오전 9시');
  });
});

test.describe('스마트 작업 목록 — 스케줄 미등록 노출 (#354)', () => {
  test('활성인데 다음 실행이 없으면 "미등록"으로 경고한다', async ({ authenticatedPage: page }) => {
    // 등록 실패 시 백엔드가 next_execute_at을 비운다 — 미등록의 관측 가능한 신호.
    // 이전에는 '-'로만 보여서 '활성' 배지만 믿은 사용자가 미실행을 알 수 없었다.
    const job = createJob({
      id: 1,
      name: '등록 실패 작업',
      enabled: true,
      nextExecuteAt: null,
    });

    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [job]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await page.goto('/ai-insights/jobs');

    const row = page.getByRole('row').filter({ hasText: '등록 실패 작업' });
    await expect(row.getByTestId('schedule-unregistered')).toHaveText('미등록');
  });

  test('정상 등록된 활성 작업에는 미등록 표시가 없다', async ({ authenticatedPage: page }) => {
    // 오탐 방지 — next_execute_at이 있으면 경고가 뜨면 안 된다
    const job = createJob({
      id: 2,
      name: '정상 작업',
      enabled: true,
      nextExecuteAt: '2099-01-02T00:00:00',
    });

    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [job]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await page.goto('/ai-insights/jobs');

    const row = page.getByRole('row').filter({ hasText: '정상 작업' });
    await expect(row.getByTestId('schedule-unregistered')).toHaveCount(0);
  });

  test('비활성 작업은 미등록이 아니라 "-"로 표시된다', async ({ authenticatedPage: page }) => {
    // 비활성은 의도적으로 스케줄이 해제된 상태이므로 경고 대상이 아니다
    const job = createJob({
      id: 3,
      name: '비활성 작업',
      enabled: false,
      nextExecuteAt: null,
    });

    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [job]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await page.goto('/ai-insights/jobs');

    const row = page.getByRole('row').filter({ hasText: '비활성 작업' });
    await expect(row.getByTestId('schedule-unregistered')).toHaveCount(0);
  });
});
