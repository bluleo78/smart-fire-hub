import type { Page } from '@playwright/test';

import type { UserDetailResponse } from '../../../src/types/user';
import { createRole, createUserDetail } from '../../factories/auth.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 워크스페이스 소유권 넘기기 E2E (WD-1132)
 * - 사용자 상세의 「워크스페이스 소유자」 카드: 표시 규칙, 확인 창(이름 입력), 요청 본문, 토스트, 재조회, 오류 안내.
 */

/** 소유자(조회자)가 다른 활성 멤버의 상세를 보는 기본 상태 */
function member(overrides: Partial<UserDetailResponse> = {}): UserDetailResponse {
  return createUserDetail({
    id: 7,
    username: 'member7',
    name: '김멤버',
    email: 'member7@example.com',
    roles: [createRole({ id: 2, name: 'ADMIN', description: '시스템 관리자', isSystem: true })],
    membershipRole: 'ADMIN',
    accountActive: true,
    workspaceName: '서울 소방 허브',
    workspaceOwnerName: '박소유',
    viewerIsOwner: true,
    ...overrides,
  });
}

async function openDetail(page: Page, detail: UserDetailResponse) {
  await setupAdminAuth(page);
  await mockApi(page, 'GET', `/api/v1/users/${detail.id}`, detail);
  await mockApi(page, 'GET', '/api/v1/roles', [
    createRole({ id: 1, name: 'USER', description: '일반 사용자', isSystem: true }),
    createRole({ id: 2, name: 'ADMIN', description: '시스템 관리자', isSystem: true }),
  ]);
  await page.goto(`/admin/users/${detail.id}`);
}

