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

describe('Pipeline MCP Tools', () => {
  let client: FireHubApiClient;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let server: any;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    client = createMockClient();
    server = createFireHubMcpServer(client);
  });

  describe('list_pipelines', () => {
    it('calls apiClient.listPipelines with args', async () => {
      const mockData = { content: [], totalElements: 0 };
      (client.listPipelines as ReturnType<typeof vi.fn>).mockResolvedValue(mockData);
      const result = await invokeTool(server, 'list_pipelines', { page: 0, size: 10 });
      expect(client.listPipelines).toHaveBeenCalledWith({ page: 0, size: 10 });
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.listPipelines as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'list_pipelines', {});
      expect(result.isError).toBe(true);
    });
  });

  describe('get_pipeline', () => {
    it('calls apiClient.getPipeline with id', async () => {
      const result = await invokeTool(server, 'get_pipeline', { id: 1 });
      expect(client.getPipeline).toHaveBeenCalledWith(1);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.getPipeline as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'get_pipeline', { id: 1 });
      expect(result.isError).toBe(true);
    });
  });

  describe('create_pipeline', () => {
    it('calls apiClient.createPipeline with args', async () => {
      const args = {
        name: '테스트 파이프라인',
        steps: [{ name: 'step1', scriptType: 'SQL', scriptContent: 'SELECT 1' }],
      };
      const result = await invokeTool(server, 'create_pipeline', args);
      expect(client.createPipeline).toHaveBeenCalledWith(args);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.createPipeline as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'create_pipeline', {
        name: '파이프라인',
        steps: [],
      });
      expect(result.isError).toBe(true);
    });
  });

  describe('update_pipeline', () => {
    it('calls apiClient.updatePipeline with id and data', async () => {
      const args = { id: 5, name: '수정된 파이프라인', isActive: false };
      const result = await invokeTool(server, 'update_pipeline', args);
      expect(client.updatePipeline).toHaveBeenCalledWith(5, { name: '수정된 파이프라인', isActive: false });
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.updatePipeline as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'update_pipeline', { id: 5, name: '파이프라인' });
      expect(result.isError).toBe(true);
    });
  });

  describe('delete_pipeline', () => {
    it('calls apiClient.deletePipeline with id', async () => {
      const result = await invokeTool(server, 'delete_pipeline', { id: 3 });
      expect(client.deletePipeline).toHaveBeenCalledWith(3);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.deletePipeline as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'delete_pipeline', { id: 3 });
      expect(result.isError).toBe(true);
    });
  });

  describe('preview_api_call', () => {
    it('calls apiClient.previewApiCall with args', async () => {
      const args = {
        customUrl: 'https://api.example.com/data',
        method: 'GET',
        dataPath: '$.items',
      };
      const result = await invokeTool(server, 'preview_api_call', args);
      expect(client.previewApiCall).toHaveBeenCalledWith(args);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.previewApiCall as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'preview_api_call', {
        method: 'GET',
        dataPath: '$.data',
      });
      expect(result.isError).toBe(true);
    });
  });

  describe('execute_pipeline', () => {
    it('calls apiClient.executePipeline with id', async () => {
      const result = await invokeTool(server, 'execute_pipeline', { id: 7 });
      expect(client.executePipeline).toHaveBeenCalledWith(7);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.executePipeline as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'execute_pipeline', { id: 7 });
      expect(result.isError).toBe(true);
    });
  });

  describe('get_execution_status', () => {
    it('calls apiClient.getExecutionStatus with pipelineId and executionId', async () => {
      const result = await invokeTool(server, 'get_execution_status', { pipelineId: 1, executionId: 42 });
      expect(client.getExecutionStatus).toHaveBeenCalledWith(1, 42);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.getExecutionStatus as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'get_execution_status', { pipelineId: 1, executionId: 42 });
      expect(result.isError).toBe(true);
    });
  });
});
