import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantPipelinePermissions, setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

/**
 * WD-812 — 파이프라인 에디터에서 이름이 비어 있을 때 이름 칸 아래 안내를 보이고 칸에 잇는다.
 * 머리글(56px)이 넘치지 않게 한 줄에 그린다 — 편집 중이면 「미저장 변경사항」 뒤에 안내를 함께, 아니면 해당하는 것만 보인다.
 */
const HINT = '파이프라인 이름을 입력하세요.';

test.describe('파이프라인 에디터 이름 안내 (WD-812)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  async function mockNewEditor(page: import('@playwright/test').Page) {
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [],
      page: 0,
      size: 1000,
      totalElements: 0,
      totalPages: 0,
    });
  }

  test('새 파이프라인: 이름이 비어 있으면 안내가 보이고 이름 칸에 이어진다', async ({ authenticatedPage: page }) => {
    await mockNewEditor(page);
    await page.goto('/pipelines/new');

    await expect(page.getByText(HINT)).toBeVisible();
    await expect(page.getByRole('textbox', { name: '파이프라인 이름' })).toHaveAttribute(
      'aria-describedby',
      /pipeline-name-hint/,
    );
    // 아직 손대지 않아 변경이 없으므로 안내만 보이고 미저장 표시는 없다
    await expect(page.getByText('미저장 변경사항')).toHaveCount(0);
  });

  test('이름을 입력하면 안내가 사라지고 미저장 변경사항 줄이 보인다', async ({ authenticatedPage: page }) => {
    await mockNewEditor(page);
    await page.goto('/pipelines/new');

    await page.getByRole('textbox', { name: '파이프라인 이름' }).fill('일일 집계');

    await expect(page.getByText(HINT)).toHaveCount(0);
    await expect(page.getByText('미저장 변경사항')).toBeVisible();
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeEnabled();
  });

  test('공백만 넣으면 안내가 다시 보이고 미저장 변경사항도 함께 보인다', async ({ authenticatedPage: page }) => {
    await mockNewEditor(page);
    await page.goto('/pipelines/new');

    await page.getByRole('textbox', { name: '파이프라인 이름' }).fill('   ');

    await expect(page.getByText(HINT)).toBeVisible();
    // 이름이 비어도 편집 중이라는 표시는 잃지 않는다
    await expect(page.getByText('미저장 변경사항')).toBeVisible();
  });

  // 반려 수정 — 보이는 라벨 대신 이름 칸 옆에 늘 보이는 필수 표시(*)와 aria-required 로 필수임을 알린다
  test('새 파이프라인: 이름 칸 옆에 필수 표시(*)가 늘 보이고 칸은 aria-required 다', async ({
    authenticatedPage: page,
  }) => {
    await mockNewEditor(page);
    await page.goto('/pipelines/new');

    const nameInput = page.getByRole('textbox', { name: '파이프라인 이름' });
    const marker = page.getByTestId('pipeline-name-required');
    await expect(marker).toBeVisible();
    await expect(marker).toHaveText('*');
    // 장식 표시는 스크린리더에서 감추고, 필수임은 칸 속성으로 읽힌다
    await expect(marker).toHaveAttribute('aria-hidden', 'true');
    await expect(nameInput).toHaveAttribute('aria-required', 'true');

    // 이름을 채워도 필수 표시는 그대로 남는다(안내와 달리 늘 보인다)
    await nameInput.fill('일일 집계');
    await expect(marker).toBeVisible();
  });

  test('좁은 화면(375px)에서 가장 긴 상태여도 머리글은 56px 이고 저장 버튼이 화면 안에 있다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 375, height: 800 });
    await mockNewEditor(page);
    await page.goto('/pipelines/new');

    // 공백만 넣어 「미저장 변경사항 · 안내」가 필수 표시와 함께 보이는 가장 긴 상태를 만든다
    const nameInput = page.getByRole('textbox', { name: '파이프라인 이름' });
    await nameInput.fill('   ');
    await expect(page.getByText(HINT)).toBeVisible();
    await expect(page.getByTestId('pipeline-name-required')).toBeVisible();

    const header = page.locator('header').filter({ has: nameInput });
    const box = await header.boundingBox();
    expect(Math.round(box!.height)).toBe(56);
    // 상태 줄(미저장 표시·안내)이 여러 줄로 접혀 머리글 밖으로 넘치지 않는다 — 한 줄로 말줄임한다
    const hintBox = await page.locator('#pipeline-name-hint').boundingBox();
    const dirtyBox = await page.getByText('미저장 변경사항').boundingBox();
    expect(hintBox!.height).toBeLessThanOrEqual(20);
    expect(dirtyBox!.height).toBeLessThanOrEqual(20);
    expect(hintBox!.y + hintBox!.height).toBeLessThanOrEqual(box!.y + box!.height);
    const saveBox = await page.getByRole('button', { name: '저장', exact: true }).boundingBox();
    expect(saveBox!.x + saveBox!.width).toBeLessThanOrEqual(375);
  });

  test('기존 파이프라인 보기 모드에는 안내도 필수 표시도 없다', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);
    await page.goto('/pipelines/1');

    await expect(page.getByRole('tab', { name: '개요' })).toBeVisible();
    await expect(page.getByText(HINT)).toHaveCount(0);
    // 보기 모드는 이름이 글자로만 보여 필수 표시도 없다
    await expect(page.getByTestId('pipeline-name-required')).toHaveCount(0);
  });

  test('기존 파이프라인 수정 모드: 이름을 지우면 안내가 보이고 칸에 이어지며 저장은 꺼진다 (WD-1090)', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);
    await page.goto('/pipelines/1');

    await page.getByRole('button', { name: '수정', exact: true }).click();
    const nameInput = page.getByRole('textbox', { name: '파이프라인 이름' });
    await nameInput.fill('');

    await expect(page.getByText(HINT)).toBeVisible();
    await expect(nameInput).toHaveAttribute('aria-describedby', /pipeline-name-hint/);
    // 이름을 지운 것도 변경이므로 미저장 표시가 안내와 함께 보인다
    await expect(page.getByText('미저장 변경사항')).toBeVisible();
    // WD-1090: 안내가 보이는 동안에는 저장 버튼도 꺼져 안내와 버튼 상태가 어긋나지 않는다
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeDisabled();
  });

  // WD-1090 — 안내(showNameHint)와 저장 버튼 상태를 한 값으로 맞춘다: 이름이 공백뿐이면 저장 버튼이 꺼진다.
  test('WD-1090 이름이 공백만이면 저장 버튼이 꺼진다', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);
    await page.goto('/pipelines/1');

    await page.getByRole('button', { name: '수정', exact: true }).click();
    await page.getByRole('textbox', { name: '파이프라인 이름' }).fill('   ');

    await expect(page.getByText(HINT)).toBeVisible();
    const save = page.getByRole('button', { name: '저장', exact: true });
    await expect(save).toBeDisabled();
    await expect(save).toHaveAttribute('aria-describedby', /pipeline-name-hint/);
  });

  test('WD-1090 공백 이름에 글자를 넣으면 저장 버튼이 다시 켜지고 다듬어 저장한다', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);
    let captured: { name?: string } | undefined;
    await page.route(
      (url) => url.pathname === '/api/v1/pipelines/1',
      (route) => {
        if (route.request().method() === 'PUT') {
          captured = route.request().postDataJSON();
        }
        return route.fallback();
      },
    );
    await page.goto('/pipelines/1');

    await page.getByRole('button', { name: '수정', exact: true }).click();
    const nameInput = page.getByRole('textbox', { name: '파이프라인 이름' });
    await nameInput.fill('   ');
    const save = page.getByRole('button', { name: '저장', exact: true });
    await expect(save).toBeDisabled();

    await nameInput.fill(' 새 이름 ');
    await expect(save).toBeEnabled();
    await expect(save).not.toHaveAttribute('aria-describedby', /pipeline-name-hint/);
    await save.click();

    await expect.poll(() => captured?.name).toBe('새 이름');
  });

  test('WD-1090 새 파이프라인에서 이름에 공백만 넣으면 저장이 꺼진다', async ({ authenticatedPage: page }) => {
    await mockNewEditor(page);
    await page.goto('/pipelines/new');

    // 이름 변경만으로도 isDirty 가 되므로, 공백 이름이어도 변경이 있는 상태에서 버튼이 꺼지는지 본다
    await page.getByRole('textbox', { name: '파이프라인 이름' }).fill('  ');
    await expect(page.getByText('미저장 변경사항')).toBeVisible();
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeDisabled();
  });

  // WD-1090 — 「실행」 확인 창의 「저장 후 실행」도 저장 버튼과 같은 값(showNameHint)으로 끈다.
  test('WD-1090 이름이 공백이면 「저장 후 실행」이 꺼지고 창 설명에 안내가 들어간다', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);
    await page.goto('/pipelines/1');

    await page.getByRole('button', { name: '수정', exact: true }).click();
    await page.getByRole('textbox', { name: '파이프라인 이름' }).fill('   ');
    await page.getByRole('button', { name: '실행', exact: true }).click();

    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toBeVisible();
    const saveRun = dialog.getByRole('button', { name: '저장 후 실행' });
    await expect(saveRun).toBeDisabled();
    await expect(dialog.getByText(HINT)).toBeVisible();
    // 안내가 창 설명(aria-describedby 대상) 안에 있어 창이 열릴 때 함께 읽힌다
    await expect(dialog).toHaveAccessibleDescription(/파이프라인 이름을 입력하세요/);
  });
});
