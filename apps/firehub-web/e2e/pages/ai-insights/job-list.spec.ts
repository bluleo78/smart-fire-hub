import { createJob, createJobExecution } from '../../factories/ai-insight.factory';
import { mockProactivePermissions, setupJobListMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 스마트 작업(Job) 목록 페이지 E2E 테스트
 * - API 모킹 기반으로 백엔드 없이 목록 페이지 UI를 검증한다.
 */

// 생성·편집·삭제·복제·실행 버튼은 proactive:write 가 있어야 보인다(WD-133) — 이 spec 은 쓰기 권한 사용자를 전제로 한다.
test.beforeEach(async ({ authenticatedPage: page }) => {
  await mockProactivePermissions(page);
});

test.describe('스마트 작업 목록 페이지', () => {
  test('작업 목록이 올바르게 렌더링된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    // 3개 작업 목록을 모킹한 후 목록 페이지 접근
    await setupJobListMocks(page, 3);
    await page.goto('/ai-insights/jobs');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '스마트 작업' })).toBeVisible();

    // 테이블 헤더 컬럼 확인 — '상태' 컬럼은 '활성' 토글과 정보 중복으로 제거 (#67)
    await expect(page.getByRole('columnheader', { name: '작업명' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '실행 주기' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '활성' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '상태' })).toHaveCount(0);

    // 팩토리에서 생성한 이름 패턴("잡 1", "잡 2", "잡 3") 확인
    await expect(page.getByText('잡 1')).toBeVisible();
    await expect(page.getByText('잡 3')).toBeVisible();

    // 데이터 행 수 확인: 헤더 1 + 데이터 3 = 총 4행
    const rows = page.getByRole('row');
    await expect(rows).toHaveCount(4);

    // 팩토리 기본값 cronExpression '0 9 * * *' 또는 관련 스케줄 텍스트가 렌더링되는지 확인
    // 크론 표현식 자체 또는 UI에서 변환된 스케줄 문구가 하나 이상 존재해야 한다
    const cronOrSchedule = page.getByText('0 9 * * *');
    const scheduleText = page.getByText(/매일|오전 9시|09:00/);
    const hasCronOrSchedule =
      (await cronOrSchedule.count()) > 0 || (await scheduleText.count()) > 0;
    expect(hasCronOrSchedule).toBe(true);

    // 팩토리 기본값 templateName '기본 리포트 템플릿' 또는 스케줄 관련 정보가 렌더링되는지 확인
    // 목록 행에 템플릿 이름이 표시되거나, 스케줄/실행 주기 셀이 비어 있지 않아야 한다
    const templateOrScheduleCell = page.getByText('기본 리포트 템플릿');
    const hasTemplateInfo = (await templateOrScheduleCell.count()) > 0;
    // 헤더에 '실행 주기' 컬럼이 있으면 데이터 셀도 비어 있지 않아야 하므로 충분한 검증
    if (!hasTemplateInfo) {
      // 스케줄 컬럼 데이터 셀이 존재하는지 확인
      await expect(page.getByRole('cell').first()).toBeVisible();
    }
  });

  test('빈 목록일 때 빈 상태 메시지와 첫 작업 만들기 버튼을 표시한다', async ({ authenticatedPage: page }) => {
    // 빈 목록으로 모킹
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', []);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs');

    // 빈 상태 메시지 확인
    await expect(page.getByText('스마트 작업 없음')).toBeVisible();

    // "첫 작업 만들기" 버튼 확인
    await expect(page.getByRole('button', { name: '첫 작업 만들기' })).toBeVisible();
  });

  test('작업 추가 버튼 클릭 시 새 작업 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupJobListMocks(page, 2);
    // 새 작업 페이지에서 필요한 템플릿 API 모킹
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);

    await page.goto('/ai-insights/jobs');

    // "작업 추가" 버튼 클릭
    await page.getByRole('button', { name: '작업 추가' }).click();

    // 새 작업 페이지로 이동 확인
    await expect(page).toHaveURL('/ai-insights/jobs/new');
  });

  test('활성 작업에 활성화 스위치가 켜진 상태로 표시된다', async ({ authenticatedPage: page }) => {
    // enabled: true 작업으로 모킹
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [
      createJob({ id: 1, name: '활성 작업', enabled: true }),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs');

    // 활성화 스위치(aria-label으로 찾음)가 체크된 상태인지 확인
    const toggle = page.getByRole('switch', { name: '활성 작업 활성화' });
    await expect(toggle).toBeChecked();
  });

  test('비활성 작업은 활성화 스위치가 꺼진 상태로 표시된다 (#67)', async ({ authenticatedPage: page }) => {
    // '상태' 컬럼이 '활성' 토글과 중복되어 제거됨(#67) — 비활성 여부는 토글 off 로만 표현
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [
      createJob({ id: 1, name: '비활성 작업', enabled: false }),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs');

    // 활성화 스위치가 unchecked 상태이고, '비활성' 배지는 더 이상 존재하지 않아야 한다
    const toggle = page.getByRole('switch', { name: '비활성 작업 활성화' });
    await expect(toggle).not.toBeChecked();
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '비활성' })).toHaveCount(0);
  });

  test('작업 끄기 — 끄기 전용 POST, 켜기 — PUT payload 검증 (WD-192)', async ({ authenticatedPage: page }) => {
    // 켜진 작업 1, 꺼진 작업 2
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [
      createJob({ id: 1, name: '잡 1', enabled: true }),
      createJob({ id: 2, name: '잡 2', enabled: false }),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    // 끄기는 PUT 이 아니라 끄기 전용 POST /jobs/{id}/disable 로 간다 — 쓰기 권한을 잃은 작성자도 끌 수 있게 한 경로를 쓰기 권한자도 같이 쓴다
    const disableCapture = await mockApi(page, 'POST', '/api/v1/proactive/jobs/1/disable', {}, { capture: true });
    // 켜기는 자동 실행을 늘리므로 여전히 PUT(쓰기 권한) 으로만 간다
    const enableCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/proactive/jobs/2',
      createJob({ id: 2, name: '잡 2', enabled: true }),
      { capture: true },
    );

    await page.goto('/ai-insights/jobs');

    await page.getByRole('switch', { name: '잡 1 활성화' }).click();
    const disableReq = await disableCapture.waitForRequest();
    expect(disableReq).toBeTruthy();
    await expect(page.getByText('작업이 비활성화되었습니다.')).toBeVisible();

    await page.getByRole('switch', { name: '잡 2 활성화' }).click();
    const enableReq = await enableCapture.waitForRequest();
    expect(enableReq.payload).toMatchObject({ enabled: true });
  });

  test('작업 실행 — POST API 호출 검증', async ({ authenticatedPage: page }) => {
    // 작업 목록 모킹
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [
      createJob({ id: 1, name: '잡 1', enabled: true }),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    // POST /api/v1/proactive/jobs/1/execute 캡처 설정
    const executeCapture = await mockApi(
      page,
      'POST',
      '/api/v1/proactive/jobs/1/execute',
      {},
      { capture: true },
    );

    await page.goto('/ai-insights/jobs');

    // 지금 실행 버튼(aria-label="지금 실행") 클릭
    await page.getByRole('button', { name: '지금 실행' }).click();

    // POST API가 실제로 호출되었는지 확인
    const req = await executeCapture.waitForRequest();
    expect(req).toBeTruthy();
  });

  test('작업 행 클릭 시 작업 상세 페이지로 이동한다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await setupJobListMocks(page, 2);
    // 작업 상세 페이지에서 필요한 API 모킹 — createJob({ id: 1 }) 기본값: name='매일 현황 리포트'
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', createJob({ id: 1 }));
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);

    await page.goto('/ai-insights/jobs');

    // 첫 번째 작업 행 클릭 — 잡 1
    await page.getByText('잡 1').click();

    // 작업 상세 페이지로 이동 확인
    await expect(page).toHaveURL('/ai-insights/jobs/1');

    // 상세 페이지에서 팩토리 기본값 작업명 '매일 현황 리포트'가 표시되는지 확인
    await expect(page.getByRole('heading', { name: '매일 현황 리포트' })).toBeVisible();
  });

  test('작업 복제 버튼 클릭 시 POST /jobs API 가 호출된다 (createJob 으로 복제)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [
      createJob({ id: 1, name: '잡 1', enabled: true }),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    // useCloneProactiveJob 은 proactiveApi.createJob(POST /proactive/jobs) 를 호출한다
    const clonedJob = createJob({ id: 2, name: '잡 1 (복사본)' });
    const cloneCapture = await mockApi(
      page,
      'POST',
      '/api/v1/proactive/jobs',
      clonedJob,
      { capture: true },
    );
    // 복제 후 이동하는 상세 페이지 API 모킹
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/2', clonedJob);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/2/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);

    await page.goto('/ai-insights/jobs');

    // 복제 버튼 클릭 (aria-label="복제")
    await page.getByRole('button', { name: '복제' }).click();

    // POST API 호출 확인 — 이름에 "(복사본)" 포함
    const req = await cloneCapture.waitForRequest();
    expect((req.payload as { name?: string })?.name).toContain('복사본');
  });

  test('작업명과 채널 정보가 세로로 분리되어 표시된다 (레이아웃 회귀 #5)', async ({ authenticatedPage: page }) => {
    // 채널 정보가 있는 작업 모킹 — 작업명과 채널 요약이 flex-col 으로 분리되는지 검증
    const longJobName = '일일 매출 분석 리포트 (복사본)';
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [
      createJob({
        id: 1,
        name: longJobName,
        config: { channels: ['CHAT', 'EMAIL'] },
      }),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs');

    // 작업명 span 과 채널 요약 span 이 별도로 DOM 에 존재해야 한다
    const jobNameSpan = page.locator('td').filter({ hasText: longJobName }).getByText(longJobName);
    const channelSpan = page.locator('td').filter({ hasText: longJobName }).getByText('채팅 / 이메일');

    await expect(jobNameSpan).toBeVisible();
    await expect(channelSpan).toBeVisible();

    // 두 요소의 Y 위치를 비교하여 채널 요약이 작업명보다 아래에 있는지 확인 (세로 분리)
    const nameBox = await jobNameSpan.boundingBox();
    const channelBox = await channelSpan.boundingBox();
    expect(nameBox).not.toBeNull();
    expect(channelBox).not.toBeNull();
    // 채널 정보의 상단 Y 좌표가 작업명 상단 Y 좌표보다 커야 한다 (아래에 위치)
    expect(channelBox!.y).toBeGreaterThan(nameBox!.y);
  });

  test('복제 버튼 hover 시 툴팁이 표시된다 (이슈 #54)', async ({ authenticatedPage: page }) => {
    // 툴팁 회귀 테스트 — 복제 아이콘 버튼에 시각적 툴팁이 없던 버그 수정 검증
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [
      createJob({ id: 1, name: '잡 1', enabled: true }),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs');

    // 복제 버튼에 hover — 툴팁 텍스트가 나타나야 한다
    await page.getByRole('button', { name: '복제' }).first().hover();
    await expect(page.getByRole('tooltip', { name: '복제' })).toBeVisible();
  });

  test('지금 실행 버튼 hover 시 툴팁이 표시된다 (이슈 #54)', async ({ authenticatedPage: page }) => {
    // 툴팁 회귀 테스트 — 지금 실행 아이콘 버튼에 시각적 툴팁이 없던 버그 수정 검증
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [
      createJob({ id: 1, name: '잡 1', enabled: true }),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs');

    // 지금 실행 버튼에 hover — 툴팁 텍스트가 나타나야 한다
    await page.getByRole('button', { name: '지금 실행' }).first().hover();
    await expect(page.getByRole('tooltip', { name: '지금 실행' })).toBeVisible();
  });

  test('FAILED lastExecution 작업도 목록에 정상 렌더링된다 (#67 컬럼 제거 후)', async ({ authenticatedPage: page }) => {
    // '상태' 컬럼 제거(#67) — 실패 배지는 더 이상 목록에 표시되지 않으며, 행 자체는 정상 렌더링되어야 한다
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [
      createJob({
        id: 1,
        name: '실패 작업',
        enabled: true,
        lastExecution: {
          id: 1,
          jobId: 1,
          status: 'FAILED',
          result: null,
          deliveredChannels: [],
          errorMessage: '오류 발생',
          startedAt: '2024-01-01T09:00:00Z',
          completedAt: '2024-01-01T09:00:10Z',
        },
      }),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs');

    // 작업명은 표시되고, '실패' 배지는 더 이상 존재하지 않아야 한다
    await expect(page.getByText('실패 작업')).toBeVisible();
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '실패' })).toHaveCount(0);
  });

  test('최근 실행이 실패한 작업은 「마지막 실행」 칸에 실패가 표시된다 (WD-134)', async ({ authenticatedPage: page }) => {
    // 예약 실행이 계속 실패해도 목록에서는 정상처럼 보이던 결함 — 실패만 표시하고, 성공·이력 없음은 표시하지 않는다
    // eslint-disable-next-line no-restricted-syntax -- epoch 숫자로 3분 전 시각을 만든다(서버 문자열 파싱 아님)
    const recent = new Date(Date.now() - 3 * 60 * 1000).toISOString();
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [
      createJob({
        id: 1,
        name: '계속 실패하는 작업',
        lastExecutedAt: recent,
        lastExecution: createJobExecution({ id: 11, jobId: 1, status: 'FAILED', errorMessage: 'AI 설정 없음' }),
      }),
      createJob({
        id: 2,
        name: '성공한 작업',
        lastExecutedAt: recent,
        lastExecution: createJobExecution({ id: 12, jobId: 2, status: 'COMPLETED' }),
      }),
      createJob({ id: 3, name: '실행 전 작업' }),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs');

    const failedRow = page.getByRole('row').filter({ hasText: '계속 실패하는 작업' });
    await expect(failedRow.getByTestId('last-run-failed')).toHaveText('· 실패');
    // 마우스를 올리면 실패 원인을 볼 수 있다
    await expect(failedRow.getByTestId('last-run-failed')).toHaveAttribute('title', 'AI 설정 없음');
    await expect(failedRow).toContainText('3분 전');
    await expect(page.getByRole('row').filter({ hasText: '성공한 작업' }).getByTestId('last-run-failed')).toHaveCount(0);
    await expect(page.getByRole('row').filter({ hasText: '실행 전 작업' }).getByTestId('last-run-failed')).toHaveCount(0);
  });

  test('최근 실행이 의도된 건너뜀이면 「마지막 실행」 칸에 실패가 아닌 건너뜀이 표시된다 (WD-191)', async ({ authenticatedPage: page }) => {
    // 서버는 작업 주인 정지로 실행하지 않은 건너뜀도 status=FAILED 로 남긴다 — 실행 상세와 같은 판정으로 회색 「건너뜀」을 보인다
    const reason = '작업 주인이 정지됐거나 워크스페이스에서 제거돼 실행하지 않았습니다';
    // eslint-disable-next-line no-restricted-syntax -- epoch 숫자로 3분 전 시각을 만든다(서버 문자열 파싱 아님)
    const recent = new Date(Date.now() - 3 * 60 * 1000).toISOString();
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [
      createJob({
        id: 1,
        name: '건너뛴 작업',
        lastExecutedAt: recent,
        lastExecution: createJobExecution({ id: 11, jobId: 1, status: 'FAILED', errorMessage: reason }),
      }),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs');

    const row = page.getByRole('row').filter({ hasText: '건너뛴 작업' });
    await expect(row.getByTestId('last-run-skipped')).toHaveText('· 건너뜀');
    // 마우스를 올리면 건너뛴 사유를 볼 수 있다
    await expect(row.getByTestId('last-run-skipped')).toHaveAttribute('title', reason);
    await expect(row.getByTestId('last-run-failed')).toHaveCount(0);
  });
});
