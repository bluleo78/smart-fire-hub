import type { Page } from '@playwright/test';

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import {
  grantDatasetPermissions,
  setupDatasetDetailMocks,
  setupDatasetMocks,
} from '../../fixtures/dataset.fixture';

/**
 * 데이터셋 생성 페이지 미저장 입력 이탈 가드 E2E (WD-577)
 *
 * 수정 전: DatasetCreatePage 가 useUnsavedChangesGuard 를 쓰지 않아, 이름·테이블명을 입력한 뒤
 * 사이드바 링크나 취소 버튼을 누르면 확인 없이 이동해 입력이 사라졌다.
 * 수정 후: 폼 isDirty(칼럼 추가·편집 포함)를 가드에 넘겨 사이드바 링크·취소 버튼·새로고침에서 확인 다이얼로그를 띄운다.
 * URL 쿼리(storageType/originType)만으로 연 경우와 생성 성공 후 상세 이동은 막지 않는다.
 */

const GUARD_MESSAGE = '저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?';

// 쓰기 흐름이라 데이터셋 쓰기 권한을 준다(WD-144) — 기본 인증 fixture 는 권한이 비어 있다.
test.beforeEach(async ({ authenticatedPage: page }) => {
  await grantDatasetPermissions(page);
  await setupDatasetMocks(page);
});

/** 사이드바(nav)의 "홈" 링크를 누른다 — 다른 위치의 동명 텍스트와 구분하려고 navigation 영역으로 한정한다. */
async function clickSidebarHome(page: Page) {
  await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();
}

/** 취소 가능한 beforeunload 이벤트를 직접 발생시켜, 가드가 preventDefault 했는지(=브라우저 확인창 대상인지) 돌려준다. */
async function beforeUnloadPrevented(page: Page) {
  return page.evaluate(() => {
    const ev = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(ev);
    return ev.defaultPrevented;
  });
}

