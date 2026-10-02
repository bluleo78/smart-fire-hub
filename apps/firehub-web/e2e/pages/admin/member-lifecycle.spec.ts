import type { Page } from '@playwright/test';

import { createRole, createUser, createUserDetail } from '../../factories/auth.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** 사용자 상세(WD-2, 와이어프레임 ④): 멤버십 정지·재활성, 워크스페이스에서 제거, 잠금 방지 표시. */
async function openDetail(page: Page, overrides: Parameters<typeof createUserDetail>[0] = {}) {
  await mockApi(page, 'GET', '/api/v1/roles', [
    createRole({ id: 1, name: 'USER', isSystem: true }),
    createRole({ id: 2, name: 'ADMIN', isSystem: true }),
  ]);
  await mockApi(page, 'GET', '/api/v1/users/5',
    createUserDetail({ id: 5, name: '김지수', username: 'jisu@acme.io', email: 'jisu@acme.io', ...overrides }));
  await page.goto('/admin/users/5');
}

test.describe('멤버십 정지·제거', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page); // 현재 사용자 = id 2 (createAdminUserDetail)
  });

  test('스위치 문구와 안내, 끄면 확인 후 { active:false } 전송', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    const capture = await mockApi(page, 'PUT', '/api/v1/users/5/active', null, { status: 204, capture: true });
    await openDetail(page);

    const toggle = page.getByRole('switch', { name: '이 워크스페이스에서 활성' });
    await expect(toggle).toBeChecked();
    await expect(page.getByText('끄면 이 워크스페이스에만 접근할 수 없습니다. 다른 워크스페이스와 계정 자체는 영향 없음.')).toBeVisible();
    await toggle.click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByText('이 워크스페이스에서만 접근이 막힙니다. 다른 워크스페이스와 계정은 영향이 없습니다. 계속하시겠습니까?')).toBeVisible();
    await dialog.getByRole('button', { name: '정지' }).click();

    expect((await capture.waitForRequest()).payload).toEqual({ active: false });
    await expect(page.getByText('멤버십이 정지되었습니다')).toBeVisible();
    await expect(toggle).not.toBeChecked();
  });

  test('정지 멤버는 확인 없이 재활성', async ({ authenticatedPage: page }) => {
    const capture = await mockApi(page, 'PUT', '/api/v1/users/5/active', null, { status: 204, capture: true });
    await openDetail(page, { isActive: false });
    await page.getByRole('switch', { name: '이 워크스페이스에서 활성' }).click();
    expect((await capture.waitForRequest()).payload).toEqual({ active: true });
    await expect(page.getByText('멤버십이 재활성화되었습니다')).toBeVisible();
  });

  test('제거: 확인 다이얼로그 → DELETE → 목록으로', async ({ authenticatedPage: page }) => {
    const capture = await mockApi(page, 'DELETE', '/api/v1/users/5/membership', null, { status: 204, capture: true });
    await openDetail(page);

    await expect(page.getByRole('heading', { name: '워크스페이스에서 제거' })).toBeVisible();
    await expect(page.getByText('이 워크스페이스의 멤버십과 역할을 삭제합니다. 계정과 만든 데이터는 남습니다.')).toBeVisible();
    await page.getByRole('button', { name: '제거' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByText('김지수(jisu@acme.io) 님의 이 워크스페이스 멤버십과 역할을 삭제합니다. 계정과 만든 데이터는 남습니다.')).toBeVisible();
    await dialog.getByRole('button', { name: '제거' }).click();

    await capture.waitForRequest();
    await expect(page.getByText('워크스페이스에서 제거되었습니다')).toBeVisible();
    await expect(page).toHaveURL(/\/admin\/users$/);
  });

  test('제거 실패(409)는 서버 메시지를 토스트로', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'DELETE', '/api/v1/users/5/membership',
      { status: 409, error: 'Conflict', message: '이 워크스페이스의 마지막 활성 ADMIN 은 정지하거나 제거할 수 없습니다' }, { status: 409 });
    await openDetail(page);
    await page.getByRole('button', { name: '제거' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '제거' }).click();
    await expect(page.getByText('이 워크스페이스의 마지막 활성 ADMIN 은 정지하거나 제거할 수 없습니다')).toBeVisible();
    await expect(page).toHaveURL(/\/admin\/users\/5$/);
  });

  for (const [label, overrides, reason] of [
    ['OWNER', { membershipRole: 'OWNER' }, '워크스페이스 소유자는 제거할 수 없습니다'],
    ['마지막 ADMIN', { lastActiveAdmin: true }, '마지막 활성 ADMIN 은 제거할 수 없습니다'],
  ] as const) {
    test(`${label}: 제거·정지 비활성 + 키보드 포커스로 툴팁`, async ({ authenticatedPage: page }) => {
      await openDetail(page, overrides);
      const remove = page.getByRole('button', { name: '제거' });
      await expect(remove).toBeDisabled();
      await expect(page.getByRole('switch', { name: '이 워크스페이스에서 활성' })).toBeDisabled();
      // 포커스 가능한 잠금 래퍼는 사유를 이름으로 갖는다(스크린 리더가 빈 그룹을 읽지 않게).
      await expect(page.getByTestId('remove-lock-trigger')).toHaveAccessibleName(reason);
      await page.getByTestId('remove-lock-trigger').focus();
      await expect(page.getByRole('tooltip')).toHaveText(reason);
    });

    test(`${label}: 정지 스위치 잠금 사유 — 이름 + 키보드 포커스 툴팁`, async ({ authenticatedPage: page }) => {
      await openDetail(page, overrides);
      const suspendReason = reason.replace('제거', '정지');
      const trigger = page.getByTestId('suspend-lock-trigger');
      await expect(trigger).toHaveAccessibleName(suspendReason);
      await trigger.focus();
      await expect(page.getByRole('tooltip')).toHaveText(suspendReason);
    });
  }

  test('자기 자신: 제거·정지 비활성', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/roles', [createRole({ id: 1, name: 'USER', isSystem: true })]);
    await mockApi(page, 'GET', '/api/v1/users/2', createUserDetail({ id: 2, name: '관리자', username: 'admin@example.com' }));
    await page.goto('/admin/users/2');
    await expect(page.getByRole('button', { name: '제거' })).toBeDisabled();
    await expect(page.getByRole('switch', { name: '이 워크스페이스에서 활성' })).toBeDisabled();
    await expect(page.getByTestId('suspend-lock-trigger')).toHaveAccessibleName('자기 자신은 정지할 수 없습니다');
    await page.getByTestId('remove-lock-trigger').hover();
    await expect(page.getByRole('tooltip')).toHaveText('자기 자신은 제거할 수 없습니다');
  });

  // 회귀: 목록 쿼리 staleTime(30s) 때문에 제거·정지 직후 목록이 낡은 캐시를 보여 주던 문제
  test('제거 후 목록으로 돌아오면 목록을 다시 조회해 제거된 행이 사라진다', async ({ authenticatedPage: page }) => {
    let removed = false;
    let listCalls = 0;
    await page.route((url) => url.pathname === '/api/v1/users', (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      listCalls += 1;
      const users = [createUser({ id: 5, name: '김지수', username: 'jisu@acme.io', email: 'jisu@acme.io' }),
        createUser({ id: 6, name: '박민수', username: 'minsu@acme.io', email: 'minsu@acme.io' })]
        .filter((u) => !(removed && u.id === 5));
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(createPageResponse(users)) });
    });
    await mockApi(page, 'DELETE', '/api/v1/users/5/membership', null, { status: 204 });
    await mockApi(page, 'GET', '/api/v1/roles', [createRole({ id: 1, name: 'USER', isSystem: true })]);
    await mockApi(page, 'GET', '/api/v1/users/5', createUserDetail({ id: 5, name: '김지수', username: 'jisu@acme.io', email: 'jisu@acme.io' }));

    await page.goto('/admin/users');
    await expect(page.getByRole('cell', { name: '김지수', exact: true })).toBeVisible();
    await page.getByRole('cell', { name: '김지수', exact: true }).click();
    await expect(page).toHaveURL(/\/admin\/users\/5$/);

    const before = listCalls;
    removed = true;
    await page.getByRole('button', { name: '제거' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '제거' }).click();

    await expect(page).toHaveURL(/\/admin\/users$/);
    await expect(page.getByRole('cell', { name: '박민수', exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: '김지수', exact: true })).toHaveCount(0);
    expect(listCalls).toBeGreaterThan(before);
  });

  test('코드가 있는 409 의 details 는 토스트에 나오지 않고 message 만 나온다 (#787)', async ({ authenticatedPage: page }) => {
    // 실제 서버 모양: GlobalExceptionHandler.handleCodedApiException 이 내리는 ErrorResponse
    // (status/error/message/errors(=details)/timestamp/path/code). 멤버 추가 중복(MEMBER_SUSPENDED)과 같은 응답이다.
    await mockApi(page, 'PUT', '/api/v1/users/5/active', {
      status: 409, error: 'Conflict',
      message: '이미 이 워크스페이스의 멤버입니다(정지됨). 상세에서 재활성화하세요',
      errors: { userId: '5' },
      timestamp: '2026-10-02T06:00:00.000Z', path: '/api/v1/users/5/active',
      code: 'MEMBER_SUSPENDED',
    }, { status: 409 });
    await openDetail(page);
    await page.getByRole('switch', { name: '이 워크스페이스에서 활성' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '정지' }).click();

    // details 값 "5" 가 아니라 서버 message 가 토스트에 나와야 한다(메시지에는 숫자 5 가 없다).
    const toast = page.locator('[data-sonner-toast]').first();
    await expect(toast).toContainText('이미 이 워크스페이스의 멤버입니다(정지됨). 상세에서 재활성화하세요');
    expect(await toast.innerText()).not.toMatch(/\b5\b/);
  });
});
