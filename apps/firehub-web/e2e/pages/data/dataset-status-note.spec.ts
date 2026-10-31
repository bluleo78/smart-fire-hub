import type { Page } from '@playwright/test';

import { createCategories, createDatasetDetail } from '../../factories/dataset.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';

/**
 * 데이터셋 상태 노트 — 길이 상한(500자)과 머리 두 줄 줄임 (WD-381)
 * - 예전에는 화면·서버 모두 길이 제한이 없어, 긴 노트가 머리에 전부 펼쳐져 탭·본문을 화면 아래로 밀어냈다
 *   (3천 자면 데스크톱 30줄, 10만 자면 휴대폰에서 6만 px).
 */

const LONG_NOTE = '사용 중단 사유 '.repeat(400); // 약 3,600자 — 상한 이전에 저장된 긴 노트

/** 상태 노트가 note 인 데이터셋 상세를 띄울 준비를 한다 */
async function setupWithNote(page: Page, note: string | null) {
  await grantDatasetPermissions(page);
  await setupDatasetDetailMocks(page, 1);
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  await mockApi(
    page,
    'GET',
    '/api/v1/datasets/1',
    createDatasetDetail({ id: 1, status: note ? 'DEPRECATED' : 'NONE', statusNote: note }),
  );
}

test.describe('데이터셋 상태 노트 (WD-381)', () => {
  test('긴 노트는 휴대폰 폭에서도 두 줄로 줄어 탭이 첫 화면에 보이고, 「전체 보기」로 펼쳤다 접을 수 있다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await setupWithNote(page, LONG_NOTE);
    await page.goto('/data/datasets/1');

    const note = page.getByTestId('dataset-status-note');
    await expect(note).toBeVisible();
    // 두 줄 — 줄 높이의 두 배를 넘지 않는다
    const lines = async () =>
      note.evaluate((el) => Math.round(el.clientHeight / parseFloat(getComputedStyle(el).lineHeight)));
    expect(await lines()).toBe(2);
    // 탭이 스크롤 없이 첫 화면 안에 있다
    const tabBox = await page.getByRole('tab', { name: '정보' }).boundingBox();
    expect(tabBox!.y + tabBox!.height).toBeLessThan(844);

    const toggle = page.getByRole('button', { name: '전체 보기' });
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await toggle.click();
    expect(await lines()).toBeGreaterThan(2);
    await expect(page.getByRole('button', { name: '접기' })).toHaveAttribute('aria-expanded', 'true');
    await page.getByRole('button', { name: '접기' }).click();
    expect(await lines()).toBe(2);
  });

  test('한 줄에 들어가는 짧은 노트에는 「전체 보기」 버튼이 없다', async ({ authenticatedPage: page }) => {
    await setupWithNote(page, '공식 데이터셋');
    await page.goto('/data/datasets/1');
    await expect(page.getByTestId('dataset-status-note')).toHaveText('공식 데이터셋');
    await expect(page.getByRole('button', { name: '전체 보기' })).toHaveCount(0);
  });

  test('입력 칸은 500자까지만 받고 글자 수를 보이며, 저장 요청에 500자가 실린다', async ({
    authenticatedPage: page,
  }) => {
    await setupWithNote(page, null);
    const put = await mockApi(
      page,
      'PUT',
      '/api/v1/datasets/1/status',
      createDatasetDetail({ id: 1, status: 'DEPRECATED' }),
      { capture: true },
    );
    await page.goto('/data/datasets/1');
    await page.getByRole('button', { name: '상태 변경' }).click();

    const input = page.getByRole('textbox', { name: '상태 노트' });
    await input.fill('가'.repeat(600));
    await expect(input).toHaveValue('가'.repeat(500));
    await expect(page.locator('#status-note-count')).toHaveText('500/500');

    await page.getByRole('button', { name: '저장', exact: true }).click();
    const req = await put.waitForRequest();
    expect((req.payload as { note: string }).note).toHaveLength(500);
  });

  test('상한 이전에 저장된 긴 노트를 열면 글자 수가 빨갛게 보이고 저장이 막힌다', async ({
    authenticatedPage: page,
  }) => {
    await setupWithNote(page, LONG_NOTE);
    const put = await mockApi(page, 'PUT', '/api/v1/datasets/1/status', {}, { capture: true });
    await page.goto('/data/datasets/1');
    await page.getByRole('button', { name: '상태 변경' }).click();

    const count = page.locator('#status-note-count');
    await expect(count).toHaveText(`${LONG_NOTE.length.toLocaleString('ko-KR')}/500`);
    await expect(count).toHaveClass(/text-destructive/);
    await expect(page.getByRole('textbox', { name: '상태 노트' })).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeDisabled();
    expect(put.lastRequest()).toBeUndefined();
  });

  test('서버가 노트 길이로 거절하면(400) 그 사유를 토스트로 보인다', async ({ authenticatedPage: page }) => {
    await setupWithNote(page, null);
    await mockApi(
      page,
      'PUT',
      '/api/v1/datasets/1/status',
      { status: 400, error: 'Bad Request', message: 'Validation failed', errors: { note: '상태 노트는 500자 이하여야 합니다.' } },
      { status: 400 },
    );
    await page.goto('/data/datasets/1');
    await page.getByRole('button', { name: '상태 변경' }).click();
    await page.getByRole('textbox', { name: '상태 노트' }).fill('짧은 노트');
    await page.getByRole('button', { name: '저장', exact: true }).click();

    await expect(page.getByText('상태 노트는 500자 이하여야 합니다.')).toBeVisible();
    await expect(page.getByText('상태 변경에 실패했습니다.')).toHaveCount(0);
  });
});
