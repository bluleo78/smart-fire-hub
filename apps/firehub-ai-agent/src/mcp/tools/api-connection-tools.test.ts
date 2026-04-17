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

describe('API Connection MCP Tools', () => {
  let client: FireHubApiClient;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let server: any;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    client = createMockClient();
    server = createFireHubMcpServer(client);
  });

  describe('list_api_connections', () => {
    it('calls apiClient.listApiConnections', async () => {
      const result = await invokeTool(server, 'list_api_connections');
      expect(client.listApiConnections).toHaveBeenCalled();
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.listApiConnections as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'list_api_connections');
      expect(result.isError).toBe(true);
    });
  });

  describe('get_api_connection', () => {
    it('calls apiClient.getApiConnection with id', async () => {
      const result = await invokeTool(server, 'get_api_connection', { id: 2 });
      expect(client.getApiConnection).toHaveBeenCalledWith(2);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.getApiConnection as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'get_api_connection', { id: 2 });
      expect(result.isError).toBe(true);
    });
  });

  describe('create_api_connection', () => {
    it('calls apiClient.createApiConnection with args', async () => {
      const args = {
        name: 'Make.com API',
        authType: 'API_KEY',
        authConfig: { placement: 'header', headerName: 'X-Api-Key', apiKey: 'secret' },
        baseUrl: 'https://api.make.com/v2',
      };
      const result = await invokeTool(server, 'create_api_connection', args);
      expect(client.createApiConnection).toHaveBeenCalledWith(args);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.createApiConnection as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'create_api_connection', {
        name: 'Test',
        authType: 'BEARER',
        authConfig: { token: 'tok' },
        baseUrl: 'https://example.com',
      });
      expect(result.isError).toBe(true);
    });
  });

  describe('update_api_connection', () => {
    it('calls apiClient.updateApiConnection with id and data', async () => {
      const args = { id: 3, name: '수정된 연결', baseUrl: 'https://new.example.com' };
      const result = await invokeTool(server, 'update_api_connection', args);
      expect(client.updateApiConnection).toHaveBeenCalledWith(3, { name: '수정된 연결', baseUrl: 'https://new.example.com' });
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.updateApiConnection as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'update_api_connection', { id: 3 });
      expect(result.isError).toBe(true);
    });
  });

  describe('test_api_connection', () => {
    it('calls apiClient.testApiConnection with id', async () => {
      const result = await invokeTool(server, 'test_api_connection', { id: 4 });
      expect(client.testApiConnection).toHaveBeenCalledWith(4);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.testApiConnection as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'test_api_connection', { id: 4 });
      expect(result.isError).toBe(true);
    });
  });

  describe('delete_api_connection', () => {
    it('calls apiClient.deleteApiConnection with id', async () => {
      const result = await invokeTool(server, 'delete_api_connection', { id: 5 });
      expect(client.deleteApiConnection).toHaveBeenCalledWith(5);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.deleteApiConnection as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'delete_api_connection', { id: 5 });
      expect(result.isError).toBe(true);
    });
  });
});
