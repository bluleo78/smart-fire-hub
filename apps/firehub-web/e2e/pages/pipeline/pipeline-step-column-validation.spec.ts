/**
 * 파이프라인 스텝 출력 컬럼 저장 시점 검증 E2E (WD-154)
 *
 * 예전에는 API 호출 스텝의 빈 응답 필드 매핑 행, AI 분류 스텝의 빈 출력 컬럼 이름, 출력 데이터셋에 없는 대상 컬럼이
 * 모두 「파이프라인이 저장되었습니다」로 저장되고 실행 시점에야 영어 원문 오류로 실패했다.
 * 이제 편집기가 저장을 막고(POST/PUT 미전송) 문제 행 바로 아래에 한국어 안내를 띄운다.
 * 서버가 같은 규칙으로 거부한 경우(400)에는 스텝 이름이 담긴 서버 문구가 그대로 보인다.
 */

import type { Page } from '@playwright/test';

import { createColumn, createDataset, createDatasetDetail } from '../../factories/dataset.factory';
import { createPipelineDetail, createStep } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantPipelinePermissions } from '../../fixtures/pipeline.fixture';

const EMPTY_ROW = '비어 있는 매핑 행이 있습니다 — 채우거나 삭제하세요';
const TARGET_REQUIRED = '대상 컬럼 이름을 입력하세요';
const SOURCE_REQUIRED = '응답 필드를 입력하세요';
const OUTPUT_NAME_REQUIRED = '출력 컬럼 이름을 입력하세요';

/** 지정 출력 데이터셋 — 사용자 컬럼은 col1 하나 */
const OUTPUT_ID = 10;

/** 새 파이프라인 편집기에서 스텝 하나를 추가하고 스텝 타입을 고른다. */
async function openNewStep(page: Page, stepTypeLabel: string) {
  await mockApi(page, 'GET', '/api/v1/datasets', {
    content: [],
    page: 0,
    size: 1000,
    totalElements: 0,
    totalPages: 0,
  });
  await mockApi(page, 'GET', '/api/v1/api-connections/selectable', []);

  await page.goto('/pipelines/new');
  await page.getByPlaceholder(/파이프라인 이름|이름 입력/).first().fill('WD-154 출력 컬럼 검증');
  await page.getByRole('button', { name: /스텝 추가/ }).first().click();
  await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });
  await page.locator('#step-name').fill('출력 스텝');

  await page.getByRole('combobox').first().click();
  await page.getByRole('option', { name: stepTypeLabel, exact: true }).click();
}

/** API 호출 스텝의 URL 을 채우고 매핑 행을 count 개 추가한다(URL 빈값 검증과 섞이지 않게). */
async function fillUrlAndAddMappings(page: Page, count: number) {
  await expect(page.getByText('기본 설정')).toBeVisible({ timeout: 10000 });
  await page.getByPlaceholder('https://api.example.com/v1/data').fill('https://api.example.com/items');
  for (let i = 0; i < count; i++) {
    await page.getByRole('button', { name: /매핑 추가/ }).click();
  }
}

/**
 * 기존 파이프라인(API 호출 스텝 하나, 지정 출력 = col1 만 있는 데이터셋)을 모킹한다.
 * 저장된 매핑을 그대로 보여 주고, 수정 → 노드 선택까지 해 둔다.
 */
async function openExistingApiStep(page: Page, fieldMappings: Array<Record<string, string>>) {
  const step = createStep({
    id: 7,
    name: 'API 호출',
    scriptType: 'API_CALL',
    scriptContent: '',
    outputDatasetId: OUTPUT_ID,
    outputDatasetName: 'API 출력',
    apiConfig: {
      customUrl: 'https://api.example.com/items',
      method: 'GET',
      dataPath: '$',
      fieldMappings,
    },
  });
  await mockApi(page, 'GET', '/api/v1/pipelines/1', createPipelineDetail({ id: 1, steps: [step] }));
  await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', []);
  await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
  await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
  await mockApi(page, 'GET', '/api/v1/api-connections/selectable', []);
  await mockApi(page, 'GET', '/api/v1/datasets', {
    content: [createDataset({ id: OUTPUT_ID, name: 'API 출력', tableName: 'api_out' })],
    page: 0,
    size: 100,
    totalElements: 1,
    totalPages: 1,
  });
  await mockApi(
    page,
    'GET',
    `/api/v1/datasets/${OUTPUT_ID}`,
    createDatasetDetail({
      id: OUTPUT_ID,
      name: 'API 출력',
      tableName: 'api_out',
      columns: [createColumn({ id: 1, columnName: 'col1', dataType: 'TEXT', columnOrder: 0 })],
    }),
  );
  const putCapture = await mockApi(page, 'PUT', '/api/v1/pipelines/1', {}, { capture: true });

  await page.goto('/pipelines/1');
  await page.getByRole('button', { name: '수정' }).click();
  await page.locator('.react-flow__node').first().click();
  await expect(page.getByText('기본 설정')).toBeVisible({ timeout: 10000 });
  return putCapture;
}

