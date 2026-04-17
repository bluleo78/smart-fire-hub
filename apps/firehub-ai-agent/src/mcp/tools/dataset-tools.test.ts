import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createFireHubMcpServer } from '../firehub-mcp-server.js';
import { FireHubApiClient } from '../api-client.js';

/**
 * FireHubApiClient 모킹 헬퍼.
 * 프로토타입의 모든 메서드를 vi.fn() 으로 대체하여 실제 HTTP 호출 없이
 * MCP 도구가 API 클라이언트에 올바른 인수로 호출되는지 검증할 수 있게 한다.
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

/** 등록된 MCP 도구를 이름으로 찾아 핸들러를 호출한다. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
async function invokeTool(server: any, toolName: string, args: Record<string, unknown> = {}) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const instance = server.instance as any;
  const entry = instance._registeredTools[toolName];
  if (!entry) throw new Error(`Tool ${toolName} not found in registered tools`);
  return entry.handler(args, {});
}

describe('Dataset MCP Tools', () => {
  let client: FireHubApiClient;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let server: any;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    client = createMockClient();
    server = createFireHubMcpServer(client);
  });

  describe('delete_dataset', () => {
    it('calls apiClient.deleteDataset with the provided id', async () => {
      (client.deleteDataset as ReturnType<typeof vi.fn>).mockResolvedValue({ success: true });

      const result = await invokeTool(server, 'delete_dataset', { id: 42 });

      // 올바른 id로 API 클라이언트가 호출되었는지 확인
      expect(client.deleteDataset).toHaveBeenCalledWith(42);
      expect(result.isError).toBeFalsy();

      // 응답은 success + 삭제된 datasetId 를 포함해야 한다
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed).toEqual({ success: true, datasetId: 42 });
    });

    it('returns isError on API failure', async () => {
      (client.deleteDataset as ReturnType<typeof vi.fn>).mockRejectedValue(
        new Error('API 오류 (404): Dataset not found'),
      );

      const result = await invokeTool(server, 'delete_dataset', { id: 999 });

      // safeTool 래퍼가 에러를 isError 응답으로 변환해야 한다
      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain('Dataset not found');
    });
  });

  describe('add_dataset_column', () => {
    it('calls apiClient.addDatasetColumn with column payload extracted from args', async () => {
      const mockResp = {
        id: 99,
        columnName: 'lat',
        displayName: '위도',
        dataType: 'DECIMAL',
        isNullable: true,
        isIndexed: false,
        columnOrder: 5,
        isPrimaryKey: false,
      };
      (client.addDatasetColumn as ReturnType<typeof vi.fn>).mockResolvedValue(mockResp);

      const result = await invokeTool(server, 'add_dataset_column', {
        datasetId: 42,
        columnName: 'lat',
        displayName: '위도',
        dataType: 'DECIMAL',
        isNullable: true,
      });

      // datasetId는 추출되고 나머지는 column payload로 전달된다
      expect(client.addDatasetColumn).toHaveBeenCalledWith(42, {
        columnName: 'lat',
        displayName: '위도',
        dataType: 'DECIMAL',
        isNullable: true,
      });
      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.id).toBe(99);
    });

    it('returns isError on API failure', async () => {
      (client.addDatasetColumn as ReturnType<typeof vi.fn>).mockRejectedValue(
        new Error('API 오류 (400): Invalid column name'),
      );

      const result = await invokeTool(server, 'add_dataset_column', {
        datasetId: 42,
        columnName: 'Bad Name',
        displayName: 'X',
        dataType: 'TEXT',
      });

      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain('Invalid column name');
    });
  });

  describe('drop_dataset_column', () => {
    it('calls apiClient.dropDatasetColumn with datasetId and columnId', async () => {
      (client.dropDatasetColumn as ReturnType<typeof vi.fn>).mockResolvedValue({ success: true });

      const result = await invokeTool(server, 'drop_dataset_column', {
        datasetId: 42,
        columnId: 99,
      });

      expect(client.dropDatasetColumn).toHaveBeenCalledWith(42, 99);
      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed).toEqual({ success: true });
    });

    it('returns isError on API failure', async () => {
      (client.dropDatasetColumn as ReturnType<typeof vi.fn>).mockRejectedValue(
        new Error('API 오류 (404): Column not found'),
      );

      const result = await invokeTool(server, 'drop_dataset_column', {
        datasetId: 42,
        columnId: 999,
      });

      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain('Column not found');
    });
  });

  describe('get_dataset_references', () => {
    it('calls apiClient.getDatasetReferences with the provided id', async () => {
      const mockResp = {
        datasetId: 42,
        pipelines: [{ id: 1, name: 'daily_import' }],
        dashboards: [{ id: 2, name: 'ops_overview' }],
        proactiveJobs: [],
        totalCount: 2,
      };
      (client.getDatasetReferences as ReturnType<typeof vi.fn>).mockResolvedValue(mockResp);

      const result = await invokeTool(server, 'get_dataset_references', { id: 42 });

      // 올바른 id로 API 클라이언트가 호출되었는지 확인
      expect(client.getDatasetReferences).toHaveBeenCalledWith(42);
      expect(result.isError).toBeFalsy();

      // 응답은 DatasetReferencesResponse 형태 그대로 반환되어야 한다
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.totalCount).toBe(2);
      expect(parsed.pipelines[0].name).toBe('daily_import');
      expect(parsed.dashboards[0].name).toBe('ops_overview');
    });

    it('returns isError on API failure', async () => {
      (client.getDatasetReferences as ReturnType<typeof vi.fn>).mockRejectedValue(
        new Error('API 오류 (404): Dataset not found'),
      );

      const result = await invokeTool(server, 'get_dataset_references', { id: 999 });

      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain('Dataset not found');
    });
  });

  describe('get_dataset', () => {
    it('calls apiClient.getDataset with id', async () => {
      const mockData = { id: 1, name: '화재 데이터셋' };
      (client.getDataset as ReturnType<typeof vi.fn>).mockResolvedValue(mockData);
      const result = await invokeTool(server, 'get_dataset', { id: 1 });
      expect(client.getDataset).toHaveBeenCalledWith(1);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.getDataset as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('Not found'));
      const result = await invokeTool(server, 'get_dataset', { id: 999 });
      expect(result.isError).toBe(true);
    });
  });

  describe('update_dataset', () => {
    it('calls apiClient.updateDataset with id and data', async () => {
      const args = { id: 2, name: '수정된 데이터셋', description: '설명' };
      (client.updateDataset as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 2, name: '수정된 데이터셋' });
      const result = await invokeTool(server, 'update_dataset', args);
      expect(client.updateDataset).toHaveBeenCalledWith(2, { name: '수정된 데이터셋', description: '설명' });
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.updateDataset as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'update_dataset', { id: 2, name: '데이터셋' });
      expect(result.isError).toBe(true);
    });
  });

  // --- tool registration ---
  it('dataset tools are registered in the MCP server', () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const registeredTools = Object.keys((server.instance as any)._registeredTools);
    expect(registeredTools).toContain('list_datasets');
    expect(registeredTools).toContain('get_dataset');
    expect(registeredTools).toContain('query_dataset_data');
    expect(registeredTools).toContain('create_dataset');
    expect(registeredTools).toContain('update_dataset');
    expect(registeredTools).toContain('delete_dataset');
    expect(registeredTools).toContain('add_dataset_column');
    expect(registeredTools).toContain('drop_dataset_column');
    expect(registeredTools).toContain('get_dataset_references');
  });
});
