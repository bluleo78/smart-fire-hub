/**
 * 파이프라인 에디터 — 「스텝 추가」 뒤 키보드 포커스 이동 E2E (WD-545)
 *
 * 키보드로 「스텝 추가」를 누르면 빈 상태 버튼이 사라지며 포커스가 <body> 로 떨어져
 * 키보드·스크린리더 사용자가 위치를 잃었다(WCAG 2.4.3). 추가 직후에는 새 스텝의 이름 입력(#step-name)으로
 * 포커스가 옮겨져야 하고, 기존 스텝 카드를 클릭해 선택만 바꿀 때는 포커스를 끌어가지 않아야 한다.
 */

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantPipelinePermissions, setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

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
});
