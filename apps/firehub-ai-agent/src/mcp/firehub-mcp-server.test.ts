import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  createFireHubMcpServer,
  buildAllMcpTools,
  filterToolsByPermissions,
  createSafeTool,
  safeTool,
  isToolAvailableForTenant,
} from './firehub-mcp-server.js';
import { ROW_SEARCH_TOOLS } from '../agent/db-dialect.js';
import { FireHubApiClient } from './api-client.js';
import { createTracker, FAILURE_WARN_SENTINEL } from '../agent/failure-streak.js';
import { mkdtemp, readFile, rm } from 'fs/promises';
import { tmpdir } from 'os';
import { join } from 'path';
import { PROACTIVE_REPORT_ROOT } from '../agent/tenant-paths.js';
import { WRITE_REPORT_FILE_TOOL } from './tools/report-file-tools.js';

function createMockClient(): FireHubApiClient {
  const client = Object.create(FireHubApiClient.prototype);
  const methodNames = Object.getOwnPropertyNames(FireHubApiClient.prototype).filter(
    (name) => name !== 'constructor',
  );
  for (const name of methodNames) {
    client[name] = vi.fn().mockResolvedValue({ mocked: true });
  }
  // S3: withPurpose 는 Promise 가 아니라 클라이언트를 돌려줘야 한다(GraphRAG 도구가 등록 시 share 클라이언트를 만든다).
  client.withPurpose = vi.fn(() => client);
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

describe('방언별 도구 노출 (WD-79)', () => {
  const names = (opts?: Parameters<typeof buildAllMcpTools>[1]) =>
    buildAllMcpTools(createMockClient(), opts).map((t) => t.name);

  it('Tibero 면 search_dataset_rows 가 없다', () => {
    expect(names({ dbDialect: 'TIBERO' })).not.toContain('search_dataset_rows');
  });
  it('PG·옵션 없음이면 search_dataset_rows 가 있다', () => {
    expect(names({ dbDialect: 'POSTGRESQL' })).toContain('search_dataset_rows');
    expect(names()).toContain('search_dataset_rows');
  });
  it('isToolAvailableForTenant 판정 — 플래그가 없으면 방언 기준(후방호환)', () => {
    expect(ROW_SEARCH_TOOLS.has('search_dataset_rows')).toBe(true);
    expect(isToolAvailableForTenant('search_dataset_rows', 'TIBERO')).toBe(false);
    expect(isToolAvailableForTenant('search_dataset_rows', 'POSTGRESQL')).toBe(true);
    expect(isToolAvailableForTenant('search_dataset_rows', undefined)).toBe(true);
    expect(isToolAvailableForTenant('list_datasets', 'TIBERO')).toBe(true);
  });
});

describe('행 검색 플래그별 도구 노출 (WD-744)', () => {
  const names = (opts?: Parameters<typeof buildAllMcpTools>[1]) =>
    buildAllMcpTools(createMockClient(), opts).map((t) => t.name);

  // 방언 × 플래그 4조합 — 행 검색 도구 노출은 플래그만 따른다.
  it.each([
    ['TIBERO', true, true],
    ['TIBERO', false, false],
    ['POSTGRESQL', true, true],
    ['POSTGRESQL', false, false],
  ] as const)('%s · rowSearch=%s → 노출 %s', (dbDialect, rowSearch, shown) => {
    const list = names({ dbDialect, rowSearch });
    expect(list.includes('search_dataset_rows')).toBe(shown);
    // 행 검색 외 도구는 플래그와 무관하다.
    expect(list).toContain('list_datasets');
  });

  it('isToolAvailableForTenant — 플래그가 방언보다 앞선다', () => {
    expect(isToolAvailableForTenant('search_dataset_rows', 'TIBERO', true)).toBe(true);
    expect(isToolAvailableForTenant('search_dataset_rows', 'POSTGRESQL', false)).toBe(false);
    expect(isToolAvailableForTenant('list_datasets', 'POSTGRESQL', false)).toBe(true);
  });

  it('Tibero·rowSearch=true 의 get_dataset 은 TABLE 데이터셋의 searchIndex 를 조회한다', async () => {
    const client = createMockClient();
    (client.getDataset as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 1, storageType: 'TABLE' });
    (client.getDatasetSearchIndex as ReturnType<typeof vi.fn>).mockResolvedValue({ enabled: true });
    const server = createFireHubMcpServer(client, { dbDialect: 'TIBERO', rowSearch: true });
    const res = await invokeTool(server, 'get_dataset', { id: 1 });
    expect(client.getDatasetSearchIndex).toHaveBeenCalledWith(1);
    expect(JSON.parse(res.content[0].text).searchIndex).toEqual({ enabled: true });
  });

  it('Tibero·플래그 없음의 get_dataset 은 searchIndex 를 조회하지 않는다(후방호환)', async () => {
    const client = createMockClient();
    (client.getDataset as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 1, storageType: 'TABLE' });
    const server = createFireHubMcpServer(client, { dbDialect: 'TIBERO' });
    const res = await invokeTool(server, 'get_dataset', { id: 1 });
    expect(client.getDatasetSearchIndex).not.toHaveBeenCalled();
    expect(JSON.parse(res.content[0].text).searchIndex).toBeNull();
  });

  it('PG·rowSearch=false 의 get_dataset 도 searchIndex 를 조회하지 않는다', async () => {
    const client = createMockClient();
    (client.getDataset as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 1, storageType: 'TABLE' });
    const server = createFireHubMcpServer(client, { dbDialect: 'POSTGRESQL', rowSearch: false });
    await invokeTool(server, 'get_dataset', { id: 1 });
    expect(client.getDatasetSearchIndex).not.toHaveBeenCalled();
  });
});

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
    const args = { customUrl: 'https://api.example.com', method: 'GET', dataPath: '$.data' };
    await invokeTool(server, 'preview_api_call', args);
    expect(client.previewApiCall).toHaveBeenCalledWith(args);
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

  // WD-62·WD-765: 리포트 쓰기 도구는 스마트 작업 리포트 실행(reportDir 있음)에만 실린다 — 일반 채팅의 SDK 도구
  // 목록에는 절대 없어야 한다(반목표: 일반 채팅에서 write_report_file 이 보이는 것).
  it('reportDir 이 없으면 SDK 도구 목록에 write_report_file 이 없다 (WD-62)', () => {
    expect(buildAllMcpTools(client).find((t) => t.name === 'write_report_file')).toBeUndefined();
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

// S3: api 정책 차단(403 POLICY_BLOCKED)을 웹이 파싱하는 고정 표식으로 싣는다.
describe('POLICY_BLOCKED 도구 결과 표식(S3)', () => {
  type Def = { handler: (a: unknown) => Promise<{ content: { text: string }[]; isError?: boolean }> };
  const blockedError = () =>
    Object.assign(new Error('API 오류 (403): 차단'), {
      status: 403,
      policyBlocked: { action: 'AI', levelName: '민감', policyKey: 'ai_policy', message: '차단' },
    });

  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  it('safeTool 은 POLICY_BLOCKED 오류를 고정 JSON 표식 + isError 로 돌려준다', async () => {
    const def = safeTool('x', 'd', {}, async () => {
      throw blockedError();
    }) as unknown as Def;
    const result = await def.handler({});
    expect(result.isError).toBe(true);
    expect(JSON.parse(result.content[0].text)).toEqual({
      policyBlocked: true,
      code: 'POLICY_BLOCKED',
      action: 'AI',
      levelName: '민감',
      policyKey: 'ai_policy',
      message: '차단',
    });
  });

  it('safeTool 의 일반 오류는 기존처럼 메시지 원문이다', async () => {
    const def = safeTool('x', 'd', {}, async () => {
      throw new Error('boom');
    }) as unknown as Def;
    const result = await def.handler({});
    expect(result).toEqual({ content: [{ type: 'text', text: 'boom' }], isError: true });
  });

  it('createSafeTool 도 같은 표식을 싣는다', async () => {
    const def = createSafeTool(createTracker())('x', 'd', {}, async () => {
      throw blockedError();
    }) as unknown as Def;
    const result = await def.handler({});
    expect(result.isError).toBe(true);
    expect(JSON.parse(result.content[0].text)).toMatchObject({ policyBlocked: true, levelName: '민감' });
  });

  it('같은 차단을 임계 횟수만큼 반복해도 경고 힌트를 붙이지 않아 표식 JSON 이 깨지지 않는다', async () => {
    const def = createSafeTool(createTracker({ warnAt: 2, haltAt: 8 }))('x', 'd', {}, async () => {
      throw blockedError();
    }) as unknown as Def;
    const results = [];
    for (let i = 0; i < 3; i++) results.push(await def.handler({}));
    for (const r of results) {
      expect(r.content).toHaveLength(1);
      expect(() => JSON.parse(r.content.map((c) => c.text).join(''))).not.toThrow();
    }
  });

  it('일반 오류 반복에는 여전히 경고 힌트가 붙는다(차단 예외가 힌트를 끄지 않음)', async () => {
    const def = createSafeTool(createTracker({ warnAt: 2, haltAt: 8 }))('x', 'd', {}, async () => {
      throw new Error('boom');
    }) as unknown as Def;
    await def.handler({});
    const second = await def.handler({});
    expect(second.content.map((c) => c.text).join('')).toContain(FAILURE_WARN_SENTINEL);
  });
});

// WD-765: SDK 경로의 report-writer 도 opencode(WD-62)와 같은 전용 저장 도구로 리포트를 쓴다 — 인메모리 MCP 서버가
// reportDir 이 있을 때만 write_report_file 을 등록하고, 그 도구는 reportDir 안 허용된 세 파일에만 쓴다.
describe('SDK 리포트 저장 도구 (WD-765)', () => {
  let base: string;
  let reportDir: string;

  beforeEach(async () => {
    base = await mkdtemp(join(tmpdir(), 'wd765-'));
    reportDir = join(base, PROACTIVE_REPORT_ROOT, 't1', '1-100');
  });

  afterEach(async () => {
    await rm(base, { recursive: true, force: true });
  });

  it('reportDir 이 있으면 도구 목록에 write_report_file 이 있고, 없으면 없다', () => {
    const names = (opts?: Parameters<typeof buildAllMcpTools>[1]) =>
      buildAllMcpTools(createMockClient(), opts).map((t) => t.name);
    expect(names({ reportDir })).toContain(WRITE_REPORT_FILE_TOOL);
    expect(names()).not.toContain(WRITE_REPORT_FILE_TOOL);
    // 권한이 비어 있어도(fail-closed) 리포트 저장 도구는 남는다 — 저장은 권한 게이팅 대상인 플랫폼 변경이 아니다.
    expect(names({ reportDir, userPermissions: [] })).toContain(WRITE_REPORT_FILE_TOOL);
  });

  it('createFireHubMcpServer 의 도구는 reportDir 안 report.html 에 저장한다', async () => {
    const server = createFireHubMcpServer(createMockClient(), { reportDir });
    const res = await invokeTool(server, WRITE_REPORT_FILE_TOOL, { fileName: 'report.html', content: '<!DOCTYPE html><p>ok</p>' });
    expect(res.isError).toBeFalsy();
    expect(await readFile(join(reportDir, 'report.html'), 'utf-8')).toBe('<!DOCTYPE html><p>ok</p>');
  });

  it('허용되지 않은 파일 이름은 오류 결과를 돌려준다', async () => {
    const server = createFireHubMcpServer(createMockClient(), { reportDir });
    const res = await invokeTool(server, WRITE_REPORT_FILE_TOOL, { fileName: '../evil.html', content: 'x' });
    expect(res.isError).toBe(true);
  });

  it('reportDir 이 없는 서버에는 도구가 등록되지 않는다', async () => {
    const server = createFireHubMcpServer(createMockClient());
    await expect(invokeTool(server, WRITE_REPORT_FILE_TOOL, { fileName: 'report.html', content: 'x' })).rejects.toThrow(
      /not found/,
    );
  });
});
