import { createOntologyGraph } from '../../factories/ontology.factory';
import {
  setupAdminAuth,
  setupOntologyGraphErrorMock,
  setupOntologyGraphRetryMock,
  setupOntologyMocks,
} from '../../fixtures/admin.fixture';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 온톨로지 시각화 페이지 E2E 테스트
 * - 스키마/인스턴스 그래프 렌더, 탭 전환, 노드 클릭 드로어, 드릴다운 브리지, 범례 필터, 에러 상태를 검증한다.
 * - AdminRoute 통과를 위해 ADMIN 역할로 users/me를 오버라이드한다.
 * - 백엔드 없이 page.route()로 /api/v1/ontology(/graph)를 모킹한다.
 */
test.describe('온톨로지 시각화 페이지', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  test(
    '범례 6타입 + 스키마 탭 6노드가 렌더링된다',
    { tag: '@smoke' },
    async ({ authenticatedPage: page }) => {
      await setupOntologyMocks(page);
      await page.goto('/admin/ontology');

      // 범례: 6타입 버튼 표시
      const legend = page.getByTestId('type-legend');
      await expect(legend).toBeVisible();
      await expect(legend.getByRole('button')).toHaveCount(6);

      // 스키마 탭(기본 탭): React Flow 노드 6개(엔티티 타입) 렌더
      await expect(page.locator('.react-flow__node')).toHaveCount(6);
    },
  );

  test('인스턴스 그래프 탭으로 전환하면 인스턴스 노드와 관계 라벨이 렌더링된다', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    await setupOntologyMocks(page);
    await page.goto('/admin/ontology');

    await page.getByRole('tab', { name: '인스턴스 그래프' }).click();

    // 모킹 그래프의 노드 수(7개)만큼 React Flow 노드가 렌더되어야 한다
    await expect(page.locator('.react-flow__node')).toHaveCount(graph.nodes.length);
    // 관계 라벨(OCCURRED_AT)이 엣지에 표시되어야 한다
    await expect(page.getByText('OCCURRED_AT').first()).toBeVisible();
  });

  test('노드 클릭 시 상세 드로어에 이름과 인접 관계가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    const building = graph.nodes.find((n) => n.type === 'Building')!;
    await setupOntologyMocks(page);
    await page.goto('/admin/ontology');
    await page.getByRole('tab', { name: '인스턴스 그래프' }).click();
    await expect(page.locator('.react-flow__node')).toHaveCount(graph.nodes.length);

    // Building 노드(강남타워)를 클릭 — outgoing(HAS_EQUIPMENT)·incoming(OCCURRED_AT x2) 관계를 모두 가진다
    await page.locator('.react-flow__node', { hasText: building.name }).click();

    const drawer = page.getByTestId('node-detail-drawer');
    await expect(drawer).toBeVisible();
    // 클릭한 노드의 이름이 드로어에 표시된다
    await expect(drawer).toContainText(building.name);
    // 최소 하나의 인접 관계 라인이 표시된다 (나가는 관계: HAS_EQUIPMENT → 스프링클러설비)
    await expect(drawer).toContainText('HAS_EQUIPMENT');
    await expect(drawer).toContainText('스프링클러설비');
    // 들어오는 관계도 표시된다 (OCCURRED_AT ← 강남구 오피스텔 화재)
    await expect(drawer).toContainText('OCCURRED_AT');
  });

  test('스키마 탭에서 Incident 타입 클릭 시 인스턴스 탭으로 드릴다운되어 Incident 노드만 표시된다', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    const incidentCount = graph.nodes.filter((n) => n.type === 'Incident').length;
    await setupOntologyMocks(page);
    await page.goto('/admin/ontology');

    // 스키마 탭의 'Incident' 타입 노드 클릭 → 드릴다운 브리지
    await page.locator('.react-flow__node', { hasText: 'Incident' }).click();

    // 인스턴스 탭이 활성화되어야 한다
    await expect(page.getByRole('tab', { name: '인스턴스 그래프' })).toHaveAttribute('aria-selected', 'true');
    // Incident 타입 노드만 필터되어 표시되어야 한다 (모킹 그래프 기준 2개)
    await expect(page.locator('.react-flow__node')).toHaveCount(incidentCount);
  });

  test('범례에서 타입 버튼 클릭 시 인스턴스 노드 집합이 해당 타입만으로 필터링된다', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    const buildingCount = graph.nodes.filter((n) => n.type === 'Building').length;
    await setupOntologyMocks(page);
    await page.goto('/admin/ontology');
    await page.getByRole('tab', { name: '인스턴스 그래프' }).click();

    // 초기: 전체 노드(7개) 표시
    await expect(page.locator('.react-flow__node')).toHaveCount(graph.nodes.length);

    // 범례에서 'Building' 타입 버튼 클릭 → 필터 토글(Building만 활성)
    const legend = page.getByTestId('type-legend');
    await legend.getByRole('button', { name: /Building/ }).click();

    // Building 타입 노드만 남아야 한다 (모킹 그래프 기준 1개)
    await expect(page.locator('.react-flow__node')).toHaveCount(buildingCount);
  });

  test('인스턴스 그래프 조회 실패 시 에러 문구가 표시되고 페이지가 크래시하지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyGraphErrorMock(page);
    await page.goto('/admin/ontology');

    // 스키마는 정상 로드되어 범례/스키마 탭은 그대로 동작한다
    await expect(page.getByTestId('type-legend')).toBeVisible();

    await page.getByRole('tab', { name: '인스턴스 그래프' }).click();

    // 인스턴스 그래프 API 500 응답 시 에러 문구가 표시된다
    await expect(page.getByText('그래프를 불러오지 못했습니다.')).toBeVisible();
    // React Flow 노드는 렌더되지 않는다(크래시 없이 안전하게 폴백)
    await expect(page.locator('.react-flow__node')).toHaveCount(0);
  });

  test('인스턴스 그래프 에러 후 "다시 시도" 클릭 시 재요청하여 그래프가 정상 렌더된다', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    // 초기 로드는 실패시키고 이후 refetch부터 성공하는 모킹 — 재요청 횟수를 카운터로 추적.
    const counter = await setupOntologyGraphRetryMock(page);
    await page.goto('/admin/ontology');

    await page.getByRole('tab', { name: '인스턴스 그래프' }).click();

    // 초기 로드 실패 → 에러 문구 + 재시도 버튼 노출
    await expect(page.getByText('그래프를 불러오지 못했습니다.')).toBeVisible();
    const retryButton = page.getByRole('button', { name: '다시 시도' });
    await expect(retryButton).toBeVisible();

    // 재시도 이전까지의 요청 수 기록(최초 요청 + 자동 재시도 1회 = 2회)
    const callsBeforeRetry = counter.calls;
    expect(callsBeforeRetry).toBeGreaterThanOrEqual(1);

    // "다시 시도" 클릭 → 그래프 API 재요청 → 성공 응답으로 노드 렌더
    await retryButton.click();

    // 에러 문구가 사라지고 모킹 그래프의 노드 수(7개)만큼 렌더된다
    await expect(page.getByText('그래프를 불러오지 못했습니다.')).toHaveCount(0);
    await expect(page.locator('.react-flow__node')).toHaveCount(graph.nodes.length);
    // 실제 재요청이 발생했음을 검증(입력→처리→출력: 클릭 → API 재호출 → UI 반영)
    expect(counter.calls).toBeGreaterThan(callsBeforeRetry);
  });
});
