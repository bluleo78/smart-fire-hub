/**
 * 데이터셋 상세 — 기본 정보(이름·설명·카테고리) 동시 편집 충돌 E2E (WD-575)
 *
 * 결함: 두 사용자가 같은 데이터셋을 열어 둔 상태에서 A 가 이름을 바꾸고, 옛 화면의 B 가 설명만 고쳐 저장하면
 * B 의 옛 이름이 다시 써져 A 의 변경이 조용히 사라졌다(둘 다 204).
 * 이제 정보 저장이 화면이 받은 데이터셋의 updatedAt 을 expectedUpdatedAt 으로 싣고,
 * 서버가 409 EDIT_CONFLICT 로 거부하면 충돌 안내 창(다시 불러오기 / 내 변경으로 덮어쓰기)이 뜬다.
 *
 * 서버는 상태를 가진 모킹이다 — 기준이 지금 서버 값과 다르면 실제 서버처럼 409 를 돌려준다.
 */

import type { Page, Route } from '@playwright/test';

import type { UpdateDatasetRequest } from '@/types/dataset';

import { createCategories, createDatasetDetail } from '../../factories/dataset.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';
import { expectLockedInFlight, expectSingleHeldRequest, holdRequests } from '../../fixtures/hold-api';

const conflictDialog = (page: Page) => page.getByRole('alertdialog', { name: '다른 곳에서 먼저 변경되었습니다' });

/** 기본 정보 카드의 보기 화면에서 항목 값(dd)을 찾는다 — 헤더 제목과 겹치지 않게 dt 이름으로 좁힌다. */
const infoValue = (page: Page, label: string) =>
  page.locator('dl > div').filter({ has: page.locator('dt', { hasText: new RegExp(`^${label}$`) }) }).locator('dd');

/** 이름·설명을 가진 가짜 데이터셋 서버(id 1). */
async function mockDatasetInfoServer(page: Page) {
  let name = '테스트 데이터셋';
  let description = '처음 설명';
  // 서버처럼 마이크로초까지 둔 문자열 — 화면은 받은 값을 그대로 돌려보내야 한다
  let updatedAt = '2026-01-01T09:00:00.123456';
  let version = 0;
  const bump = () => {
    version += 1;
    updatedAt = `2026-01-01T09:00:0${version}.654321`;
  };
  const puts: UpdateDatasetRequest[] = [];

  await setupDatasetDetailMocks(page, 1);
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  // 상세 기본 모킹보다 나중에 등록해 이 상태 서버가 이긴다
  await page.route(
    (url) => url.pathname === '/api/v1/datasets/1',
    (route: Route) => {
      const method = route.request().method();
      if (method === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createDatasetDetail({ id: 1, name, description, updatedAt })),
        });
      }
      if (method !== 'PUT') return route.fallback();
      const body = route.request().postDataJSON() as UpdateDatasetRequest;
      puts.push(body);
      if (body.expectedUpdatedAt && body.expectedUpdatedAt !== updatedAt) {
        return route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({
            status: 409,
            code: 'EDIT_CONFLICT',
            message: '이 데이터셋은 편집을 시작한 뒤 다른 곳에서 변경되었습니다. 다시 불러온 뒤 저장하세요.',
          }),
        });
      }
      name = body.name;
      description = body.description ?? '';
      bump();
      return route.fulfill({ status: 204 });
    },
  );
  return {
    puts,
    state: () => ({ name, description, updatedAt }),
    /** 다른 사용자가 먼저 데이터셋 이름을 바꿨다 */
    otherUserRenames: () => {
      name = 'A 가 바꾼 이름';
      bump();
    },
  };
}

/** 이 화면을 연 뒤 다른 사용자가 이름을 바꿔, 설명만 고친 저장이 충돌 안내 창을 띄우게 한다. */
async function openConflictDialog(page: Page, server: Awaited<ReturnType<typeof mockDatasetInfoServer>>) {
  await page.goto('/data/datasets/1');
  await expect(infoValue(page, '이름')).toHaveText('테스트 데이터셋');
  server.otherUserRenames();
  await page.getByRole('button', { name: '수정' }).click();
  await page.getByLabel('설명').fill('내 설명');
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(conflictDialog(page)).toBeVisible();
}

