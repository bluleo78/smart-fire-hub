/**
 * 파이프라인 트리거 탭 E2E 테스트
 *
 * AddTriggerDialog / EditTriggerDialog / TriggerTab / 각 Form(Schedule/Webhook)
 * 라인 커버리지를 끌어올리기 위한 상호작용 시나리오.
 * - API 모킹 기반으로 POST/PUT/DELETE/PATCH payload 를 검증한다.
 */

import type { Page } from '@playwright/test';

import { createPipelineDetail, createTrigger } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** 파이프라인 상세 + 트리거 리스트 공통 모킹 */
async function setupTriggerTabMocks(
  page: Page,
  options?: { triggers?: ReturnType<typeof createTrigger>[] },
) {
  const pipelineId = 1;
  const triggers = options?.triggers ?? [];

  await mockApi(page, 'GET', `/api/v1/pipelines/${pipelineId}`, createPipelineDetail({ id: pipelineId }));
  await mockApi(page, 'GET', `/api/v1/pipelines/${pipelineId}/executions`, []);
  await mockApi(page, 'GET', `/api/v1/pipelines/${pipelineId}/triggers`, triggers);
  await mockApi(page, 'GET', `/api/v1/pipelines/${pipelineId}/trigger-events`, []);
  await mockApi(page, 'GET', '/api/v1/datasets', {
    content: [],
    page: 0,
    size: 1000,
    totalElements: 0,
    totalPages: 0,
  });
}

/** 트리거 탭으로 이동하는 공통 헬퍼 */
async function gotoTriggerTab(page: Page) {
  await page.goto('/pipelines/1');
  await page.getByRole('tab', { name: '트리거' }).click();
  await expect(page.getByRole('button', { name: /트리거 추가/ })).toBeVisible();
}

