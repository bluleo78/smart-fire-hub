import type { Page } from '@playwright/test';

import { createCategories, createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { DATASET_WRITE_PERMISSIONS, grantDatasetPermissions } from '../../fixtures/dataset.fixture';

// 쓰기 진입점은 서버 권한이 있을 때만 보인다(WD-139) — 이 파일은 편집·적재 흐름을 검증하므로 데이터셋 쓰기 권한을 준다.
// 개별 테스트가 다른 권한을 주면 그쪽이 나중에 등록돼 이긴다.
test.beforeEach(async ({ authenticatedPage: page }) => {
  await grantDatasetPermissions(page);
});

/**
 * API 가져오기 마법사 — 기본 파이프라인 이름 E2E (WD-31④)
 *
 * 파이프라인·트리거 목록은 데이터셋 보안 등급 판정을 거치지 않는다. 기본 이름에 데이터셋 이름을 넣으면
 * 그 데이터셋을 볼 수 없는 사용자에게 이름이 새어 나가므로, 기본값은 데이터셋 id 만 쓴다(`API Import #<id>`).
 */
test.describe('데이터셋 상세 — API 가져오기 기본 이름 (WD-31④)', () => {
  /** 데이터셋 이름이 요청 본문에 새는지 확인하려고 눈에 띄는 이름을 쓴다 */
  const datasetDetail = createDatasetDetail({
    id: 1,
    name: '인사 급여',
    rowCount: 0,
    columns: [
      createColumn({ id: 1, columnName: 'id', displayName: 'ID', dataType: 'INTEGER', isPrimaryKey: true }),
      createColumn({ id: 2, columnName: 'name', displayName: '이름', dataType: 'TEXT', columnOrder: 1 }),
    ],
  });

  /** 데이터 탭 진입에 필요한 모킹 — dataset-data-tab.spec.ts 의 공통 모킹을 줄여 옮겼다 */
  async function setupDataTabMocks(page: Page) {
    await mockApi(page, 'GET', '/api/v1/datasets/1', datasetDetail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/1/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/1/stats', []);
    await mockApi(page, 'GET', '/api/v1/datasets/1/data', {
      columns: datasetDetail.columns,
      rows: [],
      page: 0,
      size: 50,
      totalElements: 0,
      totalPages: 0,
    });
  }

  test('기본 파이프라인 이름은 데이터셋 id 만 쓰고 요청에 데이터셋 이름이 없다', async ({
    authenticatedPage: page,
  }) => {
    await setupDataTabMocks(page);
    const create = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/1/api-import',
      { pipelineId: 11, triggerId: null, executionId: null },
      { capture: true },
    );

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '인사 급여' })).toBeVisible();
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: 'API 가져오기' }).click();

    const dialog = page.getByRole('dialog');
    // 1단계: URL — 2단계: 매핑 1개(0개면 다음으로 못 간다) — 3단계: 미리보기 — 4단계: 실행 옵션
    await dialog.getByPlaceholder('https://api.example.com/v1/data').fill('https://api.example.com/v1/data');
    await dialog.getByRole('button', { name: '다음' }).click();
    await dialog.getByRole('button', { name: '매핑 추가' }).click();
    await dialog.getByRole('button', { name: '다음' }).click();
    await dialog.getByRole('button', { name: '다음' }).click();

    const nameInput = dialog.getByLabel('파이프라인 이름');
    await expect(nameInput).toHaveValue('API Import #1');
    await expect(nameInput).toHaveAttribute('placeholder', 'API Import #1');

    // 이름을 지워도 같은 기본값으로 보낸다
    await nameInput.fill('');
    await dialog.getByRole('button', { name: '완료' }).click();
    const req = await create.waitForRequest();
    expect(req.payload).toMatchObject({ pipelineName: 'API Import #1' });
    expect(JSON.stringify(req.payload)).not.toContain('인사 급여');
  });

  // 회귀 가드(WD-375): 창이 기본 좌우 1rem 여백을 덮어써 390 폭에서 화면 끝에 붙었고,
  // 단계 이름 4개가 한 줄에 눌려 「API 설/정」처럼 글자 중간에서 두 줄로 끊겼다.
  test('모바일(390)에서 창에 좌우 여백이 있고 단계 이름이 한 줄로 보인다 (WD-375)', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await setupDataTabMocks(page);
    await page.goto('/data/datasets/1');
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: 'API 가져오기' }).click();

    const dialog = page.getByRole('dialog');
    const box = (await dialog.boundingBox())!;
    expect(box.x).toBeGreaterThanOrEqual(15);
    expect(390 - (box.x + box.width)).toBeGreaterThanOrEqual(15);

    // 지금 단계 이름만 한 줄(약 16px)로 보이고, 나머지 단계 이름은 숨는다
    const current = dialog.getByText('API 설정', { exact: true });
    await expect(current).toBeVisible();
    expect((await current.boundingBox())!.height).toBeLessThan(24);
    await expect(dialog.getByText('응답 매핑', { exact: true })).toBeHidden();

    // 다음 단계로 가면 그 단계 이름이 보인다
    await dialog.getByPlaceholder('https://api.example.com/v1/data').fill('https://api.example.com/v1/data');
    await dialog.getByRole('button', { name: '다음' }).click();
    await expect(dialog.getByText('응답 매핑', { exact: true })).toBeVisible();
    await expect(dialog.getByText('API 설정', { exact: true })).toBeHidden();
  });

  // 데스크톱 폭은 그대로(기본 sm:max-w-lg = 512px)이고 단계 이름 4개가 모두 보인다(WD-375).
  test('데스크톱(1280)에서는 창 폭이 512px 이고 단계 이름이 모두 보인다 (WD-375)', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await setupDataTabMocks(page);
    await page.goto('/data/datasets/1');
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: 'API 가져오기' }).click();

    const dialog = page.getByRole('dialog');
    expect(Math.round((await dialog.boundingBox())!.width)).toBe(512);
    for (const label of ['API 설정', '응답 매핑', '미리보기', '실행 옵션']) {
      await expect(dialog.getByText(label, { exact: true })).toBeVisible();
    }
  });

  /**
   * WD-154 — 서버가 매핑을 저장 시점에 거부하면(400) 고정 문구 대신 서버 문구(스텝 이름·이유)를 보인다.
   * 마법사는 빈 매핑 행을 막지 않으므로, 예전에는 생성에 성공한 뒤 실행 시점에야 영어 원문 오류로 실패했다.
   */
  test('서버가 매핑을 거부하면 그 이유를 그대로 보인다', async ({ authenticatedPage: page }) => {
    await setupDataTabMocks(page);
    const serverMessage = "스텝 'API Data Collection': 비어 있는 매핑 행이 있습니다 — 채우거나 삭제하세요";
    await mockApi(
      page,
      'POST',
      '/api/v1/datasets/1/api-import',
      { status: 400, error: 'Bad Request', message: serverMessage },
      { status: 400 },
    );

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '인사 급여' })).toBeVisible();
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: 'API 가져오기' }).click();

    const dialog = page.getByRole('dialog');
    await dialog.getByPlaceholder('https://api.example.com/v1/data').fill('https://api.example.com/v1/data');
    await dialog.getByRole('button', { name: '다음' }).click();
    await dialog.getByRole('button', { name: '매핑 추가' }).click();
    await dialog.getByRole('button', { name: '다음' }).click();
    await dialog.getByRole('button', { name: '다음' }).click();
    await dialog.getByRole('button', { name: '완료' }).click();

    await expect(page.getByText(serverMessage)).toBeVisible();
    await expect(page.getByText('API 임포트 생성에 실패했습니다.')).toHaveCount(0);
  });

  /** 실행 옵션(4단계)까지 마법사를 진행한다 */
  async function openOptionsStep(page: Page) {
    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '인사 급여' })).toBeVisible();
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: 'API 가져오기' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByPlaceholder('https://api.example.com/v1/data').fill('https://api.example.com/v1/data');
    await dialog.getByRole('button', { name: '다음' }).click();
    await dialog.getByRole('button', { name: '매핑 추가' }).click();
    await dialog.getByRole('button', { name: '다음' }).click();
    await dialog.getByRole('button', { name: '다음' }).click();
    await expect(dialog.getByLabel('파이프라인 이름')).toBeVisible();
    return dialog;
  }

  /**
   * WD-251 — 서버는 즉시 실행에 pipeline:execute, 예약에 trigger:write 를 요구한다(직접 실행·트리거 생성과 같은 권한).
   * 파이프라인 작성 권한만 있는 사용자에게는 두 옵션을 숨기고, 기본값(즉시 실행)이 켜져 있어도 요청에 싣지 않는다 — 실으면 완료가 403 이 된다.
   */
  test('실행·트리거 권한이 없으면 즉시 실행·스케줄 옵션을 숨기고 요청에도 싣지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupDataTabMocks(page);
    const create = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/1/api-import',
      { pipelineId: 11, triggerId: null, executionId: null },
      { capture: true },
    );

    const dialog = await openOptionsStep(page);
    await expect(dialog.getByLabel('생성 후 즉시 실행')).toHaveCount(0);
    await expect(dialog.getByLabel('스케줄 설정 (선택)')).toHaveCount(0);

    await dialog.getByRole('button', { name: '완료' }).click();
    const req = await create.waitForRequest();
    expect(req.payload).toMatchObject({ executeImmediately: false, schedule: null });
  });

  test('실행·트리거 권한이 있으면 두 옵션이 보이고 기본값대로 즉시 실행을 보낸다', async ({
    authenticatedPage: page,
  }) => {
    await grantDatasetPermissions(page, [...DATASET_WRITE_PERMISSIONS, 'pipeline:execute', 'trigger:write']);
    await setupDataTabMocks(page);
    const create = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/1/api-import',
      { pipelineId: 11, triggerId: 5, executionId: 7 },
      { capture: true },
    );

    const dialog = await openOptionsStep(page);
    await expect(dialog.getByLabel('생성 후 즉시 실행')).toBeChecked();
    await dialog.getByLabel('스케줄 설정 (선택)').check();

    await dialog.getByRole('button', { name: '완료' }).click();
    const req = await create.waitForRequest();
    expect(req.payload).toMatchObject({ executeImmediately: true, schedule: { cronExpression: '0 0 * * *' } });
  });
});
