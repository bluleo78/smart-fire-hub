import type { Page } from '@playwright/test';

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import {
  grantDatasetPermissions,
  setupDatasetDetailMocks,
  setupDatasetMocks,
} from '../../fixtures/dataset.fixture';
import { expectUnsavedChangesDialogOpen, unsavedChangesDialog } from '../../fixtures/dialog.fixture';

/**
 * 데이터셋 생성 — 만든 뒤 다른 등록 가드가 dirty 일 때 「취소」해도 만든 데이터셋으로 갈 길이 남는다 E2E (WD-1174 리뷰 후속)
 *
 * 결함: 만든 뒤 상세 이동(requestNavigate)을 다른 dirty 가드가 막아 확인 창이 뜨면, 「취소」에 goOnStay 가 꺼져 있어
 * 이미 만든 데이터셋의 생성 폼에 아무 안내 없이 남았다. 미룬 이동이 창을 다시 연 뒤 두 번째 「취소」도 같았다.
 * 수정: 창이 열리면 goOnStay 를 켠다. 「취소」하면 창을 다시 열지 않고(다시 열면 「이탈」 말고는 빠져나갈 수 없다)
 * 이미 만들어졌다는 안내와 「상세 보기」 바로가기를 띄운다 — 그 가드가 깨끗해진 뒤 누르면 상세로 간다.
 *
 * 실제 화면은 페이지당 가드가 하나라, E2E 빌드가 노출한 레지스트리(window.__unsavedGuardRegistry)로 dirty 가드를 하나 더 등록한다.
 */

type FakeGuardWindow = Window & {
  __unsavedGuardRegistry?: {
    registerActiveGuard: (entry: {
      isDirty: () => boolean;
      restorePath: () => string;
      onIntercepted: () => void;
      setLeaving?: (leaving: boolean) => void;
    }) => () => void;
  };
  __unregisterFakeGuard?: () => void;
};

/** 다른 화면 요소가 dirty 인 것처럼 가드를 하나 더 등록한다 — 「이탈」 확정(떠나는 중)이면 깨끗한 것으로 본다. */
async function registerFakeDirtyGuard(page: Page) {
  await page.evaluate(() => {
    const w = window as FakeGuardWindow;
    let leaving = false;
    w.__unregisterFakeGuard = w.__unsavedGuardRegistry!.registerActiveGuard({
      isDirty: () => !leaving,
      restorePath: () => window.location.pathname,
      onIntercepted: () => {},
      setLeaving: (next) => {
        leaving = next;
      },
    });
  });
}

/** 등록한 가짜 가드를 지운다 — 다른 화면 요소의 변경이 정리된 상황. */
async function unregisterFakeGuard(page: Page) {
  await page.evaluate(() => (window as FakeGuardWindow).__unregisterFakeGuard?.());
}

const guardDialog = unsavedChangesDialog;

/** 이미 만들어졌다는 「상세 보기」 안내 토스트. */
const createdNotice = (page: Page) =>
  page.locator('[data-sonner-toast]').filter({ hasText: '데이터셋은 이미 만들어졌습니다' });

/** 이름·테이블명·칼럼 하나를 채우고 다른 dirty 가드를 등록한 뒤 「만들기」를 눌러, 상세 이동을 묻는 확인 창이 열릴 때까지 간다. */
async function createWithOtherDirtyGuard(page: Page) {
  await mockApi(page, 'POST', '/api/v1/datasets', { id: 99, name: '신규 데이터셋' });
  await setupDatasetDetailMocks(page, 99);
  await page.goto('/data/datasets/new');
  await page.getByLabel('데이터셋 이름').fill('신규 데이터셋');
  await page.getByLabel('테이블명').fill('new_dataset');
  await page.getByPlaceholder('예: user_id').fill('col_name');
  await registerFakeDirtyGuard(page);
  await page.getByRole('button', { name: '만들기' }).click();
  await expect(page.getByText('데이터셋이 생성되었습니다.')).toBeVisible();
  await expectUnsavedChangesDialogOpen(page);
}

/** 위에 이어 확인 창에서 「취소」해 남고, 뜬 안내를 돌려준다. */
async function createThenStay(page: Page) {
  await createWithOtherDirtyGuard(page);
  await guardDialog(page).getByRole('button', { name: '취소' }).click();
  await expect(guardDialog(page)).toBeHidden();
  const notice = createdNotice(page);
  await expect(notice).toBeVisible();
  return notice;
}

test.describe('WD-1174 리뷰 후속 — 만든 뒤 다른 가드 「취소」', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page);
    await setupDatasetMocks(page);
  });

  test('만든 뒤 다른 가드가 dirty 면 확인 창이 뜨고, 「취소」하면 창을 다시 열지 않고 「상세 보기」 안내를 남겨 그 가드가 깨끗해진 뒤 상세로 간다', async ({
    authenticatedPage: page,
  }) => {
    // 「취소」 — 다른 가드의 변경을 지키려는 뜻이라 창을 다시 열지 않고 남는다(예전엔 아무 안내 없이 생성 폼에 남았다)
    const notice = await createThenStay(page);
    await expect(page).toHaveURL('/data/datasets/new');

    // 다른 가드의 변경이 정리된 뒤 「상세 보기」를 누르면 만든 데이터셋 상세로 간다
    await unregisterFakeGuard(page);
    await notice.getByRole('button', { name: '상세 보기' }).click();
    await expect(page).toHaveURL('/data/datasets/99');
  });

  test('만든 뒤 다른 가드가 dirty 일 때 「이탈」을 고르면 만든 데이터셋 상세로 간다', async ({
    authenticatedPage: page,
  }) => {
    await createWithOtherDirtyGuard(page);
    await guardDialog(page).getByRole('button', { name: '이탈' }).click();
    await expect(page).toHaveURL('/data/datasets/99');
  });

  test('「상세 보기」를 눌렀을 때 다른 가드가 아직 dirty 면 다시 확인을 거친다', async ({ authenticatedPage: page }) => {
    const notice = await createThenStay(page);
    await notice.getByRole('button', { name: '상세 보기' }).click();

    await expectUnsavedChangesDialogOpen(page);
    await guardDialog(page).getByRole('button', { name: '이탈' }).click();
    await expect(page).toHaveURL('/data/datasets/99');
  });

  test('「상세 보기」로 다시 연 확인 창에서 또 「취소」해도 안내가 다시 떠 바로가기가 남는다', async ({
    authenticatedPage: page,
  }) => {
    const notice = await createThenStay(page);
    await notice.getByRole('button', { name: '상세 보기' }).click();
    await expectUnsavedChangesDialogOpen(page);

    await guardDialog(page).getByRole('button', { name: '취소' }).click();
    await expect(guardDialog(page)).toBeHidden();
    await expect(notice).toBeVisible();
    await expect(page).toHaveURL('/data/datasets/new');
  });

  test('안내는 몇 초가 지나도 닫히지 않는다', async ({ authenticatedPage: page }) => {
    // 시계를 앞당겨 기본 토스트 시간(4초)을 실제로 기다리지 않고 넘긴다
    await page.clock.install();
    const notice = await createThenStay(page);
    await page.clock.fastForward(10_000);
    await expect(notice).toBeVisible();
  });
});
