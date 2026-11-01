/**
 * 파이프라인 트리거 탭 E2E 테스트
 *
 * AddTriggerDialog / EditTriggerDialog / TriggerTab / 각 Form(Schedule/Webhook)
 * 라인 커버리지를 끌어올리기 위한 상호작용 시나리오.
 * - API 모킹 기반으로 POST/PUT/DELETE/PATCH payload 를 검증한다.
 */

import type { Page } from '@playwright/test';

import type { TriggerEventResponse } from '@/types/pipeline';

import { createPipelineDetail, createTrigger } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { mockCronPreview, TRIGGER_CRON_PREVIEW_PATH } from '../../fixtures/cron-preview.fixture';
import { grantPipelinePermissions } from '../../fixtures/pipeline.fixture';

/** 파이프라인 상세 + 트리거 리스트 공통 모킹 */
async function setupTriggerTabMocks(
  page: Page,
  options?: { triggers?: ReturnType<typeof createTrigger>[]; events?: TriggerEventResponse[] },
) {
  const pipelineId = 1;
  const triggers = options?.triggers ?? [];
  const events = options?.events ?? [];

  await mockApi(page, 'GET', `/api/v1/pipelines/${pipelineId}`, createPipelineDetail({ id: pipelineId }));
  await mockApi(page, 'GET', `/api/v1/pipelines/${pipelineId}/executions`, []);
  await mockApi(page, 'GET', `/api/v1/pipelines/${pipelineId}/triggers`, triggers);
  await mockApi(page, 'GET', `/api/v1/pipelines/${pipelineId}/trigger-events`, events);
  // 「다음 5회」 미리보기 기본 응답(WD-391) — 테스트가 다시 불러 덮어쓸 수 있다
  await mockCronPreview(page, TRIGGER_CRON_PREVIEW_PATH);
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
  // 실행·수정·트리거 편집 버튼은 권한이 있을 때만 보인다(WD-219) — 기본 인증 fixture 는 권한이 비어 있어 모든 쓰기 권한을 준다.
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  test('스케줄 트리거 추가 — cron + concurrencyPolicy 가 POST payload 로 전송된다', { tag: '@smoke' }, async ({
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

    // 생성 완료 후에는 이름/설명뿐 아니라 시크릿 키 입력란도 비활성화되어야 함 (#656 회귀 방지)
    // — isEditMode 미전달로 시크릿 필드만 활성 상태로 남아 편집 가능한 것처럼 보이던 버그
    await expect(page.getByLabel(/^이름/)).toBeDisabled();
    await expect(page.getByPlaceholder('HMAC-SHA256 서명 검증에 사용할 시크릿 키')).toBeDisabled();
    await expect(page.getByText('시크릿 키는 수정할 수 없습니다')).toBeVisible();

    // "닫기" 버튼이 표시되어 수동으로 닫을 수 있어야 함
    // 푸터의 텍스트 닫기 버튼 (X 아이콘 sr-only 버튼과 구분하기 위해 data-slot="button" 사용)
    const closeBtn = page.locator('[data-slot="button"]:has-text("닫기")');
    await expect(closeBtn).toBeVisible();

    // 닫기 후 다이얼로그가 사라져야 함
    await closeBtn.click();
    await expect(page.getByRole('dialog')).not.toBeVisible();
  });

  /**
   * 회귀 방지 테스트 (WD-113)
   * 설명을 모두 지우고 저장하면 PUT 에 빈 문자열 description 이 실려야 서버가 "지움"으로 처리한다.
   * 예전엔 `trim() || undefined` 로 키가 빠져 "바꾸지 않음"이 되고, 「수정되었습니다」 후에도 옛 설명이 남았다.
   */
  test('트리거 편집 — 설명을 지우고 저장하면 빈 description 이 PUT 에 실린다 (WD-113)', async ({
    authenticatedPage: page,
  }) => {
    const existing = createTrigger({
      id: 6,
      name: '설명 있는 스케줄',
      description: '지울 설명',
      triggerType: 'SCHEDULE',
      config: { cron: '0 0 9 * * *', timezone: 'Asia/Seoul', concurrencyPolicy: 'SKIP' },
    });
    await setupTriggerTabMocks(page, { triggers: [existing] });
    const updateCapture = await mockApi(page, 'PUT', '/api/v1/pipelines/1/triggers/6', '', { capture: true });

    await gotoTriggerTab(page);
    await page.getByRole('button').filter({ has: page.locator('.lucide-ellipsis') }).first().click();
    await page.getByRole('menuitem', { name: '편집' }).click();

    const dialog = page.getByRole('dialog');
    const desc = dialog.getByLabel('설명');
    await expect(desc).toHaveValue('지울 설명');
    await desc.fill('');
    await dialog.getByRole('button', { name: '저장' }).click();

    const req = await updateCapture.waitForRequest();
    expect(req.payload).toHaveProperty('description', '');
    expect(req.payload).toMatchObject({ name: '설명 있는 스케줄' });
  });

  /**
   * 회귀 방지 테스트 (WD-220)
   * 서버는 웹훅 시크릿 암호문 대신 hasSecret 만 내려준다. 편집 창은 그 값으로 「시크릿 설정됨」을 보이고,
   * 저장 PUT 에 암호문을 싣지 않아야 한다(서버가 DB 값을 유지한다).
   */
  test('트리거 편집 — 웹훅은 hasSecret 으로 시크릿 설정 상태를 보이고 PUT 에 암호문을 싣지 않는다 (WD-220)', async ({
    authenticatedPage: page,
  }) => {
    const existing = createTrigger({
      id: 7,
      name: '서명 웹훅',
      triggerType: 'WEBHOOK',
      config: { webhookId: 'wh-wd220', hasSecret: true },
    });
    await setupTriggerTabMocks(page, { triggers: [existing] });
    const updateCapture = await mockApi(page, 'PUT', '/api/v1/pipelines/1/triggers/7', '', { capture: true });

    await gotoTriggerTab(page);
    await page.getByRole('button').filter({ has: page.locator('.lucide-ellipsis') }).first().click();
    await page.getByRole('menuitem', { name: '편집' }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('시크릿 설정됨 — 수신 시 서명을 검증합니다.')).toBeVisible();
    await expect(dialog.getByPlaceholder('설정됨 (보안상 표시하지 않습니다)')).toBeDisabled();

    await dialog.getByLabel(/^이름/).fill('서명 웹훅 (수정)');
    await dialog.getByRole('button', { name: '저장' }).click();

    const req = await updateCapture.waitForRequest();
    expect(req.payload).toMatchObject({ name: '서명 웹훅 (수정)', config: { webhookId: 'wh-wd220' } });
    expect((req.payload as { config: Record<string, unknown> }).config).not.toHaveProperty('secretEncrypted');
  });

  test('트리거 편집 — 웹훅 시크릿이 없으면 「시크릿 없음」을 보인다 (WD-220)', async ({ authenticatedPage: page }) => {
    const existing = createTrigger({
      id: 8,
      name: '서명 없는 웹훅',
      triggerType: 'WEBHOOK',
      config: { webhookId: 'wh-wd220-none', hasSecret: false },
    });
    await setupTriggerTabMocks(page, { triggers: [existing] });

    await gotoTriggerTab(page);
    await page.getByRole('button').filter({ has: page.locator('.lucide-ellipsis') }).first().click();
    await page.getByRole('menuitem', { name: '편집' }).click();

    await expect(page.getByRole('dialog').getByText('시크릿 없음 — 서명 없이 수신합니다.')).toBeVisible();
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

  /**
   * 회귀 방지 테스트 (#641)
   * EditTriggerDialog의 validate()가 빈 값만 체크하고 cronstrue 형식 검증이 없어,
   * 문법적으로 잘못된 Cron이 오류 없이 그대로 PUT 저장되던 결함.
   */
  test('트리거 편집 — 유효하지 않은 cron 표현식 입력 시 에러가 표시되고 저장이 차단된다 (refs #641)', async ({
    authenticatedPage: page,
  }) => {
    const existing = createTrigger({
      id: 5,
      name: '기존 스케줄',
      triggerType: 'SCHEDULE',
      config: { cron: '0 9 * * *', timezone: 'Asia/Seoul', concurrencyPolicy: 'SKIP' },
    });
    await setupTriggerTabMocks(page, { triggers: [existing] });

    let apiCalled = false;
    await page.route('/api/v1/pipelines/1/triggers/5', (route) => {
      if (route.request().method() === 'PUT') {
        apiCalled = true;
        return route.fulfill({ status: 200, body: JSON.stringify(existing) });
      }
      return route.continue();
    });

    await gotoTriggerTab(page);

    await expect(page.getByText('기존 스케줄')).toBeVisible();
    await page.getByRole('button').filter({ has: page.locator('.lucide-ellipsis') }).first().click();
    await page.getByRole('menuitem', { name: '편집' }).click();
    await expect(page.getByRole('dialog').getByText('트리거 편집')).toBeVisible();

    // 유효하지 않은 cron 직접 입력
    const cronInput = page.locator('input[placeholder*="* * *"]').first();
    await cronInput.fill('invalid-cron');

    await page.getByRole('button', { name: '저장' }).click();

    // 에러 메시지 표시, API 미호출 — 잘못된 값이 저장되면 안 된다
    await expect(page.getByText('유효하지 않은 Cron 표현식입니다')).toBeVisible();
    expect(apiCalled).toBe(false);
  });

  /**
   * WD-366: 매초(`* * * * * *`)처럼 1분보다 잦은 cron 은 입력 중 안내되고 저장(PUT)이 막힌다.
   * 형식은 맞아 예전엔 「1초마다」로 풀어 보여 주고 그대로 저장돼 파이프라인이 끊임없이 재실행됐다. 매분으로 고치면 저장된다.
   */
  test('트리거 편집 — 1분보다 잦은 cron 은 안내되고 저장이 차단된다 (WD-366)', async ({
    authenticatedPage: page,
  }) => {
    const existing = createTrigger({
      id: 5,
      name: '기존 스케줄',
      triggerType: 'SCHEDULE',
      config: { cron: '0 9 * * *', timezone: 'Asia/Seoul', concurrencyPolicy: 'SKIP' },
    });
    await setupTriggerTabMocks(page, { triggers: [existing] });
    const updateCapture = await mockApi(page, 'PUT', '/api/v1/pipelines/1/triggers/5', existing, {
      capture: true,
    });

    await gotoTriggerTab(page);
    await expect(page.getByText('기존 스케줄')).toBeVisible();
    await page.getByRole('button').filter({ has: page.locator('.lucide-ellipsis') }).first().click();
    await page.getByRole('menuitem', { name: '편집' }).click();
    await expect(page.getByRole('dialog').getByText('트리거 편집')).toBeVisible();

    const message = /실행 주기는 1분보다 짧게 할 수 없습니다/;
    const cronInput = page.locator('input[placeholder*="* * *"]').first();
    await cronInput.fill('* * * * * *');

    // 입력 중 바로 안내, 저장 시도해도 PUT 없음
    await expect(page.getByRole('dialog').getByText(message)).toBeVisible();
    await expect(cronInput).toHaveAttribute('aria-invalid', 'true');
    await page.getByRole('button', { name: '저장' }).click();
    await expect(page.getByRole('dialog').getByText(message)).toBeVisible();
    await page.waitForTimeout(300);
    expect(updateCapture.requests).toHaveLength(0);

    // 매분(초 칸 숫자 하나)으로 고치면 안내가 사라지고 그 값으로 저장된다
    await cronInput.fill('0 * * * * *');
    await expect(page.getByRole('dialog').getByText(message)).toHaveCount(0);
    await page.getByRole('button', { name: '저장' }).click();
    const req = await updateCapture.waitForRequest();
    expect(req.payload).toMatchObject({ config: { cron: '0 * * * * *' } });
  });

  /**
   * 회귀 방지 테스트 (#638의 stale-error 처리가 EditTriggerDialog에서도 동작하는지 확인)
   * 잘못된 cron으로 1차 저장 실패 후 값을 올바르게 고치면, 재제출 없이도
   * stale하게 남아있던 오류 문구가 즉시 사라져야 한다.
   */
  test('트리거 편집 — 잘못된 cron 저장 실패 후 값을 고치면 오류 문구가 즉시 사라진다 (refs #638, #641)', async ({
    authenticatedPage: page,
  }) => {
    const existing = createTrigger({
      id: 5,
      name: '기존 스케줄',
      triggerType: 'SCHEDULE',
      config: { cron: '0 9 * * *', timezone: 'Asia/Seoul', concurrencyPolicy: 'SKIP' },
    });
    await setupTriggerTabMocks(page, { triggers: [existing] });

    let apiCalled = false;
    await page.route('/api/v1/pipelines/1/triggers/5', (route) => {
      if (route.request().method() === 'PUT') {
        apiCalled = true;
        return route.fulfill({ status: 200, body: JSON.stringify(existing) });
      }
      return route.continue();
    });

    await gotoTriggerTab(page);

    await expect(page.getByText('기존 스케줄')).toBeVisible();
    await page.getByRole('button').filter({ has: page.locator('.lucide-ellipsis') }).first().click();
    await page.getByRole('menuitem', { name: '편집' }).click();
    await expect(page.getByRole('dialog').getByText('트리거 편집')).toBeVisible();

    const cronInput = page.locator('input[placeholder*="* * *"]').first();

    // 1차: 잘못된 cron으로 저장 시도 → 오류 표시 확인
    await cronInput.fill('invalid cron here');
    await page.getByRole('button', { name: '저장' }).click();
    await expect(page.getByText('유효하지 않은 Cron 표현식입니다')).toBeVisible();
    expect(apiCalled).toBe(false);

    // 값을 유효한 cron으로 고침 — 재제출 없이도 stale 오류 문구가 사라져야 함
    await cronInput.fill('0 9 * * *');
    await expect(page.getByText('유효하지 않은 Cron 표현식입니다')).not.toBeVisible();

    // 재제출 시 정상적으로 저장됨
    await page.getByRole('button', { name: '저장' }).click();
    await expect.poll(() => apiCalled).toBe(true);
  });

  /**
   * 회귀 방지 테스트 (WD-107)
   * 스케줄 트리거를 편집해 프리셋("매일 09:00", 5필드)으로 바꿔 저장하면 서버가 6필드로 정규화·재등록하고
   * 다음 실행 시각을 새 cron 기준으로 갱신한다. 화면은 저장 후 목록을 다시 읽어 새 "다음 실행"을 보여야 한다
   * (결함 당시엔 스케줄이 사라진 채 옛 03:00 이 그대로 남았다).
   * 목록 GET 모킹은 PUT 전후로 응답이 바뀌는 상태형이다 — 고정 응답이면 재조회 여부를 증명하지 못한다.
   */
  test('트리거 편집 — 프리셋으로 cron 을 바꿔 저장하면 "다음 실행"이 새 시각으로 갱신된다 (WD-107)', async ({
    authenticatedPage: page,
  }) => {
    // 서버가 생성 시 정규화해 돌려주는 형태(6필드)와 trigger_state 에서 꺼낸 UTC nextFireTime 을 그대로 흉내 낸다.
    const OLD_NEXT = '2026-10-20T18:00:00Z'; // 03:00 Asia/Seoul
    const NEW_NEXT = '2026-10-21T00:00:00Z'; // 09:00 Asia/Seoul
    const existing = createTrigger({
      id: 5,
      name: '매일새벽',
      description: '',
      triggerType: 'SCHEDULE',
      config: { cron: '0 0 3 * * *', timezone: 'Asia/Seoul', concurrencyPolicy: 'SKIP' },
      nextFireTime: OLD_NEXT,
    });
    const updated = {
      ...existing,
      config: { cron: '0 0 9 * * *', timezone: 'Asia/Seoul', concurrencyPolicy: 'SKIP' },
      nextFireTime: NEW_NEXT,
    };
    await setupTriggerTabMocks(page, { triggers: [existing] });

    // 목록 GET: PUT 이 성공한 뒤부터는 갱신된 트리거를 돌려준다(나중에 등록한 route 가 우선한다).
    let saved = false;
    await page.route(
      (url) => url.pathname === '/api/v1/pipelines/1/triggers',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([saved ? updated : existing]),
        });
      },
    );
    let putPayload: unknown = null;
    await page.route(
      (url) => url.pathname === '/api/v1/pipelines/1/triggers/5',
      (route) => {
        if (route.request().method() !== 'PUT') return route.fallback();
        putPayload = route.request().postDataJSON();
        saved = true;
        // 실제 API 는 204 No Content 를 돌려준다.
        return route.fulfill({ status: 204, body: '' });
      },
    );

    // 화면과 같은 방식(ko-KR 로케일, 브라우저 시간대)으로 기대 문구를 만든다 — 실행 환경 시간대에 묶이지 않게.
    // 문자열 파싱 시간대 함정(#691)을 피하려고 UTC epoch 숫자로 넘긴다.
    await gotoTriggerTab(page);
    const [oldText, newText] = await page.evaluate(
      ([a, b]) =>
        // eslint-disable-next-line no-restricted-syntax -- 서버 문자열이 아니라 UTC epoch 숫자다
        [new Date(a).toLocaleString('ko-KR'), new Date(b).toLocaleString('ko-KR')],
      [Date.UTC(2026, 9, 20, 18), Date.UTC(2026, 9, 21, 0)],
    );
    await expect(page.getByText(`다음 실행: ${oldText}`)).toBeVisible();

    await page.getByRole('button').filter({ has: page.locator('.lucide-ellipsis') }).first().click();
    await page.getByRole('menuitem', { name: '편집' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('트리거 편집')).toBeVisible();

    await dialog.getByRole('button', { name: '매일 09:00' }).click();
    await dialog.getByRole('button', { name: '저장' }).click();

    // 웹 프리셋은 5필드를 보낸다 — 정규화는 서버 책임이다.
    await expect.poll(() => putPayload).toMatchObject({ config: { cron: '0 9 * * *' } });
    await expect(page.getByText('트리거 "매일새벽"이 수정되었습니다.')).toBeVisible();
    await expect(dialog).toBeHidden();

    // 저장 후 재조회로 "다음 실행"이 새 시각으로 바뀌고 옛 시각은 사라져야 한다.
    await expect(page.getByText(`다음 실행: ${newText}`)).toBeVisible();
    await expect(page.getByText(`다음 실행: ${oldText}`)).toHaveCount(0);
  });

  /**
   * 회귀 방지 테스트 (WD-107)
   * 서버가 수정 요청의 cron 을 거부(400)하면 서버 문구를 그대로 보여 주고 다이얼로그를 닫지 않아야 한다 —
   * "수정되었습니다"처럼 성공으로 보이면 안 된다.
   */
  test('트리거 편집 — 서버가 cron 을 거부하면(400) 서버 오류 문구를 보여 주고 다이얼로그를 유지한다 (WD-107)', async ({
    authenticatedPage: page,
  }) => {
    const existing = createTrigger({
      id: 5,
      name: '매일새벽',
      triggerType: 'SCHEDULE',
      config: { cron: '0 0 3 * * *', timezone: 'Asia/Seoul', concurrencyPolicy: 'SKIP' },
    });
    await setupTriggerTabMocks(page, { triggers: [existing] });
    // 실제 GlobalExceptionHandler(IllegalArgumentException → 400) 응답 형태를 흉내 낸다.
    await mockApi(
      page,
      'PUT',
      '/api/v1/pipelines/1/triggers/5',
      { status: 400, error: 'Bad Request', message: '유효하지 않은 cron 표현식입니다: 0 9 * * *' },
      { status: 400 },
    );

    await gotoTriggerTab(page);
    await page.getByRole('button').filter({ has: page.locator('.lucide-ellipsis') }).first().click();
    await page.getByRole('menuitem', { name: '편집' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('트리거 편집')).toBeVisible();

    await dialog.getByRole('button', { name: '매일 09:00' }).click();
    await dialog.getByRole('button', { name: '저장' }).click();

    await expect(page.getByText('유효하지 않은 cron 표현식입니다: 0 9 * * *')).toBeVisible();
    await expect(page.getByText('트리거 "매일새벽"이 수정되었습니다.')).toHaveCount(0);
    await expect(dialog.getByText('트리거 편집')).toBeVisible();
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
    // WD-13: 삭제 확인은 destructive
    await expect(page.getByRole('alertdialog').getByRole('button', { name: '삭제', exact: true })).toHaveAttribute('data-variant', 'destructive');
    await page.getByRole('button', { name: '삭제', exact: true }).click();

    // 짧은 대기 후 호출 여부 확인
    await expect.poll(() => deleteCalled).toBe(true);
  });

  test('트리거 활성/비활성 토글 — PATCH 호출 검증', async ({ authenticatedPage: page }) => {
    const existing = createTrigger({ id: 8, name: '토글 대상', isEnabled: true });
    await setupTriggerTabMocks(page, { triggers: [existing] });

    let toggleCalled = false;
    let togglePayload: unknown;
    await page.route(
      (url) => url.pathname === '/api/v1/pipelines/1/triggers/8/toggle',
      (route) => {
        if (route.request().method() === 'PATCH') {
          toggleCalled = true;
          togglePayload = route.request().postDataJSON();
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
    // WD-435: 켜진 트리거의 「비활성화」는 원하는 상태(enabled=false)를 실어 보낸다.
    expect(togglePayload).toEqual({ enabled: false });
  });

  test('옛 화면에서 꺼진 것으로 보이는 트리거를 「활성화」하면 enabled=true 를 보내고 활성화 토스트가 뜬다 (WD-435)', async ({ authenticatedPage: page }) => {
    // 화면은 「비활성」을 보고 있다 — 그사이 서버에서 이미 켜졌어도 요청은 「켜라」여야 한다(예전엔 서버가 값을 뒤집어 꺼졌다).
    const existing = createTrigger({ id: 8, name: '켤 대상', isEnabled: false });
    await setupTriggerTabMocks(page, { triggers: [existing] });

    let togglePayload: unknown;
    await page.route(
      (url) => url.pathname === '/api/v1/pipelines/1/triggers/8/toggle',
      (route) => {
        if (route.request().method() === 'PATCH') {
          togglePayload = route.request().postDataJSON();
          return route.fulfill({ status: 204 });
        }
        return route.fallback();
      },
    );

    await gotoTriggerTab(page);

    await expect(page.getByText('켤 대상')).toBeVisible();
    await page.getByRole('button').filter({ has: page.locator('.lucide-ellipsis') }).first().click();
    await page.getByRole('menuitem', { name: '활성화', exact: true }).click();

    await expect.poll(() => togglePayload).toEqual({ enabled: true });
    await expect(page.getByText('트리거 "켤 대상"이 활성화되었습니다.')).toBeVisible();
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

  /**
   * 회귀 방지 테스트 (WD-109)
   * IP 제한 없이 API·AI 로 만든 API 트리거는 저장된 config 에 allowedIps 키가 없다.
   * ApiTriggerForm 이 config.allowedIps.length 를 바로 읽어, 편집을 누르는 순간 페이지 전체가 오류 화면이 되던 결함.
   */
  test('API 트리거 편집 — allowedIps 가 없는 트리거도 편집 창이 열리고 IP 를 추가해 저장할 수 있다 (WD-109)', async ({
    authenticatedPage: page,
  }) => {
    const existing = createTrigger({
      id: 13,
      name: 'IP 제한 없는 API 트리거',
      triggerType: 'API',
      config: {},
    });
    await setupTriggerTabMocks(page, { triggers: [existing] });
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(e.message));

    const updateCapture = await mockApi(page, 'PUT', '/api/v1/pipelines/1/triggers/13', '', { capture: true });

    await gotoTriggerTab(page);
    await expect(page.getByText('IP 제한 없는 API 트리거')).toBeVisible();
    await page.getByRole('button').filter({ has: page.locator('.lucide-ellipsis') }).first().click();
    await page.getByRole('menuitem', { name: '편집' }).click();

    // 오류 화면 대신 편집 창이 열리고 IP 제한 입력이 보인다
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('트리거 편집')).toBeVisible();
    await expect(dialog.getByLabel('IP 제한 (선택)')).toBeVisible();
    expect(errors).toEqual([]);

    // 빈 목록에서 IP 를 추가하면 배지가 보이고 PUT 에 실린다
    await dialog.getByLabel('IP 제한 (선택)').fill('10.0.0.0/24');
    await dialog.getByRole('button', { name: '추가' }).click();
    await expect(dialog.getByText('10.0.0.0/24')).toBeVisible();
    await dialog.getByRole('button', { name: '저장' }).click();

    const req = await updateCapture.waitForRequest();
    expect(req.payload).toMatchObject({ name: 'IP 제한 없는 API 트리거', config: { allowedIps: ['10.0.0.0/24'] } });
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

  /**
   * 회귀 방지 테스트 (#638)
   * 잘못된 cron으로 1차 제출 실패 후 값을 올바르게 고치면, 부모(AddTriggerDialog)가
   * 제출 시점에만 계산해 stale하게 남아있던 validationErrors.cron이 즉시 지워져야 한다.
   * 이전에는 CronExpressionInput의 displayError가 stale한 부모 error를 우선해
   * 유효한 값인데도 오류 문구가 정상 설명·다음 실행 예정 목록과 함께 계속 표시됐다.
   */
  test('스케줄 트리거 — 잘못된 cron 제출 실패 후 값을 고치면 오류 문구가 즉시 사라진다 (refs #638)', async ({
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

    await page.getByLabel(/^이름/).fill('cron stale 테스트');

    const cronInput = page.locator('input[placeholder*="* * *"]').first();

    // 1차: 잘못된 cron으로 제출 → 오류 표시 확인
    await cronInput.fill('invalid cron here');
    await page.getByRole('button', { name: '트리거 생성' }).click();
    await expect(page.getByText('유효하지 않은 Cron 표현식입니다')).toBeVisible();
    expect(apiCalled).toBe(false);

    // 값을 유효한 cron으로 고침 — 재제출 없이도 stale 오류 문구가 사라져야 함
    await cronInput.fill('0 9 * * *');
    await expect(page.getByText('유효하지 않은 Cron 표현식입니다')).not.toBeVisible();
    // 정상 해석 문구가 표시됨
    await expect(page.getByText('시간 09:00')).toBeVisible();

    // 재제출 시 정상적으로 생성됨
    await page.getByRole('button', { name: '트리거 생성' }).click();
    await expect.poll(() => apiCalled).toBe(true);
  });

  test('API 트리거 — 잘못된 IP 형식 입력 시 추가 차단 + 에러 메시지가 표시된다', async ({
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

    // API 트리거 유형 선택
    await page.getByRole('button', { name: /트리거 추가/ }).click();
    await page.getByRole('button', { name: /API.*외부 API/ }).click();

    // 잘못된 IP 형식 입력 → 추가 버튼 클릭
    await page.getByPlaceholder('192.168.1.0/24').fill('invalid-ip');
    await page.getByRole('button', { name: '추가' }).click();

    // 에러 메시지가 표시되어야 하며 목록에 추가되지 않음
    await expect(page.getByText(/올바른 IPv4 주소 또는 CIDR/)).toBeVisible();
    await expect(page.getByText('invalid-ip')).not.toBeVisible();

    // 잘못된 옥텟값 (999.999.999.999) 입력
    await page.getByPlaceholder('192.168.1.0/24').fill('999.999.999.999');
    await page.getByRole('button', { name: '추가' }).click();
    await expect(page.getByText(/올바른 IPv4 주소 또는 CIDR/)).toBeVisible();
    await expect(page.getByText('999.999.999.999')).not.toBeVisible();

    // 유효한 CIDR 입력은 정상 추가됨
    await page.getByPlaceholder('192.168.1.0/24').fill('10.0.0.0/24');
    await page.getByRole('button', { name: '추가' }).click();
    await expect(page.getByText('10.0.0.0/24')).toBeVisible();
    // 에러 메시지 사라짐
    await expect(page.getByText(/올바른 IPv4 주소 또는 CIDR/)).not.toBeVisible();

    // API는 아직 호출되지 않아야 함 (트리거 생성 버튼 미클릭)
    expect(apiCalled).toBe(false);
  });

  test('API 트리거 — 잘못된 CIDR 프리픽스 입력 시 추가 차단된다', async ({
    authenticatedPage: page,
  }) => {
    await setupTriggerTabMocks(page);

    await gotoTriggerTab(page);

    await page.getByRole('button', { name: /트리거 추가/ }).click();
    await page.getByRole('button', { name: /API.*외부 API/ }).click();

    // 프리픽스가 범위 초과 (0~32만 허용)
    await page.getByPlaceholder('192.168.1.0/24').fill('192.168.1.0/33');
    await page.getByRole('button', { name: '추가' }).click();
    await expect(page.getByText(/올바른 IPv4 주소 또는 CIDR/)).toBeVisible();
    await expect(page.getByText('192.168.1.0/33')).not.toBeVisible();
  });

  /**
   * 회귀 방지 테스트 (#196)
   * DATASET_CHANGE 트리거 생성 시 폴링 주기가 30초 미만이면 유효성 에러가 표시되고 API 호출이 차단되어야 한다.
   */
  test('DATASET_CHANGE 트리거 추가 — 폴링 주기 29초 입력 시 유효성 에러가 표시되고 API 호출이 차단된다 (refs #196)', async ({
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

    // 데이터셋 목록 모킹 (콤보박스가 데이터셋을 로드할 수 있도록)
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [{ id: 1, name: '센서 데이터', tableName: 'sensor_data' }],
      page: 0,
      size: 1000,
      totalElements: 1,
      totalPages: 1,
    });

    await gotoTriggerTab(page);

    await page.getByRole('button', { name: /트리거 추가/ }).click();
    await page.getByRole('button', { name: /데이터셋 변경/ }).click();

    await page.getByLabel(/^이름/).fill('변경 감지 트리거');

    // 폴링 주기를 30초 미만(29초)으로 설정
    const pollingInput = page.locator('input[type="number"]').first();
    await pollingInput.fill('29');

    await page.getByRole('button', { name: '트리거 생성' }).click();

    // 폴링 주기 유효성 에러가 표시되어야 함
    await expect(page.getByText('폴링 주기는 30~3600초 사이여야 합니다')).toBeVisible();
    // API가 호출되지 않아야 함 (클라이언트 측 차단 확인)
    expect(apiCalled).toBe(false);
  });

  /**
   * 회귀 방지 테스트 (#196)
   * DATASET_CHANGE 트리거 생성 시 폴링 주기가 3600초를 초과하면 유효성 에러가 표시되어야 한다.
   */
  test('DATASET_CHANGE 트리거 추가 — 폴링 주기 3601초 입력 시 유효성 에러가 표시된다 (refs #196)', async ({
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
    await page.getByRole('button', { name: /데이터셋 변경/ }).click();

    await page.getByLabel(/^이름/).fill('변경 감지 트리거');

    // 폴링 주기를 3600초 초과(3601초)로 설정
    const pollingInput = page.locator('input[type="number"]').first();
    await pollingInput.fill('3601');

    await page.getByRole('button', { name: '트리거 생성' }).click();

    // 유효성 에러가 표시되어야 함
    await expect(page.getByText('폴링 주기는 30~3600초 사이여야 합니다')).toBeVisible();
    expect(apiCalled).toBe(false);
  });

  /**
   * 회귀 방지 테스트 (#196)
   * DATASET_CHANGE 트리거 생성 시 폴링 주기가 유효한 범위(30~3600초)이면 정상 생성된다.
   */
  test('DATASET_CHANGE 트리거 추가 — 폴링 주기 30초·데이터셋 선택 시 정상 생성되고 payload 가 전송된다 (refs #196)', async ({
    authenticatedPage: page,
  }) => {
    // 데이터셋이 있는 경우로 별도 모킹 (setupTriggerTabMocks는 빈 목록으로 설정)
    const pipelineId = 1;
    await mockApi(page, 'GET', `/api/v1/pipelines/${pipelineId}`, createPipelineDetail({ id: pipelineId }));
    await mockApi(page, 'GET', `/api/v1/pipelines/${pipelineId}/executions`, []);
    await mockApi(page, 'GET', `/api/v1/pipelines/${pipelineId}/trigger-events`, []);
    await mockApi(page, 'GET', `/api/v1/pipelines/${pipelineId}/triggers`, []);
    // 데이터셋 목록에 항목 포함 (DatasetCombobox가 선택할 수 있도록)
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [{ id: 42, name: '화재 이력', tableName: 'fire_history' }],
      page: 0,
      size: 1000,
      totalElements: 1,
      totalPages: 1,
    });

    const created = createTrigger({
      id: 20,
      name: '화재 이력 변경 감지',
      triggerType: 'DATASET_CHANGE',
      config: { datasetIds: [42], pollingIntervalSeconds: 30, debounceSeconds: 0 },
    });
    const createCapture = await mockApi(
      page,
      'POST',
      '/api/v1/pipelines/1/triggers',
      created,
      { capture: true },
    );

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: '트리거' }).click();
    await expect(page.getByRole('button', { name: /트리거 추가/ })).toBeVisible();

    await page.getByRole('button', { name: /트리거 추가/ }).click();
    await page.getByRole('button', { name: /데이터셋 변경/ }).click();

    await page.getByLabel(/^이름/).fill('화재 이력 변경 감지');

    // DatasetCombobox에서 데이터셋 선택 (콤보박스 버튼 클릭 → 항목 선택)
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '화재 이력' }).click();

    // 폴링 주기를 30초(최솟값)로 설정
    const pollingInput = page.locator('input[type="number"]').first();
    await pollingInput.fill('30');

    await page.getByRole('button', { name: '트리거 생성' }).click();

    // POST payload 검증: pollingIntervalSeconds가 30으로 전송되어야 함
    const req = await createCapture.waitForRequest();
    expect(req.payload).toMatchObject({
      name: '화재 이력 변경 감지',
      triggerType: 'DATASET_CHANGE',
      config: expect.objectContaining({
        pollingIntervalSeconds: 30,
      }),
    });
  });

  /**
   * 회귀 테스트 (#676): 백엔드가 SCHEDULE 트리거의 nextFireTime을 쓰는 경로가 없어
   * "다음 실행" 줄이 한 번도 표시된 적이 없었다. GET /triggers 응답에 nextFireTime이
   * 채워지면 트리거 카드에 "다음 실행" 문구가 나타나고, 없으면 나타나지 않아야 한다.
   */
  test('스케줄 트리거 — nextFireTime이 있으면 "다음 실행" 문구가 표시된다 (refs #676)', async ({
    authenticatedPage: page,
  }) => {
    const withNextFire = createTrigger({
      id: 20,
      name: '다음 실행 표시 트리거',
      triggerType: 'SCHEDULE',
      nextFireTime: '2026-09-13T09:00:00Z',
    });
    const withoutNextFire = createTrigger({
      id: 21,
      name: '다음 실행 미등록 트리거',
      triggerType: 'SCHEDULE',
      nextFireTime: null,
    });

    await setupTriggerTabMocks(page, { triggers: [withNextFire, withoutNextFire] });
    await gotoTriggerTab(page);

    const withCard = page.getByText('다음 실행 표시 트리거').locator('..').locator('..');
    await expect(withCard.getByText(/다음 실행:/)).toBeVisible();

    const withoutCard = page.getByText('다음 실행 미등록 트리거').locator('..').locator('..');
    await expect(withoutCard.getByText(/다음 실행:/)).toHaveCount(0);
  });

  /**
   * 회귀 테스트 (WD-114): 비활성 트리거는 실행되지 않으므로 「다음 실행」을 보이지 않아야 한다. 서버가 옛 응답처럼 nextFireTime 을
   * 실어 보내도(배포 순서·옛 캐시) 화면이 「비활성」 배지와 다음 실행 시각을 함께 보여 주지 않는지 확인한다.
   */
  test('스케줄 트리거 — 비활성 트리거에는 "다음 실행"이 표시되지 않는다 (WD-114)', async ({
    authenticatedPage: page,
  }) => {
    const enabled = createTrigger({
      id: 22,
      name: '켜진 스케줄',
      triggerType: 'SCHEDULE',
      isEnabled: true,
      nextFireTime: '2026-09-13T09:00:00Z',
    });
    const disabled = createTrigger({
      id: 23,
      name: '꺼진 스케줄',
      triggerType: 'SCHEDULE',
      isEnabled: false,
      nextFireTime: '2026-09-13T09:00:00Z',
    });

    await setupTriggerTabMocks(page, { triggers: [enabled, disabled] });
    await gotoTriggerTab(page);

    const enabledCard = page.locator('[data-slot="card"]').filter({ hasText: '켜진 스케줄' });
    await expect(enabledCard.getByText('활성', { exact: true })).toBeVisible();
    await expect(enabledCard.getByText(/다음 실행:/)).toBeVisible();

    const disabledCard = page.locator('[data-slot="card"]').filter({ hasText: '꺼진 스케줄' });
    await expect(disabledCard.getByText('비활성', { exact: true })).toBeVisible();
    await expect(disabledCard.getByText(/다음 실행:/)).toHaveCount(0);
  });
  /**
   * 보안 등급(Task 3 D1, 디자인 검토 3절): 볼 수 없는 감시 대상은 이름 대신 잠금 칩("열람 권한 없음", X 없음)으로 보이고,
   * 주기만 바꿔 저장해도 PUT config.datasetIds 에 그대로 남는다(서버는 새로 추가된 id 만 판정).
   */
  test('DATASET_CHANGE 트리거 편집 — 볼 수 없는 감시 대상은 잠금 칩, 주기만 바꿔 저장해도 id 유지', async ({
    authenticatedPage: page,
  }) => {
    const existing = createTrigger({
      id: 21,
      name: '숨김 감시 트리거',
      triggerType: 'DATASET_CHANGE',
      config: { datasetIds: [42, 77], pollingIntervalSeconds: 60, debounceSeconds: 0 },
    });
    await mockApi(page, 'GET', '/api/v1/pipelines/1', createPipelineDetail({ id: 1 }));
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', [existing]);
    // 볼 수 있는 목록에는 42 만 — 77 은 숨김
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [{ id: 42, name: '화재 이력', tableName: 'fire_history' }],
      page: 0,
      size: 1000,
      totalElements: 1,
      totalPages: 1,
    });
    const updateCapture = await mockApi(page, 'PUT', '/api/v1/pipelines/1/triggers/21', {}, { capture: true });

    await gotoTriggerTab(page);
    await page.getByRole('button').filter({ has: page.locator('.lucide-ellipsis') }).first().click();
    await page.getByRole('menuitem', { name: '편집' }).click();

    const watch = page.getByRole('dialog').getByRole('combobox', { name: '감시 대상 데이터셋' });
    await expect(watch).toContainText('화재 이력');
    const lockChip = watch.getByTestId('dataset-restricted');
    await expect(lockChip).toHaveText('열람 권한 없음');
    await expect(lockChip.locator('[role="button"]')).toHaveCount(0);

    await page.getByRole('dialog').getByLabel('폴링 주기 (초)').fill('120');
    await page.getByRole('button', { name: '저장' }).click();

    const req = await updateCapture.waitForRequest();
    const payload = req.payload as { config: { datasetIds: number[]; pollingIntervalSeconds: number } };
    expect(payload.config.pollingIntervalSeconds).toBe(120);
    expect(payload.config.datasetIds).toEqual([42, 77]);
    await expect(page.locator('[data-sonner-toast][data-type="error"]')).toHaveCount(0);
  });

  /**
   * 회귀 테스트 (WD-221): 감시 데이터셋이 모두 삭제되면 서버가 감시 목록을 비우고 트리거를 끈 뒤 watchedDatasetsMissing 을 내려 준다.
   * 예전엔 이유 없는 「비활성」만 보였고 「활성화」도 눌렸다. 목록에 꺼진 이유가 보이고 「활성화」가 막히는지, 끄기는 막지 않는지 본다.
   */
  test('DATASET_CHANGE 트리거 — 감시 데이터셋이 모두 삭제되어 꺼진 이유가 보이고 활성화가 막힌다 (WD-221)', async ({
    authenticatedPage: page,
  }) => {
    const orphan = createTrigger({
      id: 31,
      name: '감시 대상 없는 트리거',
      triggerType: 'DATASET_CHANGE',
      isEnabled: false,
      config: { datasetIds: [], pollingIntervalSeconds: 60, debounceSeconds: 0 },
      watchedDatasetsMissing: true,
    });
    const healthy = createTrigger({
      id: 32,
      name: '정상 감시 트리거',
      triggerType: 'DATASET_CHANGE',
      isEnabled: false,
      config: { datasetIds: [42], pollingIntervalSeconds: 60, debounceSeconds: 0 },
      watchedDatasetsMissing: false,
    });
    await setupTriggerTabMocks(page, { triggers: [orphan, healthy] });

    await gotoTriggerTab(page);
    const reason = '감시하던 데이터셋이 모두 삭제되어 꺼졌습니다. 편집에서 감시할 데이터셋을 고르세요.';
    const orphanCard = page.locator('[data-slot="card"]').filter({ hasText: '감시 대상 없는 트리거' });
    const healthyCard = page.locator('[data-slot="card"]').filter({ hasText: '정상 감시 트리거' });
    await expect(orphanCard.getByText(reason)).toBeVisible();
    // 대조: 감시 대상이 남은 꺼진 트리거에는 이유 문구가 없고 「활성화」가 가능하다
    await expect(healthyCard.getByText(reason)).toHaveCount(0);

    await orphanCard.getByRole('button').filter({ has: page.locator('.lucide-ellipsis') }).click();
    await expect(page.getByRole('menuitem', { name: '활성화' })).toBeDisabled();
    await page.keyboard.press('Escape');

    await healthyCard.getByRole('button').filter({ has: page.locator('.lucide-ellipsis') }).click();
    await expect(page.getByRole('menuitem', { name: '활성화' })).toBeEnabled();
  });

  /**
   * 회귀 테스트 (WD-152): 트리거 이벤트 로그가 건너뜀·오류 이벤트의 사유(detail.reason)를 배지 아래에 보여 준다. 예전엔 「건너뜀」 배지만
   * 보여 왜 실행되지 않았는지 알 수 없었다. 예외 원문(detail.error)은 보이지 않아야 하고, 사유가 없는 발화 이벤트에는 사유 문구가 없다.
   */
  test('트리거 이벤트 로그 — 건너뜀·오류 이벤트의 사유를 배지 아래에 보여 준다 (WD-152)', async ({
    authenticatedPage: page,
  }) => {
    const base = { triggerId: 30, triggerName: 'API 호출', createdAt: '2026-10-22T09:00:00' };
    const events: TriggerEventResponse[] = [
      {
        ...base,
        id: 1,
        eventType: 'SKIPPED',
        executionId: null,
        detail: { reason: '파이프라인이 비활성 상태라 실행하지 않았습니다', sourceIp: '10.0.0.1' },
      },
      {
        ...base,
        id: 2,
        eventType: 'ERROR',
        executionId: null,
        detail: { reason: '파이프라인 실행을 시작하지 못했습니다', error: 'ERROR: relation "x" does not exist' },
      },
      { ...base, id: 3, eventType: 'FIRED', executionId: 77, detail: { sourceIp: '10.0.0.1' } },
    ];

    await setupTriggerTabMocks(page, { events });
    await gotoTriggerTab(page);

    const rows = page.getByRole('row');
    const skippedRow = rows.filter({ hasText: '건너뜀' });
    await expect(skippedRow.getByText('파이프라인이 비활성 상태라 실행하지 않았습니다')).toBeVisible();

    const errorRow = rows.filter({ hasText: '오류' });
    await expect(errorRow.getByText('파이프라인 실행을 시작하지 못했습니다')).toBeVisible();
    // 예외 원문은 내부 구조를 드러낼 수 있어 화면에 보이지 않는다
    await expect(page.getByText('relation "x" does not exist')).toHaveCount(0);

    // 사유가 없는 발화 이벤트는 실행 링크만 있고 사유 문구가 없다
    const firedRow = rows.filter({ hasText: '발화' });
    await expect(firedRow.getByRole('link', { name: '#77' })).toBeVisible();
    await expect(firedRow.locator('p')).toHaveCount(0);
  });

  test('삭제된 파이프라인 — 트리거 목록이 404 면 「트리거가 없습니다」 대신 파이프라인 없음 안내를 보인다 (WD-244)', async ({
    authenticatedPage: page,
  }) => {
    const notFound = {
      status: 404,
      error: 'Not Found',
      message: '파이프라인을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.',
    };
    await setupTriggerTabMocks(page);
    // 나중에 등록한 route 가 먼저 매칭된다 — 상세는 열린 채(캐시) 트리거·이벤트 조회만 404. 재시도(1회)에도 계속 404 다.
    await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', notFound, { status: 404 });
    await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', notFound, { status: 404 });

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: '트리거' }).click();

    await expect(page.getByRole('alert').filter({ hasText: notFound.message })).toBeVisible();
    await expect(page.getByText('트리거가 없습니다.')).toHaveCount(0);
    // 없는 파이프라인에 트리거를 추가할 수 없으므로 추가 버튼과 이벤트 목록을 숨긴다
    await expect(page.getByRole('button', { name: /트리거 추가/ })).toHaveCount(0);
    await expect(page.getByText('최근 트리거 이벤트')).toHaveCount(0);
  });

  test('트리거 이벤트 조회 실패 — 「이벤트가 없습니다」 대신 서버 문구를 보인다 (WD-244)', async ({
    authenticatedPage: page,
  }) => {
    await setupTriggerTabMocks(page);
    await mockApi(
      page,
      'GET',
      '/api/v1/pipelines/1/trigger-events',
      { status: 400, error: 'Bad Request', message: 'limit 은 1~200 사이여야 합니다.' },
      { status: 400 },
    );
    await gotoTriggerTab(page);

    await expect(page.getByRole('alert').filter({ hasText: 'limit 은 1~200 사이여야 합니다.' })).toBeVisible();
    await expect(page.getByText('트리거 이벤트가 없습니다.')).toHaveCount(0);
  });

  /**
   * 회귀 테스트 (WD-406): 모바일 폭(375px)에서 트리거 카드가 화면 밖으로 넘쳐 오른쪽 ⋯ 메뉴가 잘리면 편집·삭제를 할 수 없다.
   * 긴 이름·cron 설명이 있어도 ⋯ 버튼이 화면 안에 있고 눌러서 메뉴를 열 수 있는지 확인한다.
   */
  test('모바일 폭 — 긴 이름 트리거도 ⋯ 메뉴가 화면 안에 보이고 열린다 (WD-406)', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    const longName = '매일 아침 운영 데이터 적재 후 집계 파이프라인을 다시 돌리는 아주 긴 이름의 스케줄 트리거';
    await setupTriggerTabMocks(page, {
      triggers: [createTrigger({ id: 30, name: longName, triggerType: 'SCHEDULE', nextFireTime: '2026-09-13T09:00:00Z' })],
    });
    await gotoTriggerTab(page);

    const menuButton = page.getByRole('button', { name: `트리거 "${longName}" 작업` });
    await expect(menuButton).toBeInViewport({ ratio: 1 });
    const box = await menuButton.boundingBox();
    expect(box!.x + box!.width).toBeLessThanOrEqual(375);

    await menuButton.click();
    await expect(page.getByRole('menuitem', { name: '편집' })).toBeVisible();
  });

  /**
   * WD-407: ⋯ 메뉴의 「편집」·「삭제」로 연 창을 키보드(Esc)로 닫으면 포커스가 ⋯ 버튼으로 돌아온다.
   * 예전에는 메뉴 항목이 닫히며 사라져 Radix 가 돌려줄 요소를 잃고 포커스가 body 로 떨어졌다.
   */
  test('⋯ 메뉴로 연 편집·삭제 창을 Esc 로 닫으면 포커스가 ⋯ 버튼으로 돌아온다 (WD-407)', async ({
    authenticatedPage: page,
  }) => {
    await setupTriggerTabMocks(page, {
      triggers: [
        createTrigger({ id: 21, name: '아침 실행', triggerType: 'SCHEDULE', config: { cron: '0 9 * * *', timezone: 'Asia/Seoul', concurrencyPolicy: 'SKIP' } }),
      ],
    });
    await gotoTriggerTab(page);

    const menuButton = page.getByRole('button', { name: '트리거 "아침 실행" 작업' });
    // 편집은 dialog, 삭제 확인은 alertdialog 역할이다.
    const anyDialog = page.getByRole('dialog').or(page.getByRole('alertdialog'));
    for (const item of ['편집', '삭제']) {
      await menuButton.focus();
      await page.keyboard.press('Enter');
      await page.getByRole('menuitem', { name: item }).press('Enter');
      await expect(anyDialog).toBeVisible();
      await page.keyboard.press('Escape');
      await expect(anyDialog).toBeHidden();
      await expect(menuButton).toBeFocused();
    }
  });
});

