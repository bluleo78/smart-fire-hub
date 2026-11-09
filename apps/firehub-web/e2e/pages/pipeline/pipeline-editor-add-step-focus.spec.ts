/**
 * 파이프라인 에디터 — 「스텝 추가」 뒤 키보드 포커스 이동 E2E (WD-545)
 *
 * 키보드로 「스텝 추가」를 누르면 빈 상태 버튼이 사라지며 포커스가 <body> 로 떨어져
 * 키보드·스크린리더 사용자가 위치를 잃었다(WCAG 2.4.3). 추가 직후에는 새 스텝의 이름 입력(#step-name)으로
 * 포커스가 옮겨져야 하고, 기존 스텝 카드를 클릭해 선택만 바꿀 때는 포커스를 끌어가지 않아야 한다.
 */

import { createExecution, createPipelineDetail } from '../../factories/pipeline.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { clickToolbarAddStep, grantPipelinePermissions, setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

test.describe('파이프라인 에디터 — 스텝 추가 후 포커스 (WD-545)', () => {
  // 수정·추가 버튼은 권한이 있을 때만 보인다(WD-219) — 모든 파이프라인 권한을 준다.
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  test('빈 상태 「스텝 추가」를 키보드 Enter 로 누르면 새 스텝 이름 입력에 포커스가 간다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [],
      page: 0,
      size: 1000,
      totalElements: 0,
      totalPages: 0,
    });
    await page.goto('/pipelines/new');
    await expect(page.getByText('첫 번째 스텝을 추가하세요')).toBeVisible();

    const addButton = page.getByRole('button', { name: '스텝 추가', exact: true });
    await addButton.focus();
    await page.keyboard.press('Enter');

    // 새 스텝(#1)의 설정 패널이 열리고 이름 입력이 포커스를 받는다 — body 로 떨어지지 않는다
    await expect(page.getByText('스텝 #1:')).toBeVisible();
    const stepName = page.locator('#step-name');
    await expect(stepName).toBeFocused();
    expect(await page.evaluate(() => document.activeElement?.tagName)).not.toBe('BODY');

    // 포커스된 칸에 바로 입력하면 새 스텝 이름이 된다
    await page.keyboard.type('원천 적재');
    await expect(stepName).toHaveValue('원천 적재');
  });

  test('스텝이 있을 때 툴바 「스텝 추가」를 Enter 로 누르면 새로 추가된 스텝의 이름 입력에 포커스가 간다', async ({
    authenticatedPage: page,
  }) => {
    // 기존 파이프라인(스텝 2개)을 수정 모드로 연다
    await setupPipelineEditorMocks(page);
    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await expect(page.locator('.react-flow__node')).toHaveCount(2);

    const toolbarAdd = page.getByRole('button', { name: '스텝 추가', exact: true });
    await toolbarAdd.focus();
    await page.keyboard.press('Enter');

    // 세 번째(새) 스텝이 선택되고 그 이름 입력(빈 값)에 포커스가 있다
    await expect(page.locator('.react-flow__node')).toHaveCount(3);
    await expect(page.getByText('스텝 #3:')).toBeVisible();
    const stepName = page.locator('#step-name');
    await expect(stepName).toBeFocused();
    await expect(stepName).toHaveValue('');
  });

  test('스텝 카드의 「다음 스텝 추가」로 만든 스텝도 이름 입력에 포커스가 간다', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page);
    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await expect(page.locator('.react-flow__node')).toHaveCount(2);

    await page.getByRole('button', { name: '다음 스텝 추가' }).first().click();

    await expect(page.locator('.react-flow__node')).toHaveCount(3);
    await expect(page.locator('#step-name')).toBeFocused();
    await expect(page.locator('#step-name')).toHaveValue('');
  });

  test('기존 스텝 카드를 클릭해 선택하면 이름 입력으로 포커스를 끌어가지 않는다(회귀)', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page);
    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();

    await page.locator('.react-flow__node').first().click();

    // 선택한 스텝의 패널은 열리지만 이름 입력은 포커스를 받지 않는다
    const stepName = page.locator('#step-name');
    await expect(stepName).toHaveValue('데이터 추출');
    await expect(stepName).not.toBeFocused();
  });
  test('리듀서가 무시한 추가(지워진 원본) 뒤 카드 클릭이 이름 입력으로 포커스를 끌어가지 않는다(WD-666)', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page);
    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await expect(page.locator('.react-flow__node')).toHaveCount(2);

    // 두 번째 스텝(나가는 연결 없음)의 삭제 확인을 연다
    const secondNode = page.locator('.react-flow__node').nth(1);
    await secondNode.getByRole('button', { name: '스텝 삭제' }).click();
    await expect(page.getByRole('alertdialog')).toBeVisible();

    // 삭제 확정과 그 스텝의 「다음 스텝 추가」를 한 이벤트 루프 안에서 연달아 누른다 — 두 번째 클릭은 다시 그려지기 전의
    // 옛 콜백(지워진 원본)을 부르므로 리듀서가 추가를 무시한다(새 스텝·선택 변화 없음).
    await page.evaluate(() => {
      const confirm = Array.from(document.querySelectorAll('[role="alertdialog"] button')).find(
        (b) => b.textContent?.trim() === '삭제',
      ) as HTMLButtonElement | undefined;
      const addAfter = document.querySelectorAll<HTMLButtonElement>('.react-flow__node button[aria-label="다음 스텝 추가"]');
      const stale = addAfter[addAfter.length - 1];
      confirm?.click();
      stale?.click();
    });
    await expect(page.locator('.react-flow__node')).toHaveCount(1);

    // 남은 카드를 클릭해 선택만 바꾼다 — 남아 있던 포커스 표시가 이름 입력으로 포커스를 뺏으면 안 된다
    await page.locator('.react-flow__node').first().click();
    const stepName = page.locator('#step-name');
    await expect(stepName).toHaveValue('데이터 추출');
    await expect(stepName).not.toBeFocused();
  });

  test('리듀서가 혼자 무시한 추가 뒤 서버 재조회로 선택이 새 id 로 바뀌어도 이름 입력으로 포커스를 끌어가지 않는다(WD-672)', async ({
    authenticatedPage: page,
  }) => {
    // 실행 목록 폴링이 RUNNING → COMPLETED 를 보면 상세가 재조회된다(#733). 테스트가 completeRun 을 켜기 전까지는 RUNNING 만 준다 —
    // 무시된 추가를 보낸 뒤에 재조회가 오도록 순서를 정하기 위해서다. 재조회 응답은 내용이 달라야 새 객체로 반영된다(structural sharing).
    // WD-675: 상세 응답 내용은 「실행 완료 뒤인가」로만 바뀐다 — 완료 전에 다른 이유(편집 취소·포커스 복귀 등)로 오는 상세 GET 은 처음과 같은
    // 내용이라 화면을 바꾸지 않는다. 그래서 아래 대기가 그런 GET 에 걸려 「재조회 뒤」를 잘못 판정하지 않고, 완료 뒤 재조회만 집어 확인한다.
    let completeRun = false;
    const REFETCHED_STEP_DESCRIPTION = '실행 완료 뒤 재조회된 설명';
    await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
    await mockApi(page, 'GET', '/api/v1/datasets', { content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0 });
    await page.route(
      (url) => url.pathname === '/api/v1/pipelines/1' || url.pathname === '/api/v1/pipelines/1/executions',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        if (new URL(route.request().url()).pathname.endsWith('/executions')) {
          const status = completeRun ? 'COMPLETED' : 'RUNNING';
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(createPageResponse([createExecution({ id: 475, pipelineId: 1, status })])),
          });
        }
        // 스텝 구성은 그대로 두고 완료 뒤에는 첫 스텝의 설명만 바꾼다 — 다른 동작(스텝 수·의존 관계 변화)에 기대지 않게
        const body = createPipelineDetail({ id: 1, description: '처음 설명' });
        if (completeRun) {
          body.steps[0].description = REFETCHED_STEP_DESCRIPTION;
        }
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
      },
    );

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await expect(page.locator('.react-flow__node')).toHaveCount(2);

    // 스텝을 하나 추가하고(포커스 표시는 여기서 소비된다) 그 새 스텝 카드의 「다음 스텝 추가」 콜백을 붙잡아 둔다
    await clickToolbarAddStep(page);
    await expect(page.locator('.react-flow__node')).toHaveCount(3);
    await expect(page.locator('#step-name')).toBeFocused();
    await page.evaluate(() => {
      const buttons = document.querySelectorAll<HTMLButtonElement>('.react-flow__node button[aria-label="다음 스텝 추가"]');
      const button = buttons[buttons.length - 1] as unknown as Record<string, { onClick?: unknown }>;
      const propsKey = Object.keys(button).find((k) => k.startsWith('__reactProps$'));
      (window as unknown as { __staleAddAfter?: unknown }).__staleAddAfter = propsKey ? button[propsKey].onClick : undefined;
    });

    // 편집을 취소하면 새 스텝이 사라진다 — 붙잡은 콜백은 이제 없는 스텝을 가리키는 옛 콜백이다
    await page.getByRole('button', { name: '취소', exact: true }).click();
    await page.getByRole('button', { name: '변경사항 취소' }).click();
    await expect(page.locator('.react-flow__node')).toHaveCount(2);

    // 다시 수정 모드(미저장 변경 없음 — 재조회가 화면에 반영된다)에서 기존 카드를 골라 둔다
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node').first().click();
    const stepName = page.locator('#step-name');
    await expect(stepName).toHaveValue('데이터 추출');
    await expect(stepName).not.toBeFocused();

    // 옛 콜백으로 추가를 보낸다 — 리듀서가 무시해 같은 상태를 돌려주므로(다른 변화 없음) 다시 그려지지 않는다
    const called = await page.evaluate(() => {
      const stale = (window as unknown as { __staleAddAfter?: (e: unknown) => void }).__staleAddAfter;
      if (!stale) return false;
      stale({ stopPropagation() {}, preventDefault() {} });
      return true;
    });
    expect(called).toBe(true);
    await expect(page.locator('.react-flow__node')).toHaveCount(2);

    // 실행이 끝나 상세가 재조회되면 tempId 가 새로 만들어지고, 선택은 이름으로 새 id 에 다시 붙는다 —
    // 남아 있던 포커스 표시가 이 「새 id 선택」을 새 스텝으로 오인해 이름 입력으로 포커스를 뺏으면 안 된다
    // WD-674: 고정 대기 대신 조건으로 기다린다 — ① 재조회 내용이 화면에 반영됨, ② tempId 가 새로 만들어져 노드 data-id 가 바뀜,
    // ③ 반영 커밋의 effect(포커스 판정)가 끝남(두 프레임).
    const firstNode = page.locator('.react-flow__node').first();
    const nodeIdBeforeRefetch = await firstNode.getAttribute('data-id');
    expect(nodeIdBeforeRefetch).toBeTruthy();
    // WD-675: 아무 상세 GET 이 아니라 「실행 완료 뒤 재조회」가 화면에 반영됐는지를 바뀐 스텝 설명으로 확인한다 — 완료 전 다른 상세 GET 은
    // 처음 내용이라 이 조건을 채우지 못한다. 실행 목록 폴링 주기가 5초라 넉넉한 상한을 둔다.
    await expect(firstNode).not.toContainText(REFETCHED_STEP_DESCRIPTION);
    completeRun = true;
    await expect(firstNode).toContainText(REFETCHED_STEP_DESCRIPTION, { timeout: 15_000 });
    await expect(firstNode).not.toHaveAttribute('data-id', nodeIdBeforeRefetch as string);
    await page.evaluate(
      () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
    );
    await expect(stepName).toHaveValue('데이터 추출');
    await expect(stepName).not.toBeFocused();
  });
});
