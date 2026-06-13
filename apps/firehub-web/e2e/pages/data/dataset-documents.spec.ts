import { createDocument, createDocuments } from '../../factories/document.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDocumentDatasetMocks } from '../../fixtures/document.fixture';

const DATASET_ID = 1;

test.describe('문서 데이터셋 상세', () => {
  test('DOCUMENT 데이터셋은 문서 탭을 보이고 필드/데이터 탭을 숨긴다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await setupDocumentDatasetMocks(page, DATASET_ID, createDocuments(2));

    await page.goto(`/data/datasets/${DATASET_ID}?tab=documents`);

    await expect(page.getByRole('tab', { name: '문서' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '필드' })).toHaveCount(0);
    await expect(page.getByRole('tab', { name: '데이터' })).toHaveCount(0);
    await expect(page.getByText('document_1.pdf')).toBeVisible();
  });

  test('문서를 업로드하면 POST가 호출된다', async ({ authenticatedPage: page }) => {
    await setupDocumentDatasetMocks(page, DATASET_ID, []);
    const uploaded = createDocuments(1)[0];
    const capture = await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/documents`, uploaded, {
      status: 202,
      capture: true,
    });

    await page.goto(`/data/datasets/${DATASET_ID}?tab=documents`);
    // 문서 업로드 섹션의 파일 입력만 선택 — AI 패널 등 다른 파일 입력과 구분한다
    await page.locator('input[type="file"][accept*=".pdf,.docx"]').setInputFiles({
      name: 'manual.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from('%PDF-1.4 test'),
    });
    await page.getByRole('button', { name: '업로드' }).click();

    const req = await capture.waitForRequest();
    expect(req.url.pathname).toBe(`/api/v1/datasets/${DATASET_ID}/documents`);
  });

  test('완료/실패 상태 배지를 표시한다', async ({ authenticatedPage: page }) => {
    const docs = [
      createDocument({ id: 1, originalName: 'done.pdf', status: 'COMPLETED' }),
      createDocument({ id: 2, originalName: 'fail.pdf', status: 'FAILED', errorDetail: '추출 실패' }),
    ];
    await setupDocumentDatasetMocks(page, DATASET_ID, docs);
    await page.goto(`/data/datasets/${DATASET_ID}?tab=documents`);
    await expect(page.getByText('완료')).toBeVisible();
    await expect(page.getByText('실패')).toBeVisible();
  });

  test('의미검색 결과를 표시한다', async ({ authenticatedPage: page }) => {
    await setupDocumentDatasetMocks(page, DATASET_ID, createDocuments(1));
    const hits = [
      {
        chunkId: 11, documentFileId: 1, datasetId: DATASET_ID, fileName: 'document_1.pdf',
        chunkIndex: 3, content: '소화 방식은 물 분무, 포소화, 분말소화로 나뉜다.', score: 0.87,
      },
    ];
    const capture = await mockApi(page, 'POST', '/api/v1/documents/search', hits, { capture: true });

    await page.goto(`/data/datasets/${DATASET_ID}?tab=documents`);
    await page.getByPlaceholder('검색어를 입력하세요').fill('소화 방식');
    await page.getByRole('button', { name: '검색' }).click();

    const req = await capture.waitForRequest();
    expect((req.payload as { datasetIds: number[] }).datasetIds).toEqual([DATASET_ID]);
    await expect(page.getByText('소화 방식은 물 분무', { exact: false })).toBeVisible();
    // 검색 결과 span은 "fileName · 청크 #N" 형태 — 문서 목록 셀과 구분된다
    await expect(page.getByText('document_1.pdf · 청크', { exact: false })).toBeVisible();
  });

  test('검색 모드를 키워드로 선택하면 mode:KEYWORD를 전송한다', async ({ authenticatedPage: page }) => {
    await setupDocumentDatasetMocks(page, DATASET_ID, createDocuments(1));
    const capture = await mockApi(page, 'POST', '/api/v1/documents/search', [], { capture: true });

    await page.goto(`/data/datasets/${DATASET_ID}?tab=documents`);
    // 검색 모드 토글에서 키워드 선택
    await page.getByRole('tab', { name: '키워드' }).click();
    await page.getByPlaceholder('검색어를 입력하세요').fill('소화 방식');
    await page.getByRole('button', { name: '검색' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({ query: '소화 방식', mode: 'KEYWORD' });
  });

  /**
   * 회귀 테스트: DOCUMENT 데이터셋은 백엔드가 rowCount: null을 반환한다.
   * DatasetInfoTab가 null.toLocaleString()을 호출하면 TypeError로 정보 탭이 크래시하므로,
   * 정보 탭이 정상 렌더되고 행 카운트가 '-'로 표시되는지 검증한다.
   */
  test('rowCount가 null인 DOCUMENT 데이터셋의 정보 탭이 크래시 없이 - 를 표시한다', async ({
    authenticatedPage: page,
  }) => {
    // 페이지 에러(미처리 예외)를 수집 — toLocaleString()이 throw하면 여기에 잡힌다.
    const pageErrors: Error[] = [];
    page.on('pageerror', (err) => pageErrors.push(err));

    await setupDocumentDatasetMocks(page, DATASET_ID, createDocuments(2));

    // 정보 탭(기본 탭)으로 진입
    await page.goto(`/data/datasets/${DATASET_ID}`);

    // 정보 탭이 활성 상태로 렌더링되어야 한다
    await expect(page.getByRole('tab', { name: '정보' })).toHaveAttribute('data-state', 'active');

    // 행 카운트 카드가 '-'를 표시해야 한다 ('행' 라벨 카드 내부)
    const rowCard = page.locator('div').filter({ hasText: /^-행$/ });
    await expect(rowCard).toBeVisible();

    // 렌더 도중 미처리 예외가 없어야 한다 (rowCount.toLocaleString() 크래시 회귀 방지)
    expect(pageErrors).toHaveLength(0);
  });

  test('문서를 삭제하면 DELETE가 호출된다', async ({ authenticatedPage: page }) => {
    await setupDocumentDatasetMocks(page, DATASET_ID, [createDocument({ id: 7, originalName: 'gone.pdf' })]);
    const capture = await mockApi(page, 'DELETE', `/api/v1/datasets/${DATASET_ID}/documents/7`, null, {
      status: 204,
      capture: true,
    });
    // window.confirm 다이얼로그를 자동 수락한다
    page.on('dialog', (d) => d.accept());

    await page.goto(`/data/datasets/${DATASET_ID}?tab=documents`);
    await page.getByRole('button', { name: 'gone.pdf 삭제' }).click();

    const req = await capture.waitForRequest();
    expect(req.url.pathname).toBe(`/api/v1/datasets/${DATASET_ID}/documents/7`);
  });
});