/**
 * 회귀 가드(WD-385): 트리거 시간대를 내 시간대와 다르게 두면, 같은 다음 실행이 목록(내 시간대 오후 2:30)과 편집 창 미리보기
 * (트리거 시간대 오전 1:30)에서 시간대 표기 없이 달라 보였다. 이제 각 시각 뒤에 그 기준 시간대 이름을 붙인다.
 */
test.describe('파이프라인 트리거 — 다음 실행 시간대 표기 (WD-385)', () => {
  test.use({ timezoneId: 'Asia/Seoul' });

  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  test('목록은 내 시간대(KST), 편집 창 미리보기는 트리거 시간대(EDT)를 붙여 보인다', async ({ authenticatedPage: page }) => {
    // 미리보기는 지금 시각 기준으로 계산하므로 시각을 고정한다 — UTC 2026-10-28 12:00(뉴욕 서머타임 기간)
    await page.clock.setFixedTime(Date.UTC(2026, 9, 28, 12));
    const trigger = createTrigger({
      id: 7,
      name: '뉴욕새벽',
      description: '',
      triggerType: 'SCHEDULE',
      config: { cron: '0 30 1 * * *', timezone: 'America/New_York', concurrencyPolicy: 'SKIP' },
      nextFireTime: '2026-10-29T05:30:00Z', // 뉴욕 01:30 EDT = 서울 14:30 KST
    });
    await setupTriggerTabMocks(page, { triggers: [trigger] });
    // 편집 창 미리보기는 서버 계산 값을 보인다(WD-391) — 뉴욕 01:30 EDT 부터 5일치
    await mockCronPreview(page, TRIGGER_CRON_PREVIEW_PATH, [
      '2026-10-29T05:30:00Z',
      '2026-10-30T05:30:00Z',
      '2026-10-31T05:30:00Z',
      '2026-11-01T05:30:00Z',
      '2026-11-02T06:30:00Z',
    ]);
    await gotoTriggerTab(page);

    await expect(page.getByText('다음 실행: 2026. 10. 29. 오후 2:30:00 (KST)')).toBeVisible();

    await page.getByRole('button').filter({ has: page.locator('.lucide-ellipsis') }).first().click();
    await page.getByRole('menuitem', { name: '편집' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('1. 2026. 10. 29. 오전 1:30:00 (EDT)')).toBeVisible();
  });
});

/**
 * WD-404: 웹훅 편집 창이 「X-Hub-Signature 헤더로 HMAC-SHA256」으로만 안내해 접두어·서명 대상을 알 수 없었다.
 * 헤더 이름 둘, 값 형식(sha256= + hex, 접두어 생략 가능), GitHub 동작을 안내해야 한다.
 */
test.describe('WD-404 웹훅 서명 안내', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  test('웹훅 트리거 추가 창이 서명 헤더·값 형식·GitHub 동작을 안내한다', async ({ authenticatedPage: page }) => {
    await setupTriggerTabMocks(page);
    await gotoTriggerTab(page);

    await page.getByRole('button', { name: /트리거 추가/ }).click();
    await page.getByRole('button', { name: /웹훅.*HTTP POST/ }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('X-Hub-Signature-256', { exact: true })).toBeVisible();
    await expect(dialog.getByText('X-Hub-Signature', { exact: true })).toBeVisible();
    await expect(dialog.getByText(/요청 본문 원문을 시크릿으로 HMAC-SHA256 한 16진수/)).toBeVisible();
    await expect(dialog.getByText(/sha256=5d41402abc4b2a76/)).toBeVisible();
    await expect(dialog.getByText('GitHub 웹훅은 시크릿만 같게 넣으면 그대로 동작합니다.')).toBeVisible();
  });
});

