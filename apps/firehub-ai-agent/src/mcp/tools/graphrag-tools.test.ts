// graphrag_query / graphrag_ingest MCP 도구 단위 테스트.
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../../graphrag/retriever.js', () => ({
  retrieve: vi.fn().mockResolvedValue({
    nodes: [{ key: 'Incident:x', type: 'Incident', name: 'X' }],
    relations: [{ subject: 'X', type: 'CAUSED_BY', object: 'Y' }],
    sourceChunks: [{ chunkId: 1, fileName: 'r.md', content: '...' }],
  }),
}));

// graphrag_ingest 핸들러가 의존하는 모듈들 — best-effort 기록 로직만 검증하려는 목적이므로
// 실제 추출/적재 파이프라인은 스텁하고 ingestDataset 만 결과를 제어한다.
vi.mock('../../graphrag/ingest.js', () => ({ ingestDataset: vi.fn() }));
vi.mock('../../graphrag/ontology-source.js', () => ({
  // ingest 는 이제 바인딩 기반으로 온톨로지를 해소한다(폴백 없음). 이 테스트는 적재 이력 기록만
  // 검증하므로 해소 결과는 최소 형태로 고정한다.
  resolveDatasetOntology: vi.fn().mockResolvedValue({
    ontology: { domain: 'fire', schemaVersion: 1, entities: [], relations: [] },
    ontologyId: 42,
  }),
  // id 기반 해소 — 읽기 도구(graphrag_query·structured_query)의 소유권 확인 겸 온톨로지 로딩.
  // apiClient 를 실제와 같은 지점에서 호출한다: 그래야 "api 가 거부하면 Neo4j 를 조회하지 않는다"를
  // 테스트가 apiClient 목만으로 조종할 수 있다. 역직렬화는 생략하고 응답을 그대로 흘린다 —
  // vi.mock 팩토리는 호이스팅돼 모듈 import 를 참조할 수 없고, 이 테스트들이 보는 것은
  // entities/relations 필드뿐이라 형태가 같다.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  resolveOntologyById: vi.fn(async (apiClient: any, ontologyId: number) => ({
    ontology: await apiClient.getOntologyById(ontologyId),
    ontologyId,
  })),
}));
vi.mock('../../graphrag/neo4j-client.js', () => ({ bootstrapConstraints: vi.fn() }));
vi.mock('../../graphrag/extractor.js', () => ({ extractGraph: vi.fn() }));
vi.mock('../../graphrag/llm-completer.js', () => ({ createCompleter: vi.fn(() => vi.fn()) }));
vi.mock('../../graphrag/loader.js', () => ({ loadGraph: vi.fn() }));

import { retrieve } from '../../graphrag/retriever.js';
import { registerGraphragTools } from './graphrag-tools.js';
import { ingestDataset } from '../../graphrag/ingest.js';
import { resolveDatasetOntology } from '../../graphrag/ontology-source.js';
import { createCompleter } from '../../graphrag/llm-completer.js';
import { FireHubApiClient } from '../api-client.js';
import { createFireHubMcpServer } from '../firehub-mcp-server.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

/**
 * FireHubApiClient 모킹 헬퍼(dataset-tools.test.ts 와 동일한 관례).
 * 프로토타입의 모든 메서드를 vi.fn() 으로 대체하여, 실제 MCP 서버(zod 스키마 검증 포함)를
 * 통해 도구를 호출해도 HTTP 요청 없이 동작하게 한다.
 */
function createMockClient(): FireHubApiClient {
  const client = Object.create(FireHubApiClient.prototype);
  const methodNames = Object.getOwnPropertyNames(FireHubApiClient.prototype).filter(
    (name) => name !== 'constructor',
  );
  for (const name of methodNames) {
    client[name] = vi.fn().mockResolvedValue({ mocked: true });
  }
  return client as FireHubApiClient;
}

