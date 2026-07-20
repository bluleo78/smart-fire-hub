// graphrag_query MCP 도구 단위 테스트: retrieve를 스텁해 jsonResult 반환 형태를 검증한다.
import { describe, it, expect, vi } from 'vitest';

vi.mock('../../graphrag/retriever.js', () => ({
  retrieve: vi.fn().mockResolvedValue({
    nodes: [{ key: 'Incident:x', type: 'Incident', name: 'X' }],
    relations: [{ subject: 'X', type: 'CAUSED_BY', object: 'Y' }],
    sourceChunks: [{ chunkId: 1, fileName: 'r.md', content: '...' }],
  }),
}));

import { registerGraphragTools } from './graphrag-tools.js';
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
