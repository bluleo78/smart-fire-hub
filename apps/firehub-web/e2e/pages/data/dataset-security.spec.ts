import { createCategories, createDatasetDetail } from '../../factories/dataset.factory';
import { createLevelSummary } from '../../factories/security-level.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';
import { setupSecurityLevelMocks } from '../../fixtures/security-level.fixture';

/** 화면 2(목업 s2) — 헤더 배지, 등급 변경, 「보안」 탭(정책 칩·자동 상향 배너·허용 목록). */
async function setup(page: import('@playwright/test').Page, level: '공개' | '내부' | '민감' | '기밀', opts: { autoRaised?: boolean; myRank?: number } = {}) {
  await setupDatasetDetailMocks(page, 1);
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  await mockApi(
    page, 'GET', '/api/v1/datasets/1',
    createDatasetDetail({
      id: 1, name: '인사_평가_2026', securityLevel: createLevelSummary(level),
      securityLevelAutoRaisedAt: opts.autoRaised ? '2026-10-07T09:00:00' : null,
    }),
  );
  await setupSecurityLevelMocks(page, { myRank: opts.myRank });
}

test.describe('데이터셋 상세 — 보안', () => {
  test('헤더에 등급 배지가 보이고 classify 권한이면 변경 버튼이 있다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setup(page, '기밀');
    await page.goto('/data/datasets/1');
    const badge = page.getByTestId('security-level-badge').first();
    await expect(badge).toHaveText('기밀');
    await expect(badge).toHaveAttribute('data-tone', 'caution');
    await expect(page.getByRole('button', { name: '보안 등급 변경' })).toBeVisible();
  });

  test('classify 권한이 없으면 변경 버튼이 없다', async ({ authenticatedPage: page }) => {
    await setup(page, '내부');
    await page.goto('/data/datasets/1');
    await expect(page.getByTestId('security-level-badge').first()).toHaveText('내부');
    await expect(page.getByRole('button', { name: '보안 등급 변경' })).toHaveCount(0);
  });

  test('하향은 사유 필수, 본인 자격 초과 등급은 선택 불가 → PUT payload', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setup(page, '민감', { myRank: 3 });
    const put = await mockApi(page, 'PUT', '/api/v1/datasets/1/security-level', {}, { status: 204, capture: true });
    await page.goto('/data/datasets/1');
    await page.getByRole('button', { name: '보안 등급 변경' }).click();
    const dialog = page.getByRole('dialog', { name: '보안 등급 변경' });
    await expect(dialog.getByRole('radio', { name: /기밀/ })).toBeDisabled();
    await expect(dialog.getByText('본인 열람 등급보다 높아 선택 불가')).toBeVisible();
    await dialog.getByRole('radio', { name: /내부/ }).check();
    const submit = dialog.getByRole('button', { name: '변경' });
    await expect(submit).toBeDisabled();
    await dialog.getByLabel('하향 사유').fill('공개 보고서 반영으로 하향');
    await submit.click();
    expect((await put.waitForRequest()).payload).toEqual({ securityLevelId: 2, reason: '공개 보고서 반영으로 하향' });
  });

  /**
   * 코드리뷰 CR6 — 변경 성공 후 다시 열면 새 등급(현재)이 선택돼 있고 「변경」은 비활성이어야 한다. 성공 시 선택을 옛 등급으로 되돌리면, 다시 연
   * 다이얼로그가 옛 등급을 미리 고른 채 「변경」이 활성이라 한 번 더 누르면 의도치 않게 되돌린다.
   */
  test('등급 변경 성공 후 다시 열면 새 등급이 선택되고 변경 버튼은 비활성', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setup(page, '내부', { myRank: 4 });
    // PUT 이 성공하면 상세 GET 이 새 등급(민감)을 돌려준다 — 실제 서버처럼 무효화 후 재조회에 반영된다.
    let changed = false;
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/security-level',
      (route) => {
        if (route.request().method() !== 'PUT') return route.fallback();
        changed = true;
        return route.fulfill({ status: 204 });
      },
    );
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(
            createDatasetDetail({
              id: 1,
              name: '인사_평가_2026',
              securityLevel: createLevelSummary(changed ? '민감' : '내부'),
            }),
          ),
        });
      },
    );
    await page.goto('/data/datasets/1');
    await page.getByRole('button', { name: '보안 등급 변경' }).click();
    const dialog = page.getByRole('dialog', { name: '보안 등급 변경' });
    await dialog.getByRole('radio', { name: /민감/ }).check();
    await dialog.getByRole('button', { name: '변경' }).click();
    await expect(dialog).toBeHidden();
    await expect(page.getByTestId('security-level-badge').first()).toHaveText('민감');

    await page.getByRole('button', { name: '보안 등급 변경' }).click();
    await expect(dialog.getByRole('radio', { name: '민감 (현재)' })).toBeChecked();
    await expect(dialog.getByRole('radio', { name: /^내부/ })).not.toBeChecked();
    await expect(dialog.getByRole('button', { name: '변경' })).toBeDisabled();
  });

  // 서버(Task 3)는 수동 등급 변경 시 securityLevelAutoRaisedAt 을 지운다 — 웹은 재조회 값을 따라 배너를 거둬야 한다.
  test('수동 등급 변경 후 재조회에서 자동 상향 배너가 사라진다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setup(page, '민감', { autoRaised: true, myRank: 4 });
    let changed = false;
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/security-level',
      (route) => {
        if (route.request().method() !== 'PUT') return route.fallback();
        changed = true;
        return route.fulfill({ status: 204 });
      },
    );
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(
            createDatasetDetail({
              id: 1,
              name: '인사_평가_2026',
              securityLevel: createLevelSummary(changed ? '기밀' : '민감'),
              securityLevelAutoRaisedAt: changed ? null : '2026-10-07T09:00:00',
            }),
          ),
        });
      },
    );
    await page.goto('/data/datasets/1?tab=security');
    await expect(page.getByText(/자동 상향되었습니다/)).toBeVisible();
    await page.getByRole('button', { name: '보안 등급 변경' }).click();
    const dialog = page.getByRole('dialog', { name: '보안 등급 변경' });
    await dialog.getByRole('radio', { name: /기밀/ }).check();
    await dialog.getByRole('button', { name: '변경' }).click();
    await expect(dialog).toBeHidden();
    expect(changed).toBe(true);
    await expect(page.getByTestId('security-level-badge').first()).toHaveText('기밀');
    await expect(page.getByText(/자동 상향되었습니다/)).toHaveCount(0);
  });

  test('보안 탭 — 정책 칩, 자동 상향 배너, 허용 목록 카드', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setup(page, '기밀', { autoRaised: true });
    await mockApi(page, 'GET', '/api/v1/datasets/1/access-grants', [
      { id: 11, type: 'ROLE', subjectId: 5, subjectName: '인사팀', grantedByName: '양동희', grantedAt: '2026-10-07T10:00:00' },
      { id: 12, type: 'USER', subjectId: 99, subjectName: '김OO', grantedByName: '양동희', grantedAt: '2026-10-07T10:01:00' },
    ]);
    await page.goto('/data/datasets/1?tab=security');
    await expect(page.getByText("입력 데이터셋의 등급에 따라 '기밀'(으)로 자동 상향되었습니다", { exact: false })).toBeVisible();
    for (const chip of ['내보내기 차단', '자체 호스팅 AI만', '외부 공유 차단', '감사 기록 중']) {
      await expect(page.getByText(chip)).toBeVisible();
    }
    const rows = page.getByRole('row');
    await expect(rows.filter({ hasText: '인사팀' })).toContainText('역할');
    await expect(rows.filter({ hasText: '김OO' })).toContainText('사용자');
  });

  test('허용 목록 필요 없는 등급이면 카드 대신 안내 문장', async ({ authenticatedPage: page }) => {
    await setup(page, '내부');
    await page.goto('/data/datasets/1?tab=security');
    await expect(page.getByText("열람 등급이 '내부' 이상인 역할은 누구나 볼 수 있습니다.")).toBeVisible();
  });

  test('마지막 항목 제거 불가 + 본인 제거는 확인', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setup(page, '기밀');
    // createAdminUserDetail 의 id 와 같은 사용자 항목 — 본인
    const me = (await import('../../factories/auth.factory')).createAdminUserDetail();
    await mockApi(page, 'GET', '/api/v1/datasets/1/access-grants', [
      { id: 21, type: 'USER', subjectId: me.id, subjectName: me.name, grantedByName: me.name, grantedAt: '2026-10-07T10:00:00' },
    ]);
    await page.goto('/data/datasets/1?tab=security');
    await expect(page.getByRole('button', { name: `${me.name} 제거` })).toBeDisabled();
    await mockApi(page, 'GET', '/api/v1/datasets/1/access-grants', [
      { id: 21, type: 'USER', subjectId: me.id, subjectName: me.name, grantedByName: me.name, grantedAt: '2026-10-07T10:00:00' },
      { id: 22, type: 'ROLE', subjectId: 5, subjectName: '인사팀', grantedByName: me.name, grantedAt: '2026-10-07T10:01:00' },
    ]);
    const del = await mockApi(page, 'DELETE', '/api/v1/datasets/1/access-grants/21', {}, { status: 204, capture: true });
    await page.reload();
    await page.getByRole('button', { name: `${me.name} 제거` }).click();
    await expect(page.getByRole('alertdialog')).toContainText('더 이상 접근할 수 없습니다');
    await page.getByRole('alertdialog').getByRole('button', { name: '제거' }).click();
    await del.waitForRequest();
  });

  test('본인 제거 후 데이터셋이 숨겨지면(404) 목록으로 이동하고 안내한다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setup(page, '기밀');
    const me = (await import('../../factories/auth.factory')).createAdminUserDetail();
    await mockApi(page, 'GET', '/api/v1/datasets/1/access-grants', [
      { id: 21, type: 'USER', subjectId: me.id, subjectName: me.name, grantedByName: me.name, grantedAt: '2026-10-07T10:00:00' },
      { id: 22, type: 'ROLE', subjectId: 5, subjectName: '인사팀', grantedByName: me.name, grantedAt: '2026-10-07T10:01:00' },
    ]);
    await mockApi(page, 'DELETE', '/api/v1/datasets/1/access-grants/21', {}, { status: 204 });
    await mockApi(page, 'GET', '/api/v1/datasets', { content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 });
    await page.goto('/data/datasets/1?tab=security');
    // 최근 접근 이력은 사용자·테넌트 단위 키에 저장된다 — 제거 전에는 기록돼 있어야 아래 단언이 공허하지 않다
    const recentKey = `sfh-recent-datasets:${me.id}:1`;
    await expect
      .poll(() => page.evaluate((k) => JSON.parse(localStorage.getItem(k) ?? '[]').map((d: { id: number }) => d.id), recentKey))
      .toContain(1);
    // 제거 이후의 상세 조회는 숨김 데이터셋과 같은 404 를 돌려준다(백엔드 규칙)
    await page.getByRole('button', { name: `${me.name} 제거` }).click();
    await mockApi(page, 'GET', '/api/v1/datasets/1', { message: 'not found' }, { status: 404 });
    await page.getByRole('alertdialog').getByRole('button', { name: '제거' }).click();
    await expect(page).toHaveURL(/\/data\/datasets$/);
    await expect(page.getByText('더 이상 이 데이터셋에 접근할 수 없습니다', { exact: false })).toBeVisible();
    await expect(page.getByText('데이터셋을 찾을 수 없습니다.')).toHaveCount(0);
    // 접근을 잃은 데이터셋은 최근 본 데이터셋 바로가기에서도 빠진다
    const recents = await page.evaluate((k) => JSON.parse(localStorage.getItem(k) ?? '[]'), recentKey);
    expect(recents.map((d: { id: number }) => d.id)).not.toContain(1);
  });

  test('본인이 가진 역할 항목 제거는 확인을 받고, 다른 항목은 바로 제거한다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setup(page, '기밀');
    // createAdminUserDetail 은 ADMIN 역할(id 2)을 가진다 — 같은 역할 항목은 본인 항목이다
    await mockApi(page, 'GET', '/api/v1/datasets/1/access-grants', [
      { id: 31, type: 'ROLE', subjectId: 2, subjectName: 'ADMIN', grantedByName: '관리자', grantedAt: '2026-10-07T10:00:00' },
      { id: 32, type: 'USER', subjectId: 99, subjectName: '김OO', grantedByName: '관리자', grantedAt: '2026-10-07T10:01:00' },
      { id: 33, type: 'ROLE', subjectId: 5, subjectName: '인사팀', grantedByName: '관리자', grantedAt: '2026-10-07T10:02:00' },
    ]);
    const delRole = await mockApi(page, 'DELETE', '/api/v1/datasets/1/access-grants/31', {}, { status: 204, capture: true });
    const delOther = await mockApi(page, 'DELETE', '/api/v1/datasets/1/access-grants/32', {}, { status: 204, capture: true });
    await page.goto('/data/datasets/1?tab=security');

    await page.getByRole('button', { name: 'ADMIN 제거' }).click();
    const confirm = page.getByRole('alertdialog');
    await expect(confirm).toContainText('본인이 속한 역할');
    await expect(confirm).toContainText('더 이상 접근할 수 없습니다');
    await confirm.getByRole('button', { name: '제거' }).click();
    await delRole.waitForRequest();

    await page.getByRole('button', { name: '김OO 제거' }).click();
    await delOther.waitForRequest();
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
  });

  test('허용 목록 추가 — 사용자 후보는 이메일로 구분되고 POST payload 는 userId', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setup(page, '기밀');
    await mockApi(page, 'GET', '/api/v1/datasets/1/access-grants', [
      { id: 11, type: 'ROLE', subjectId: 5, subjectName: '인사팀', grantedByName: '양동희', grantedAt: '2026-10-07T10:00:00' },
    ]);
    await mockApi(page, 'GET', '/api/v1/datasets/1/access-grants/candidates', {
      users: [
        { id: 99, name: '김OO', email: 'kim1@example.com' },
        { id: 100, name: '김OO', email: 'kim2@example.com' },
      ],
      roles: [{ id: 5, name: '인사팀' }],
    });
    const post = await mockApi(
      page, 'POST', '/api/v1/datasets/1/access-grants',
      { id: 40, type: 'USER', subjectId: 100, subjectName: '김OO', grantedByName: '관리자', grantedAt: '2026-10-08T10:00:00' },
      { capture: true },
    );
    await page.goto('/data/datasets/1?tab=security');
    await page.getByRole('button', { name: '추가', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '허용 목록 추가' });
    await dialog.getByRole('combobox', { name: '유형' }).click();
    await page.getByRole('option', { name: '사용자' }).click();
    await dialog.getByRole('combobox', { name: '대상' }).click();
    await page.getByRole('option', { name: '김OO (kim2@example.com)' }).click();
    await dialog.getByRole('button', { name: '추가' }).click();
    expect((await post.waitForRequest()).payload).toEqual({ userId: 100 });
    await expect(dialog).toHaveCount(0);
  });

  test('문서형 데이터셋에도 보안 탭이 있다', async ({ authenticatedPage: page }) => {
    await setup(page, '내부');
    await mockApi(page, 'GET', '/api/v1/datasets/1', createDatasetDetail({ id: 1, storageType: 'DOCUMENT', securityLevel: createLevelSummary('내부') }));
    await mockApi(page, 'GET', '/api/v1/datasets/1/documents', []);
    await page.goto('/data/datasets/1');
    await expect(page.getByRole('tab', { name: '보안' })).toBeVisible();
  });
});
