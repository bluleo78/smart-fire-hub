/**
 * 파이프라인 폼 필수 칸 접근성 E2E (WD-964)
 *
 * 트리거 추가·수정 대화상자의 이름 칸, API 호출 스텝의 직접 입력 모드 URL 칸(연결 모드 URL 칸은 표시 전용이라 제외), AI 분류 스텝의 프롬프트 칸이
 * 별표 글자 대신 aria-required="true" 로 필수를 알리고, 별표는 aria-hidden 으로 접근 이름에서 빠지는지 고정한다.
 * 조건부로 보이는 URL 칸은 보이는 칸에만 aria-required 가 있어야 한다. native required 는 쓰지 않는다(WD-908·928).
 */
import type { Page } from '@playwright/test';

import { API_FULL_URL_PLACEHOLDER } from '../../../src/lib/api-connection-form';
import { createPipelineDetail, createStep, createTrigger } from '../../factories/pipeline.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { mockCronPreview, TRIGGER_CRON_PREVIEW_PATH } from '../../fixtures/cron-preview.fixture';
import { clickToolbarAddStep, grantPipelinePermissions, setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';
import { expectAriaRequired, expectRequiredField } from '../../fixtures/required-a11y';

/** 트리거 탭 모킹 + 이동 */
async function gotoTriggerTab(page: Page, triggers: ReturnType<typeof createTrigger>[] = []) {
  await mockApi(page, 'GET', '/api/v1/pipelines/1', createPipelineDetail({ id: 1 }));
  await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', createPageResponse([]));
  await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', triggers);
  await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
  await mockCronPreview(page, TRIGGER_CRON_PREVIEW_PATH);
  await mockApi(page, 'GET', '/api/v1/datasets', { content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0 });
  await page.goto('/pipelines/1');
  await page.getByRole('tab', { name: '트리거' }).click();
  await expect(page.getByRole('button', { name: /새 트리거/ })).toBeVisible();
}

/** 신규 편집기에서 이름 있는 스텝 하나를 만들고 유형을 고른다 */
async function openNewEditorWithTypedStep(page: Page, typeLabel: 'API 호출' | 'AI 분류') {
  await mockApi(page, 'GET', '/api/v1/datasets', { content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0 });
  await mockApi(page, 'GET', '/api/v1/api-connections/selectable', [
    { id: 7, name: '날씨 API', authType: 'NONE', baseUrl: 'https://weather.example.com' },
  ]);
  await page.goto('/pipelines/new');
  await page.getByPlaceholder('파이프라인 이름').fill('필수 칸 파이프라인');
  await clickToolbarAddStep(page);
  await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });
  await page.locator('#step-name').fill('스텝');
  await page.getByRole('combobox', { name: '스크립트 타입' }).click();
  await page.getByRole('option', { name: typeLabel, exact: true }).click();
}

