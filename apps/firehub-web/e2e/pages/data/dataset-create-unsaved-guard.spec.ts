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

/**
 * 첫 칼럼의 데이터 타입을 고른다 — 콤보박스를 「데이터 타입 *」 라벨 이름으로 집는다(WD-398 이 라벨을 트리거에 이었다).
 * 표시 문구(hasText)로 거르면 현재 고른 타입에 따라 셀렉터가 바뀌어 깨지기 쉽다 (WD-642).
 */
async function chooseColumnType(page: Page, optionName: string) {
  await page.getByRole('combobox', { name: '데이터 타입 *' }).click();
  await page.getByRole('option', { name: optionName, exact: true }).click();
}

/** 필수 입력(이름·테이블명·칼럼명)을 채운다. */
async function fillRequired(page: Page, name: string, tableName: string) {
  await page.getByLabel('데이터셋 이름').fill(name);
  await page.getByLabel('테이블명').fill(tableName);
  await page.getByPlaceholder('예: user_id').fill('col_name');
}

/**
 * POST /api/v1/datasets 응답을 테스트가 풀어줄 때까지 붙잡는다 (WD-642).
 * - requested: 첫 요청이 도착하면 풀리는 약속
 * - release(status, json): 붙잡힌 모든 요청에 같은 응답을 돌려준다
 * - count(): 지금까지 도착한 POST 수, payloads: 각 요청 본문
 */
async function holdCreateRequest(page: Page) {
  let markRequested: () => void = () => {};
  const requested = new Promise<void>((resolve) => {
    markRequested = resolve;
  });
  let doRelease: (r: { status: number; json: unknown }) => void = () => {};
  const released = new Promise<{ status: number; json: unknown }>((resolve) => {
    doRelease = resolve;
  });
  const payloads: unknown[] = [];
  await page.route(
    (url) => url.pathname === '/api/v1/datasets',
    async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      payloads.push(route.request().postDataJSON());
      markRequested();
      const r = await released;
      await route.fulfill({ status: r.status, json: r.json });
    },
  );
  return {
    requested,
    payloads,
    count: () => payloads.length,
    release: (status: number, json: unknown) => doRelease({ status, json }),
  };
}

/**
 * 생성 요청을 보내 붙잡아 둔 채 사이드바 링크로 이탈 확인창을 연다 (WD-642).
 * 「확인창이 떠 있는 동안 응답이 온다」 테스트들의 공통 준비 단계다.
 */
