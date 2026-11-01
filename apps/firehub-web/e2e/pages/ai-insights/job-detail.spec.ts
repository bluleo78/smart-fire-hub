import { createJob, createJobExecution, createTemplate, createTemplates } from '../../factories/ai-insight.factory';
import { createDataset } from '../../factories/dataset.factory';
import {
  mockProactivePermissions,
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

// 생성·편집·삭제·복제·실행 버튼은 proactive:write 가 있어야 보인다(WD-133) — 이 spec 은 쓰기 권한 사용자를 전제로 한다.
test.beforeEach(async ({ authenticatedPage: page }) => {
  await mockProactivePermissions(page);
});

test.describe('스마트 작업 상세 페이지', () => {
  test('작업 정보가 올바르게 렌더링된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
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

  test('실행 이력 탭에서 실행 목록을 확인할 수 있다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
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

  test('새 작업 페이지에서 생성 폼이 표시된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
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

  test('새 작업 폼 — 필수 필드(작업 이름·프롬프트)가 비어 있으면 생성 버튼이 비활성화된다 (#52)', async ({ authenticatedPage: page }) => {
    // 새 작업 페이지 API 모킹
    await setupNewJobMocks(page);

    await page.goto('/ai-insights/jobs/new');

    // 초기 상태: 작업 이름과 프롬프트 미입력 → 생성 버튼 비활성화 확인
    const createBtn = page.getByRole('button', { name: '생성' });
    await expect(createBtn).toBeDisabled();

    // 작업 이름만 입력해도 아직 비활성화 (프롬프트 미입력)
    await page.locator('#job-name').fill('테스트 작업');
    // 수동 모드 진입 — 프롬프트 필드 노출을 위해
    const manualModeBtn = page.getByRole('button', { name: /수동|직접/ });
    if (await manualModeBtn.isVisible()) {
      await manualModeBtn.click();
    }

    // 프롬프트 필드가 보이는 경우만 검증
    const promptField = page.locator('#job-prompt');
    if (await promptField.isVisible()) {
      // 프롬프트 미입력 → 여전히 비활성화
      await expect(createBtn).toBeDisabled();

      // 프롬프트까지 입력 → 생성 버튼 활성화
      await promptField.fill('일별 데이터 분석 프롬프트');
      await expect(createBtn).toBeEnabled();
    }
  });

  test('새 작업 폼 — "직접 설정" → "목표 기반" 전환 시 이전 프롬프트가 초기화되고, 템플릿 생성 전에는 생성 버튼이 비활성화된다 (#523)', async ({ authenticatedPage: page }) => {
    // 새 작업 페이지 API 모킹
    await setupNewJobMocks(page);

    // 생성 API 요청 페이로드를 캡처해 실제 제출값을 검증한다
    const createCapture = await mockApi(
      page,
      'POST',
      '/api/v1/proactive/jobs',
      createJob({ id: 99, name: '이번 달 매출 하락 원인 분석' }),
      { capture: true },
    );
    // 목표 기반 생성은 섹션 구성을 리포트 양식으로 먼저 저장한다(WD-297)
    await mockApi(page, 'POST', '/api/v1/proactive/templates', createTemplate({ id: 77 }));

    await page.goto('/ai-insights/jobs/new');

    // 1. "직접 설정" 모드에서 프롬프트를 입력한다 (stale 값이 될 텍스트)
    await page.locator('#job-name').fill('테스트 작업');
    await page.locator('#job-prompt').fill('테스트 프롬프트 내용 재현용입니다');

    const createBtn = page.getByRole('button', { name: '생성', exact: true });
    await expect(createBtn).toBeEnabled();

    // 2. "목표 기반" 모드로 전환 — 이전 프롬프트 입력란은 숨겨지고 비즈니스 질문 UI가 나타난다
    await page.locator('#mode-goal').click();
    await expect(page.getByLabel('비즈니스 질문')).toBeVisible();
    await expect(page.locator('#job-prompt')).toHaveCount(0);

    // 3. 비즈니스 질문을 비워둔 채(템플릿 자동 생성 클릭 안 함) — stale prompt가 여전히 폼에
    //    남아 생성 버튼이 활성화되는 것이 이슈 #523의 근본 원인이었다.
    //    수정 후에는 모드 전환 시 prompt가 초기화되어 생성 버튼이 비활성화되어야 한다.
    await expect(createBtn).toBeDisabled();

    // 4. 비즈니스 질문 입력 후 "템플릿 자동 생성"을 클릭하면 prompt가 채워지며
    //    생성 버튼이 다시 활성화되어야 한다.
    await page.getByLabel('비즈니스 질문').fill('이번 달 매출 하락 원인 분석');
    await page.getByRole('button', { name: '템플릿 자동 생성' }).click();
    await expect(createBtn).toBeEnabled();

    // 5. 실제 제출된 payload의 prompt가 목표 기반 모드의 비즈니스 질문이며,
    //    "직접 설정" 모드에서 입력했던 stale 텍스트가 아님을 검증한다.
    await createBtn.click();
    const req = await createCapture.waitForRequest();
    const payload = req.payload as { prompt: string };
    expect(payload.prompt).toBe('이번 달 매출 하락 원인 분석');
    expect(payload.prompt).not.toContain('테스트 프롬프트 내용 재현용입니다');
  });

  test('새 작업 폼 — 이름을 비운 채 "목표 기반"으로 템플릿을 자동 생성하면 자동 채운 이름만으로 바로 생성되고, 미리보기 구성이 리포트 양식으로 저장·연결된다 (WD-297)', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    const templateCapture = await mockApi(page, 'POST', '/api/v1/proactive/templates', createTemplate({ id: 77 }), {
      capture: true,
    });
    const createCapture = await mockApi(
      page,
      'POST',
      '/api/v1/proactive/jobs',
      createJob({ id: 98, name: '지난주 대비 화재 건수 변화는?' }),
      { capture: true },
    );

    await page.goto('/ai-insights/jobs/new');

    // 이름을 입력하지 않고 바로 목표 기반 모드로 질문 → 템플릿 자동 생성
    await page.locator('#mode-goal').click();
    await page.getByLabel('비즈니스 질문').fill('지난주 대비 화재 건수 변화는?');
    await page.getByRole('button', { name: '템플릿 자동 생성' }).click();

    // 이름 칸이 질문으로 채워지고, 다른 입력 없이 "생성"이 활성화되어야 한다(수정 전에는 비활성으로 남았다)
    await expect(page.locator('#job-name')).toHaveValue('지난주 대비 화재 건수 변화는?');
    // 무엇이 저장되는지 미리 안내한다
    await expect(
      page.getByText('작업을 생성하면 이 구성이 리포트 양식 「지난주 대비 화재 건수 변화는? 양식」(으)로 저장되어 연결됩니다.'),
    ).toBeVisible();
    const createBtn = page.getByRole('button', { name: '생성', exact: true });
    await expect(createBtn).toBeEnabled();

    await createBtn.click();

    // 1) 미리보기의 4개 섹션이 「<작업 이름> 양식」으로 먼저 저장된다
    const templateReq = await templateCapture.waitForRequest();
    const templatePayload = templateReq.payload as { name: string; sections: { key: string; label: string }[] };
    expect(templatePayload.name).toBe('지난주 대비 화재 건수 변화는? 양식');
    expect(templatePayload.sections.map((sec) => sec.label)).toEqual(['요약', '상세 분석', '주요 지표', '권고사항']);

    // 2) 작업은 그 양식(id 77)을 연결해 만들어진다(수정 전에는 templateId 없이 「기본 형식」)
    const req = await createCapture.waitForRequest();
    expect(req.payload).toMatchObject({
      name: '지난주 대비 화재 건수 변화는?',
      prompt: '지난주 대비 화재 건수 변화는?',
      templateId: 77,
    });
    await expect(page).toHaveURL('/ai-insights/jobs/98');
  });

  test('목표 기반 — 리포트 양식 저장이 실패하면 작업을 만들지 않고 사유를 알린다 (WD-297)', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    await mockApi(
      page,
      'POST',
      '/api/v1/proactive/templates',
      { status: 500, message: '양식 저장 중 오류' },
      { status: 500 },
    );
    const createCapture = await mockApi(page, 'POST', '/api/v1/proactive/jobs', createJob({ id: 98 }), {
      capture: true,
    });

    await page.goto('/ai-insights/jobs/new');
    await page.locator('#mode-goal').click();
    await page.getByLabel('비즈니스 질문').fill('주간 화재 추이');
    await page.getByRole('button', { name: '템플릿 자동 생성' }).click();
    await page.getByRole('button', { name: '생성', exact: true }).click();

    await expect(page.getByText('양식 저장 중 오류')).toBeVisible();
    expect(createCapture.requests).toHaveLength(0);
    await expect(page).toHaveURL('/ai-insights/jobs/new');
  });

  test('목표 기반 — 작업 생성이 실패하면 방금 저장한 리포트 양식을 지운다 (WD-297)', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    await mockApi(page, 'POST', '/api/v1/proactive/templates', createTemplate({ id: 77 }));
    await mockApi(page, 'POST', '/api/v1/proactive/jobs', { status: 400, message: '작업 생성 거부' }, { status: 400 });
    const deleteCapture = await mockApi(page, 'DELETE', '/api/v1/proactive/templates/77', null, { capture: true });

    await page.goto('/ai-insights/jobs/new');
    await page.locator('#mode-goal').click();
    await page.getByLabel('비즈니스 질문').fill('주간 화재 추이');
    await page.getByRole('button', { name: '템플릿 자동 생성' }).click();
    await page.getByRole('button', { name: '생성', exact: true }).click();

    await expect(page.getByText('작업 생성 거부')).toBeVisible();
    await deleteCapture.waitForRequest();
    expect(deleteCapture.requests).toHaveLength(1);
  });

  test('목표 기반으로 구성을 만든 뒤 「직접 설정」으로 돌아가 만들면 양식을 저장하지 않는다 (WD-297)', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    const templateCapture = await mockApi(page, 'POST', '/api/v1/proactive/templates', createTemplate({ id: 77 }), {
      capture: true,
    });
    const createCapture = await mockApi(page, 'POST', '/api/v1/proactive/jobs', createJob({ id: 98 }), {
      capture: true,
    });

    await page.goto('/ai-insights/jobs/new');
    await page.locator('#mode-goal').click();
    await page.getByLabel('비즈니스 질문').fill('주간 화재 추이');
    await page.getByRole('button', { name: '템플릿 자동 생성' }).click();
    // 구성이 만들어진 뒤(미리보기가 보인 뒤) 모드를 바꾼다 — 생성 중 전환은 이 테스트의 관심사가 아니다
    await expect(page.getByText('자동 생성된 템플릿 (4개 섹션)')).toBeVisible();

    await page.locator('#mode-manual').click();
    await page.locator('#job-prompt').fill('직접 쓴 프롬프트');
    await page.getByRole('button', { name: '생성', exact: true }).click();

    const req = await createCapture.waitForRequest();
    expect(req.payload).toMatchObject({ prompt: '직접 쓴 프롬프트', templateId: null });
    expect(templateCapture.requests).toHaveLength(0);
  });

  test('목표 기반 — 작업 생성 요청이 응답 없이 끊기면 서버가 이미 만들었을 수 있어 양식을 지우지 않는다 (WD-297)', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    await mockApi(page, 'POST', '/api/v1/proactive/templates', createTemplate({ id: 77 }));
    // 작업 생성 요청은 연결이 끊긴 것처럼 응답 없이 실패시킨다
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/jobs',
      (route) => (route.request().method() === 'POST' ? route.abort('connectionreset') : route.fallback()),
    );
    const deleteCapture = await mockApi(page, 'DELETE', '/api/v1/proactive/templates/77', null, { capture: true });

    await page.goto('/ai-insights/jobs/new');
    await page.locator('#mode-goal').click();
    await page.getByLabel('비즈니스 질문').fill('주간 화재 추이');
    await page.getByRole('button', { name: '템플릿 자동 생성' }).click();
    await page.getByRole('button', { name: '생성', exact: true }).click();

    await expect(page.getByText('작업 생성에 실패했습니다.')).toBeVisible();
    expect(deleteCapture.requests).toHaveLength(0);
  });

  test('목표 기반 — 구성을 만든 뒤 질문을 고치거나 모드를 오가면 이전 구성은 버려지고 다시 만들어야 생성할 수 있다 (WD-297)', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    await page.goto('/ai-insights/jobs/new');
    await page.locator('#mode-goal').click();
    const preview = page.getByText('자동 생성된 템플릿 (4개 섹션)');
    const createBtn = page.getByRole('button', { name: '생성', exact: true });

    // 1) 「직접 설정」으로 갔다 돌아오면 미리보기도 비워진다(구성은 이미 비워졌으므로 안내가 남으면 거짓이다)
    await page.getByLabel('비즈니스 질문').fill('주간 화재 추이');
    await page.getByRole('button', { name: '템플릿 자동 생성' }).click();
    await expect(preview).toBeVisible();
    await page.locator('#mode-manual').click();
    await page.locator('#mode-goal').click();
    await expect(preview).toBeHidden();
    await expect(createBtn).toBeDisabled();

    // 2) 만든 뒤 질문을 고치면 이전 질문의 구성으로 저장되지 않게 미리보기가 사라지고 생성이 막힌다
    await page.getByRole('button', { name: '템플릿 자동 생성' }).click();
    await expect(preview).toBeVisible();
    await expect(createBtn).toBeEnabled();
    await page.getByLabel('비즈니스 질문').fill('월간 화재 추이');
    await expect(preview).toBeHidden();
    await expect(createBtn).toBeDisabled();
  });

  test('목표 기반 — 「템플릿 자동 생성」은 AI 가 만든 섹션 구성을 보여 주고, 그 구성이 리포트 양식으로 저장된다 (WD-455)', async ({ authenticatedPage: page }) => {
    await setupNewJobMocks(page);
    // setupNewJobMocks 의 기본 실패 모킹보다 나중에 등록해 성공 응답이 우선한다
    const aiSections = [
      { key: 'summary', type: 'text', label: '핵심 요약', instruction: '화재 건수 변화를 요약하세요.', required: true },
      { key: 'weekly_compare', type: 'comparison', label: '전주 대비 변화', instruction: '지난주와 비교하세요.' },
      { key: 'top_regions', type: 'table', label: '지역별 순위', instruction: '증가 지역을 표로 보여 주세요.' },
      { key: 'actions', type: 'recommendation', label: '대응 권고', instruction: '조치를 제안하세요.' },
      { key: 'risk_alert', type: 'alert', label: '위험 경보', instruction: '급증 지역을 경고하세요.' },
    ];
    const generateCapture = await mockApi(page, 'POST', '/api/v1/proactive/templates/generate', { sections: aiSections }, {
      capture: true,
    });
    const templateCapture = await mockApi(page, 'POST', '/api/v1/proactive/templates', createTemplate({ id: 77 }), {
      capture: true,
    });
    const createCapture = await mockApi(page, 'POST', '/api/v1/proactive/jobs', createJob({ id: 98 }), {
      capture: true,
    });

    await page.goto('/ai-insights/jobs/new');
    await page.locator('#mode-goal').click();
    await page.getByLabel('비즈니스 질문').fill('지난주 대비 화재 건수 변화는?');
    await page.getByRole('button', { name: '템플릿 자동 생성' }).click();

    // 1) 질문이 그대로 생성 API 로 간다
    const generateReq = await generateCapture.waitForRequest();
    expect(generateReq.payload).toEqual({ question: '지난주 대비 화재 건수 변화는?' });

    // 2) AI 구성(5개)이 미리보기에 보이고, 실패 안내는 없다
    await expect(page.getByText('자동 생성된 템플릿 (5개 섹션)')).toBeVisible();
    await expect(page.getByText('전주 대비 변화')).toBeVisible();
    await expect(page.getByText('위험 경보')).toBeVisible();
    await expect(page.getByText('AI 생성에 실패해 기본 구성(4개 섹션)으로 만들었습니다.')).toHaveCount(0);

    // 3) 생성하면 AI 구성 그대로 양식으로 저장되고 작업에 연결된다(WD-297 흐름 그대로)
    await page.getByRole('button', { name: '생성', exact: true }).click();
    const templateReq = await templateCapture.waitForRequest();
    const templatePayload = templateReq.payload as { sections: { key: string; label: string }[] };
    expect(templatePayload.sections.map((sec) => sec.key)).toEqual([
      'summary',
      'weekly_compare',
      'top_regions',
      'actions',
      'risk_alert',
    ]);
    const req = await createCapture.waitForRequest();
    expect(req.payload).toMatchObject({ prompt: '지난주 대비 화재 건수 변화는?', templateId: 77 });
  });

  test('목표 기반 — AI 생성이 실패하면 고정 4개 구성으로 대신하고 그 사실을 알린다 (WD-455)', async ({ authenticatedPage: page }) => {
    // setupNewJobMocks 가 생성 API 를 502 로 모킹한다
    await setupNewJobMocks(page);
    const templateCapture = await mockApi(page, 'POST', '/api/v1/proactive/templates', createTemplate({ id: 77 }), {
      capture: true,
    });
    await mockApi(page, 'POST', '/api/v1/proactive/jobs', createJob({ id: 98 }));

    await page.goto('/ai-insights/jobs/new');
    await page.locator('#mode-goal').click();
    await page.getByLabel('비즈니스 질문').fill('주간 화재 추이');
    await page.getByRole('button', { name: '템플릿 자동 생성' }).click();

    // 고정 구성 미리보기 + 대체 안내
    await expect(page.getByText('자동 생성된 템플릿 (4개 섹션)')).toBeVisible();
    await expect(page.getByText('AI 생성에 실패해 기본 구성(4개 섹션)으로 만들었습니다.')).toBeVisible();

    // 대체 구성으로도 작업을 만들 수 있고, 그 4개 섹션이 양식으로 저장된다
    const createBtn = page.getByRole('button', { name: '생성', exact: true });
    await expect(createBtn).toBeEnabled();
    await createBtn.click();
    const templateReq = await templateCapture.waitForRequest();
    const templatePayload = templateReq.payload as { sections: { label: string }[] };
    expect(templatePayload.sections.map((sec) => sec.label)).toEqual(['요약', '상세 분석', '주요 지표', '권고사항']);
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

  test('실행 이력의 시작 시각 링크로 키보드(Tab·Enter)만으로 실행 상세를 연다 (WD-436)', async ({ authenticatedPage: page }) => {
    // 행 클릭은 마우스로만 열려 키보드 사용자는 실행 상세에 들어갈 수 없었다 — 시작 시각이 링크가 되어 Tab 으로 닿아야 한다
    const execution = createJobExecution({ id: 5, jobId: 1, status: 'COMPLETED' });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', createJob({ id: 1 }));
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', [execution]);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/5', execution);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/5/html', '', { status: 404 });

    await page.goto('/ai-insights/jobs/1?tab=executions');

    const link = page.getByRole('row').nth(1).getByRole('link');
    await expect(link).toHaveAttribute('href', '/ai-insights/jobs/1/executions/5');
    await link.focus();
    await expect(link).toBeFocused();
    await page.keyboard.press('Enter');

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

  test('리포트 템플릿을 「기본 (선택 안 함)」으로 되돌리면 해제 요청(clearTemplate)을 함께 보낸다 (WD-136)', async ({
    authenticatedPage: page,
  }) => {
    // templateId=null 만 보내면 서버가 "바꾸지 않음"으로 받아 옛 템플릿이 남았다
    const job = createJob({ id: 1, templateId: 2, templateName: '주간 통계 리포트' });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', job);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/anomaly-events', []);
    const putCapture = await mockApi(page, 'PUT', '/api/v1/proactive/jobs/1', {}, { capture: true });

    await page.goto('/ai-insights/jobs/1');
    await page.getByRole('button', { name: '편집' }).click();
    await page.locator('#job-template').click();
    await page.getByRole('option', { name: '기본 (선택 안 함)' }).click();
    await page.getByRole('button', { name: '저장' }).click();

    const req = await putCapture.waitForRequest();
    expect(req.payload).toMatchObject({ templateId: null, clearTemplate: true });
    await expect(page.getByText('작업이 저장되었습니다.')).toBeVisible();
  });

  test('리포트 템플릿을 고른 채 저장하면 해제 요청을 보내지 않는다 (WD-136)', async ({ authenticatedPage: page }) => {
    const job = createJob({ id: 1, templateId: 2, templateName: '주간 통계 리포트' });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', job);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/anomaly-events', []);
    const putCapture = await mockApi(page, 'PUT', '/api/v1/proactive/jobs/1', {}, { capture: true });

    await page.goto('/ai-insights/jobs/1');
    await page.getByRole('button', { name: '편집' }).click();
    await page.locator('#job-name').fill('이름만 변경');
    await page.getByRole('button', { name: '저장' }).click();

    const req = await putCapture.waitForRequest();
    expect(req.payload).toMatchObject({ templateId: 2, clearTemplate: false });
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

  // WD-430: 200자 이름은 「 (복사본)」을 붙이면 한도를 넘어 「작업 이름은 200자 이내」로 실패했다
  test('이름이 200자인 작업을 복제하면 원래 이름을 잘라 200자 안의 「(복사본)」 이름으로 보낸다', async ({
    authenticatedPage: page,
  }) => {
    await setupJobDetailMocks(page, 1);
    const longName = '나'.repeat(200);
    const clonedName = `${'나'.repeat(194)} (복사본)`;
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', createJob({ id: 1, name: longName }));
    const cloneCapture = await mockApi(
      page,
      'POST',
      '/api/v1/proactive/jobs',
      createJob({ id: 98, name: clonedName, enabled: false }),
      { capture: true },
    );
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/98', createJob({ id: 98, name: clonedName, enabled: false }));
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/98/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/98/anomaly-events', []);

    await page.goto('/ai-insights/jobs/1');
    await page.getByRole('button', { name: '복제' }).click();

    const req = await cloneCapture.waitForRequest();
    expect(req.payload).toMatchObject({ name: clonedName, enabled: false });
    expect(Array.from((req.payload as { name: string }).name)).toHaveLength(200);
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/98/);
  });

  test('작업 복제 — POST API 호출 및 새 작업으로 이동', async ({ authenticatedPage: page }) => {
    await setupJobDetailMocks(page, 1);

    // 복제는 useCloneProactiveJob → proactiveApi.createJob (POST /api/v1/proactive/jobs) 호출
    // 복제된 새 작업(id=99) 반환을 모킹한다
    const cloneCapture = await mockApi(
      page,
      'POST',
      '/api/v1/proactive/jobs',
      createJob({ id: 99, name: '매일 현황 리포트 (복사본)', enabled: false }),
      { capture: true },
    );
    // 복제 후 새 작업 상세 페이지로 이동하므로 해당 API도 모킹
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/99', createJob({ id: 99, name: '매일 현황 리포트 (복사본)', enabled: false }));
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/99/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/99/anomaly-events', []);

    await page.goto('/ai-insights/jobs/1');

    // 복제 버튼 클릭
    await page.getByRole('button', { name: '복제' }).click();

    // POST /api/v1/proactive/jobs API가 호출되었는지 확인 (복사본 이름 포함)
    const req = await cloneCapture.waitForRequest();
    // 복사본은 늘 비활성으로 만든다(WD-300) — 기본값(활성)이면 원본과 같은 수신자에게 두 번 예약된다
    expect(req.payload).toMatchObject({ name: '매일 현황 리포트 (복사본)', enabled: false });
    await expect(page.getByText('"매일 현황 리포트 (복사본)" 작업을 꺼진 상태로 복제했습니다. 확인한 뒤 작업 목록에서 켜세요.')).toBeVisible();

    // 새 작업 상세 페이지(id=99)로 이동 확인
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/99/);
    // 서버가 돌려준 꺼진 복사본이 상세 머리에 「비활성」으로 보인다
    await expect(page.getByText('비활성', { exact: true }).first()).toBeVisible();
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

  test('개요 탭 — 의도된 건너뜀은 빨간 「실패」가 아닌 회색 「건너뜀」으로 보인다 (WD-191)', async ({ authenticatedPage: page }) => {
    // 서버는 작업 주인 권한이 없어 실행하지 않은 건너뜀도 status=FAILED 로 남긴다 — 실행 상세와 같은 판정으로 건너뜀을 보여야 한다
    const reason = '작업 주인에게 스마트 작업 권한이 없어 실행하지 않았습니다';
    await mockApi(
      page,
      'GET',
      '/api/v1/proactive/jobs/1',
      createJob({ id: 1, lastExecution: createJobExecution({ id: 10, jobId: 1, status: 'FAILED', errorMessage: reason }) }),
    );
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/anomaly-events', []);

    await page.goto('/ai-insights/jobs/1');

    const status = page.getByText('마지막 실행 상태').locator('..');
    await expect(status.locator('[data-slot="badge"]')).toHaveText('건너뜀');
    // 사유 원문은 그대로 보이되 오류 색(빨강)이 아닌 중립 톤이다
    const reasonText = status.getByTestId('last-execution-reason');
    await expect(reasonText).toHaveText(reason);
    await expect(reasonText).not.toHaveClass(/text-destructive/);
  });

  test('실행 이력 탭 — 의도된 건너뜀 행은 「실패」가 아닌 「건너뜀」 뱃지로 보인다 (WD-191)', async ({ authenticatedPage: page }) => {
    // 같은 실행이 상세에서는 건너뜀, 이력 목록에서는 실패로 보이면 헷갈린다 — 목록 행도 같은 판정을 쓴다
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', createJob({ id: 1 }));
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', [
      createJobExecution({ id: 21, jobId: 1, status: 'FAILED', errorMessage: '워크스페이스가 정지되어 실행하지 않았습니다' }),
      createJobExecution({
        id: 22,
        jobId: 1,
        status: 'FAILED',
        errorMessage: 'AI API 키 또는 OAuth 토큰이 설정되지 않았습니다. 관리자 설정에서 등록하세요.',
        startedAt: '2024-01-01T08:00:00Z',
      }),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs/1?tab=executions');

    const rows = page.getByRole('row');
    // 헤더 다음 첫 행 = 건너뜀(회색), 둘째 행 = 진짜 실패(빨강) 그대로
    await expect(rows.nth(1)).toContainText('건너뜀');
    await expect(rows.nth(1)).not.toContainText('실패');
    await expect(rows.nth(2)).toContainText('실패');
  });

  test('개요 탭 — 실행 중이던 최근 실행이 끝나면 다시 읽어 실패로 바뀐다 (WD-134)', async ({ authenticatedPage: page }) => {
    // 「지금 실행」 직후에는 실행 중 상태가 실리므로, 끝날 때까지 다시 읽어야 결과가 화면에 반영된다
    const running = createJob({
      id: 1,
      lastExecution: createJobExecution({ id: 10, jobId: 1, status: 'RUNNING', completedAt: null }),
    });
    const failed = createJob({
      id: 1,
      lastExecution: createJobExecution({ id: 10, jobId: 1, status: 'FAILED', errorMessage: 'AI 설정 없음' }),
    });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/anomaly-events', []);
    let jobCalls = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/jobs/1',
      (route) => {
        jobCalls += 1;
        return route.fulfill({ status: 200, json: jobCalls === 1 ? running : failed });
      },
    );

    await page.goto('/ai-insights/jobs/1');
    const status = page.getByText('마지막 실행 상태').locator('..');
    await expect(status.locator('[data-slot="badge"]')).toHaveText('실행중');

    // 실행 중이면 5초 간격으로 다시 읽는다 — 끝난 결과(실패와 오류 문구)로 바뀐다
    await expect(status.locator('[data-slot="badge"]')).toHaveText('실패', { timeout: 10000 });
    await expect(status.getByText('AI 설정 없음')).toBeVisible();
    expect(jobCalls).toBeGreaterThanOrEqual(2);
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

  test('새 작업 생성 — 트리거 유형을 "이상 탐지"로 선택하면 config.anomaly.enabled=true로 저장된다 (#655)', async ({ authenticatedPage: page }) => {
    // 새 작업 페이지 API 모킹
    await setupNewJobMocks(page);

    // 생성 API 요청 페이로드를 캡처해 실제 제출값을 검증한다
    const createCapture = await mockApi(
      page,
      'POST',
      '/api/v1/proactive/jobs',
      createJob({ id: 99, triggerType: 'ANOMALY', name: '이상 탐지 신규 작업' }),
      { capture: true },
    );

    await page.goto('/ai-insights/jobs/new');

    await page.locator('#job-name').fill('이상 탐지 신규 작업');
    await page.locator('#job-prompt').fill('이상 탐지 시 분석할 프롬프트');

    // 트리거 유형을 "이상 탐지"로 변경 — 모니터링 탭은 방문하지 않는다(이슈 재현 조건)
    await page.locator('#job-trigger-type').click();
    await page.getByRole('option', { name: '이상 탐지 (이벤트 기반)' }).click();

    const createBtn = page.getByRole('button', { name: '생성', exact: true });
    await expect(createBtn).toBeEnabled();
    await createBtn.click();

    // 실제 제출된 payload에 config.anomaly.enabled=true가 포함돼야 한다.
    // 수정 전에는 트리거 유형만 바뀌고 config.anomaly가 undefined로 남아
    // 상단 배지는 "활성"이지만 폴러 대상에서 제외되는 모순 상태가 됐다.
    const req = await createCapture.waitForRequest();
    const payload = req.payload as {
      triggerType: string;
      config: { anomaly?: { enabled: boolean } };
    };
    expect(payload.triggerType).toBe('ANOMALY');
    expect(payload.config.anomaly).toBeDefined();
    expect(payload.config.anomaly?.enabled).toBe(true);
  });

  test('새 작업 생성 — 트리거 유형을 "스케줄"로 유지하면 config.anomaly 없이 cronExpression이 정상 제출된다 (#655 회귀 방지)', async ({ authenticatedPage: page }) => {
    // 새 작업 페이지 API 모킹
    await setupNewJobMocks(page);

    const createCapture = await mockApi(
      page,
      'POST',
      '/api/v1/proactive/jobs',
      createJob({ id: 100, name: '스케줄 신규 작업' }),
      { capture: true },
    );

    await page.goto('/ai-insights/jobs/new');

    await page.locator('#job-name').fill('스케줄 신규 작업');
    await page.locator('#job-prompt').fill('정기 실행 분석 프롬프트');

    // 트리거 유형은 기본값(SCHEDULE)에서 건드리지 않는다 — 회귀 없는지 확인

    const createBtn = page.getByRole('button', { name: '생성', exact: true });
    await expect(createBtn).toBeEnabled();
    await createBtn.click();

    const req = await createCapture.waitForRequest();
    const payload = req.payload as {
      triggerType: string;
      cronExpression: string;
      config: { anomaly?: { enabled: boolean } };
    };
    expect(payload.triggerType).toBe('SCHEDULE');
    expect(payload.cronExpression).toBeTruthy();
    // SCHEDULE 트리거는 anomaly.enabled를 강제로 켜지 않아야 한다 (회귀 방지)
    expect(payload.config.anomaly?.enabled).not.toBe(true);
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

  /**
   * 회귀 테스트 — 이슈 #59
   * 스마트 작업 편집 중 뒤로가기/취소 클릭 시 저장 확인 다이얼로그 없이 변경사항이 소실되는 문제.
   * isDirty 상태 + 뒤로가기/취소 버튼 onClick 인터셉트 + AlertDialog로 이탈 전 사용자 확인.
   */
  test.describe('이슈 #59 — 미저장 변경 이탈 가드', () => {
    test('편집 모드가 아니면 뒤로가기 클릭 시 즉시 목록으로 이동한다', async ({
      authenticatedPage: page,
    }) => {
      await setupJobDetailMocks(page, 1);
      await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: 1 })]);

      await page.goto('/ai-insights/jobs/1');
      await expect(page.getByRole('heading', { name: '매일 현황 리포트' })).toBeVisible();

      // 편집 모드 진입 안 함 → 미저장 표시 없음
      await expect(page.getByText('미저장 변경사항')).not.toBeVisible();

      // 뒤로가기 클릭 → 다이얼로그 없이 즉시 이동
      await page.getByRole('button', { name: '목록으로' }).click();
      await expect(page).toHaveURL(/\/ai-insights\/jobs$/);
    });

    test('편집 모드에서 작업명 변경 후 뒤로가기 클릭 시 이탈 확인 다이얼로그가 표시된다', async ({
      authenticatedPage: page,
    }) => {
      await setupJobDetailMocks(page, 1);

      await page.goto('/ai-insights/jobs/1');
      await expect(page.getByRole('heading', { name: '매일 현황 리포트' })).toBeVisible();

      // 편집 모드 진입
      await page.getByRole('button', { name: '편집' }).click();

      // 작업명 변경 → isDirty=true 트리거
      await page.locator('#job-name').fill('수정된 작업명');

      // 미저장 변경사항 인디케이터 표시
      await expect(page.getByText('미저장 변경사항')).toBeVisible();

      // 뒤로가기 클릭 → AlertDialog 표시
      await page.getByRole('button', { name: '목록으로' }).click();

      await expect(page.getByRole('alertdialog')).toBeVisible();
      await expect(
        page.getByRole('heading', { name: '저장하지 않은 변경사항' }),
      ).toBeVisible();
      await expect(
        page.getByText('저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?'),
      ).toBeVisible();

      // URL은 아직 변경되지 않아야 함
      await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
    });

    test('이탈 확인 다이얼로그에서 취소를 누르면 페이지에 머무르고 입력값이 보존된다', async ({
      authenticatedPage: page,
    }) => {
      await setupJobDetailMocks(page, 1);

      await page.goto('/ai-insights/jobs/1');
      await page.getByRole('button', { name: '편집' }).click();
      await page.locator('#job-name').fill('수정된 작업명');
      await page.getByRole('button', { name: '목록으로' }).click();

      // AlertDialog 내 '취소' 버튼 클릭
      await page.getByRole('alertdialog').getByRole('button', { name: '취소' }).click();

      // 다이얼로그 닫힘 + 페이지 유지 + 입력값 보존 + dirty 표시 유지
      await expect(page.getByRole('alertdialog')).not.toBeVisible();
      await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
      await expect(page.locator('#job-name')).toHaveValue('수정된 작업명');
      await expect(page.getByText('미저장 변경사항')).toBeVisible();
    });

    test('이탈 확인 다이얼로그에서 이탈을 누르면 목록으로 이동한다', async ({
      authenticatedPage: page,
    }) => {
      await setupJobDetailMocks(page, 1);
      await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: 1 })]);

      await page.goto('/ai-insights/jobs/1');
      await page.getByRole('button', { name: '편집' }).click();
      await page.locator('#job-name').fill('수정된 작업명');
      await page.getByRole('button', { name: '목록으로' }).click();

      // '이탈' 버튼 클릭 → 변경사항 버리고 목록으로 이동
      await page.getByRole('alertdialog').getByRole('button', { name: '이탈' }).click();

      await expect(page).toHaveURL(/\/ai-insights\/jobs$/);
    });

    test('편집 모드에서 변경 후 취소 버튼 클릭 시 이탈 확인 다이얼로그가 표시된다', async ({
      authenticatedPage: page,
    }) => {
      await setupJobDetailMocks(page, 1);

      await page.goto('/ai-insights/jobs/1');
      await page.getByRole('button', { name: '편집' }).click();

      // 작업명 변경 → dirty 트리거
      await page.locator('#job-name').fill('수정된 작업명');
      await expect(page.getByText('미저장 변경사항')).toBeVisible();

      // 취소 버튼 클릭 → AlertDialog 표시 (페이지 이탈이 아닌 편집 취소도 가드)
      await page.getByRole('button', { name: '취소' }).click();

      await expect(page.getByRole('alertdialog')).toBeVisible();
      await expect(
        page.getByRole('heading', { name: '저장하지 않은 변경사항' }),
      ).toBeVisible();

      // URL은 그대로 유지 (페이지 이탈 아님)
      await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
    });

    test('작업 저장 성공 후 미저장 표시가 사라진다', async ({ authenticatedPage: page }) => {
      // 작업 수정 모킹 — PUT 응답을 그대로 내려준다
      const updatedJob = createJob({ id: 1, name: '수정된 매일 현황 리포트' });
      await setupJobDetailMocks(page, 1);
      await mockApi(page, 'PUT', '/api/v1/proactive/jobs/1', updatedJob);

      await page.goto('/ai-insights/jobs/1');
      await expect(page.getByRole('heading', { name: '매일 현황 리포트' })).toBeVisible();

      // 편집 모드 진입 + 작업명 변경
      await page.getByRole('button', { name: '편집' }).click();
      await page.locator('#job-name').fill('수정된 매일 현황 리포트');

      // 저장 전 dirty 표시 확인
      await expect(page.getByText('미저장 변경사항')).toBeVisible();

      // 저장 버튼 클릭
      await page.getByRole('button', { name: '저장' }).click();

      // 저장 성공 → dirty 해제 → 미저장 표시 사라짐
      await expect(page.getByText('미저장 변경사항')).not.toBeVisible({ timeout: 5000 });

      // 뒤로가기 클릭 시 다이얼로그 없이 즉시 이동 가능 (저장 후이므로)
      await mockApi(page, 'GET', '/api/v1/proactive/jobs', [updatedJob]);
      await page.getByRole('button', { name: '목록으로' }).click();
      await expect(page).toHaveURL(/\/ai-insights\/jobs$/);
    });
  });

  /**
   * 회귀 테스트 — 이슈 #640
   * 이슈 #59의 가드는 beforeunload(탭 닫기)와 페이지 내 "목록으로"/"취소" 버튼 onClick만 커버해,
   * 사이드바 메뉴 클릭 같은 React Router SPA 내부 네비게이션은 무경고로 통과시켰다.
   * 공용 useUnsavedChangesGuard(document click capture)를 배선해 사이드바 링크 클릭도 가로채는지 검증한다.
   */
  test.describe('이슈 #640 — 사이드바 SPA 네비게이션 이탈 가드', () => {
    test('작업명 입력 후 사이드바 "쿼리" 링크 클릭 시 이탈 확인 다이얼로그가 표시되고 URL이 유지된다', async ({
      authenticatedPage: page,
    }) => {
      await setupNewJobMocks(page);

      await page.goto('/ai-insights/jobs/new');
      await expect(page.getByRole('heading', { name: '새 스마트 작업' })).toBeVisible();

      // 작업명 입력 → isDirty=true 트리거 (목록으로 버튼은 건드리지 않는다 — SPA 링크 클릭만으로 재현)
      await page.locator('#job-name').fill('exp-job-43-unsaved-test');
      await expect(page.getByText('미저장 변경사항')).toBeVisible();

      // 사이드바 "쿼리" 링크 클릭 — useUnsavedChangesGuard의 document click capture가 가로채야 한다
      await page.getByRole('navigation').getByRole('link', { name: '쿼리' }).click();

      await expect(page.getByRole('alertdialog')).toBeVisible();
      await expect(
        page.getByText('저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?'),
      ).toBeVisible();
      // 가로챘으므로 URL은 그대로 작업 작성 화면에 머문다.
      expect(new URL(page.url()).pathname).toBe('/ai-insights/jobs/new');
    });

    test('이탈 다이얼로그에서 취소하면 사이드바 이동이 취소되고 입력값이 보존된다', async ({
      authenticatedPage: page,
    }) => {
      await setupNewJobMocks(page);

      await page.goto('/ai-insights/jobs/new');
      await page.locator('#job-name').fill('exp-job-43-unsaved-test');

      await page.getByRole('navigation').getByRole('link', { name: '쿼리' }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();

      await page.getByRole('button', { name: '취소' }).click();
      await expect(page.getByRole('alertdialog')).not.toBeVisible();

      expect(new URL(page.url()).pathname).toBe('/ai-insights/jobs/new');
      await expect(page.locator('#job-name')).toHaveValue('exp-job-43-unsaved-test');
    });

    test('이탈 다이얼로그에서 이탈을 확정하면 사이드바가 가리키는 페이지로 이동한다', async ({
      authenticatedPage: page,
    }) => {
      await setupNewJobMocks(page);

      await page.goto('/ai-insights/jobs/new');
      await page.locator('#job-name').fill('exp-job-43-unsaved-test');

      await page.getByRole('navigation').getByRole('link', { name: '쿼리' }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();

      await page.getByRole('button', { name: '이탈' }).click();

      await expect(page).toHaveURL(/\/analytics\/queries$/);
    });

    test('변경 사항이 없으면 사이드바 링크 클릭 시 다이얼로그 없이 즉시 이동한다', async ({
      authenticatedPage: page,
    }) => {
      // dirty가 아닐 때는 가드가 개입하지 않아야 한다는 대비군(contrast) 테스트.
      await setupNewJobMocks(page);

      await page.goto('/ai-insights/jobs/new');
      await expect(page.getByText('미저장 변경사항')).not.toBeVisible();

      await page.getByRole('navigation').getByRole('link', { name: '쿼리' }).click();

      await expect(page.getByRole('alertdialog')).not.toBeVisible();
      await expect(page).toHaveURL(/\/analytics\/queries$/);
    });
  });

  test('새 작업 폼 — 분석 프롬프트·비즈니스 질문 입력란은 서버 상한(10,000자)까지만 입력받는다 (WD-425)', async ({ authenticatedPage: page }) => {
    // 서버가 10,000자를 넘는 프롬프트를 400 으로 거부하므로 입력란에서 같은 길이로 막는다.
    await setupNewJobMocks(page);
    await page.goto('/ai-insights/jobs/new');

    await expect(page.locator('#job-prompt')).toHaveAttribute('maxlength', '10000');

    // 「목표 기반」의 비즈니스 질문도 그대로 프롬프트가 되므로 같은 상한이다
    await page.locator('#mode-goal').click();
    await expect(page.locator('#business-question')).toHaveAttribute('maxlength', '10000');
  });
});

