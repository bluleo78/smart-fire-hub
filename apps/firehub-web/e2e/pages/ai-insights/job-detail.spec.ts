import { createJob, createJobExecution, createTemplates } from '../../factories/ai-insight.factory';
import { createDataset } from '../../factories/dataset.factory';
import {
  setupJobDetailMocks,
  setupJobDetailWithExecutionsMocks,
  setupNewJobMocks,
} from '../../fixtures/ai-insight.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 스마트 작업(Job) 상세 페이지 E2E 테스트
 * - 기존 작업 조회, 실행 이력, 새 작업 생성 폼 UI를 검증한다.
 */
test.describe('스마트 작업 상세 페이지', () => {
  test('작업 정보가 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
    // 잡 ID 1 상세 페이지 모킹
    await setupJobDetailMocks(page, 1);

    await page.goto('/ai-insights/jobs/1');

    // 작업명이 헤더에 표시되는지 확인 (팩토리 기본값: "매일 현황 리포트")
    await expect(page.getByRole('heading', { name: '매일 현황 리포트' })).toBeVisible();

    // 헤더 영역 내 활성 뱃지 확인 (enabled: true 기본값)
    // header와 overview 탭 두 곳에 모두 표시될 수 있으므로 header로 범위를 좁힌다
    await expect(page.locator('header').locator('[data-slot="badge"]').filter({ hasText: '활성' })).toBeVisible();

    // 탭 목록 확인
    await expect(page.getByRole('tab', { name: '개요' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '실행 이력' })).toBeVisible();

    // 팩토리 기본값 cronExpression '0 9 * * *' 또는 변환된 스케줄 설명이 표시되는지 확인
    const cronText = page.getByText('0 9 * * *');
    const scheduleDesc = page.getByText(/매일|오전 9시|09:00|스케줄/);
    const hasCron = (await cronText.count()) > 0 || (await scheduleDesc.count()) > 0;
    expect(hasCron).toBe(true);

    // 팩토리 기본값 templateName '기본 리포트 템플릿'이 페이지 어딘가에 표시되는지 확인
    await expect(page.getByText('기본 리포트 템플릿')).toBeVisible();
  });

  test('실행 이력 탭에서 실행 목록을 확인할 수 있다', async ({ authenticatedPage: page }) => {
    // 실행 이력 3건 포함하여 모킹 — 첫 번째 FAILED, 나머지 COMPLETED
    await setupJobDetailWithExecutionsMocks(page, 1, 3);

    await page.goto('/ai-insights/jobs/1?tab=executions');

    // 실행 이력 탭이 활성화되어 있는지 확인
    await expect(page.getByRole('tab', { name: '실행 이력' })).toHaveAttribute('data-state', 'active');

    // 실행 이력 테이블 헤더 확인
    await expect(page.getByRole('columnheader', { name: '실행 시간' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '상태' })).toBeVisible();

    // 행 수 확인: 헤더 1 + 데이터 3 = 총 4행
    const rows = page.getByRole('row');
    await expect(rows).toHaveCount(4);

    // 첫 번째 데이터 행(헤더 제외)에 FAILED 상태 뱃지가 표시되는지 확인
    // setupJobDetailWithExecutionsMocks: i===0 → FAILED, 나머지 → COMPLETED
    const firstDataRow = rows.nth(1);
    await expect(firstDataRow.locator('[data-slot="badge"]').filter({ hasText: '실패' })).toBeVisible();

    // COMPLETED 상태 뱃지가 2개 이상 존재하는지 확인
    const completedBadges = page.locator('[data-slot="badge"]').filter({ hasText: '완료' });
    await expect(completedBadges).toHaveCount(2);
  });

  test('새 작업 페이지에서 생성 폼이 표시된다', async ({ authenticatedPage: page }) => {
    // 새 작업 페이지 API 모킹
    await setupNewJobMocks(page);

    await page.goto('/ai-insights/jobs/new');

    // "새 스마트 작업" 헤더 확인
    await expect(page.getByRole('heading', { name: '새 스마트 작업' })).toBeVisible();

    // "생성" 버튼 확인
    await expect(page.getByRole('button', { name: '생성' })).toBeVisible();

    // 작업명 입력 필드 확인 (id="job-name", label="작업 이름 *")
    await expect(page.locator('#job-name')).toBeVisible();
    await expect(page.getByLabel('작업 이름 *')).toBeVisible();

    // 수동 모드로 전환하여 프롬프트/템플릿 필드 확인
    // 새 작업 페이지는 기본적으로 목표 모드로 시작하므로 수동 모드 버튼을 클릭해야 한다
    const manualModeBtn = page.getByRole('button', { name: /수동|직접/ });
    if (await manualModeBtn.isVisible()) {
      await manualModeBtn.click();
    }

    // 분석 프롬프트 textarea 확인 (id="job-prompt", label="분석 프롬프트 *")
    // 수동 모드에서만 표시되므로 표시된 경우에만 검증한다
    const promptField = page.locator('#job-prompt');
    const templateField = page.locator('#job-template');
    const hasPrompt = (await promptField.count()) > 0 && await promptField.isVisible();
    const hasTemplate = (await templateField.count()) > 0 && await templateField.isVisible();
    // 수동 모드 전환 후 최소 하나의 필드가 표시되어야 한다
    expect(hasPrompt || hasTemplate).toBe(true);
  });

  test('목록으로 버튼 클릭 시 작업 목록 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupJobDetailMocks(page, 1);
    // 목록 페이지로 돌아갈 때 필요한 API 모킹
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: 1 })]);

    await page.goto('/ai-insights/jobs/1');

    // 뒤로가기 버튼(aria-label="목록으로") 클릭
    await page.getByRole('button', { name: '목록으로' }).click();

    // 작업 목록 페이지로 이동 확인
    await expect(page).toHaveURL('/ai-insights/jobs');
  });

  test('실행 이력 탭에서 실행 행 클릭 시 실행 상세 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    // 실행 이력 1건 포함하여 모킹
    const execution = createJobExecution({ id: 5, jobId: 1, status: 'COMPLETED' });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', createJob({ id: 1 }));
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', [execution]);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    // 실행 상세 페이지 API
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/5', execution);
    await mockApi(
      page,
      'GET',
      '/api/v1/proactive/jobs/1/executions/5/html',
      '',
      { status: 404 },
    );

    await page.goto('/ai-insights/jobs/1?tab=executions');

    // 실행 행 클릭 — 상태 뱃지가 있는 첫 번째 행 클릭
    const rows = page.getByRole('row');
    // 헤더 제외한 첫 번째 데이터 행 클릭
    await rows.nth(1).click();

    // 실행 상세 페이지로 이동 확인
    await expect(page).toHaveURL('/ai-insights/jobs/1/executions/5');
  });

  test('모니터링 탭에 이상 탐지 이력이 표시된다', async ({ authenticatedPage: page }) => {
    // setupJobDetailMocks에 이상 탐지 이벤트 2건이 모킹되어 있다
    await setupJobDetailMocks(page, 1);

    await page.goto('/ai-insights/jobs/1');

    // 모니터링 탭 클릭
    await page.getByRole('tab', { name: /모니터링/ }).click();

    // 이상 탐지 이력 섹션 헤더 확인
    await expect(page.getByText('최근 이상 탐지')).toBeVisible();

    // 팩토리 기본값 metricName '파이프라인 실패율'이 테이블에 표시되는지 확인
    await expect(page.getByText('파이프라인 실패율')).toBeVisible();

    // deviation 6.38 값이 "+6.38σ" 형식으로 표시되는지 확인
    await expect(page.getByText('+6.38σ')).toBeVisible();
  });

  test('비활성 작업에는 "비활성" 뱃지가 표시된다', async ({ authenticatedPage: page }) => {
    // enabled: false 작업 모킹
    const disabledJob = createJob({ id: 2, enabled: false, name: '비활성 작업' });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/2', disabledJob);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/2/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs/2');

    // 헤더 영역 내 비활성 뱃지 확인
    // header와 overview 탭 두 곳에 모두 표시될 수 있으므로 header로 범위를 좁힌다
    await expect(page.locator('header').locator('[data-slot="badge"]').filter({ hasText: '비활성' })).toBeVisible();
  });

  test('이상 탐지 이력 셀 수준 검증', async ({ authenticatedPage: page }) => {
    // 이상 탐지 이벤트 2건을 포함하는 setupJobDetailMocks 사용
    // 팩토리 기본값: metricName='파이프라인 실패율', currentValue=45.5, mean=12.3, deviation=6.38, sensitivity='medium'
    // 두 번째 이벤트: metricName='데이터셋 수', currentValue=150, mean=100, deviation=3.2, sensitivity='medium'
    await setupJobDetailMocks(page, 1);

    await page.goto('/ai-insights/jobs/1');

    // 모니터링 탭으로 이동
    await page.getByRole('tab', { name: /모니터링/ }).click();

    // 이상 탐지 이력 테이블 헤더 확인
    await expect(page.getByRole('columnheader', { name: '메트릭' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '현재 값' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '평균' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '편차(σ)' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '민감도' })).toBeVisible();

    // 첫 번째 이벤트: 메트릭명 '파이프라인 실패율' 확인
    await expect(page.getByRole('cell', { name: '파이프라인 실패율' })).toBeVisible();

    // 첫 번째 이벤트: currentValue 45.5 → '45.50' 형식으로 표시
    await expect(page.getByRole('cell', { name: '45.50' })).toBeVisible();

    // 첫 번째 이벤트: mean 12.3 → '12.30' 형식으로 표시
    await expect(page.getByRole('cell', { name: '12.30' })).toBeVisible();

    // 첫 번째 이벤트: deviation 6.38 → '+6.38σ' 배지 형식으로 표시
    await expect(page.getByText('+6.38σ')).toBeVisible();

    // 두 번째 이벤트: deviation 3.2 → '+3.20σ' 배지 형식으로 표시
    await expect(page.getByText('+3.20σ')).toBeVisible();

    // sensitivity 'medium' → '보통' 레이블로 변환 표시 (두 행 모두 '보통')
    const sensitivityCells = page.getByRole('cell', { name: '보통' });
    await expect(sensitivityCells).toHaveCount(2);

    // 두 번째 이벤트: 메트릭명 '데이터셋 수' 확인
    await expect(page.getByRole('cell', { name: '데이터셋 수' })).toBeVisible();

    // 두 번째 이벤트: currentValue 150 → '150.00' 형식으로 표시
    await expect(page.getByRole('cell', { name: '150.00' })).toBeVisible();

    // 두 번째 이벤트: mean 100 → '100.00' 형식으로 표시
    await expect(page.getByRole('cell', { name: '100.00' })).toBeVisible();
  });

  test('커스텀 메트릭 모달 열기/입력 후 목록에 "데이터셋" 뱃지가 표시된다', async ({ authenticatedPage: page }) => {
    // 이상 탐지 활성화된 작업 설정 — config.anomaly.enabled: true 로 모킹
    const jobWithAnomaly = createJob({
      id: 1,
      config: {
        channels: [{ type: 'CHAT', recipientUserIds: [], recipientEmails: [] }],
        anomaly: { enabled: true, metrics: [], sensitivity: 'medium', cooldownMinutes: 30 },
      },
    });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', jobWithAnomaly);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/anomaly-events', []);

    // 커스텀 메트릭 모달의 데이터셋 Select에 사용할 데이터셋 목록 모킹
    const datasetsResponse = createPageResponse(
      [
        createDataset({ id: 1, name: '소방 데이터셋', tableName: 'fire_dataset' }),
        createDataset({ id: 2, name: '통계 데이터셋', tableName: 'stats_dataset' }),
      ],
    );
    await mockApi(page, 'GET', '/api/v1/datasets', datasetsResponse);

    await page.goto('/ai-insights/jobs/1');

    // 편집 버튼 클릭으로 편집 모드 진입
    await page.getByRole('button', { name: '편집' }).click();

    // 모니터링 탭으로 이동
    await page.getByRole('tab', { name: /모니터링/ }).click();

    // "커스텀 메트릭" 버튼 클릭 — 모달 열기
    await page.getByRole('button', { name: /커스텀 메트릭/ }).click();

    // 커스텀 메트릭 추가 모달이 열렸는지 확인
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByText('커스텀 메트릭 추가')).toBeVisible();

    // 모달 내 필드 확인: 메트릭 이름, 데이터셋, SQL 쿼리, 폴링 주기
    await expect(page.getByLabel('메트릭 이름')).toBeVisible();
    await expect(page.getByLabel('데이터셋')).toBeVisible();
    await expect(page.getByLabel('집계 쿼리')).toBeVisible();
    await expect(page.getByLabel('폴링 주기 (초)')).toBeVisible();

    // 메트릭 이름 입력
    await page.getByLabel('메트릭 이름').fill('신규 주문 건수');

    // 데이터셋 선택 — '소방 데이터셋' 선택
    await page.getByLabel('데이터셋').click();
    await page.getByRole('option', { name: '소방 데이터셋' }).click();

    // SQL 쿼리 입력
    await page.getByLabel('집계 쿼리').fill('SELECT COUNT(*) FROM fire_orders WHERE created_at > NOW() - INTERVAL 1 DAY');

    // 추가 버튼 클릭
    await page.getByRole('dialog').getByRole('button', { name: '추가' }).click();

    // 모달이 닫혔는지 확인
    await expect(page.getByRole('dialog')).not.toBeVisible();

    // 추가된 메트릭이 목록에 표시되는지 확인 — 메트릭 이름 표시
    await expect(page.getByText('신규 주문 건수')).toBeVisible();

    // 커스텀 메트릭은 "데이터셋" 뱃지로 표시된다 (source: 'dataset')
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '데이터셋' })).toBeVisible();
  });

  test('EmailTagInput — 유효하지 않은 이메일 입력 시 에러 메시지가 표시된다', async ({ authenticatedPage: page }) => {
    // 이메일 채널이 포함된 작업 모킹
    const jobWithEmail = createJob({
      id: 1,
      config: {
        channels: [{ type: 'EMAIL', recipientUserIds: [], recipientEmails: [] }],
      },
    });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', jobWithEmail);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/anomaly-events', []);
    await mockApi(page, 'GET', '/api/v1/users', { content: [], totalElements: 0, totalPages: 0, page: 0, size: 20 });

    await page.goto('/ai-insights/jobs/1');

    // 편집 모드 진입
    await page.getByRole('button', { name: '편집' }).click();

    // 알림 탭으로 이동 (채널 설정이 있는 탭)
    const notifyTab = page.getByRole('tab', { name: /알림|채널|전달/ });
    if (await notifyTab.isVisible()) {
      await notifyTab.click();
    }

    // 외부 이메일 입력란 확인
    const emailInput = page.getByPlaceholder('이메일 입력 후 Enter');
    await expect(emailInput).toBeVisible({ timeout: 5000 });

    // 잘못된 이메일 형식 입력 후 Enter
    await emailInput.fill('not-an-email');
    await emailInput.press('Enter');

    // 에러 메시지가 표시되어야 한다
    await expect(page.getByText('올바른 이메일 형식이 아닙니다')).toBeVisible({ timeout: 3000 });
  });

  test('EmailTagInput — 유효한 이메일 입력 시 태그가 추가된다', async ({ authenticatedPage: page }) => {
    // 이메일 채널이 포함된 작업 모킹
    const jobWithEmail = createJob({
      id: 1,
      config: {
        channels: [{ type: 'EMAIL', recipientUserIds: [], recipientEmails: [] }],
      },
    });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', jobWithEmail);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/anomaly-events', []);
    await mockApi(page, 'GET', '/api/v1/users', { content: [], totalElements: 0, totalPages: 0, page: 0, size: 20 });

    await page.goto('/ai-insights/jobs/1');

    // 편집 모드 진입
    await page.getByRole('button', { name: '편집' }).click();

    // 알림 탭으로 이동
    const notifyTab = page.getByRole('tab', { name: /알림|채널|전달/ });
    if (await notifyTab.isVisible()) {
      await notifyTab.click();
    }

    // 외부 이메일 입력란에 유효한 이메일 입력 후 Enter
    const emailInput = page.getByPlaceholder('이메일 입력 후 Enter');
    await expect(emailInput).toBeVisible({ timeout: 5000 });
    await emailInput.fill('test@example.com');
    await emailInput.press('Enter');

    // 이메일 태그(Badge)가 추가되어야 한다
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: 'test@example.com' })).toBeVisible({ timeout: 3000 });

    // 에러 메시지는 표시되지 않아야 한다
    await expect(page.getByText('올바른 이메일 형식이 아닙니다')).not.toBeVisible();
  });

  test('작업 삭제 — DELETE API 호출 검증', async ({ authenticatedPage: page }) => {
    await setupJobDetailMocks(page, 1);

    // DELETE /api/v1/proactive/jobs/1 캡처 설정 — goto 이전에 등록해야 한다
    const deleteCapture = await mockApi(
      page,
      'DELETE',
      '/api/v1/proactive/jobs/1',
      {},
      { capture: true },
    );
    // 삭제 후 목록 페이지로 이동하므로 목록 API도 모킹
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', []);

    await page.goto('/ai-insights/jobs/1');

    // 삭제 버튼 클릭 → 확인 다이얼로그 표시됨 (#37)
    await page.getByRole('button', { name: '삭제' }).click();
    // AlertDialog에서 "삭제" 확인 버튼 클릭
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();

    // DELETE API가 실제로 호출되었는지 확인
    const req = await deleteCapture.waitForRequest();
    expect(req).toBeTruthy();
  });

  test('작업 실행 — POST API 호출 검증', async ({ authenticatedPage: page }) => {
    await setupJobDetailMocks(page, 1);

    // POST /api/v1/proactive/jobs/1/execute 캡처 설정
    const executeCapture = await mockApi(
      page,
      'POST',
      '/api/v1/proactive/jobs/1/execute',
      {},
      { capture: true },
    );

    await page.goto('/ai-insights/jobs/1');

    // 지금 실행 버튼 클릭
    await page.getByRole('button', { name: '지금 실행' }).click();

    // POST API가 실제로 호출되었는지 확인
    const req = await executeCapture.waitForRequest();
    expect(req).toBeTruthy();
  });

  test('작업 복제 — POST API 호출 및 새 작업으로 이동', async ({ authenticatedPage: page }) => {
    await setupJobDetailMocks(page, 1);

    // 복제는 useCloneProactiveJob → proactiveApi.createJob (POST /api/v1/proactive/jobs) 호출
    // 복제된 새 작업(id=99) 반환을 모킹한다
    const cloneCapture = await mockApi(
      page,
      'POST',
      '/api/v1/proactive/jobs',
      createJob({ id: 99, name: '매일 현황 리포트 (복사본)' }),
      { capture: true },
    );
    // 복제 후 새 작업 상세 페이지로 이동하므로 해당 API도 모킹
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/99', createJob({ id: 99, name: '매일 현황 리포트 (복사본)' }));
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/99/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/99/anomaly-events', []);

    await page.goto('/ai-insights/jobs/1');

    // 복제 버튼 클릭
    await page.getByRole('button', { name: '복제' }).click();

    // POST /api/v1/proactive/jobs API가 호출되었는지 확인 (복사본 이름 포함)
    const req = await cloneCapture.waitForRequest();
    expect(req.payload).toMatchObject({ name: '매일 현황 리포트 (복사본)' });

    // 새 작업 상세 페이지(id=99)로 이동 확인
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/99/);
  });

  test('개요 탭 — triggerType ANOMALY 작업에 "이상 탐지" 배지가 표시된다', async ({ authenticatedPage: page }) => {
    // triggerType: ANOMALY 작업 모킹
    const anomalyJob = createJob({ id: 1, triggerType: 'ANOMALY', name: '이상 탐지 작업' });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', anomalyJob);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/anomaly-events', []);

    await page.goto('/ai-insights/jobs/1');
    await expect(page.getByRole('heading', { name: '이상 탐지 작업' })).toBeVisible();

    // 개요 탭이 기본 활성 — 트리거 유형 섹션의 "이상 탐지" 배지 확인
    // JobOverviewTab 읽기 모드: triggerType === 'ANOMALY' → Badge에 '이상 탐지' 렌더링
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '이상 탐지' })).toBeVisible({ timeout: 5000 });
  });

  test('개요 탭 — lastExecution이 있는 작업에 마지막 실행 상태가 표시된다', async ({ authenticatedPage: page }) => {
    // lastExecution 포함 작업 모킹 (FAILED 상태, 에러 메시지 포함)
    const jobWithLastExec = createJob({
      id: 1,
      lastExecution: createJobExecution({
        id: 10,
        jobId: 1,
        status: 'FAILED',
        errorMessage: '파이프라인 연결 오류',
      }),
    });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', jobWithLastExec);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/anomaly-events', []);

    await page.goto('/ai-insights/jobs/1');

    // 개요 탭에 "마지막 실행 상태" 섹션이 표시된다
    await expect(page.getByText('마지막 실행 상태')).toBeVisible({ timeout: 5000 });

    // FAILED 상태 배지 확인
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '실패' }).first()).toBeVisible();

    // 에러 메시지 확인
    await expect(page.getByText('파이프라인 연결 오류')).toBeVisible();
  });

  test('편집 모드 — 트리거 유형 ANOMALY 선택 시 설명 텍스트가 변경된다', async ({ authenticatedPage: page }) => {
    await setupJobDetailMocks(page, 1);

    await page.goto('/ai-insights/jobs/1');
    await expect(page.getByRole('heading', { name: '매일 현황 리포트' })).toBeVisible();

    // 편집 모드 진입
    await page.getByRole('button', { name: '편집' }).click();

    // 트리거 유형 Select — id="job-trigger-type"
    await page.locator('#job-trigger-type').click();

    // '이상 탐지 (이벤트 기반)' 선택
    await page.getByRole('option', { name: '이상 탐지 (이벤트 기반)' }).click();

    // triggerType === 'ANOMALY' 분기 설명 텍스트 확인
    await expect(page.getByText(/이상 탐지 시에만 실행됩니다/)).toBeVisible();
  });

  test('시스템 메트릭 추가 동작 검증', async ({ authenticatedPage: page }) => {
    // 이상 탐지 활성화된 작업 설정
    const jobWithAnomaly = createJob({
      id: 1,
      config: {
        channels: [{ type: 'CHAT', recipientUserIds: [], recipientEmails: [] }],
        anomaly: { enabled: true, metrics: [], sensitivity: 'medium', cooldownMinutes: 30 },
      },
    });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', jobWithAnomaly);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/anomaly-events', []);
    // 데이터셋 목록 모킹 (커스텀 메트릭 모달용)
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse([]));

    await page.goto('/ai-insights/jobs/1');

    // 편집 버튼 클릭으로 편집 모드 진입
    await page.getByRole('button', { name: '편집' }).click();

    // 모니터링 탭으로 이동
    await page.getByRole('tab', { name: /모니터링/ }).click();

    // "시스템 메트릭 추가" Select Trigger 클릭
    // SYSTEM_METRICS 중 아직 추가되지 않은 항목이 표시된다 (현재 metrics: [] 이므로 전체 4개 표시)
    await page.getByText('시스템 메트릭 추가').click();

    // 드롭다운에 시스템 메트릭 항목이 표시되는지 확인
    // SYSTEM_METRICS: 파이프라인 실패율, 파이프라인 실행 건수, 데이터셋 수, 활성 사용자 수
    await expect(page.getByRole('option', { name: '파이프라인 실패율' })).toBeVisible();
    await expect(page.getByRole('option', { name: '파이프라인 실행 건수' })).toBeVisible();

    // '파이프라인 실패율' 선택
    // Radix Select 드롭다운 팝오버가 뷰포트 밖에 렌더링될 수 있으므로
    // dispatchEvent로 포인터 이벤트를 직접 발생시킨다
    await page.getByRole('option', { name: '파이프라인 실패율' }).dispatchEvent('click');

    // 선택한 메트릭이 모니터링 메트릭 목록에 추가되었는지 확인 — 메트릭 이름 표시
    await expect(page.getByText('파이프라인 실패율')).toBeVisible();

    // 시스템 메트릭은 "시스템" 뱃지로 표시된다 (source: 'system')
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '시스템' })).toBeVisible();

    // 추가된 후 '파이프라인 실패율'은 드롭다운에서 사라진다 (중복 방지)
    // selectKey 증가로 Select가 리마운트되어 다시 열면 해당 항목이 없다
    await page.getByText('시스템 메트릭 추가').click();
    await expect(page.getByRole('option', { name: '파이프라인 실패율' })).not.toBeVisible();
  });
});
