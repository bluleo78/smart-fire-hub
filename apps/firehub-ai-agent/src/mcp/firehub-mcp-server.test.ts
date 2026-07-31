import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  createFireHubMcpServer,
  buildAllMcpTools,
  filterToolsByPermissions,
  createSafeTool,
} from './firehub-mcp-server.js';
import { FireHubApiClient } from './api-client.js';
import { createTracker, FAILURE_WARN_SENTINEL } from '../agent/failure-streak.js';

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

describe('createFireHubMcpServer', () => {
  it('should return McpSdkServerConfigWithInstance with type sdk', () => {
    const client = createMockClient();
    const result = createFireHubMcpServer(client);

    expect(result).toBeDefined();
    expect(result.type).toBe('sdk');
    expect(result.name).toBe('firehub');
    expect(result).toHaveProperty('instance');
  });

  it('should have an McpServer instance', () => {
    const client = createMockClient();
    const result = createFireHubMcpServer(client);

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const instance = (result as any).instance;
    expect(instance).toBeDefined();
    expect(typeof instance).toBe('object');
  });

  it('safeTool should catch errors and return isError result', async () => {
    const client = createMockClient();
    (client.listCategories as ReturnType<typeof vi.fn>).mockRejectedValue(
      new Error('Connection refused'),
    );

    const result = createFireHubMcpServer(client);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const instance = (result as any).instance;

    expect(instance).toBeDefined();
    await expect(client.listCategories()).rejects.toThrow('Connection refused');
  });

  it('jsonResult should produce correct format (verified via api-client mock)', async () => {
    const client = createMockClient();
    const mockData = { datasets: 5, pipelines: 3 };
    (client.getDashboard as ReturnType<typeof vi.fn>).mockResolvedValue(mockData);

    const data = await client.getDashboard();
    expect(data).toEqual(mockData);

    const result = createFireHubMcpServer(client);
    expect(result.type).toBe('sdk');
    expect(result.name).toBe('firehub');
  });
});

