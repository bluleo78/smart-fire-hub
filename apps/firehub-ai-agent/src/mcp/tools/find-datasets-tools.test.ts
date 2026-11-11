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

  it('storageType 필터로 FILE 을 전달할 수 있다 (enum 이 FILE 을 허용)', async () => {
    (client.searchDatasets as ReturnType<typeof vi.fn>).mockResolvedValue([]);

    // FILE 은 과거 enum(['TABLE','DOCUMENT'])에서 런타임 거부되던 값 — 이제 통과해야 한다.
    const result = await invokeTool(server, 'find_datasets', { query: '첨부파일', storageType: 'FILE' });

    expect(result.isError).toBeUndefined();
    expect(client.searchDatasets).toHaveBeenCalledWith('첨부파일', undefined, undefined, 'FILE');
  });

  it('storageType enum 이 TABLE/DOCUMENT/FILE 을 허용하고 그 외는 거부한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const entry = (server.instance as any)._registeredTools['find_datasets'];
    const shape = entry.inputSchema.shape as Record<string, { safeParse: (v: unknown) => { success: boolean } }>;
    expect(shape.storageType.safeParse('TABLE').success).toBe(true);
    expect(shape.storageType.safeParse('DOCUMENT').success).toBe(true);
    expect(shape.storageType.safeParse('FILE').success).toBe(true);
    expect(shape.storageType.safeParse('INVALID').success).toBe(false);
  });

  it('실패 시 isError를 반환한다', async () => {
    (client.searchDatasets as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('검색 오류'));

    const result = await invokeTool(server, 'find_datasets', { query: 'x' });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain('검색 오류');
  });
});

// WD-495: 플랫폼 DB(Tibero 등)가 검색을 끈 설치본에서 find_datasets 의 동작.
describe('find_datasets — 플랫폼 DB 검색 기능 상태', () => {
  let client: FireHubApiClient;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let server: any;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    client = createMockClient();
    (client.searchDatasets as ReturnType<typeof vi.fn>).mockResolvedValue([]);
    server = createFireHubMcpServer(client);
  });

  const caps = () => client.getPlatformDbCapabilities as ReturnType<typeof vi.fn>;

  it('둘 다 꺼짐: 검색 API 를 부르지 않고 안내문을 오류 아닌 결과로 돌려준다', async () => {
    caps().mockResolvedValue({ vectorSearch: false, trigramSearch: false });
    const result = await invokeTool(server, 'find_datasets', { query: '화재' });

    expect(client.searchDatasets).not.toHaveBeenCalled();
    expect(result.isError).toBeFalsy();
    expect(result.content[0].text).toBe('이 설치본에서는 검색 기능(벡터·키워드)이 꺼져 있어 쓸 수 없습니다.');
  });

  it('벡터만 꺼짐: HYBRID 요청을 KEYWORD 로 바꿔 부르고 바꾼 사실을 덧붙인다', async () => {
    caps().mockResolvedValue({ vectorSearch: false, trigramSearch: true });
    const result = await invokeTool(server, 'find_datasets', { query: '화재', mode: 'HYBRID' });

    expect(client.searchDatasets).toHaveBeenCalledWith('화재', 'KEYWORD', undefined, undefined);
    expect(result.isError).toBeFalsy();
    // 첫 블록은 기존과 같은 JSON 결과 그대로, 안내는 두 번째 블록.
    expect(JSON.parse(result.content[0].text)).toEqual([]);
    expect(result.content[1].text).toContain('KEYWORD');
  });

  it('벡터만 꺼짐: SEMANTIC 요청도 KEYWORD 로 바꾼다', async () => {
    caps().mockResolvedValue({ vectorSearch: false, trigramSearch: true });
    const result = await invokeTool(server, 'find_datasets', { query: '화재', mode: 'SEMANTIC' });

    expect(client.searchDatasets).toHaveBeenCalledWith('화재', 'KEYWORD', undefined, undefined);
    expect(result.content[1].text).toContain('벡터');
  });

  it('벡터만 꺼짐: KEYWORD 요청은 그대로 부르고 안내를 붙이지 않는다', async () => {
    caps().mockResolvedValue({ vectorSearch: false, trigramSearch: true });
    const result = await invokeTool(server, 'find_datasets', { query: '화재', mode: 'KEYWORD' });

    expect(client.searchDatasets).toHaveBeenCalledWith('화재', 'KEYWORD', undefined, undefined);
    expect(result.content).toHaveLength(1);
  });

  it('트라이그램만 꺼짐: 모드 생략(HYBRID)을 SEMANTIC 으로 바꿔 부르고 안내한다', async () => {
    caps().mockResolvedValue({ vectorSearch: true, trigramSearch: false });
    const result = await invokeTool(server, 'find_datasets', { query: '화재' });

    expect(client.searchDatasets).toHaveBeenCalledWith('화재', 'SEMANTIC', undefined, undefined);
    expect(result.content[1].text).toContain('SEMANTIC');
  });

  it('트라이그램만 꺼짐: KEYWORD 요청을 SEMANTIC 으로 바꾼다', async () => {
    caps().mockResolvedValue({ vectorSearch: true, trigramSearch: false });
    const result = await invokeTool(server, 'find_datasets', { query: '화재', mode: 'KEYWORD' });

    expect(client.searchDatasets).toHaveBeenCalledWith('화재', 'SEMANTIC', undefined, undefined);
    expect(result.content[1].text).toContain('키워드 검색이 꺼져');
  });

  it('트라이그램만 꺼짐: SEMANTIC 요청은 그대로 부르고 안내를 붙이지 않는다', async () => {
    caps().mockResolvedValue({ vectorSearch: true, trigramSearch: false });
    const result = await invokeTool(server, 'find_datasets', { query: '화재', mode: 'SEMANTIC' });

    expect(client.searchDatasets).toHaveBeenCalledWith('화재', 'SEMANTIC', undefined, undefined);
    expect(result.content).toHaveLength(1);
  });

  it('둘 다 켜짐: 그대로 부른다', async () => {
    caps().mockResolvedValue({ vectorSearch: true, trigramSearch: true });
    const result = await invokeTool(server, 'find_datasets', { query: '화재', mode: 'KEYWORD' });

    expect(client.searchDatasets).toHaveBeenCalledWith('화재', 'KEYWORD', undefined, undefined);
    expect(JSON.parse(result.content[0].text)).toEqual([]);
  });
});
