import { createPipeline, createPipelines } from '../../factories/pipeline.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupPipelineMocks } from '../../fixtures/pipeline.fixture';

/**
 * 파이프라인 목록 페이지 E2E 테스트
 * - API 모킹 기반으로 백엔드 없이 목록 페이지 UI를 검증한다.
 */
test.describe('파이프라인 목록 페이지', () => {
  test('파이프라인 목록이 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
    // 5개 파이프라인 목록을 모킹한 후 목록 페이지 접근
    await setupPipelineMocks(page, 5);
    await page.goto('/pipelines');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '파이프라인 관리' })).toBeVisible();

    // 테이블 헤더 컬럼 확인
    await expect(page.getByRole('columnheader', { name: '이름' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '상태' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '스텝 수' })).toBeVisible();

    // 파이프라인 행이 5개 렌더링되는지 확인 (팩토리에서 생성한 이름 패턴)
    await expect(page.getByRole('cell', { name: '파이프라인 1', exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: '파이프라인 5', exact: true })).toBeVisible();
  });

  test('빈 목록일 때 빈 상태 메시지를 표시한다', async ({ authenticatedPage: page }) => {
    // 빈 페이지 응답으로 모킹
    await mockApi(page, 'GET', '/api/v1/pipelines', createPageResponse([]));

    await page.goto('/pipelines');

    // 빈 상태 메시지 확인
    await expect(page.getByText('파이프라인이 없습니다.')).toBeVisible();
  });

  test('파이프라인 추가 버튼 클릭 시 /new 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupPipelineMocks(page, 3);

    // 신규 에디터 페이지에서 필요한 데이터셋 목록 모킹
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [],
      page: 0,
      size: 1000,
      totalElements: 0,
      totalPages: 0,
    });

    await page.goto('/pipelines');

    // "파이프라인 추가" 링크 버튼 클릭
    await page.getByRole('link', { name: /파이프라인 추가/ }).click();

    // /pipelines/new 페이지로 이동 확인
    await expect(page).toHaveURL('/pipelines/new');
  });

  test('활성 파이프라인에 활성 뱃지가 표시된다', async ({ authenticatedPage: page }) => {
    // isActive: true 파이프라인 1개 모킹
    await mockApi(
      page,
      'GET',
      '/api/v1/pipelines',
      createPageResponse([createPipeline({ id: 1, name: '활성 파이프라인', isActive: true })]),
    );

    await page.goto('/pipelines');

    // "활성" 뱃지 확인 — 파이프라인 이름 셀과 구분하기 위해 뱃지 역할(data-slot="badge") 요소를 지정
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: /^활성$/ })).toBeVisible();
  });

  test('비활성 파이프라인에 비활성 뱃지가 표시된다', async ({ authenticatedPage: page }) => {
    // isActive: false 파이프라인 1개 모킹
    await mockApi(
      page,
      'GET',
      '/api/v1/pipelines',
      createPageResponse([createPipeline({ id: 1, name: '비활성 파이프라인', isActive: false })]),
    );

    await page.goto('/pipelines');

    // "비활성" 뱃지 확인 — 파이프라인 이름 셀과 구분하기 위해 뱃지 역할(data-slot="badge") 요소를 지정
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: /^비활성$/ })).toBeVisible();
  });

  test('삭제 버튼 클릭 시 확인 다이얼로그가 열린다', async ({ authenticatedPage: page }) => {
    await setupPipelineMocks(page, 2);
    await page.goto('/pipelines');

    // 첫 번째 행의 삭제 버튼 클릭 (aria-label="삭제")
    const deleteButtons = page.getByRole('button', { name: '삭제' });
    await deleteButtons.first().click();

    // 삭제 확인 다이얼로그가 열리는지 확인
    await expect(page.getByRole('alertdialog')).toBeVisible();
  });

  test('트리거가 있는 파이프라인은 트리거 수 뱃지를 표시한다', async ({ authenticatedPage: page }) => {
    // triggerCount: 3 파이프라인 모킹
    await mockApi(
      page,
      'GET',
      '/api/v1/pipelines',
      createPageResponse([
        createPipeline({ id: 1, name: '트리거 파이프라인', triggerCount: 3 }),
        createPipeline({ id: 2, name: '트리거 없는 파이프라인', triggerCount: 0 }),
      ]),
    );

    await page.goto('/pipelines');

    // triggerCount가 있는 행에서 트리거 수 확인 (뱃지 내 숫자)
    await expect(page.getByRole('row', { name: /트리거 파이프라인/ }).getByText('3')).toBeVisible();
  });

  test('서버 에러(500) 시 목록이 비어 있다', async ({ authenticatedPage: page }) => {
    // 500 에러 응답으로 모킹
    await mockApi(page, 'GET', '/api/v1/pipelines', {}, { status: 500 });

    await page.goto('/pipelines');

    // 서버 에러 시 빈 상태 메시지 확인
    await expect(page.getByText('파이프라인이 없습니다.')).toBeVisible();
  });

  test('파이프라인 목록 페이지네이션이 렌더링된다', async ({ authenticatedPage: page }) => {
    // 총 25개 항목 → 3페이지 (size=10)
    await mockApi(
      page,
      'GET',
      '/api/v1/pipelines',
      createPageResponse(createPipelines(10), { totalElements: 25, totalPages: 3 }),
    );

    await page.goto('/pipelines');

    // 첫 번째 데이터 행이 보이는지 확인 (헤더 행 제외)
    await expect(page.getByRole('row').nth(1)).toBeVisible();
  });
});
