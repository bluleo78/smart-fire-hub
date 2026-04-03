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
  if (!entry) throw new Error(`Tool ${toolName} not found in registered tools`);
  return entry.handler(args, {});
}

describe('Proactive MCP Tools', () => {
  let client: FireHubApiClient;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let server: any;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    client = createMockClient();
    server = createFireHubMcpServer(client);
  });

  // --- generate_report ---
  describe('generate_report', () => {
    const baseArgs = {
      question: '월별 화재 발생 추이는?',
      templateStructure: {
        sections: [
          { key: 'summary', label: '요약', type: 'text', required: true },
          { key: 'trend', label: '추이 분석', type: 'text' },
        ],
        output_format: 'markdown',
      },
      sectionContents: {
        summary: '2026년 1분기 화재 건수는 전년 대비 12% 감소했습니다.',
        trend: '1월 45건, 2월 38건, 3월 32건으로 감소 추세입니다.',
      },
    };

    it('returns displayed: true with question and template structure', async () => {
      const result = await invokeTool(server, 'generate_report', baseArgs);

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.displayed).toBe(true);
      expect(parsed.question).toBe('월별 화재 발생 추이는?');
      expect(parsed.templateStructure).toEqual(baseArgs.templateStructure);
      expect(parsed.sectionContents).toEqual(baseArgs.sectionContents);
    });

    it('includes datasetIds when provided', async () => {
      const result = await invokeTool(server, 'generate_report', {
        ...baseArgs,
        datasetIds: [1, 2, 3],
      });

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.datasetIds).toEqual([1, 2, 3]);
    });

    it('defaults datasetIds to empty array when not provided', async () => {
      const result = await invokeTool(server, 'generate_report', baseArgs);

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.datasetIds).toEqual([]);
    });

    it('includes optional style when provided', async () => {
      const result = await invokeTool(server, 'generate_report', {
        ...baseArgs,
        style: '간결하고 전문적인 톤',
      });

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.style).toBe('간결하고 전문적인 톤');
    });

    it('does not call apiClient (pure passthrough)', async () => {
      await invokeTool(server, 'generate_report', baseArgs);

      // generate_report is a passthrough tool — no API calls
      const methodNames = Object.getOwnPropertyNames(FireHubApiClient.prototype).filter(
        (name) => name !== 'constructor',
      );
      for (const name of methodNames) {
        expect((client as unknown as Record<string, unknown>)[name]).not.toHaveBeenCalled();
      }
    });
  });

  // --- save_as_smart_job ---
  describe('save_as_smart_job', () => {
    const baseArgs = {
      name: '주간 화재 분석',
      templateStructure: {
        sections: [
          { key: 'summary', label: '요약', type: 'text', required: true },
          { key: 'detail', label: '상세 분석', type: 'text' },
        ],
        output_format: 'markdown',
      },
      prompt: '최근 7일간 화재 발생 현황을 분석해주세요',
    };

    it('calls apiClient.createSmartJobWithTemplate with correct args', async () => {
      const mockResult = { jobId: 10, templateId: 5 };
      (client.createSmartJobWithTemplate as ReturnType<typeof vi.fn>).mockResolvedValue(mockResult);

      const result = await invokeTool(server, 'save_as_smart_job', baseArgs);

      expect(client.createSmartJobWithTemplate).toHaveBeenCalledWith({
        name: '주간 화재 분석',
        prompt: '최근 7일간 화재 발생 현황을 분석해주세요',
        cronExpression: undefined,
        channels: undefined,
        templateName: '주간 화재 분석 양식',
        templateStructure: {
          sections: baseArgs.templateStructure.sections,
          output_format: 'markdown',
        },
        templateStyle: undefined,
      });
      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.jobId).toBe(10);
      expect(parsed.templateId).toBe(5);
    });

    it('passes optional cronExpression, channels, and style', async () => {
      const mockResult = { jobId: 11, templateId: 6 };
      (client.createSmartJobWithTemplate as ReturnType<typeof vi.fn>).mockResolvedValue(mockResult);

      await invokeTool(server, 'save_as_smart_job', {
        ...baseArgs,
        cronExpression: '0 9 * * 1',
        channels: ['CHAT', 'EMAIL'],
        style: '공식적인 보고서 톤',
      });

      expect(client.createSmartJobWithTemplate).toHaveBeenCalledWith(
        expect.objectContaining({
          cronExpression: '0 9 * * 1',
          channels: ['CHAT', 'EMAIL'],
          templateStyle: '공식적인 보고서 톤',
        }),
      );
    });

    it('defaults output_format to markdown when not provided', async () => {
      const mockResult = { jobId: 12, templateId: 7 };
      (client.createSmartJobWithTemplate as ReturnType<typeof vi.fn>).mockResolvedValue(mockResult);

      await invokeTool(server, 'save_as_smart_job', {
        ...baseArgs,
        templateStructure: {
          sections: [{ key: 'summary', label: '요약', type: 'text' }],
        },
      });

      expect(client.createSmartJobWithTemplate).toHaveBeenCalledWith(
        expect.objectContaining({
          templateStructure: expect.objectContaining({
            output_format: 'markdown',
          }),
        }),
      );
    });

    it('generates templateName from job name', async () => {
      (client.createSmartJobWithTemplate as ReturnType<typeof vi.fn>).mockResolvedValue({});

      await invokeTool(server, 'save_as_smart_job', {
        ...baseArgs,
        name: '일일 보고서',
      });

      expect(client.createSmartJobWithTemplate).toHaveBeenCalledWith(
        expect.objectContaining({
          templateName: '일일 보고서 양식',
        }),
      );
    });

    it('returns isError on API failure', async () => {
      (client.createSmartJobWithTemplate as ReturnType<typeof vi.fn>).mockRejectedValue(
        new Error('Internal Server Error'),
      );

      const result = await invokeTool(server, 'save_as_smart_job', baseArgs);

      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain('Internal Server Error');
    });
  });

  // --- show_report_builder (registered in ui-tools) ---
  describe('show_report_builder', () => {
    const baseArgs = {
      title: '화재 분석 리포트',
      question: '이번 달 화재 발생 현황은?',
      templateStructure: {
        sections: [
          { key: 'overview', label: '개요', type: 'text', required: true },
          { key: 'analysis', label: '분석', type: 'text' },
        ],
        output_format: 'markdown',
      },
      sectionContents: {
        overview: '이번 달 총 120건의 화재가 발생했습니다.',
        analysis: '주거지역 화재가 45%로 가장 높은 비중을 차지합니다.',
      },
    };

    it('returns widgetType report_builder with correct fields', async () => {
      const result = await invokeTool(server, 'show_report_builder', baseArgs);

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.widgetType).toBe('report_builder');
      expect(parsed.title).toBe('화재 분석 리포트');
      expect(parsed.question).toBe('이번 달 화재 발생 현황은?');
      expect(parsed.templateStructure).toEqual(baseArgs.templateStructure);
      expect(parsed.sectionContents).toEqual(baseArgs.sectionContents);
    });

    it('includes optional style when provided', async () => {
      const result = await invokeTool(server, 'show_report_builder', {
        ...baseArgs,
        style: '전문적인 보고서',
      });

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.style).toBe('전문적인 보고서');
    });

    it('style is undefined when not provided', async () => {
      const result = await invokeTool(server, 'show_report_builder', baseArgs);

      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.style).toBeUndefined();
    });

    it('does not call apiClient (pure passthrough)', async () => {
      await invokeTool(server, 'show_report_builder', baseArgs);

      const methodNames = Object.getOwnPropertyNames(FireHubApiClient.prototype).filter(
        (name) => name !== 'constructor',
      );
      for (const name of methodNames) {
        expect((client as unknown as Record<string, unknown>)[name]).not.toHaveBeenCalled();
      }
    });
  });

  // --- tool registration ---
  it('proactive tools are registered in the MCP server', () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const registeredTools = Object.keys((server.instance as any)._registeredTools);
    expect(registeredTools).toContain('generate_report');
    expect(registeredTools).toContain('save_as_smart_job');
    expect(registeredTools).toContain('show_report_builder');
    expect(registeredTools).toContain('list_proactive_jobs');
    expect(registeredTools).toContain('create_proactive_job');
    expect(registeredTools).toContain('update_proactive_job');
    expect(registeredTools).toContain('delete_proactive_job');
    expect(registeredTools).toContain('execute_proactive_job');
    expect(registeredTools).toContain('list_report_templates');
    expect(registeredTools).toContain('create_report_template');
  });
});
