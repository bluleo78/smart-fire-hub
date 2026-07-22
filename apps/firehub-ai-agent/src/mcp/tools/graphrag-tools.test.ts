// graphrag_query / graphrag_ingest MCP 도구 단위 테스트.
import { describe, it, expect, vi } from 'vitest';

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
vi.mock('../../graphrag/ontology-source.js', () => ({ loadOntology: vi.fn() }));
vi.mock('../../graphrag/neo4j-client.js', () => ({ bootstrapConstraints: vi.fn() }));
vi.mock('../../graphrag/extractor.js', () => ({ extractGraph: vi.fn() }));
vi.mock('../../graphrag/llm-cli.js', () => ({ createCliCompleter: vi.fn(() => vi.fn()) }));
vi.mock('../../graphrag/loader.js', () => ({ loadGraph: vi.fn() }));
vi.mock('../../graphrag/embedding.js', () => ({ embedTexts: vi.fn() }));

import { registerGraphragTools } from './graphrag-tools.js';
import { ingestDataset } from '../../graphrag/ingest.js';
import { loadOntology } from '../../graphrag/ontology-source.js';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

describe('graphrag_query 도구', () => {
  it('retrieve 결과를 jsonResult로 반환한다', async () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const jsonResult = ((data: any) => ({ content: [{ type: 'text', text: JSON.stringify(data) }] })) as unknown as JsonResultFn;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const safeTool = ((_n: string, _d: string, _s: any, handler: any) => ({ name: _n, handler })) as unknown as SafeToolFn;
    const apiClient = { searchDocuments: vi.fn() } as unknown as FireHubApiClient;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const tools: any[] = registerGraphragTools(apiClient, safeTool, jsonResult);
    const query = tools.find((t) => t.name === 'graphrag_query');
    const out = await query.handler({ query: '원인?' });
    const payload = JSON.parse(out.content[0].text);
    expect(payload.subgraph.nodes[0].name).toBe('X');
    expect(payload.sourceChunks[0].fileName).toBe('r.md');
  });
});

describe('graphrag_ingest 도구 — 적재 이력 best-effort 기록', () => {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const jsonResult = ((data: any) => ({ content: [{ type: 'text', text: JSON.stringify(data) }] })) as unknown as JsonResultFn;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const safeTool = ((_n: string, _d: string, _s: any, handler: any) => ({ name: _n, handler })) as unknown as SafeToolFn;

  const ontology = { domain: 'd', schemaVersion: 3, entities: [], relations: [] };

  it('추출 실패가 0건이면 status=SUCCESS 로 recordGraphIngest 를 호출한다', async () => {
    vi.mocked(loadOntology).mockResolvedValue(ontology as never);
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
  });

  it('추출 실패가 있으면 status=PARTIAL 로 recordGraphIngest 를 호출한다', async () => {
    vi.mocked(loadOntology).mockResolvedValue(ontology as never);
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
    vi.mocked(loadOntology).mockResolvedValue(ontology as never);
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