test.describe('데이터셋 기본 정보 — 동시 편집 충돌 (WD-575)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page);
  });

  test('저장이 받은 updatedAt 을 싣고, 그사이 이름이 바뀌었으면 충돌 안내 후 다시 불러오면 최신 이름이 보인다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockDatasetInfoServer(page);
    await page.goto('/data/datasets/1');
    await expect(infoValue(page, '이름')).toHaveText('테스트 데이터셋');

    // 이 화면을 연 뒤 다른 사용자가 이름을 바꿨다. 옛 화면에서 설명만 고쳐 저장한다.
    server.otherUserRenames();
    await page.getByRole('button', { name: '수정' }).click();
    await page.getByLabel('설명').fill('B 가 고친 설명');
    await page.getByRole('button', { name: '저장', exact: true }).click();

    await expect(conflictDialog(page)).toBeVisible();
    await expect(conflictDialog(page)).toContainText('데이터셋');
    expect(server.puts[0]).toMatchObject({
      name: '테스트 데이터셋',
      description: 'B 가 고친 설명',
      expectedUpdatedAt: '2026-01-01T09:00:00.123456',
    });
    // 먼저 바꾼 이름이 옛 이름으로 되돌려지지 않았다
    expect(server.state().name).toBe('A 가 바꾼 이름');
    // 충돌은 토스트(서버 문구)가 아니라 안내 창으로만 알린다
    await expect(page.getByText('이 데이터셋은 편집을 시작한 뒤')).toBeHidden();

    await conflictDialog(page).getByRole('button', { name: '다시 불러오기' }).click();
    await expect(conflictDialog(page)).toBeHidden();
    // 편집을 닫고 최신 내용을 보여 준다(내 변경은 버린다)
    await expect(infoValue(page, '이름')).toHaveText('A 가 바꾼 이름');
    await expect(infoValue(page, '설명')).toHaveText('처음 설명');

    // 새 기준으로 다시 수정하면 통과한다
    await page.getByRole('button', { name: '수정' }).click();
    await expect(page.getByLabel('데이터셋 이름 *')).toHaveValue('A 가 바꾼 이름');
    await page.getByLabel('설명').fill('다시 고친 설명');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await expect(page.getByText('데이터셋 정보가 업데이트되었습니다.')).toBeVisible();
    expect(server.puts[1]).toMatchObject({
      name: 'A 가 바꾼 이름',
      description: '다시 고친 설명',
      expectedUpdatedAt: '2026-01-01T09:00:01.654321',
    });
  });

  test('「내 변경으로 덮어쓰기」는 expectedUpdatedAt 없이 다시 저장한다', async ({ authenticatedPage: page }) => {
    const server = await mockDatasetInfoServer(page);
    await page.goto('/data/datasets/1');
    await expect(infoValue(page, '이름')).toHaveText('테스트 데이터셋');

    server.otherUserRenames();
    await page.getByRole('button', { name: '수정' }).click();
    await page.getByLabel('설명').fill('내 설명');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await expect(conflictDialog(page)).toBeVisible();

    await conflictDialog(page).getByRole('button', { name: '내 변경으로 덮어쓰기' }).click();
    await expect(conflictDialog(page)).toBeHidden();
    await expect(page.getByText('데이터셋 정보가 업데이트되었습니다.')).toBeVisible();
    expect(server.puts).toHaveLength(2);
    expect(server.puts[1]).not.toHaveProperty('expectedUpdatedAt');
    expect(server.state()).toMatchObject({ name: '테스트 데이터셋', description: '내 설명' });
  });

  test('저장 뒤 다시 읽은 updatedAt 으로 다음 저장을 보내 자기 저장과 충돌하지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockDatasetInfoServer(page);
    await page.goto('/data/datasets/1');
    await expect(infoValue(page, '이름')).toHaveText('테스트 데이터셋');

    await page.getByRole('button', { name: '수정' }).click();
    await page.getByLabel('설명').fill('첫 저장');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    // 저장 뒤 상세를 다시 읽어 보기 화면에 새 설명이 보인다
    await expect(infoValue(page, '설명')).toHaveText('첫 저장');

    await page.getByRole('button', { name: '수정' }).click();
    await page.getByLabel('설명').fill('두 번째 저장');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await expect(infoValue(page, '설명')).toHaveText('두 번째 저장');

    expect(server.puts.map((p) => p.expectedUpdatedAt)).toEqual([
      '2026-01-01T09:00:00.123456',
      '2026-01-01T09:00:01.654321',
    ]);
    await expect(conflictDialog(page)).toBeHidden();
  });
});

