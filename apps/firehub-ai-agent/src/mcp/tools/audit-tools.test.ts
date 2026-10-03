import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createFireHubMcpServer } from '../firehub-mcp-server.js';
import { FireHubApiClient } from '../api-client.js';
import { withUserZoneOffset } from './audit-tools.js';

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

describe('Audit MCP Tools', () => {
  let client: FireHubApiClient;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let server: any;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    client = createMockClient();
    server = createFireHubMcpServer(client);
  });

  describe('list_audit_logs', () => {
    it('calls apiClient.listAuditLogs with args', async () => {
      const args = { search: 'admin', actionType: 'LOGIN', page: 0, size: 20 };
      const result = await invokeTool(server, 'list_audit_logs', args);
      expect(client.listAuditLogs).toHaveBeenCalledWith(args);
      expect(result.isError).toBeFalsy();
    });

    it('calls apiClient.listAuditLogs with startDate/endDate args (#629)', async () => {
      const args = { startDate: '2026-09-01T00:00:00', endDate: '2026-09-05T23:59:59' };
      const result = await invokeTool(server, 'list_audit_logs', args);
      // WD-11: 오프셋 없는 시각은 사용자 시간대(KST) 벽시계로 보고 +09:00 을 붙여 보낸다.
      expect(client.listAuditLogs).toHaveBeenCalledWith({
        startDate: '2026-09-01T00:00:00+09:00',
        endDate: '2026-09-05T23:59:59+09:00',
      });
      expect(result.isError).toBeFalsy();
    });

    // WD-11: 운영 저장 TZ 는 UTC 라, 오프셋 없는 값을 그대로 보내면 KST 로 묻는 사용자에게 결과가 9시간 밀린다.
    it('붙은 오프셋(Z, +09:00, -05:00)은 그 순간을 그대로 보낸다 (WD-11)', async () => {
      const args = { startDate: '2026-09-01T00:00:00Z', endDate: '2026-09-05T23:59:59-05:00' };
      await invokeTool(server, 'list_audit_logs', args);
      expect(client.listAuditLogs).toHaveBeenCalledWith(args);
      await invokeTool(server, 'list_audit_logs', { startDate: '2026-09-01T00:00:00+09:00' });
      expect(client.listAuditLogs).toHaveBeenLastCalledWith({ startDate: '2026-09-01T00:00:00+09:00' });
    });

    it('날짜만 오면 KST 하루 전체(00:00:00 ~ 23:59:59)로 바꿔 오프셋을 붙인다 (WD-11)', async () => {
      await invokeTool(server, 'list_audit_logs', { startDate: '2026-09-01', endDate: '2026-09-05', size: 100 });
      expect(client.listAuditLogs).toHaveBeenCalledWith({
        startDate: '2026-09-01T00:00:00+09:00',
        endDate: '2026-09-05T23:59:59+09:00',
        size: 100,
      });
    });

    it('분까지만·소수 초가 붙은 오프셋 없는 값에도 +09:00 을 붙인다 (WD-11)', async () => {
      await invokeTool(server, 'list_audit_logs', { startDate: '2026-09-01T09:30', endDate: '2026-09-01T18:00:00.500' });
      expect(client.listAuditLogs).toHaveBeenCalledWith({
        startDate: '2026-09-01T09:30+09:00',
        endDate: '2026-09-01T18:00:00.500+09:00',
      });
    });

    it('withUserZoneOffset: KST 고정 오프셋을 붙이고, 알 수 없는 형식은 그대로 둔다 (WD-11)', () => {
      expect(withUserZoneOffset('2026-07-01T12:00:00', 'start')).toBe('2026-07-01T12:00:00+09:00');
      expect(withUserZoneOffset('2026-01-15', 'end')).toBe('2026-01-15T23:59:59+09:00');
      expect(withUserZoneOffset('어제', 'start')).toBe('어제');
      expect(withUserZoneOffset(undefined, 'start')).toBeUndefined();
    });

    it('스키마 설명이 오프셋 포함을 요구한다 (WD-11)', () => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const shape = (server.instance as any)._registeredTools['list_audit_logs'].inputSchema.shape;
      expect(shape.startDate.description).toContain('+09:00');
      expect(shape.endDate.description).toContain('+09:00');
    });

    it('calls apiClient.listAuditLogs with userId arg (#657)', async () => {
      const args = { userId: 16, size: 20 };
      const result = await invokeTool(server, 'list_audit_logs', args);
      expect(client.listAuditLogs).toHaveBeenCalledWith(args);
      expect(result.isError).toBeFalsy();
    });

    it('calls apiClient.listAuditLogs with empty args', async () => {
      const result = await invokeTool(server, 'list_audit_logs', {});
      expect(client.listAuditLogs).toHaveBeenCalledWith({});
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.listAuditLogs as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류 (403): Forbidden'));
      const result = await invokeTool(server, 'list_audit_logs', {});
      expect(result.isError).toBe(true);
    });
  });
});
