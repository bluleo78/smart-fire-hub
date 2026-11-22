/**
 * 파이프라인 편집기 — 스텝 삭제 확인 창이 캔버스 이벤트 트리 밖에 있는지 검증 (WD-952)
 *
 * 확인 창이 StepNode 안에 있으면 포털로 DOM 은 밖에 그려져도, React 합성 이벤트는 React 트리를 따라
 * React Flow 의 노드 래퍼와 PipelineCanvas 의 onKeyDown div 로 올라간다. 그래서 창 안에서 누른
 * Backspace·Delete 가 선택된 연결선을 지우고, 배경 클릭이 스텝을 선택하는 사고가 있었다.
 * 창을 캔버스 div 의 형제로 옮긴 뒤에는 이 이벤트들이 캔버스에 닿지 않아야 한다.
 *
 * 픽스처: 「데이터 추출」 → 「데이터 변환」 연결 1개(createPipelineDetail 기본값).
 */
import type { Page } from '@playwright/test';

import { createPipelineDetail, createStep } from '../../factories/pipeline.factory';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantPipelinePermissions, setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

/** 편집 모드로 들어가 연결선이 그려질 때까지 기다린다 */
async function openEditor(page: Page) {
  await setupPipelineEditorMocks(page, 1);
  await page.goto('/pipelines/1');
  await page.getByRole('button', { name: '수정' }).click();
  await expect(page.locator('.react-flow__edge')).toHaveCount(1);
}

/** 연결선을 키보드로 선택한다 — 포커스 후 Enter */
async function selectEdge(page: Page) {
  await page.locator('.react-flow__edge').first().focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('.react-flow__edge.selected')).toHaveCount(1);
}

/** 지정한 스텝 노드(기본 「데이터 변환」)의 X 버튼으로 삭제 확인 창을 연다 */
async function openDeleteDialog(page: Page, stepName = '데이터 변환') {
  await stepXButton(page, stepName).click();
  await expect(page.getByRole('alertdialog')).toBeVisible();
}

/**
 * 이름으로 스텝 노드를 찾는다. hasText 부분 일치는 「SQL로 원본 데이터 추출」 같은 설명에도 걸려
 * 노드 둘이 잡히므로, 이름 요소를 exact 로 가진 노드만 고른다.
 */
function stepNode(page: Page, stepName: string) {
  return page.locator('.react-flow__node').filter({ has: page.getByText(stepName, { exact: true }) });
}

/** 스텝 노드 안의 X(스텝 삭제) 버튼 */
function stepXButton(page: Page, stepName: string) {
  return stepNode(page, stepName).getByRole('button', { name: '스텝 삭제' });
}

/** 확인 창 안의 버튼 */
function dialogButton(page: Page, name: '취소' | '삭제') {
  return page.getByRole('alertdialog').getByRole('button', { name });
}

/**
 * 창 안의 키·배경 클릭이 캔버스에 닿지 않았음을 단언한다.
 * 「없음」 단언은 이벤트가 처리되기 전에도 통과하므로, 창을 마우스로 닫은 뒤 같은 값을 한 번 더 확인해
 * 이벤트가 처리될 시간이 지난 뒤에도 그대로임을 보장한다.
 */
async function expectCanvasUntouched(page: Page) {
  const check = async () => {
    await expect(page.locator('.react-flow__edge')).toHaveCount(1);
    await expect(page.locator('.react-flow__edge.selected')).toHaveCount(1);
    await expect(page.getByText('미저장 변경사항')).toHaveCount(0);
    await expect(page.locator('#step-name')).toHaveCount(0);
  };
  await expect(page.getByRole('alertdialog')).toBeVisible();
  await check();
  await dialogButton(page, '취소').click();
  await expect(page.getByRole('alertdialog')).toHaveCount(0);
  await check();
}

/** 순서를 시간이 아니라 약속으로 정하기 위한 문 — release() 를 부를 때까지 promise 가 기다린다 */
function createGate() {
  let release!: () => void;
  const promise = new Promise<void>((resolve) => {
    release = resolve;
  });
  return { promise, release };
}

/** 저장 중 요청 본문에서 시험이 보는 부분 */
type SavedPayload = { steps: { name: string; scriptContent: string }[] };