test.describe('파이프라인 트리거 탭', () => {
  test('스케줄 트리거 추가 — cron + concurrencyPolicy 가 POST payload 로 전송된다', async ({
    authenticatedPage: page,
  }) => {
    await setupTriggerTabMocks(page);

    // 트리거 생성 API — payload 캡처
    const created = createTrigger({
      id: 10,
      name: '매일 아침 9시',
      triggerType: 'SCHEDULE',
      config: { cron: '0 9 * * *', timezone: 'Asia/Seoul', concurrencyPolicy: 'SKIP' },
    });
    const createCapture = await mockApi(
      page,
      'POST',
      '/api/v1/pipelines/1/triggers',
      created,
      { capture: true },
    );

    await gotoTriggerTab(page);

    // 추가 버튼 클릭 → 타입 선택 화면
    await page.getByRole('button', { name: /트리거 추가/ }).click();
    await expect(page.getByRole('dialog').getByText('트리거 추가')).toBeVisible();

    // 스케줄 선택
    await page.getByRole('button', { name: /스케줄.*Cron/ }).click();

    // 이름 입력
    await page.getByLabel(/^이름/).fill('매일 아침 9시');
    await page.getByLabel('설명').fill('오전 배치 실행');

    // 생성
    await page.getByRole('button', { name: '트리거 생성' }).click();

    // POST payload 검증
    const req = await createCapture.waitForRequest();
    expect(req.payload).toMatchObject({
      name: '매일 아침 9시',
      triggerType: 'SCHEDULE',
      description: '오전 배치 실행',
      config: {
        cron: '0 9 * * *',
        timezone: 'Asia/Seoul',
        concurrencyPolicy: 'SKIP',
      },
    });
  });

  test('웹훅 트리거 추가 — secret 을 POST payload 로 전송한다', async ({
    authenticatedPage: page,
  }) => {
    await setupTriggerTabMocks(page);

    const created = createTrigger({
      id: 11,
      name: 'GitHub webhook',
      triggerType: 'WEBHOOK',
      config: { webhookId: 'abc-123', secret: 'my-secret-key' },
    });
    const createCapture = await mockApi(
      page,
      'POST',
      '/api/v1/pipelines/1/triggers',
      created,
      { capture: true },
    );

    await gotoTriggerTab(page);

    await page.getByRole('button', { name: /트리거 추가/ }).click();
    await page.getByRole('button', { name: /웹훅.*HTTP POST/ }).click();

    await page.getByLabel(/^이름/).fill('GitHub webhook');

    // 시크릿 입력 (password 타입 input)
    await page
      .getByPlaceholder('HMAC-SHA256 서명 검증에 사용할 시크릿 키')
      .fill('my-secret-key');

    await page.getByRole('button', { name: '트리거 생성' }).click();

    const req = await createCapture.waitForRequest();
    expect(req.payload).toMatchObject({
      name: 'GitHub webhook',
      triggerType: 'WEBHOOK',
      config: { secret: 'my-secret-key' },
    });
  });

  test('웹훅 트리거 추가 — 생성 후 다이얼로그가 유지되고 웹훅 URL이 표시된다 (refs #28)', async ({
    authenticatedPage: page,
  }) => {
    await setupTriggerTabMocks(page);

    // 생성 응답에 webhookId 포함 — WebhookTriggerForm의 URL 표시 UI 활성화 조건
    const created = createTrigger({
      id: 13,
      name: '슬랙 알림 웹훅',
      triggerType: 'WEBHOOK',
      config: { webhookId: 'wh-test-uuid-1234', secret: '' },
    });
    await mockApi(page, 'POST', '/api/v1/pipelines/1/triggers', created);

    await gotoTriggerTab(page);

    await page.getByRole('button', { name: /트리거 추가/ }).click();
    await page.getByRole('button', { name: /웹훅.*HTTP POST/ }).click();

    await page.getByLabel(/^이름/).fill('슬랙 알림 웹훅');
    await page.getByRole('button', { name: '트리거 생성' }).click();

    // 생성 후 다이얼로그가 닫히지 않고 유지되어야 함 (버그 #28 회귀 방지)
    await expect(page.getByRole('dialog')).toBeVisible();

    // 웹훅 URL이 다이얼로그 내에 표시되어야 함
    await expect(page.getByText('웹훅 URL')).toBeVisible();
    await expect(page.getByText(/wh-test-uuid-1234/)).toBeVisible();

    // "닫기" 버튼이 표시되어 수동으로 닫을 수 있어야 함
    await expect(page.getByRole('button', { name: '닫기' })).toBeVisible();

    // 닫기 후 다이얼로그가 사라져야 함
    await page.getByRole('button', { name: '닫기' }).click();
    await expect(page.getByRole('dialog')).not.toBeVisible();
  });

  test('트리거 편집 — 이름 변경 후 PUT payload 검증', async ({ authenticatedPage: page }) => {
    const existing = createTrigger({
      id: 5,
      name: '기존 스케줄',
      triggerType: 'SCHEDULE',
      config: { cron: '0 9 * * *', timezone: 'Asia/Seoul', concurrencyPolicy: 'SKIP' },
    });
    await setupTriggerTabMocks(page, { triggers: [existing] });

    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/pipelines/1/triggers/5',
      { ...existing, name: '수정된 스케줄' },
      { capture: true },
    );

    await gotoTriggerTab(page);

    // 드롭다운 → 편집
    await expect(page.getByText('기존 스케줄')).toBeVisible();
    await page.getByRole('button').filter({ has: page.locator('.lucide-ellipsis') }).first().click();
    await page.getByRole('menuitem', { name: '편집' }).click();

    await expect(page.getByRole('dialog').getByText('트리거 편집')).toBeVisible();

    // 이름 변경
    const nameInput = page.getByLabel(/^이름/);
    await nameInput.fill('수정된 스케줄');

    await page.getByRole('button', { name: '저장' }).click();

    const req = await updateCapture.waitForRequest();
    expect(req.payload).toMatchObject({ name: '수정된 스케줄' });
  });

  test('트리거 삭제 — 확인 다이얼로그 후 DELETE 호출', async ({ authenticatedPage: page }) => {
    const existing = createTrigger({ id: 7, name: '삭제 대상 트리거' });
    await setupTriggerTabMocks(page, { triggers: [existing] });

    let deleteCalled = false;
    await page.route(
      (url) => url.pathname === '/api/v1/pipelines/1/triggers/7',
      (route) => {
        if (route.request().method() === 'DELETE') {
          deleteCalled = true;
          return route.fulfill({ status: 204, body: '' });
        }
        return route.fallback();
      },
    );

    await gotoTriggerTab(page);

    await expect(page.getByText('삭제 대상 트리거')).toBeVisible();
    await page.getByRole('button').filter({ has: page.locator('.lucide-ellipsis') }).first().click();
    await page.getByRole('menuitem', { name: '삭제' }).click();

    // AlertDialog 확인
    await expect(page.getByRole('alertdialog').getByText(/트리거 삭제/)).toBeVisible();
    await page.getByRole('button', { name: '삭제', exact: true }).click();

    // 짧은 대기 후 호출 여부 확인
    await expect.poll(() => deleteCalled).toBe(true);
  });

  test('트리거 활성/비활성 토글 — PATCH 호출 검증', async ({ authenticatedPage: page }) => {
    const existing = createTrigger({ id: 8, name: '토글 대상', isEnabled: true });
    await setupTriggerTabMocks(page, { triggers: [existing] });

    let toggleCalled = false;
    await page.route(
      (url) => url.pathname === '/api/v1/pipelines/1/triggers/8/toggle',
      (route) => {
        if (route.request().method() === 'PATCH') {
          toggleCalled = true;
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ ...existing, isEnabled: false }),
          });
        }
        return route.fallback();
      },
    );

    await gotoTriggerTab(page);

    await expect(page.getByText('토글 대상')).toBeVisible();
    await page.getByRole('button').filter({ has: page.locator('.lucide-ellipsis') }).first().click();
    await page.getByRole('menuitem', { name: /비활성화|활성화/ }).click();

    await expect.poll(() => toggleCalled).toBe(true);
  });

  test('트리거 이름 미입력 시 유효성 에러가 표시된다', async ({ authenticatedPage: page }) => {
    await setupTriggerTabMocks(page);

    await gotoTriggerTab(page);

    await page.getByRole('button', { name: /트리거 추가/ }).click();
    await page.getByRole('button', { name: /스케줄/ }).click();

    // 이름 비운 채로 생성 시도
    await page.getByRole('button', { name: '트리거 생성' }).click();

    await expect(page.getByText('트리거 이름을 입력하세요')).toBeVisible();
  });

  test('API 트리거 추가 — 생성 후 one-time 토큰이 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupTriggerTabMocks(page);

    // API 트리거 생성 응답 — config.rawToken에 one-time 토큰 포함
    const created = createTrigger({
      id: 12,
      name: '외부 시스템 API 트리거',
      triggerType: 'API',
      config: { allowedIps: ['10.0.0.1/24'], rawToken: 'api-token-abc123' },
    });
    const createCapture = await mockApi(
      page,
      'POST',
      '/api/v1/pipelines/1/triggers',
      created,
      { capture: true },
    );

    await gotoTriggerTab(page);

    // 트리거 추가 → API 유형 선택
    await page.getByRole('button', { name: /트리거 추가/ }).click();
    await page.getByRole('button', { name: /API.*외부 API/ }).click();

    // 이름 입력
    await page.getByLabel(/^이름/).fill('외부 시스템 API 트리거');

    // IP 제한 추가
    await page.getByPlaceholder('192.168.1.0/24').fill('10.0.0.1/24');
    await page.getByRole('button', { name: '추가' }).click();

    // IP 배지가 표시되어야 함
    await expect(page.getByText('10.0.0.1/24')).toBeVisible();

    // 트리거 생성
    await page.getByRole('button', { name: '트리거 생성' }).click();

    // POST payload 검증
    const req = await createCapture.waitForRequest();
    expect(req.payload).toMatchObject({
      name: '외부 시스템 API 트리거',
      triggerType: 'API',
      config: { allowedIps: ['10.0.0.1/24'] },
    });

    // API 타입은 창을 닫지 않고 one-time 토큰을 표시해야 함
    await expect(page.getByText('이 토큰은 다시 볼 수 없습니다')).toBeVisible();
    await expect(page.getByText('api-token-abc123', { exact: true })).toBeVisible();
  });

  test('DATASET_CHANGE 트리거 추가 — 데이터셋 선택 없이 생성 시 에러가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupTriggerTabMocks(page);

    await gotoTriggerTab(page);

    await page.getByRole('button', { name: /트리거 추가/ }).click();
    await page.getByRole('button', { name: /데이터셋 변경/ }).click();

    await page.getByLabel(/^이름/).fill('데이터셋 변경 트리거');

    // 데이터셋 선택 없이 생성 시도
    await page.getByRole('button', { name: '트리거 생성' }).click();

    await expect(page.getByText('감시 대상 데이터셋을 선택하세요')).toBeVisible();
  });

  test('스케줄 트리거 — 유효하지 않은 cron 표현식 입력 시 에러가 표시되고 생성이 차단된다', async ({
    authenticatedPage: page,
  }) => {
    await setupTriggerTabMocks(page);

    let apiCalled = false;
    await page.route('/api/v1/pipelines/1/triggers', (route) => {
      if (route.request().method() === 'POST') {
        apiCalled = true;
        return route.fulfill({ status: 201, body: JSON.stringify(createTrigger({ id: 99 })) });
      }
      return route.continue();
    });

    await gotoTriggerTab(page);

    await page.getByRole('button', { name: /트리거 추가/ }).click();
    await page.getByRole('button', { name: /스케줄.*Cron/ }).click();

    await page.getByLabel(/^이름/).fill('잘못된 크론 테스트');

    // 유효하지 않은 cron 직접 입력
    const cronInput = page.locator('input[placeholder*="* * *"]').first();
    await cronInput.fill('invalid-cron');

    await page.getByRole('button', { name: '트리거 생성' }).click();

    // 에러 메시지 표시, API 미호출
    await expect(page.getByText('유효하지 않은 Cron 표현식입니다')).toBeVisible();
    expect(apiCalled).toBe(false);
  });
});
