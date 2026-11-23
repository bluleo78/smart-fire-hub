/**
 * 데이터셋·보안 대화상자 필수 칸 접근성 E2E (WD-964)
 *
 * 데이터셋 이름·테이블 이름·칼럼명(필드명)·데이터 타입·최대 길이·하향 사유·옮길 등급 칸이
 * 별표 글자 대신 aria-required="true" 로 필수를 알리고, 별표는 aria-hidden 으로 접근 이름에서 빠지는지 고정한다.
 * 최대 길이는 VARCHAR 일 때만 보이고 그때만 aria-required 를 가진다. 데이터가 있어 잠긴(disabled) 칸은 입력할 수 없으므로 필수로 알리지 않는다.
 * native required 는 쓰지 않는다(WD-908·928).
 */
import type { Locator, Page } from '@playwright/test';

import { createCategories, createDatasetDetail } from '../../factories/dataset.factory';
import { createLevelSummary } from '../../factories/security-level.factory';
import { setupAdminAuth, setupSettingsMocks } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import {
  grantDatasetPermissions,
  setupDatasetDetailMocks,
  setupDatasetMocks,
} from '../../fixtures/dataset.fixture';
import { expectRequiredField } from '../../fixtures/required-a11y';
import { setupSecurityLevelMocks } from '../../fixtures/security-level.fixture';

/**
 * 데이터 타입 콤보박스가 필수이고 최대 길이 칸은 VARCHAR 일 때만 나타나 필수가 되며, 다른 타입으로 돌리면 사라지는지 본다.
 * 생성 폼 칼럼 카드와 필드 추가 대화상자가 locator 루트(scope)만 다르다. 생성 폼은 카드가 여럿일 수 있어 first 를 쓴다.
 */
async function expectMaxLengthFollowsType(page: Page, scope: Locator, first: boolean) {
  const pick = (l: Locator) => (first ? l.first() : l);
  const type = pick(scope.getByRole('combobox', { name: '데이터 타입', exact: true }));
  const maxLength = pick(scope.getByRole('spinbutton', { name: '최대 길이', exact: true }));
  await expectRequiredField(type, '데이터 타입');
  // 최대 길이는 VARCHAR 일 때만 보인다
  await expect(maxLength).toHaveCount(0);
  await type.click();
  await page.getByRole('option', { name: '문자열(크기지정)' }).click();
  await expectRequiredField(maxLength, '최대 길이');
  // 다른 타입으로 돌리면 사라진다
  await type.click();
  await page.getByRole('option', { name: '정수' }).click();
  await expect(maxLength).toHaveCount(0);
}

/** 상세 화면 모킹(데이터셋 1) */
async function mockDetail(page: Page, rowCount = 0) {
  await setupDatasetDetailMocks(page, 1);
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  await mockApi(page, 'GET', '/api/v1/datasets/1', createDatasetDetail({ id: 1, rowCount }));
}

