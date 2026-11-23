import { createJob } from '../../factories/ai-insight.factory';
import { mockProactivePermissions, setupJobDetailMocks } from '../../fixtures/ai-insight.fixture';
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

  test('cron 없는 이상 탐지 작업은 미등록 경고 없이 정상 렌더링된다', async ({
    authenticatedPage: page,
  }) => {
    // triggerType=ANOMALY 작업은 cron 없이 저장되고(#38/#43) 스케줄러 등록 대상도 아니다.
    // 등록 실패가 아니라 정상 구성이므로 경고를 띄우면 오탐이다.
    // 또한 cron이 비어 있어도 라벨 변환이 크래시하지 않아야 한다 — 죽으면 목록 전체가 백지가 된다.
    const job = createJob({
      id: 4,
      name: '이상 탐지 작업',
      enabled: true,
      triggerType: 'ANOMALY',
      cronExpression: '',
      nextExecuteAt: null,
    });

    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [job]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await page.goto('/ai-insights/jobs');

    // 페이지가 살아 있고 행이 렌더된다 (크래시 회귀 방지)
    const row = page.getByRole('row').filter({ hasText: '이상 탐지 작업' });
    await expect(row).toBeVisible();
    await expect(row.getByTestId('schedule-unregistered')).toHaveCount(0);
  });

  test('cron 을 보존한 이상 탐지 작업도 미등록 경고 없이 표시된다', async ({
    authenticatedPage: page,
  }) => {
    // WD-993 — 트리거를 정기 → 이상 탐지로 바꾸면 cron 을 그대로 보존해 둔다(돌아갈 때 다시 입력하지 않게).
    // 그래서 ANOMALY 행도 cronExpression 이 있고 nextExecuteAt 은 비어 있다 — 예약 대상이 아니라
    // 이상 탐지 폴러가 돌리므로 「실행되지 않습니다」 경고는 오경고다.
    const job = createJob({
      id: 5,
      name: 'cron 보존 이상 탐지 작업',
      enabled: true,
      triggerType: 'ANOMALY',
      cronExpression: '0 9 * * *',
      nextExecuteAt: null,
    });

    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [job]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await page.goto('/ai-insights/jobs');

    const row = page.getByRole('row').filter({ hasText: 'cron 보존 이상 탐지 작업' });
    await expect(row).toBeVisible();
    await expect(row.getByTestId('schedule-unregistered')).toHaveCount(0);

    // WD-993 반려 ① — 보존된 cron 을 「매일 오전 9시」처럼 스케줄 라벨로 보여 주면 정기 실행 중인 줄로 읽힌다
    await expect(row).toContainText('주기 없음');
    await expect(row).not.toContainText('매일 오전 9시');
    await expect(row).not.toContainText('0 9 * * *');
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

/**
 * 스마트 작업 상세(개요 탭) — ANOMALY 보존 cron 표기 + 다음 실행 + 미등록 게이트 (WD-993 반려 ①②④)
 *
 * 목록은 검증했지만 상세 탭은 없었다(반려 ④). 상세는 목록과 다른 코드 경로(ReadonlyCard)로 같은 값을 그려,
 * 「스케줄」·「다음 실행」 카드가 각각 ANOMALY 를 따로 거르지 않으면 두 화면의 표기가 갈린다.
 */
test.describe('스마트 작업 상세 — ANOMALY 보존 cron 표기 (WD-993)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await mockProactivePermissions(page);
  });

  test('보존된 cron 을 스케줄처럼 보여 주지 않고 다음 실행도 비운다', async ({
    authenticatedPage: page,
  }) => {
    // 반려 ①·② — 트리거를 이상 탐지로 바꾸며 cron 을 보존해 둔 행에 배포 전 예약(nextExecuteAt)이 남아 있어도
    // 상세는 「매일 오전 9시」·지난 시각을 정기 실행처럼 보여 주면 안 된다.
    const job = createJob({
      id: 6,
      name: '상세 이상 탐지 보존 주기',
      enabled: true,
      triggerType: 'ANOMALY',
      cronExpression: '0 9 * * *',
      nextExecuteAt: '2099-01-01T09:00:00',
    });
    await setupJobDetailMocks(page, 6);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/6', job);

    await page.goto('/ai-insights/jobs/6');

    const section = page.getByRole('heading', { name: '실행 주기' }).locator('..');
    await expect(section).toBeVisible();

    // ① 스케줄 칸 — 정기 실행 라벨이 아니라 「주기 없음」
    await expect(section).toContainText('주기 없음');
    await expect(section).not.toContainText('매일 오전 9시');

    // ② 다음 실행 — ANOMALY 는 정기 예약 대상이 아니므로 남아 있는 예약 시각을 보여 주지 않는다
    await expect(section).not.toContainText('2099');

    // 웹 미등록 게이트 오경고 없음(#354) — 이 행은 nextExecuteAt 이 있어 경고 분기에 닿지 않는다.
    // 분기 "순서"까지 확인하려면 아래 nextExecuteAt 없는 ANOMALY 행 테스트를 본다.
    await expect(page.getByTestId('schedule-unregistered')).toHaveCount(0);
  });

  test('정기 실행 작업은 스케줄 라벨과 다음 실행 시각이 그대로 보인다', async ({
    authenticatedPage: page,
  }) => {
    // 과잉 제외를 막는 대조 — ANOMALY 거르기가 SCHEDULE 까지 들지 않아야 한다
    const job = createJob({
      id: 7,
      name: '상세 정기 실행 작업',
      enabled: true,
      triggerType: 'SCHEDULE',
      cronExpression: '0 9 * * *',
      nextExecuteAt: '2099-01-01T09:00:00',
    });
    await setupJobDetailMocks(page, 7);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/7', job);

    await page.goto('/ai-insights/jobs/7');

    const section = page.getByRole('heading', { name: '실행 주기' }).locator('..');
    await expect(section).toContainText('매일 오전 9시');
    await expect(section).toContainText('2099');
    await expect(page.getByTestId('schedule-unregistered')).toHaveCount(0);
  });

  test('활성 + cron 이 있는데 다음 실행이 없으면 상세에서도 미등록으로 경고한다', async ({
    authenticatedPage: page,
  }) => {
    // #354 상세 경고가 이번 삼항 재작성으로 사라지지 않는지 — 반려 ② 고치며 분기를 갈라 놓았으므로 대조가 필요하다
    const job = createJob({
      id: 8,
      name: '상세 미등록 작업',
      enabled: true,
      triggerType: 'SCHEDULE',
      cronExpression: '0 9 * * *',
      nextExecuteAt: null,
    });
    await setupJobDetailMocks(page, 8);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/8', job);

    await page.goto('/ai-insights/jobs/8');

    await expect(page.getByTestId('schedule-unregistered')).toBeVisible();
    await expect(page.getByTestId('schedule-unregistered')).toContainText('미등록');
  });

  test('nextExecuteAt 이 비어 있는 ANOMALY 도 미등록 경고를 뜨지 않는다', async ({
    authenticatedPage: page,
  }) => {
    // 위 첫 테스트는 nextExecuteAt 이 있어 경고 분기 자체에 닿지 않는다 — ANOMALY 오경고 방지는 분기 "순서"에만
    // 의존하므로(nextExecuteAt ? … : ANOMALY ? … 처럼 뒤로 밀면 깨진다) 반드시 nextExecuteAt 없는 행으로 확인해야 한다.
    // 목록에는 이 경우가 있었는데 상세에는 없었다(반려 ④).
    const job = createJob({
      id: 9,
      name: '상세 이상 탐지 다음 실행 없음',
      enabled: true,
      triggerType: 'ANOMALY',
      cronExpression: '0 9 * * *',
      nextExecuteAt: null,
    });
    await setupJobDetailMocks(page, 9);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/9', job);

    await page.goto('/ai-insights/jobs/9');

    await expect(page.getByTestId('schedule-unregistered')).toHaveCount(0);
    const section = page.getByRole('heading', { name: '실행 주기' }).locator('..');
    await expect(section).toContainText('주기 없음');
    await expect(section).not.toContainText('2099');
  });
});
