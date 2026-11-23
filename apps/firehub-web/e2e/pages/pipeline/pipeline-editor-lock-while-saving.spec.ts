/**
 * 파이프라인 편집 — 저장 중 편집 잠금 (WD-953)
 *
 * 결함: 저장 요청이 도는 동안에도 캔버스·설정 패널·이름 칸을 고칠 수 있었다. 저장은 누른 순간의 상태를 보내고
 * 응답 뒤 MARK_SAVED 가 그 사이 편집까지 저장됨으로 표시해, 재조회가 그 편집을 조용히 지웠다.
 * 이제 저장 중에는 기존 readOnly 경로로 캔버스·패널을, disabled 로 이름 칸을 잠근다.
 */

import type { UpdatePipelineRequest } from '@/types/pipeline';

import { createDataset, createDatasetDetail } from '../../factories/dataset.factory';
import { createPipelineDetail, createStep } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { holdApi, holdRequests } from '../../fixtures/hold-api';
import { grantPipelinePermissions } from '../../fixtures/pipeline.fixture';

const V1 = '2026-10-30T10:00:00.123456';
/** 저장 전 검사가 컬럼 조회(데이터셋 상세 GET)를 하게 만드는 API_CALL 스텝의 출력 데이터셋 id */
const OUTPUT_DATASET_ID = 7;

/** GET 계열 모킹(스텝 s1·s2·s3). PUT 은 putHandler 가 있으면 넘겨 처리한다. */
async function mockPipeline(
  page: import('@playwright/test').Page,
  onPut?: (route: import('@playwright/test').Route) => Promise<void>,
  /** true 면 s3 를 「필드 매핑 + 출력 데이터셋 지정」 API_CALL 스텝으로 둬 저장 전 검사가 컬럼 조회를 하게 한다 */
  withColumnLookup = false,
) {
  const steps = ['s1', 's2', 's3'].map((name, i) =>
    withColumnLookup && name === 's3'
      ? createStep({
          id: 11 + i,
          name,
          scriptType: 'API_CALL',
          scriptContent: undefined,
          stepOrder: i,
          outputDatasetId: OUTPUT_DATASET_ID,
          outputDatasetName: '출력',
          apiConfig: { customUrl: 'https://example.com/api', method: 'GET', fieldMappings: [{ sourceField: 'a', targetColumn: 'id' }] },
        })
      : createStep({ id: 11 + i, name, scriptType: 'SQL', scriptContent: 'SELECT 1', stepOrder: i }),
  );
  await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
  await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
  // 편집기 데이터셋 목록에 출력 데이터셋이 있어야 검증이 상세를 조회한다(없으면 조회 없이 건너뛴다)
  await mockApi(page, 'GET', '/api/v1/datasets', {
    content: withColumnLookup ? [createDataset({ id: OUTPUT_DATASET_ID })] : [],
    page: 0, size: 1000, totalElements: withColumnLookup ? 1 : 0, totalPages: withColumnLookup ? 1 : 0,
  });
  await mockApi(page, 'GET', `/api/v1/datasets/${OUTPUT_DATASET_ID}`, createDatasetDetail({ id: OUTPUT_DATASET_ID }));
  await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
  await page.route(
    (url) => url.pathname === '/api/v1/pipelines/1',
    async (route) => {
      const method = route.request().method();
      if (method === 'PUT' && onPut) return onPut(route);
      if (method !== 'GET') return route.fallback();
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(createPipelineDetail({ id: 1, updatedAt: V1, steps })),
      });
    },
  );
}

/** PUT 본문을 puts 에 쌓고 저장 성공으로 응답하는 핸들러 */
function savePutHandler(puts: UpdatePipelineRequest[]) {
  return async (route: import('@playwright/test').Route) => {
    puts.push(route.request().postDataJSON() as UpdatePipelineRequest);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ updatedAt: '2026-10-30T10:00:01.111111' }),
    });
  };
}