/** {{#1}}·{{#2}} 참조가 있는 3스텝 — 스텝 삭제가 새면 참조 번호가 밀려 저장 본문이 달라진다 */
const ORIGINAL_NAMES = ['데이터 추출', '데이터 변환', '데이터 적재'];
const ORIGINAL_SCRIPTS = ['SELECT * FROM source_table', 'SELECT * FROM {{#1}}', 'SELECT * FROM {{#2}}'];

function createThreeStepDetail(name: string) {
  return createPipelineDetail({
    name,
    steps: [
      createStep(),
      createStep({ id: 2, name: '데이터 변환', stepOrder: 1, scriptContent: ORIGINAL_SCRIPTS[1], dependsOnStepNames: ['데이터 추출'] }),
      createStep({ id: 3, name: '데이터 적재', stepOrder: 2, scriptContent: ORIGINAL_SCRIPTS[2], dependsOnStepNames: ['데이터 변환'] }),
    ],
  });
}

/**
 * 「저장 중 삭제 창이 열려 있는데 저장이 끝나는」 상황을 만든다 (WD-952).
 * 첫 PUT 과 그 뒤 GET(재조회)을 시험이 여는 문으로 막아 두어, 저장 완료(읽기 전용 전환)와 재조회(tempId 교체)를
 * 따로 일으킬 수 있다. slowExit 이면 닫히는 창의 퇴장 애니메이션을 늘려 Presence 가 창을 계속 마운트하게 하므로
 * 「닫히는 중인 창의 삭제 버튼」을 200ms 경주 없이 결정적으로 누를 수 있다.
 * 반환 시점: 이름을 바꿔 저장을 누르고, PUT 이 걸려 있는 채로 「데이터 변환」의 삭제 창이 열려 있다.
 */
async function openDialogWhileSaving(page: Page, options: { slowExit: boolean }) {
  await setupPipelineEditorMocks(page, 1);
  const putStarted = createGate();
  const releasePut = createGate();
  const releaseGet = createGate();
  const refetchServed = createGate();
  const puts: SavedPayload[] = [];
  let saved = false;
  await page.route(
    (url) => url.pathname === '/api/v1/pipelines/1',
    async (route) => {
      if (route.request().method() === 'PUT') {
        puts.push(route.request().postDataJSON());
        if (puts.length === 1) {
          putStarted.release();
          await releasePut.promise;
        }
        saved = true;
        return route.fulfill({ json: { updatedAt: '2025-01-01T00:00:00Z' } });
      }
      if (saved) await releaseGet.promise;
      // 저장 뒤 재조회 응답은 이름이 달라, 편집기가 서버 상태로 동기화됐음(tempId 교체)을 화면에서 알 수 있다
      const detail = createThreeStepDetail(saved ? '서버 이름' : '테스트 파이프라인');
      await route.fulfill({ json: { ...detail, updatedAt: saved ? '2025-01-01T00:00:00Z' : null } });
      if (saved) refetchServed.release();
    },
  );
  await page.goto('/pipelines/1');
  await page.getByRole('button', { name: '수정' }).click();
  await expect(page.locator('.react-flow__node')).toHaveCount(3);
  if (options.slowExit) {
    await page.addStyleTag({ content: '[role="alertdialog"][data-state="closed"] { animation-duration: 8s !important; }' });
  }

  await page.locator('header input').first().fill('바뀐 이름');
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await putStarted.promise;
  await stepXButton(page, '데이터 변환').click();
  await expect(page.getByRole('alertdialog')).toBeVisible();
  // 닫히는 동안에는 버튼이 접근성 트리에서 빠지므로 자리를 열려 있을 때 재 둔다
  const confirmBox = await dialogButton(page, '삭제').boundingBox();
  if (!confirmBox) throw new Error('「삭제」 버튼의 자리를 재지 못했다');
  return { confirmBox, puts, releasePut: releasePut.release, releaseGet: releaseGet.release, refetchServed: refetchServed.promise };
}

/** 두 번 그려질 시간을 기다린다 — 시간 대신 렌더 프레임으로 「다시 열렸다면 이미 그려졌을」 시점을 보장한다 */
async function waitRenderFrames(page: Page) {
  await page.evaluate(
    () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
  );
}

/** 닫히는 중인 창의 「삭제」를 누른다 — 좌표가 빗나가면 「아무 일 없음」 단언이 헛통과하므로 버튼에 닿는지 먼저 본다 */
async function clickClosingConfirm(page: Page, box: { x: number; y: number; width: number; height: number }) {
  await expect(page.locator('[role="alertdialog"]')).toHaveAttribute('data-state', 'closed');
  const point = { x: box.x + box.width / 2, y: box.y + box.height / 2 };
  const hit = await page.evaluate(
    ({ x, y }) => document.elementFromPoint(x, y)?.closest('[role="alertdialog"] button')?.textContent,
    point,
  );
  expect(hit).toBe('삭제');
  await page.mouse.click(point.x, point.y);
}