test.describe('스텝 출력 컬럼 저장 검증 (WD-154)', () => {
  // 실행·수정·트리거 편집 버튼은 권한이 있을 때만 보인다(WD-219) — 기본 인증 fixture 는 권한이 비어 있어 모든 쓰기 권한을 준다.
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  test('빈 매핑 행은 저장을 막고 그 행 아래에 안내한다 — 행을 지우면 저장된다', async ({
    authenticatedPage: page,
  }) => {
    const createCapture = await mockApi(
      page,
      'POST',
      '/api/v1/pipelines',
      createPipelineDetail({ id: 99, steps: [] }),
      { capture: true },
    );
    await openNewStep(page, 'API 호출');
    await fillUrlAndAddMappings(page, 2);
    await page.getByLabel('응답 필드 1').fill('name');
    await page.getByLabel('대상 컬럼 1').fill('name');
    // 2행은 비워 둔다 — 예전에는 그대로 저장됐다

    await page.getByRole('button', { name: '저장', exact: true }).click();

    await expect(page.getByText('입력 오류를 확인하세요.')).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: EMPTY_ROW })).toBeVisible();
    // 빈 행의 두 칸 모두 오류 표시, 정상 행은 오류 없음
    await expect(page.getByLabel('응답 필드 2')).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByLabel('대상 컬럼 2')).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByLabel('대상 컬럼 1')).toHaveAttribute('aria-invalid', 'false');
    await page.waitForTimeout(300);
    expect(createCapture.requests).toHaveLength(0);

    // 빈 행을 지우면(행 삭제 버튼 = 2행의 휴지통) 오류가 사라지고 저장 요청이 실제로 나간다
    const secondRow = page.getByLabel('대상 컬럼 2').locator('xpath=ancestor::div[contains(@class,"grid")][1]');
    await secondRow.getByRole('button').click();
    await expect(page.getByText(EMPTY_ROW)).toHaveCount(0);
    await page.getByRole('button', { name: '저장', exact: true }).click();
    const req = await createCapture.waitForRequest();
    const payload = req.payload as {
      steps: Array<{ apiConfig: { fieldMappings: Array<{ sourceField: string; targetColumn: string }> } }>;
    };
    expect(payload.steps[0].apiConfig.fieldMappings).toEqual([{ sourceField: 'name', targetColumn: 'name' }]);
  });

  test('한쪽만 채운 매핑 행은 비어 있는 칸을 알린다', async ({ authenticatedPage: page }) => {
    const createCapture = await mockApi(page, 'POST', '/api/v1/pipelines', {}, { capture: true });
    await openNewStep(page, 'API 호출');
    await fillUrlAndAddMappings(page, 2);
    await page.getByLabel('응답 필드 1').fill('name');
    await page.getByLabel('대상 컬럼 2').fill('amount');

    await page.getByRole('button', { name: '저장', exact: true }).click();

    await expect(page.getByRole('alert').filter({ hasText: TARGET_REQUIRED })).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: SOURCE_REQUIRED })).toBeVisible();
    await expect(page.getByLabel('대상 컬럼 1')).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByLabel('응답 필드 1')).toHaveAttribute('aria-invalid', 'false');
    await expect(page.getByLabel('응답 필드 2')).toHaveAttribute('aria-invalid', 'true');
    await page.waitForTimeout(300);
    expect(createCapture.requests).toHaveLength(0);
  });

  test('대상 컬럼 이름 형식 위반은 규칙을 담아 알린다', async ({ authenticatedPage: page }) => {
    const createCapture = await mockApi(page, 'POST', '/api/v1/pipelines', {}, { capture: true });
    await openNewStep(page, 'API 호출');
    await fillUrlAndAddMappings(page, 1);
    await page.getByLabel('응답 필드 1').fill('Name');
    await page.getByLabel('대상 컬럼 1').fill('Bad Col');

    await page.getByRole('button', { name: '저장', exact: true }).click();

    await expect(
      page.getByText('컬럼 이름은 영문 소문자로 시작하고 영문 소문자·숫자·밑줄만 쓸 수 있습니다: Bad Col'),
    ).toBeVisible();
    await page.waitForTimeout(300);
    expect(createCapture.requests).toHaveLength(0);
  });

  test('AI 분류 출력 컬럼 이름이 비어 있으면 저장을 막고 그 행 아래에 안내한다', async ({
    authenticatedPage: page,
  }) => {
    const createCapture = await mockApi(page, 'POST', '/api/v1/pipelines', {}, { capture: true });
    await openNewStep(page, 'AI 분류');
    await page.getByPlaceholder('입력 데이터의 어떤 컬럼을 어떻게 처리할지 설명하세요').fill('감성을 분류하세요');
    await page.getByRole('button', { name: '컬럼 추가', exact: true }).click();
    await page.getByRole('button', { name: '컬럼 추가', exact: true }).click();
    await page.getByLabel('출력 컬럼명 1').fill('label');
    // 2행 이름은 비워 둔다

    await page.getByRole('button', { name: '저장', exact: true }).click();

    await expect(page.getByRole('alert').filter({ hasText: OUTPUT_NAME_REQUIRED })).toBeVisible();
    await expect(page.getByLabel('출력 컬럼명 2')).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByLabel('출력 컬럼명 1')).toHaveAttribute('aria-invalid', 'false');
    await page.waitForTimeout(300);
    expect(createCapture.requests).toHaveLength(0);
  });

  test('출력 데이터셋에 없는 대상 컬럼은 저장을 막고 컬럼 이름을 알린다', async ({
    authenticatedPage: page,
  }) => {
    const putCapture = await openExistingApiStep(page, [
      { sourceField: 'a', targetColumn: 'col1' },
      { sourceField: 'b', targetColumn: 'no_such_col' },
    ]);
    // 설명만 바꿔 저장해도(다른 필드 편집) 같은 검증을 거친다
    await page.locator('#step-description').fill('설명 변경');

    await page.getByRole('button', { name: '저장', exact: true }).click();

    await expect(
      page.getByRole('alert').filter({ hasText: '출력 데이터셋에 없는 컬럼입니다: no_such_col' }),
    ).toBeVisible();
    await expect(page.getByLabel('대상 컬럼 2')).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByLabel('대상 컬럼 1')).toHaveAttribute('aria-invalid', 'false');
    await page.waitForTimeout(300);
    expect(putCapture.requests).toHaveLength(0);
  });

  test('빈 매핑 행이 저장돼 있던 기존 파이프라인은 열 때 오류 없이 보이고, 저장할 때만 막힌다', async ({
    authenticatedPage: page,
  }) => {
    const putCapture = await openExistingApiStep(page, [
      { sourceField: 'a', targetColumn: 'col1' },
      { sourceField: '', targetColumn: '' },
    ]);

    // 열기·보기는 깨지지 않는다 — 저장된 행이 그대로 보이고 오류 문구는 없다
    await expect(page.getByLabel('대상 컬럼 1')).toHaveValue('col1');
    await expect(page.getByLabel('대상 컬럼 2')).toHaveValue('');
    await expect(page.getByText(EMPTY_ROW)).toHaveCount(0);

    // 다른 필드(설명)만 고쳐 저장해도 빈 행 때문에 막힌다 — 채우거나 지워야 한다
    await page.locator('#step-description').fill('설명 변경');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await expect(page.getByRole('alert').filter({ hasText: EMPTY_ROW })).toBeVisible();
    await page.waitForTimeout(300);
    expect(putCapture.requests).toHaveLength(0);
  });

  test('서버가 거부하면(400) 스텝 이름이 담긴 서버 문구를 그대로 보인다', async ({
    authenticatedPage: page,
  }) => {
    // 편집기 검사를 통과했지만 서버가 같은 규칙으로 거부한 경우(예: 화면이 출력 컬럼을 확인하지 못한 경우)
    const serverMessage = "스텝 '출력 스텝': 출력 데이터셋에 없는 컬럼입니다: amount";
    await mockApi(
      page,
      'POST',
      '/api/v1/pipelines',
      { status: 400, error: 'Bad Request', message: serverMessage },
      { status: 400 },
    );
    await openNewStep(page, 'API 호출');
    await fillUrlAndAddMappings(page, 1);
    await page.getByLabel('응답 필드 1').fill('amount');
    await page.getByLabel('대상 컬럼 1').fill('amount');

    await page.getByRole('button', { name: '저장', exact: true }).click();

    await expect(page.getByText(serverMessage)).toBeVisible();
    await expect(page.getByText('파이프라인이 생성되었습니다.')).toHaveCount(0);
  });
  test('입력 데이터셋에서 지워진 AI 분류 입력 컬럼은 "데이터셋에 없음"으로 보이고, 체크를 해제하면 빠진 채로 저장된다 (WD-215)', async ({
    authenticatedPage: page,
  }) => {
    // 저장 뒤 입력 데이터셋에서 gone_col 이 지워진 상황 — 예전엔 목록에 안 보여 해제할 수 없었고(개수만 셈) 저장이 계속 400 이었다.
    const INPUT_ID = 20;
    const step = createStep({
      id: 7,
      name: 'AI 분류',
      scriptType: 'AI_CLASSIFY',
      scriptContent: '',
      outputDatasetId: OUTPUT_ID,
      outputDatasetName: 'API 출력',
      inputDatasetIds: [INPUT_ID],
      aiConfig: {
        prompt: '분류',
        outputColumns: [{ name: 'label', type: 'TEXT' }],
        inputColumns: ['col1', 'gone_col', 'id'],
      },
    });
    await mockApi(page, 'GET', '/api/v1/pipelines/1', createPipelineDetail({ id: 1, steps: [step] }));
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
    await mockApi(page, 'GET', '/api/v1/api-connections/selectable', []);
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [
        createDataset({ id: OUTPUT_ID, name: 'API 출력', tableName: 'api_out' }),
        createDataset({ id: INPUT_ID, name: '분류 입력', tableName: 'cls_in' }),
      ],
      page: 0,
      size: 100,
      totalElements: 2,
      totalPages: 1,
    });
    for (const [id, name, tableName] of [
      [OUTPUT_ID, 'API 출력', 'api_out'],
      [INPUT_ID, '분류 입력', 'cls_in'],
    ] as const) {
      await mockApi(
        page,
        'GET',
        `/api/v1/datasets/${id}`,
        createDatasetDetail({
          id,
          name,
          tableName,
          columns: [createColumn({ id: 1, columnName: 'col1', dataType: 'TEXT', columnOrder: 0 })],
        }),
      );
    }
    const putCapture = await mockApi(page, 'PUT', '/api/v1/pipelines/1', {}, { capture: true });

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node').first().click();
    await page.getByRole('button', { name: /입력 컬럼/ }).click();

    // 지워진 칸은 체크된 채 "데이터셋에 없음"으로 보이고, 시스템 컬럼 id 는 경고하지 않는다
    const stale = page.getByRole('checkbox', { name: /gone_col/ });
    await expect(stale).toBeChecked();
    await expect(page.getByText('(데이터셋에 없음 — 체크를 해제하세요)')).toHaveCount(1);
    await expect(page.getByRole('checkbox', { name: /^col1/ })).toBeChecked();

    await stale.click();
    await expect(page.getByRole('checkbox', { name: /gone_col/ })).toHaveCount(0);

    await page.getByRole('button', { name: '저장', exact: true }).click();
    const req = await putCapture.waitForRequest();
    const payload = req.payload as { steps: Array<{ aiConfig: { inputColumns: string[] } }> };
    expect(payload.steps[0].aiConfig.inputColumns).toEqual(['col1', 'id']);
  });
});
