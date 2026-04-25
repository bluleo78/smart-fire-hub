import { createQueryResult, createSavedQuery } from '../../factories/analytics.factory';
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

  test('쿼리 실행 후 내보내기 드롭다운이 표시되고 CSV 내보내기 API가 호출된다', async ({
    authenticatedPage: page,
  }) => {
    await setupQueryEditorMocks(page, 1);
    // rows가 있는 SELECT 결과 모킹
    await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/execute',
      createQueryResult({ queryType: 'SELECT', rows: [{ id: 1, name: '항목 1', value: 100 }] }),
    );

    // 내보내기 API 모킹 (blob 응답)
    let exportCalled = false;
    await page.route(
      (url) => url.pathname === '/api/v1/query-results/export',
      (route) => {
        if (route.request().method() === 'POST') {
          exportCalled = true;
          return route.fulfill({
            status: 200,
            contentType: 'text/csv',
            body: 'id,name,value\n1,항목 1,100',
          });
        }
        return route.fallback();
      },
    );

    await page.goto('/analytics/queries/1');
    await expect(page.getByText('테스트 쿼리')).toBeVisible();

    // 쿼리 실행
    await page.getByRole('button', { name: '실행' }).click();
    await expect(page.getByRole('columnheader', { name: 'id' })).toBeVisible();

    // 내보내기 드롭다운이 나타나야 함 (rows.length > 0 조건)
    await expect(page.getByRole('button', { name: '내보내기' })).toBeVisible();

    // 드롭다운 열기
    await page.getByRole('button', { name: '내보내기' }).click();

    // "CSV로 내보내기" 메뉴 아이템 클릭
    await page.getByRole('menuitem', { name: 'CSV로 내보내기' }).click();

    // 내보내기 API 호출 검증
    await expect.poll(() => exportCalled).toBe(true);
  });

  test('쿼리 실행 오류 시 에러 메시지가 결과 영역에 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupQueryEditorMocks(page, 1);
    // 오류를 포함한 실행 결과 모킹
    await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/execute',
      createQueryResult({
        error: 'column "unknown_col" does not exist',
        queryType: 'SELECT',
        rows: [],
        columns: [],
      }),
    );

    await page.goto('/analytics/queries/1');
    await expect(page.getByText('테스트 쿼리')).toBeVisible();

    await page.getByRole('button', { name: '실행' }).click();

    // 에러 메시지가 표시되어야 함
    await expect(page.getByText('column "unknown_col" does not exist')).toBeVisible();

    // 오류 상태에서는 내보내기 버튼이 표시되지 않아야 함
    await expect(page.getByRole('button', { name: '내보내기' })).not.toBeVisible();
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

  test('새 쿼리 저장 다이얼로그 — 저장 버튼 클릭 시 POST API가 호출된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewQueryEditorMocks(page);

    // POST /api/v1/analytics/queries 모킹 (저장 성공)
    const savedQuery = createSavedQuery({ id: 99, name: '내 새 쿼리' });
    const cap = await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries',
      savedQuery,
      { capture: true },
    );

    await page.goto('/analytics/queries/new');
    await expect(page.getByText('새 쿼리')).toBeVisible();

    // 저장 버튼 클릭 → 다이얼로그 열기
    await page.getByRole('button', { name: '저장' }).click();

    // 다이얼로그가 열려야 한다
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByRole('heading', { name: '쿼리 저장' })).toBeVisible();

    // 이름 입력란에 기본값 "새 쿼리"가 채워져 있어야 한다
    const nameInput = page.getByLabel('이름 *');
    await expect(nameInput).toBeVisible();

    // 이름을 변경
    await nameInput.clear();
    await nameInput.fill('내 새 쿼리');

    // 공유 토글 on
    await page.getByLabel('공유 쿼리').click();

    // 다이얼로그의 저장 버튼 클릭
    await page.getByRole('dialog').getByRole('button', { name: '저장' }).click();

    // POST API가 호출되었는지 확인
    const req = await cap.waitForRequest();
    expect(req.payload).toMatchObject({
      name: '내 새 쿼리',
      isShared: true,
    });
  });

  test('기존 쿼리 수정 다이얼로그 — 수정 버튼 클릭 시 PUT API가 호출된다', async ({
    authenticatedPage: page,
  }) => {
    await setupQueryEditorMocks(page, 1);

    // PUT /api/v1/analytics/queries/1 모킹 (수정 성공)
    const updatedQuery = createSavedQuery({ id: 1, name: '수정된 쿼리' });
    const cap = await mockApi(
      page,
      'PUT',
      '/api/v1/analytics/queries/1',
      updatedQuery,
      { capture: true },
    );

    await page.goto('/analytics/queries/1');
    await expect(page.getByText('테스트 쿼리')).toBeVisible();

    // 저장 버튼 클릭 → 수정 다이얼로그 열기
    await page.getByRole('button', { name: '저장' }).click();

    // 다이얼로그 제목이 "쿼리 수정"이어야 한다
    await expect(page.getByRole('heading', { name: '쿼리 수정' })).toBeVisible();

    // 이름 입력란에 기존 이름이 채워져 있어야 한다
    const nameInput = page.getByLabel('이름 *');
    await expect(nameInput).toHaveValue('테스트 쿼리');

    // 이름 변경
    await nameInput.clear();
    await nameInput.fill('수정된 쿼리');

    // 수정 버튼 클릭
    await page.getByRole('dialog').getByRole('button', { name: '수정' }).click();

    // PUT API가 호출되었는지 확인
    const req = await cap.waitForRequest();
    expect(req.payload).toMatchObject({ name: '수정된 쿼리' });
  });

  test('스키마 탐색기 테이블 클릭 시 컬럼 목록이 펼쳐진다', async ({
    authenticatedPage: page,
  }) => {
    await setupQueryEditorMocks(page, 1);

    await page.goto('/analytics/queries/1');

    // test_table 행 확인
    await expect(page.getByText('test_table').first()).toBeVisible();

    // 테이블 버튼 클릭 — toggleTable 호출로 컬럼 목록 펼침
    await page.getByText('test_table').first().click();

    // 컬럼명이 표시되어야 한다 (createSchemaInfo 기준: id, name 컬럼)
    // 컬럼 행은 pl-7 영역 안에 렌더링된다
    await expect(page.getByText('ID').first()).toBeVisible();
    await expect(page.getByText('이름').first()).toBeVisible();
  });

  test('저장 다이얼로그에서 폴더 선택 — 폴더 목록이 표시되고 선택값이 payload에 포함된다', async ({
    authenticatedPage: page,
  }) => {
    // 폴더 목록이 있는 새 쿼리 에디터 모킹
    await mockApi(page, 'GET', '/api/v1/analytics/queries/schema', { tables: [] });
    await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', ['업무용', '분석용', '공유']);

    const savedQuery = createSavedQuery({ id: 50, name: '폴더 테스트 쿼리', folder: '업무용' });
    const cap = await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries',
      savedQuery,
      { capture: true },
    );

    await page.goto('/analytics/queries/new');
    await expect(page.getByText('새 쿼리')).toBeVisible();

    // 저장 버튼 클릭 → 다이얼로그 열기
    await page.getByRole('button', { name: '저장' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // 폴더 Select 트리거 클릭
    await page.getByRole('dialog').getByLabel('폴더').click();

    // 폴더 옵션이 표시되어야 한다
    await expect(page.getByRole('option', { name: '업무용' })).toBeVisible();
    await expect(page.getByRole('option', { name: '분석용' })).toBeVisible();

    // '업무용' 폴더 선택
    await page.getByRole('option', { name: '업무용' }).click();

    // 저장 버튼 클릭
    await page.getByRole('dialog').getByRole('button', { name: '저장' }).click();

    // POST payload에 folder가 포함되는지 검증
    const req = await cap.waitForRequest();
    expect(req.payload).toMatchObject({ folder: '업무용' });
  });

  test('스키마 탐색기 — 테이블 숨기기/보기 토글', async ({ authenticatedPage: page }) => {
    await setupQueryEditorMocks(page, 1);

    await page.goto('/analytics/queries/1');

    // 기본적으로 사이드바가 열려 있으므로 "테이블 숨기기" 버튼이 표시된다
    const hideButton = page.getByRole('button', { name: '테이블 숨기기' });
    await expect(hideButton).toBeVisible();

    // 클릭하면 "테이블 보기"로 전환된다
    await hideButton.click();
    await expect(page.getByRole('button', { name: '테이블 보기' })).toBeVisible();

    // 다시 클릭하면 "테이블 숨기기"로 복귀한다
    await page.getByRole('button', { name: '테이블 보기' }).click();
    await expect(page.getByRole('button', { name: '테이블 숨기기' })).toBeVisible();
  });

  test('비SELECT 쿼리 실행 결과 — 영향받은 행 수가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupQueryEditorMocks(page, 1);
    // DML 쿼리 결과 모킹 (queryType != SELECT, columns 없음)
    await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/execute',
      createQueryResult({
        queryType: 'UPDATE',
        columns: [],
        rows: [],
        affectedRows: 5,
        executionTimeMs: 12,
      }),
    );

    await page.goto('/analytics/queries/1');
    await expect(page.getByText('테스트 쿼리')).toBeVisible();

    await page.getByRole('button', { name: '실행' }).click();

    // DML 결과: "N개 행이 처리되었습니다." 메시지 확인
    await expect(page.getByText(/5개 행이 처리되었습니다/)).toBeVisible();
  });

  /**
   * 회귀 테스트: 쿼리 결과 테이블에서 null 값이 'NULL' 텍스트가 아닌 dash('-')로 표시된다 (refs #13)
   */
  test('쿼리 결과 테이블에서 null 값은 NULL 텍스트가 아닌 dash(-)로 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupQueryEditorMocks(page, 1);

    // null 값이 포함된 결과 모킹 — ad-hoc execute 엔드포인트 사용 (QueryEditorPage가 executeAdhoc 호출)
    await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/execute',
      createQueryResult({
        queryType: 'SELECT',
        columns: ['id', 'name', 'value'],
        rows: [
          { id: 1, name: null, value: 100 },
          { id: 2, name: '항목 2', value: null },
        ],
        totalRows: 2,
      }),
    );

    await page.goto('/analytics/queries/1');
    await expect(page.getByText('테스트 쿼리')).toBeVisible();

    await page.getByRole('button', { name: '실행' }).click();

    // null 값이 있는 셀이 렌더링될 때까지 대기
    await expect(page.getByText('항목 2')).toBeVisible();

    // null 값 셀에 'NULL' 텍스트가 없어야 한다 (회귀 검증)
    await expect(page.getByText('NULL')).not.toBeVisible();

    // null 값은 dash('-')로 표시되어야 한다
    const dashCells = page.locator('span.italic.text-xs').filter({ hasText: '-' });
    await expect(dashCells.first()).toBeVisible();
  });
});
