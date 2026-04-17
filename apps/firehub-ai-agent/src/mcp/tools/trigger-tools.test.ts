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

describe('Trigger MCP Tools', () => {
  let client: FireHubApiClient;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let server: any;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    client = createMockClient();
    server = createFireHubMcpServer(client);
  });

  describe('list_triggers', () => {
    it('calls apiClient.listTriggers with pipelineId', async () => {
      const result = await invokeTool(server, 'list_triggers', { pipelineId: 1 });
      expect(client.listTriggers).toHaveBeenCalledWith(1);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.listTriggers as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'list_triggers', { pipelineId: 1 });
      expect(result.isError).toBe(true);
    });
  });

  describe('create_trigger', () => {
    it('calls apiClient.createTrigger with pipelineId and data', async () => {
      const args = {
        pipelineId: 2,
        name: '매일 실행',
        triggerType: 'SCHEDULE',
        config: { cronExpression: '0 9 * * *' },
      };
      const result = await invokeTool(server, 'create_trigger', args);
      expect(client.createTrigger).toHaveBeenCalledWith(2, {
        name: '매일 실행',
        triggerType: 'SCHEDULE',
        config: { cronExpression: '0 9 * * *' },
      });
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.createTrigger as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'create_trigger', {
        pipelineId: 2,
        name: '트리거',
        triggerType: 'API',
        config: {},
      });
      expect(result.isError).toBe(true);
    });
  });

  describe('update_trigger', () => {
    it('calls apiClient.updateTrigger with pipelineId, triggerId, and data', async () => {
      const args = { pipelineId: 3, triggerId: 10, isEnabled: false };
      const result = await invokeTool(server, 'update_trigger', args);
      expect(client.updateTrigger).toHaveBeenCalledWith(3, 10, { isEnabled: false });
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.updateTrigger as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'update_trigger', { pipelineId: 3, triggerId: 10 });
      expect(result.isError).toBe(true);
    });
  });

  describe('delete_trigger', () => {
    it('calls apiClient.deleteTrigger with pipelineId and triggerId', async () => {
      const result = await invokeTool(server, 'delete_trigger', { pipelineId: 4, triggerId: 20 });
      expect(client.deleteTrigger).toHaveBeenCalledWith(4, 20);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.deleteTrigger as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'delete_trigger', { pipelineId: 4, triggerId: 20 });
      expect(result.isError).toBe(true);
    });
  });
});
