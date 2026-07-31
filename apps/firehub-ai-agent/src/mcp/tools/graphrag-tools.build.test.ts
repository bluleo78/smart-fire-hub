// 지식그래프 구축 파이프라인 보강 도구 단위 테스트.
// 대상: graphrag_list_ontologies / graphrag_describe_ontology / graphrag_bind_ontology /
//       graphrag_activate_mapping + graphrag_structured_query 의 동적 스키마 검증.
// 왜: 바인딩·활성화 수단이 없으면 infer_mapping(바인딩 필수)·project_table(active 필수)이
//     서로를 막아 에이전트 단독으로는 파이프라인을 완결할 수 없는 데드엔드가 된다.
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../../graphrag/neo4j-client.js', () => ({ bootstrapConstraints: vi.fn().mockResolvedValue(undefined) }));
vi.mock('../../graphrag/loader.js', () => ({
  loadGraph: vi.fn().mockResolvedValue({ nodes: 0, relations: 0 }),
  loadTableGraph: vi.fn().mockResolvedValue({ nodes: 0, relations: 0 }),
}));
// Neo4j 실행부는 스텁 — 본 테스트는 화이트리스트 검증까지만 다룬다.
const structuredQueryMock = vi.fn();
vi.mock('../../graphrag/structured-query.js', () => ({
  structuredQuery: (...a: unknown[]) => structuredQueryMock(...a),
}));

import { registerGraphragTools } from './graphrag-tools.js';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const safeTool = (name: string, description: string, _s: unknown, handler: (a: any) => Promise<any>) =>
  ({ name, description, handler });
const jsonResult = (data: unknown) => data;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function findTool(apiClient: any, name: string) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const tools = registerGraphragTools(apiClient, safeTool as any, jsonResult as any) as any[];
  const tool = tools.find((t) => t.name === name);
  expect(tool, `${name} 미등록`).toBeDefined();
  return tool!;
}

// 온톨로지 wire 포맷(SerializedOntology). Incident 에 필터 가능 속성 1개.
const ontologyWire = {
  domain: 'fire',
  schemaVersion: 3,
  entities: [
    {
      type: 'Incident', description: '사건', naming: '', resolution: 'exact', id: 1,
      properties: [{ name: '피해액', description: '재산 피해', dataType: 'number', unit: '원' }],
    },
    { type: 'Building', description: '건물', naming: '', resolution: 'exact', id: 2, properties: [] },
  ],
  relations: [{ subject: 'Incident', relation: 'OCCURRED_AT', object: 'Building', description: '발생 장소' }],
};

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function baseClient(overrides: Partial<any> = {}) {
  return {
    listOntologies: vi.fn().mockResolvedValue([
      { id: 1, domain: 'fire', schemaVersion: 3 },
      { id: 2, domain: 'safety', schemaVersion: 1 },
    ]),
    getOntology: vi.fn().mockResolvedValue(ontologyWire),
    getOntologyById: vi.fn().mockResolvedValue(ontologyWire),
    bindDatasetOntology: vi.fn().mockResolvedValue(undefined),
    getDatasetMapping: vi.fn().mockResolvedValue({ status: 'draft', ontologyId: 1, spec: {} }),
    activateDatasetMapping: vi.fn().mockResolvedValue({ datasetId: 900, ontologyId: 1, status: 'active' }),
    ...overrides,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  structuredQueryMock.mockResolvedValue({ entities: [], sourceChunkIds: [], truncated: false });
});

describe('graphrag_list_ontologies', () => {
  it('온톨로지 요약 목록을 반환한다', async () => {
    const client = baseClient();
    const out = await findTool(client, 'graphrag_list_ontologies').handler({});
    expect(client.listOntologies).toHaveBeenCalledTimes(1);
    expect(out.ontologies).toHaveLength(2);
    expect(out.ontologies[0]).toMatchObject({ id: 1, domain: 'fire' });
  });
});

describe('graphrag_describe_ontology', () => {
  it('ontologyId 생략 시 기본 온톨로지를 로드해 엔티티 타입·필터 가능 속성을 노출한다', async () => {
    const client = baseClient();
    const out = await findTool(client, 'graphrag_describe_ontology').handler({});
    expect(client.getOntology).toHaveBeenCalledTimes(1);
    expect(client.getOntologyById).not.toHaveBeenCalled();
    expect(out.domain).toBe('fire');
    const incident = out.entityTypes.find((e: { type: string }) => e.type === 'Incident');
    expect(incident.filterableProperties).toEqual([
      expect.objectContaining({ name: '피해액', dataType: 'number', unit: '원' }),
    ]);
    expect(out.relationTypes[0]).toMatchObject({ subject: 'Incident', relation: 'OCCURRED_AT' });
  });

  it('ontologyId 지정 시 by-id 로 로드하고 source=db 로 표기한다', async () => {
    const client = baseClient();
    const out = await findTool(client, 'graphrag_describe_ontology').handler({ ontologyId: 7 });
    expect(client.getOntologyById).toHaveBeenCalledWith(7);
    expect(out.source).toBe('db');
  });

  // 폴백을 숨기면 이 도구가 바로 그 "낡은 하드코딩 스키마"를 사실처럼 광고하게 된다.
  it('DB fetch 실패로 번들 폴백이 걸리면 source 로 표면화한다', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const client = baseClient({ getOntology: vi.fn().mockRejectedValue(new Error('api down')) });
    const out = await findTool(client, 'graphrag_describe_ontology').handler({});
    expect(out.source).toBe('bundled-fallback');
    warn.mockRestore();
  });
});

