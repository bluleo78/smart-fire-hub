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
  if (!entry) throw new Error(`Tool ${toolName} not found in registered tools`);
  return entry.handler(args, {});
}

describe('UI MCP Tools', () => {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let server: any;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const client = createMockClient();
    server = createFireHubMcpServer(client);
  });

  // --- show_dataset ---
  describe('show_dataset', () => {
    it('returns displayed: true with datasetId', async () => {
      const result = await invokeTool(server, 'show_dataset', { datasetId: 42 });

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.displayed).toBe(true);
      expect(parsed.datasetId).toBe(42);
    });

    it('works with different datasetId values', async () => {
      const result = await invokeTool(server, 'show_dataset', { datasetId: 1 });

      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.displayed).toBe(true);
      expect(parsed.datasetId).toBe(1);
    });
  });

  // --- show_table ---
  describe('show_table', () => {
    it('returns displayed: true with rowCount and totalRows', async () => {
      const result = await invokeTool(server, 'show_table', {
        sql: 'SELECT id, name FROM users',
        columns: ['id', 'name'],
        rows: [
          { id: 1, name: 'Alice' },
          { id: 2, name: 'Bob' },
        ],
      });

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.displayed).toBe(true);
      expect(parsed.rowCount).toBe(2);
      expect(parsed.totalRows).toBe(2);
    });

    it('uses provided totalRows when given', async () => {
      const result = await invokeTool(server, 'show_table', {
        sql: 'SELECT id, name FROM users LIMIT 10',
        columns: ['id', 'name'],
        rows: [
          { id: 1, name: 'Alice' },
          { id: 2, name: 'Bob' },
        ],
        totalRows: 100,
      });

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.displayed).toBe(true);
      expect(parsed.rowCount).toBe(2);
      expect(parsed.totalRows).toBe(100);
    });

    it('accepts optional title', async () => {
      const result = await invokeTool(server, 'show_table', {
        title: '사용자 목록',
        sql: 'SELECT id FROM users',
        columns: ['id'],
        rows: [{ id: 1 }],
      });

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.displayed).toBe(true);
      expect(parsed.rowCount).toBe(1);
    });

    it('returns rowCount 0 for empty rows', async () => {
      const result = await invokeTool(server, 'show_table', {
        sql: 'SELECT id FROM users WHERE 1=0',
        columns: ['id'],
        rows: [],
      });

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.rowCount).toBe(0);
      expect(parsed.totalRows).toBe(0);
    });
  });

  // --- navigate_to ---
  describe('navigate_to', () => {
    it('returns navigated: true with type and id for dataset', async () => {
      const result = await invokeTool(server, 'navigate_to', {
        type: 'dataset',
        id: 5,
        label: '화재 사고 데이터셋',
      });

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.navigated).toBe(true);
      expect(parsed.type).toBe('dataset');
      expect(parsed.id).toBe(5);
    });

    it('returns navigated: true for pipeline type', async () => {
      const result = await invokeTool(server, 'navigate_to', {
        type: 'pipeline',
        id: 10,
        label: 'ETL 파이프라인',
      });

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.navigated).toBe(true);
      expect(parsed.type).toBe('pipeline');
      expect(parsed.id).toBe(10);
    });

    it('returns navigated: true for dashboard type', async () => {
      const result = await invokeTool(server, 'navigate_to', {
        type: 'dashboard',
        id: 3,
        label: '주간 보고서',
      });

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.navigated).toBe(true);
      expect(parsed.type).toBe('dashboard');
      expect(parsed.id).toBe(3);
    });
  });

  // --- show_pipeline ---
  describe('show_pipeline', () => {
    it('returns displayed: true with pipelineId', async () => {
      const result = await invokeTool(server, 'show_pipeline', { pipelineId: 7 });

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.displayed).toBe(true);
      expect(parsed.pipelineId).toBe(7);
    });
  });

  // --- show_dataset_list ---
  describe('show_dataset_list', () => {
    it('returns displayed: true with count', async () => {
      const result = await invokeTool(server, 'show_dataset_list', {
        items: [
          { id: 1, name: '화재 데이터', datasetType: 'TABLE', rowCount: 100 },
          { id: 2, name: '소방서 위치', datasetType: 'TABLE' },
        ],
      });

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.displayed).toBe(true);
      expect(parsed.count).toBe(2);
    });

    it('returns count 0 for empty items', async () => {
      const result = await invokeTool(server, 'show_dataset_list', { items: [] });

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.displayed).toBe(true);
      expect(parsed.count).toBe(0);
    });
  });

  // --- show_pipeline_list ---
  describe('show_pipeline_list', () => {
    it('returns displayed: true with count', async () => {
      const result = await invokeTool(server, 'show_pipeline_list', {
        items: [
          { id: 1, name: 'ETL 파이프라인', isActive: true, stepCount: 3 },
          { id: 2, name: '집계 파이프라인', isActive: false, stepCount: 2, lastStatus: 'SUCCESS' },
        ],
      });

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.displayed).toBe(true);
      expect(parsed.count).toBe(2);
    });

    it('returns count 0 for empty items', async () => {
      const result = await invokeTool(server, 'show_pipeline_list', { items: [] });

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.displayed).toBe(true);
      expect(parsed.count).toBe(0);
    });
  });

  // --- show_dashboard_summary ---
  describe('show_dashboard_summary', () => {
    it('returns displayed: true with no args', async () => {
      const result = await invokeTool(server, 'show_dashboard_summary', {});

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.displayed).toBe(true);
    });
  });

  // --- show_activity ---
  describe('show_activity', () => {
    it('returns displayed: true with default size', async () => {
      const result = await invokeTool(server, 'show_activity', {});

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.displayed).toBe(true);
      expect(parsed.size).toBe(10);
    });

    it('returns displayed: true with custom size', async () => {
      const result = await invokeTool(server, 'show_activity', { size: 5 });

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.displayed).toBe(true);
      expect(parsed.size).toBe(5);
    });
  });

  // --- tool registration ---
  it('all 8 UI tools are registered in the MCP server', () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const registeredTools = Object.keys((server.instance as any)._registeredTools);
    expect(registeredTools).toContain('show_dataset');
    expect(registeredTools).toContain('show_table');
    expect(registeredTools).toContain('navigate_to');
    expect(registeredTools).toContain('show_pipeline');
    expect(registeredTools).toContain('show_dataset_list');
    expect(registeredTools).toContain('show_pipeline_list');
    expect(registeredTools).toContain('show_dashboard_summary');
    expect(registeredTools).toContain('show_activity');
  });
});