// Ruling #30 — opencode 테넌트의 GraphRAG completion 이 tenant provider(baseUrl/apiKey/model)
// 로 가려면 registerGraphragTools 가 credentials 의 model 필드까지 createCompleter 로 넘겨야
// 한다. createCompleter 의 credentials 파라미터 자체에는 model 이 없으므로(순수 자격증명 타입),
// 별도 top-level model 옵션으로 전달돼야 한다 — 빠뜨리면 OpenAICompatCompletionProvider 가
// model: '' 을 보내 상류가 400 을 반환한다.
describe('registerGraphragTools — credentials.model 전달', () => {
  it('credentials.model 을 createCompleter 의 model 옵션으로 넘긴다', () => {
    const apiClient = {} as unknown as FireHubApiClient;
    const safeTool = (() => undefined) as unknown as SafeToolFn;
    const jsonResult = (() => ({ content: [] })) as unknown as JsonResultFn;

    registerGraphragTools(apiClient, safeTool, jsonResult, {
      agentType: 'opencode',
      baseUrl: 'https://api.openai.com/v1',
      apiKey: 'sk-tenant',
      providerId: 'openai',
      model: 'openai/gpt-4o',
    });

    expect(createCompleter).toHaveBeenCalledWith(
      expect.objectContaining({ model: 'openai/gpt-4o' }),
    );
  });

  it('credentials 가 없으면 model 도 undefined 로 넘긴다', () => {
    const apiClient = {} as unknown as FireHubApiClient;
    const safeTool = (() => undefined) as unknown as SafeToolFn;
    const jsonResult = (() => ({ content: [] })) as unknown as JsonResultFn;

    registerGraphragTools(apiClient, safeTool, jsonResult);

    expect(createCompleter).toHaveBeenCalledWith(
      expect.objectContaining({ model: undefined }),
    );
  });
});

describe('graphrag_query 도구', () => {
  // retrieve 목은 모듈 레벨이라 테스트 간 호출 기록이 누적된다 — "호출되지 않았다" 단언이
  // 앞 테스트의 호출을 보고 실패한다. 구현(mockResolvedValue)은 clearAllMocks 로 지워지지 않는다.
  beforeEach(() => vi.clearAllMocks());

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const jsonResult = ((data: any) => ({ content: [{ type: 'text', text: JSON.stringify(data) }] })) as unknown as JsonResultFn;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const safeTool = ((_n: string, _d: string, _s: any, handler: any) => ({ name: _n, handler })) as unknown as SafeToolFn;

  it('retrieve 결과를 jsonResult로 반환한다', async () => {
    const apiClient = {
      searchDocuments: vi.fn(),
      getOntologyById: vi.fn().mockResolvedValue({ domain: 'd', schemaVersion: 1, entities: [], relations: [] }),
    } as unknown as FireHubApiClient;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const tools: any[] = registerGraphragTools(apiClient, safeTool, jsonResult);
    const query = tools.find((t) => t.name === 'graphrag_query');
    const out = await query.handler({ ontologyId: 9, query: '원인?' });
    const payload = JSON.parse(out.content[0].text);
    expect(payload.subgraph.nodes[0].name).toBe('X');
    expect(payload.sourceChunks[0].fileName).toBe('r.md');
    // 스코프 인자가 실제로 retriever 까지 전달돼야 한다 — 도구가 ontologyId 를 받기만 하고
    // 흘려버리면 Cypher 술어가 있어도 전역 조회가 된다.
    expect(vi.mocked(retrieve)).toHaveBeenCalledWith(expect.anything(), 9, '원인?', expect.anything());
  });

  // 소유권 확인은 firehub-api 왕복(RLS)이 전담한다 — Neo4j 에는 RLS 가 없으므로 이 호출이
  // 빠지면 남의 온톨로지 id 를 그대로 받아 그래프를 읽는 IDOR 이 된다. api 가 거부하면(남의 것
  // 또는 없는 것) 그 예외가 그대로 올라가 Neo4j 를 조회조차 하지 않아야 한다.
  it('온톨로지 소유권 확인에 실패하면 그래프를 조회하지 않는다', async () => {
    const apiClient = {
      searchDocuments: vi.fn(),
      getOntologyById: vi.fn().mockRejectedValue(new Error('존재하지 않는 온톨로지입니다: 999')),
    } as unknown as FireHubApiClient;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const tools: any[] = registerGraphragTools(apiClient, safeTool, jsonResult);
    const query = tools.find((t) => t.name === 'graphrag_query');

    await expect(query.handler({ ontologyId: 999, query: '원인?' })).rejects.toThrow('존재하지 않는 온톨로지');
    expect(vi.mocked(retrieve)).not.toHaveBeenCalled();
  });
});