test.describe('데이터셋 생성 페이지 — 미저장 입력 이탈 가드 (WD-577)', () => {
  test('이름 입력 후 사이드바 링크를 누르면 확인 다이얼로그가 뜨고, 취소하면 입력이 남는다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/data/datasets/new');
    await page.getByLabel('데이터셋 이름').fill('작성 중 데이터셋');
    await page.getByLabel('테이블명').fill('draft_table');

    await clickSidebarHome(page);

    // 다이얼로그가 뜨고 URL 은 그대로다
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await expect(page.getByText(GUARD_MESSAGE)).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/data/datasets/new');

    // 취소 → 페이지에 남고 입력값이 보존된다
    await page.getByRole('alertdialog').getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('alertdialog')).toBeHidden();
    expect(new URL(page.url()).pathname).toBe('/data/datasets/new');
    await expect(page.getByLabel('데이터셋 이름')).toHaveValue('작성 중 데이터셋');
    await expect(page.getByLabel('테이블명')).toHaveValue('draft_table');
  });

  test('이탈을 확정하면 사이드바 링크 대상으로 이동한다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets/new');
    await page.getByLabel('데이터셋 이름').fill('작성 중 데이터셋');

    await clickSidebarHome(page);
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await page.getByRole('button', { name: '이탈' }).click();

    await expect(page).toHaveURL(/\/$/);
  });

  test('칼럼만 추가해도 dirty 로 보고 다이얼로그를 띄운다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets/new');
    await page.getByRole('button', { name: '칼럼 추가' }).click();

    await clickSidebarHome(page);
    await expect(page.getByRole('alertdialog')).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/data/datasets/new');
  });

  test('쿼리 파라미터로만 연 빈 폼은 다이얼로그 없이 바로 이동하고 beforeunload 도 막지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/data/datasets/new?storageType=TABLE&originType=SOURCE');
    await expect(page.getByRole('heading', { name: '새 데이터셋' })).toBeVisible();

    expect(await beforeUnloadPrevented(page)).toBe(false);

    await clickSidebarHome(page);
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
  });

  test('파일 유형 생성 화면도 이름 입력 후 이탈하면 다이얼로그가 뜬다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets/new?storageType=FILE&originType=SOURCE');
    // 자동 생성 tableName 기본값만으로는 dirty 가 아니다
    expect(await beforeUnloadPrevented(page)).toBe(false);

    await page.getByLabel('데이터셋 이름').fill('파일 데이터셋');
    await clickSidebarHome(page);
    await expect(page.getByRole('alertdialog')).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/data/datasets/new');
  });

  test('입력 후 페이지 취소 버튼을 누르면 다이얼로그가 뜨고, 이탈하면 목록으로 간다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/data/datasets/new');
    await page.getByLabel('데이터셋 이름').fill('작성 중 데이터셋');

    await page.getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await expect(page.getByText(GUARD_MESSAGE)).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/data/datasets/new');

    await page.getByRole('button', { name: '이탈' }).click();
    await expect(page).toHaveURL('/data/datasets');
  });

  test('입력이 있으면 beforeunload(새로고침·탭 닫기)를 막는다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets/new');
    await page.getByLabel('데이터셋 이름').fill('작성 중 데이터셋');

    expect(await beforeUnloadPrevented(page)).toBe(true);
  });

  test('생성 성공 시 다이얼로그 없이 상세 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/datasets',
      { id: 99, name: '신규 데이터셋' },
      { capture: true },
    );
    // 상세 페이지 이동 후 필요한 API 모킹
    await setupDatasetDetailMocks(page, 99);

    await page.goto('/data/datasets/new');
    await page.getByLabel('데이터셋 이름').fill('신규 데이터셋');
    await page.getByLabel('테이블명').fill('new_dataset');
    await page.getByPlaceholder('예: user_id').fill('col_name');
    await page.getByRole('button', { name: '만들기' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({ name: '신규 데이터셋', tableName: 'new_dataset' });

    await expect(page).toHaveURL('/data/datasets/99');
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    // 상세 페이지에서는 생성 화면의 가드가 남아 있지 않다
    expect(await beforeUnloadPrevented(page)).toBe(false);
  });

  test('칼럼의 NULL 허용 체크박스만 바꿔도 다이얼로그를 띄운다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets/new');
    // 기본값 true → 클릭으로 false (setValue 에 shouldDirty 가 없으면 dirty 로 잡히지 않던 경로)
    await page.getByLabel('NULL 허용').click();
    await expect(page.getByLabel('NULL 허용')).not.toBeChecked();

    await clickSidebarHome(page);
    await expect(page.getByRole('alertdialog')).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/data/datasets/new');
  });

  test('카테고리만 골라도 다이얼로그를 띄운다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets/new');
    // 첫 번째 combobox 가 카테고리 셀렉터다 (dataset-create-extended.spec.ts 와 같은 선택 방식)
    await page.getByRole('combobox').first().click();
    await page.getByRole('option', { name: '소방 데이터' }).click();

    await clickSidebarHome(page);
    await expect(page.getByRole('alertdialog')).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/data/datasets/new');
  });

  test('타입을 VARCHAR 로 바꿨다가 텍스트로 되돌리면 깨끗한 폼으로 보고 다이얼로그 없이 이동한다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/data/datasets/new');
    await page.getByRole('combobox').filter({ hasText: '텍스트' }).click();
    await page.getByRole('option', { name: '문자열(크기지정)' }).click();
    // VARCHAR 전용 최대 길이 입력이 마운트(등록)된 것을 확인한다
    await expect(page.getByLabel('최대 길이 *')).toBeVisible();

    await page.getByRole('combobox').filter({ hasText: '문자열(크기지정)' }).click();
    await page.getByRole('option', { name: '텍스트', exact: true }).click();
    await expect(page.getByLabel('최대 길이 *')).toHaveCount(0);

    // 숨겨진 maxLength 값이 남지 않아 원상태와 같다 → 가드가 꺼져 있다
    expect(await beforeUnloadPrevented(page)).toBe(false);
    await clickSidebarHome(page);
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
  });

  test('목록에서 들어와 입력한 뒤 브라우저 뒤로가기를 하면 다이얼로그가 뜨고 입력이 남는다', async ({
    authenticatedPage: page,
  }) => {
    // 실제 히스토리 스택이 쌓이도록 목록 → 유형 선택 모달 → 생성 폼으로 SPA 이동한다 (pipeline-editor-interactive 의 뒤로가기 가드 spec 과 같은 방식)
    await page.goto('/data/datasets');
    await page.getByRole('button', { name: '새 데이터셋' }).click();
    const typeDialog = page.getByRole('dialog');
    await typeDialog.getByRole('button', { name: '테이블' }).click();
    await typeDialog.getByRole('button', { name: '원본' }).click();
    await expect(page).toHaveURL('/data/datasets/new?storageType=TABLE&originType=SOURCE');

    await page.getByLabel('데이터셋 이름').fill('뒤로가기 데이터셋');
    await page.goBack();

    await expect(page.getByRole('alertdialog')).toBeVisible();
    await expect(page.getByText(GUARD_MESSAGE)).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/data/datasets/new');
    await expect(page.getByLabel('데이터셋 이름')).toHaveValue('뒤로가기 데이터셋');
  });

  test('생성 요청 중 이탈을 확정하면, 응답이 늦게 와도 이동한 화면에 머문다 (토스트는 알림)', async ({
    authenticatedPage: page,
  }) => {
    // POST 응답을 테스트가 풀어줄 때까지 붙잡아 둔다 — 요청 도착·응답 해제를 각각 약속으로 제어한다.
    let markRequested: () => void = () => {};
    const requested = new Promise<void>((resolve) => {
      markRequested = resolve;
    });
    let release: () => void = () => {};
    const released = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        markRequested();
        await released;
        await route.fulfill({ status: 201, json: { id: 99, name: '늦은 데이터셋' } });
      },
    );
    await setupDatasetDetailMocks(page, 99);

    await page.goto('/data/datasets/new');
    await page.getByLabel('데이터셋 이름').fill('늦은 데이터셋');
    await page.getByLabel('테이블명').fill('late_dataset');
    await page.getByPlaceholder('예: user_id').fill('col_name');
    await page.getByRole('button', { name: '만들기' }).click();
    await requested;

    // 응답 대기 중 사이드바로 이탈 → 확정
    await clickSidebarHome(page);
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await page.getByRole('button', { name: '이탈' }).click();
    await expect(page).toHaveURL(/\/$/);
    // 생성 화면이 언마운트되기를 기다리지 않는다 — 라우터 전환(startTransition) 중 페이지가 아직 마운트된
    // 틈에 응답이 와도 끌려가지 않아야 한다(마운트 ref 만으로는 이 틈에서 실패했다).

    // 응답을 풀어준다 — 성공 토스트가 떠서 onSubmit 이 끝까지 돈 것을 확인한 뒤에도 URL 은 그대로여야 한다
    release();
    await expect(page.getByText('데이터셋이 생성되었습니다.')).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/');
  });
});
