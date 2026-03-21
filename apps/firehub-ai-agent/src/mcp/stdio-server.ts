/**
 * MCP stdio server for Claude Code CLI (`claude -p --mcp-config`).
 *
 * Registers all FireHub tools using @modelcontextprotocol/sdk McpServer,
 * then connects over stdio so the CLI can call them.
 *
 * Usage (standalone):
 *   API_BASE_URL=... INTERNAL_SERVICE_TOKEN=... USER_ID=... node dist/mcp/stdio-server.js
 */
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { FireHubApiClient } from './api-client.js';
import { MCP_SERVER_NAME, MCP_SERVER_VERSION } from '../constants.js';
import type { SafeToolFn, JsonResultFn } from './firehub-mcp-server.js';
import { registerCategoryTools } from './tools/category-tools.js';
import { registerDatasetTools } from './tools/dataset-tools.js';
import { registerDataTools } from './tools/data-tools.js';
import { registerPipelineTools } from './tools/pipeline-tools.js';
import { registerTriggerTools } from './tools/trigger-tools.js';
import { registerApiConnectionTools } from './tools/api-connection-tools.js';
import { registerMiscTools } from './tools/misc-tools.js';
import { registerAnalyticsTools } from './tools/analytics-tools.js';
import type { AnyZodRawShape, InferShape } from '@anthropic-ai/claude-agent-sdk';

type ToolResult = { content: Array<{ type: 'text'; text: string }>; isError?: boolean };

/**
 * Creates a safeTool function that registers tools on an McpServer instance.
 * The return type is cast to SafeToolFn so existing register*Tools() functions
 * (which are typed against the SDK's safeTool signature) work without modification.
 */
function createMcpSafeTool(server: McpServer): SafeToolFn {
  return function safeTool<Schema extends AnyZodRawShape>(
    name: string,
    description: string,
    schema: Schema,
    handler: (args: InferShape<Schema>) => Promise<ToolResult>,
  ) {
    server.tool(
      name,
      description,
      // Cast: AnyZodRawShape (Zod v4) is compatible with ZodRawShapeCompat (MCP SDK)
      schema as Record<string, never>,
      async (args: Record<string, unknown>) => {
        try {
          const result = await handler(args as InferShape<Schema>);
          return {
            content: result.content,
            isError: result.isError,
          };
        } catch (error) {
          const message = error instanceof Error ? error.message : String(error);
          console.error(`[MCP Stdio Tool] ${name} failed: ${message}`);
          return {
            content: [{ type: 'text' as const, text: message }],
            isError: true,
          };
        }
      },
    );
    // Return value is unused by register*Tools() callers; return placeholder
    return undefined as unknown as ReturnType<SafeToolFn>;
  } as SafeToolFn;
}

const jsonResult: JsonResultFn = (data: unknown): ToolResult => ({
  content: [{ type: 'text', text: JSON.stringify(data, null, 2) }],
});

async function main(): Promise<void> {
  const apiBaseUrl = process.env.API_BASE_URL ?? 'http://localhost:8080/api/v1';
  const internalToken = process.env.INTERNAL_SERVICE_TOKEN ?? '';
  const userIdRaw = process.env.USER_ID;

  if (!userIdRaw) {
    console.error('[MCP Stdio] USER_ID environment variable is required');
    process.exit(1);
  }

  const userId = Number(userIdRaw);
  if (isNaN(userId)) {
    console.error('[MCP Stdio] USER_ID must be a valid number');
    process.exit(1);
  }

  const apiClient = new FireHubApiClient(apiBaseUrl, internalToken, userId);

  const server = new McpServer({
    name: MCP_SERVER_NAME,
    version: MCP_SERVER_VERSION,
  });

  const safeTool = createMcpSafeTool(server);

  // Register all FireHub tools
  registerCategoryTools(apiClient, safeTool, jsonResult);
  registerDatasetTools(apiClient, safeTool, jsonResult);
  registerDataTools(apiClient, safeTool, jsonResult);
  registerPipelineTools(apiClient, safeTool, jsonResult);
  registerTriggerTools(apiClient, safeTool, jsonResult);
  registerApiConnectionTools(apiClient, safeTool, jsonResult);
  registerMiscTools(apiClient, safeTool, jsonResult);
  registerAnalyticsTools(apiClient, safeTool, jsonResult);

  const transport = new StdioServerTransport();
  await server.connect(transport);

  console.error(`[MCP Stdio] FireHub MCP server running (userId=${userId})`);
}

main().catch((err) => {
  console.error('[MCP Stdio] Fatal error:', err);
  process.exit(1);
});
