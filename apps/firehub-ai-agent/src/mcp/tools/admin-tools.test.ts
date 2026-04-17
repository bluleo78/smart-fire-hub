import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createFireHubMcpServer } from '../firehub-mcp-server.js';
import { FireHubApiClient } from '../api-client.js';

/** FireHubApiClient 프로토타입 메서드를 vi.fn()으로 대체하여 HTTP 없이 도구 동작 검증 */
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

describe('Admin MCP Tools', () => {
  let client: FireHubApiClient;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let server: any;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    client = createMockClient();
    server = createFireHubMcpServer(client);
  });

  describe('list_users', () => {
    it('calls apiClient.listUsers with args', async () => {
      const mockData = { content: [], totalElements: 0 };
      (client.listUsers as ReturnType<typeof vi.fn>).mockResolvedValue(mockData);

      const result = await invokeTool(server, 'list_users', { search: 'test', page: 0, size: 10 });

      expect(client.listUsers).toHaveBeenCalledWith({ search: 'test', page: 0, size: 10 });
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.listUsers as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류 (403): Forbidden'));

      const result = await invokeTool(server, 'list_users', {});

      expect(result.isError).toBe(true);
    });
  });

  describe('get_user', () => {
    it('calls apiClient.getUser with userId', async () => {
      const mockUser = { id: 1, name: 'test', email: 'test@example.com', roles: [] };
      (client.getUser as ReturnType<typeof vi.fn>).mockResolvedValue(mockUser);

      const result = await invokeTool(server, 'get_user', { userId: 1 });

      expect(client.getUser).toHaveBeenCalledWith(1);
      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.id).toBe(1);
    });

    it('returns isError on failure', async () => {
      (client.getUser as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류 (404): Not found'));

      const result = await invokeTool(server, 'get_user', { userId: 999 });

      expect(result.isError).toBe(true);
    });
  });

  describe('set_user_roles', () => {
    it('calls apiClient.setUserRoles and returns success', async () => {
      (client.setUserRoles as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);

      const result = await invokeTool(server, 'set_user_roles', { userId: 1, roleIds: [2, 3] });

      expect(client.setUserRoles).toHaveBeenCalledWith(1, [2, 3]);
      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.success).toBe(true);
      expect(parsed.userId).toBe(1);
    });

    it('returns isError on failure', async () => {
      (client.setUserRoles as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류 (400): Invalid'));

      const result = await invokeTool(server, 'set_user_roles', { userId: 1, roleIds: [] });

      expect(result.isError).toBe(true);
    });
  });

  describe('set_user_active', () => {
    it('calls apiClient.setUserActive and returns success', async () => {
      (client.setUserActive as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);

      const result = await invokeTool(server, 'set_user_active', { userId: 1, active: false });

      expect(client.setUserActive).toHaveBeenCalledWith(1, false);
      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.success).toBe(true);
      expect(parsed.active).toBe(false);
    });

    it('returns isError on failure', async () => {
      (client.setUserActive as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류 (403): Forbidden'));

      const result = await invokeTool(server, 'set_user_active', { userId: 1, active: true });

      expect(result.isError).toBe(true);
    });
  });

  describe('list_roles', () => {
    it('calls apiClient.listRoles', async () => {
      const mockRoles = [{ id: 1, name: 'ADMIN' }, { id: 2, name: 'USER' }];
      (client.listRoles as ReturnType<typeof vi.fn>).mockResolvedValue(mockRoles);

      const result = await invokeTool(server, 'list_roles', {});

      expect(client.listRoles).toHaveBeenCalled();
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.listRoles as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류 (500): Server error'));

      const result = await invokeTool(server, 'list_roles', {});

      expect(result.isError).toBe(true);
    });
  });

  describe('list_permissions', () => {
    it('calls apiClient.listPermissions with category', async () => {
      const mockPerms = [{ code: 'dataset:read', category: 'dataset' }];
      (client.listPermissions as ReturnType<typeof vi.fn>).mockResolvedValue(mockPerms);

      const result = await invokeTool(server, 'list_permissions', { category: 'dataset' });

      expect(client.listPermissions).toHaveBeenCalledWith({ category: 'dataset' });
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.listPermissions as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류 (403): Forbidden'));

      const result = await invokeTool(server, 'list_permissions', {});

      expect(result.isError).toBe(true);
    });
  });
});
