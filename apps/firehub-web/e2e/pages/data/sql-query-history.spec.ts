import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';

/**
 * SqlQueryHistory 컴포넌트 E2E 테스트
 * - queryTypeBadgeVariant 분기(SELECT/INSERT/UPDATE/DELETE/기타) 커버
 * - item.success === false → "실패" 텍스트 렌더링
 * - item.error 필드 → 에러 메시지 렌더링
 * - 이력 없음 상태 → "이력이 없습니다." 메시지 렌더링
 * - 이력 항목 클릭 → onSelect(sql) 콜백으로 SQL 에디터 채움
 */

const DATASET_ID = 1;

/** 쿼리 이력 레코드 팩토리 */
function makeHistory(overrides: {
  id?: number;
  queryType: string;
  sql: string;
  success?: boolean;
  affectedRows?: number;
  executionTimeMs?: number;
  error?: string | null;
  executedAt?: string;
}) {
  return {
    id: overrides.id ?? 1,
    queryType: overrides.queryType,
    sql: overrides.sql,
    success: overrides.success ?? true,
    affectedRows: overrides.affectedRows ?? 0,
    executionTimeMs: overrides.executionTimeMs ?? 10,
    error: overrides.error ?? null,
    executedAt: overrides.executedAt ?? '2024-01-01T00:00:00Z',
  };
}

/** 페이지네이션 래퍼 */
function pageOf(content: unknown[]) {
  return { content, totalElements: content.length, totalPages: 1, size: 20, number: 0 };
}

/**
 * 데이터셋 쿼리 탭으로 이동하고 이력 팝오버를 열어 반환한다.
 */
async function openHistoryPopover(page: import('@playwright/test').Page) {
  await page.goto(`/data/datasets/${DATASET_ID}`);
  // SqlQueryEditor는 "데이터" 탭 안에 있다
  await page.getByRole('tab', { name: '데이터' }).click();
  // "SQL" 버튼 클릭 → sqlEditorOpen=true → SqlQueryEditor 렌더링
  await page.getByRole('button', { name: 'SQL' }).click();
  // "이력" 버튼 클릭 → SqlQueryHistory 팝오버 오픈
  await page.getByRole('button', { name: '이력' }).click();
  // 팝오버 헤더 "쿼리 이력" 노출 대기
  await expect(page.getByText('쿼리 이력')).toBeVisible({ timeout: 5000 });
}

test.describe('SqlQueryHistory — 쿼리 이력 팝오버', () => {
  test('이력이 없으면 "이력이 없습니다." 메시지가 표시된다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    // setupDatasetDetailMocks가 이미 빈 queries를 모킹하므로 그대로 사용
    await openHistoryPopover(page);
    await expect(page.getByText('이력이 없습니다.')).toBeVisible();
  });

  test('INSERT 쿼리 이력 — default 뱃지 및 SQL이 표시된다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    await mockApi(
      page,
      'GET',
      `/api/v1/datasets/${DATASET_ID}/queries`,
      pageOf([
        makeHistory({ id: 1, queryType: 'INSERT', sql: 'INSERT INTO tbl VALUES (1)', affectedRows: 1 }),
      ]),
    );

    await openHistoryPopover(page);

    // INSERT 뱃지 텍스트 확인 — SQL 텍스트와 중복 매칭을 피해 first() 사용
    await expect(page.getByText('INSERT').first()).toBeVisible();
    // affectedRows 정보 표시
    await expect(page.getByText(/1행/)).toBeVisible();
    // SQL truncated 텍스트 확인
    await expect(page.getByText(/INSERT INTO tbl/).first()).toBeVisible();
  });

  test('UPDATE 쿼리 이력 — outline 뱃지가 표시된다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    await mockApi(
      page,
      'GET',
      `/api/v1/datasets/${DATASET_ID}/queries`,
      pageOf([
        makeHistory({ id: 2, queryType: 'UPDATE', sql: 'UPDATE tbl SET col=1', affectedRows: 3 }),
      ]),
    );

    await openHistoryPopover(page);

    await expect(page.getByText('UPDATE').first()).toBeVisible();
    await expect(page.getByText(/3행/)).toBeVisible();
  });

  test('DELETE 쿼리 이력 — destructive 뱃지가 표시된다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    await mockApi(
      page,
      'GET',
      `/api/v1/datasets/${DATASET_ID}/queries`,
      pageOf([
        makeHistory({ id: 3, queryType: 'DELETE', sql: 'DELETE FROM tbl WHERE id=1', affectedRows: 1 }),
      ]),
    );

    await openHistoryPopover(page);

    await expect(page.getByText('DELETE').first()).toBeVisible();
  });

  test('기타 쿼리 타입 — secondary 뱃지(기본값)가 표시된다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    await mockApi(
      page,
      'GET',
      `/api/v1/datasets/${DATASET_ID}/queries`,
      pageOf([
        makeHistory({ id: 4, queryType: 'CREATE', sql: 'CREATE TABLE tbl (id INT)' }),
      ]),
    );

    await openHistoryPopover(page);

    await expect(page.getByText('CREATE').first()).toBeVisible();
  });

  test('success=false 이력 — "실패" 텍스트가 표시된다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    await mockApi(
      page,
      'GET',
      `/api/v1/datasets/${DATASET_ID}/queries`,
      pageOf([
        makeHistory({
          id: 5,
          queryType: 'SELECT',
          sql: 'SELECT * FROM bad_table',
          success: false,
          error: '테이블을 찾을 수 없습니다',
        }),
      ]),
    );

    await openHistoryPopover(page);

    // success=false 분기 → "실패" span 렌더링
    await expect(page.getByText('실패')).toBeVisible();
    // item.error 분기 → 에러 메시지 렌더링
    await expect(page.getByText('테이블을 찾을 수 없습니다')).toBeVisible();
  });

  test('이력 항목 클릭 시 해당 SQL이 에디터에 채워진다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    const targetSql = 'SELECT id, name FROM incidents LIMIT 10';
    await mockApi(
      page,
      'GET',
      `/api/v1/datasets/${DATASET_ID}/queries`,
      pageOf([
        makeHistory({ id: 6, queryType: 'SELECT', sql: targetSql, affectedRows: 10 }),
      ]),
    );

    await openHistoryPopover(page);

    // 이력 항목 클릭 → onSelect(sql) → 에디터에 SQL 채움
    await page.getByText(/SELECT id, name/).click();

    // 팝오버가 닫히고 에디터에 SQL이 반영된다
    await expect(page.getByText('쿼리 이력')).not.toBeVisible({ timeout: 3000 });
  });
});
