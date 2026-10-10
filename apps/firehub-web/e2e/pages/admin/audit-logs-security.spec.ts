import { createAuditLog } from '../../factories/admin.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 감사 로그 — 데이터셋 보안 등급 액션(WD-44)
 * - api 가 남기는 보안 감사 액션·리소스가 한글 라벨로 보이고 필터 쿼리로 그대로 실리는지 검증한다.
 * - 접근 거부 행의 상세 다이얼로그에 metadata 의 동작·사유가 한 줄 요약으로 보이는지 검증한다.
 */
const DENIED = createAuditLog({
  id: 1,
  username: 'kim',
  actionType: 'DATASET_ACCESS_DENIED',
  resource: 'dataset',
  resourceId: '7',
  result: 'FAILURE',
  description: 'VIEW 거부: CLEARANCE_INSUFFICIENT',
  metadata: { action: 'VIEW', reason: 'CLEARANCE_INSUFFICIENT' },
});
const AUTO_RAISE = createAuditLog({
  id: 2,
  username: 'lee',
  actionType: 'DATASET_SECURITY_LEVEL_AUTO_RAISE',
  resource: 'dataset',
  resourceId: '8',
  description: '파이프라인 입력 등급에 따른 자동 상향',
  metadata: { from: '내부', to: '민감' },
});
const SQL_DENIED = createAuditLog({
  id: 3,
  username: 'park',
  actionType: 'DATASET_ACCESS_DENIED',
  resource: 'dataset',
  resourceId: null,
  result: 'FAILURE',
  description: 'SQL 거부: NOT_ON_ALLOWLIST',
  metadata: { action: 'SQL', reason: 'NOT_ON_ALLOWLIST', tableName: 'hr_eval' },
});

// 감사 등급 접근 — kind 라벨과, 모르는 사유 코드의 원문 폴백 확인용(api 가 새 코드를 추가해도 깨지지 않게)
const ACCESS = createAuditLog({
  id: 4,
  username: 'choi',
  actionType: 'DATASET_ACCESS',
  resource: 'dataset',
  resourceId: '9',
  description: 'ROW_VIEW',
  metadata: { kind: 'ROW_VIEW' },
});
const UNKNOWN_REASON = createAuditLog({
  id: 5,
  username: 'jung',
  actionType: 'DATASET_ACCESS_DENIED',
  resource: 'dataset',
  resourceId: '10',
  result: 'FAILURE',
  metadata: { action: 'EXPORT', reason: 'FUTURE_CODE' },
});
// 요약 항목이 하나도 없는 거부 행 — 「접근 요약」 필드 자체를 그리지 않는다.
const EMPTY_META = createAuditLog({
  id: 6,
  username: 'han',
  actionType: 'DATASET_ACCESS_DENIED',
  resource: 'dataset',
  resourceId: '11',
  result: 'FAILURE',
  metadata: { other: 1 },
});