/**
 * WD-391: 편집 창 「다음 5회」가 화면(cron-parser) 계산이라 서머타임 전환일에 서버 실제 실행과 달랐다 — 해제일엔 두 번 도는 작업이
 * 한 번으로, 시작일엔 건너뛰는 3:30 이 그대로 보였다. 이제 서버 미리보기 API(스케줄러와 같은 규칙)의 값을 디바운스해 받아 보인다.
 */
test.describe('파이프라인 트리거 — 다음 5회 서버 미리보기 (WD-391)', () => {
  test.use({ timezoneId: 'Asia/Seoul' });

  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
    await setupTriggerTabMocks(page);
  });

  /** 트리거 추가 창을 열고 스케줄(Cron) 유형을 고른다. */
  async function openScheduleForm(page: Page) {
    await gotoTriggerTab(page);
    await page.getByRole('button', { name: /트리거 추가/ }).click();
    await page.getByRole('button', { name: /스케줄.*Cron/ }).click();
    return page.getByRole('dialog');
  }

  test('서머타임 시작일 — 서버가 준 3:30 EDT 를 그대로 보이고, cron·시간대·개수를 쿼리로 보낸다', async ({
    authenticatedPage: page,
  }) => {
    // 뉴욕 시작일(2027-03-14) 매일 2:30 → 그날 3:30 EDT(07:30Z), 다음 날부터 2:30 EDT
    const preview = await mockCronPreview(page, TRIGGER_CRON_PREVIEW_PATH, [
      '2027-03-14T07:30:00Z',
      '2027-03-15T06:30:00Z',
      '2027-03-16T06:30:00Z',
      '2027-03-17T06:30:00Z',
      '2027-03-18T06:30:00Z',
    ]);
    const dialog = await openScheduleForm(page);

    await dialog.getByLabel('타임존').click();
    await page.getByRole('option', { name: 'America/New_York' }).click();
    await dialog.locator('input[placeholder*="* * *"]').first().fill('30 2 * * *');

    await expect(dialog.getByText('1. 2027. 3. 14. 오전 3:30:00 (EDT)')).toBeVisible();
    await expect(dialog.getByText('2. 2027. 3. 15. 오전 2:30:00 (EDT)')).toBeVisible();
    await expect(dialog.getByRole('list', { name: '다음 5회 실행 예정' }).getByRole('listitem')).toHaveCount(5);

    await expect.poll(() => preview.lastRequest()?.searchParams.get('cron')).toBe('30 2 * * *');
    const last = preview.lastRequest();
    expect(last?.searchParams.get('timezone')).toBe('America/New_York');
    expect(last?.searchParams.get('count')).toBe('5');
  });

  test('입력 중에는 디바운스되어 마지막 값으로 한 번만 묻는다', async ({ authenticatedPage: page }) => {
    const preview = await mockCronPreview(page, TRIGGER_CRON_PREVIEW_PATH);
    const dialog = await openScheduleForm(page);
    // 기본값(0 9 * * *) 미리보기 요청이 끝난 뒤부터 센다
    await expect(dialog.getByText(/^1\. /)).toBeVisible();
    const before = preview.requests.length;

    const input = dialog.locator('input[placeholder*="* * *"]').first();
    await input.fill('');
    // 디바운스(300ms)보다 짧은 간격으로 한 글자씩 친다
    await input.pressSequentially('15 10 * * 1', { delay: 40 });

    await expect.poll(() => preview.requests.length).toBeGreaterThan(before);
    // 디바운스가 끝날 시간을 충분히 지나도 추가 요청이 없어야 한다
    await page.waitForTimeout(800);
    const typed = preview.requests.slice(before);
    expect(typed).toHaveLength(1);
    expect(typed[0].searchParams.get('cron')).toBe('15 10 * * 1');
  });

  test('서버가 거부하면(400) 그 문구를 목록 자리에 보인다', async ({ authenticatedPage: page }) => {
    await mockCronPreview(page, TRIGGER_CRON_PREVIEW_PATH, [], {
      status: 400,
      message: '유효하지 않은 시간대입니다: Asia/Seoul',
    });
    const dialog = await openScheduleForm(page);

    await expect(dialog.getByRole('alert').filter({ hasText: '유효하지 않은 시간대입니다: Asia/Seoul' })).toBeVisible();
    await expect(dialog.getByRole('list', { name: '다음 5회 실행 예정' })).toHaveCount(0);
  });

  test('형식 오류는 기존 입력 검사 문구만 보이고 서버에 묻지 않는다', async ({ authenticatedPage: page }) => {
    const preview = await mockCronPreview(page, TRIGGER_CRON_PREVIEW_PATH);
    const dialog = await openScheduleForm(page);
    await expect(dialog.getByText(/^1\. /)).toBeVisible();
    const before = preview.requests.length;

    await dialog.locator('input[placeholder*="* * *"]').first().fill('invalid cron here');

    await expect(dialog.getByText('유효하지 않은 cron 표현식입니다')).toBeVisible();
    await page.waitForTimeout(600);
    expect(preview.requests.length).toBe(before);
    await expect(dialog.getByText('다음 5회 실행 예정')).toHaveCount(0);
  });
});