test.describe('파이프라인 편집 — 저장 중 편집 잠금 (WD-953)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  test('저장 요청이 도는 동안 캔버스·패널·이름 칸이 잠기고 누른 순간의 스텝이 저장된다 (WD-953)', async ({
    authenticatedPage: page,
  }) => {
    const puts: UpdatePipelineRequest[] = [];
    await mockPipeline(page, savePutHandler(puts));
    // PUT 을 시험이 풀어 줄 때까지 붙잡는다 — 상태 모킹 뒤에 건다
    const hold = await holdRequests(page, '/api/v1/pipelines/1', 'PUT');

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node', { hasText: 's2' }).click();
    await page.locator('#step-name').fill('s2-renamed');
    // 저장 전에 있었음을 먼저 보아 '없음' 단언이 헛통과하지 않게 한다
    await page.locator('.react-flow__node', { hasText: 's2' }).hover();
    await expect(page.locator('.react-flow__node').getByRole('button', { name: '스텝 삭제' }).first()).toBeVisible();
    // '스텝 추가'는 툴바 버튼과 노드의 「다음 스텝 추가」 여럿에 걸리므로 first() 로 하나만 본다
    await expect(page.getByRole('button', { name: '스텝 추가' }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: '스텝 삭제' }).filter({ hasText: '스텝 삭제' })).toBeVisible();
    await expect(page.locator('#step-name')).toBeEditable();
    await expect(page.getByLabel('파이프라인 이름')).toBeEnabled();
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await expect.poll(hold.count).toBe(1);

    // 붙잡힌 동안: 노드 X·스텝 추가·패널 삭제가 없고, 패널 이름 칸과 파이프라인 이름 칸이 잠겼다
    await expect(page.locator('.react-flow__node').getByRole('button', { name: '스텝 삭제' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '스텝 추가' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '스텝 삭제' }).filter({ hasText: '스텝 삭제' })).toHaveCount(0);
    // 패널이 사라져도 통과하지 않도록: 스텝이 선택된 채 패널이 떠 있고 이름 칸이 잠긴 채 편집한 값을 보여 준다
    await expect(page.locator('#step-name')).toBeDisabled();
    await expect(page.locator('#step-name')).toHaveValue('s2-renamed');
    await expect(page.getByLabel('파이프라인 이름')).toBeDisabled();

    hold.release();
    // 보기 모드로 돌아온 것을 먼저 기다린 뒤 확인한다
    await expect(page.getByRole('button', { name: '수정' })).toBeVisible();
    await expect(page.locator('.react-flow__node')).toHaveCount(3);
    await expect(page.getByText('미저장 변경사항')).toHaveCount(0);
    expect(puts).toHaveLength(1);
    expect(puts[0].steps.map((s) => s.name)).toEqual(['s1', 's2-renamed', 's3']);
  });

  test('저장이 실패하면 잠금이 풀려 다시 편집할 수 있다 (WD-953)', async ({ authenticatedPage: page }) => {
    await mockPipeline(page);
    const hold = await holdApi(page, 'PUT', '/api/v1/pipelines/1', 500, { status: 500, message: '서버 오류' });

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node', { hasText: 's2' }).click();
    await page.locator('#step-name').fill('s2-renamed');
    // 저장 전에 enabled 였음을 먼저 보아 'disabled' 단언이 헛통과하지 않게 한다
    await expect(page.getByLabel('파이프라인 이름')).toBeEnabled();
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await expect.poll(() => hold.requests.length).toBe(1);
    await expect(page.getByLabel('파이프라인 이름')).toBeDisabled();

    hold.release();
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeVisible();
    await expect(page.getByLabel('파이프라인 이름')).toBeEnabled();
    await expect(page.locator('#step-name')).toBeEditable();
    await expect(page.getByText('미저장 변경사항')).toBeVisible();

    // 잠금이 풀려 다시 스텝을 지울 수 있다
    const node = page.locator('.react-flow__node', { hasText: 's2-renamed' });
    await node.hover();
    await node.getByRole('button', { name: '스텝 삭제' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();
    await expect(page.locator('.react-flow__node')).toHaveCount(2);
  });

  test('저장 전 검사(컬럼 조회)가 도는 동안에도 편집이 잠긴다 (WD-953)', async ({ authenticatedPage: page }) => {
    const puts: UpdatePipelineRequest[] = [];
    await mockPipeline(
      page,
      savePutHandler(puts),
      true,
    );
    // 저장 전 검사의 출력 데이터셋 상세 조회를 붙잡는다 — PUT 은 아직 나가지 않은 구간
    const hold = await holdRequests(page, `/api/v1/datasets/${OUTPUT_DATASET_ID}`, 'GET');

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node', { hasText: 's2' }).click();
    await page.locator('#step-name').fill('s2-renamed');
    // 저장 전에는 편집이 열려 있었음을 먼저 본다
    await page.locator('.react-flow__node', { hasText: 's2' }).hover();
    await expect(page.locator('.react-flow__node').getByRole('button', { name: '스텝 삭제' }).first()).toBeVisible();
    await expect(page.locator('#step-name')).toBeEnabled();
    await expect(page.getByLabel('파이프라인 이름')).toBeEnabled();
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await expect.poll(hold.count).toBe(1);

    // 컬럼 조회가 붙잡힌 동안(요청 전): 편집이 잠겼다
    await expect(page.getByLabel('파이프라인 이름')).toBeDisabled();
    await expect(page.locator('#step-name')).toBeDisabled();
    await expect(page.locator('.react-flow__node').getByRole('button', { name: '스텝 삭제' })).toHaveCount(0);
    expect(puts).toHaveLength(0);

    hold.release();
    await expect(page.getByRole('button', { name: '수정' })).toBeVisible();
    expect(puts).toHaveLength(1);
    expect(puts[0].steps.map((st) => st.name)).toEqual(['s1', 's2-renamed', 's3']);
  });

  test('저장 전 검사가 도는 동안 저장 버튼을 두 번 눌러도 저장 요청은 1번이다 (WD-953)', async ({
    authenticatedPage: page,
  }) => {
    const puts: UpdatePipelineRequest[] = [];
    await mockPipeline(
      page,
      savePutHandler(puts),
      true,
    );
    const hold = await holdRequests(page, `/api/v1/datasets/${OUTPUT_DATASET_ID}`, 'GET');

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node', { hasText: 's2' }).click();
    await page.locator('#step-name').fill('s2-renamed');
    const save = page.getByRole('button', { name: '저장', exact: true });
    // 빠른 두 번 클릭 — 두 번째가 잠기기 전에 들어가는 경우와 이미 막힌 경우 모두 PUT 은 1번이어야 한다
    await save.dblclick({ force: true });
    await expect.poll(hold.count).toBeGreaterThanOrEqual(1);
    await expect(save).toBeDisabled();

    hold.release();
    await expect(page.getByRole('button', { name: '수정' })).toBeVisible();
    // 두 번째 저장이 늦게 나가 편집 충돌 창을 띄우는 일이 없다
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    expect(puts).toHaveLength(1);
  });

  test('키보드로 시작한 저장이 500 으로 실패해 잠금이 풀리면 저장 전 포커스가 돌아온다 (WD-953)', async ({
    authenticatedPage: page,
  }) => {
    await mockPipeline(page);
    const hold = await holdApi(page, 'PUT', '/api/v1/pipelines/1', 500, { status: 500, message: '서버 오류' });

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.getByLabel('파이프라인 이름').fill('renamed');
    // 실제 사용자 경로 — 이름 칸에서 Tab 으로 저장 버튼까지 가 Enter 로 저장한다
    const save = page.getByRole('button', { name: '저장', exact: true });
    for (let i = 0; i < 10 && !(await save.evaluate((el) => el === document.activeElement)); i++) {
      await page.keyboard.press('Tab');
    }
    await expect(save).toBeFocused();
    await page.keyboard.press('Enter');
    await expect.poll(() => hold.requests.length).toBe(1);
    await expect(page.getByLabel('파이프라인 이름')).toBeDisabled();

    hold.release();
    await expect(page.getByLabel('파이프라인 이름')).toBeEnabled();
    await expect(save).toBeFocused();
  });

  test('스텝 이름 칸에서 시작한 저장이 실패하면 포커스가 그 칸으로 돌아온다 (WD-953)', async ({
    authenticatedPage: page,
  }) => {
    await mockPipeline(page);
    const hold = await holdApi(page, 'PUT', '/api/v1/pipelines/1', 500, { status: 500, message: '서버 오류' });

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node', { hasText: 's2' }).click();
    await page.locator('#step-name').fill('s2-renamed');
    // 저장을 포커스를 옮기지 않는 방식으로 시작한다 — 이름 칸에 포커스가 있는 채 저장이 도는 경로
    await page.locator('#step-name').focus();
    await page.getByRole('button', { name: '저장', exact: true }).evaluate((el) => (el as HTMLButtonElement).click());
    await expect.poll(() => hold.requests.length).toBe(1);
    await expect(page.locator('#step-name')).toBeDisabled();

    hold.release();
    await expect(page.locator('#step-name')).toBeEnabled();
    await expect(page.locator('#step-name')).toBeFocused();
  });

  test('저장이 409 충돌로 거부돼 창을 닫으면 저장 전 포커스가 칸으로 돌아온다 (WD-953)', async ({
    authenticatedPage: page,
  }) => {
    await mockPipeline(page);
    const hold = await holdApi(page, 'PUT', '/api/v1/pipelines/1', 409, {
      status: 409,
      code: 'EDIT_CONFLICT',
      message: '다른 곳에서 변경되었습니다.',
    });

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.getByLabel('파이프라인 이름').fill('renamed');
    await page.getByLabel('파이프라인 이름').focus();
    await page.getByRole('button', { name: '저장', exact: true }).evaluate((el) => (el as HTMLButtonElement).click());
    await expect.poll(() => hold.requests.length).toBe(1);

    hold.release();
    // 충돌 창이 떠 있는 동안에는 포커스가 창 안에 있다(창 뒤 칸으로 빼앗지 않는다)
    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toBeVisible();
    await expect(page.getByLabel('파이프라인 이름')).toBeEnabled();
    await expect(page.getByLabel('파이프라인 이름')).not.toBeFocused();

    // 「계속 편집」으로 닫으면 저장 전에 있던 이름 칸으로 돌아온다
    await dialog.getByRole('button', { name: '계속 편집' }).click();
    await expect(dialog).toHaveCount(0);
    await expect(page.getByLabel('파이프라인 이름')).toBeFocused();
    // 창의 퇴장 애니메이션 뒤 Radix 가 포커스를 body 로 되돌려도 이름 칸에 남아 있어야 한다(WD-953 리뷰)
    await page.waitForTimeout(500);
    await expect(page.getByLabel('파이프라인 이름')).toBeFocused();
  });

  test('스크립트 편집기(CodeMirror)에서 시작한 저장이 실패하면 포커스가 편집기로 돌아온다 (WD-953)', async ({
    authenticatedPage: page,
  }) => {
    await mockPipeline(page);
    const hold = await holdApi(page, 'PUT', '/api/v1/pipelines/1', 500, { status: 500, message: '서버 오류' });

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node', { hasText: 's2' }).click();
    const editor = page.locator('.cm-content').first();
    await editor.click();
    await page.keyboard.type(' -- x');
    await expect(editor).toBeFocused();
    await page.getByRole('button', { name: '저장', exact: true }).evaluate((el) => (el as HTMLButtonElement).click());
    await expect.poll(() => hold.requests.length).toBe(1);
    await expect(page.locator('#step-name')).toBeDisabled();

    hold.release();
    await expect(page.locator('#step-name')).toBeEnabled();
    await expect(editor).toBeFocused();
  });
  test('저장 중 사용자가 포커스를 다른 곳으로 옮겼으면 실패해도 되돌리지 않는다 (WD-953)', async ({
    authenticatedPage: page,
  }) => {
    await mockPipeline(page);
    const hold = await holdApi(page, 'PUT', '/api/v1/pipelines/1', 500, { status: 500, message: '서버 오류' });

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.getByLabel('파이프라인 이름').fill('renamed');
    await page.getByLabel('파이프라인 이름').focus();
    await page.getByRole('button', { name: '저장', exact: true }).evaluate((el) => (el as HTMLButtonElement).click());
    await expect.poll(() => hold.requests.length).toBe(1);
    // 저장이 도는 동안 사용자가 잠기지 않는 뒤로 가기 링크로 포커스를 옮긴다
    const back = page.getByRole('link', { name: '파이프라인 목록으로' });
    await back.focus();
    await expect(back).toBeFocused();

    hold.release();
    await expect(page.getByLabel('파이프라인 이름')).toBeEnabled();
    await page.waitForTimeout(300);
    await expect(back).toBeFocused();
  });

  test('「저장 후 실행」의 저장이 실패하면 실행을 요청하지 않는다 (WD-953)', async ({ authenticatedPage: page }) => {
    await mockPipeline(page);
    const hold = await holdApi(page, 'PUT', '/api/v1/pipelines/1', 500, { status: 500, message: '서버 오류' });
    const executes: string[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/pipelines/1/execute',
      async (route) => {
        executes.push(route.request().method());
        await route.fulfill({ status: 500, contentType: 'application/json', body: '{}' });
      },
    );

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.getByLabel('파이프라인 이름').fill('renamed');
    await page.getByRole('button', { name: '실행', exact: true }).click();
    await page.getByRole('button', { name: '저장 후 실행' }).click();
    await expect.poll(() => hold.requests.length).toBe(1);

    hold.release();
    await expect(page.getByLabel('파이프라인 이름')).toBeEnabled();
    await page.waitForTimeout(500);
    expect(executes).toHaveLength(0);
  });

  test('저장 버튼을 누르면 포커스가 body 에 남는 브라우저(Safari·Firefox 맥)도 실패 뒤 저장 버튼으로 돌아온다 (WD-953)', async ({
    authenticatedPage: page,
  }) => {
    await mockPipeline(page);
    const hold = await holdApi(page, 'PUT', '/api/v1/pipelines/1', 500, { status: 500, message: '서버 오류' });

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.getByLabel('파이프라인 이름').fill('renamed');
    const save = page.getByRole('button', { name: '저장', exact: true });
    // 클릭이 포커스를 주지 않는 브라우저 흉내 — 포커스를 body 로 빼고 click() 만 부른다
    await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
    await save.evaluate((el) => (el as HTMLButtonElement).click());
    await expect.poll(() => hold.requests.length).toBe(1);

    hold.release();
    await expect(page.getByLabel('파이프라인 이름')).toBeEnabled();
    await expect(save).toBeFocused();
  });
});
