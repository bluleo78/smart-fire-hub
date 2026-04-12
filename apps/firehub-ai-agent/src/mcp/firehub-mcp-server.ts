import { createSdkMcpServer, tool } from '@anthropic-ai/claude-agent-sdk';
import type {
  McpSdkServerConfigWithInstance,
  AnyZodRawShape,
  InferShape,
} from '@anthropic-ai/claude-agent-sdk';
import { FireHubApiClient } from './api-client.js';
import { MCP_SERVER_NAME, MCP_SERVER_VERSION } from '../constants.js';
import { registerCategoryTools } from './tools/category-tools.js';
import { registerDatasetTools } from './tools/dataset-tools.js';
import { registerDataTools } from './tools/data-tools.js';
import { registerPipelineTools } from './tools/pipeline-tools.js';
import { registerTriggerTools } from './tools/trigger-tools.js';
import { registerApiConnectionTools } from './tools/api-connection-tools.js';
import { registerMiscTools } from './tools/misc-tools.js';
import { registerAnalyticsTools } from './tools/analytics-tools.js';
import { registerUiTools } from './tools/ui-tools.js';
import { registerProactiveTools } from './tools/proactive-tools.js';
import { registerDataImportTools } from './tools/dataimport-tools.js';
import { registerAdminTools } from './tools/admin-tools.js';

type ToolResult = { content: Array<{ type: 'text'; text: string }>; isError?: boolean };

export function safeTool<Schema extends AnyZodRawShape>(
  name: string,
  description: string,
  schema: Schema,
  handler: (args: InferShape<Schema>) => Promise<ToolResult>,
) {
  return tool(name, description, schema, async (args: InferShape<Schema>): Promise<ToolResult> => {
    try {
      return await handler(args);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      console.error(`[MCP Tool] ${name} failed: ${message}`);
      return { content: [{ type: 'text', text: message }], isError: true };
    }
  });
}

export function jsonResult(data: unknown): ToolResult {
  return { content: [{ type: 'text', text: JSON.stringify(data, null, 2) }] };
}

export type SafeToolFn = typeof safeTool;
export type JsonResultFn = typeof jsonResult;

/**
 * 권한 코드 상수. raw 문자열 리터럴 대신 이 상수를 참조해 오타·리팩토링 누락을 방지한다.
 */
const PERMISSIONS = {
  DATASET_DELETE: 'dataset:delete',
  USER_READ: 'user:read',
  USER_WRITE: 'user:write',
  ROLE_READ: 'role:read',
  ROLE_ASSIGN: 'role:assign',
  PERMISSION_READ: 'permission:read',
} as const;

/**
 * 도구별 필수 권한 맵.
 * 해당 권한이 세션 사용자에게 없으면 도구가 빌드 결과에서 제외되고,
 * 결과적으로 Claude Agent SDK의 allowedTools 목록에도 포함되지 않아
 * 에이전트가 도구 존재 자체를 인지하지 못한다 (가장 강한 게이팅).
 *
 * 파괴적 도구만 등록하며, 맵에 없는 도구는 기본 허용이다.
 */
const TOOL_PERMISSION_REQUIREMENTS: Record<string, string> = {
  delete_dataset: PERMISSIONS.DATASET_DELETE,
  drop_dataset_column: PERMISSIONS.DATASET_DELETE,
  list_users: PERMISSIONS.USER_READ,
  get_user: PERMISSIONS.USER_READ,
  set_user_roles: PERMISSIONS.ROLE_ASSIGN,
  set_user_active: PERMISSIONS.USER_WRITE,
  list_roles: PERMISSIONS.ROLE_READ,
  list_permissions: PERMISSIONS.PERMISSION_READ,
};

