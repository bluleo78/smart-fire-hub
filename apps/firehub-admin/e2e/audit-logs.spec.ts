import { ALL_PLATFORM_PERMISSIONS, createAuditLog, createAuditPage } from './factories/platform.factory';
import { mockApi } from './fixtures/api-mock';
import { expect, loginAs, test } from './fixtures/auth.fixture';

const PATH = '/api/platform/audit-logs';

test.describe('플랫폼 감사 로그 (WD-4)', () => {
  // WD-11: 기간·표시는 브라우저 로컬 하루 기준 — KST 로 고정해 경계 값을 결정적으로 단언한다.
  test.use({ timezoneId: 'Asia/Seoul' });

  test('KST 운영자: UTC 15:30 기록은 다음 날 00:30 으로 보이고, 하루 필터는 KST 자정 경계를 보낸다 (WD-11)', async ({ authenticatedPage: page }) => {
    const capture = await mockApi(page, 'GET', PATH, createAuditPage([createAuditLog({ actionTime: '2026-09-30T15:30:00Z' })]), { capture: true });
    await page.goto('/audit-logs');

    const table = page.getByRole('table', { name: '플랫폼 감사 로그' });
    await expect(table.getByRole('cell', { name: '2026-10-01 00:30:00' })).toBeVisible();

    await page.getByLabel('시작일').fill('2026-10-01');
    await page.getByLabel('종료일').fill('2026-10-01');
    await expect.poll(() => {
      const p = capture.requests.at(-1)?.searchParams;
      return p && `${p.get('from')}|${p.get('to')}`;
    }).toBe('2026-09-30T15:00:00.000Z|2026-10-01T15:00:00.000Z');
  });

  test('목록: 시간·행위자·액션·대상·설명·결과를 그리고 내비가 활성이다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', PATH, createAuditPage([
      createAuditLog(),
      createAuditLog({ id: 502, actionType: 'LOGIN', username: 'lee@example.com', resource: 'auth', resourceId: null, description: '로그인 성공', metadata: null, actionTime: '2026-09-30T23:00:05Z' }),
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

  test('결과 배지는 성공·실패 무게가 같다(둘 다 옅은 틴트) (WD-15)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', PATH, createAuditPage([createAuditLog(), createAuditLog({ id: 502, result: 'FAILURE' })]));
    await page.goto('/audit-logs');
    const table = page.getByRole('table', { name: '플랫폼 감사 로그' });
    const fail = table.getByText('실패', { exact: true });
    await expect(fail).toHaveAttribute('data-status', 'error');
    // 꽉 찬 빨강(bg-destructive + 흰 글자)이 아니라 성공과 같은 /10 틴트다
    await expect(fail).not.toHaveClass(/(^|\s)bg-destructive(\s|$)/);
    await expect(fail).toHaveClass(/bg-destructive\/10/);
    await expect(table.getByText('성공', { exact: true })).toHaveClass(/bg-success\/10/);
  });

  test('필터 결과가 비면 "필터 초기화" 로 모든 필터를 지운다 (WD-15)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', PATH, createAuditPage([]));
    await page.goto('/audit-logs');
    await expect(page.getByText('감사 로그가 없습니다.')).toBeVisible();
    await expect(page.getByRole('button', { name: '필터 초기화' })).toHaveCount(0);

    await page.getByLabel('시작일').fill('2026-09-01');
    await page.getByLabel('행위자').fill('ops');
    await expect(page.getByText('조건에 맞는 감사 로그가 없습니다.')).toBeVisible();
    await page.getByRole('button', { name: '필터 초기화' }).click();
    // 화면 상태로 단언한다 — 초기화 후 params 가 최초 조회와 같아져 staleTime(30s) 안에서는 캐시 적중으로 새 요청이
    // 나가지 않으므로 "마지막 요청에 from 이 없다" 는 단언은 성립하지 않는다(이 파일의 page=0 테스트 주석 참고).
    await expect(page.getByLabel('시작일')).toHaveValue('');
    await expect(page.getByLabel('행위자')).toHaveValue('');
    await expect(page.getByText('감사 로그가 없습니다.')).toBeVisible();
    await expect(page.getByRole('button', { name: '필터 초기화' })).toHaveCount(0);
  });

  test('대상 입력 안내는 slug 대신 "식별자" 라고 말하고 잘리지 않는 폭이다 (WD-15)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', PATH, createAuditPage([]));
    await page.goto('/audit-logs');
    const target = page.getByLabel('대상');
    await expect(target).toHaveAttribute('placeholder', '아이디·테넌트 이름·식별자 일부');
    // 안내 문구 전체가 보이는지: 빈 입력의 scrollWidth 는 placeholder 를 재지 않으므로(w-48 에서도 통과 — 실측),
    // 입력의 실제 글꼴로 placeholder 폭을 재서 패딩을 뺀 글자 영역과 비교한다.
    const fits = await target.evaluate(async (el: HTMLInputElement) => {
      await document.fonts.ready;
      const cs = getComputedStyle(el);
      const ctx = document.createElement('canvas').getContext('2d')!;
      ctx.font = cs.font;
      const box = el.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
      return ctx.measureText(el.placeholder).width <= box;
    });
    expect(fits).toBe(true);
  });

  test('필터: 행위자·대상·액션·기간(로컬 자정 순간)을 쿼리로 보내고 페이지를 0 으로 되돌린다', async ({ authenticatedPage: page }) => {
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
    }).toBe('ops|kim@|ACCOUNT_DEACTIVATE|2026-08-31T15:00:00.000Z|2026-09-30T15:00:00.000Z|0|20');
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
    await expect.poll(() => capture.requests.at(-1)?.searchParams.get('to')).toBe('2026-10-01T15:00:00.000Z');
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
    await expect.poll(() => capture.requests.at(-1)?.searchParams.get('to')).toBe('2026-10-03T15:00:00.000Z');
    const inverted = capture.requests.filter((r) => {
      const p = r.searchParams;
      return p.get('from') && p.get('to') && p.get('from')! >= p.get('to')!;
    });
    expect(inverted).toHaveLength(0);
  });

  test('목록 조회가 403 이 아닌 오류로 실패하면 표에 실패 문구를 보인다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', PATH, { status: 500, error: 'Internal Server Error', message: 'boom' }, { status: 500 });
    await page.goto('/audit-logs');
    await expect(page.getByText('데이터를 불러오는데 실패했습니다.')).toBeVisible();
    await expect(page.getByText('이 작업을 수행할 권한이 없습니다.')).toHaveCount(0);
  });

  test('테넌트 생명주기 행: 액션 라벨·대상(이름·slug)·액션 필터 (WD-12)', async ({ authenticatedPage: page }) => {
    const capture = await mockApi(page, 'GET', PATH, createAuditPage([
      createAuditLog({
        id: 601,
        actionType: 'TENANT_SUSPEND',
        resource: 'tenant',
        resourceId: '42',
        description: '테넌트 정지 처리',
        metadata: { plane: 'platform', tenantSlug: 'seoul', tenantName: '서울소방청' },
      }),
      createAuditLog({
        id: 602,
        actionType: 'TENANT_CREATE',
        resource: 'tenant',
        resourceId: '43',
        description: '테넌트 생성 처리',
        metadata: { plane: 'platform', tenantSlug: 'busan', tenantName: '부산소방청' },
      }),
      createAuditLog({
        id: 603,
        actionType: 'TENANT_ACTIVATE',
        resource: 'tenant',
        resourceId: '44',
        description: '테넌트 활성화 처리',
        metadata: { plane: 'platform', tenantSlug: 'daegu', tenantName: '대구소방청' },
      }),
    ]), { capture: true });
    await page.goto('/audit-logs');

    // 행은 대상 셀로 찾고, 액션 칸(시간·행위자·액션·대상 → 3번째)을 열 위치로 단언한다 — 설명 칸 문구와 섞이지 않게.
    const row = page.getByRole('table', { name: '플랫폼 감사 로그' }).getByRole('row').filter({ hasText: '서울소방청 (seoul)' });
    await expect(row.getByRole('cell').nth(2)).toHaveText('테넌트 정지');
    const rowOf = (target: string) =>
      page.getByRole('table', { name: '플랫폼 감사 로그' }).getByRole('row').filter({ hasText: target });
    await expect(rowOf('부산소방청 (busan)').getByRole('cell').nth(2)).toHaveText('테넌트 생성');
    await expect(rowOf('대구소방청 (daegu)').getByRole('cell').nth(2)).toHaveText('테넌트 활성화');
    // 숫자 id(42)가 아니라 이름·slug 로 보인다
    await expect(row.getByRole('cell', { name: '서울소방청 (seoul)' })).toBeVisible();

    await page.getByRole('combobox', { name: '액션' }).click();
    await page.getByRole('option', { name: '테넌트 생성' }).click();
    await expect.poll(() => capture.requests.at(-1)?.searchParams.get('actionType')).toBe('TENANT_CREATE');
  });

  test('계정 생성 행: "계정 생성" 라벨·대상 username·액션 필터 (WD-46)', async ({ authenticatedPage: page }) => {
    const capture = await mockApi(page, 'GET', PATH, createAuditPage([
      createAuditLog({
        id: 701,
        actionType: 'ACCOUNT_CREATE',
        resourceId: '77',
        description: '소속 없는 계정 생성(임시 비밀번호, 첫 로그인 시 변경 강제)',
        metadata: { plane: 'platform', targetUsername: 'new@example.com' },
      }),
    ]), { capture: true });
    await page.goto('/audit-logs');

    const row = page.getByRole('table', { name: '플랫폼 감사 로그' }).getByRole('row').filter({ hasText: 'new@example.com' });
    await expect(row.getByRole('cell').nth(2)).toHaveText('계정 생성');

    await page.getByRole('combobox', { name: '액션' }).click();
    await page.getByRole('option', { name: '계정 생성', exact: true }).click();
    await expect.poll(() => capture.requests.at(-1)?.searchParams.get('actionType')).toBe('ACCOUNT_CREATE');
  });

  test('모르는 액션은 원문 그대로, 빈 결과는 빈 상태 문구', async ({ authenticatedPage: page }) => {
    // WD-12 로 TENANT_SUSPEND 는 라벨이 생겼다 — 매핑에 없을 값으로 원문 폴백을 지킨다.
    await mockApi(page, 'GET', PATH, createAuditPage([createAuditLog({ actionType: 'PLATFORM_UNMAPPED_ACTION' })]));
    await page.goto('/audit-logs');
    await expect(page.getByRole('cell', { name: 'PLATFORM_UNMAPPED_ACTION' })).toBeVisible();

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
