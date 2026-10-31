import type { Page } from '@playwright/test';

import { createDataset } from '../../factories/dataset.factory';
import { createOntologyGraph } from '../../factories/ontology.factory';
import { mockOntologyGraph, setupAdminAuth, setupOntologyMocks } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 지식그래프 노드 상세 — 표(데이터셋) 투영 노드의 출처·속성 (WD-382).
 * 표 투영 노드는 청크가 없어 늘 「출처 청크 0개」로 보여 근거 없는 노드처럼 읽혔고, 적재된 속성(피해액 등)은 상세에 없었다.
 * 그래프는 Cytoscape(Canvas)라 노드를 dev 전용 window.__ontologyCy 로 탭한다(ontology.spec.ts 와 같은 방식).
 */

test.beforeEach(async ({ authenticatedPage: page }) => {
  await setupAdminAuth(page);
});

/** 그래프 탐색 탭을 열고 노드 하나를 탭해 상세를 띄운다. */
async function openNodeDetail(page: Page, key: string, nodeCount: number) {
  await page.goto('/knowledge-graph/model');
  await page.getByRole('tab', { name: '그래프 탐색' }).click();
  await expect(page.getByTestId('instance-graph')).toHaveAttribute('data-node-count', String(nodeCount));
  await page.evaluate((k) => {
    (window as unknown as { __ontologyCy: { $(sel: string): { emit(e: string): void } } }).__ontologyCy
      .$(`#${k}`)
      .emit('tap');
  }, key);
}

test.describe('지식그래프 노드 상세 — 표 투영 노드 출처·속성 (WD-382)', () => {
  test('표 투영 노드는 출처 데이터셋 이름과 적재된 속성을 보이고 「출처 청크 0개」를 보이지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyMocks(page);
    const base = createOntologyGraph();
    const tableNode = {
      ...base.nodes[0],
      key: 'incident-table',
      name: '표 화재사건8',
      sourceChunkCount: 0,
      sourceDatasetIds: [999, 7],
      properties: { 피해액: 9077284, 원인: '누전' },
    };
    const graph = { nodes: [...base.nodes, tableNode], edges: base.edges };
    await mockOntologyGraph(page, graph);
    await mockApi(page, 'GET', '/api/v1/datasets/999', createDataset({ id: 999, name: 'QA 화재 시각' }));

    await openNodeDetail(page, 'incident-table', graph.nodes.length);

    const provenance = page.getByTestId('node-source-datasets');
    await expect(provenance).toHaveText('출처 데이터셋: QA 화재 시각 외 1개');
    await expect(provenance.getByRole('link', { name: 'QA 화재 시각' })).toHaveAttribute('href', '/data/datasets/999');
    await expect(page.getByText(/출처 청크/)).toHaveCount(0);

    const props = page.getByTestId('node-properties');
    await expect(props.locator('dt')).toHaveText(['피해액', '원인']);
    await expect(props.locator('dd')).toHaveText(['9,077,284', '누전']);
  });

  test('데이터셋 이름을 못 읽으면(권한 없음) id 로 출처를 보인다', async ({ authenticatedPage: page }) => {
    await setupOntologyMocks(page);
    const base = createOntologyGraph();
    const tableNode = { ...base.nodes[0], key: 'incident-table', sourceChunkCount: 0, sourceDatasetIds: [999] };
    const graph = { nodes: [...base.nodes, tableNode], edges: base.edges };
    await mockOntologyGraph(page, graph);
    await mockApi(page, 'GET', '/api/v1/datasets/999', { status: 403, message: '권한이 없습니다' }, { status: 403 });

    await openNodeDetail(page, 'incident-table', graph.nodes.length);

    await expect(page.getByTestId('node-source-datasets')).toHaveText('출처 데이터셋: 데이터셋 #999');
  });

  test('문서 노드는 지금처럼 청크 수로 출처를 보이고 속성 칸이 없다', async ({ authenticatedPage: page }) => {
    await setupOntologyMocks(page);
    const graph = createOntologyGraph();
    await mockOntologyGraph(page, graph);
    const docNode = graph.nodes[0];

    await openNodeDetail(page, docNode.key, graph.nodes.length);

    await expect(page.getByText(`출처 청크 ${docNode.sourceChunkCount}개`)).toBeVisible();
    await expect(page.getByTestId('node-source-datasets')).toHaveCount(0);
    await expect(page.getByTestId('node-properties')).toHaveCount(0);
  });
});
