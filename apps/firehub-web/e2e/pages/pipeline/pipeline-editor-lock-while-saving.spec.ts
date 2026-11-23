/**
 * 파이프라인 편집 — 저장 중 편집 잠금 (WD-953)
 *
 * 결함: 저장 요청이 도는 동안에도 캔버스·설정 패널·이름 칸을 고칠 수 있었다. 저장은 누른 순간의 상태를 보내고
 * 응답 뒤 MARK_SAVED 가 그 사이 편집까지 저장됨으로 표시해, 재조회가 그 편집을 조용히 지웠다.
 * 이제 저장 중에는 기존 readOnly 경로로 캔버스·패널을, disabled 로 이름 칸을 잠근다.
 */

import type { Page, Route } from '@playwright/test';

import type { UpdatePipelineRequest } from '@/types/pipeline';

import { createDataset, createDatasetDetail } from '../../factories/dataset.factory';
import { createPipelineDetail, createStep } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { waitTwoFrames } from '../../fixtures/frames';
import { holdApi, holdRequests } from '../../fixtures/hold-api';
import { tabUntilFocused } from '../../fixtures/keyboard-focus';
import { grantPipelinePermissions } from '../../fixtures/pipeline.fixture';

const V1 = '2026-10-30T10:00:00.123456';
/** 저장 전 검사가 컬럼 조회(데이터셋 상세 GET)를 하게 만드는 API_CALL 스텝의 출력 데이터셋 id */
const OUTPUT_DATASET_ID = 7;

/** GET 계열 모킹(스텝 s1·s2·s3). PUT 은 putHandler 가 있으면 넘겨 처리한다. */
async function mockPipeline(
  page: Page,
  onPut?: (route: Route) => Promise<void>,
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
  return async (route: Route) => {
    puts.push(route.request().postDataJSON() as UpdatePipelineRequest);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ updatedAt: '2026-10-30T10:00:01.111111' }),
    });
  };
}

/**
 * 저장 버튼. includeHidden: 충돌 창(모달)이 뜨면 뒤 화면이 aria-hidden 이 되므로 창이 떠 있는 동안에도 같은 버튼을 가리키게 한다
 */
function saveButton(page: Page) {
  return page.getByRole('button', { name: '저장', exact: true, includeHidden: true });
}

/** 이름 칸에서 key(기본 Tab)로 저장 버튼까지 가 Enter 로 저장한다 — 실제 키보드 경로(저장 버튼이 포커스를 가진 채 저장이 시작된다) */
async function saveByKeyboard(page: Page, key = 'Tab', maxPresses = 10) {
  const save = saveButton(page);
  await tabUntilFocused(page, save, maxPresses, key);
  await page.keyboard.press('Enter');
  return save;
}

/**
 * 첫 PUT 은 409 충돌, 이후 PUT 은 overwriteStatus 로 응답한다. count 는 지금까지 들어온 PUT 수
 */
