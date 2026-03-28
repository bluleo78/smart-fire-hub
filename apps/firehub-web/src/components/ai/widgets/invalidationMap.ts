// MCP 도구 실행 후 invalidate할 TanStack Query 키 매핑
// 키: mcp__firehub__ 접두사 제거한 도구 이름
// 값: invalidateQueries에 전달할 queryKey 배열들
const TOOL_INVALIDATION_MAP: Record<string, string[][]> = {
  create_dataset: [['datasets']],
  update_dataset: [['datasets']],
  delete_dataset: [['datasets']],
  truncate_dataset: [['datasets']],
  add_row: [['datasets']],
  add_rows: [['datasets']],
  update_row: [['datasets']],
  delete_rows: [['datasets']],
  replace_dataset_data: [['datasets']],
  create_pipeline: [['pipelines']],
  update_pipeline: [['pipelines']],
  delete_pipeline: [['pipelines']],
  execute_pipeline: [['pipelines']],
  create_trigger: [['pipelines']],
  update_trigger: [['pipelines']],
  delete_trigger: [['pipelines']],
  create_chart: [['charts'], ['dashboards']],
  create_dashboard: [['dashboards']],
  add_chart_to_dashboard: [['dashboards']],
  create_category: [['categories']],
  update_category: [['categories']],
  create_api_connection: [['api-connections']],
  update_api_connection: [['api-connections']],
  delete_api_connection: [['api-connections']],
  create_proactive_job: [['proactive', 'jobs']],
  update_proactive_job: [['proactive', 'jobs']],
  delete_proactive_job: [['proactive', 'jobs']],
  execute_proactive_job: [['proactive', 'jobs']],
  create_report_template: [['proactive', 'templates']],
};

export function getInvalidationKeys(toolName: string): string[][] {
  const cleanName = toolName.replace(/^mcp__firehub__/, '');
  return TOOL_INVALIDATION_MAP[cleanName] || [];
}
