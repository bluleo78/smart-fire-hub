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
    // S3: 적재·추론 도구는 등록 시 withPurpose('share') 클라이언트를 만든다 — 목은 자기 자신을 돌려준다.
    withPurpose: vi.fn(function (this: unknown) {
      return this;
    }),
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

  // S3(WD-39) §4.3: 투영 결과는 공유 그래프로 간다 — 매핑·온톨로지·행 읽기와 이력 기록이 전부 share 목적 클라이언트로
  // 가야 api 가 SHARE 판정을 더한다. 채팅 클라이언트로 되돌리면(SHARE 판정 누락 = fail-open) 아래 reject 목이 잡는다.
  it('매핑·온톨로지·행 읽기와 이력 기록을 share 목적 클라이언트로만 한다', async () => {
    const share = baseClient();
    const chatReject = () => vi.fn().mockRejectedValue(new Error('채팅 클라이언트로 읽으면 안 된다'));
    const client = baseClient({
      getDatasetMapping: chatReject(),
      getOntologyById: chatReject(),
      queryDatasetData: chatReject(),
      recordGraphIngest: chatReject(),
      withPurpose: vi.fn().mockReturnValue(share),
    });
    const summary = await findTool(client).handler({ datasetId: 900 });
    expect(client.withPurpose).toHaveBeenCalledWith('share');
    expect(share.getDatasetMapping).toHaveBeenCalledWith(900);
    expect(share.getOntologyById).toHaveBeenCalledWith(5);
    expect(share.queryDatasetData).toHaveBeenCalledWith(900, expect.objectContaining({ includeTotalCount: true }));
    expect(share.recordGraphIngest).toHaveBeenCalledTimes(1);
    expect(summary.rowCount).toBe(1);
    expect(client.getDatasetMapping).not.toHaveBeenCalled();
    expect(client.getOntologyById).not.toHaveBeenCalled();
    expect(client.queryDatasetData).not.toHaveBeenCalled();
    expect(client.recordGraphIngest).not.toHaveBeenCalled();
  });

  it('이력 기록 실패는 무시하고 summary를 반환한다', async () => {
    const client = baseClient({ recordGraphIngest: vi.fn().mockRejectedValue(new Error('down')) });
    const summary = await findTool(client).handler({ datasetId: 900 });
    expect(summary.rowCount).toBe(1);
  });
});