describe('graphrag_ingest 도구 — 적재 이력 best-effort 기록', () => {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const jsonResult = ((data: any) => ({ content: [{ type: 'text', text: JSON.stringify(data) }] })) as unknown as JsonResultFn;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const safeTool = ((_n: string, _d: string, _s: any, handler: any) => ({ name: _n, handler })) as unknown as SafeToolFn;

  const ontology = { domain: 'd', schemaVersion: 3, entities: [], relations: [] };

  it('추출 실패가 0건이면 status=SUCCESS 로 recordGraphIngest 를 호출한다', async () => {
    vi.mocked(resolveDatasetOntology).mockResolvedValue({ ontology, ontologyId: 42 });
    vi.mocked(ingestDataset).mockResolvedValue({ datasetId: 1, chunks: 10, entities: 20, relations: 15 });
    const recordGraphIngest = vi.fn().mockResolvedValue(undefined);
    const apiClient = { recordGraphIngest } as unknown as FireHubApiClient;

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const tools: any[] = registerGraphragTools(apiClient, safeTool, jsonResult);
    const ingest = tools.find((t) => t.name === 'graphrag_ingest');
    const out = await ingest.handler({ datasetId: 1 });

    expect(recordGraphIngest).toHaveBeenCalledWith(1, {
      schemaVersionAtIngest: 3, chunkCount: 10, nodeCount: 20, edgeCount: 15,
      extractionFailures: 0, status: 'SUCCESS',
    });
    const payload = JSON.parse(out.content[0].text);
    expect(payload.chunks).toBe(10);
    // 바인딩 해소 경로가 실제로 타는지 못박는다(resolveDatasetOntology 는 미바인딩이면 예외를 던진다 — 폴백 없음).
    expect(payload.ontologyId).toBe(42);
    // 배선이 args.datasetId 에 묶여 있는지(하드코딩된 다른 값이 아닌지) 확인한다.
    expect(resolveDatasetOntology).toHaveBeenCalledWith(apiClient, 1);
  });

  it('추출 실패가 있으면 status=PARTIAL 로 recordGraphIngest 를 호출한다', async () => {
    vi.mocked(resolveDatasetOntology).mockResolvedValue({ ontology, ontologyId: 42 });
    vi.mocked(ingestDataset).mockResolvedValue({
      datasetId: 1, chunks: 10, entities: 20, relations: 15, extractionFailures: 2,
    });
    const recordGraphIngest = vi.fn().mockResolvedValue(undefined);
    const apiClient = { recordGraphIngest } as unknown as FireHubApiClient;

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const tools: any[] = registerGraphragTools(apiClient, safeTool, jsonResult);
    const ingest = tools.find((t) => t.name === 'graphrag_ingest');
    await ingest.handler({ datasetId: 1 });

    expect(recordGraphIngest).toHaveBeenCalledWith(1, expect.objectContaining({
      extractionFailures: 2, status: 'PARTIAL',
    }));
  });

  it('recordGraphIngest 가 실패해도 도구는 jsonResult(summary) 를 정상 반환한다(best-effort)', async () => {
    vi.mocked(resolveDatasetOntology).mockResolvedValue({ ontology, ontologyId: 42 });
    vi.mocked(ingestDataset).mockResolvedValue({ datasetId: 1, chunks: 5, entities: 3, relations: 2 });
    const recordGraphIngest = vi.fn().mockRejectedValue(new Error('api down'));
    const apiClient = { recordGraphIngest } as unknown as FireHubApiClient;

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const tools: any[] = registerGraphragTools(apiClient, safeTool, jsonResult);
    const ingest = tools.find((t) => t.name === 'graphrag_ingest');

    const out = await ingest.handler({ datasetId: 1 });
    const payload = JSON.parse(out.content[0].text);
    expect(payload.chunks).toBe(5);
    expect(recordGraphIngest).toHaveBeenCalled();
  });
});

