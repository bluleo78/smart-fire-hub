// graphrag_project_table 툴 단위 테스트 — apiClient·Neo4j를 모킹해 흐름(active 게이트, 투영, 기록)을 검증.
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../../graphrag/neo4j-client.js', () => ({ bootstrapConstraints: vi.fn().mockResolvedValue(undefined) }));
vi.mock('../../graphrag/loader.js', () => ({
  loadGraph: vi.fn().mockResolvedValue({ nodes: 0, relations: 0 }),
  loadTableGraph: vi.fn().mockResolvedValue({ nodes: 0, relations: 0 }),
}));

import { registerGraphragTools } from './graphrag-tools.js';

// 최소 safeTool/jsonResult 스텁 — safeTool은 핸들러를 그대로 노출, jsonResult는 data를 반환.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const safeTool = (name: string, _d: string, _s: unknown, handler: (a: any) => Promise<any>) => ({ name, handler });
const jsonResult = (data: unknown) => data;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function findTool(apiClient: any) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const tools = registerGraphragTools(apiClient, safeTool as any, jsonResult as any) as any[];
  return tools.find((t) => t.name === 'graphrag_project_table')!;
}

// deserializeOntology가 통과하도록 최소 온톨로지 wire(엔티티 id 필수).
const ontologyWire = {
  domain: 'd', schemaVersion: 1,
  entities: [{ type: 'Incident', description: '', naming: '', resolution: 'exact', properties: [], id: 10 }],
  relations: [],
};

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function baseClient(overrides: Partial<any> = {}) {
  return {
    getDatasetMapping: vi.fn().mockResolvedValue({
      spec: { entities: [{ entityType: 'Incident', nameColumn: 'c', properties: [] }], relations: [] },
      status: 'active', ontologyId: 5,
    }),
    getOntologyById: vi.fn().mockResolvedValue(ontologyWire),
    queryDatasetData: vi.fn().mockResolvedValue({ rows: [{ c: 'A' }], totalPages: 1 }),
    recordGraphIngest: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  };
}

describe('graphrag_project_table', () => {
  beforeEach(() => vi.clearAllMocks());

  it('active 매핑을 바인딩 온톨로지로 투영하고 이력을 기록한다', async () => {
    const client = baseClient();
    const summary = await findTool(client).handler({ datasetId: 900 });
    expect(client.getOntologyById).toHaveBeenCalledWith(5); // 바인딩 온톨로지(id=1 아님)
    expect(summary.rowCount).toBe(1);
    expect(client.recordGraphIngest).toHaveBeenCalledTimes(1);
  });

  it('매핑이 active가 아니면 투영하지 않고 에러', async () => {
    const client = baseClient({
      getDatasetMapping: vi.fn().mockResolvedValue({ spec: { entities: [], relations: [] }, status: 'draft', ontologyId: 5 }),
    });
    await expect(findTool(client).handler({ datasetId: 900 })).rejects.toThrow();
    expect(client.queryDatasetData).not.toHaveBeenCalled();
  });

  it('이력 기록 실패는 무시하고 summary를 반환한다', async () => {
    const client = baseClient({ recordGraphIngest: vi.fn().mockRejectedValue(new Error('down')) });
    const summary = await findTool(client).handler({ datasetId: 900 });
    expect(summary.rowCount).toBe(1);
  });
});