/** 편집 모드로 돌아와 이름을 다시 바꿔 저장하고, 두 번째 PUT 에 세 스텝과 참조 번호가 그대로인지 본다 */
async function saveAgainAndExpectOriginalSteps(page: Page, puts: SavedPayload[]) {
  await page.getByRole('button', { name: '수정' }).click();
  await page.locator('header input').first().fill('다시 바뀐 이름');
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect.poll(() => puts.length).toBe(2);
  expect(puts[1].steps.map((step) => step.name)).toEqual(ORIGINAL_NAMES);
  expect(puts[1].steps.map((step) => step.scriptContent)).toEqual(ORIGINAL_SCRIPTS);
}

test.describe('파이프라인 편집기 — 스텝 삭제 확인 창 (WD-952)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  for (const key of ['Backspace', 'Delete']) {
    test(`창이 열린 채 ${key} 를 눌러도 선택된 연결선은 지워지지 않는다`, async ({ authenticatedPage: page }) => {
      await openEditor(page);
      await selectEdge(page);
      await openDeleteDialog(page);
      // 초점이 「취소」에 있어야 키가 창 안에서 눌린 것이다 — 다른 곳이면 이 시험이 캔버스 키 처리를 보지 못한다
      await expect(dialogButton(page, '취소')).toBeFocused();

      await page.keyboard.press(key);
      await expectCanvasUntouched(page);
    });
  }

  test('창 뒤 배경을 눌러도 스텝이 선택되지 않고 연결선 선택도 유지된다', async ({ authenticatedPage: page }) => {
    await openEditor(page);
    await selectEdge(page);
    await openDeleteDialog(page);

    // 창 밖(왼쪽 위 모서리) 좌표 — 배경(overlay)을 누른다
    await page.mouse.click(5, 5);
    await expectCanvasUntouched(page);
  });

  test('창 뒤 배경을 더블클릭해도 스텝이 선택되지 않고 연결선 선택도 유지된다', async ({ authenticatedPage: page }) => {
    await openEditor(page);
    await selectEdge(page);
    await openDeleteDialog(page);

    await page.mouse.dblclick(5, 5);
    await expectCanvasUntouched(page);
  });

  // 키보드로 창을 닫으면 캔버스는 그대로이고 초점은 창을 연 X 버튼으로 돌아와야 한다(WCAG 2.4.3)
  for (const key of ['Enter', 'Space', 'Escape']) {
    test(`${key} 로 창을 닫으면 캔버스는 그대로이고 초점이 X 버튼으로 돌아온다`, async ({ authenticatedPage: page }) => {
      await openEditor(page);
      await selectEdge(page);
      await openDeleteDialog(page);

      // Enter·Space 는 초점이 있는 「취소」를 누르고, Escape 는 창 자체를 닫는다
      await page.keyboard.press(key);

      await expect(page.getByRole('alertdialog')).toHaveCount(0);
      await expect(page.locator('.react-flow__node')).toHaveCount(2);
      await expect(page.locator('.react-flow__edge')).toHaveCount(1);
      await expect(page.locator('.react-flow__edge.selected')).toHaveCount(1);
      await expect(stepXButton(page, '데이터 변환')).toBeFocused();
    });
  }

  test('Tab → Enter 로 삭제하면 스텝이 지워지고 초점이 body 로 떨어지지 않는다', async ({ authenticatedPage: page }) => {
    await openEditor(page);
    await openDeleteDialog(page);

    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');

    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    await expect(page.locator('.react-flow__node')).toHaveCount(1);
    await expect(page.locator('.react-flow__node').first()).toContainText('데이터 추출');
    await expect
      .poll(() => page.evaluate(() => document.activeElement?.tagName))
      .not.toBe('BODY');
  });

  test('다른 스텝이 선택된 채 다른 스텝의 창 뒤 배경을 눌러도 선택된 스텝이 바뀌지 않는다', async ({ authenticatedPage: page }) => {
    await openEditor(page);
    await stepNode(page, '데이터 추출').click();
    await expect(page.locator('#step-name')).toHaveValue('데이터 추출');

    await openDeleteDialog(page);
    // AlertDialog 는 배경 클릭으로 닫히지 않는다 — 창은 그대로이고 편집 패널의 선택도 그대로여야 한다
    await page.mouse.click(5, 5);
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await expect(page.locator('#step-name')).toHaveValue('데이터 추출');

    // 이벤트가 처리될 시간이 지난 뒤에도 같은지 창을 닫고 한 번 더 확인한다
    await dialogButton(page, '취소').click();
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    await expect(page.locator('#step-name')).toHaveValue('데이터 추출');
  });

  test('취소한 뒤 다른 스텝을 삭제하면 처음 눌렀던 스텝이 아니라 새로 누른 스텝이 지워진다', async ({ authenticatedPage: page }) => {
    await openEditor(page);
    await openDeleteDialog(page, '데이터 변환');
    await dialogButton(page, '취소').click();
    await expect(page.getByRole('alertdialog')).toHaveCount(0);

    await openDeleteDialog(page, '데이터 추출');
    await dialogButton(page, '삭제').click();

    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    await expect(page.locator('.react-flow__node')).toHaveCount(1);
    await expect(page.locator('.react-flow__node').first()).toContainText('데이터 변환');
  });

  test('스텝을 모두 지운 뒤 다시 추가해도 확인 창이 남지 않는다', async ({ authenticatedPage: page }) => {
    await openEditor(page);
    for (const name of ['데이터 변환', '데이터 추출']) {
      await openDeleteDialog(page, name);
      await dialogButton(page, '삭제').click();
      await expect(page.getByRole('alertdialog')).toHaveCount(0);
    }
    await expect(page.getByText('첫 번째 스텝을 추가하세요')).toBeVisible();

    await page.getByRole('button', { name: '스텝 추가' }).click();

    await expect(page.locator('.react-flow__node')).toHaveCount(1);
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
  });

  // 저장·재조회로 삭제 대상이 무효가 되면 대기 중인 삭제 요청도 버려져야 한다(WD-952 리뷰 후속)
  test('저장이 끝나면 열려 있던 삭제 창이 닫히고 다시 수정해도 저절로 열리지 않는다', async ({ authenticatedPage: page }) => {
    const { releasePut } = await openDialogWhileSaving(page, { slowExit: false });

    // PUT 만 풀어 읽기 전용으로 바뀌게 한다 — 재조회(GET)는 아직 막혀 있어 tempId 는 그대로다
    releasePut();
    await expect(page.getByRole('alertdialog')).toHaveCount(0);

    await page.getByRole('button', { name: '수정' }).click();
    // 창이 다시 열리면 바깥이 aria-hidden 이 되어 role 조회가 막히므로, 텍스트로 편집 모드 진입을 확인한다
    await expect(page.getByText('수정모드').first()).toBeVisible();
    // 다시 열렸다면 이 프레임들 안에 그려졌을 것이므로, 이 뒤의 「0」은 우연히 이른 통과가 아니다
    await waitRenderFrames(page);
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
  });

  test('저장이 끝난 직후 닫히는 창의 삭제를 눌러도 읽기 전용 스텝이 지워지지 않는다', async ({ authenticatedPage: page }) => {
    const { confirmBox, puts, releasePut, releaseGet } = await openDialogWhileSaving(page, { slowExit: true });

    releasePut();
    // 읽기 전용이 되어 창이 닫히는 중(퇴장 애니메이션 중이라 아직 마운트되어 있다)일 때 삭제를 누른다
    await clickClosingConfirm(page, confirmBox);
    await expect(page.locator('.react-flow__node')).toHaveCount(3);

    releaseGet();
    await saveAgainAndExpectOriginalSteps(page, puts);
  });

  test('재조회로 tempId 가 바뀐 뒤 닫히는 창의 삭제를 눌러도 다음 저장 본문이 그대로다', async ({ authenticatedPage: page }) => {
    const { confirmBox, puts, releasePut, releaseGet, refetchServed } = await openDialogWhileSaving(page, { slowExit: true });

    releasePut();
    releaseGet();
    await refetchServed;
    // 재조회 결과의 서버 이름이 화면에 나타나면 편집기 상태로 동기화(tempId 교체)된 것이다
    await expect(page.getByText('서버 이름').first()).toBeVisible();
    await clickClosingConfirm(page, confirmBox);

    await saveAgainAndExpectOriginalSteps(page, puts);
  });
});
