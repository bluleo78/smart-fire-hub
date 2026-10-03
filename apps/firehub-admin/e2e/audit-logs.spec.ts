import { ALL_PLATFORM_PERMISSIONS, createAuditLog, createAuditPage } from './factories/platform.factory';
import { mockApi } from './fixtures/api-mock';
import { expect, loginAs, test } from './fixtures/auth.fixture';

const PATH = '/api/platform/audit-logs';

test.describe('플랫폼 감사 로그 (WD-4)', () => {
  test('목록: 시간·행위자·액션·대상·설명·결과를 그리고 내비가 활성이다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', PATH, createAuditPage([
      createAuditLog(),
      createAuditLog({ id: 502, actionType: 'LOGIN', username: 'lee@example.com', resource: 'auth', resourceId: null, description: '로그인 성공', metadata: null, actionTime: '2026-10-01T08:00:05' }),
    ]));
    await page.goto('/audit-logs');

    await expect(page.getByRole('link', { name: '감사 로그' })).toHaveAttribute('aria-current', 'page');
    await expect(page.getByRole('heading', { name: '감사 로그' })).toBeVisible();
    const table = page.getByRole('table', { name: '플랫폼 감사 로그' });
    const row = table.getByRole('row').filter({ hasText: '계정 비활성화' });
    await expect(row.getByRole('cell', { name: '2026-10-01 09:15:30' })).toBeVisible();
    await expect(row.getByRole('cell', { name: 'ops@example.com' })).toBeVisible();
    await expect(row.getByRole('cell', { name: 'kim@example.com' })).toBeVisible();
    await expect(row.getByText('성공', { exact: true })).toHaveAttribute('data-status', 'success');
    // 대상 metadata 가 없는 로그인 행은 대상 '-' — 행위자 셀로 모호하지 않게 행을 고른다.
    const login = table.getByRole('row').filter({ has: page.getByRole('cell', { name: 'lee@example.com', exact: true }) });
    await expect(login.getByRole('cell', { name: '-', exact: true })).toBeVisible();
  });

  test('필터: 행위자·대상·액션·기간(날짜만)을 쿼리로 보내고 페이지를 0 으로 되돌린다', async ({ authenticatedPage: page }) => {
    const capture = await mockApi(page, 'GET', PATH, createAuditPage([createAuditLog()], { totalPages: 3, totalElements: 50 }), { capture: true });
    await page.goto('/audit-logs');
    await capture.waitForRequest();

    await page.getByRole('button', { name: '다음 페이지' }).click();
    await expect.poll(() => capture.requests.at(-1)?.searchParams.get('page')).toBe('1');

    await page.getByLabel('행위자').fill('ops');
    await page.getByLabel('대상').fill('kim@');
    await page.getByRole('combobox', { name: '액션' }).click();
    await page.getByRole('option', { name: '계정 비활성화' }).click();
    await page.getByLabel('시작일').fill('2026-09-01');
    await page.getByLabel('종료일').fill('2026-09-30');

    await expect.poll(() => {
      const p = capture.requests.at(-1)?.searchParams;
      return p && [p.get('actor'), p.get('target'), p.get('actionType'), p.get('from'), p.get('to'), p.get('page'), p.get('size')].join('|');
    }).toBe('ops|kim@|ACCOUNT_DEACTIVATE|2026-09-01|2026-09-30|0|20');
  });

  test('2페이지에서 행위자를 입력해도 이전 필터로 page=0 요청이 나가지 않는다', async ({ authenticatedPage: page }) => {
    // 전역 staleTime(30s) 안에서는 "이전 필터 + page 0" 이 캐시 적중이라 요청이 안 나가 결함이 가려진다 —
    // 시계를 앞당겨 캐시를 낡게 만든 뒤 입력한다.
    await page.clock.install();
    const capture = await mockApi(page, 'GET', PATH, createAuditPage([createAuditLog()], { totalPages: 3, totalElements: 50 }), { capture: true });
    await page.goto('/audit-logs');
    await capture.waitForRequest();
    await page.getByRole('button', { name: '다음 페이지' }).click();
    await expect.poll(() => capture.requests.at(-1)?.searchParams.get('page')).toBe('1');
    await page.clock.fastForward(31_000);

    await page.getByLabel('행위자').fill('ops');
    await expect.poll(() => {
      const p = capture.requests.at(-1)?.searchParams;
      return p && `${p.get('actor')}|${p.get('page')}`;
    }).toBe('ops|0');
    // 디바운스 전에 "이전 필터(actor 없음) + page=0" 요청이 한 번 더 나가면 안 된다(page=0 은 새 필터로만).
    // 첫 요청(초기 로드)도 actor 없음+page=0 이므로 제외한다.
    const stale = capture.requests.slice(1).filter((r) => r.searchParams.get('page') === '0' && !r.searchParams.get('actor'));
    expect(stale).toHaveLength(0);
  });

  test('시작일이 종료일보다 늦으면 조회하지 않고 안내한다', async ({ authenticatedPage: page }) => {
    const capture = await mockApi(page, 'GET', PATH, createAuditPage([]), { capture: true });
    await page.goto('/audit-logs');
    await capture.waitForRequest();

    // 종료일만 있는 상태는 정상 필터라 요청이 나간다 — 그 요청을 확인한 뒤에 기준 개수를 잡는다(고정 대기 금지).
    await page.getByLabel('종료일').fill('2026-10-01');
    await expect.poll(() => capture.requests.at(-1)?.searchParams.get('to')).toBe('2026-10-01');
    await page.getByLabel('시작일').fill('2026-10-02');

    await expect(page.getByRole('alert').filter({ hasText: '시작일이 종료일보다 늦습니다. 기간을 다시 고르세요.' })).toBeVisible();
    await expect(page.getByLabel('시작일')).toHaveAttribute('max', '2026-10-01');
    await expect(page.getByLabel('종료일')).toHaveAttribute('min', '2026-10-02');
    await expect(page.getByText('기간을 고치면 결과가 표시됩니다.')).toBeVisible();
    // "결과 없음" 과 섞이지 않는다(웹 #541 과 같은 구분)
    await expect(page.getByText('감사 로그가 없습니다.')).toHaveCount(0);

    // 양성 대조: 역전을 풀면 새 요청이 실제로 나간다. 요청은 발생 순서대로 기록되므로, 역전 상태에서
    // 요청이 나갔다면(enabled 가드가 없다면) 이 대조 요청보다 앞에 from > to 로 남는다.
    await page.getByLabel('종료일').fill('2026-10-03');
    await expect.poll(() => capture.requests.at(-1)?.searchParams.get('to')).toBe('2026-10-03');
    const inverted = capture.requests.filter((r) => {
      const p = r.searchParams;
      return p.get('from') && p.get('to') && p.get('from')! > p.get('to')!;
    });
    expect(inverted).toHaveLength(0);
  });

  test('목록 조회가 403 이 아닌 오류로 실패하면 표에 실패 문구를 보인다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', PATH, { status: 500, error: 'Internal Server Error', message: 'boom' }, { status: 500 });
    await page.goto('/audit-logs');
    await expect(page.getByText('데이터를 불러오는데 실패했습니다.')).toBeVisible();
    await expect(page.getByText('이 작업을 수행할 권한이 없습니다.')).toHaveCount(0);
  });

  test('모르는 액션은 원문 그대로, 빈 결과는 빈 상태 문구', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', PATH, createAuditPage([createAuditLog({ actionType: 'TENANT_SUSPEND' })]));
    await page.goto('/audit-logs');
    await expect(page.getByRole('cell', { name: 'TENANT_SUSPEND' })).toBeVisible();

    await mockApi(page, 'GET', PATH, createAuditPage([]));
    await page.getByLabel('행위자').fill('nobody');
    await expect(page.getByText('감사 로그가 없습니다.')).toBeVisible();
  });

  test('403 이면 권한 없음 배너로 본문을 바꾼다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', PATH, { status: 403, error: 'Forbidden', message: 'Forbidden' }, { status: 403 });
    await page.goto('/audit-logs');
    await expect(page.getByText('이 작업을 수행할 권한이 없습니다.')).toBeVisible();
  });

  test('platform:member:read 가 없으면 내비에 감사 로그가 없고 직접 진입은 권한 안내', async ({ page }) => {
    await loginAs(page, ALL_PLATFORM_PERMISSIONS.filter((p) => p !== 'platform:member:read'));
    await page.goto('/tenants');
    // 셸이 실제로 그려진 뒤에만 "없음" 이 의미를 갖는다.
    await expect(page.getByRole('navigation', { name: '주요 메뉴' })).toBeVisible();
    await expect(page.getByRole('link', { name: '테넌트' })).toBeVisible();
    await expect(page.getByRole('link', { name: '감사 로그' })).toHaveCount(0);

    await page.goto('/audit-logs');
    await expect(page.getByRole('heading', { name: '감사 로그' })).toBeVisible(); // ProtectedRoute deniedTitle
    await expect(page.getByText('이 작업을 수행할 권한이 없습니다.')).toBeVisible();
  });
});