describe('graphrag_bind_ontology', () => {
  it('존재하는 온톨로지에 바인딩한다', async () => {
    const client = baseClient();
    const out = await findTool(client, 'graphrag_bind_ontology').handler({ datasetId: 900, ontologyId: 2 });
    expect(client.bindDatasetOntology).toHaveBeenCalledWith(900, 2);
    expect(out).toMatchObject({ datasetId: 900, ontologyId: 2, domain: 'safety', bound: true });
  });

  // 잘못된 id 로 바인딩하면 뒤늦게 추론 단계에서 실패한다 — 사전 거부 + 후보 안내로 자체 정정 유도.
  it('없는 온톨로지 id 는 사용 가능 목록과 함께 거부하고 바인딩하지 않는다', async () => {
    const client = baseClient();
    await expect(
      findTool(client, 'graphrag_bind_ontology').handler({ datasetId: 900, ontologyId: 99 }),
    ).rejects.toThrow(/1\(fire\)|사용 가능/);
    expect(client.bindDatasetOntology).not.toHaveBeenCalled();
  });
});

describe('graphrag_activate_mapping', () => {
  it('draft 매핑을 활성화한다', async () => {
    const client = baseClient();
    const out = await findTool(client, 'graphrag_activate_mapping').handler({ datasetId: 900 });
    expect(client.activateDatasetMapping).toHaveBeenCalledWith(900);
    expect(out).toMatchObject({ status: 'active', alreadyActive: false });
  });

  it('이미 active 면 재활성화하지 않고 조기 반환한다', async () => {
    const client = baseClient({
      getDatasetMapping: vi.fn().mockResolvedValue({ status: 'active', ontologyId: 1, spec: {} }),
    });
    const out = await findTool(client, 'graphrag_activate_mapping').handler({ datasetId: 900 });
    expect(client.activateDatasetMapping).not.toHaveBeenCalled();
    expect(out).toMatchObject({ status: 'active', alreadyActive: true });
  });

  // conformance 위반은 백엔드(400)가 판정한다 — 삼키지 말고 그대로 표면화해야 사용자가 고칠 수 있다.
  it('백엔드 활성화 실패를 전파한다', async () => {
    const client = baseClient({
      activateDatasetMapping: vi.fn().mockRejectedValue(new Error('conformance 위반: 알 수 없는 엔티티 타입')),
    });
    await expect(
      findTool(client, 'graphrag_activate_mapping').handler({ datasetId: 900 }),
    ).rejects.toThrow(/conformance/);
  });
});

describe('graphrag_structured_query — 동적 스키마 검증', () => {
  it('도구 설명에 특정 속성을 하드코딩하지 않고 describe 도구를 가리킨다', () => {
    const tool = findTool(baseClient(), 'graphrag_structured_query');
    expect(tool.description).not.toContain('피해액');
    expect(tool.description).toContain('graphrag_describe_ontology');
  });

  it('알 수 없는 엔티티 타입은 사용 가능 목록과 함께 거부한다', async () => {
    const client = baseClient();
    await expect(
      findTool(client, 'graphrag_structured_query').handler({ entityType: 'Nope', filters: [] }),
    ).rejects.toThrow(/Incident.*Building|사용 가능/s);
    expect(structuredQueryMock).not.toHaveBeenCalled();
  });

  it('정의되지 않은 속성은 해당 타입의 필터 가능 속성 목록과 함께 거부한다', async () => {
    const client = baseClient();
    await expect(
      findTool(client, 'graphrag_structured_query').handler({
        entityType: 'Incident',
        filters: [{ property: '없는속성', operator: 'gt', value: 1 }],
      }),
    ).rejects.toThrow(/피해액/);
    expect(structuredQueryMock).not.toHaveBeenCalled();
  });

  // 기본(id=1) 온톨로지만 화이트리스트로 쓰면 다른 온톨로지에 바인딩된 데이터셋의
  // 정당한 속성이 "필터 불가"로 거부된다 → ontologyId 로 타겟팅 가능해야 한다.
  it('ontologyId 지정 시 해당 온톨로지를 화이트리스트로 사용한다', async () => {
    const client = baseClient();
    await findTool(client, 'graphrag_structured_query').handler({
      ontologyId: 2,
      entityType: 'Incident',
      filters: [{ property: '피해액', operator: 'gt', value: 1 }],
    });
    expect(client.getOntologyById).toHaveBeenCalledWith(2);
    expect(client.getOntology).not.toHaveBeenCalled();
    expect(structuredQueryMock).toHaveBeenCalledTimes(1);
  });

  it('ontologyId 미지정 상태의 속성 거부 오류는 ontologyId 재시도를 안내한다', async () => {
    const client = baseClient();
    await expect(
      findTool(client, 'graphrag_structured_query').handler({
        entityType: 'Incident',
        filters: [{ property: '없는속성', operator: 'gt', value: 1 }],
      }),
    ).rejects.toThrow(/ontologyId/);
  });

  it('온톨로지에 정의된 속성이면 실행한다', async () => {
    const client = baseClient();
    await findTool(client, 'graphrag_structured_query').handler({
      entityType: 'Incident',
      filters: [{ property: '피해액', operator: 'gte', value: 100000000 }],
    });
    expect(structuredQueryMock).toHaveBeenCalledTimes(1);
  });
});
