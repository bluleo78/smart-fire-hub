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
import { registerAllTools } from './firehub-mcp-server.js';
import type { AnyZodRawShape, InferShape } from '@anthropic-ai/claude-agent-sdk';
import { createTracker, FAILURE_WARN_HINT, type FailureTracker } from '../agent/failure-streak.js';
import { isOpenCodeSchemaCompat, sanitizeOutgoingMessage } from './schema-compat.js';

type ToolResult = { content: Array<{ type: 'text'; text: string }>; isError?: boolean };

/**
 * createMcpSafeTool: McpServer.tool() 시그니처에 맞춘 safeTool + Tier1 경고 주입.
 * 핸들러를 try/catch로 감싸고(기존 동작 유지), 연속 실패 트래커에 기록하여
 * 임계(WARN_AT)에 도달한 오류 결과엔 경고 힌트를 1회 덧붙인다.
 */
function createMcpSafeTool(server: McpServer, tracker: FailureTracker): SafeToolFn {
  return function safeTool<Schema extends AnyZodRawShape>(
    name: string,
    description: string,
    schema: Schema,
    handler: (args: InferShape<Schema>) => Promise<ToolResult>,
  ) {
    server.tool(
      name,
      description,
      // Cast: AnyZodRawShape (Zod v4) 은 MCP SDK ZodRawShapeCompat 와 호환
      schema as Record<string, never>,
      async (args: Record<string, unknown>) => {
        let result: ToolResult;
        try {
          result = await handler(args as InferShape<Schema>);
        } catch (error) {
          const message = error instanceof Error ? error.message : String(error);
          console.error(`[MCP Stdio Tool] ${name} failed: ${message}`);
          result = { content: [{ type: 'text', text: message }], isError: true };
        }
        const text = result.content.map((c) => c.text).join('');
        const { warn } = tracker.record(name, text, result.isError ?? false);
        const finalContent = warn
          ? [...result.content, { type: 'text' as const, text: FAILURE_WARN_HINT }]
          : result.content;
        return { content: finalContent, isError: result.isError };
      },
    );
    // 반환값은 register*Tools() 호출부에서 사용되지 않음 — placeholder 반환
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

  // 연속 실패 트래커 생성 후 safeTool 래퍼에 주입 (Tier1 경고 주입용)
  const tracker = createTracker();
  const safeTool = createMcpSafeTool(server, tracker);

  // Register all FireHub tools (공통 함수 사용)
  registerAllTools(apiClient, safeTool, jsonResult);

  const transport = new StdioServerTransport();

  // OpenCode 경로: tools/list 응답에서 게이트웨이가 거부하는 `propertyNames` 를 제거한다.
  // transport.send 를 래핑해 나가는 메시지를 정제(Anthropic 경로엔 미적용 — env 게이트).
  if (isOpenCodeSchemaCompat()) {
    const originalSend = transport.send.bind(transport);
    transport.send = async (message: Parameters<typeof originalSend>[0]) => {
      sanitizeOutgoingMessage(message);
      return originalSend(message);
    };
  }

  await server.connect(transport);

  console.error(
    `[MCP Stdio] FireHub MCP server running (userId=${userId}${isOpenCodeSchemaCompat() ? ', opencode-schema-compat' : ''})`,
  );
}

main().catch((err) => {
  console.error('[MCP Stdio] Fatal error:', err);
  process.exit(1);
});
