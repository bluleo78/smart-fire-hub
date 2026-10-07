import {
  setupAdminAuth,
  setupSettingsMocks,
} from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupSecurityLevelMocks } from '../../fixtures/security-level.fixture';

/** 화면 1(목업 s1) — 등급 목록·정책 저장·허용 목록 켜기 확인·순서 적용·이동 삭제. */
test.describe('설정 › 데이터 보안', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupSettingsMocks(page);
    await setupSecurityLevelMocks(page);
  });

  async function openTab(page: import('@playwright/test').Page) {
    await page.goto('/admin/settings');
    await page.getByRole('tab', { name: '데이터 보안' }).click();
  }

  test(
    '등급 목록이 순서·배지·사용량과 함께 보인다',
    { tag: '@smoke' },
    async ({ authenticatedPage: page }) => {
      await openTab(page);
      await expect(
        page.getByText('등급은 아래로 갈수록 높습니다.', { exact: false }),
      ).toBeVisible();
      const rows = page.getByTestId('security-level-row');
      await expect(rows).toHaveCount(4);
      await expect(rows.nth(0)).toContainText('공개');
      await expect(rows.nth(1)).toContainText('기본');
      await expect(rows.nth(1)).toContainText('데이터셋 230 · 역할 4');
      await expect(
        rows.nth(3).getByTestId('security-level-badge'),
      ).toHaveAttribute('data-tone', 'caution');
    },
  );

  test('정책 수정 → PUT payload', async ({ authenticatedPage: page }) => {
    const put = await mockApi(
      page,
      'PUT',
      '/api/v1/security-levels/3',
      {},
      { capture: true },
    );
    await openTab(page);
    await page.getByRole('button', { name: '민감 펼치기' }).click();
    await page.getByRole('radio', { name: '차단' }).first().check();
    await page.getByRole('button', { name: '저장' }).click();
    const req = await put.waitForRequest();
    expect(req.payload).toMatchObject({
      name: '민감',
      exportPolicy: 'DENY',
      aiPolicy: 'SELF_HOSTED_ONLY',
      allowlistRequired: false,
    });
  });

  test('허용 목록 켜기 — 영향 수 확인 + 시드 기본 체크', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/security-levels/3/allowlist-impact', {
      datasetsWithoutAllowlist: 23,
    });
    const put = await mockApi(
      page,
      'PUT',
      '/api/v1/security-levels/3',
      {},
      { capture: true },
    );
    await openTab(page);
    await page.getByRole('button', { name: '민감 펼치기' }).click();
    await page.getByRole('switch', { name: '허용 목록 필요' }).click();
    await page.getByRole('button', { name: '저장' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toContainText('허용 목록을 켤까요?');
    await expect(dialog).toContainText(
      '이 등급 데이터셋 23개의 허용 목록이 비어 있어 저장 즉시 아무도 볼 수 없게 됩니다.',
    );
    await expect(
      dialog.getByRole('checkbox', {
        name: '현재 열람 가능한 역할로 허용 목록 채우기',
      }),
    ).toBeChecked();
    await dialog.getByRole('button', { name: '저장' }).click();
    expect((await put.waitForRequest()).payload).toMatchObject({
      allowlistRequired: true,
      seedAllowlistFromViewers: true,
    });
  });

  test('순서 변경 — 적용 바 → 영향 확인 → PUT', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'POST', '/api/v1/security-levels/reorder/preview', {
      roles: [
        { roleId: 7, roleName: '분석가', datasetDelta: 8 },
        { roleId: 8, roleName: '운영자', datasetDelta: -4 },
      ],
    });
    const put = await mockApi(
      page,
      'PUT',
      '/api/v1/security-levels/reorder',
      {},
      { capture: true },
    );
    await openTab(page);
    await page.getByRole('button', { name: '민감 위로' }).click();
    await expect(page.getByText('저장되지 않은 순서 변경')).toBeVisible();
    await page.getByRole('button', { name: '적용' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toContainText('역할 2개의 열람 범위가 달라집니다.');
    await expect(dialog).toContainText('분석가: 데이터셋 +8');
    await expect(dialog).toContainText('운영자: 데이터셋 −4');
    await dialog.getByRole('button', { name: '적용' }).click();
    expect((await put.waitForRequest()).payload).toEqual({
      orderedIds: [1, 3, 2, 4],
    });
  });

  test('등급 삭제 — 하향 이동은 사유 입력 전 삭제 비활성', async ({
    authenticatedPage: page,
  }) => {
    const del = await mockApi(
      page,
      'DELETE',
      '/api/v1/security-levels/3',
      {},
      { capture: true },
    );
    await openTab(page);
    await page.getByRole('button', { name: '민감 메뉴' }).click();
    await page.getByRole('menuitem', { name: '삭제' }).click();
    const dialog = page.getByRole('dialog', { name: "'민감' 등급 삭제" });
    await expect(dialog).toContainText(
      '데이터셋 12개와 역할 2개를 다른 등급으로 옮겨야 삭제할 수 있습니다.',
    );
    await dialog.getByRole('combobox', { name: '옮길 등급' }).click();
    await page.getByRole('option', { name: '내부 (하향)' }).click();
    const submit = dialog.getByRole('button', { name: '삭제' });
    await expect(submit).toBeDisabled();
    await dialog.getByLabel('하향 사유').fill('민감 등급 통합 정리');
    await expect(submit).toBeEnabled();
    await submit.click();
    expect((await del.waitForRequest()).payload).toEqual({
      reassignToLevelId: 2,
      reason: '민감 등급 통합 정리',
    });
  });

  test('기본 등급의 삭제 메뉴는 비활성', async ({
    authenticatedPage: page,
  }) => {
    await openTab(page);
    await page.getByRole('button', { name: '내부 메뉴' }).click();
    await expect(page.getByRole('menuitem', { name: '삭제' })).toBeDisabled();
    await expect(
      page.getByText('다른 등급을 기본으로 지정한 뒤 삭제하세요'),
    ).toBeVisible();
  });

  test('security:settings 없는 ADMIN 에게는 탭이 없다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/auth/me/permissions', ['user:read']);
    await page.goto('/admin/settings');
    await expect(page.getByRole('tab', { name: 'AI 에이전트' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '데이터 보안' })).toHaveCount(0);
  });
  test('이름 중복(409) — 토스트가 아니라 이름 입력란 아래 인라인 오류', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(
      page,
      'PUT',
      '/api/v1/security-levels/3',
      {
        code: 'SECURITY_LEVEL_NAME_DUPLICATE',
        message: '같은 이름의 보안 등급이 이미 있습니다.',
      },
      { status: 409 },
    );
    await openTab(page);
    await page.getByRole('button', { name: '민감 펼치기' }).click();
    await page.getByLabel('이름', { exact: true }).fill('내부');
    await page.getByRole('button', { name: '저장' }).click();
    const input = page.getByLabel('이름', { exact: true });
    await expect(
      page
        .getByRole('alert')
        .filter({ hasText: '같은 이름의 보안 등급이 이미 있습니다.' }),
    ).toBeVisible();
    await expect(input).toHaveAttribute('aria-invalid', 'true');
    // 고치기 시작하면 오류가 사라진다
    await input.fill('내부2');
    await expect(
      page.getByRole('alert').filter({ hasText: '이미 있습니다' }),
    ).toHaveCount(0);
  });

  test('등급 추가 — 이름 중복(409)은 다이얼로그 안에 인라인 오류', async ({
    authenticatedPage: page,
  }) => {
    const post = await mockApi(
      page,
      'POST',
      '/api/v1/security-levels',
      {
        code: 'SECURITY_LEVEL_NAME_DUPLICATE',
        message: '같은 이름의 보안 등급이 이미 있습니다.',
      },
      { status: 409, capture: true },
    );
    await openTab(page);
    await page.getByRole('button', { name: '등급 추가' }).click();
    const dialog = page.getByRole('dialog', { name: '등급 추가' });
    await dialog.getByLabel('이름').fill('기밀');
    await dialog.getByRole('button', { name: '추가' }).click();
    expect((await post.waitForRequest()).payload).toMatchObject({
      name: '기밀',
      allowlistRequired: false,
    });
    await expect(dialog.getByRole('alert')).toContainText(
      '같은 이름의 보안 등급이 이미 있습니다.',
    );
    await expect(dialog).toBeVisible();
  });

  test('순서 확인 문구는 역할 단위 수치임을 밝힌다(사용자 수 아님)', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'POST', '/api/v1/security-levels/reorder/preview', {
      roles: [{ roleId: 7, roleName: '분석가', datasetDelta: 8 }],
    });
    await openTab(page);
    await page.getByRole('button', { name: '민감 위로' }).click();
    await page.getByRole('button', { name: '적용' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toContainText('역할 기준 데이터셋 수 변화입니다.');
    await expect(dialog).toContainText('사용자 개별 허용은 포함하지 않습니다.');
  });
  test('삭제 다이얼로그 — 취소 후 다른 등급을 열면 이전 선택·사유가 남지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await openTab(page);
    await page.getByRole('button', { name: '민감 메뉴' }).click();
    await page.getByRole('menuitem', { name: '삭제' }).click();
    let dialog = page.getByRole('dialog', { name: "'민감' 등급 삭제" });
    await dialog.getByRole('combobox', { name: '옮길 등급' }).click();
    await page.getByRole('option', { name: '내부 (하향)' }).click();
    await dialog.getByLabel('하향 사유').fill('민감 등급 통합 정리');
    await dialog.getByRole('button', { name: '취소' }).click();
    await expect(dialog).toHaveCount(0);

    // 기밀(id 4) 삭제 — 선택은 비어 있고 하향 사유란도 없다
    await page.getByRole('button', { name: '기밀 메뉴' }).click();
    await page.getByRole('menuitem', { name: '삭제' }).click();
    dialog = page.getByRole('dialog', { name: "'기밀' 등급 삭제" });
    await expect(
      dialog.getByRole('combobox', { name: '옮길 등급' }),
    ).toContainText('선택…');
    await expect(dialog.getByLabel('하향 사유')).toHaveCount(0);
    await expect(dialog.getByRole('button', { name: '삭제' })).toBeDisabled();
  });

  test('삭제 성공 후 다시 열면 입력이 비어 있다', async ({
    authenticatedPage: page,
  }) => {
    const del = await mockApi(
      page,
      'DELETE',
      '/api/v1/security-levels/3',
      {},
      { capture: true },
    );
    await openTab(page);
    await page.getByRole('button', { name: '민감 메뉴' }).click();
    await page.getByRole('menuitem', { name: '삭제' }).click();
    let dialog = page.getByRole('dialog', { name: "'민감' 등급 삭제" });
    await dialog.getByRole('combobox', { name: '옮길 등급' }).click();
    await page.getByRole('option', { name: '기밀' }).click();
    await dialog.getByRole('button', { name: '삭제' }).click();
    await del.waitForRequest();
    await expect(dialog).toHaveCount(0);

    // 모킹 목록은 그대로라 민감 행이 남아 있다 — 다시 열면 깨끗해야 한다
    await page.getByRole('button', { name: '민감 메뉴' }).click();
    await page.getByRole('menuitem', { name: '삭제' }).click();
    dialog = page.getByRole('dialog', { name: "'민감' 등급 삭제" });
    await expect(
      dialog.getByRole('combobox', { name: '옮길 등급' }),
    ).toContainText('선택…');
    await expect(dialog.getByRole('button', { name: '삭제' })).toBeDisabled();
  });

  test('사용량 조회 중에는 삭제가 막히고 안내가 보인다', async ({
    authenticatedPage: page,
  }) => {
    await page.route(
      (url) => url.pathname === '/api/v1/security-levels/usage',
      async (route) => {
        await new Promise((r) => setTimeout(r, 1500));
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([
            { levelId: 3, datasetCount: 12, roleCount: 2 },
          ]),
        });
      },
    );
    await openTab(page);
    await page.getByRole('button', { name: '민감 메뉴' }).click();
    await page.getByRole('menuitem', { name: '삭제' }).click();
    const dialog = page.getByRole('dialog', { name: "'민감' 등급 삭제" });
    await expect(dialog).toContainText('사용 현황을 불러오는 중입니다');
    await expect(dialog).not.toContainText('사용 중이 아닌 등급');
    await expect(dialog.getByRole('button', { name: '삭제' })).toBeDisabled();
    await expect(dialog).toContainText('데이터셋 12개와 역할 2개를');
  });

  test('사용량 조회 실패 시 삭제 불가 안내', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/security-levels/usage',
      { message: 'boom' },
      { status: 500 },
    );
    await openTab(page);
    await page.getByRole('button', { name: '민감 메뉴' }).click();
    await page.getByRole('menuitem', { name: '삭제' }).click();
    const dialog = page.getByRole('dialog', { name: "'민감' 등급 삭제" });
    await expect(dialog).toContainText(
      '사용 현황을 확인하지 못해 삭제할 수 없습니다',
    );
    await expect(dialog.getByRole('button', { name: '삭제' })).toBeDisabled();
  });

  test('등급 추가 — Enter 로 제출되고 설명이 있다', async ({
    authenticatedPage: page,
  }) => {
    const post = await mockApi(
      page,
      'POST',
      '/api/v1/security-levels',
      {},
      { capture: true },
    );
    await openTab(page);
    await page.getByRole('button', { name: '등급 추가' }).click();
    const dialog = page.getByRole('dialog', { name: '등급 추가' });
    await expect(dialog).toHaveAccessibleDescription(
      /가장 높은 등급으로 추가됩니다/,
    );
    await dialog.getByLabel('이름').fill('극비');
    await dialog.getByLabel('이름').press('Enter');
    expect((await post.waitForRequest()).payload).toMatchObject({
      name: '극비',
    });
  });
});
