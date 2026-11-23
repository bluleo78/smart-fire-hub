import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantPipelinePermissions, setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';
import { expectSelectedTabControlsPanel } from '../../fixtures/tabs-a11y';

/**
 * 파이프라인 저장 전(/pipelines/new) 탭 접근성 E2E (WD-919)
 * - 저장 전에는 TabsList(트리거)를 감춰 렌더링하지만 TabsContent 3개는 무조건 그려져,
 *   트리거 없는 role=tabpanel 이 남고 aria-labelledby 가 존재하지 않는 요소를 가리켰다(WCAG 1.3.1·4.1.2).
 * - 저장 전 화면에 탭 역할이 하나도 남지 않는지, 저장 후 탭 구조는 그대로인지 함께 확인한다.
 */
test.describe('파이프라인 저장 전 탭 접근성 (WD-919)', () => {
  // 쓰기 진입점 표시는 권한에 따라 달라지므로 에디터·헤더 흐름을 통일하기 위해 전체 권한을 준다(WD-219, 기존 pipeline-editor.spec.ts 와 동일)
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  test('새 파이프라인에는 탭 패널 역할이 없다 (WD-919)', async ({ authenticatedPage: page }) => {
    // 신규 생성 화면은 데이터셋 목록 모킹만 필요하다(기존 pipeline-editor.spec.ts 신규 생성 테스트와 같은 모킹)
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse([], { size: 1000 }));

    await page.goto('/pipelines/new');
    // 개요 본문(캔버스 빈 상태 「스텝 추가」 버튼)이 그려진 뒤에야 카운트 단언을 한다 —
    // toHaveCount(0) 은 마운트 전에도 즉시 통과해(빈 문서) 결함을 조용히 놓칠 수 있다.
    await expect(page.getByRole('button', { name: '스텝 추가' }).first()).toBeVisible();
    await expect(page.locator('[role="tablist"]')).toHaveCount(0);
    await expect(page.locator('[role="tab"]')).toHaveCount(0);
    await expect(page.locator('[role="tabpanel"]')).toHaveCount(0);
    // aria-labelledby 가 대상 없는 요소를 가리키는 요소 0개 (이슈 재현 스크립트와 같은 셀렉터)
    expect(
      await page.evaluate(() =>
        [...document.querySelectorAll('[aria-labelledby]')]
          .filter((el) => !document.getElementById(el.getAttribute('aria-labelledby')!))
          .length,
      ),
    ).toBe(0);
  });

  test('저장 후 탭은 그대로다 (WD-919 회귀)', async ({ authenticatedPage: page }) => {
    // 기존 pipeline 생성/진입 경로로 /pipelines/:id 로 간다
    await setupPipelineEditorMocks(page, 1);
    await page.goto('/pipelines/1');

    const overviewTab = page.getByRole('tab', { name: '개요' });
    await expectSelectedTabControlsPanel(page, overviewTab); // WD-514 헬퍼 — aria-controls ↔ tabpanel 짝 확인
    await expect(page.locator('[role="tab"]')).toHaveCount(3);
    await expect(page.getByRole('tab', { name: '트리거' })).toBeVisible();
  });
});