async function startCreateThenOpenPrompt(
  page: Page,
  hold: Awaited<ReturnType<typeof holdCreateRequest>>,
  name: string,
  tableName: string,
) {
  await page.goto('/data/datasets/new');
  await fillRequired(page, name, tableName);
  await page.getByRole('button', { name: '만들기' }).click();
  await hold.requested;
  await clickSidebarHome(page);
  await expect(page.getByRole('alertdialog')).toBeVisible();
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
    // 카테고리 콤보박스를 라벨 이름으로 집는다 — 순서(.first())에 기대면 콤보박스가 늘 때 엉뚱한 것을 누른다 (WD-642)
    await page.getByRole('combobox', { name: '카테고리' }).click();
    await page.getByRole('option', { name: '소방 데이터' }).click();

    await clickSidebarHome(page);
    await expect(page.getByRole('alertdialog')).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/data/datasets/new');
  });

  test('타입을 VARCHAR 로 바꿨다가 텍스트로 되돌리면 깨끗한 폼으로 보고 다이얼로그 없이 이동한다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/data/datasets/new');
    await chooseColumnType(page, '문자열(크기지정)');
    // VARCHAR 전용 최대 길이 입력이 마운트(등록)된 것을 확인한다
    await expect(page.getByLabel('최대 길이 *')).toBeVisible();

    await chooseColumnType(page, '텍스트');
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
  test('VARCHAR 길이를 넣고 다른 타입으로 갔다가 돌아오면 넣었던 길이가 그대로 있고 그 길이로 저장된다 (WD-642)', async ({
    authenticatedPage: page,
  }) => {
    const capture = await mockApi(page, 'POST', '/api/v1/datasets', { id: 99, name: '길이 데이터셋' }, { capture: true });
    await setupDatasetDetailMocks(page, 99);

    await page.goto('/data/datasets/new');
    await fillRequired(page, '길이 데이터셋', 'len_dataset');
    await chooseColumnType(page, '문자열(크기지정)');
    await page.getByLabel('최대 길이 *').fill('255');

    await chooseColumnType(page, '텍스트');
    await expect(page.getByLabel('최대 길이 *')).toHaveCount(0);
    await chooseColumnType(page, '문자열(크기지정)');
    // 다시 VARCHAR — 비워지지 않고 사용자가 넣었던 255 가 돌아온다
    await expect(page.getByLabel('최대 길이 *')).toHaveValue('255');

    await page.getByRole('button', { name: '만들기' }).click();
    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({ columns: [{ columnName: 'col_name', dataType: 'VARCHAR', maxLength: 255 }] });
    await expect(page).toHaveURL('/data/datasets/99');
  });

  test('VARCHAR 길이를 넣었다가 다른 타입으로 바꿔 저장하면 숨은 길이를 보내지 않는다 (WD-642)', async ({
    authenticatedPage: page,
  }) => {
    const capture = await mockApi(page, 'POST', '/api/v1/datasets', { id: 99, name: '텍스트 데이터셋' }, { capture: true });
    await setupDatasetDetailMocks(page, 99);

    await page.goto('/data/datasets/new');
    await fillRequired(page, '텍스트 데이터셋', 'text_dataset');
    await chooseColumnType(page, '문자열(크기지정)');
    await page.getByLabel('최대 길이 *').fill('255');
    await chooseColumnType(page, '텍스트');

    await page.getByRole('button', { name: '만들기' }).click();
    const req = await capture.waitForRequest();
    const columns = (req.payload as { columns: Record<string, unknown>[] }).columns;
    expect(columns[0]).toMatchObject({ columnName: 'col_name', dataType: 'TEXT' });
    expect(columns[0].maxLength).toBeUndefined();
  });

  test('생성 요청 중 이탈했다가 같은 생성 화면으로 돌아와 새로 입력하면, 늦게 온 옛 응답이 새 입력을 건드리지 않는다 (WD-642)', async ({
    authenticatedPage: page,
  }) => {
    const hold = await holdCreateRequest(page);
    await setupDatasetDetailMocks(page, 99);

    await page.goto('/data/datasets/new');
    await fillRequired(page, '옛 데이터셋', 'old_dataset');
    await page.getByRole('button', { name: '만들기' }).click();
    await hold.requested;

    await clickSidebarHome(page);
    await page.getByRole('button', { name: '이탈' }).click();
    await expect(page).toHaveURL(/\/$/);
    // 홈 화면이 실제로 그려질 때까지 기다린다 — 라우터 전환(startTransition) 중엔 생성 화면이 아직 마운트돼 있어
    // 그 틈에 뒤로 가면 옛 화면의 가드가 뒤로가기를 가로챈다(사람 속도로는 생기지 않는 테스트 타이밍 문제).
    await expect(page.getByRole('heading', { name: '홈', level: 1 })).toBeVisible();

    // SPA 안에서 뒤로 가 같은 생성 화면(새 인스턴스)으로 돌아온다 — goto 는 새로고침이라 옛 요청이 사라져 의미가 없다
    await page.goBack();
    await expect(page).toHaveURL('/data/datasets/new');
    await expect(page.getByLabel('데이터셋 이름')).toHaveValue('');
    await page.getByLabel('데이터셋 이름').fill('새 입력');

    hold.release(201, { id: 99, name: '옛 데이터셋' });
    // 옛 요청의 성공 토스트로 응답 처리가 끝난 것을 확인한 뒤 검사한다
    await expect(page.getByText('데이터셋이 생성되었습니다.')).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/data/datasets/new');
    await expect(page.getByLabel('데이터셋 이름')).toHaveValue('새 입력');
    // 새 입력은 여전히 미저장으로 가드된다
    expect(await beforeUnloadPrevented(page)).toBe(true);
  });

  test('이탈 확인창이 떠 있는 동안 생성이 성공하면 끌고 가지 않고, 이탈을 고르면 가려던 곳으로 간다 (WD-642)', async ({
    authenticatedPage: page,
  }) => {
    const hold = await holdCreateRequest(page);
    await setupDatasetDetailMocks(page, 99);

    await startCreateThenOpenPrompt(page, hold, '창 데이터셋', 'dialog_dataset');

    hold.release(201, { id: 99, name: '창 데이터셋' });
    await expect(page.getByText('데이터셋이 생성되었습니다.')).toBeVisible();
    // 사용자가 고르기 전이므로 화면·확인창이 그대로다
    await expect(page.getByRole('alertdialog')).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/data/datasets/new');
    // 선택을 기다리는 동안 Cmd/Ctrl+S 를 눌러도 같은 입력으로 다시 만들지 않는다
    await page.keyboard.press('ControlOrMeta+s');
    await page.waitForTimeout(300);
    expect(hold.count()).toBe(1);

    await page.getByRole('button', { name: '이탈' }).click();
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
  });

  test('이탈 확인창이 떠 있는 동안 생성이 성공하고 취소를 고르면 만든 데이터셋 상세로 간다 (WD-642)', async ({
    authenticatedPage: page,
  }) => {
    const hold = await holdCreateRequest(page);
    await setupDatasetDetailMocks(page, 99);

    await startCreateThenOpenPrompt(page, hold, '창 데이터셋', 'dialog_dataset');
    hold.release(201, { id: 99, name: '창 데이터셋' });
    await expect(page.getByText('데이터셋이 생성되었습니다.')).toBeVisible();

    // 남기를 골랐는데 생성 화면에 그대로 두면 같은 입력으로 다시 만들게 되므로, 만든 데이터셋 상세로 보낸다
    await page.getByRole('alertdialog').getByRole('button', { name: '취소' }).click();
    await expect(page).toHaveURL('/data/datasets/99');
    expect(await beforeUnloadPrevented(page)).toBe(false);
  });

  test('이탈 확인창이 떠 있는 동안 생성이 실패하면 오류를 알리고 확인창·입력이 남는다 (WD-642)', async ({
    authenticatedPage: page,
  }) => {
    const hold = await holdCreateRequest(page);

    await startCreateThenOpenPrompt(page, hold, '실패 데이터셋', 'fail_dataset');
    hold.release(500, { status: 500, message: '서버 내부 오류입니다.' });
    await expect(page.getByText('서버 내부 오류입니다.')).toBeVisible();
    await expect(page.getByRole('alertdialog')).toBeVisible();

    await page.getByRole('alertdialog').getByRole('button', { name: '취소' }).click();
    expect(new URL(page.url()).pathname).toBe('/data/datasets/new');
    await expect(page.getByLabel('데이터셋 이름')).toHaveValue('실패 데이터셋');
    expect(await beforeUnloadPrevented(page)).toBe(true);
  });

  test('저장 중 Cmd/Ctrl+S 를 거듭 눌러도 생성 요청은 한 번만 간다 (WD-642)', async ({ authenticatedPage: page }) => {
    const hold = await holdCreateRequest(page);
    await setupDatasetDetailMocks(page, 99);

    await page.goto('/data/datasets/new');
    await fillRequired(page, '단축키 데이터셋', 'shortcut_dataset');
    await page.keyboard.press('ControlOrMeta+s');
    await hold.requested;
    // 요청이 붙잡힌 동안 거듭 누른다 — requestSubmit 은 비활성 버튼과 무관하게 submit 을 일으킨다
    await page.keyboard.press('ControlOrMeta+s');
    await page.keyboard.press('ControlOrMeta+s');
    await expect(page.getByRole('button', { name: '만드는 중...' })).toBeDisabled();

    hold.release(201, { id: 99, name: '단축키 데이터셋' });
    await expect(page).toHaveURL('/data/datasets/99');
    expect(hold.count()).toBe(1);
    expect(hold.payloads[0]).toMatchObject({ name: '단축키 데이터셋', tableName: 'shortcut_dataset' });
  });

  test('생성 요청 중 이탈한 뒤 요청이 실패하면 어느 데이터셋이 저장되지 않았는지 맥락을 담아 알린다 (WD-642)', async ({
    authenticatedPage: page,
  }) => {
    const hold = await holdCreateRequest(page);

    await page.goto('/data/datasets/new');
    await fillRequired(page, '실패 데이터셋', 'fail_dataset');
    await page.getByRole('button', { name: '만들기' }).click();
    await hold.requested;

    await clickSidebarHome(page);
    await page.getByRole('button', { name: '이탈' }).click();
    await expect(page).toHaveURL(/\/$/);

    hold.release(500, { status: 500, message: '서버 내부 오류입니다.' });
    // 성공은 떠난 뒤에도 알리므로 실패도 알린다 — 다른 화면 위라 데이터셋 이름과 「저장되지 않음」을 함께 적는다
    await expect(
      page.getByText('데이터셋 「실패 데이터셋」 생성에 실패했습니다 — 저장되지 않았습니다.'),
    ).toBeVisible();
    // 맥락 없는 서버 원문 토스트는 따로 띄우지 않고, 사용자가 간 화면에 그대로 둔다
    expect(await page.getByText('서버 내부 오류입니다.', { exact: true }).count()).toBe(0);
    expect(new URL(page.url()).pathname).toBe('/');
  });

  test('VARCHAR 길이를 넣고 VARCHAR 아닌 타입끼리 거쳐(텍스트→정수) 돌아와도 넣었던 길이가 남는다 (WD-642)', async ({
    authenticatedPage: page,
  }) => {
    const capture = await mockApi(page, 'POST', '/api/v1/datasets', { id: 99, name: '거친 데이터셋' }, { capture: true });
    await setupDatasetDetailMocks(page, 99);

    await page.goto('/data/datasets/new');
    await fillRequired(page, '거친 데이터셋', 'hop_dataset');
    await chooseColumnType(page, '문자열(크기지정)');
    await page.getByLabel('최대 길이 *').fill('255');
    await chooseColumnType(page, '텍스트');
    await chooseColumnType(page, '정수');
    await chooseColumnType(page, '문자열(크기지정)');
    await expect(page.getByLabel('최대 길이 *')).toHaveValue('255');

    await page.getByRole('button', { name: '만들기' }).click();
    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({ columns: [{ dataType: 'VARCHAR', maxLength: 255 }] });
  });

  test('생성 성공 직후 상세로 넘어가는 사이 Cmd/Ctrl+S 를 눌러도 다시 만들지 않는다 (WD-642)', async ({
    authenticatedPage: page,
  }) => {
    const hold = await holdCreateRequest(page);
    await setupDatasetDetailMocks(page, 99);
    // 상세 화면 코드(lazy 라우트) 로딩을 붙잡아 전환 틈을 넓힌다 — 그동안 라우터는 생성 화면을 마운트한 채 둔다
    let releaseDetailChunk: () => void = () => {};
    const detailChunkReleased = new Promise<void>((resolve) => {
      releaseDetailChunk = resolve;
    });
    // dev 서버는 원본 경로(/src/pages/data/DatasetDetailPage.tsx), preview(훅의 전체 스위트)는 해시 청크
    // (/assets/DatasetDetailPage-<hash>.js)로 받으므로 둘 다 붙잡는다 — 한쪽만 잡으면 preview 에선 전환이 바로 끝나 틈이 사라진다
    let detailChunkHeld = false;
    await page.route(
      /\/(src\/pages\/data\/DatasetDetailPage\.tsx|assets\/DatasetDetailPage-[\w-]+\.js)(\?|$)/,
      async (route) => {
        detailChunkHeld = true;
        await detailChunkReleased;
        await route.continue();
      },
    );

    await page.goto('/data/datasets/new');
    await fillRequired(page, '전환 데이터셋', 'transition_dataset');
    await page.keyboard.press('ControlOrMeta+s');
    await hold.requested;

    hold.release(201, { id: 99, name: '전환 데이터셋' });
    // 성공 처리(토스트)까지 끝나 상세로 넘어가는 중 — 생성 폼은 아직 화면에 있다
    await expect(page.getByText('데이터셋이 생성되었습니다.')).toBeVisible();
    // 상세 청크 요청이 실제로 붙잡혔는지 먼저 확인한다 — 경로가 바뀌어 못 잡으면 여기서 원인이 드러난다
    await expect.poll(() => detailChunkHeld).toBe(true);
    await expect(page.getByLabel('데이터셋 이름')).toBeVisible();
    await page.keyboard.press('ControlOrMeta+s');
    await page.keyboard.press('ControlOrMeta+s');
    // 제출이 일어났다면 검증·요청까지 갈 시간을 준다
    await page.waitForTimeout(300);
    releaseDetailChunk();

    await expect(page).toHaveURL('/data/datasets/99');
    expect(hold.count()).toBe(1);
  });
});