test.describe('파이프라인 폼 필수 칸 aria-required (WD-964)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  test('트리거 추가 대화상자 이름 칸', async ({ authenticatedPage: page }) => {
    await gotoTriggerTab(page);
    await page.getByRole('button', { name: /새 트리거/ }).click();
    await page.getByRole('button', { name: /스케줄.*Cron/ }).click();
    const dialog = page.getByRole('dialog');
    await expectRequiredField(dialog.getByRole('textbox', { name: '이름', exact: true }), '이름');
    // 선택 칸(설명)에는 주지 않는다
    await expect(dialog.getByRole('textbox', { name: '설명' })).not.toHaveAttribute('aria-required', 'true');
  });

  test('트리거 수정 대화상자 이름 칸', async ({ authenticatedPage: page }) => {
    const existing = createTrigger({
      id: 6,
      name: '기존 스케줄',
      triggerType: 'SCHEDULE',
      config: { cron: '0 0 9 * * *', timezone: 'Asia/Seoul', concurrencyPolicy: 'SKIP' },
    });
    await gotoTriggerTab(page, [existing]);
    await page.getByRole('button').filter({ has: page.locator('.lucide-ellipsis') }).first().click();
    await page.getByRole('menuitem', { name: '편집' }).click();
    const dialog = page.getByRole('dialog');
    await expectRequiredField(dialog.getByRole('textbox', { name: '이름', exact: true }), '이름');
  });

  test('API 호출 스텝 — 직접 입력/연결 모드에서 보이는 URL 칸만 aria-required', async ({ authenticatedPage: page }) => {
    await openNewEditorWithTypedStep(page, 'API 호출');
    // 직접 입력 모드(기본): URL 칸만 있다
    const customUrl = page.getByRole('textbox', { name: 'URL', exact: true });
    await expectRequiredField(customUrl, 'URL');
    // 직접 입력 모드에는 추가 경로 칸이 없다
    await expect(page.getByRole('textbox', { name: '추가 경로' })).toHaveCount(0);

    // 저장된 연결로 전환: 직접 입력 URL 칸은 사라지고, 보이는 연결 URL 칸은 표시 전용(disabled)이라 필수로 알리지 않는다
    await page.locator('[role="combobox"]:has-text("직접 입력")').click();
    await page.getByRole('option', { name: /날씨 API/ }).click();
    const baseUrl = page.getByRole('textbox', { name: 'URL', exact: true });
    await expect(baseUrl).toHaveValue('https://weather.example.com');
    await expect(baseUrl).toBeDisabled();
    await expect(baseUrl).not.toHaveAttribute('aria-required', 'true');
    await expect(page.locator('label[for$="-base-url"] [aria-hidden="true"]')).toHaveCount(0);
    await expect(page.getByPlaceholder(API_FULL_URL_PLACEHOLDER, { exact: true })).toHaveCount(0);
    await expect(page.getByRole('textbox', { name: '추가 경로' })).not.toHaveAttribute('aria-required', 'true');
  });

  // 읽기 전용(보기 모드)에서 잠긴 칸은 입력할 수 없으므로 필수로 알리지 않는다 — 스텝 종류별 케이스 표
  const readOnlyCases = [
    {
      title: '읽기 전용(보기 모드) API 호출 스텝 — 잠긴 URL 칸은 aria-required 가 없다',
      step: createStep({
        id: 11,
        name: 'api1',
        scriptType: 'API_CALL',
        scriptContent: '',
        apiConfig: { customUrl: 'https://x.example.com/a', method: 'GET', headers: {}, queryParams: {} },
      }),
      field: (page: Page) => page.getByPlaceholder(API_FULL_URL_PLACEHOLDER, { exact: true }),
    },
    {
      title: '읽기 전용(보기 모드) AI 분류 스텝 — 잠긴 프롬프트 칸은 aria-required 가 없다',
      step: createStep({
        id: 12,
        name: 'ai1',
        scriptType: 'AI_CLASSIFY',
        scriptContent: '',
        aiConfig: { prompt: '분류하세요', outputColumns: [] },
      }),
      field: (page: Page) => page.getByRole('textbox', { name: '프롬프트', exact: true }),
      // 읽기 전용에서는 「출력 컬럼 정의」 제목에도 「(필수)」 를 내지 않는다(WD-964)
      extra: async (page: Page) => {
        await expect(page.getByTestId('ai-output-columns-title')).toBeVisible();
        await expect(page.getByTestId('ai-output-columns-title').locator('.sr-only')).toHaveCount(0);
      },
    },
  ];
  for (const { title, step, field, extra } of readOnlyCases) {
    test(title, async ({ authenticatedPage: page }) => {
      await setupPipelineEditorMocks(page);
      await mockApi(page, 'GET', '/api/v1/api-connections/selectable', []);
      await mockApi(page, 'GET', '/api/v1/pipelines/1', createPipelineDetail({ id: 1, steps: [step] }));
      await page.goto('/pipelines/1');
      await page.locator('.react-flow__node').first().click();
      const locked = field(page);
      await expect(locked).toBeDisabled();
      await expect(locked).not.toHaveAttribute('aria-required', 'true');
      await extra?.(page);
    });
  }

  test('AI 분류 스텝 — 프롬프트 칸 필수, 출력 컬럼 별표는 aria-hidden', async ({ authenticatedPage: page }) => {
    await openNewEditorWithTypedStep(page, 'AI 분류');
    const prompt = page.getByRole('textbox', { name: '프롬프트', exact: true });
    await expectAriaRequired(prompt);
    // 별표는 모두 aria-hidden — 접근 이름·텍스트 노출에서 빠진다(출력 컬럼 정의 제목 포함)
    const heading = page.getByTestId('ai-output-columns-title');
    await expect(heading.locator('span[aria-hidden="true"]')).toHaveText('*');
    // 별표 대신 스크린리더 전용 「(필수)」 가 필수 뜻을 전한다
    await expect(heading.locator('.sr-only')).toHaveText('(필수)');
  });
});
