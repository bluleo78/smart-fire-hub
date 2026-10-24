import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createFireHubMcpServer } from '../firehub-mcp-server.js';
import { FireHubApiClient } from '../api-client.js';

/**
 * FireHubApiClient 모킹 헬퍼. dataset-tools.test.ts 와 동일 패턴.
 * 프로토타입의 모든 메서드를 vi.fn() 으로 대체한다.
 */
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

/** 등록된 MCP 도구를 이름으로 찾아 핸들러를 호출한다. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
async function invokeTool(server: any, toolName: string, args: Record<string, unknown> = {}) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const instance = server.instance as any;
  const entry = instance._registeredTools[toolName];
  if (!entry) throw new Error(`Tool ${toolName} not found in registered tools`);
  return entry.handler(args, {});
}

describe('DataImport MCP Tools', () => {
  let client: FireHubApiClient;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let server: any;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    client = createMockClient();
    server = createFireHubMcpServer(client);
  });

  describe('preview_csv', () => {
    it('calls apiClient.previewImport with datasetId, fileId and parse options', async () => {
      const mockResp = {
        fileHeaders: ['a', 'b'],
        sampleRows: [{ a: '1', b: '2' }],
        suggestedMappings: [],
        totalRows: 1,
      };
      (client.previewImport as ReturnType<typeof vi.fn>).mockResolvedValue(mockResp);

      const result = await invokeTool(server, 'preview_csv', {
        datasetId: 7,
        fileId: 11,
        delimiter: ',',
        hasHeader: true,
      });

      expect(client.previewImport).toHaveBeenCalledWith(7, 11, {
        delimiter: ',',
        hasHeader: true,
      });
      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.fileHeaders).toEqual(['a', 'b']);
    });

    it('returns isError on API failure', async () => {
      (client.previewImport as ReturnType<typeof vi.fn>).mockRejectedValue(
        new Error('API 오류 (404): File not found'),
      );

      const result = await invokeTool(server, 'preview_csv', { datasetId: 7, fileId: 999 });
      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain('File not found');
    });
  });

  describe('validate_import', () => {
    it('calls apiClient.validateImport with mappings extracted from args', async () => {
      const mockResp = { totalRows: 3, validRows: 2, errorRows: 1, errors: [] };
      (client.validateImport as ReturnType<typeof vi.fn>).mockResolvedValue(mockResp);

      const mappings = [{ fileColumn: 'a', datasetColumn: 'col_a' }];
      const result = await invokeTool(server, 'validate_import', {
        datasetId: 7,
        fileId: 12,
        mappings,
        encoding: 'UTF-8',
      });

      expect(client.validateImport).toHaveBeenCalledWith(7, 12, mappings, { encoding: 'UTF-8' });
      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.validRows).toBe(2);
    });

    it('returns isError on API failure', async () => {
      (client.validateImport as ReturnType<typeof vi.fn>).mockRejectedValue(
        new Error('API 오류 (400): Invalid mappings'),
      );

      const result = await invokeTool(server, 'validate_import', {
        datasetId: 7,
        fileId: 12,
        mappings: [],
      });
      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain('Invalid mappings');
    });
  });

  describe('start_import', () => {
    it('calls apiClient.startImport with mappings, importMode and parse options', async () => {
      const mockResp = { jobId: 'job-abc', status: 'QUEUED' };
      (client.startImport as ReturnType<typeof vi.fn>).mockResolvedValue(mockResp);

      const mappings = [{ fileColumn: 'a', datasetColumn: 'col_a' }];
      const result = await invokeTool(server, 'start_import', {
        datasetId: 7,
        fileId: 13,
        mappings,
        importMode: 'REPLACE',
        delimiter: ';',
      });

      expect(client.startImport).toHaveBeenCalledWith(7, 13, {
        mappings,
        importMode: 'REPLACE',
        parseOptions: { delimiter: ';' },
      });
      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.jobId).toBe('job-abc');
    });

    it('returns isError on API failure', async () => {
      (client.startImport as ReturnType<typeof vi.fn>).mockRejectedValue(
        new Error('API 오류 (500): Import failed'),
      );

      const result = await invokeTool(server, 'start_import', {
        datasetId: 7,
        fileId: 13,
      });
      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain('Import failed');
    });
  });

  describe('import_status', () => {
    it('하위 호환: datasetId+importId 면 기존 이력 조회(getImportStatus)를 부른다', async () => {
      const mockResp = {
        id: 99,
        datasetId: 7,
        status: 'SUCCESS',
        totalRows: 10,
        successRows: 10,
        errorRows: 0,
      };
      (client.getImportStatus as ReturnType<typeof vi.fn>).mockResolvedValue(mockResp);

      const result = await invokeTool(server, 'import_status', {
        datasetId: 7,
        importId: 99,
      });

      expect(client.getImportStatus).toHaveBeenCalledWith(7, 99);
      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.status).toBe('SUCCESS');
    });

    it('returns isError on API failure', async () => {
      (client.getImportStatus as ReturnType<typeof vi.fn>).mockRejectedValue(
        new Error('API 오류 (404): 가져오기 이력을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.'),
      );

      const result = await invokeTool(server, 'import_status', {
        datasetId: 7,
        importId: 999,
      });
      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain('가져오기 이력을 찾을 수 없습니다');
    });
  });

  // WD-254: start_import 는 UUID jobId 를 돌려주므로 import_status 는 jobId 로 작업 상태를 조회해야 한다.
  describe('import_status (jobId 경로, WD-254)', () => {
    const baseJob = {
      jobId: 'be5b2a44-ad66-4bfc-b8cd-ff06cf8c67e7',
      jobType: 'IMPORT',
      message: 'Inserting rows...',
      errorMessage: null,
      createdAt: '2026-10-25T07:12:38',
      updatedAt: '2026-10-25T07:12:39',
      userId: 3,
    };

    it('jobId 로 작업 상태 API 를 부르고 진행 중 요약(done=false, 한국어 단계)을 돌려준다', async () => {
      (client.getImportJobStatus as ReturnType<typeof vi.fn>).mockResolvedValue({
        ...baseJob,
        stage: 'INSERTING',
        progress: 70,
        metadata: { totalRows: 1000, processedRows: 500 },
      });

      const result = await invokeTool(server, 'import_status', { jobId: baseJob.jobId });

      expect(client.getImportJobStatus).toHaveBeenCalledWith(baseJob.jobId);
      expect(client.getImportStatus).not.toHaveBeenCalled();
      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed).toMatchObject({
        jobId: baseJob.jobId,
        stage: 'INSERTING',
        stageLabel: '적재 중',
        progress: 70,
        done: false,
        processedRows: 500,
        successRows: null,
      });
      expect(parsed.summary).toContain('진행 중');
      expect(parsed.summary).toContain('500/1,000행');
      // 백엔드의 영어 message 는 요약에 싣지 않는다
      expect(result.content[0].text).not.toContain('Inserting rows');
    });

    it('완료되면 done=true 와 적재 행 수 요약을 돌려준다', async () => {
      (client.getImportJobStatus as ReturnType<typeof vi.fn>).mockResolvedValue({
        ...baseJob,
        stage: 'COMPLETED',
        progress: 100,
        message: 'Completed',
        metadata: { totalRows: 1234, successRows: 1234, errorRows: 0 },
      });

      const result = await invokeTool(server, 'import_status', { jobId: baseJob.jobId });

      const parsed = JSON.parse(result.content[0].text);
      expect(parsed).toMatchObject({
        stage: 'COMPLETED',
        done: true,
        totalRows: 1234,
        successRows: 1234,
        errorRows: 0,
        errorMessage: null,
      });
      expect(parsed.summary).toBe(
        '임포트가 완료되었습니다. 1,234행을 적재했습니다 (전체 1,234행).',
      );
    });

    it('실패하면 done=true 와 백엔드의 한국어 실패 사유를 돌려준다', async () => {
      (client.getImportJobStatus as ReturnType<typeof vi.fn>).mockResolvedValue({
        ...baseJob,
        stage: 'FAILED',
        progress: 40,
        metadata: {},
        errorMessage: '3행: amount 값이 숫자가 아닙니다.',
      });

      const result = await invokeTool(server, 'import_status', { jobId: baseJob.jobId });

      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.done).toBe(true);
      expect(parsed.stageLabel).toBe('실패');
      expect(parsed.errorMessage).toBe('3행: amount 값이 숫자가 아닙니다.');
      expect(parsed.summary).toBe('임포트가 실패했습니다. 사유: 3행: amount 값이 숫자가 아닙니다.');
    });

    it('jobId 와 importId 가 함께 오면 jobId 를 우선한다', async () => {
      (client.getImportJobStatus as ReturnType<typeof vi.fn>).mockResolvedValue({
        ...baseJob,
        stage: 'PENDING',
        progress: 0,
        metadata: {},
      });

      await invokeTool(server, 'import_status', {
        jobId: baseJob.jobId,
        datasetId: 7,
        importId: 99,
      });

      expect(client.getImportJobStatus).toHaveBeenCalledWith(baseJob.jobId);
      expect(client.getImportStatus).not.toHaveBeenCalled();
    });

    it('임포트가 아닌 작업(jobType)이면 한국어 오류를 돌려준다', async () => {
      (client.getImportJobStatus as ReturnType<typeof vi.fn>).mockResolvedValue({
        ...baseJob,
        jobType: 'EXPORT',
        stage: 'COMPLETED',
        progress: 100,
        metadata: {},
      });

      const result = await invokeTool(server, 'import_status', { jobId: baseJob.jobId });
      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain('임포트 작업이 아닙니다');
    });

    it('남의 작업이면 백엔드 403 한국어 메시지를 그대로 전한다', async () => {
      (client.getImportJobStatus as ReturnType<typeof vi.fn>).mockRejectedValue(
        new Error('API 오류 (403): 이 작업을 시작한 사용자만 진행 상황을 볼 수 있습니다.'),
      );

      const result = await invokeTool(server, 'import_status', { jobId: baseJob.jobId });
      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain('이 작업을 시작한 사용자만');
    });

    it('jobId 도 importId 도 없으면 한국어 안내 오류를 돌려준다', async () => {
      const result = await invokeTool(server, 'import_status', { datasetId: 7 });
      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain('jobId 가 필요합니다');
      expect(client.getImportJobStatus).not.toHaveBeenCalled();
      expect(client.getImportStatus).not.toHaveBeenCalled();
    });

    it('importId 만 있고 datasetId 가 없으면 한국어 안내 오류를 돌려준다', async () => {
      const result = await invokeTool(server, 'import_status', { importId: 99 });
      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain('datasetId 도 함께');
      expect(client.getImportStatus).not.toHaveBeenCalled();
    });
  });

  // --- tool registration ---
  it('dataimport tools are registered in the MCP server', () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const registeredTools = Object.keys((server.instance as any)._registeredTools);
    expect(registeredTools).toContain('preview_csv');
    expect(registeredTools).toContain('validate_import');
    expect(registeredTools).toContain('start_import');
    expect(registeredTools).toContain('import_status');
  });
});