test.describe('워크스페이스 소유권 넘기기 (WD-1132)', () => {
  test('소유자가 다른 활성 멤버 상세에서 소유권을 넘긴다', async ({ authenticatedPage: page }) => {
    await openDetail(page, member());
    const post = await mockApi(page, 'POST', '/api/v1/users/7/ownership', null, { status: 204, capture: true });

    await expect(page.getByRole('heading', { level: 2, name: '워크스페이스 소유자' })).toBeVisible();
    await expect(page.getByText('지금 소유자: 박소유')).toBeVisible();

    await page.getByRole('button', { name: '소유권 넘기기…' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByText('김멤버에게 소유권을 넘길까요?')).toBeVisible();
    for (const line of [
      '새 소유자에게 ADMIN 역할이 없으면 부여합니다',
      '나는 소유자에서 빠지지만 ADMIN 역할은 유지합니다',
      '되돌리려면 새 소유자가 다시 넘겨야 합니다',
      '감사 기록에 남습니다',
    ]) {
      await expect(dialog.getByRole('listitem').filter({ hasText: line })).toBeVisible();
    }

    const confirm = dialog.getByRole('button', { name: '소유권 넘기기', exact: true });
    const input = dialog.getByLabel('확인하려면 워크스페이스 이름을 입력하세요');
    await expect(confirm).toBeDisabled();
    await input.fill('다른 이름');
    await expect(confirm).toBeDisabled();
    await input.fill('서울 소방 허브');
    await expect(confirm).toBeEnabled();
    // 이름을 지우면 다시 비활성
    await input.fill('');
    await expect(confirm).toBeDisabled();
    // 앞뒤 공백은 허용
    await input.fill('  서울 소방 허브  ');
    await expect(confirm).toBeEnabled();

    // 넘긴 뒤 재조회 응답: 대상이 소유자가 됨
    await mockApi(page, 'GET', '/api/v1/users/7', member({ membershipRole: 'OWNER', workspaceOwnerName: '김멤버', viewerIsOwner: false }));
    await confirm.click();

    await expect(page.getByText('소유권을 넘겼습니다')).toBeVisible();
    expect(post.lastRequest()?.payload).toEqual({ confirmName: '서울 소방 허브' });
    await expect(page.getByText('지금 소유자: 김멤버')).toBeVisible();
    await expect(page.getByRole('button', { name: '소유권 넘기기…' })).toHaveCount(0);
  });

  test('저장된 워크스페이스 이름 앞뒤에 공백이 있어도 공백 없이 입력하면 넘길 수 있다', async ({ authenticatedPage: page }) => {
    await openDetail(page, member({ workspaceName: ' 서울 소방 허브 ' }));
    const post = await mockApi(page, 'POST', '/api/v1/users/7/ownership', null, { status: 204, capture: true });

    await page.getByRole('button', { name: '소유권 넘기기…' }).click();
    const dialog = page.getByRole('alertdialog');
    const confirm = dialog.getByRole('button', { name: '소유권 넘기기', exact: true });
    await dialog.getByLabel('확인하려면 워크스페이스 이름을 입력하세요').fill('서울 소방 허브');
    await expect(confirm).toBeEnabled();
    await confirm.click();

    await expect(page.getByText('소유권을 넘겼습니다')).toBeVisible();
    expect(post.lastRequest()?.payload).toEqual({ confirmName: '서울 소방 허브' });
  });

  test('공백만 입력하면 넘기기 버튼이 비활성이다 — 저장 이름이 공백뿐이어도 같다 (WD-1158)', async ({ authenticatedPage: page }) => {
    for (const workspaceName of ['서울 소방 허브', ' 서울 소방 허브 ', '   ']) {
      await openDetail(page, member({ workspaceName }));
      await page.getByRole('button', { name: '소유권 넘기기…' }).click();
      const dialog = page.getByRole('alertdialog');
      const confirm = dialog.getByRole('button', { name: '소유권 넘기기', exact: true });
      await dialog.getByLabel('확인하려면 워크스페이스 이름을 입력하세요').fill('   ');
      await expect(confirm).toBeDisabled();
    }
  });

  test('넘긴 뒤 역할 할당의 ADMIN 체크가 새 역할로 맞춰진다', async ({ authenticatedPage: page }) => {
    // 처음엔 ADMIN 이 없는 멤버(USER 만)
    await openDetail(page, member({ roles: [createRole({ id: 1, name: 'USER', description: '일반 사용자', isSystem: true })] }));
    await mockApi(page, 'POST', '/api/v1/users/7/ownership', null, { status: 204 });
    const adminCheckbox = page.getByRole('checkbox', { name: /ADMIN/ });
    await expect(adminCheckbox).not.toBeChecked();

    // 넘긴 뒤 재조회: 서버가 ADMIN 을 부여해 roles 에 포함되고 OWNER 가 된다
    await mockApi(
      page,
      'GET',
      '/api/v1/users/7',
      member({
        membershipRole: 'OWNER',
        workspaceOwnerName: '김멤버',
        viewerIsOwner: false,
        roles: [
          createRole({ id: 1, name: 'USER', description: '일반 사용자', isSystem: true }),
          createRole({ id: 2, name: 'ADMIN', description: '시스템 관리자', isSystem: true }),
        ],
      }),
    );
    await page.getByRole('button', { name: '소유권 넘기기…' }).click();
    const dialog = page.getByRole('alertdialog');
    await dialog.getByLabel('확인하려면 워크스페이스 이름을 입력하세요').fill('서울 소방 허브');
    await dialog.getByRole('button', { name: '소유권 넘기기', exact: true }).click();

    await expect(page.getByText('지금 소유자: 김멤버')).toBeVisible();
    await expect(adminCheckbox).toBeChecked();
  });

  test('소유자가 아니면 넘기기 버튼이 없다', async ({ authenticatedPage: page }) => {
    await openDetail(page, member({ viewerIsOwner: false }));
    await expect(page.getByText('지금 소유자: 박소유')).toBeVisible();
    await expect(page.getByRole('button', { name: '소유권 넘기기…' })).toHaveCount(0);
  });

  test('대상이 비활성이면 버튼이 없다', async ({ authenticatedPage: page }) => {
    await openDetail(page, member({ isActive: false }));
    await expect(page.getByText('지금 소유자: 박소유')).toBeVisible();
    await expect(page.getByRole('button', { name: '소유권 넘기기…' })).toHaveCount(0);
  });

  test('대상 계정이 비활성(accountActive false)이면 버튼이 없다', async ({ authenticatedPage: page }) => {
    await openDetail(page, member({ accountActive: false }));
    await expect(page.getByText('지금 소유자: 박소유')).toBeVisible();
    await expect(page.getByRole('button', { name: '소유권 넘기기…' })).toHaveCount(0);
  });

  test('소유자가 없으면 「지정된 소유자가 없습니다」', async ({ authenticatedPage: page }) => {
    await openDetail(page, member({ workspaceOwnerName: null }));
    await expect(page.getByText('지정된 소유자가 없습니다')).toBeVisible();
  });

  test('서버 409 오류 메시지가 토스트로 보인다', async ({ authenticatedPage: page }) => {
    await openDetail(page, member());
    await mockApi(page, 'POST', '/api/v1/users/7/ownership', { message: '비활성 멤버에게는 소유권을 넘길 수 없습니다' }, { status: 409 });

    await page.getByRole('button', { name: '소유권 넘기기…' }).click();
    const dialog = page.getByRole('alertdialog');
    await dialog.getByLabel('확인하려면 워크스페이스 이름을 입력하세요').fill('서울 소방 허브');
    await dialog.getByRole('button', { name: '소유권 넘기기', exact: true }).click();

    await expect(page.getByText('비활성 멤버에게는 소유권을 넘길 수 없습니다')).toBeVisible();
  });

  test('서버 400(이름 불일치) 메시지가 토스트로 보인다', async ({ authenticatedPage: page }) => {
    await openDetail(page, member());
    await mockApi(page, 'POST', '/api/v1/users/7/ownership', { message: '워크스페이스 이름이 일치하지 않습니다' }, { status: 400 });

    await page.getByRole('button', { name: '소유권 넘기기…' }).click();
    const dialog = page.getByRole('alertdialog');
    await dialog.getByLabel('확인하려면 워크스페이스 이름을 입력하세요').fill('서울 소방 허브');
    await dialog.getByRole('button', { name: '소유권 넘기기', exact: true }).click();

    await expect(page.getByText('워크스페이스 이름이 일치하지 않습니다')).toBeVisible();
  });

  test('응답이 느려도 성공 토스트와 카드 갱신이 이뤄진다(확인 창이 먼저 닫혀도)', async ({ authenticatedPage: page }) => {
    await openDetail(page, member());
    await mockApi(page, 'POST', '/api/v1/users/7/ownership', null, { status: 204, delayMs: 800 });

    await page.getByRole('button', { name: '소유권 넘기기…' }).click();
    const dialog = page.getByRole('alertdialog');
    await dialog.getByLabel('확인하려면 워크스페이스 이름을 입력하세요').fill('서울 소방 허브');
    await mockApi(page, 'GET', '/api/v1/users/7', member({ membershipRole: 'OWNER', workspaceOwnerName: '김멤버', viewerIsOwner: false }));
    await dialog.getByRole('button', { name: '소유권 넘기기', exact: true }).click();

    // 응답 전에 창은 이미 닫힌다
    await expect(dialog).toHaveCount(0);
    await expect(page.getByText('소유권을 넘겼습니다')).toBeVisible();
    await expect(page.getByText('지금 소유자: 김멤버')).toBeVisible();
  });

  test('응답이 느린 409 도 오류 토스트가 보인다(확인 창이 먼저 닫혀도)', async ({ authenticatedPage: page }) => {
    await openDetail(page, member());
    await mockApi(
      page,
      'POST',
      '/api/v1/users/7/ownership',
      { message: '이미 유일한 소유자입니다' },
      { status: 409, delayMs: 800 },
    );

    await page.getByRole('button', { name: '소유권 넘기기…' }).click();
    const dialog = page.getByRole('alertdialog');
    await dialog.getByLabel('확인하려면 워크스페이스 이름을 입력하세요').fill('서울 소방 허브');
    await dialog.getByRole('button', { name: '소유권 넘기기', exact: true }).click();

    await expect(dialog).toHaveCount(0);
    await expect(page.getByText('이미 유일한 소유자입니다')).toBeVisible();
  });
});
