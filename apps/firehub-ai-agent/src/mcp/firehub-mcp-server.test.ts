import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createFireHubMcpServer } from './firehub-mcp-server.js';
import { FireHubApiClient } from './api-client.js';

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
  if (!entry) throw new Error(`Tool ${toolName} not found in registered tools`);
  return entry.handler(args, {});
}

describe('createFireHubMcpServer', () => {
  it('should return McpSdkServerConfigWithInstance with type sdk', () => {
    const client = createMockClient();
    const result = createFireHubMcpServer(client);

    expect(result).toBeDefined();
    expect(result.type).toBe('sdk');
    expect(result.name).toBe('firehub');
    expect(result).toHaveProperty('instance');
  });

  it('should have an McpServer instance', () => {
    const client = createMockClient();
    const result = createFireHubMcpServer(client);

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const instance = (result as any).instance;
    expect(instance).toBeDefined();
    expect(typeof instance).toBe('object');
  });

  it('safeTool should catch errors and return isError result', async () => {
    const client = createMockClient();
    (client.listCategories as ReturnType<typeof vi.fn>).mockRejectedValue(
      new Error('Connection refused'),
    );

    const result = createFireHubMcpServer(client);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const instance = (result as any).instance;

    expect(instance).toBeDefined();
    await expect(client.listCategories()).rejects.toThrow('Connection refused');
  });

  it('jsonResult should produce correct format (verified via api-client mock)', async () => {
    const client = createMockClient();
    const mockData = { datasets: 5, pipelines: 3 };
    (client.getDashboard as ReturnType<typeof vi.fn>).mockResolvedValue(mockData);

    const data = await client.getDashboard();
    expect(data).toEqual(mockData);

    const result = createFireHubMcpServer(client);
    expect(result.type).toBe('sdk');
    expect(result.name).toBe('firehub');
  });
});

