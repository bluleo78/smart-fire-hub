import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createFireHubMcpServer } from '../firehub-mcp-server.js';
import { FireHubApiClient } from '../api-client.js';

// document-tools.test.ts와 동일한 목 클라이언트 헬퍼.
function createMockClient(): FireHubApiClient {
  const client = Object.create(FireHubApiClient.prototype);
  const methodNames = Object.getOwnPropertyNames(FireHubApiClient.prototype).filter(
    (name) => name !== 'constructor',
  );
  for (const name of methodNames) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (client as any)[name] = vi.fn().mockResolvedValue({ mocked: true });
  }
  return client as FireHubApiClient;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
async function invokeTool(server: any, toolName: string, args: Record<string, unknown> = {}) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const instance = server.instance as any;
  const entry = instance._registeredTools[toolName];
  if (!entry) throw new Error(`Tool ${toolName} not found in registered tools`);
  return entry.handler(args, {});
}

describe('find_datasets MCP tool', () => {
  let client: FireHubApiClient;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let server: any;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    client = createMockClient();
    server = createFireHubMcpServer(client);
  });

  it('searchDatasets를 인자대로 호출한다', async () => {
    const hits = [
      {
        datasetId: 42,
        name: '화재',
        description: null,
        storageType: 'TABLE',
        originType: 'SOURCE',
        tableName: 'fire',
        category: null,
        score: 0.9,
      },
    ];
    (client.searchDatasets as ReturnType<typeof vi.fn>).mockResolvedValue(hits);

    const result = await invokeTool(server, 'find_datasets', { query: '화재', mode: 'HYBRID', topK: 5 });

    expect(client.searchDatasets).toHaveBeenCalledWith('화재', 'HYBRID', 5, undefined);
    expect(result.content[0].type).toBe('text');
    expect(JSON.parse(result.content[0].text)).toEqual(hits);
    expect(result.isError).toBeUndefined();
  });

  it('query만으로도 호출된다', async () => {
    (client.searchDatasets as ReturnType<typeof vi.fn>).mockResolvedValue([]);

    await invokeTool(server, 'find_datasets', { query: '안전' });

    expect(client.searchDatasets).toHaveBeenCalledWith('안전', undefined, undefined, undefined);
  });

  it('실패 시 isError를 반환한다', async () => {
    (client.searchDatasets as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('검색 오류'));

    const result = await invokeTool(server, 'find_datasets', { query: 'x' });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain('검색 오류');
  });
});
