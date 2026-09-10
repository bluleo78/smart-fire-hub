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

  describe('create_api_connection — 더미/placeholder 자격증명 차단 (#255)', () => {
    it('rejects empty token (BEARER)', async () => {
      const result = await invokeTool(server, 'create_api_connection', {
        name: 'noauth-empty',
        authType: 'BEARER',
        authConfig: { token: '' },
        baseUrl: 'https://example.com',
      });
      expect(result.isError).toBe(true);
      expect(client.createApiConnection).not.toHaveBeenCalled();
    });

    it('rejects token="none" (BEARER)', async () => {
      const result = await invokeTool(server, 'create_api_connection', {
        name: 'noauth-none',
        authType: 'BEARER',
        authConfig: { token: 'none' },
        baseUrl: 'https://example.com',
      });
      expect(result.isError).toBe(true);
      expect(client.createApiConnection).not.toHaveBeenCalled();
    });

    it('rejects apiKey="dummy" (API_KEY)', async () => {
      const result = await invokeTool(server, 'create_api_connection', {
        name: 'noauth-dummy',
        authType: 'API_KEY',
        authConfig: { apiKey: 'dummy', headerName: 'X-Api-Key' },
        baseUrl: 'https://example.com',
      });
      expect(result.isError).toBe(true);
      expect(client.createApiConnection).not.toHaveBeenCalled();
    });

    it('rejects headerName="X-No-Auth" placeholder', async () => {
      const result = await invokeTool(server, 'create_api_connection', {
        name: 'noauth-xna',
        authType: 'API_KEY',
        authConfig: { apiKey: 'realkey-abc123', headerName: 'X-No-Auth' },
        baseUrl: 'https://example.com',
      });
      expect(result.isError).toBe(true);
      expect(client.createApiConnection).not.toHaveBeenCalled();
    });

    it('rejects token with only whitespace', async () => {
      const result = await invokeTool(server, 'create_api_connection', {
        name: 'noauth-ws',
        authType: 'BEARER',
        authConfig: { token: '   ' },
        baseUrl: 'https://example.com',
      });
      expect(result.isError).toBe(true);
      expect(client.createApiConnection).not.toHaveBeenCalled();
    });

    it('rejects too-short token (< 3 chars)', async () => {
      const result = await invokeTool(server, 'create_api_connection', {
        name: 'noauth-short',
        authType: 'BEARER',
        authConfig: { token: 'ab' },
        baseUrl: 'https://example.com',
      });
      expect(result.isError).toBe(true);
      expect(client.createApiConnection).not.toHaveBeenCalled();
    });

    it('rejects API_KEY missing headerName', async () => {
      const result = await invokeTool(server, 'create_api_connection', {
        name: 'noauth-missing',
        authType: 'API_KEY',
        authConfig: { apiKey: 'real-key-1234' },
        baseUrl: 'https://example.com',
      });
      expect(result.isError).toBe(true);
      expect(client.createApiConnection).not.toHaveBeenCalled();
    });

    it('accepts real-looking BEARER token', async () => {
      const result = await invokeTool(server, 'create_api_connection', {
        name: 'real-bearer',
        authType: 'BEARER',
        authConfig: { token: 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig' },
        baseUrl: 'https://example.com',
      });
      expect(result.isError).toBeFalsy();
      expect(client.createApiConnection).toHaveBeenCalled();
    });

    // (#619) 정확일치 Set만으로는 통과하던 "더미 단어 접두사" 변형 회귀 가드
    it('rejects apiKey="dummyvalue12345" (dummy-prefixed variant)', async () => {
      const result = await invokeTool(server, 'create_api_connection', {
        name: 'noauth-dummy-variant',
        authType: 'API_KEY',
        authConfig: { apiKey: 'dummyvalue12345', headerName: 'X-Test-Key' },
        baseUrl: 'https://example.com',
      });
      expect(result.isError).toBe(true);
      expect(client.createApiConnection).not.toHaveBeenCalled();
    });

    it('rejects apiKey="testkey123" (test-prefixed variant)', async () => {
      const result = await invokeTool(server, 'create_api_connection', {
        name: 'noauth-test-variant',
        authType: 'API_KEY',
        authConfig: { apiKey: 'testkey123', headerName: 'X-Api-Key' },
        baseUrl: 'https://example.com',
      });
      expect(result.isError).toBe(true);
      expect(client.createApiConnection).not.toHaveBeenCalled();
    });

    it('rejects token="placeholdervalue999" (placeholder-prefixed variant)', async () => {
      const result = await invokeTool(server, 'create_api_connection', {
        name: 'noauth-placeholder-variant',
        authType: 'BEARER',
        authConfig: { token: 'placeholdervalue999' },
        baseUrl: 'https://example.com',
      });
      expect(result.isError).toBe(true);
      expect(client.createApiConnection).not.toHaveBeenCalled();
    });

    it('rejects repeated-character token ("xxxxxxxxxxxx")', async () => {
      const result = await invokeTool(server, 'create_api_connection', {
        name: 'noauth-repeated',
        authType: 'BEARER',
        authConfig: { token: 'xxxxxxxxxxxx' },
        baseUrl: 'https://example.com',
      });
      expect(result.isError).toBe(true);
      expect(client.createApiConnection).not.toHaveBeenCalled();
    });

    it('rejects sequential-digit token ("123456789")', async () => {
      const result = await invokeTool(server, 'create_api_connection', {
        name: 'noauth-sequential',
        authType: 'BEARER',
        authConfig: { token: '123456789' },
        baseUrl: 'https://example.com',
      });
      expect(result.isError).toBe(true);
      expect(client.createApiConnection).not.toHaveBeenCalled();
    });

    it('accepts key that merely contains a dummy word mid-string (not prefix)', async () => {
      // "attestation-key-abc123f9" 처럼 접두사가 아닌 중간에 단어가 섞인 경우는
      // 오탐 방지를 위해 통과시킨다 (접두사 매칭만 사용).
      const result = await invokeTool(server, 'create_api_connection', {
        name: 'real-key-with-substring',
        authType: 'BEARER',
        authConfig: { token: 'attestation-key-abc123f9' },
        baseUrl: 'https://example.com',
      });
      expect(result.isError).toBeFalsy();
      expect(client.createApiConnection).toHaveBeenCalled();
    });
  });

  describe('update_api_connection — 더미 자격증명 차단 (#255)', () => {
    it('rejects update with empty token', async () => {
      const result = await invokeTool(server, 'update_api_connection', {
        id: 1,
        authType: 'BEARER',
        authConfig: { token: '' },
      });
      expect(result.isError).toBe(true);
      expect(client.updateApiConnection).not.toHaveBeenCalled();
    });

    it('rejects update with placeholder apiKey', async () => {
      const result = await invokeTool(server, 'update_api_connection', {
        id: 1,
        authConfig: { apiKey: 'placeholder', headerName: 'X-Api-Key' },
      });
      expect(result.isError).toBe(true);
      expect(client.updateApiConnection).not.toHaveBeenCalled();
    });

    it('allows update without authConfig (name only)', async () => {
      const result = await invokeTool(server, 'update_api_connection', {
        id: 1,
        name: 'renamed',
      });
      expect(result.isError).toBeFalsy();
      expect(client.updateApiConnection).toHaveBeenCalled();
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

  // #605: 삭제 전 참조 파이프라인 확인 도구 — dataset-manager의 get_dataset_references와 동일 패턴
  describe('get_api_connection_references', () => {
    it('calls apiClient.getApiConnectionReferences with id', async () => {
      (client.getApiConnectionReferences as ReturnType<typeof vi.fn>).mockResolvedValue({
        apiConnectionId: 15,
        pipelines: [{ id: 56, name: 'inspector-fk-test' }],
        totalCount: 1,
      });

      const result = await invokeTool(server, 'get_api_connection_references', { id: 15 });

      expect(client.getApiConnectionReferences).toHaveBeenCalledWith(15);
      expect(result.isError).toBeFalsy();
      expect(result.content[0].text).toContain('inspector-fk-test');
    });

    it('returns isError on failure', async () => {
      (client.getApiConnectionReferences as ReturnType<typeof vi.fn>).mockRejectedValue(
        new Error('API 오류'),
      );
      const result = await invokeTool(server, 'get_api_connection_references', { id: 15 });
      expect(result.isError).toBe(true);
    });
  });
});
