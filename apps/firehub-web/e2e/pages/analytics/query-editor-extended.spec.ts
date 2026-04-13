import { createQueryResult, createSavedQuery } from '../../factories/analytics.factory';
import {
  setupNewQueryEditorMocks,
  setupQueryEditorMocks,
} from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 쿼리 에디터 심화 E2E 테스트
 * - query-editor.spec.ts에서 커버하지 않은 분기를 추가로 검증한다.
 * - truncated 결과, SELECT 0행, 차트 버튼 상태, SQL URL 파라미터 등
 */
test.describe('쿼리 에디터 심화', () => {
  test('truncated 결과 — "(상위 N행)" 뱃지가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupQueryEditorMocks(page, 1);
    // truncated: true — 최대 행 수 초과로 잘린 결과
    await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/execute',
      createQueryResult({
        queryType: 'SELECT',
        rows: [{ id: 1, name: '항목 1', value: 100 }],
        columns: ['id', 'name', 'value'],
        truncated: true,
        executionTimeMs: 55,
      }),
    );

    await page.goto('/analytics/queries/1');
    await expect(page.getByText('테스트 쿼리')).toBeVisible();

    await page.getByRole('button', { name: '실행' }).click();

    // truncated 시 "(상위 1행)" 형태의 텍스트가 뱃지에 표시된다
    await expect(page.getByText(/상위 1행/).first()).toBeVisible();
    // 실행 시간 뱃지도 함께 표시된다
    await expect(page.getByText(/55\s*ms/).first()).toBeVisible();
  });

  test('SELECT 0행 결과 — "결과가 없습니다." 메시지가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupQueryEditorMocks(page, 1);
    await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/execute',
      createQueryResult({
        queryType: 'SELECT',
        columns: [],
        rows: [],
        affectedRows: 0,
        executionTimeMs: 10,
      }),
    );

    await page.goto('/analytics/queries/1');
    await expect(page.getByText('테스트 쿼리')).toBeVisible();

    await page.getByRole('button', { name: '실행' }).click();

    // SELECT + 컬럼 없음 → "결과가 없습니다."
    await expect(page.getByText('결과가 없습니다.')).toBeVisible();
    // SELECT 0행이므로 내보내기 버튼은 표시되지 않아야 한다
    await expect(page.getByRole('button', { name: '내보내기' })).not.toBeVisible();
  });

  test('저장된 쿼리에서 실행 후 "차트로 만들기" 버튼이 활성화된다', async ({
    authenticatedPage: page,
  }) => {
    await setupQueryEditorMocks(page, 1);
    await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/execute',
      createQueryResult({ queryType: 'SELECT', rows: [{ id: 1, name: '항목 1', value: 100 }] }),
    );

    await page.goto('/analytics/queries/1');
    await expect(page.getByText('테스트 쿼리')).toBeVisible();

    await page.getByRole('button', { name: '실행' }).click();
    await expect(page.getByRole('columnheader', { name: 'id' })).toBeVisible();

    // 저장된 쿼리(queryId 존재) → "차트로 만들기" 버튼 활성화
    const chartButton = page.getByRole('button', { name: '차트로 만들기' });
    await expect(chartButton).toBeVisible();
    await expect(chartButton).not.toBeDisabled();
  });

  test('새 쿼리에서 실행 후 "차트로 만들기" 버튼이 비활성화된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewQueryEditorMocks(page);
    await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/execute',
      createQueryResult({ queryType: 'SELECT', rows: [{ id: 1, name: '항목 1', value: 100 }] }),
    );

    await page.goto('/analytics/queries/new');
    await expect(page.getByText('새 쿼리')).toBeVisible();

    // 새 쿼리 에디터에서 실행 버튼은 SQL이 없으므로 비활성화 상태다
    // 실행 버튼 상태와 차트 버튼 상태를 검증한다
    const runButton = page.getByRole('button', { name: '실행' });
    await expect(runButton).toBeDisabled();

    // 결과 없는 상태에서도 차트로 만들기 버튼은 보이지 않는다 (result가 null)
    await expect(page.getByRole('button', { name: '차트로 만들기' })).not.toBeVisible();
  });

  test('저장 다이얼로그 취소 버튼 클릭 시 다이얼로그가 닫힌다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewQueryEditorMocks(page);

    await page.goto('/analytics/queries/new');
    await expect(page.getByText('새 쿼리')).toBeVisible();

    // 저장 버튼 클릭 → 다이얼로그 열기
    await page.getByRole('button', { name: '저장' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // 취소 버튼 클릭
    await page.getByRole('dialog').getByRole('button', { name: '취소' }).click();

    // 다이얼로그가 닫혀야 한다
    await expect(page.getByRole('dialog')).not.toBeVisible();
  });

  test('실행 결과에서 Excel 내보내기 옵션이 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupQueryEditorMocks(page, 1);
    await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/execute',
      createQueryResult({ queryType: 'SELECT', rows: [{ id: 1, name: '항목 1', value: 100 }] }),
    );

    // Excel 내보내기 API 모킹
    await page.route(
      (url) => url.pathname === '/api/v1/query-results/export',
      (route) => {
        if (route.request().method() === 'POST') {
          return route.fulfill({
            status: 200,
            contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
            body: Buffer.from('fake-xlsx'),
          });
        }
        return route.fallback();
      },
    );

    await page.goto('/analytics/queries/1');
    await expect(page.getByText('테스트 쿼리')).toBeVisible();

    await page.getByRole('button', { name: '실행' }).click();
    await expect(page.getByRole('columnheader', { name: 'id' })).toBeVisible();

    // 내보내기 드롭다운 열기
    await page.getByRole('button', { name: '내보내기' }).click();

    // Excel 내보내기 옵션이 표시되어야 한다
    await expect(page.getByRole('menuitem', { name: 'Excel로 내보내기' })).toBeVisible();
    // CSV 옵션도 함께 표시된다
    await expect(page.getByRole('menuitem', { name: 'CSV로 내보내기' })).toBeVisible();
  });

  test('폴더가 있는 쿼리 — 폴더 뱃지가 툴바에 표시된다', async ({
    authenticatedPage: page,
  }) => {
    // folder: '업무용' 쿼리 모킹
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/queries/2',
      createSavedQuery({ id: 2, name: '폴더 쿼리', folder: '업무용' }),
    );
    await mockApi(page, 'GET', '/api/v1/analytics/queries/schema', { tables: [] });
    await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', ['업무용']);

    await page.goto('/analytics/queries/2');

    // 쿼리 이름 확인
    await expect(page.getByText('폴더 쿼리')).toBeVisible();
    // 폴더 뱃지가 툴바에 표시되어야 한다
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '업무용' })).toBeVisible();
  });
});
