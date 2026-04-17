import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createFireHubMcpServer } from '../firehub-mcp-server.js';
import { FireHubApiClient } from '../api-client.js';

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

// eslint-disable-next-line @typescript-eslint/no-explicit-any
async function invokeTool(server: any, toolName: string, args: Record<string, unknown> = {}) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const instance = server.instance as any;
  const entry = instance._registeredTools[toolName];
  if (!entry) throw new Error(`Tool ${toolName} not found`);
  return entry.handler(args, {});
}

describe('Data MCP Tools', () => {
  let client: FireHubApiClient;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let server: any;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    client = createMockClient();
    server = createFireHubMcpServer(client);
  });

  describe('execute_sql_query', () => {
    it('calls apiClient.executeQuery with datasetId, sql, and maxRows', async () => {
      const result = await invokeTool(server, 'execute_sql_query', {
        datasetId: 1,
        sql: 'SELECT * FROM data."myTable"',
        maxRows: 100,
      });
      expect(client.executeQuery).toHaveBeenCalledWith(1, 'SELECT * FROM data."myTable"', 100);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.executeQuery as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('SQL 오류'));
      const result = await invokeTool(server, 'execute_sql_query', { datasetId: 1, sql: 'SELECT 1' });
      expect(result.isError).toBe(true);
    });
  });

  describe('add_row', () => {
    it('calls apiClient.addRow with datasetId and data', async () => {
      const args = { datasetId: 2, data: { name: '홍길동', age: 30 } };
      const result = await invokeTool(server, 'add_row', args);
      expect(client.addRow).toHaveBeenCalledWith(2, { name: '홍길동', age: 30 });
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.addRow as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'add_row', { datasetId: 2, data: {} });
      expect(result.isError).toBe(true);
    });
  });

  describe('add_rows', () => {
    it('calls apiClient.addRowsBatch with datasetId and rows', async () => {
      const args = { datasetId: 3, rows: [{ name: 'A' }, { name: 'B' }] };
      const result = await invokeTool(server, 'add_rows', args);
      expect(client.addRowsBatch).toHaveBeenCalledWith(3, [{ name: 'A' }, { name: 'B' }]);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.addRowsBatch as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'add_rows', { datasetId: 3, rows: [{ name: 'A' }] });
      expect(result.isError).toBe(true);
    });
  });

  describe('update_row', () => {
    it('calls apiClient.updateRow with datasetId, rowId, and data', async () => {
      const args = { datasetId: 4, rowId: 10, data: { name: '수정값' } };
      const result = await invokeTool(server, 'update_row', args);
      expect(client.updateRow).toHaveBeenCalledWith(4, 10, { name: '수정값' });
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.updateRow as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'update_row', { datasetId: 4, rowId: 10, data: {} });
      expect(result.isError).toBe(true);
    });
  });

  describe('delete_rows', () => {
    it('calls apiClient.deleteRows with datasetId and rowIds', async () => {
      const args = { datasetId: 5, rowIds: [1, 2, 3] };
      const result = await invokeTool(server, 'delete_rows', args);
      expect(client.deleteRows).toHaveBeenCalledWith(5, [1, 2, 3]);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.deleteRows as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'delete_rows', { datasetId: 5, rowIds: [1] });
      expect(result.isError).toBe(true);
    });
  });

  describe('truncate_dataset', () => {
    it('calls apiClient.truncateDataset with datasetId', async () => {
      const result = await invokeTool(server, 'truncate_dataset', { datasetId: 6 });
      expect(client.truncateDataset).toHaveBeenCalledWith(6);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.truncateDataset as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'truncate_dataset', { datasetId: 6 });
      expect(result.isError).toBe(true);
    });
  });

  describe('get_row_count', () => {
    it('calls apiClient.getRowCount with datasetId', async () => {
      const result = await invokeTool(server, 'get_row_count', { datasetId: 7 });
      expect(client.getRowCount).toHaveBeenCalledWith(7);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.getRowCount as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'get_row_count', { datasetId: 7 });
      expect(result.isError).toBe(true);
    });
  });

  describe('replace_dataset_data', () => {
    it('calls apiClient.replaceDatasetData with datasetId and rows', async () => {
      const args = { datasetId: 8, rows: [{ col: 'val' }] };
      const result = await invokeTool(server, 'replace_dataset_data', args);
      expect(client.replaceDatasetData).toHaveBeenCalledWith(8, [{ col: 'val' }]);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.replaceDatasetData as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'replace_dataset_data', { datasetId: 8, rows: [{}] });
      expect(result.isError).toBe(true);
    });
  });
});
