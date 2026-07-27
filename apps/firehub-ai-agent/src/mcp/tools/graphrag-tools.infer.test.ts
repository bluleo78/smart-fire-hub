// graphrag_infer_mapping 툴 단위 테스트 — apiClient 모킹 + inferMapping 스텁으로 가드/저장 흐름 검증.
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../../graphrag/neo4j-client.js', () => ({ bootstrapConstraints: vi.fn().mockResolvedValue(undefined) }));
vi.mock('../../graphrag/loader.js', () => ({
  loadGraph: vi.fn().mockResolvedValue({ nodes: 0, relations: 0 }),
  loadTableGraph: vi.fn().mockResolvedValue({ nodes: 0, relations: 0 }),
}));
// inferMapping을 스텁 — LLM 없이 결과를 제어한다. (기본: 엔티티 1개 통과)
const inferMock = vi.fn();
vi.mock('../../graphrag/mapping-inference.js', () => ({ inferMapping: (...a: unknown[]) => inferMock(...a) }));

import { registerGraphragTools } from './graphrag-tools.js';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const safeTool = (name: string, _d: string, _s: unknown, handler: (a: any) => Promise<any>) => ({ name, handler });
const jsonResult = (data: unknown) => data;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function findTool(apiClient: any) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const tools = registerGraphragTools(apiClient, safeTool as any, jsonResult as any) as any[];
  return tools.find((t) => t.name === 'graphrag_infer_mapping')!;
}

const ontologyWire = {
  domain: 'd', schemaVersion: 1,
  entities: [{ type: 'Building', description: '', naming: '', resolution: 'exact', properties: [], id: 10 }],
  relations: [],
};

// axios 404 형태 에러.
function notFound() {
  return Object.assign(new Error('Request failed with status code 404'), { response: { status: 404 } });
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function baseClient(overrides: Partial<any> = {}) {
  return {
    getDatasetMapping: vi.fn().mockRejectedValue(notFound()), // 기본: 매핑 없음(404)
    getDatasetOntology: vi.fn().mockResolvedValue({ datasetId: 900, ontologyId: 5 }),
    getOntologyById: vi.fn().mockResolvedValue(ontologyWire),
    queryDatasetData: vi.fn().mockResolvedValue({
      columns: [{ columnName: 'c', dataType: 'VARCHAR', isPrimaryKey: false }],
      rows: [{ c: 'A' }], totalPages: 1,
    }),
    saveDatasetMapping: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  };
}

describe('graphrag_infer_mapping', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // 기본 추론 결과: 엔티티 1개 통과.
    inferMock.mockResolvedValue({
      spec: { entities: [{ entityType: 'Building', nameColumn: 'c', properties: [] }], relations: [] },
      dropped: [], confidences: [],
    });
  });

  it('매핑이 없으면(404) 추론해 draft로 저장한다', async () => {
    const client = baseClient();
    const out = await findTool(client).handler({ datasetId: 900 });
    expect(client.getOntologyById).toHaveBeenCalledWith(5);
    expect(client.saveDatasetMapping).toHaveBeenCalledTimes(1);
    expect(out.status).toBe('draft');
    expect(out.entityCount).toBe(1);
  });

  it('draft 매핑이 있으면 덮어쓴다', async () => {
    const client = baseClient({ getDatasetMapping: vi.fn().mockResolvedValue({ status: 'draft', ontologyId: 5, spec: {} }) });
    await findTool(client).handler({ datasetId: 900 });
    expect(client.saveDatasetMapping).toHaveBeenCalledTimes(1);
  });

  it('active 매핑 + force 없음 → 거부(저장 안 함)', async () => {
    const client = baseClient({ getDatasetMapping: vi.fn().mockResolvedValue({ status: 'active', ontologyId: 5, spec: {} }) });
    await expect(findTool(client).handler({ datasetId: 900 })).rejects.toThrow();
    expect(client.saveDatasetMapping).not.toHaveBeenCalled();
  });

  it('active 매핑 + force:true → 덮어쓰고 forcedOverActive 표시', async () => {
    const client = baseClient({ getDatasetMapping: vi.fn().mockResolvedValue({ status: 'active', ontologyId: 5, spec: {} }) });
    const out = await findTool(client).handler({ datasetId: 900, force: true });
    expect(client.saveDatasetMapping).toHaveBeenCalledTimes(1);
    expect(out.forcedOverActive).toBe(true);
  });

  it('온톨로지 미바인딩(ontologyId=null) → 거부', async () => {
    const client = baseClient({ getDatasetOntology: vi.fn().mockResolvedValue({ datasetId: 900, ontologyId: null }) });
    await expect(findTool(client).handler({ datasetId: 900 })).rejects.toThrow();
    expect(client.saveDatasetMapping).not.toHaveBeenCalled();
  });

  it('추론 결과가 비면 저장하지 않고 에러', async () => {
    inferMock.mockResolvedValue({ spec: { entities: [], relations: [] }, dropped: [], confidences: [] });
    const client = baseClient();
    await expect(findTool(client).handler({ datasetId: 900 })).rejects.toThrow();
    expect(client.saveDatasetMapping).not.toHaveBeenCalled();
  });

  it('getDatasetMapping이 404가 아닌 에러면 그대로 전파', async () => {
    const boom = Object.assign(new Error('500'), { response: { status: 500 } });
    const client = baseClient({ getDatasetMapping: vi.fn().mockRejectedValue(boom) });
    await expect(findTool(client).handler({ datasetId: 900 })).rejects.toThrow('500');
    expect(client.saveDatasetMapping).not.toHaveBeenCalled();
  });
});
