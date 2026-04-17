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
    // title 필드가 추가된 기본 인수
    const baseArgs = {
      title: '월별 화재 발생 추이 분석',
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

    it('returns widgetType report_builder with question and template structure', async () => {
      const result = await invokeTool(server, 'generate_report', baseArgs);

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      // widgetType이 report_builder로 반환되어야 프론트엔드에서 위젯으로 렌더링됩니다
      expect(parsed.widgetType).toBe('report_builder');
      expect(parsed.question).toBe('월별 화재 발생 추이는?');
      expect(parsed.templateStructure).toEqual(baseArgs.templateStructure);
      expect(parsed.sectionContents).toEqual(baseArgs.sectionContents);
    });

    it('includes title field in response', async () => {
      const result = await invokeTool(server, 'generate_report', baseArgs);

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      // title 필드가 응답에 포함되어야 합니다
      expect(parsed.title).toBe('월별 화재 발생 추이 분석');
    });

    it('applies default preset when templateStructure is not provided', async () => {
      // templateStructure를 생략하면 기본 "일반 분석" 프리셋이 적용되어야 합니다
      const argsWithoutTemplate = {
        title: '기본 프리셋 테스트',
        question: '데이터를 분석해줘',
        sectionContents: {
          executive_summary: '핵심 요약 내용',
          data_analysis: '분석 내용',
          key_metrics: '지표 내용',
        },
      };

      const result = await invokeTool(server, 'generate_report', argsWithoutTemplate);

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      // 기본 프리셋이 적용되어 4개 섹션이 반환되어야 합니다
      expect(parsed.templateStructure.sections).toHaveLength(4);
    });

    it('default preset has correct section keys and types', async () => {
      const argsWithoutTemplate = {
        title: '기본 프리셋 섹션 검증',
        question: '데이터를 분석해줘',
        sectionContents: {},
      };

      const result = await invokeTool(server, 'generate_report', argsWithoutTemplate);

      const parsed = JSON.parse(result.content[0].text);
      const sections = parsed.templateStructure.sections;

      // 기본 프리셋의 각 섹션 key/label/type 검증
      expect(sections[0]).toMatchObject({ key: 'executive_summary', label: '핵심 요약', type: 'text', required: true });
      expect(sections[1]).toMatchObject({ key: 'data_analysis', label: '데이터 분석', type: 'text', required: true });
      expect(sections[2]).toMatchObject({ key: 'key_metrics', label: '주요 지표', type: 'cards', required: true });
      expect(sections[3]).toMatchObject({ key: 'recommendations', label: '권장 사항', type: 'recommendation' });
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

      // generate_report는 패스스루 도구 — API 호출 없음
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

  // --- list_proactive_jobs ---
  describe('list_proactive_jobs', () => {
    it('calls apiClient.listSmartJobs', async () => {
      (client.listSmartJobs as ReturnType<typeof vi.fn>).mockResolvedValue([]);
      const result = await invokeTool(server, 'list_proactive_jobs');
      expect(client.listSmartJobs).toHaveBeenCalled();
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.listSmartJobs as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'list_proactive_jobs');
      expect(result.isError).toBe(true);
    });
  });

  // --- create_proactive_job ---
  describe('create_proactive_job', () => {
    it('calls apiClient.createSmartJob with args', async () => {
      const args = { name: '테스트 작업', prompt: '분석 프롬프트', cronExpression: '0 9 * * *' };
      const result = await invokeTool(server, 'create_proactive_job', args);
      expect(client.createSmartJob).toHaveBeenCalledWith(args);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.createSmartJob as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'create_proactive_job', {
        name: '작업', prompt: '프롬프트', cronExpression: '0 9 * * *',
      });
      expect(result.isError).toBe(true);
    });
  });

  // --- update_proactive_job ---
  describe('update_proactive_job', () => {
    it('calls apiClient.updateSmartJob with id and data', async () => {
      const args = { id: 1, name: '수정된 작업', enabled: true };
      const result = await invokeTool(server, 'update_proactive_job', args);
      expect(client.updateSmartJob).toHaveBeenCalledWith(1, { name: '수정된 작업', enabled: true });
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.updateSmartJob as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'update_proactive_job', { id: 1 });
      expect(result.isError).toBe(true);
    });
  });

  // --- delete_proactive_job ---
  describe('delete_proactive_job', () => {
    it('calls apiClient.deleteSmartJob with id', async () => {
      const result = await invokeTool(server, 'delete_proactive_job', { id: 5 });
      expect(client.deleteSmartJob).toHaveBeenCalledWith(5);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.deleteSmartJob as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'delete_proactive_job', { id: 5 });
      expect(result.isError).toBe(true);
    });
  });

  // --- execute_proactive_job ---
  describe('execute_proactive_job', () => {
    it('calls apiClient.executeSmartJob with id', async () => {
      const result = await invokeTool(server, 'execute_proactive_job', { id: 3 });
      expect(client.executeSmartJob).toHaveBeenCalledWith(3);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.executeSmartJob as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'execute_proactive_job', { id: 3 });
      expect(result.isError).toBe(true);
    });
  });

  // --- list_report_templates ---
  describe('list_report_templates', () => {
    it('calls apiClient.listReportTemplates', async () => {
      const result = await invokeTool(server, 'list_report_templates');
      expect(client.listReportTemplates).toHaveBeenCalled();
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.listReportTemplates as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'list_report_templates');
      expect(result.isError).toBe(true);
    });
  });

  // --- create_report_template ---
  describe('create_report_template', () => {
    it('calls apiClient.createReportTemplate with args', async () => {
      const args = {
        name: '분석 양식',
        structure: {
          sections: [{ key: 'summary', label: '요약', required: true }],
          output_format: 'markdown',
        },
      };
      const result = await invokeTool(server, 'create_report_template', args);
      expect(client.createReportTemplate).toHaveBeenCalledWith(args);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.createReportTemplate as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'create_report_template', {
        name: '양식',
        structure: { sections: [{ key: 'a', label: 'A' }], output_format: 'markdown' },
      });
      expect(result.isError).toBe(true);
    });
  });

  // --- get_report_template ---
  describe('get_report_template', () => {
    it('calls apiClient.getReportTemplate with id', async () => {
      const result = await invokeTool(server, 'get_report_template', { id: 2 });
      expect(client.getReportTemplate).toHaveBeenCalledWith(2);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.getReportTemplate as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'get_report_template', { id: 2 });
      expect(result.isError).toBe(true);
    });
  });

  // --- update_report_template ---
  describe('update_report_template', () => {
    it('calls apiClient.updateReportTemplate with id and data', async () => {
      const args = { id: 4, name: '수정된 양식' };
      const result = await invokeTool(server, 'update_report_template', args);
      expect(client.updateReportTemplate).toHaveBeenCalledWith(4, { name: '수정된 양식' });
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.updateReportTemplate as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'update_report_template', { id: 4 });
      expect(result.isError).toBe(true);
    });
  });

  // --- delete_report_template ---
  describe('delete_report_template', () => {
    it('calls apiClient.deleteReportTemplate with id', async () => {
      const result = await invokeTool(server, 'delete_report_template', { id: 7 });
      expect(client.deleteReportTemplate).toHaveBeenCalledWith(7);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.deleteReportTemplate as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'delete_report_template', { id: 7 });
      expect(result.isError).toBe(true);
    });
  });

  // --- list_job_executions ---
  describe('list_job_executions', () => {
    it('calls apiClient.listJobExecutions with jobId and params', async () => {
      const args = { jobId: 1, limit: 5 };
      const result = await invokeTool(server, 'list_job_executions', args);
      expect(client.listJobExecutions).toHaveBeenCalledWith(1, { limit: 5 });
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.listJobExecutions as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'list_job_executions', { jobId: 1 });
      expect(result.isError).toBe(true);
    });
  });

  // --- get_execution ---
  describe('get_execution', () => {
    it('calls apiClient.getExecution with jobId and executionId', async () => {
      const result = await invokeTool(server, 'get_execution', { jobId: 1, executionId: 10 });
      expect(client.getExecution).toHaveBeenCalledWith(1, 10);
      expect(result.isError).toBeFalsy();
    });

    it('returns isError on failure', async () => {
      (client.getExecution as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API 오류'));
      const result = await invokeTool(server, 'get_execution', { jobId: 1, executionId: 10 });
      expect(result.isError).toBe(true);
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