test.describe('데이터셋·보안 대화상자 필수 칸 aria-required (WD-964)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page);
  });

  test('데이터셋 생성 폼 칼럼 카드 — 칼럼명·데이터 타입·VARCHAR 일 때만 최대 길이', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await page.goto('/data/datasets/new');
    // 생성 폼은 기본 칼럼 카드가 둘 이상일 수 있어 첫 카드만 본다
    await page.getByRole('button', { name: '칼럼 추가' }).click();
    await expectRequiredField(page.getByRole('textbox', { name: '칼럼명', exact: true }).first(), '칼럼명');
    // 선택 칸(표시명)에는 주지 않는다
    await expect(page.getByRole('textbox', { name: '표시명', exact: true }).first()).not.toHaveAttribute('aria-required', 'true');
    await expectMaxLengthFollowsType(page, page.locator('body'), true);
  });

  test('필드 추가 대화상자 — 필드명·데이터 타입·VARCHAR 일 때만 최대 길이', async ({ authenticatedPage: page }) => {
    await mockDetail(page);
    await page.goto('/data/datasets/1?tab=columns');
    await page.getByRole('button', { name: '필드 추가' }).click();
    const dialog = page.getByRole('dialog');
    await expectRequiredField(dialog.getByRole('textbox', { name: '필드명', exact: true }), '필드명');
    await expectMaxLengthFollowsType(page, dialog, false);
  });

  // 데이터가 없으면 필드명·타입이 필수, 있으면 잠겨 입력할 수 없으므로 필수로 알리지 않는다
  for (const rowCount of [0, 5]) {
    const locked = rowCount > 0;
    test(`필드 수정 대화상자 — 데이터 ${locked ? '있음(잠김): 필수로 알리지 않는다' : '없음: 필수'}`, async ({ authenticatedPage: page }) => {
      await mockDetail(page, rowCount);
      await page.goto('/data/datasets/1?tab=columns');
      await page.getByRole('button', { name: '컬럼 편집' }).first().click();
      const dialog = page.getByRole('dialog');
      const name = dialog.getByRole('textbox', { name: '필드명', exact: true });
      const type = dialog.getByRole('combobox', { name: '데이터 타입', exact: true });
      if (locked) {
        await expect(name).toBeDisabled();
        await expect(name).not.toHaveAttribute('aria-required', 'true');
        await expect(type).not.toHaveAttribute('aria-required', 'true');
      } else {
        await expectRequiredField(name, '필드명');
        await expectRequiredField(type, '데이터 타입');
      }
    });
  }

  test('데이터셋 정보 수정 — 이름 칸', async ({ authenticatedPage: page }) => {
    await mockDetail(page);
    await page.goto('/data/datasets/1');
    await page.getByRole('button', { name: '수정' }).click();
    await expectRequiredField(page.getByRole('textbox', { name: '데이터셋 이름', exact: true }), '데이터셋 이름');
    await expect(page.getByRole('textbox', { name: '설명' })).not.toHaveAttribute('aria-required', 'true');
  });

  test('데이터셋 복제 대화상자 — 데이터셋 이름·테이블 이름', async ({ authenticatedPage: page }) => {
    await mockDetail(page);
    await page.goto('/data/datasets/1');
    await page.getByRole('button', { name: '복제' }).click();
    const dialog = page.getByRole('dialog');
    await expectRequiredField(dialog.getByRole('textbox', { name: '데이터셋 이름', exact: true }), '데이터셋 이름');
    await expectRequiredField(dialog.getByRole('textbox', { name: '테이블 이름', exact: true }), '테이블 이름');
  });

  test('보안 등급 변경 대화상자 — 하향일 때만 보이는 하향 사유', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupDatasetDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/1', createDatasetDetail({ id: 1, securityLevel: createLevelSummary('민감') }));
    await setupSecurityLevelMocks(page, { myRank: 3 });
    await page.goto('/data/datasets/1');
    await page.getByRole('button', { name: '보안 등급 변경' }).click();
    const dialog = page.getByRole('dialog', { name: '보안 등급 변경' });
    await expect(dialog.getByRole('textbox', { name: '하향 사유' })).toHaveCount(0);
    await dialog.getByRole('radio', { name: /내부/ }).check();
    await expectRequiredField(dialog.getByRole('textbox', { name: '하향 사유', exact: true }), '하향 사유');
  });

  test('등급 삭제(이동) 대화상자 — 옮길 등급 콤보박스·하향 사유', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupSettingsMocks(page);
    await setupSecurityLevelMocks(page);
    await page.goto('/admin/settings');
    await page.getByRole('tab', { name: '데이터 보안' }).click();
    await page.getByRole('button', { name: '민감 메뉴' }).click();
    await page.getByRole('menuitem', { name: '삭제' }).click();
    const dialog = page.getByRole('dialog', { name: "'민감' 등급 삭제" });
    const target = dialog.getByRole('combobox', { name: '옮길 등급', exact: true });
    await expectRequiredField(target, '옮길 등급');
    await expect(dialog.getByRole('textbox', { name: '하향 사유' })).toHaveCount(0);
    await target.click();
    await page.getByRole('option', { name: '내부 (하향)' }).click();
    await expectRequiredField(dialog.getByRole('textbox', { name: '하향 사유', exact: true }), '하향 사유');
  });
});
