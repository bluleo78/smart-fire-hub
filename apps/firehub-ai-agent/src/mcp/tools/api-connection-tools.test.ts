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

  describe('update_api_connection — 가려진 값(****…) 처리 (WD-179)', () => {
    it('도구 설명이 가려진 값을 지어내 보내지 말고 인증 미변경 시 authConfig 를 생략하라고 안내한다', () => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const description: string = (server.instance as any)._registeredTools['update_api_connection']
        .description;
      expect(description).toContain('****');
      expect(description).toContain('authConfig를 생략');
    });

    it('조회 결과의 가린 값은 클라이언트에서 막지 않고 서버로 그대로 전달한다(서버가 유지/거절을 판정)', async () => {
      const authConfig = { placement: 'query', paramName: 'authKey', apiKey: '****9876' };
      const result = await invokeTool(server, 'update_api_connection', { id: 5, authConfig });
      expect(result.isError).toBeFalsy();
      expect(client.updateApiConnection).toHaveBeenCalledWith(5, { authConfig });
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

  // WD-681: BASIC(아이디·비밀번호) 인증 — 스키마·더미 검사·설명이 API_KEY/BEARER 와 같은 수준으로 다루는지 확인한다.
  describe('BASIC 인증 (WD-681)', () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const tool = (name: string) => (server.instance as any)._registeredTools[name];

    it('create_api_connection 스키마가 authType BASIC 을 받는다', () => {
      const parsed = tool('create_api_connection').inputSchema.safeParse({
        name: 'Basic API',
        authType: 'BASIC',
        authConfig: { username: 'svc-user', password: 'S3cr3t-pw!9' },
        baseUrl: 'https://api.example.com',
      });
      expect(parsed.success).toBe(true);
    });

    it('create_api_connection 스키마는 지원하지 않는 authType(NONE)을 여전히 거절한다', () => {
      const parsed = tool('create_api_connection').inputSchema.safeParse({
        name: 'No auth',
        authType: 'NONE',
        authConfig: {},
        baseUrl: 'https://api.example.com',
      });
      expect(parsed.success).toBe(false);
    });

    it('실제 아이디·비밀번호로 BASIC 연결을 만들면 그대로 서버로 보낸다', async () => {
      const args = {
        name: 'Basic API',
        authType: 'BASIC',
        authConfig: { username: 'svc-user', password: 'S3cr3t-pw!9' },
        baseUrl: 'https://api.example.com',
      };
      const result = await invokeTool(server, 'create_api_connection', args);
      expect(result.isError).toBeFalsy();
      expect(client.createApiConnection).toHaveBeenCalledWith(args);
    });

    it.each([
      ['빈 값', ''],
      ['공백만', '   '],
      ['none', 'none'],
      ['dummy 접두사', 'dummypass123'],
      ['반복 문자', 'xxxxxxxx'],
      ['순차 숫자', '123456789'],
      ['너무 짧음', 'ab'],
    ])('BASIC password 가 %s 이면 token/apiKey 처럼 거절한다', async (_label, password) => {
      const result = await invokeTool(server, 'create_api_connection', {
        name: 'basic-placeholder',
        authType: 'BASIC',
        authConfig: { username: 'svc-user', password },
        baseUrl: 'https://api.example.com',
      });
      expect(result.isError).toBe(true);
      expect(client.createApiConnection).not.toHaveBeenCalled();
    });

    it('BASIC 인데 password 가 없으면 거절한다', async () => {
      const result = await invokeTool(server, 'create_api_connection', {
        name: 'basic-missing-pw',
        authType: 'BASIC',
        authConfig: { username: 'svc-user' },
        baseUrl: 'https://api.example.com',
      });
      expect(result.isError).toBe(true);
      expect(client.createApiConnection).not.toHaveBeenCalled();
    });

    it('BASIC 인데 username 이 없거나 빈 값·placeholder 면 거절한다', async () => {
      for (const authConfig of [
        { password: 'S3cr3t-pw!9' },
        { username: '  ', password: 'S3cr3t-pw!9' },
        { username: 'none', password: 'S3cr3t-pw!9' },
      ]) {
        const result = await invokeTool(server, 'create_api_connection', {
          name: 'basic-missing-user',
          authType: 'BASIC',
          authConfig,
          baseUrl: 'https://api.example.com',
        });
        expect(result.isError, JSON.stringify(authConfig)).toBe(true);
      }
      expect(client.createApiConnection).not.toHaveBeenCalled();
    });

    it('username 은 비밀이 아니므로 접두사·길이 검사를 하지 않는다(testuser·jo 같은 실제 아이디 허용)', async () => {
      for (const username of ['testuser', 'jo']) {
        const result = await invokeTool(server, 'create_api_connection', {
          name: 'basic-real-user',
          authType: 'BASIC',
          authConfig: { username, password: 'S3cr3t-pw!9' },
          baseUrl: 'https://api.example.com',
        });
        expect(result.isError, username).toBeFalsy();
      }
    });

    it('update 에서 조회 결과의 가린 password(****…)는 막지 않고 서버로 그대로 보낸다', async () => {
      const authConfig = { username: 'svc-user', password: '****pw!9' };
      const result = await invokeTool(server, 'update_api_connection', {
        id: 7,
        authType: 'BASIC',
        authConfig,
      });
      expect(result.isError).toBeFalsy();
      expect(client.updateApiConnection).toHaveBeenCalledWith(7, { authType: 'BASIC', authConfig });
    });

    it('update 에서 placeholder password 는 거절한다', async () => {
      const result = await invokeTool(server, 'update_api_connection', {
        id: 7,
        authType: 'BASIC',
        authConfig: { username: 'svc-user', password: 'changeme' },
      });
      expect(result.isError).toBe(true);
      expect(client.updateApiConnection).not.toHaveBeenCalled();
    });

    it('도구 설명이 BASIC(username·password)과 baseUrl 에 사용자 정보를 넣지 말라는 안내를 담는다', () => {
      for (const name of ['create_api_connection', 'update_api_connection']) {
        const text = JSON.stringify({
          d: tool(name).description,
          authType: tool(name).inputSchema.shape.authType.description,
          authConfig: tool(name).inputSchema.shape.authConfig.description,
          baseUrl: tool(name).inputSchema.shape.baseUrl.description,
        });
        expect(text, name).toContain('BASIC');
        expect(text, name).toContain('username');
        expect(text, name).toContain('password');
        expect(text, name).toContain('사용자 정보');
      }
    });
  });
});
