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

describe('Category MCP Tools', () => {
  let client: FireHubApiClient;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let server: any;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    client = createMockClient();
    server = createFireHubMcpServer(client);
  });

  describe('list_categories', () => {
    it('calls apiClient.listCategories', async () => {
      const result = await invokeTool(server, 'list_categories');
      expect(client.listCategories).toHaveBeenCalled();
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.listCategories as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'list_categories');
      expect(result.isError).toBe(true);
    });
  });

  describe('create_category', () => {
    it('calls apiClient.createCategory with args', async () => {
      const args = { name: '화재 데이터', description: '화재 관련 데이터셋' };
      const result = await invokeTool(server, 'create_category', args);
      expect(client.createCategory).toHaveBeenCalledWith(args);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.createCategory as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'create_category', { name: '카테고리' });
      expect(result.isError).toBe(true);
    });
  });

  describe('update_category', () => {
    it('calls apiClient.updateCategory with id and data', async () => {
      const args = { id: 3, name: '수정된 카테고리', description: '수정된 설명' };
      const result = await invokeTool(server, 'update_category', args);
      expect(client.updateCategory).toHaveBeenCalledWith(3, { name: '수정된 카테고리', description: '수정된 설명' });
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.updateCategory as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'update_category', { id: 3, name: '카테고리' });
      expect(result.isError).toBe(true);
    });
  });
});
