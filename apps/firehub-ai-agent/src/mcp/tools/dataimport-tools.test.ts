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
    it('calls apiClient.getImportStatus with datasetId and importId', async () => {
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
        new Error('API 오류 (404): Import not found'),
      );

      const result = await invokeTool(server, 'import_status', {
        datasetId: 7,
        importId: 999,
      });
      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain('Import not found');
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