describe('graphrag_describe_ontology / graphrag_structured_query — ontologyId 필수화', () => {
  // 실제 MCP 서버(createFireHubMcpServer)를 통해 등록해야 zod 스키마 검증이 실제로 걸린다.
  // (이 파일 상단의 registerGraphragTools 직접 호출 + safeTool 스텁 조합은 zod 검증을 우회한다 —
  // 검증은 tool()이 아니라 McpServer.validateToolInput 에서 일어나기 때문이다.)
  let client: FireHubApiClient;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let server: any;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    client = createMockClient();
    server = createFireHubMcpServer(client);
  });

  it('graphrag_describe_ontology 스키마는 ontologyId 없이는 거부하고 있으면 통과한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const entry = (server.instance as any)._registeredTools['graphrag_describe_ontology'];
    expect(entry.inputSchema.safeParse({}).success).toBe(false);
    expect(entry.inputSchema.safeParse({ ontologyId: 7 }).success).toBe(true);
  });

  it('graphrag_structured_query 스키마는 ontologyId 없이는 거부하고 있으면 통과한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const entry = (server.instance as any)._registeredTools['graphrag_structured_query'];
    expect(entry.inputSchema.safeParse({ entityType: 'Incident', filters: [] }).success).toBe(false);
    expect(entry.inputSchema.safeParse({ ontologyId: 7, entityType: 'Incident', filters: [] }).success).toBe(true);
  });

  it('graphrag_describe_ontology 는 지정된 ontologyId 로 조회하고 응답에 source 필드가 없다', async () => {
    const ontology = {
      domain: 'fire', schemaVersion: 2,
      entities: [{ id: 1, type: 'Incident', description: '사건', properties: [] }],
      relations: [],
    };
    (client.getOntologyById as ReturnType<typeof vi.fn>).mockResolvedValue(ontology);

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const entry = (server.instance as any)._registeredTools['graphrag_describe_ontology'];
    const out = await entry.handler({ ontologyId: 7 }, {});
    const payload = JSON.parse(out.content[0].text);

    expect(client.getOntologyById).toHaveBeenCalledWith(7);
    expect(payload.domain).toBe('fire');
    expect(payload).not.toHaveProperty('source');
  });

  it('graphrag_structured_query 의 "필터 불가" 에러 메시지는 온톨로지 id를 명시하고 "기본 온톨로지"를 언급하지 않는다', async () => {
    const ontology = {
      domain: 'fire', schemaVersion: 2,
      entities: [{ id: 1, type: 'Incident', description: '사건', properties: [] }],
      relations: [],
    };
    (client.getOntologyById as ReturnType<typeof vi.fn>).mockResolvedValue(ontology);

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const entry = (server.instance as any)._registeredTools['graphrag_structured_query'];
    const out = await entry.handler(
      { ontologyId: 7, entityType: 'Incident', filters: [{ property: '없는속성', operator: 'eq', value: 1 }] },
      {},
    );

    expect(out.isError).toBe(true);
    const message = out.content.map((c: { text: string }) => c.text).join('');
    expect(message).toContain('온톨로지 id=7');
    expect(message).not.toContain('기본 온톨로지');
  });
});