/**
 * 사용자 권한 목록에 따라 도구 배열을 필터링한다.
 *
 * 권한 매개변수의 3가지 상태 해석:
 * - `undefined`: 권한 정보 자체가 전달되지 않음 → 후방호환을 위해 전부 허용(permissive).
 *   기존에 권한 주입 로직이 없는 호출 경로에서 안전하게 동작하도록 한다.
 * - `[]` (빈 배열): 권한 조회는 수행되었으나 사용자에게 권한이 전혀 없음 → fail-closed.
 *   파괴적 도구는 제외된다. 권한 조회 실패 시에도 빈 배열을 사용해 안전 측면으로 동작한다.
 * - 비어있지 않은 배열: 해당 권한을 가진 도구만 허용.
 */
export function filterToolsByPermissions<T extends { name: string }>(
  tools: T[],
  userPermissions: string[] | undefined,
): T[] {
  // 후방호환: 권한 정보가 전혀 제공되지 않으면 기존 동작을 유지한다.
  if (userPermissions === undefined) return tools;
  return tools.filter((tool) => {
    const required = TOOL_PERMISSION_REQUIREMENTS[tool.name];
    if (!required) return true; // 권한 요구가 없는 도구는 항상 허용
    return userPermissions.includes(required);
  });
}

/** registerAllTools/buildAllMcpTools의 공통 옵션 */
export interface BuildToolsOptions {
  /**
   * 세션 사용자의 권한 목록. `undefined`면 모든 도구 허용(후방호환),
   * `[]`면 권한 요구가 있는 도구는 전부 제외(fail-closed).
   */
  userPermissions?: string[];
}

/**
 * 모든 FireHub MCP 도구를 등록합니다.
 * firehub-mcp-server (SDK 모드)와 stdio-server (CLI 모드) 양쪽에서 공통 사용.
 *
 * 권한 기반 필터링을 적용하여, 호출한 사용자가 권한을 갖지 못한 파괴적 도구는
 * 반환 배열에서 제외된다. 부수효과(각 register* 함수의 배열 반환)는 모두 수행되지만,
 * 최종 합쳐진 배열에 필터가 적용된 결과를 반환한다.
 */
export function registerAllTools(
  apiClient: FireHubApiClient,
  safeToolFn: SafeToolFn,
  jsonResultFn: JsonResultFn,
  options: BuildToolsOptions = {},
) {
  const allTools = [
    ...registerCategoryTools(apiClient, safeToolFn, jsonResultFn),
    ...registerDatasetTools(apiClient, safeToolFn, jsonResultFn),
    ...registerDataImportTools(apiClient, safeToolFn, jsonResultFn),
    ...registerDataTools(apiClient, safeToolFn, jsonResultFn),
    ...registerPipelineTools(apiClient, safeToolFn, jsonResultFn),
    ...registerTriggerTools(apiClient, safeToolFn, jsonResultFn),
    ...registerApiConnectionTools(apiClient, safeToolFn, jsonResultFn),
    ...registerMiscTools(apiClient, safeToolFn, jsonResultFn),
    ...registerAnalyticsTools(apiClient, safeToolFn, jsonResultFn),
    ...registerUiTools(safeToolFn, jsonResultFn),
    ...registerProactiveTools(apiClient, safeToolFn, jsonResultFn),
    ...registerAdminTools(apiClient, safeToolFn, jsonResultFn),
  ];
  return filterToolsByPermissions(allTools, options.userPermissions);
}

/**
 * buildAllMcpTools: createSdkMcpServer에 전달할 도구 배열을 생성한다.
 * registerAllTools를 기본 safeTool/jsonResult로 래핑한 편의 함수.
 */
export function buildAllMcpTools(
  apiClient: FireHubApiClient,
  options: BuildToolsOptions = {},
) {
  return registerAllTools(apiClient, safeTool, jsonResult, options);
}

export function createFireHubMcpServer(
  apiClient: FireHubApiClient,
  options: BuildToolsOptions = {},
): McpSdkServerConfigWithInstance {
  return createSdkMcpServer({
    name: MCP_SERVER_NAME,
    version: MCP_SERVER_VERSION,
    tools: buildAllMcpTools(apiClient, options),
  });
}