test.describe('데이터셋 기본 정보 — 충돌 창 진행 중 포커스 유지 (WD-678)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page);
  });

  test('Enter 로 「내 변경으로 덮어쓰기」를 누르면 진행 중 포커스가 그 버튼에 남고, 다시 Enter 를 눌러도 PUT 은 한 번만 간다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockDatasetInfoServer(page);
    await openConflictDialog(page, server);
    // 충돌을 낸 첫 PUT 뒤부터 붙잡는다
    const hold = await holdRequests(page, '/api/v1/datasets/1', 'PUT');

    const overwrite = conflictDialog(page).getByRole('button', { name: '내 변경으로 덮어쓰기' });
    const cancel = conflictDialog(page).getByRole('button', { name: '계속 편집' });
    await overwrite.focus();
    await page.keyboard.press('Enter');

    // 응답 전: disabled 가 아니라 aria-disabled 로 잠겨 포커스가 body 로 떨어지지 않는다
    await expect.poll(() => hold.count()).toBe(1);
    await expectLockedInFlight(conflictDialog(page), overwrite);

    // 진행 중 다시 Enter, 「계속 편집」 클릭 — 두 번째 요청도, 창 닫기도 일어나지 않는다
    await page.keyboard.press('Enter');
    await cancel.dispatchEvent('click');
    await expect(conflictDialog(page)).toBeVisible();
    await expect(overwrite).toBeFocused();
    // 두 번째 요청이 나갈 틈을 준 뒤 센다 — 누른 직후 바로 세면 아직 안 나간 요청을 놓친다
    await expectSingleHeldRequest(hold.count);

    // Escape 는 끝나지 않는 요청의 비상구로 창을 닫는다 — 요청은 더 나가지 않는다
    await page.keyboard.press('Escape');
    await expect(conflictDialog(page)).toBeHidden();
    expect(hold.count()).toBe(1);

    hold.release();
    await expect(page.getByText('데이터셋 정보가 업데이트되었습니다.')).toBeVisible();
    // 첫 충돌 PUT + 덮어쓰기 PUT 한 번
    expect(server.puts).toHaveLength(2);
    expect(server.puts[1]).not.toHaveProperty('expectedUpdatedAt');
    expect(server.state()).toMatchObject({ name: '테스트 데이터셋', description: '내 설명' });
  });

  test('덮어쓰기가 500 으로 실패하면 창이 열린 채 남고 포커스도 창 안(누른 버튼)에 남아 다시 누를 수 있다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockDatasetInfoServer(page);
    await openConflictDialog(page, server);

    // 덮어쓰기 PUT 은 서버 오류로 실패한다(fail 을 끄면 상태 서버로 넘긴다)
    let fail = true;
    let failingPuts = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1',
      (route) => {
        if (route.request().method() !== 'PUT' || !fail) return route.fallback();
        failingPuts += 1;
        return route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ status: 500, message: '일시적인 서버 오류입니다.' }),
        });
      },
    );

    const overwrite = conflictDialog(page).getByRole('button', { name: '내 변경으로 덮어쓰기' });
    await overwrite.focus();
    await page.keyboard.press('Enter');

    await expect(page.getByText('일시적인 서버 오류입니다.')).toBeVisible();
    expect(failingPuts).toBe(1);
    // 창은 열린 채, 잠금은 풀리고, 포커스는 누른 버튼에 그대로 있다
    await expect(conflictDialog(page)).toBeVisible();
    await expect(overwrite).not.toHaveAttribute('aria-disabled');
    await expect(overwrite).toBeFocused();

    // 다시 Enter 로 재시도할 수 있다 — 이번엔 상태 서버가 받아 저장된다
    fail = false;
    await page.keyboard.press('Enter');
    await expect(conflictDialog(page)).toBeHidden();
    await expect(page.getByText('데이터셋 정보가 업데이트되었습니다.')).toBeVisible();
    expect(server.state()).toMatchObject({ description: '내 설명' });
  });
});