test.describe('감사 로그 — 보안 등급 액션', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse([DENIED, AUTO_RAISE, SQL_DENIED, ACCESS, UNKNOWN_REASON, EMPTY_META]),
    );
  });

  test('보안 액션·리소스가 영문 raw 대신 한글 라벨로 보인다', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/audit-logs');
    const deniedRow = page.getByRole('row').filter({ hasText: 'kim' });
    await expect(deniedRow.getByRole('cell', { name: '데이터셋 접근 거부', exact: true })).toBeVisible();
    const raiseRow = page.getByRole('row').filter({ hasText: 'lee' });
    await expect(raiseRow.getByRole('cell', { name: '보안 등급 자동 상향', exact: true })).toBeVisible();
    await expect(page.getByText('DATASET_ACCESS_DENIED')).toHaveCount(0);
    await expect(page.getByText('DATASET_SECURITY_LEVEL_AUTO_RAISE')).toHaveCount(0);
  });

  test('액션 필터에서 「데이터셋 접근 거부」를 고르면 actionType 쿼리가 실린다', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/audit-logs');
    await expect(page.getByRole('row').filter({ hasText: 'kim' })).toBeVisible();
    const req = page.waitForRequest(
      (r) =>
        new URL(r.url()).pathname === '/api/v1/admin/audit-logs' &&
        new URL(r.url()).searchParams.get('actionType') === 'DATASET_ACCESS_DENIED',
    );
    await page.getByRole('combobox', { name: '액션 유형 필터' }).click();
    // 보안 액션 12종이 모두 옵션에 있다(정확 일치 — '보안 등급 변경' 과 '기본 보안 등급 변경' 을 구분).
    for (const label of [
      '데이터셋 접근 거부',
      '감사 등급 데이터 접근',
      '보안 등급 변경',
      '보안 등급 자동 상향',
      '허용 목록 추가',
      '허용 목록 제거',
      '보안 등급 생성',
      '보안 등급 수정',
      '보안 등급 삭제',
      '보안 등급 순서 변경',
      '기본 보안 등급 변경',
      '역할 열람 등급 변경',
    ]) {
      await expect(page.getByRole('option', { name: label, exact: true })).toHaveCount(1);
    }
    await page.getByRole('option', { name: '데이터셋 접근 거부', exact: true }).click();
    await req;
  });

  test('액션 드롭다운은 일반·멤버 관리·데이터셋 보안·보안 설정 4그룹으로 나뉜다', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/audit-logs');
    await page.getByRole('combobox', { name: '액션 유형 필터' }).click();
    const group = (name: string) => page.getByRole('group', { name, exact: true });
    await expect(page.getByRole('group')).toHaveCount(4);
    await expect(group('일반').getByRole('option', { name: '생성', exact: true })).toHaveCount(1);
    await expect(group('일반').getByRole('option', { name: '도메인 수정', exact: true })).toHaveCount(1);
    await expect(group('멤버 관리').getByRole('option')).toHaveCount(4);
    await expect(group('데이터셋 보안').getByRole('option')).toHaveCount(6);
    await expect(group('데이터셋 보안').getByRole('option', { name: '감사 등급 데이터 접근', exact: true })).toHaveCount(1);
    await expect(group('보안 설정').getByRole('option')).toHaveCount(7);
    await expect(group('보안 설정').getByRole('option', { name: '역할 열람 등급 변경', exact: true })).toHaveCount(1);
    await expect(group('보안 설정').getByRole('option', { name: 'AI 호스팅 위치 변경', exact: true })).toHaveCount(1);
    // 긴 라벨을 고르면 트리거 값에 전체 라벨 title 이 붙는다(잘려도 확인 가능).
    await group('데이터셋 보안').getByRole('option', { name: '감사 등급 데이터 접근', exact: true }).click();
    const trigger = page.getByRole('combobox', { name: '액션 유형 필터' });
    await expect(trigger).toHaveText('감사 등급 데이터 접근');
    await expect(trigger.locator('[data-slot="select-value"]')).toHaveAttribute('title', '감사 등급 데이터 접근');
  });

  test('리소스 필터의 「보안 등급」·「쿼리 결과」 옵션이 resource 쿼리로 실린다', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/audit-logs');
    await expect(page.getByRole('row').filter({ hasText: 'kim' })).toBeVisible();
    const byResource = (value: string) =>
      page.waitForRequest(
        (r) =>
          new URL(r.url()).pathname === '/api/v1/admin/audit-logs' &&
          new URL(r.url()).searchParams.get('resource') === value,
      );

    let req = byResource('security_level');
    await page.getByRole('combobox', { name: '리소스 필터' }).click();
    await page.getByRole('option', { name: '보안 등급', exact: true }).click();
    await req;

    req = byResource('query_result');
    await page.getByRole('combobox', { name: '리소스 필터' }).click();
    await page.getByRole('option', { name: '쿼리 결과', exact: true }).click();
    await req;
  });

  test('접근 거부 상세의 「접근 요약」에 동작·사유(·테이블)가 한국어로 보인다', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/audit-logs');
    await page.getByRole('row').filter({ hasText: 'kim' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('접근 요약', { exact: true })).toBeVisible();
    await expect(dialog.getByTestId('audit-access-summary')).toHaveText('동작: 조회 · 사유: 열람 등급 부족');
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();

    await page.getByRole('row').filter({ hasText: 'park' }).click();
    await expect(dialog.getByTestId('audit-access-summary')).toHaveText(
      '동작: SQL · 사유: 허용 목록에 없음 · 테이블: hr_eval',
    );
  });

  test('감사 등급 접근은 종류 라벨, 모르는 사유 코드는 원문으로 보인다', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/audit-logs');
    const dialog = page.getByRole('dialog');
    await page.getByRole('row').filter({ hasText: 'choi' }).click();
    await expect(dialog.getByTestId('audit-access-summary')).toHaveText('종류: 행 조회');
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();

    await page.getByRole('row').filter({ hasText: 'jung' }).click();
    await expect(dialog.getByTestId('audit-access-summary')).toHaveText('동작: 내보내기 · 사유: FUTURE_CODE');
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();

    // 요약 항목이 없으면 「접근 요약」 필드 자체가 없다.
    await page.getByRole('row').filter({ hasText: 'han' }).click();
    await expect(dialog.getByText('Metadata')).toBeVisible();
    await expect(dialog.getByText('접근 요약', { exact: true })).toHaveCount(0);
  });

  test('보안 액션이 아닌 행에는 요약 줄이 없다', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/audit-logs');
    await page.getByRole('row').filter({ hasText: 'lee' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('Metadata')).toBeVisible();
    await expect(dialog.getByTestId('audit-access-summary')).toHaveCount(0);
  });
});

