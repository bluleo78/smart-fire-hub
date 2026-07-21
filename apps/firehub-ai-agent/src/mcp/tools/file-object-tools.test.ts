import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createFireHubMcpServer } from '../firehub-mcp-server.js';
import { FireHubApiClient } from '../api-client.js';

// find-datasets-tools.test.ts 와 동일한 목 클라이언트 헬퍼.
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

describe('FILE(오브젝트) 데이터셋 MCP 도구', () => {
  let client: FireHubApiClient;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let server: any;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    client = createMockClient();
    server = createFireHubMcpServer(client);
  });

  it('세 도구가 MCP 서버에 등록된다', () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const registered = Object.keys((server.instance as any)._registeredTools);
    expect(registered).toContain('list_dataset_files');
    expect(registered).toContain('summarize_dataset_files');
    expect(registered).toContain('get_dataset_file_url');
  });

  describe('list_dataset_files', () => {
    it('datasetId·cursor·limit 를 apiClient.listDatasetObjects 에 그대로 전달한다', async () => {
      (client.listDatasetObjects as ReturnType<typeof vi.fn>).mockResolvedValue({
        objects: [{ key: 'p/a.csv', name: 'a.csv', size: 10, lastModified: '2026-07-01T00:00:00Z' }],
        nextToken: 'p/a.csv',
        hasMore: true,
      });

      const result = await invokeTool(server, 'list_dataset_files', {
        datasetId: 7,
        cursor: 'tok',
        limit: 100,
      });

      expect(client.listDatasetObjects).toHaveBeenCalledWith(7, 'tok', 100);
      expect(result.isError).toBeUndefined();
      expect(JSON.parse(result.content[0].text).hasMore).toBe(true);
    });
  });

  describe('summarize_dataset_files', () => {
    it('여러 페이지를 순회해 개수·용량·확장자 분포를 집계한다 (전수 스캔이면 capped=false)', async () => {
      const fn = client.listDatasetObjects as ReturnType<typeof vi.fn>;
      // 1페이지: 2건(hasMore) → 2페이지: 1건(끝)
      fn.mockResolvedValueOnce({
        objects: [
          { key: 'p/a.csv', name: 'a.csv', size: 100, lastModified: '2026-07-01T00:00:00Z' },
          { key: 'p/b.pdf', name: 'b.pdf', size: 300, lastModified: '2026-07-03T00:00:00Z' },
        ],
        nextToken: 'p/b.pdf',
        hasMore: true,
      });
      fn.mockResolvedValueOnce({
        objects: [{ key: 'p/c.csv', name: 'c.csv', size: 200, lastModified: '2026-07-02T00:00:00Z' }],
        nextToken: null,
        hasMore: false,
      });

      const result = await invokeTool(server, 'summarize_dataset_files', { datasetId: 9 });
      const body = JSON.parse(result.content[0].text);

      expect(result.isError).toBeUndefined();
      expect(fn).toHaveBeenCalledTimes(2);
      expect(body.scannedCount).toBe(3);
      expect(body.capped).toBe(false);
      expect(body.countLabel).toBe('3');
      expect(body.totalSize).toBe(600);
      // 확장자별: csv 2건(300B), pdf 1건(300B) — 개수 내림차순이라 csv 먼저
      expect(body.byExtension[0]).toMatchObject({ ext: 'csv', count: 2, size: 300 });
      expect(body.byExtension).toContainEqual(expect.objectContaining({ ext: 'pdf', count: 1 }));
      // 최대 파일 = b.pdf(300)
      expect(body.largest[0]).toMatchObject({ name: 'b.pdf', size: 300 });
      // 최근 수정 = b.pdf(07-03)
      expect(body.recent[0]).toMatchObject({ name: 'b.pdf' });
    });

    it('스캔 상한을 넘겨 더 남아 있으면 capped=true 와 ≥N 라벨을 낸다', async () => {
      const fn = client.listDatasetObjects as ReturnType<typeof vi.fn>;
      // 항상 hasMore=true 인 페이지를 반환 → 상한(10페이지)까지 순회 후 capped
      fn.mockResolvedValue({
        objects: [{ key: 'p/x', name: 'x', size: 1, lastModified: '2026-07-01T00:00:00Z' }],
        nextToken: 'p/x',
        hasMore: true,
      });

      const result = await invokeTool(server, 'summarize_dataset_files', { datasetId: 9 });
      const body = JSON.parse(result.content[0].text);

      // 최대 10페이지까지만 호출(무한 순회 방지)
      expect(fn).toHaveBeenCalledTimes(10);
      expect(body.capped).toBe(true);
      expect(body.countLabel).toMatch(/^≥\d+/);
    });

    it('빈 데이터셋도 0 집계로 정상 반환한다', async () => {
      (client.listDatasetObjects as ReturnType<typeof vi.fn>).mockResolvedValue({
        objects: [],
        nextToken: null,
        hasMore: false,
      });

      const result = await invokeTool(server, 'summarize_dataset_files', { datasetId: 9 });
      const body = JSON.parse(result.content[0].text);

      expect(result.isError).toBeUndefined();
      expect(body.scannedCount).toBe(0);
      expect(body.totalSize).toBe(0);
      expect(body.byExtension).toEqual([]);
    });

    it('백엔드 오류(FILE 데이터셋 아님 등)는 isError 로 반환한다', async () => {
      (client.listDatasetObjects as ReturnType<typeof vi.fn>).mockRejectedValue(
        new Error('FILE 데이터셋이 아닙니다'),
      );

      const result = await invokeTool(server, 'summarize_dataset_files', { datasetId: 9 });

      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain('FILE 데이터셋이 아닙니다');
    });
  });

  describe('get_dataset_file_url', () => {
    it('datasetId·key 를 그대로 전달하고 presigned URL 을 반환한다', async () => {
      (client.getDatasetObjectUrl as ReturnType<typeof vi.fn>).mockResolvedValue({
        url: 'https://minio.example/firehub-files/p/a.csv?sig=xxx',
        expiresInSeconds: 300,
      });

      const result = await invokeTool(server, 'get_dataset_file_url', {
        datasetId: 7,
        key: 'p/a.csv',
      });

      expect(client.getDatasetObjectUrl).toHaveBeenCalledWith(7, 'p/a.csv');
      expect(result.isError).toBeUndefined();
      expect(JSON.parse(result.content[0].text).expiresInSeconds).toBe(300);
    });
  });
});
