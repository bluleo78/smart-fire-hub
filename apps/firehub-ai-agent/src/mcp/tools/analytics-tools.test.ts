import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createFireHubMcpServer } from '../firehub-mcp-server.js';
import { FireHubApiClient } from '../api-client.js';
import { clampAnalyticsResult } from './analytics-tools.js';

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

describe('Analytics MCP Tools', () => {
  let client: FireHubApiClient;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let server: any;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    client = createMockClient();
    server = createFireHubMcpServer(client);
  });

  // --- execute_analytics_query ---
  it('execute_analytics_query calls apiClient.executeAnalyticsQuery with sql and maxRows', async () => {
    const mockResult = {
      queryType: 'SELECT',
      columns: ['id', 'name'],
      rows: [{ id: 1, name: 'test' }],
      affectedRows: 0,
      executionTimeMs: 42,
      totalRows: 1,
      truncated: false,
      error: null,
    };
    (client.executeAnalyticsQuery as ReturnType<typeof vi.fn>).mockResolvedValue(mockResult);

    const result = await invokeTool(server, 'execute_analytics_query', {
      sql: 'SELECT id, name FROM users',
      maxRows: 100,
    });

    expect(client.executeAnalyticsQuery).toHaveBeenCalledWith('SELECT id, name FROM users', 100);
    expect(result.content[0].type).toBe('text');
    expect(JSON.parse(result.content[0].text)).toEqual(mockResult);
    expect(result.isError).toBeUndefined();
  });

  it('execute_analytics_query works without maxRows', async () => {
    const mockResult = { queryType: 'SELECT', columns: ['cnt'], rows: [], affectedRows: 0, executionTimeMs: 5, totalRows: 0, truncated: false, error: null };
    (client.executeAnalyticsQuery as ReturnType<typeof vi.fn>).mockResolvedValue(mockResult);

    await invokeTool(server, 'execute_analytics_query', { sql: 'SELECT COUNT(*) as cnt FROM t' });

    // 이슈 #251: maxRows 미지정 시 tool 레이어가 기본값 1000으로 cap한다.
    expect(client.executeAnalyticsQuery).toHaveBeenCalledWith('SELECT COUNT(*) as cnt FROM t', 1000);
  });

  it('execute_analytics_query returns isError on failure', async () => {
    (client.executeAnalyticsQuery as ReturnType<typeof vi.fn>).mockRejectedValue(
      new Error('DML not allowed in read-only mode'),
    );

    const result = await invokeTool(server, 'execute_analytics_query', { sql: 'DELETE FROM users' });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain('DML not allowed');
  });

  // --- 이슈 #251: execute_analytics_query 응답 크기 가드 ---
  describe('execute_analytics_query response size guard (#251)', () => {
    it('passes maxRows through when caller specifies it', async () => {
      (client.executeAnalyticsQuery as ReturnType<typeof vi.fn>).mockResolvedValue({
        queryType: 'SELECT', columns: ['id'], rows: [{ id: 1 }], affectedRows: 0, executionTimeMs: 1, totalRows: 1, truncated: false, error: null,
      });
      await invokeTool(server, 'execute_analytics_query', { sql: 'SELECT id FROM t', maxRows: 5000 });
      expect(client.executeAnalyticsQuery).toHaveBeenCalledWith('SELECT id FROM t', 5000);
    });

    it('clamps large response with truncated meta when serialized size exceeds threshold', async () => {
      // ~3000행 × 컬럼 7개로 약 200KB 초과 응답 시뮬레이션
      const bigRows = Array.from({ length: 3000 }, (_, i) => ({
        id: i,
        name: `name_${i}_with_some_padding_text_to_inflate_bytes`,
        addr: `address_line_${i}_${'x'.repeat(40)}`,
        phone: `010-${String(i).padStart(4, '0')}-1234`,
        email: `user${i}@example.com`,
        memo: `${'lorem ipsum '.repeat(5)}${i}`,
        created: `2026-01-${(i % 28) + 1}T00:00:00Z`,
      }));
      (client.executeAnalyticsQuery as ReturnType<typeof vi.fn>).mockResolvedValue({
        queryType: 'SELECT',
        columns: ['id', 'name', 'addr', 'phone', 'email', 'memo', 'created'],
        rows: bigRows,
        affectedRows: 0,
        executionTimeMs: 200,
        totalRows: 3000,
        truncated: false,
        error: null,
      });

      const result = await invokeTool(server, 'execute_analytics_query', { sql: 'SELECT * FROM users', maxRows: 10000 });
      const parsed = JSON.parse(result.content[0].text);

      expect(parsed.truncated).toBe(true);
      expect(parsed.totalRows).toBe(3000);
      expect(parsed.returnedRows).toBeLessThan(3000);
      expect(parsed.returnedRows).toBe(parsed.rows.length);
      expect(typeof parsed.hint).toBe('string');
      expect(parsed.hint).toMatch(/LIMIT|집계|차트/);
      // 직렬화 결과가 200KB 임계치 이하인지 확인
      expect(result.content[0].text.length).toBeLessThanOrEqual(200_000);
    });

    it('does not modify response when result is small', async () => {
      const smallResult = {
        queryType: 'SELECT', columns: ['id'], rows: [{ id: 1 }, { id: 2 }],
        affectedRows: 0, executionTimeMs: 3, totalRows: 2, truncated: false, error: null,
      };
      (client.executeAnalyticsQuery as ReturnType<typeof vi.fn>).mockResolvedValue(smallResult);
      const result = await invokeTool(server, 'execute_analytics_query', { sql: 'SELECT id FROM t' });
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.truncated).toBe(false);
      expect(parsed.rows).toHaveLength(2);
      expect(parsed.hint).toBeUndefined();
      expect(parsed.returnedRows).toBeUndefined();
    });

    it('clampAnalyticsResult unit: passes through small payloads unchanged', () => {
      const r = { queryType: 'SELECT', columns: ['a'], rows: [{ a: 1 }], totalRows: 1, truncated: false };
      expect(clampAnalyticsResult(r)).toBe(r);
    });

    it('clampAnalyticsResult unit: truncates and attaches meta when over budget', () => {
      const rows = Array.from({ length: 500 }, (_, i) => ({ a: 'x'.repeat(500), i }));
      const r = { queryType: 'SELECT', columns: ['a', 'i'], rows, totalRows: 500, truncated: false };
      const out = clampAnalyticsResult(r, 10_000); // 10KB 강제 임계치
      expect(out.truncated).toBe(true);
      expect(out.returnedRows).toBeLessThan(500);
      expect(out.totalRows).toBe(500);
      expect(out.hint).toBeDefined();
      expect(JSON.stringify(out).length).toBeLessThanOrEqual(10_000);
    });

    it('clampAnalyticsResult unit: empty rows returned unchanged', () => {
      const r = { queryType: 'SELECT', columns: [], rows: [], totalRows: 0, truncated: false };
      expect(clampAnalyticsResult(r, 100)).toBe(r);
    });
  });

  // --- create_saved_query ---
  it('create_saved_query calls apiClient.createSavedQuery with correct args', async () => {
    const args = {
      name: '월별 추이',
      sqlText: "SELECT DATE_TRUNC('month', created_at) as month, COUNT(*) FROM events GROUP BY 1",
      description: '이벤트 월별 통계',
      folder: '주간보고',
      isShared: false,
    };
    const mockResp = { id: 1, ...args, chartCount: 0, createdAt: '2026-02-28T00:00:00Z', updatedAt: '2026-02-28T00:00:00Z' };
    (client.createSavedQuery as ReturnType<typeof vi.fn>).mockResolvedValue(mockResp);

    const result = await invokeTool(server, 'create_saved_query', args);

    expect(client.createSavedQuery).toHaveBeenCalledWith(args);
    expect(JSON.parse(result.content[0].text).id).toBe(1);
    expect(result.isError).toBeUndefined();
  });

  it('create_saved_query returns isError on failure', async () => {
    (client.createSavedQuery as ReturnType<typeof vi.fn>).mockRejectedValue(
      new Error('name is required'),
    );

    const result = await invokeTool(server, 'create_saved_query', { name: '', sqlText: 'SELECT 1' });

    expect(result.isError).toBe(true);
  });

  // --- list_saved_queries ---
  it('list_saved_queries calls apiClient.listSavedQueries with no params', async () => {
    const mockList = { content: [{ id: 1, name: '쿼리1' }], totalElements: 1, totalPages: 1, number: 0, size: 20 };
    (client.listSavedQueries as ReturnType<typeof vi.fn>).mockResolvedValue(mockList);

    const result = await invokeTool(server, 'list_saved_queries', {});

    expect(client.listSavedQueries).toHaveBeenCalledWith({});
    expect(JSON.parse(result.content[0].text).totalElements).toBe(1);
    expect(result.isError).toBeUndefined();
  });

  it('list_saved_queries passes search and folder params', async () => {
    (client.listSavedQueries as ReturnType<typeof vi.fn>).mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });

    await invokeTool(server, 'list_saved_queries', { search: '월별', folder: '주간보고' });

    expect(client.listSavedQueries).toHaveBeenCalledWith({ search: '월별', folder: '주간보고' });
  });

  // --- run_saved_query ---
  it('run_saved_query calls apiClient.executeSavedQuery with queryId', async () => {
    const mockResult = {
      queryType: 'SELECT',
      columns: ['month', 'cnt'],
      rows: [{ month: '2026-01', cnt: 100 }],
      affectedRows: 0,
      executionTimeMs: 55,
      totalRows: 1,
      truncated: false,
      error: null,
    };
    (client.executeSavedQuery as ReturnType<typeof vi.fn>).mockResolvedValue(mockResult);

    const result = await invokeTool(server, 'run_saved_query', { queryId: 7 });

    expect(client.executeSavedQuery).toHaveBeenCalledWith(7);
    expect(JSON.parse(result.content[0].text).columns).toEqual(['month', 'cnt']);
    expect(result.isError).toBeUndefined();
  });

  it('run_saved_query returns isError when query not found', async () => {
    (client.executeSavedQuery as ReturnType<typeof vi.fn>).mockRejectedValue(
      new Error('Query not found'),
    );

    const result = await invokeTool(server, 'run_saved_query', { queryId: 999 });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain('Query not found');
  });

  // --- get_data_schema ---
  it('get_data_schema calls apiClient.getDataSchema', async () => {
    const mockSchema = {
      tables: [
        {
          tableName: 'fire_incidents',
          datasetName: '화재 사고',
          datasetId: 1,
          columns: [
            { columnName: 'id', dataType: 'bigint', displayName: 'ID' },
            { columnName: 'incident_date', dataType: 'timestamp', displayName: '사고일시' },
          ],
        },
      ],
    };
    (client.getDataSchema as ReturnType<typeof vi.fn>).mockResolvedValue(mockSchema);

    const result = await invokeTool(server, 'get_data_schema', {});

    expect(client.getDataSchema).toHaveBeenCalled();
    const parsed = JSON.parse(result.content[0].text);
    expect(parsed.tables).toHaveLength(1);
    expect(parsed.tables[0].tableName).toBe('fire_incidents');
    expect(parsed.tables[0].columns).toHaveLength(2);
    expect(result.isError).toBeUndefined();
  });

  it('get_data_schema returns isError on failure', async () => {
    (client.getDataSchema as ReturnType<typeof vi.fn>).mockRejectedValue(
      new Error('Internal Server Error'),
    );

    const result = await invokeTool(server, 'get_data_schema', {});

    expect(result.isError).toBe(true);
  });

  // --- create_chart ---
  it('create_chart calls apiClient.createChart with correct args', async () => {
    const args = {
      name: '월별 화재 건수 막대차트',
      savedQueryId: 5,
      chartType: 'BAR' as const,
      config: { xAxis: 'month', yAxis: ['cnt'], groupBy: 'region', stacked: false },
      description: '월별 지역별 화재 건수',
      isShared: true,
    };
    const mockChart = {
      id: 10,
      ...args,
      savedQueryName: '월별 통계',
      createdBy: 1,
      createdByName: 'admin',
      createdAt: '2026-02-28T00:00:00Z',
      updatedAt: '2026-02-28T00:00:00Z',
    };
    (client.createChart as ReturnType<typeof vi.fn>).mockResolvedValue(mockChart);

    const result = await invokeTool(server, 'create_chart', args);

    expect(client.createChart).toHaveBeenCalledWith(args);
    const parsed = JSON.parse(result.content[0].text);
    expect(parsed.id).toBe(10);
    expect(parsed.chartType).toBe('BAR');
    expect(result.isError).toBeUndefined();
  });

  it('create_chart returns isError on failure', async () => {
    (client.createChart as ReturnType<typeof vi.fn>).mockRejectedValue(
      new Error('savedQueryId not found'),
    );

    const result = await invokeTool(server, 'create_chart', {
      name: '실패차트',
      savedQueryId: 999,
      chartType: 'LINE',
      config: { xAxis: 'date', yAxis: ['value'] },
    });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain('savedQueryId not found');
  });

  // --- list_charts ---
  it('list_charts calls apiClient.listCharts with no params', async () => {
    const mockList = {
      content: [{ id: 1, name: '차트1', chartType: 'BAR', savedQueryId: 2, savedQueryName: 'q1', isShared: false, createdByName: 'admin', createdAt: '2026-02-28T00:00:00Z', updatedAt: '2026-02-28T00:00:00Z', description: null }],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    };
    (client.listCharts as ReturnType<typeof vi.fn>).mockResolvedValue(mockList);

    const result = await invokeTool(server, 'list_charts', {});

    expect(client.listCharts).toHaveBeenCalledWith({});
    const parsed = JSON.parse(result.content[0].text);
    expect(parsed.totalElements).toBe(1);
    expect(result.isError).toBeUndefined();
  });

  it('list_charts passes search param', async () => {
    (client.listCharts as ReturnType<typeof vi.fn>).mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });

    await invokeTool(server, 'list_charts', { search: '화재' });

    expect(client.listCharts).toHaveBeenCalledWith({ search: '화재' });
  });

  it('list_charts returns isError on failure', async () => {
    (client.listCharts as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('Server Error'));

    const result = await invokeTool(server, 'list_charts', {});

    expect(result.isError).toBe(true);
  });

  // --- get_chart_data ---
  it('get_chart_data calls apiClient.getChartData with chartId', async () => {
    const mockChartData = {
      chart: {
        id: 3,
        name: '월별 통계',
        description: null,
        chartType: 'LINE',
        config: { xAxis: 'month', yAxis: ['cnt'] },
        savedQueryId: 5,
        savedQueryName: '월별 쿼리',
        isShared: false,
        createdBy: 1,
        createdByName: 'admin',
        createdAt: '2026-02-28T00:00:00Z',
        updatedAt: '2026-02-28T00:00:00Z',
      },
      queryResult: {
        queryType: 'SELECT',
        columns: ['month', 'cnt'],
        rows: [{ month: '2026-01', cnt: 42 }],
        affectedRows: 0,
        executionTimeMs: 30,
        totalRows: 1,
        truncated: false,
        error: null,
      },
    };
    (client.getChartData as ReturnType<typeof vi.fn>).mockResolvedValue(mockChartData);

    const result = await invokeTool(server, 'get_chart_data', { chartId: 3 });

    expect(client.getChartData).toHaveBeenCalledWith(3);
    const parsed = JSON.parse(result.content[0].text);
    expect(parsed.chart.id).toBe(3);
    expect(parsed.queryResult.totalRows).toBe(1);
    expect(result.isError).toBeUndefined();
  });

  it('get_chart_data returns isError when chart not found', async () => {
    (client.getChartData as ReturnType<typeof vi.fn>).mockRejectedValue(
      new Error('Chart not found'),
    );

    const result = await invokeTool(server, 'get_chart_data', { chartId: 999 });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain('Chart not found');
  });

  // --- create_dashboard ---
  it('create_dashboard calls apiClient.createDashboard with correct args', async () => {
    const args = { name: '주간 보고서', description: '주간 화재 통계', isShared: true, autoRefreshSeconds: 30 };
    const mockDashboard = {
      id: 1,
      ...args,
      createdBy: 1,
      createdByName: 'admin',
      createdAt: '2026-02-28T00:00:00Z',
      updatedAt: '2026-02-28T00:00:00Z',
    };
    (client.createDashboard as ReturnType<typeof vi.fn>).mockResolvedValue(mockDashboard);

    const result = await invokeTool(server, 'create_dashboard', args);

    expect(client.createDashboard).toHaveBeenCalledWith(args);
    const parsed = JSON.parse(result.content[0].text);
    expect(parsed.id).toBe(1);
    expect(parsed.name).toBe('주간 보고서');
    expect(result.isError).toBeUndefined();
  });

  it('create_dashboard works with name only', async () => {
    const mockDashboard = { id: 2, name: '간단 대시보드', description: null, isShared: false, autoRefreshSeconds: null, createdBy: 1, createdByName: 'admin', createdAt: '2026-02-28T00:00:00Z', updatedAt: '2026-02-28T00:00:00Z' };
    (client.createDashboard as ReturnType<typeof vi.fn>).mockResolvedValue(mockDashboard);

    await invokeTool(server, 'create_dashboard', { name: '간단 대시보드' });

    expect(client.createDashboard).toHaveBeenCalledWith({ name: '간단 대시보드' });
  });

  it('create_dashboard returns isError on failure', async () => {
    (client.createDashboard as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('name is required'));

    const result = await invokeTool(server, 'create_dashboard', { name: '' });

    expect(result.isError).toBe(true);
  });

  // --- add_chart_to_dashboard ---
  it('add_chart_to_dashboard calls apiClient.addDashboardWidget with defaults', async () => {
    const mockWidget = { id: 10, dashboardId: 1, chartId: 5, chartName: '차트A', positionX: 0, positionY: 0, width: 6, height: 4, createdAt: '2026-02-28T00:00:00Z', updatedAt: '2026-02-28T00:00:00Z' };
    (client.addDashboardWidget as ReturnType<typeof vi.fn>).mockResolvedValue(mockWidget);

    const result = await invokeTool(server, 'add_chart_to_dashboard', { dashboardId: 1, chartId: 5 });

    expect(client.addDashboardWidget).toHaveBeenCalledWith(1, { chartId: 5, positionX: 0, positionY: 0, width: 6, height: 4 });
    const parsed = JSON.parse(result.content[0].text);
    expect(parsed.id).toBe(10);
    expect(result.isError).toBeUndefined();
  });

  it('add_chart_to_dashboard uses provided position and size', async () => {
    const mockWidget = { id: 11, dashboardId: 1, chartId: 7, chartName: '차트B', positionX: 6, positionY: 4, width: 4, height: 3, createdAt: '2026-02-28T00:00:00Z', updatedAt: '2026-02-28T00:00:00Z' };
    (client.addDashboardWidget as ReturnType<typeof vi.fn>).mockResolvedValue(mockWidget);

    await invokeTool(server, 'add_chart_to_dashboard', { dashboardId: 1, chartId: 7, positionX: 6, positionY: 4, width: 4, height: 3 });

    expect(client.addDashboardWidget).toHaveBeenCalledWith(1, { chartId: 7, positionX: 6, positionY: 4, width: 4, height: 3 });
  });

  it('add_chart_to_dashboard returns isError when chart not found', async () => {
    (client.addDashboardWidget as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('Chart not found'));

    const result = await invokeTool(server, 'add_chart_to_dashboard', { dashboardId: 1, chartId: 999 });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain('Chart not found');
  });

  // --- list_dashboards ---
  it('list_dashboards calls apiClient.listDashboards with no params', async () => {
    const mockList = { content: [{ id: 1, name: '주간 보고서', description: null, isShared: false, autoRefreshSeconds: null, createdByName: 'admin', createdAt: '2026-02-28T00:00:00Z', updatedAt: '2026-02-28T00:00:00Z' }], totalElements: 1, totalPages: 1, number: 0, size: 20 };
    (client.listDashboards as ReturnType<typeof vi.fn>).mockResolvedValue(mockList);

    const result = await invokeTool(server, 'list_dashboards', {});

    expect(client.listDashboards).toHaveBeenCalledWith({});
    const parsed = JSON.parse(result.content[0].text);
    expect(parsed.totalElements).toBe(1);
    expect(result.isError).toBeUndefined();
  });

  it('list_dashboards passes search param', async () => {
    (client.listDashboards as ReturnType<typeof vi.fn>).mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });

    await invokeTool(server, 'list_dashboards', { search: '주간' });

    expect(client.listDashboards).toHaveBeenCalledWith({ search: '주간' });
  });

  it('list_dashboards returns isError on failure', async () => {
    (client.listDashboards as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('Server Error'));

    const result = await invokeTool(server, 'list_dashboards', {});

    expect(result.isError).toBe(true);
  });

  // --- show_chart ---
  describe('show_chart', () => {
    it('should validate and return displayed result', async () => {
      // show_chart는 API 호출 없음 — nock 불필요
      const result = await invokeTool(server, 'show_chart', {
        sql: 'SELECT name, revenue FROM data."sales" ORDER BY revenue DESC LIMIT 10',
        chartType: 'BAR',
        config: { xAxis: 'name', yAxis: ['revenue'] },
        columns: ['name', 'revenue'],
        rows: [
          { name: 'Product A', revenue: 1000 },
          { name: 'Product B', revenue: 800 },
        ],
      });
      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.displayed).toBe(true);
      expect(parsed.chartType).toBe('BAR');
      expect(parsed.rowCount).toBe(2);
    });

    it('should validate MAP chart with spatialColumn', async () => {
      const result = await invokeTool(server, 'show_chart', {
        sql: 'SELECT geom, name FROM data."locations"',
        chartType: 'MAP',
        config: { xAxis: '', yAxis: [], spatialColumn: 'geom' },
        columns: ['geom', 'name'],
        rows: [{ geom: '{"type":"Point","coordinates":[127,37]}', name: 'A' }],
      });
      expect(result.isError).toBeFalsy();
    });

    it('should accept new chart types (HISTOGRAM, BOXPLOT, TREEMAP, FUNNEL, RADAR, WATERFALL, GAUGE, CANDLESTICK, HEATMAP)', async () => {
      // 9종 신규 차트 타입이 Zod 검증을 통과하는지 확인
      const newTypes = ['HISTOGRAM', 'BOXPLOT', 'HEATMAP', 'TREEMAP', 'FUNNEL', 'RADAR', 'WATERFALL', 'GAUGE', 'CANDLESTICK'] as const;
      for (const chartType of newTypes) {
        const result = await invokeTool(server, 'show_chart', {
          sql: 'SELECT x, y FROM data."test"',
          chartType,
          config: { xAxis: 'x', yAxis: ['y'] },
          columns: ['x', 'y'],
          rows: [{ x: 'A', y: 100 }],
        });
        expect(result.isError, `chartType=${chartType} should not be an error`).toBeFalsy();
      }
    });

    it('should accept optional title and echo it in result (이슈 #203)', async () => {
      // title은 프론트엔드 인라인 차트 헤더에 표시되는 분석 제목.
      // 미전달 시 폴백, 전달 시 결과에 포함되어 클라이언트가 렌더링 시 사용한다.
      const result = await invokeTool(server, 'show_chart', {
        sql: 'SELECT category, count FROM data."incidents" GROUP BY category',
        title: '출동 유형별 비율',
        chartType: 'PIE',
        config: { xAxis: 'category', yAxis: ['count'] },
        columns: ['category', 'count'],
        rows: [
          { category: '화재', count: 10 },
          { category: '구조', count: 5 },
        ],
      });
      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.displayed).toBe(true);
      expect(parsed.title).toBe('출동 유형별 비율');
    });

    it('should accept show_chart without title (폴백 동작 — 기존 호출 호환)', async () => {
      const result = await invokeTool(server, 'show_chart', {
        sql: 'SELECT 1',
        chartType: 'BAR',
        config: { xAxis: 'a', yAxis: ['b'] },
        columns: ['a', 'b'],
        rows: [{ a: 'x', b: 1 }],
      });
      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.title).toBeUndefined();
    });

    it('should reject invalid chartType via Zod validation', async () => {
      const result = await invokeTool(server, 'show_chart', {
        sql: 'SELECT 1',
        chartType: 'INVALID_TYPE',
        config: { xAxis: 'x', yAxis: ['y'] },
        columns: ['x', 'y'],
        rows: [],
      });
      expect(result.isError).toBe(true);
    });
  });

  // --- tool registration ---
  it('all 12 analytics tools are registered in the MCP server', () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const registeredTools = Object.keys((server.instance as any)._registeredTools);
    expect(registeredTools).toContain('execute_analytics_query');
    expect(registeredTools).toContain('create_saved_query');
    expect(registeredTools).toContain('list_saved_queries');
    expect(registeredTools).toContain('run_saved_query');
    expect(registeredTools).toContain('get_data_schema');
    expect(registeredTools).toContain('create_chart');
    expect(registeredTools).toContain('list_charts');
    expect(registeredTools).toContain('get_chart_data');
    expect(registeredTools).toContain('create_dashboard');
    expect(registeredTools).toContain('add_chart_to_dashboard');
    expect(registeredTools).toContain('list_dashboards');
    expect(registeredTools).toContain('show_chart');
  });
});