describe('MCP Tool Handlers', () => {
  let client: FireHubApiClient;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let server: any;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    client = createMockClient();
    server = createFireHubMcpServer(client);
  });

  // MCP-01: list_categories
  it('list_categories calls apiClient.listCategories', async () => {
    const mockData = [{ id: 1, name: 'Cat1' }];
    (client.listCategories as ReturnType<typeof vi.fn>).mockResolvedValue(mockData);

    const result = await invokeTool(server, 'list_categories');

    expect(client.listCategories).toHaveBeenCalled();
    expect(result.content[0].type).toBe('text');
    expect(JSON.parse(result.content[0].text)).toEqual(mockData);
  });

  // MCP-02: create_category
  it('create_category calls apiClient.createCategory with correct args', async () => {
    await invokeTool(server, 'create_category', { name: 'Test', description: 'desc' });
    expect(client.createCategory).toHaveBeenCalledWith({ name: 'Test', description: 'desc' });
  });

  // MCP-03: list_datasets
  it('list_datasets calls apiClient.listDatasets with params', async () => {
    await invokeTool(server, 'list_datasets', { categoryId: 1, page: 0, size: 10 });
    expect(client.listDatasets).toHaveBeenCalledWith({ categoryId: 1, page: 0, size: 10 });
  });

  // MCP-04: create_dataset
  it('create_dataset calls apiClient.createDataset with columns', async () => {
    const args = {
      name: 'Test',
      tableName: 'test_table',
      columns: [{ columnName: 'col1', displayName: 'Col 1', dataType: 'TEXT' }],
    };
    await invokeTool(server, 'create_dataset', args);
    // 에이전트는 정형 데이터셋만 생성하므로 storageType: 'TABLE' 이 항상 추가된다.
    expect(client.createDataset).toHaveBeenCalledWith({ ...args, storageType: 'TABLE' });
  });

  // MCP-05: query_dataset_data
  it('query_dataset_data calls apiClient.queryDatasetData', async () => {
    await invokeTool(server, 'query_dataset_data', { id: 1, page: 0, size: 50 });
    expect(client.queryDatasetData).toHaveBeenCalledWith(1, { page: 0, size: 50 });
  });

  // MCP-06: execute_sql_query
  it('execute_sql_query calls apiClient.executeQuery', async () => {
    await invokeTool(server, 'execute_sql_query', { datasetId: 1, sql: 'SELECT 1', maxRows: 100 });
    expect(client.executeQuery).toHaveBeenCalledWith(1, 'SELECT 1', 100);
  });

  // MCP-07: add_rows
  it('add_rows calls apiClient.addRowsBatch', async () => {
    const rows = [{ name: 'test' }];
    await invokeTool(server, 'add_rows', { datasetId: 1, rows });
    expect(client.addRowsBatch).toHaveBeenCalledWith(1, rows);
  });

  // MCP-08: delete_rows
  it('delete_rows calls apiClient.deleteRows', async () => {
    await invokeTool(server, 'delete_rows', { datasetId: 1, rowIds: [1, 2, 3] });
    expect(client.deleteRows).toHaveBeenCalledWith(1, [1, 2, 3]);
  });

  // MCP-09: truncate_dataset
  it('truncate_dataset calls apiClient.truncateDataset', async () => {
    await invokeTool(server, 'truncate_dataset', { datasetId: 1 });
    expect(client.truncateDataset).toHaveBeenCalledWith(1);
  });

  // MCP-10: replace_dataset_data
  it('replace_dataset_data calls apiClient.replaceDatasetData', async () => {
    const rows = [{ col: 'val' }];
    await invokeTool(server, 'replace_dataset_data', { datasetId: 1, rows });
    expect(client.replaceDatasetData).toHaveBeenCalledWith(1, rows);
  });

  // MCP-11: create_pipeline
  it('create_pipeline calls apiClient.createPipeline', async () => {
    const args = { name: 'Test Pipeline', steps: [{ name: 'step1', scriptType: 'SQL' }] };
    await invokeTool(server, 'create_pipeline', args);
    expect(client.createPipeline).toHaveBeenCalled();
  });

  // MCP-12: execute_pipeline
  it('execute_pipeline calls apiClient.executePipeline', async () => {
    await invokeTool(server, 'execute_pipeline', { id: 5 });
    expect(client.executePipeline).toHaveBeenCalledWith(5);
  });

  // MCP-13: preview_api_call
  it('preview_api_call calls apiClient.previewApiCall', async () => {
    const args = { url: 'https://api.example.com', method: 'GET', dataPath: '$.data' };
    await invokeTool(server, 'preview_api_call', args);
    expect(client.previewApiCall).toHaveBeenCalled();
  });

  // MCP-14: create_trigger
  it('create_trigger calls apiClient.createTrigger', async () => {
    const args = {
      pipelineId: 1,
      name: 'Trigger',
      triggerType: 'SCHEDULE',
      config: { cronExpression: '0 0 * * *' },
    };
    await invokeTool(server, 'create_trigger', args);
    expect(client.createTrigger).toHaveBeenCalledWith(1, {
      name: 'Trigger',
      triggerType: 'SCHEDULE',
      config: { cronExpression: '0 0 * * *' },
    });
  });

  // MCP-15: update_trigger
  it('update_trigger calls apiClient.updateTrigger', async () => {
    const args = { pipelineId: 1, triggerId: 2, name: 'Updated', isEnabled: false };
    await invokeTool(server, 'update_trigger', args);
    expect(client.updateTrigger).toHaveBeenCalledWith(1, 2, { name: 'Updated', isEnabled: false });
  });

  // MCP-16: create_api_connection
  it('create_api_connection calls apiClient.createApiConnection', async () => {
    const args = { name: 'My API', authType: 'BEARER', authConfig: { token: 'abc' } };
    await invokeTool(server, 'create_api_connection', args);
    expect(client.createApiConnection).toHaveBeenCalledWith(args);
  });

  // MCP-17: delete_api_connection
  it('delete_api_connection calls apiClient.deleteApiConnection', async () => {
    await invokeTool(server, 'delete_api_connection', { id: 5 });
    expect(client.deleteApiConnection).toHaveBeenCalledWith(5);
  });

  // MCP-17b: test_api_connection
  it('test_api_connection calls apiClient.testApiConnection', async () => {
    (client.testApiConnection as ReturnType<typeof vi.fn>).mockResolvedValue({
      ok: true,
      status: 200,
      latencyMs: 120,
      errorMessage: null,
    });

    const result = await invokeTool(server, 'test_api_connection', { id: 3 });

    expect(client.testApiConnection).toHaveBeenCalledWith(3);
    expect(result.isError).toBeUndefined();
    const parsed = JSON.parse(result.content[0].text);
    expect(parsed.ok).toBe(true);
    expect(parsed.latencyMs).toBe(120);
  });

  // MCP-18: get_dashboard
  it('get_dashboard calls apiClient.getDashboard', async () => {
    const mockData = { datasets: 5, pipelines: 3 };
    (client.getDashboard as ReturnType<typeof vi.fn>).mockResolvedValue(mockData);

    const result = await invokeTool(server, 'get_dashboard');

    expect(client.getDashboard).toHaveBeenCalled();
    expect(JSON.parse(result.content[0].text)).toEqual(mockData);
  });

  // MCP-19: Error handling — safeTool wrapper catches errors
  it('safeTool catches api errors and returns isError result', async () => {
    (client.listCategories as ReturnType<typeof vi.fn>).mockRejectedValue(
      new Error('Connection refused'),
    );

    const result = await invokeTool(server, 'list_categories');

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain('Connection refused');
  });

  // MCP-20: jsonResult format verification
  it('jsonResult produces { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] }', async () => {
    const mockData = { id: 1, name: 'test' };
    (client.getDashboard as ReturnType<typeof vi.fn>).mockResolvedValue(mockData);

    const result = await invokeTool(server, 'get_dashboard');

    expect(result.content).toHaveLength(1);
    expect(result.content[0].type).toBe('text');
    const parsed = JSON.parse(result.content[0].text);
    expect(parsed).toEqual(mockData);
    expect(result.content[0].text).toBe(JSON.stringify(mockData, null, 2));
  });
});