// WD-295: 좁은 화면(390px)에서 머리글 버튼 줄이 화면 밖으로 넘쳐 「삭제」가 잘리고 본문이 가로로 밀리던 회귀를 막는다.
test.describe('스마트 작업 상세 — 모바일 머리글', () => {
  test('320px 폭(재배치 기준, #345)에서 복제·지금 실행·편집·삭제가 모두 화면 안에 있고 본문이 가로로 넘치지 않는다', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 320, height: 800 });
    await setupJobDetailMocks(page, 1);

    await page.goto('/ai-insights/jobs/1');
    await expect(page.getByRole('heading', { name: '매일 현황 리포트' })).toBeVisible();

    // 네 버튼의 오른쪽 끝이 모두 화면 폭(320) 안에 있어야 한다 — 이전에는 「삭제」가 382~430 에 걸쳤다.
    const header = page.locator('header').filter({ has: page.getByRole('heading', { name: '매일 현황 리포트' }) });
    for (const name of ['복제', '지금 실행', '편집', '삭제']) {
      const box = await header.getByRole('button', { name, exact: true }).boundingBox();
      expect(box, `${name} 버튼 위치`).not.toBeNull();
      expect(box!.x + box!.width, `${name} 버튼 오른쪽 끝`).toBeLessThanOrEqual(320);
    }

    // 본문(main)이 가로로 스크롤되지 않아야 한다.
    const overflow = await page.locator('main').evaluate((el) => el.scrollWidth - el.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });
});
