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

export function createFireHubMcpServer(
  apiClient: FireHubApiClient,
): McpSdkServerConfigWithInstance {
  return createSdkMcpServer({
    name: MCP_SERVER_NAME,
    version: MCP_SERVER_VERSION,
    tools: [
      ...registerCategoryTools(apiClient, safeTool, jsonResult),
      ...registerDatasetTools(apiClient, safeTool, jsonResult),
      ...registerDataTools(apiClient, safeTool, jsonResult),
      ...registerPipelineTools(apiClient, safeTool, jsonResult),
      ...registerTriggerTools(apiClient, safeTool, jsonResult),
      ...registerApiConnectionTools(apiClient, safeTool, jsonResult),
      ...registerMiscTools(apiClient, safeTool, jsonResult),
      ...registerAnalyticsTools(apiClient, safeTool, jsonResult),
    ],
  });
}