test.describe('데이터셋 생성 페이지 — 이탈 확정 뒤 다음 화면 로딩 중 뒤로 가기 (WD-651)', () => {
  test('이탈 확정 뒤 홈 화면 코드가 늦게 와도, 그 사이 뒤로 가기는 다이얼로그 없이 처리되고 주소·화면·기록이 어긋나지 않는다', async ({
    authenticatedPage: page,
  }) => {
    // 홈 화면 지연 로딩 청크(dev: /src/pages/HomePage.tsx, preview: /assets/HomePage-<hash>.js)를 테스트가 풀어줄 때까지 붙잡는다.
    // 이렇게 해야 「이탈」 확정 뒤 주소는 '/' 로 바뀌었지만 작성 화면이 아직 마운트된 틈을 결정적으로 재현할 수 있다.
    let markChunkRequested: () => void = () => {};
    const chunkRequested = new Promise<void>((resolve) => {
      markChunkRequested = resolve;
    });
    let releaseChunk: () => void = () => {};
    const chunkReleased = new Promise<void>((resolve) => {
      releaseChunk = resolve;
    });
    const isHomeChunk = (url: URL) => /\/HomePage[^/]*\.(tsx|js)$/.test(url.pathname);
    await page.route(
      isHomeChunk,
      async (route) => {
        markChunkRequested();
        await chunkReleased;
        await route.continue();
      },
    );

    await page.goto('/data/datasets/new');
    await page.getByLabel('데이터셋 이름').fill('작성 중 데이터셋');
    const historyLengthBefore = await page.evaluate(() => window.history.length);

    await clickSidebarHome(page);
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await page.getByRole('button', { name: '이탈' }).click();
    await expect(page).toHaveURL(/\/$/);
    await chunkRequested;
    // 홈 화면 코드가 아직 오지 않아 작성 화면이 남아 있는 상태다
    await expect(page.getByLabel('데이터셋 이름')).toBeVisible();

    // 이 틈에 브라우저 뒤로 가기 — 옛 가드가 잡으면 기록을 하나 더 넣고 다이얼로그를 연다(수정 전 결함)
    await page.goBack();
    await expect(page).toHaveURL('/data/datasets/new');

    // 홈 청크를 풀고 그 응답이 도착한 뒤 두 프레임(React 커밋 여유)을 기다린다 — 고정 시간 대기 대신 이벤트로 기다려,
    // 수정 전처럼 청크 도착 후 화면이 홈으로 바뀌는 결함이 있었다면 아래 단언 시점에는 이미 드러나 있다.
    const chunkResponse = page.waitForResponse((res) => isHomeChunk(new URL(res.url())));
    releaseChunk();
    await chunkResponse;
    await page.evaluate(
      () =>
        new Promise<void>((resolve) => {
          requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
        }),
    );

    // 다이얼로그가 뜨지 않고, 주소(/data/datasets/new)와 보이는 화면(작성 폼)이 일치하며, 기록이 늘지 않는다
    expect(new URL(page.url()).pathname).toBe('/data/datasets/new');
    await expect(page.getByRole('heading', { name: '새 데이터셋' })).toBeVisible();
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    expect(await page.evaluate(() => window.history.length)).toBe(historyLengthBefore + 1);

    // 라우터가 대기 중인 전환을 버리고 같은 화면을 그대로 그려 작성 화면이 재마운트되지 않으므로 입력값이 남아 있다.
    // 이 페이지로 되돌아왔으니 가드가 다시 켜져야 한다 — 새로고침 가드와 사이드바 링크 가드가 다시 동작한다.
    await expect(page.getByLabel('데이터셋 이름')).toHaveValue('작성 중 데이터셋');
    expect(await beforeUnloadPrevented(page)).toBe(true);
    await clickSidebarHome(page);
    await expect(page.getByRole('alertdialog')).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/data/datasets/new');
  });
});