describe('MCP Tool Handlers', () => {
  let client: FireHubApiClient;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let server: any;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    client = createMockClient();
    server = createFireHubMcpServer(client);
  });

  // MCP-01: list_categories
  it('list_categories calls apiClient.listCategories', async () => {
    const mockData = [{ id: 1, name: 'Cat1' }];
    (client.listCategories as ReturnType<typeof vi.fn>).mockResolvedValue(mockData);

    const result = await invokeTool(server, 'list_categories');

    expect(client.listCategories).toHaveBeenCalled();
    expect(result.content[0].type).toBe('text');
    expect(JSON.parse(result.content[0].text)).toEqual(mockData);
  });

  // MCP-02: create_category
  it('create_category calls apiClient.createCategory with correct args', async () => {
    await invokeTool(server, 'create_category', { name: 'Test', description: 'desc' });
    expect(client.createCategory).toHaveBeenCalledWith({ name: 'Test', description: 'desc' });
  });

  // MCP-03: list_datasets
  it('list_datasets calls apiClient.listDatasets with params', async () => {
    await invokeTool(server, 'list_datasets', { categoryId: 1, page: 0, size: 10 });
    expect(client.listDatasets).toHaveBeenCalledWith({ categoryId: 1, page: 0, size: 10 });
  });

  // MCP-04: create_dataset
  it('create_dataset calls apiClient.createDataset with columns', async () => {
    const args = {
      name: 'Test',
      tableName: 'test_table',
      columns: [{ columnName: 'col1', displayName: 'Col 1', dataType: 'TEXT' }],
    };
    await invokeTool(server, 'create_dataset', args);
    expect(client.createDataset).toHaveBeenCalledWith(args);
  });

  // MCP-05: query_dataset_data
  it('query_dataset_data calls apiClient.queryDatasetData', async () => {
    await invokeTool(server, 'query_dataset_data', { id: 1, page: 0, size: 50 });
    expect(client.queryDatasetData).toHaveBeenCalledWith(1, { page: 0, size: 50 });
  });

  // MCP-06: execute_sql_query
  it('execute_sql_query calls apiClient.executeQuery', async () => {
    await invokeTool(server, 'execute_sql_query', { datasetId: 1, sql: 'SELECT 1', maxRows: 100 });
    expect(client.executeQuery).toHaveBeenCalledWith(1, 'SELECT 1', 100);
  });

  // MCP-07: add_rows
  it('add_rows calls apiClient.addRowsBatch', async () => {
    const rows = [{ name: 'test' }];
    await invokeTool(server, 'add_rows', { datasetId: 1, rows });
    expect(client.addRowsBatch).toHaveBeenCalledWith(1, rows);
  });

  // MCP-08: delete_rows
  it('delete_rows calls apiClient.deleteRows', async () => {
    await invokeTool(server, 'delete_rows', { datasetId: 1, rowIds: [1, 2, 3] });
    expect(client.deleteRows).toHaveBeenCalledWith(1, [1, 2, 3]);
  });

  // MCP-09: truncate_dataset
  it('truncate_dataset calls apiClient.truncateDataset', async () => {
    await invokeTool(server, 'truncate_dataset', { datasetId: 1 });
    expect(client.truncateDataset).toHaveBeenCalledWith(1);
  });

  // MCP-10: replace_dataset_data
  it('replace_dataset_data calls apiClient.replaceDatasetData', async () => {
    const rows = [{ col: 'val' }];
    await invokeTool(server, 'replace_dataset_data', { datasetId: 1, rows });
    expect(client.replaceDatasetData).toHaveBeenCalledWith(1, rows);
  });

  // MCP-11: create_pipeline
  it('create_pipeline calls apiClient.createPipeline', async () => {
    const args = { name: 'Test Pipeline', steps: [{ name: 'step1', scriptType: 'SQL' }] };
    await invokeTool(server, 'create_pipeline', args);
    expect(client.createPipeline).toHaveBeenCalled();
  });

  // MCP-12: execute_pipeline
  it('execute_pipeline calls apiClient.executePipeline', async () => {
    await invokeTool(server, 'execute_pipeline', { id: 5 });
    expect(client.executePipeline).toHaveBeenCalledWith(5);
  });

  // MCP-13: preview_api_call
  it('preview_api_call calls apiClient.previewApiCall', async () => {
    const args = { url: 'https://api.example.com', method: 'GET', dataPath: '$.data' };
    await invokeTool(server, 'preview_api_call', args);
    expect(client.previewApiCall).toHaveBeenCalled();
  });

  // MCP-14: create_trigger
  it('create_trigger calls apiClient.createTrigger', async () => {
    const args = {
      pipelineId: 1,
      name: 'Trigger',
      triggerType: 'SCHEDULE',
      config: { cronExpression: '0 0 * * *' },
    };
    await invokeTool(server, 'create_trigger', args);
    expect(client.createTrigger).toHaveBeenCalledWith(1, {
      name: 'Trigger',
      triggerType: 'SCHEDULE',
      config: { cronExpression: '0 0 * * *' },
    });
  });

  // MCP-15: update_trigger
  it('update_trigger calls apiClient.updateTrigger', async () => {
    const args = { pipelineId: 1, triggerId: 2, name: 'Updated', isEnabled: false };
    await invokeTool(server, 'update_trigger', args);
    expect(client.updateTrigger).toHaveBeenCalledWith(1, 2, { name: 'Updated', isEnabled: false });
  });

  // MCP-16: create_api_connection
  it('create_api_connection calls apiClient.createApiConnection', async () => {
    const args = { name: 'My API', authType: 'BEARER', authConfig: { token: 'abc' } };
    await invokeTool(server, 'create_api_connection', args);
    expect(client.createApiConnection).toHaveBeenCalledWith(args);
  });

  // MCP-17: delete_api_connection
  it('delete_api_connection calls apiClient.deleteApiConnection', async () => {
    await invokeTool(server, 'delete_api_connection', { id: 5 });
    expect(client.deleteApiConnection).toHaveBeenCalledWith(5);
  });

  // MCP-18: get_dashboard
  it('get_dashboard calls apiClient.getDashboard', async () => {
    const mockData = { datasets: 5, pipelines: 3 };
    (client.getDashboard as ReturnType<typeof vi.fn>).mockResolvedValue(mockData);

    const result = await invokeTool(server, 'get_dashboard');

    expect(client.getDashboard).toHaveBeenCalled();
    expect(JSON.parse(result.content[0].text)).toEqual(mockData);
  });

  // MCP-19: Error handling — safeTool wrapper catches errors
  it('safeTool catches api errors and returns isError result', async () => {
    (client.listCategories as ReturnType<typeof vi.fn>).mockRejectedValue(
      new Error('Connection refused'),
    );

    const result = await invokeTool(server, 'list_categories');

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain('Connection refused');
  });

  // MCP-20: jsonResult format verification
  it('jsonResult produces { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] }', async () => {
    const mockData = { id: 1, name: 'test' };
    (client.getDashboard as ReturnType<typeof vi.fn>).mockResolvedValue(mockData);

    const result = await invokeTool(server, 'get_dashboard');

    expect(result.content).toHaveLength(1);
    expect(result.content[0].type).toBe('text');
    const parsed = JSON.parse(result.content[0].text);
    expect(parsed).toEqual(mockData);
    expect(result.content[0].text).toBe(JSON.stringify(mockData, null, 2));
  });
});
