import { isValidId } from '../../../lib/route-id';

/** invalidateQueries 에 넘길 쿼리 키 하나 — 작업 id 같은 숫자 조각도 담는다 */
type InvalidationKey = (string | number)[];
/** 도구 입력(tool_use input)을 받아 키를 만드는 항목 — 입력의 id 로 특정 작업의 캐시만 고를 때 쓴다 */
type InvalidationEntry = InvalidationKey[] | ((input: Record<string, unknown> | undefined) => InvalidationKey[]);

// MCP 도구 실행 후 invalidate할 TanStack Query 키 매핑
// 키: mcp__firehub__ 접두사 제거한 도구 이름
// 값: invalidateQueries에 전달할 queryKey 배열들, 또는 도구 입력으로 그 배열을 만드는 함수
const TOOL_INVALIDATION_MAP: Record<string, InvalidationEntry> = {
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
  // 채팅에서 실행해도 실행 이력 탭이 바로 「실행 중」을 보이고 폴링을 시작하게 그 작업의 실행 목록도 무효화한다(WD-667).
  // 화면의 「지금 실행」과 같이 목록 접두사만 — 단건 실행 캐시는 다시 읽지 않는다. id 를 모르면 모든 실행 캐시로 넓힌다.
  execute_proactive_job: (input) => {
    const id = typeof input?.id === 'number' ? input.id : null;
    return [['proactive', 'jobs'], isValidId(id) ? ['proactive', 'executions', id, 'list'] : ['proactive', 'executions']];
  },
  create_report_template: [['proactive', 'templates']],
};

/** 도구 이름(과 입력)으로 무효화할 쿼리 키들을 돌려준다. 입력은 함수형 항목만 쓴다. */
export function getInvalidationKeys(toolName: string, input?: Record<string, unknown>): InvalidationKey[] {
  const cleanName = toolName.replace(/^mcp__firehub__/, '');
  const entry = TOOL_INVALIDATION_MAP[cleanName];
  if (!entry) return [];
  return typeof entry === 'function' ? entry(input) : entry;
}
