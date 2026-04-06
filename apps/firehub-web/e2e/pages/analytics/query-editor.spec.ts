import { createSavedQuery } from '../../factories/analytics.factory';
import {
  setupNewQueryEditorMocks,
  setupQueryEditorMocks,
  setupQueryExecuteMock,
} from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 쿼리 에디터 페이지 E2E 테스트
 * - API 모킹 기반으로 백엔드 없이 에디터 페이지 UI를 검증한다.
 * - CodeMirror 에디터 내부 입력은 어렵기 때문에 페이지 레벨 UI 확인에 집중한다.
 */
test.describe('쿼리 에디터 페이지', () => {
  test('새 쿼리 에디터가 렌더링된다', async ({ authenticatedPage: page }) => {
    // 새 쿼리 에디터에서 필요한 스키마/폴더 API 모킹
    await setupNewQueryEditorMocks(page);

    await page.goto('/analytics/queries/new');

    // 툴바의 "새 쿼리" 텍스트 확인
    await expect(page.getByText('새 쿼리')).toBeVisible();

    // 저장 버튼과 실행 버튼이 존재하는지 확인
    await expect(page.getByRole('button', { name: '저장' })).toBeVisible();
    await expect(page.getByRole('button', { name: '실행' })).toBeVisible();
  });

  test('기존 쿼리 로드 시 쿼리 이름이 툴바에 표시된다', async ({ authenticatedPage: page }) => {
    // 기존 쿼리 ID=1 관련 API 모킹
    await setupQueryEditorMocks(page, 1);

    await page.goto('/analytics/queries/1');

    // 쿼리 이름이 툴바에 표시되는지 확인
    await expect(page.getByText('테스트 쿼리')).toBeVisible();

    // 저장 버튼과 실행 버튼이 모두 존재하는지 확인 (정상 로드 검증)
    await expect(page.getByRole('button', { name: '저장' })).toBeVisible();
    await expect(page.getByRole('button', { name: '실행' })).toBeVisible();
  });

  test('스키마 탐색기 "테이블 목록" 패널이 표시된다', async ({ authenticatedPage: page }) => {
    await setupQueryEditorMocks(page, 1);

    await page.goto('/analytics/queries/1');

    // 스키마 탐색기 패널 헤더 확인
    await expect(page.getByText('테이블 목록')).toBeVisible();

    // createSchemaInfo()에 정의된 테이블 이름이 스키마 탐색기에 렌더링되는지 확인
    // test_table이 두 곳에서 렌더링될 수 있으므로 (스키마 패널 + CodeMirror) .first() 사용
    await expect(page.getByText('test_table').first()).toBeVisible();
    await expect(page.getByText('another_table').first()).toBeVisible();
  });

  test('기존 쿼리 에디터에서 실행 버튼 클릭 시 결과 테이블이 렌더링된다', async ({ authenticatedPage: page }) => {
    // 기존 쿼리 에디터 모킹 (sqlText가 채워진 쿼리 로드됨)
    await setupQueryEditorMocks(page, 1);
    // ad-hoc 쿼리 실행 결과 모킹 — columns: ['id','name','value'], rows 2개, executionTimeMs: 42
    await setupQueryExecuteMock(page);

    await page.goto('/analytics/queries/1');

    // 쿼리가 정상 로드되었는지 먼저 확인
    await expect(page.getByText('테스트 쿼리')).toBeVisible();

    // 실행 버튼이 활성화 상태인지 확인 (SQL이 채워져 있으므로 disabled 해제됨)
    const runButton = page.getByRole('button', { name: '실행' });
    await expect(runButton).toBeVisible();
    await expect(runButton).not.toBeDisabled();

    // 실행 버튼 클릭 — POST /api/v1/analytics/queries/execute 호출
    await runButton.click();

    // 결과 테이블에 컬럼 헤더가 렌더링되는지 확인
    // createQueryResult()의 columns: ['id', 'name', 'value']
    await expect(page.getByRole('columnheader', { name: 'id' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'name' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'value' })).toBeVisible();

    // 결과 테이블에 셀 데이터가 렌더링되는지 확인
    // rows: [{id:1, name:'항목 1', value:100}, {id:2, name:'항목 2', value:200}]
    await expect(page.getByRole('cell', { name: '항목 1' })).toBeVisible();
    // '100'은 CodeMirror span과 테이블 cell 양쪽에서 나타날 수 있으므로 cell 역할로 한정
    await expect(page.getByRole('cell', { name: '100' })).toBeVisible();

    // 실행 시간 표시 확인 — executionTimeMs: 42 (배지 또는 요약 텍스트 둘 다 허용)
    await expect(page.getByText(/42\s*ms/).first()).toBeVisible();
  });

  test('목록으로 돌아가기 버튼이 존재한다', async ({ authenticatedPage: page }) => {
    await setupNewQueryEditorMocks(page);

    await page.goto('/analytics/queries/new');

    // 툴바의 뒤로가기 버튼 (ArrowLeft icon, ghost/icon 버튼)
    const backButton = page.getByRole('button').filter({ has: page.locator('svg') }).first();
    await expect(backButton).toBeVisible();
  });

  test('공유 쿼리에는 "공유됨" 뱃지가 표시된다', async ({ authenticatedPage: page }) => {
    // isShared: true 쿼리 모킹
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/queries/1',
      createSavedQuery({ id: 1, isShared: true }),
    );
    await mockApi(page, 'GET', '/api/v1/analytics/queries/schema', { tables: [] });
    await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', []);

    await page.goto('/analytics/queries/1');

    // "공유됨" 뱃지 확인
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '공유됨' })).toBeVisible();
  });
});