/**
 * 감사 로그 — AI 호스팅 위치 변경·멤버 관리 액션(WD-49)
 * - api 가 기록하던 AI_PROVIDER_HOSTING_CHANGE(resource ai_provider_hosting)·MEMBER_* 가 라벨 목록에 없어
 *   영문 raw 로 보이고 액션 필터로 찾을 수 없던 결함의 회귀 가드.
 */
const HOSTING = createAuditLog({
  id: 21,
  username: 'hostadm',
  actionType: 'AI_PROVIDER_HOSTING_CHANGE',
  resource: 'ai_provider_hosting',
  resourceId: 'EMBEDDING',
  description: 'AI 공급자 호스팅 선언 변경(EMBEDDING): EXTERNAL → SELF_HOSTED',
  metadata: { slot: 'EMBEDDING', from: 'EXTERNAL', to: 'SELF_HOSTED' },
});
const MEMBER_ADD = createAuditLog({
  id: 22,
  username: 'yoon',
  actionType: 'MEMBER_ADD',
  resource: 'user',
  resourceId: '42',
  description: '기존 계정을 멤버로 추가',
});
const MEMBER_SUSPEND = createAuditLog({ id: 23, username: 'kang', actionType: 'MEMBER_SUSPEND', resource: 'user' });
const MEMBER_REACTIVATE = createAuditLog({ id: 24, username: 'cho', actionType: 'MEMBER_REACTIVATE', resource: 'user' });
const MEMBER_REMOVE = createAuditLog({ id: 25, username: 'shin', actionType: 'MEMBER_REMOVE', resource: 'user' });