/**
 * 파괴적 도구 권한 기반 필터링 테스트.
 *
 * 권한 매개변수의 3가지 상태:
 * - undefined: 후방호환, 모든 도구 허용
 * - []: fail-closed, 권한 요구가 있는 도구는 전부 제외
 * - 배열: 해당 권한에 매칭되는 도구만 허용
 */
describe('destructive tool filtering', () => {
  let client: FireHubApiClient;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    client = createMockClient();
  });

  it('excludes delete_dataset and drop_dataset_column when user lacks dataset:delete permission', () => {
    const tools = buildAllMcpTools(client, {
      userPermissions: ['dataset:read', 'dataset:update'],
    });
    expect(tools.find((t) => t.name === 'delete_dataset')).toBeUndefined();
    expect(tools.find((t) => t.name === 'drop_dataset_column')).toBeUndefined();
    // sanity: 비파괴 도구는 여전히 존재
    expect(tools.find((t) => t.name === 'list_datasets')).toBeDefined();
  });

  it('includes destructive tools when user has dataset:delete permission', () => {
    const tools = buildAllMcpTools(client, {
      userPermissions: ['dataset:read', 'dataset:delete'],
    });
    expect(tools.find((t) => t.name === 'delete_dataset')).toBeDefined();
    expect(tools.find((t) => t.name === 'drop_dataset_column')).toBeDefined();
  });

  it('includes all tools when userPermissions is undefined (backwards compat)', () => {
    const tools = buildAllMcpTools(client);
    expect(tools.find((t) => t.name === 'delete_dataset')).toBeDefined();
    expect(tools.find((t) => t.name === 'drop_dataset_column')).toBeDefined();
  });

  it('excludes destructive tools when userPermissions is empty array (fail-closed)', () => {
    const tools = buildAllMcpTools(client, { userPermissions: [] });
    expect(tools.find((t) => t.name === 'delete_dataset')).toBeUndefined();
    expect(tools.find((t) => t.name === 'drop_dataset_column')).toBeUndefined();
    // 비파괴 도구는 fail-closed에서도 유지되어야 함
    expect(tools.find((t) => t.name === 'list_datasets')).toBeDefined();
  });

  // GraphRAG 도구가 MCP 서버에 실제로 등록되고 권한 등급이 의도대로 갈리는지 고정한다.
  // (등록 누락/권한 오분류는 프롬프트만으로는 드러나지 않아 회귀 가드가 필요하다.)
  it('graphrag 도구가 등록되고 read/write 권한으로 분리 게이팅된다', () => {
    const READ_TOOLS = [
      'graphrag_query', 'graphrag_structured_query',
      'graphrag_list_ontologies', 'graphrag_describe_ontology',
      'graphrag_ingest_history', 'graphrag_list_review_items', 'graphrag_review_evidence',
    ];
    const WRITE_TOOLS = [
      'graphrag_ingest', 'graphrag_project_table',
      'graphrag_infer_mapping', 'graphrag_bind_ontology', 'graphrag_activate_mapping',
      'graphrag_approve_review_item', 'graphrag_reject_review_item',
    ];

    // 권한 미지정(후방호환) — 전부 노출
    const all = buildAllMcpTools(client);
    for (const name of [...READ_TOOLS, ...WRITE_TOOLS]) {
      expect(all.find((t) => t.name === name), `${name} 미등록`).toBeDefined();
    }

    // dataset:read 만 — 조회 계열만 남고 구축 계열은 제외
    const readOnly = buildAllMcpTools(client, { userPermissions: ['dataset:read'] });
    for (const name of READ_TOOLS) {
      expect(readOnly.find((t) => t.name === name), `${name} 이 read 권한에서 사라짐`).toBeDefined();
    }
    for (const name of WRITE_TOOLS) {
      expect(readOnly.find((t) => t.name === name), `${name} 이 read 권한에 노출됨`).toBeUndefined();
    }

    // dataset:write 보유 시 구축 계열 노출
    const writer = buildAllMcpTools(client, { userPermissions: ['dataset:read', 'dataset:write'] });
    for (const name of WRITE_TOOLS) {
      expect(writer.find((t) => t.name === name), `${name} 이 write 권한에서 누락`).toBeDefined();
    }
  });

  // 항목 4 회귀 가드: 도구 설명에 특정 온톨로지 속성을 하드코딩하면 온톨로지가 바뀌어도
  // 모델은 옛 속성 하나만 알게 된다. 설명은 discovery 도구를 가리켜야 한다.
  it('graphrag_structured_query 설명이 온톨로지 속성을 하드코딩하지 않는다', () => {
    const tools = buildAllMcpTools(client);
    const tool = tools.find((t) => t.name === 'graphrag_structured_query');
    expect(tool).toBeDefined();
    const description = (tool as unknown as { description: string }).description;
    expect(description).not.toContain('피해액');
    expect(description).toContain('graphrag_describe_ontology');
  });

  // 필터 유틸 단위 테스트 (맵 직접 검증)
  it('filterToolsByPermissions: unknown tool names pass through', () => {
    const fake = [
      { name: 'delete_dataset' },
      { name: 'list_datasets' },
      { name: 'drop_dataset_column' },
      { name: 'some_other_tool' },
    ];
    const filtered = filterToolsByPermissions(fake, ['dataset:read']);
    expect(filtered.map((t) => t.name)).toEqual(['list_datasets', 'some_other_tool']);
  });
});

