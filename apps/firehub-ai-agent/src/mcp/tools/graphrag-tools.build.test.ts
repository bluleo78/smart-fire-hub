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

// registerGraphragTools 가 내부에서 createCompleter() 를 부르므로 주입 지점이 없다.
// 모듈을 mock 해서 추론 프롬프트에 대한 LLM 응답을 테스트가 제어한다.
const completeMock = vi.fn();
vi.mock('../../graphrag/llm-completer.js', () => ({
  createCompleter: () => (...a: unknown[]) => completeMock(...a),
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
    createOntology: vi.fn().mockResolvedValue(7),
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
    expect(client.listOntologies).toHaveBeenCalledWith(undefined);
    expect(out.ontologies).toHaveLength(2);
    expect(out.ontologies[0]).toMatchObject({ id: 1, domain: 'fire' });
  });

  // #426: status 인자가 apiClient 로 그대로 전달돼야 archived/draft 온톨로지도 조회할 수 있다.
  // 인자 없는 호출은 서버 기본값(active만)에 묶여 있어, 이름이 일치하는 archived 온톨로지가
  // 있어도 "존재하지 않음"으로 오답하는 게 근본 원인이었다.
  it('status 인자를 apiClient.listOntologies 로 그대로 전달한다', async () => {
    const client = baseClient();
    await findTool(client, 'graphrag_list_ontologies').handler({ status: 'all' });
    expect(client.listOntologies).toHaveBeenCalledWith('all');

    await findTool(client, 'graphrag_list_ontologies').handler({ status: 'archived' });
    expect(client.listOntologies).toHaveBeenCalledWith('archived');
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

describe('graphrag_propose_ontology', () => {
  const validArgs = {
    domain: '건축물 안전점검',
    entities: [
      { type: 'Inspection', description: '점검 이벤트', naming: '문서마다 고유', resolution: 'exact', properties: [] },
      { type: 'Facility', description: '점검 대상 시설', naming: '본문 표기 보존', resolution: 'embedding', properties: [] },
    ],
    relations: [{ subject: 'Inspection', relation: 'TARGETS', object: 'Facility', description: '점검 대상' }],
  };

  it('항상 draft 상태로 생성한다', async () => {
    const client = baseClient();
    const out = await findTool(client, 'graphrag_propose_ontology').handler(validArgs);

    // status를 모델이 고르게 두면 안 된다 — 사람 검토 없이 운영에 들어간다.
    expect(client.createOntology).toHaveBeenCalledWith(
      expect.objectContaining({ domain: '건축물 안전점검', status: 'draft' }),
    );
    expect(out).toMatchObject({ ontologyId: 7, domain: '건축물 안전점검', status: 'draft' });
  });

  it('사람이 활성화해야 함을 반환값으로 안내한다', async () => {
    const client = baseClient();
    const out = (await findTool(client, 'graphrag_propose_ontology').handler(validArgs)) as { nextStep: string };
    expect(out.nextStep).toMatch(/지식 모델/);
  });

  it('이미 같은 도메인이 있으면 사전 거부하고 생성하지 않는다', async () => {
    // 백엔드도 409로 막지만, 여기서 막으면 에이전트가 같은 턴에 정정할 수 있다.
    const client = baseClient({
      listOntologies: vi.fn().mockResolvedValue([{ id: 1, domain: '건축물 안전점검', schemaVersion: 1 }]),
    });
    await expect(
      findTool(client, 'graphrag_propose_ontology').handler(validArgs),
    ).rejects.toThrow(/이미/);
    expect(client.createOntology).not.toHaveBeenCalled();
  });

  it('같은 도메인이 archived 상태뿐이면 이름을 선점한 것으로 보지 않고 생성한다', async () => {
    // 백엔드 부분 유니크 인덱스(WHERE status <> 'archived')와 정합 — archived는 이름을 선점하지 않는다.
    // baseClient()의 listOntologies 목킹은 status 필드가 없어 undefined !== 'archived'로 우연히 통과했었다(갭).
    const client = baseClient({
      listOntologies: vi.fn().mockResolvedValue([
        { id: 1, domain: '건축물 안전점검', schemaVersion: 1, status: 'archived' },
      ]),
    });
    const out = await findTool(client, 'graphrag_propose_ontology').handler(validArgs);

    expect(client.createOntology).toHaveBeenCalledWith(
      expect.objectContaining({ domain: '건축물 안전점검', status: 'draft' }),
    );
    expect(out).toMatchObject({ ontologyId: 7, domain: '건축물 안전점검', status: 'draft' });
  });

  it('백엔드 권한 오류(403)를 그대로 전파한다', async () => {
    const client = baseClient({
      createOntology: vi.fn().mockRejectedValue(new Error('API 오류 (403): 권한이 없습니다')),
    });
    await expect(
      findTool(client, 'graphrag_propose_ontology').handler(validArgs),
    ).rejects.toThrow(/403/);
  });
});

describe('graphrag_infer_ontology', () => {
  // 엔티티 1개짜리 최소 정상 LLM 응답.
  const entityJson = (type: string) =>
    '```json\n' +
    JSON.stringify({
      entities: [{ type, description: '', naming: '', resolution: 'embedding', properties: [] }],
      relations: [],
    }) +
    '\n```';

  it('문서 데이터셋 근거로 draft 온톨로지를 생성한다', async () => {
    completeMock.mockResolvedValue(entityJson('Inspection'));
    const client = baseClient({
      listOntologies: vi.fn().mockResolvedValue([]),
      getDataset: vi.fn().mockResolvedValue({ id: 2, name: '점검보고서', storageType: 'DOCUMENT' }),
      listDocumentChunks: vi.fn().mockResolvedValue([{ chunkId: 1, content: '2026년 정기점검 결과 …' }]),
    });
    const out = await findTool(client, 'graphrag_infer_ontology').handler({
      domain: '건축물 안전점검',
      datasetIds: [2],
    });
    // status 를 모델이 고르게 두면 안 된다 — 사람 검토 없이 운영에 들어간다.
    expect(client.createOntology).toHaveBeenCalledWith(
      expect.objectContaining({ domain: '건축물 안전점검', status: 'draft' }),
    );
    expect(out).toMatchObject({ ontologyId: 7, status: 'draft', entityCount: 1 });
    expect(out.basedOn).toEqual([{ datasetId: 2, name: '점검보고서', kind: 'document' }]);
    // 근거가 실제로 프롬프트에 실렸는지 — 안 실리면 "데이터 근거" 라는 기능의 전제가 무너진다.
    expect(completeMock.mock.calls[0][0]).toContain('2026년 정기점검 결과');
  });

  it('살아있는 동일 도메인이 있으면 생성하지 않는다', async () => {
    const client = baseClient({
      listOntologies: vi.fn().mockResolvedValue([{ id: 9, domain: '건축물 안전점검', status: 'draft' }]),
      getDataset: vi.fn().mockResolvedValue({ id: 2, name: 'x', storageType: 'DOCUMENT' }),
      listDocumentChunks: vi.fn().mockResolvedValue([{ chunkId: 1, content: 'a' }]),
    });
    await expect(
      findTool(client, 'graphrag_infer_ontology').handler({ domain: '건축물 안전점검', datasetIds: [2] }),
    ).rejects.toThrow(/id=9/);
    expect(client.createOntology).not.toHaveBeenCalled();
  });

  it('같은 도메인이 archived 뿐이면 이름을 선점한 것으로 보지 않는다', async () => {
    // 백엔드 부분 유니크 인덱스(WHERE status <> 'archived')와 정합.
    completeMock.mockResolvedValue(entityJson('A'));
    const client = baseClient({
      listOntologies: vi.fn().mockResolvedValue([{ id: 9, domain: 'D', status: 'archived' }]),
      getDataset: vi.fn().mockResolvedValue({ id: 2, name: 'x', storageType: 'DOCUMENT' }),
      listDocumentChunks: vi.fn().mockResolvedValue([{ chunkId: 1, content: 'a' }]),
    });
    const out = await findTool(client, 'graphrag_infer_ontology').handler({ domain: 'D', datasetIds: [2] });
    expect(out.ontologyId).toBe(7);
  });

  it('TABLE 데이터셋 하나만 주면 경고를 낸다', async () => {
    // 표 하나만 보면 컬럼을 그대로 옮긴 온톨로지가 되기 쉽다 — 차단은 하지 않고 경고만.
    completeMock.mockResolvedValue(entityJson('Building'));
    const client = baseClient({
      listOntologies: vi.fn().mockResolvedValue([]),
      getDataset: vi.fn().mockResolvedValue({ id: 3, name: '건축물대장', storageType: 'TABLE' }),
      queryDatasetData: vi.fn().mockResolvedValue({
        columns: [{ columnName: 'bld_name', dataType: 'VARCHAR', isPrimaryKey: false }],
        rows: [{ bld_name: '○○아파트' }],
        totalPages: 1,
      }),
    });
    const out = await findTool(client, 'graphrag_infer_ontology').handler({ domain: 'D', datasetIds: [3] });
    expect(out.warnings.join(' ')).toContain('표 데이터셋 하나');
    expect(completeMock.mock.calls[0][0]).toContain('bld_name');
  });

  it('FILE 데이터셋은 스킵하고 skipped 에 기록한다', async () => {
    completeMock.mockResolvedValue(entityJson('A'));
    const client = baseClient({
      listOntologies: vi.fn().mockResolvedValue([]),
      getDataset: vi.fn().mockImplementation(async (id: number) =>
        id === 4
          ? { id: 4, name: '첨부파일', storageType: 'FILE' }
          : { id: 2, name: '보고서', storageType: 'DOCUMENT' },
      ),
      listDocumentChunks: vi.fn().mockResolvedValue([{ chunkId: 1, content: 'a' }]),
    });
    const out = await findTool(client, 'graphrag_infer_ontology').handler({ domain: 'D', datasetIds: [4, 2] });
    expect(out.skipped).toHaveLength(1);
    expect(out.skipped[0]).toMatchObject({ datasetId: 4 });
    expect(out.basedOn).toHaveLength(1);
  });

  it('추론 결과가 비면 저장하지 않고 실패한다', async () => {
    // 무용한 빈 draft 를 조용히 만들지 않는다.
    completeMock.mockResolvedValue('설명뿐, JSON 없음');
    const client = baseClient({
      listOntologies: vi.fn().mockResolvedValue([]),
      getDataset: vi.fn().mockResolvedValue({ id: 2, name: 'x', storageType: 'DOCUMENT' }),
      listDocumentChunks: vi.fn().mockResolvedValue([{ chunkId: 1, content: 'a' }]),
    });
    await expect(
      findTool(client, 'graphrag_infer_ontology').handler({ domain: 'D', datasetIds: [2] }),
    ).rejects.toThrow(/비었습니다/);
    expect(client.createOntology).not.toHaveBeenCalled();
  });

  it('청크 0건인 DOCUMENT 데이터셋만 주면 근거로 인정하지 않고 실패한다', async () => {
    // 청크가 없는데 "근거 있음"으로 통과시키면, LLM 이 도메인명만 보고 지어낸 온톨로지가
    // basedOn 에 그 데이터셋을 달고 데이터 기반인 것처럼 반환된다 — 이 도구의 존재 이유가 무너진다.
    const client = baseClient({
      listOntologies: vi.fn().mockResolvedValue([]),
      getDataset: vi.fn().mockResolvedValue({ id: 2, name: '빈문서', storageType: 'DOCUMENT' }),
      listDocumentChunks: vi.fn().mockResolvedValue([]),
    });
    await expect(
      findTool(client, 'graphrag_infer_ontology').handler({ domain: 'D', datasetIds: [2] }),
    ).rejects.toThrow(/근거로 쓸 수 있는 데이터셋이 없습니다/);
    expect(client.createOntology).not.toHaveBeenCalled();
    expect(completeMock).not.toHaveBeenCalled();
  });

  // review#2: 컬럼은 있는데 행이 0건이면 profileColumns가 nullRatio=0을 돌려줘 "근거 있음"으로
  // 잘못 통과한다 — DOCUMENT의 빈 청크 스킵과 대칭으로 스킵해야 한다.
  it('TABLE 데이터셋의 행이 0건이면 근거로 인정하지 않고 skipped에 기록한다', async () => {
    completeMock.mockResolvedValue(entityJson('A'));
    const client = baseClient({
      listOntologies: vi.fn().mockResolvedValue([]),
      getDataset: vi.fn().mockImplementation(async (id: number) => (id === 3
        ? { id: 3, name: '빈테이블', storageType: 'TABLE' }
        : { id: 2, name: '보고서', storageType: 'DOCUMENT' })),
      queryDatasetData: vi.fn().mockResolvedValue({
        columns: [{ columnName: 'bld_name', dataType: 'VARCHAR', isPrimaryKey: false }],
        rows: [],
        totalPages: 1,
      }),
      listDocumentChunks: vi.fn().mockResolvedValue([{ chunkId: 1, content: 'a' }]),
    });
    const out = await findTool(client, 'graphrag_infer_ontology').handler({ domain: 'D', datasetIds: [3, 2] });
    expect(out.skipped).toContainEqual(expect.objectContaining({ datasetId: 3 }));
    expect(out.basedOn).toEqual([{ datasetId: 2, name: '보고서', kind: 'document' }]);
  });

  // review#3: 중복 id를 넘겨도 같은 데이터셋을 두 번 세면 안 된다 — 특히 단일 TABLE 경고가
  // evidence.length로 판정되므로, 중복 제거를 안 하면 이 경고가 조용히 안 걸린다.
  it('중복된 datasetId를 넘겨도 한 번만 수집하고 단일 TABLE 경고가 뜬다', async () => {
    completeMock.mockResolvedValue(entityJson('Building'));
    const queryDatasetDataMock = vi.fn().mockResolvedValue({
      columns: [{ columnName: 'bld_name', dataType: 'VARCHAR', isPrimaryKey: false }],
      rows: [{ bld_name: '○○아파트' }],
      totalPages: 1,
    });
    const client = baseClient({
      listOntologies: vi.fn().mockResolvedValue([]),
      getDataset: vi.fn().mockResolvedValue({ id: 3, name: '건축물대장', storageType: 'TABLE' }),
      queryDatasetData: queryDatasetDataMock,
    });
    const out = await findTool(client, 'graphrag_infer_ontology').handler({ domain: 'D', datasetIds: [3, 3] });
    expect(out.basedOn).toHaveLength(1);
    expect(out.warnings.join(' ')).toContain('표 데이터셋 하나');
    expect(queryDatasetDataMock).toHaveBeenCalledTimes(1);
  });

  // review#4: 모델이 지어낸 id 하나(404) 때문에 여러 데이터셋짜리 요청 전체가 죽으면 안 된다 —
  // FILE 등 스킵 경로와 비대칭이었다.
  it('데이터셋 1건 조회 오류는 skipped로 넘기고 나머지로 계속 진행한다', async () => {
    completeMock.mockResolvedValue(entityJson('A'));
    const client = baseClient({
      listOntologies: vi.fn().mockResolvedValue([]),
      getDataset: vi.fn().mockImplementation(async (id: number) => {
        if (id === 999) throw new Error('데이터셋을 찾을 수 없습니다(404)');
        return { id: 2, name: '보고서', storageType: 'DOCUMENT' };
      }),
      listDocumentChunks: vi.fn().mockResolvedValue([{ chunkId: 1, content: 'a' }]),
    });
    const out = await findTool(client, 'graphrag_infer_ontology').handler({ domain: 'D', datasetIds: [999, 2] });
    expect(out.skipped).toContainEqual(expect.objectContaining({ datasetId: 999 }));
    expect(out.basedOn).toEqual([{ datasetId: 2, name: '보고서', kind: 'document' }]);
    expect(client.createOntology).toHaveBeenCalled();
  });

  it('청크가 상한(40)보다 많으면 앞뒤 청크를 고르게 포함해 40건을 표본으로 뽑는다', async () => {
    // i % step 방식은 상한을 살짝 넘는 구간(41건/상한40)에서 표본이 21건까지 급감한다.
    // 인덱스를 직접 계산해 min(len, cap) 건이 항상 뽑히고, 뒤쪽 청크도 표본에 들어가야 한다.
    completeMock.mockResolvedValue(entityJson('A'));
    const chunks = Array.from({ length: 41 }, (_, i) => ({ chunkId: i, content: `청크내용${i}` }));
    const client = baseClient({
      listOntologies: vi.fn().mockResolvedValue([]),
      getDataset: vi.fn().mockResolvedValue({ id: 2, name: '대용량문서', storageType: 'DOCUMENT' }),
      listDocumentChunks: vi.fn().mockResolvedValue(chunks),
    });
    await findTool(client, 'graphrag_infer_ontology').handler({ domain: 'D', datasetIds: [2] });
    const prompt = completeMock.mock.calls[0][0] as string;
    // Math.floor((i*41)/40) 은 i=0..39 에서 0..39 를 고르게 훑는다(끝단은 39, 41건 중 마지막
    // 청크 40은 반올림 특성상 빠지지만 바로 앞인 39까지는 포함돼 뒤쪽 편중 없이 골고루 뽑힌다).
    expect(prompt).toContain('청크내용0'); // 앞쪽
    expect(prompt).toContain('청크내용39'); // 뒤쪽 부근까지 포함
  });
});
