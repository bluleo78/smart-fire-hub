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

/** 등록된 도구의 zod 입력 스키마(전체 객체)를 꺼낸다 — 핸들러 직접 호출은 스키마 검증을 거치지 않으므로 따로 본다. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function toolEntry(server: any, toolName: string) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const entry = (server.instance as any)._registeredTools[toolName];
  if (!entry) throw new Error(`Tool ${toolName} not found`);
  return entry as {
    description: string;
    inputSchema: { safeParse: (v: unknown) => { success: boolean } };
  };
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
        config: { cron: '0 9 * * *' },
      };
      const result = await invokeTool(server, 'create_trigger', args);
      expect(client.createTrigger).toHaveBeenCalledWith(2, {
        name: '매일 실행',
        triggerType: 'SCHEDULE',
        config: { cron: '0 9 * * *' },
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

  describe('create_trigger 설명·스키마 (WD-227)', () => {
    it('DATASET_CHANGE config 를 datasetIds 배열과 폴링·디바운스 정수 범위로 안내한다', () => {
      const { inputSchema } = toolEntry(server, 'create_trigger');
      // 설명은 config 필드 describe 에 있다 — JSON 스키마로 바꿔 문자열로 본다
      const text = JSON.stringify(
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (inputSchema as any).shape.config.description,
      );
      expect(text).toContain('datasetIds');
      expect(text).not.toMatch(/DATASET_CHANGE: \{datasetId\}/);
      expect(text).toContain('pollingIntervalSeconds?(정수 30~3600, 기본 60)');
      expect(text).toContain('debounceSeconds?(정수 0~3600');
      expect(text).toContain('upstreamPipelineId');
      expect(text).toContain('allowedIps');
      expect(text).toContain('concurrencyPolicy');
    });

    it('숫자 필드는 정수만 받고 문자열 숫자·범위 밖 값은 거부한다', () => {
      const { inputSchema } = toolEntry(server, 'create_trigger');
      const base = { pipelineId: 1, name: '변경 감시', triggerType: 'DATASET_CHANGE' };
      const ok = (config: unknown) => inputSchema.safeParse({ ...base, config }).success;
      expect(ok({ datasetIds: [7], pollingIntervalSeconds: 60, debounceSeconds: 0 })).toBe(true);
      expect(ok({ datasetIds: [7], pollingIntervalSeconds: '60' })).toBe(false);
      expect(ok({ datasetIds: ['7'] })).toBe(false);
      expect(ok({ datasetIds: [] })).toBe(false);
      expect(ok({ datasetIds: [7], pollingIntervalSeconds: 10 })).toBe(false);
      expect(ok({ datasetIds: [7], pollingIntervalSeconds: 4000 })).toBe(false);
      expect(ok({ datasetIds: [7], pollingIntervalSeconds: 60.5 })).toBe(false);
      expect(ok({ datasetIds: [7], debounceSeconds: -1 })).toBe(false);
      expect(ok({ upstreamPipelineId: '3' })).toBe(false);
      expect(ok({ condition: 'MAYBE' })).toBe(false);
      // 모르는 키(조회 응답의 서버 소유 키 등)는 통과시킨다
      expect(ok({ datasetIds: [7], tokenHash: 'x' })).toBe(true);
    });
  });

  describe('update_trigger', () => {
    it('name 이 있고 config 가 없으면 조회 없이 그대로 보낸다', async () => {
      const args = { pipelineId: 3, triggerId: 10, name: '새 이름', isEnabled: false };
      const result = await invokeTool(server, 'update_trigger', args);
      expect(client.getTrigger).not.toHaveBeenCalled();
      expect(client.updateTrigger).toHaveBeenCalledWith(3, 10, {
        name: '새 이름',
        isEnabled: false,
      });
      expect(result.isError).toBeFalsy();
    });

    it('name 이 없으면 기존 트리거를 조회해 이름을 채운다', async () => {
      (client.getTrigger as ReturnType<typeof vi.fn>).mockResolvedValue({
        id: 10,
        name: '기존 이름',
        config: { cron: '0 0 3 * * *' },
      });
      const result = await invokeTool(server, 'update_trigger', {
        pipelineId: 3,
        triggerId: 10,
        isEnabled: false,
      });
      expect(client.getTrigger).toHaveBeenCalledWith(3, 10);
      // config 를 받지 않았으므로 config 는 보내지 않는다(서버가 저장값 유지)
      expect(client.updateTrigger).toHaveBeenCalledWith(3, 10, {
        name: '기존 이름',
        isEnabled: false,
      });
      expect(result.isError).toBeFalsy();
    });

    it('config 일부만 받으면 기존 config 위에 합쳐 감시 목록을 지우지 않는다', async () => {
      (client.getTrigger as ReturnType<typeof vi.fn>).mockResolvedValue({
        id: 11,
        name: '데이터 변경 감시',
        config: { datasetIds: [7, 8], pollingIntervalSeconds: 60, debounceSeconds: 5 },
      });
      const result = await invokeTool(server, 'update_trigger', {
        pipelineId: 3,
        triggerId: 11,
        config: { pollingIntervalSeconds: 120 },
      });
      expect(client.updateTrigger).toHaveBeenCalledWith(3, 11, {
        name: '데이터 변경 감시',
        config: { datasetIds: [7, 8], pollingIntervalSeconds: 120, debounceSeconds: 5 },
      });
      expect(result.isError).toBeFalsy();
    });

    it('API 트리거 allowedIps 수정은 저장된 tokenHash 를 함께 되돌려 보낸다', async () => {
      (client.getTrigger as ReturnType<typeof vi.fn>).mockResolvedValue({
        id: 12,
        name: '외부 호출',
        config: { tokenHash: 'h', allowedIps: [] },
      });
      await invokeTool(server, 'update_trigger', {
        pipelineId: 3,
        triggerId: 12,
        name: '외부 호출',
        config: { allowedIps: ['10.0.0.0/8'] },
      });
      expect(client.updateTrigger).toHaveBeenCalledWith(3, 12, {
        name: '외부 호출',
        config: { tokenHash: 'h', allowedIps: ['10.0.0.0/8'] },
      });
    });

    it('배열 값은 합치지 않고 보낸 배열로 바꾼다', async () => {
      (client.getTrigger as ReturnType<typeof vi.fn>).mockResolvedValue({
        name: '감시',
        config: { datasetIds: [7, 8], pollingIntervalSeconds: 60 },
      });
      await invokeTool(server, 'update_trigger', {
        pipelineId: 3,
        triggerId: 13,
        config: { datasetIds: [9] },
      });
      expect(client.updateTrigger).toHaveBeenCalledWith(3, 13, {
        name: '감시',
        config: { datasetIds: [9], pollingIntervalSeconds: 60 },
      });
    });

    it('description 빈 문자열(설명 지움)은 그대로 넘긴다', async () => {
      await invokeTool(server, 'update_trigger', {
        pipelineId: 3,
        triggerId: 10,
        name: '이름',
        description: '',
      });
      expect(client.updateTrigger).toHaveBeenCalledWith(3, 10, { name: '이름', description: '' });
    });

    it('기존 이름을 알 수 없으면 수정 요청을 보내지 않고 오류를 돌려준다', async () => {
      (client.getTrigger as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 10 });
      const result = await invokeTool(server, 'update_trigger', {
        pipelineId: 3,
        triggerId: 10,
        isEnabled: true,
      });
      expect(client.updateTrigger).not.toHaveBeenCalled();
      expect(result.isError).toBe(true);
    });

    it('기존 트리거 조회가 실패하면 isError 를 돌려준다', async () => {
      (client.getTrigger as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('404'));
      const result = await invokeTool(server, 'update_trigger', {
        pipelineId: 3,
        triggerId: 10,
        config: { pollingIntervalSeconds: 120 },
      });
      expect(client.updateTrigger).not.toHaveBeenCalled();
      expect(result.isError).toBe(true);
    });

    it('returns isError on failure', async () => {
      (client.updateTrigger as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'update_trigger', {
        pipelineId: 3,
        triggerId: 10,
        name: '이름',
      });
      expect(client.updateTrigger).toHaveBeenCalled();
      expect(result.isError).toBe(true);
    });

    it('스키마: name 은 선택, 숫자 config 는 정수만 받는다', () => {
      const { inputSchema } = toolEntry(server, 'update_trigger');
      expect(inputSchema.safeParse({ pipelineId: 3, triggerId: 10 }).success).toBe(true);
      expect(
        inputSchema.safeParse({
          pipelineId: 3,
          triggerId: 10,
          config: { pollingIntervalSeconds: 120 },
        }).success,
      ).toBe(true);
      expect(
        inputSchema.safeParse({
          pipelineId: 3,
          triggerId: 10,
          config: { pollingIntervalSeconds: '120' },
        }).success,
      ).toBe(false);
      expect(inputSchema.safeParse({ pipelineId: 3, triggerId: 10, name: '' }).success).toBe(false);
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