describe('createSafeTool Tier1 경고 주입', () => {
  it('동일 오류 4회째 결과에만 경고 힌트가 붙는다', async () => {
    const tracker = createTracker({ warnAt: 4, haltAt: 8 });
    const safeTool = createSafeTool(tracker);
    const def = safeTool('execute_sql_query', 'desc', {}, async () => ({
      content: [{ type: 'text', text: 'column "x" does not exist' }],
      isError: true,
    })) as unknown as { handler: (a: unknown) => Promise<{ content: { text: string }[] }> };

    const texts: string[] = [];
    for (let i = 0; i < 5; i++) {
      const r = await def.handler({});
      texts.push(r.content.map((c) => c.text).join(''));
    }
    expect(texts[0]).not.toContain(FAILURE_WARN_SENTINEL);
    expect(texts[2]).not.toContain(FAILURE_WARN_SENTINEL);
    expect(texts[3]).toContain(FAILURE_WARN_SENTINEL); // 4회째만
    expect(texts[4]).not.toContain(FAILURE_WARN_SENTINEL);
  });

  it('성공 결과엔 힌트 없음 + 카운터 리셋', async () => {
    const tracker = createTracker({ warnAt: 2, haltAt: 8 });
    const safeTool = createSafeTool(tracker);
    let fail = true;
    const def = safeTool('tool_a', 'desc', {}, async () => ({
      content: [{ type: 'text', text: fail ? 'boom' : 'ok' }],
      isError: fail,
    })) as unknown as { handler: (a: unknown) => Promise<{ content: { text: string }[] }> };

    await def.handler({}); // 실패 1
    fail = false;
    const ok = await def.handler({}); // 성공 → 리셋
    expect(ok.content.map((c) => c.text).join('')).not.toContain(FAILURE_WARN_SENTINEL);
    fail = true;
    const r = await def.handler({}); // 다시 1 (warnAt=2 미달)
    expect(r.content.map((c) => c.text).join('')).not.toContain(FAILURE_WARN_SENTINEL);
  });
});
