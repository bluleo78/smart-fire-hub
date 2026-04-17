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

describe('Misc MCP Tools', () => {
  let client: FireHubApiClient;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let server: any;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    client = createMockClient();
    server = createFireHubMcpServer(client);
  });

  describe('list_imports', () => {
    it('calls apiClient.listImports with datasetId', async () => {
      const result = await invokeTool(server, 'list_imports', { datasetId: 5 });
      expect(client.listImports).toHaveBeenCalledWith(5);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.listImports as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'list_imports', { datasetId: 5 });
      expect(result.isError).toBe(true);
    });
  });

  describe('get_dashboard', () => {
    it('calls apiClient.getDashboard', async () => {
      const result = await invokeTool(server, 'get_dashboard');
      expect(client.getDashboard).toHaveBeenCalled();
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.getDashboard as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'get_dashboard');
      expect(result.isError).toBe(true);
    });
  });
});