function conflictThen(overwriteStatus: number) {
  let n = 0;
  const handler = async (route: Route) => {
    n += 1;
    const status = n === 1 ? 409 : overwriteStatus;
    const body =
      n === 1
        ? { status, code: 'EDIT_CONFLICT', message: '다른 곳에서 변경되었습니다.' }
        : status === 200
          ? { updatedAt: '2026-10-30T10:00:01.111111' }
          : { status, message: '서버 오류' };
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
  };
  return { handler, count: () => n };
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
    const stepName = page.locator('#step-name');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node', { hasText: 's2' }).click();
    await stepName.fill('s2-renamed');
    // 저장 전에 있었음을 먼저 보아 '없음' 단언이 헛통과하지 않게 한다
    await page.locator('.react-flow__node', { hasText: 's2' }).hover();
    await expect(page.locator('.react-flow__node').getByRole('button', { name: '스텝 삭제' }).first()).toBeVisible();
    // '스텝 추가'는 툴바 버튼과 노드의 「다음 스텝 추가」 여럿에 걸리므로 first() 로 하나만 본다
    await expect(page.getByRole('button', { name: '스텝 추가' }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: '스텝 삭제' }).filter({ hasText: '스텝 삭제' })).toBeVisible();
    await expect(stepName).toBeEditable();
    await expect(page.getByLabel('파이프라인 이름')).toBeEnabled();
    await saveButton(page).click();
    await expect.poll(hold.count).toBe(1);

    // 붙잡힌 동안: 노드 X·스텝 추가·패널 삭제가 없고, 패널 이름 칸과 파이프라인 이름 칸이 잠겼다
    await expect(page.locator('.react-flow__node').getByRole('button', { name: '스텝 삭제' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '스텝 추가' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '스텝 삭제' }).filter({ hasText: '스텝 삭제' })).toHaveCount(0);
    // 패널이 사라져도 통과하지 않도록: 스텝이 선택된 채 패널이 떠 있고 이름 칸이 잠긴 채 편집한 값을 보여 준다
    await expect(stepName).toBeDisabled();
    await expect(stepName).toHaveValue('s2-renamed');
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
    const nameInput = page.getByLabel('파이프라인 이름');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node', { hasText: 's2' }).click();
    await page.locator('#step-name').fill('s2-renamed');
    // 저장 전에 enabled 였음을 먼저 보아 'disabled' 단언이 헛통과하지 않게 한다
    await expect(nameInput).toBeEnabled();
    await saveButton(page).click();
    await expect.poll(() => hold.requests.length).toBe(1);
    await expect(nameInput).toBeDisabled();

    hold.release();
    await expect(saveButton(page)).toBeVisible();
    await expect(nameInput).toBeEnabled();
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
    const stepName = page.locator('#step-name');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node', { hasText: 's2' }).click();
    await stepName.fill('s2-renamed');
    // 저장 전에는 편집이 열려 있었음을 먼저 본다
    await page.locator('.react-flow__node', { hasText: 's2' }).hover();
    await expect(page.locator('.react-flow__node').getByRole('button', { name: '스텝 삭제' }).first()).toBeVisible();
    await expect(stepName).toBeEnabled();
    await expect(page.getByLabel('파이프라인 이름')).toBeEnabled();
    await saveButton(page).click();
    await expect.poll(hold.count).toBe(1);

    // 컬럼 조회가 붙잡힌 동안(요청 전): 편집이 잠겼다
    await expect(page.getByLabel('파이프라인 이름')).toBeDisabled();
    await expect(stepName).toBeDisabled();
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
    const save = saveButton(page);
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
    const nameInput = page.getByLabel('파이프라인 이름');
    await page.getByRole('button', { name: '수정' }).click();
    await nameInput.fill('renamed');
    // 실제 사용자 경로 — 이름 칸에서 Tab 으로 저장 버튼까지 가 Enter 로 저장한다
    const save = await saveByKeyboard(page);
    await expect.poll(() => hold.requests.length).toBe(1);
    await expect(nameInput).toBeDisabled();

    hold.release();
    await expect(nameInput).toBeEnabled();
    await expect(save).toBeFocused();
  });

  test('스텝 패널을 연 채 키보드로 시작한 저장이 실패하면 포커스가 저장 버튼으로 돌아온다 (WD-953)', async ({
    authenticatedPage: page,
  }) => {
    await mockPipeline(page);
    const hold = await holdApi(page, 'PUT', '/api/v1/pipelines/1', 500, { status: 500, message: '서버 오류' });

    await page.goto('/pipelines/1');
    const stepName = page.locator('#step-name');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node', { hasText: 's2' }).click();
    await stepName.fill('s2-renamed');
    // 실제 키보드 조작 — 스텝 이름 칸에서 Shift+Tab 으로 저장 버튼까지 거슬러 가 Enter 로 저장한다.
    // 저장 단축키가 없어 칸에 포커스가 남은 채 저장을 시작할 실제 경로는 없다(클릭·Enter 모두 저장 버튼이 포커스를 가진다).
    // 따라서 저장 전 포커스는 저장 버튼이며, 스텝 패널이 열려 잠금(#step-name disabled)이 걸린 상태에서도 그리로 돌아와야 한다.
    // 칸에 포커스가 있는 채 저장이 도는 경로 자체는 Safari 흉내 시험(포커스 body)과 사용자 포커스 이동 시험이 맡는다
    await stepName.focus();
    const save = await saveByKeyboard(page, 'Shift+Tab', 60);
    await expect.poll(() => hold.requests.length).toBe(1);
    await expect(stepName).toBeDisabled();

    hold.release();
    await expect(stepName).toBeEnabled();
    await expect(save).toBeFocused();
  });

  test('저장 시작 때 포커스가 body 였던 저장이 409 충돌로 거부돼 창을 닫으면 복원 코드가 저장 버튼으로 옮긴다 (WD-953, WD-992)', async ({
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
    // 클릭이 포커스를 주지 않는 브라우저(Safari·Firefox 맥) 흉내 — 포커스를 body 로 빼고 click() 만 부른다.
    // 이 경로에서는 충돌 창이 열릴 때 Radix 가 기억하는 요소가 body 라, 복원 코드가 없으면 창을 닫아도 body 에 머문다(WD-992)
    const save = saveButton(page);
    await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
    await save.evaluate((el) => (el as HTMLButtonElement).click());
    await expect.poll(() => hold.requests.length).toBe(1);

    hold.release();
    // 충돌 창이 떠 있는 동안에는 포커스가 창 안에 있다(창 뒤 칸으로 빼앗지 않는다)
    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toBeVisible();
    await expect(save).toBeEnabled();
    await expect(save).not.toBeFocused();

    // 창이 열릴 때 Radix 가 기억한 요소는 잠금으로 포커스를 잃은 body 다 — 기본 복귀만으로는 body 로 가므로,
    // 저장 버튼으로 돌아온다면 handleConflictCloseAutoFocus 의 복원 덕이다(WD-992: 복원 코드를 빼면 이 단언이 실패한다)
    await dialog.getByRole('button', { name: '계속 편집' }).click();
    await expect(dialog).toHaveCount(0);
    await expect(save).toBeFocused();
    // 퇴장 애니메이션 뒤 Radix 의 늦은 포커스 복귀까지 덮도록 두 프레임을 기다린 뒤 다시 본다(고정 시간 대기 아님)
    await waitTwoFrames(page);
    await expect(save).toBeFocused();
  });

  test('저장이 연속 두 번 실패해도 매번 저장 전 포커스(저장 버튼)로 돌아온다 (WD-992)', async ({
    authenticatedPage: page,
  }) => {
    // PUT 마다 별도 관문을 둬 두 번째 요청도 붙잡는다 — 풀기 전에 잠금이 실제로 걸렸는지 단언하기 위함
    const gates: (() => void)[] = [];
    await mockPipeline(page, async (route) => {
      const idx = gates.length;
      await new Promise<void>((resolve) => {
        gates[idx] = resolve;
      });
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ status: 500, message: '서버 오류' }),
      });
    });

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    const nameInput = page.getByLabel('파이프라인 이름');
    await nameInput.fill('renamed');
    const save = await saveByKeyboard(page);
    await expect.poll(() => gates.length).toBe(1);
    await expect(nameInput).toBeDisabled();
    gates[0]();
    await expect(nameInput).toBeEnabled();
    await expect(save).toBeFocused();

    // 복원 직후 곧바로 다시 저장 — 이전 복원 대기가 다음 저장에 영향을 주지 않아야 한다
    await page.keyboard.press('Enter');
    await expect.poll(() => gates.length).toBe(2);
    // 두 번째 저장도 잠금이 실제로 걸린 뒤(응답 붙잡힘)에 풀어, 포커스가 떠난 적 없어 통과하는 헛통과를 막는다
    await expect(nameInput).toBeDisabled();
    gates[1]();
    await expect(nameInput).toBeEnabled();
    await expect(save).toBeFocused();
  });

  test('충돌 창에서 덮어쓰기가 성공하면 보기 모드가 되고 포커스를 칸으로 강제하지 않는다 (WD-992)', async ({
    authenticatedPage: page,
  }) => {
    await mockPipeline(page, conflictThen(200).handler);

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.getByLabel('파이프라인 이름').fill('renamed');
    const save = await saveByKeyboard(page);
    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toBeVisible();
    await expect(save).toBeEnabled();

    await dialog.getByRole('button', { name: '내 변경으로 덮어쓰기' }).click();
    await expect(dialog).toHaveCount(0);
    await expect(page.getByRole('button', { name: '수정' })).toBeVisible();
    // 보기 모드로 간 뒤 저장 전 칸·저장 버튼으로 포커스를 되돌리지 않는다.
    // 포커스 목표는 결정적이지 않다: 충돌 창이 닫히는 시점의 Radix 복귀와 WD-415 useEditModeFocus(「수정」 버튼 이동,
    // 포커스를 잃은 경우에만 동작)의 순서에 따라 「수정」 버튼이 아니라 body 에 남기도 한다(실측: 「수정」 버튼은 포커스 못 받음).
    // 그래서 구체적 요소 대신 「포커스가 입력 칸이 아니고, 활성 요소가 문서에 붙어 있다(사라진 요소 아님)」만 단언한다 —
    // 'input:focus 없음' 만으로는 보기 모드에서 항상 참이라 헛통과하므로 activeElement 를 직접 읽는다
    const active = await page.evaluate(() => {
      const el = document.activeElement;
      return { connected: !!el && el.isConnected, tag: el?.tagName ?? null };
    });
    expect(active.connected).toBe(true);
    expect(['INPUT', 'TEXTAREA']).not.toContain(active.tag);
    await expect(save).toHaveCount(0);
  });

  test('충돌 창에서 덮어쓰기가 500 으로 실패해 창이 닫히면 저장 전 포커스로 돌아온다 (WD-992)', async ({
    authenticatedPage: page,
  }) => {
    const conflict = conflictThen(500);
    await mockPipeline(page, conflict.handler);

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.getByLabel('파이프라인 이름').fill('renamed');
    const save = await saveByKeyboard(page);
    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toBeVisible();

    await dialog.getByRole('button', { name: '내 변경으로 덮어쓰기' }).click();
    // 덮어쓰기 PUT(2번째)이 나가 500 으로 끝날 때까지 조건으로 기다린다
    await expect.poll(conflict.count).toBe(2);
    // 500 응답이 앱에 반영돼 실패 안내가 뜬 뒤에 창 상태를 본다(요청 도착만으로는 응답 처리 전일 수 있다)
    await expect(page.getByText('서버 오류').first()).toBeVisible();
    // 덮어쓰기가 실패하면 충돌 창은 그대로 남는다(editConflict 가 유지된다) — 결정적으로 단언한 뒤 「계속 편집」으로 닫는다
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('button', { name: '내 변경으로 덮어쓰기' })).toBeEnabled();
    await dialog.getByRole('button', { name: '계속 편집' }).click();
    await expect(dialog).toHaveCount(0);
    await expect(save).toBeFocused();
  });

  test('스크립트 편집기(CodeMirror)를 고친 뒤 키보드로 시작한 저장이 실패하면 포커스가 저장 버튼으로 돌아온다 (WD-953)', async ({
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
    // 실제 키보드 조작 — 편집기 안에서 Tab 은 들여쓰기라, Escape 로 포커스 가두기를 풀고 Shift+Tab 으로 저장 버튼까지 가 Enter 로 저장한다.
    // 저장 시작 때 포커스는 저장 버튼이고, 스크립트 편집기가 잠금(readOnly)으로 바뀌는 상황에서도 저장 버튼으로 돌아와야 한다
    await page.keyboard.press('Escape');
    const save = await saveByKeyboard(page, 'Shift+Tab', 60);
    await expect.poll(() => hold.requests.length).toBe(1);
    await expect(page.locator('#step-name')).toBeDisabled();

    hold.release();
    await expect(page.locator('#step-name')).toBeEnabled();
    await expect(save).toBeFocused();
  });
  test('저장 중 사용자가 포커스를 다른 곳으로 옮겼으면 실패해도 되돌리지 않는다 (WD-953)', async ({
    authenticatedPage: page,
  }) => {
    await mockPipeline(page);
    const hold = await holdApi(page, 'PUT', '/api/v1/pipelines/1', 500, { status: 500, message: '서버 오류' });

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.getByLabel('파이프라인 이름').fill('renamed');
    // 실제 키보드 조작으로 저장을 시작한다
    await saveByKeyboard(page);
    await expect.poll(() => hold.requests.length).toBe(1);
    // 저장이 도는 동안 사용자가 잠기지 않는 뒤로 가기 링크로 포커스를 옮긴다
    const back = page.getByRole('link', { name: '파이프라인 목록으로' });
    await back.focus();
    await expect(back).toBeFocused();

    hold.release();
    await expect(page.getByLabel('파이프라인 이름')).toBeEnabled();
    // 잠금 해제·복원 프레임이 지난 것을 두 프레임 대기로 확인한 뒤, 되돌리지 않았음을 단언한다
    await waitTwoFrames(page);
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
    // 실패 토스트가 보이고 잠금이 풀린 뒤에도 실행 요청이 없어야 한다
    await expect(page.getByText('서버 오류').first()).toBeVisible();
    await expect(page.getByLabel('파이프라인 이름')).toBeEnabled();
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
    const save = saveButton(page);
    // 클릭이 포커스를 주지 않는 브라우저 흉내 — 포커스를 body 로 빼고 click() 만 부른다.
    // 이 시험의 목적이 그 브라우저의 동작을 재현하는 것이라 실제 클릭·키보드 대신 evaluate 클릭을 의도적으로 쓴다
    // (Chromium 의 실제 클릭은 버튼에 포커스를 줘 이 경로를 만들 수 없다)
    await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
    await save.evaluate((el) => (el as HTMLButtonElement).click());
    await expect.poll(() => hold.requests.length).toBe(1);

    hold.release();
    await expect(page.getByLabel('파이프라인 이름')).toBeEnabled();
    await expect(save).toBeFocused();
  });

  test('스텝 이름 칸에 포커스가 있던 저장이 500 으로 실패하면 잠금이 풀린 뒤 그 칸으로 포커스가 돌아온다 (WD-953)', async ({
    authenticatedPage: page,
  }) => {
    await mockPipeline(page);
    const hold = await holdApi(page, 'PUT', '/api/v1/pipelines/1', 500, { status: 500, message: '서버 오류' });

    await page.goto('/pipelines/1');
    const stepName = page.locator('#step-name');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node', { hasText: 's2' }).click();
    // fill 은 칸에 포커스를 둔다 — 이 포커스가 저장 시작 때 기억돼 실패 뒤 돌아와야 한다(칸 복원 분기)
    await stepName.fill('s2-renamed');
    await expect(stepName).toBeFocused();
    // 칸에 포커스가 있는 채 저장을 시작할 실제 입력 경로는 없다(저장 단축키가 없고 클릭·Enter 는 저장 버튼이 포커스를 가진다).
    // 그래서 이 시험만 포커스를 옮기지 않는 evaluate 클릭을 의도적으로 쓴다 — 칸 복원 분기를 덮을 유일한 방법이다
    const save = saveButton(page);
    await save.evaluate((el) => (el as HTMLButtonElement).click());
    await expect.poll(() => hold.requests.length).toBe(1);
    await expect(stepName).toBeDisabled();

    hold.release();
    await expect(stepName).toBeEnabled();
    await expect(stepName).toBeFocused();
    await expect(stepName).toHaveValue('s2-renamed');
  });
});