test.describe('감사 로그 — AI 호스팅 위치·멤버 관리 액션(WD-49)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse([HOSTING, MEMBER_ADD, MEMBER_SUSPEND, MEMBER_REACTIVATE, MEMBER_REMOVE]),
    );
  });

  test('호스팅 변경·멤버 액션과 리소스가 영문 raw 대신 한글 라벨로 보인다', async ({ authenticatedPage: page }) => {
    // 미등록 값은 console.warn 을 남긴다 — 경고가 하나도 없어야 전부 등록된 것이다.
    const warnings: string[] = [];
    page.on('console', (m) => {
      if (m.type() === 'warning' && m.text().startsWith('[AuditLog]')) warnings.push(m.text());
    });
    await page.goto('/admin/audit-logs');
    const row = (name: string) => page.getByRole('row').filter({ hasText: name });
    await expect(row('hostadm').getByRole('cell', { name: 'AI 호스팅 위치 변경', exact: true })).toBeVisible();
    await expect(row('hostadm').getByRole('cell', { name: 'AI 호스팅 위치', exact: true })).toBeVisible();
    await expect(row('yoon').getByRole('cell', { name: '멤버 추가', exact: true })).toBeVisible();
    await expect(row('yoon').getByRole('cell', { name: '사용자', exact: true })).toBeVisible();
    await expect(row('kang').getByRole('cell', { name: '멤버십 정지', exact: true })).toBeVisible();
    await expect(row('cho').getByRole('cell', { name: '멤버십 재활성화', exact: true })).toBeVisible();
    await expect(row('shin').getByRole('cell', { name: '멤버 제거', exact: true })).toBeVisible();
    for (const raw of ['AI_PROVIDER_HOSTING_CHANGE', 'ai_provider_hosting', 'MEMBER_ADD', 'MEMBER_SUSPEND']) {
      await expect(page.getByRole('cell', { name: raw, exact: true })).toHaveCount(0);
    }
    expect(warnings).toEqual([]);
  });

  test('상세 다이얼로그도 호스팅 변경 액션·리소스를 한글로 보인다', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/audit-logs');
    await page.getByRole('row').filter({ hasText: 'hostadm' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('AI 호스팅 위치 변경', { exact: true })).toBeVisible();
    await expect(dialog.getByText('AI 호스팅 위치 (EMBEDDING)', { exact: true })).toBeVisible();
    await expect(dialog.getByText('AI_PROVIDER_HOSTING_CHANGE', { exact: true })).toHaveCount(0);
  });

  test('액션 필터에서 「AI 호스팅 위치 변경」·「멤버 추가」를 고르면 actionType 쿼리가 실린다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/admin/audit-logs');
    await expect(page.getByRole('row').filter({ hasText: 'hostadm' })).toBeVisible();
    const byAction = (value: string) =>
      page.waitForRequest(
        (r) =>
          new URL(r.url()).pathname === '/api/v1/admin/audit-logs' &&
          new URL(r.url()).searchParams.get('actionType') === value,
      );
    const group = (name: string) => page.getByRole('group', { name, exact: true });

    let req = byAction('AI_PROVIDER_HOSTING_CHANGE');
    await page.getByRole('combobox', { name: '액션 유형 필터' }).click();
    await group('보안 설정').getByRole('option', { name: 'AI 호스팅 위치 변경', exact: true }).click();
    await req;

    req = byAction('MEMBER_ADD');
    await page.getByRole('combobox', { name: '액션 유형 필터' }).click();
    for (const label of ['멤버 추가', '멤버십 정지', '멤버십 재활성화', '멤버 제거']) {
      await expect(group('멤버 관리').getByRole('option', { name: label, exact: true })).toHaveCount(1);
    }
    await group('멤버 관리').getByRole('option', { name: '멤버 추가', exact: true }).click();
    await req;
  });

  test('리소스 필터의 「AI 호스팅 위치」 옵션이 resource 쿼리로 실린다', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/audit-logs');
    await expect(page.getByRole('row').filter({ hasText: 'hostadm' })).toBeVisible();
    const req = page.waitForRequest(
      (r) =>
        new URL(r.url()).pathname === '/api/v1/admin/audit-logs' &&
        new URL(r.url()).searchParams.get('resource') === 'ai_provider_hosting',
    );
    await page.getByRole('combobox', { name: '리소스 필터' }).click();
    await page.getByRole('option', { name: 'AI 호스팅 위치', exact: true }).click();
    await req;
  });
});
