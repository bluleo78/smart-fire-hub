/**
 * MCP stdio server for Claude Code CLI (`claude -p --mcp-config`).
 *
 * Registers all FireHub tools using @modelcontextprotocol/sdk McpServer,
 * then connects over stdio so the CLI can call them.
 *
 * Usage (standalone):
 *   API_BASE_URL=... INTERNAL_SERVICE_TOKEN=... USER_ID=... TENANT_ID=... node dist/mcp/stdio-server.js
 */
import { realpathSync } from 'node:fs';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { basename } from 'node:path';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { FireHubApiClient } from './api-client.js';
import { MCP_SERVER_NAME, MCP_SERVER_VERSION } from '../constants.js';
import { isValidTenantId } from '../agent/tenant-paths.js';
import type { SafeToolFn, JsonResultFn } from './firehub-mcp-server.js';
import { registerAllTools } from './firehub-mcp-server.js';
import { resolveStdioCredentials } from './stdio-credentials.js';
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

/**
 * 프로세스 단위 식별자 환경변수를 읽는다. 없거나 양수 정수가 아니면 기동을 멈춘다.
 *
 * <p>술어는 경로 판정의 권위인 isValidTenantId 를 재사용한다 — 예전의 `isNaN` 단독 검사는
 * `0`·음수·소수를 통과시켜, 그 값이 나중에 헤더 누락으로 조용히 바뀌었다(복제된 술어가 갈린 사례).
 */
function requireIdEnv(name: 'USER_ID' | 'TENANT_ID'): number {
  const value = Number(process.env[name]);
  if (!isValidTenantId(value)) {
    console.error(`[MCP Stdio] ${name} environment variable is required (positive integer)`);
    process.exit(1);
  }
  return value;
}

/**
 * `main()` 이 실제로 이 파일에서 기동되는지(스크립트 직접 실행) 판별한다.
 *
 * <p>왜 필요한가(리뷰 라운드 1 지적, Ruling #30 재발): 이 함수가 없던 이전 버전은 파일 하단에서
 * `main().catch(...)` 를 무조건 실행했다 — 그러면 이 파일을 **import 하기만 해도** MCP 서버
 * 기동이 시작되므로, `registerAllTools(...)` 를 실제로 호출하는 지점(아래 `main()` 본문)을 단위
 * 테스트가 직접 통과시킬 수 없었다. 그 결과 `resolveStdioCredentials()` 자체는 순수 함수라
 * 테스트로 고정됐지만, main() 안에서 그 결과를 `registerAllTools` 로 넘기는 배선(그 "hop")은
 * 어떤 테스트도 지나가지 않았다 — `credentials: { apiKey: process.env.ANTHROPIC_API_KEY, ... }`
 * (옛 ambient 유출 코드)로 되돌려도 전체 스위트가 그대로 GREEN 이었다. 함수가 옳다는 것은
 * 프로그램이 옳다는 것을 증명하지 않는다.
 *
 * <p><b>단순 문자열 비교로는 부족하다(보안 리뷰 Fix6, 이 가드 자체가 만든 회귀).</b> 이전 구현
 * (`import.meta.url === \`file://${'$'}{process.argv[1]}\``)은 두 가지를 놓친다.
 *
 * <ul>
 *   <li>Node 는 ESM 엔트리를 로드할 때 {@code import.meta.url} 을 <b>realpath(심링크 해석)</b>
 *       한 경로로 채운다 — 반면 {@code process.argv[1]} 은 명령행에 그대로 받은 문자열이다.
 *       {@link getStdioServerCommand}(`stdio-server-command.ts`)가 opencode 자식에게 넘기는
 *       경로에 심링크 구간이 하나라도 있으면(pnpm 의 {@code node_modules/.bin/tsx} 처럼 심링크가
 *       흔한 배포 구성에서 실제로 있을 수 있다) 두 문자열이 절대 같아지지 않는다.
 *   <li>{@code file://} 접두만 붙이는 수동 조립은 퍼센트 인코딩을 하지 않는다 — 경로에 공백 등
 *       URL 에서 인코딩돼야 하는 문자가 있으면 {@code import.meta.url}(자동 인코딩됨)과 절대
 *       바이트 단위로 같아지지 않는다.
 * </ul>
 *
 * <p>이 조건이 거짓으로 잘못 판정되면(실제로는 메인 스크립트로 실행됐는데), {@code main()} 이
 * 아예 안 불려 <b>opencode/CLI 채팅이 firehub MCP 도구 36개를 통째로 조용히 잃는다</b> — 어떤
 * 에러도 안 남긴다(브랜치 이전엔 이 가드 자체가 없어 항상 기동했다 — 이 가드가 테스트 가능성을
 * 위해 추가되면서 생긴 회귀다). 그래서 {@link realpathSync}/{@link pathToFileURL} 로 양쪽을
 * 정규화해 비교하고(심링크·퍼센트 인코딩 모두 흡수), 호출부(파일 하단)는 이 함수가 false 를
 * 돌려준 경우 중 <b>실제로 기동을 기대했을 법한 경우만</b> 골라 이유를 stderr 에 남긴다
 * ({@link shouldWarnNotMain} 참고).
 *
 * @param argv1 실행된 스크립트 경로(보통 {@code process.argv[1]}) — 없으면(undefined) 무조건 false.
 * @param metaUrl 이 모듈 자신의 {@code import.meta.url}.
 */
export function isMainModule(argv1: string | undefined, metaUrl: string): boolean {
  if (!argv1) return false;
  try {
    return pathToFileURL(realpathSync(argv1)).href === metaUrl;
  } catch {
    // realpathSync 는 argv1 이 존재하지 않는 경로면 던진다 — 판별 불가는 "메인이 아니다"로
    // 안전하게 떨어뜨린다(fail-closed: 잘못 기동해 두 번 뜨는 것보다 안 뜨는 쪽이 덜 위험하고,
    // 아래 호출부가 어느 쪽이든 이유를 남긴다).
    return false;
  }
}

/**
 * {@link isMainModule} 이 false 일 때 <b>경고를 남겨야 하는가</b>를 판정한다(재검토 N8).
 *
 * <p><b>왜 필요한가.</b> 이전 구현은 else 분기에서 무조건 stderr 에 한 문단을 찍었다 — 이
 * 모듈을 {@code import} 하는 <b>모든</b> 테스트/도구 실행에서 매번 나오는 상시 노이즈였고,
 * 정작 진짜 사고(스크립트로 스폰됐는데 경로 정규화가 어긋나 {@code main()} 이 안 불린 경우)가
 * 그 잡음에 묻힌다.
 *
 * <p><b>판정 기준.</b> 실행된 스크립트의 파일명이 이 모듈 자신의 파일명과 같으면 "이 파일을
 * 스크립트로 띄우려 했는데 실패한 것"이다 — 그때만 경고한다. 확장자는 무시한다
 * ({@code stdio-server.ts} 를 tsx 로 띄우는 개발 실행과 {@code stdio-server.js} 를 node 로
 * 띄우는 배포 실행이 같은 사고를 낸다). 파일명이 다르면(vitest, 다른 엔트리 등) 정상적인
 * import 이므로 조용히 넘어간다.
 *
 * @param argv1 실행된 스크립트 경로({@code process.argv[1]}) — 없으면 경고하지 않는다.
 * @param metaUrl 이 모듈 자신의 {@code import.meta.url}.
 */
export function shouldWarnNotMain(argv1: string | undefined, metaUrl: string): boolean {
  if (!argv1) return false;
  try {
    const stripExt = (name: string) => name.replace(/\.[^.]+$/, '');
    return stripExt(basename(argv1)) === stripExt(basename(fileURLToPath(metaUrl)));
  } catch {
    // metaUrl 이 file: URL 이 아니면 판정할 수 없다 — 노이즈를 내지 않는 쪽으로 떨어뜨린다.
    return false;
  }
}

async function main(): Promise<void> {
  const apiBaseUrl = process.env.API_BASE_URL ?? 'http://localhost:8080/api/v1';
  const internalToken = process.env.INTERNAL_SERVICE_TOKEN ?? '';
  // USER_ID 와 TENANT_ID 는 둘 다 필수다. TENANT_ID 를 빠뜨리면 api 가 멤버십 역추론 폴백을
  // 타는데(동작은 FireHubApiClient 생성자 주석 참고) 그 실패는 멀티 워크스페이스 사용자에게서만
  // 터져 재현이 어렵다 — 주입 누락은 기동 시점에 드러낸다.
  const userId = requireIdEnv('USER_ID');
  const tenantId = requireIdEnv('TENANT_ID');

  const apiClient = new FireHubApiClient(apiBaseUrl, internalToken, userId, tenantId);

  const server = new McpServer({
    name: MCP_SERVER_NAME,
    version: MCP_SERVER_VERSION,
  });

  // 연속 실패 트래커 생성 후 safeTool 래퍼에 주입 (Tier1 경고 주입용)
  const tracker = createTracker();
  const safeTool = createMcpSafeTool(server, tracker);

  // Register all FireHub tools (공통 함수 사용)
  // GraphRAG 도구는 LLM completion 을 호출하므로 자격증명이 필요하다 — resolveStdioCredentials
  // 가 opencode/CLI 두 경로를 가른다(위 함수 docstring 참고). 이 프로세스 env 는 부모가 명시적으로
  // 심어 준 값이라 ambient 가 아니다. 자격증명이 비어 있으면 GraphRAG 호출 시점에 명확히 실패한다
  // (#708 — 환경/키체인 폴백 없음).
  registerAllTools(apiClient, safeTool, jsonResult, {
    credentials: resolveStdioCredentials(process.env),
  });

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

export { main };

// 스크립트로 직접 실행될 때만 기동한다 — 테스트가 이 모듈을 import 해도 서버가 뜨지 않아야
// main() 본문(registerAllTools 로의 자격증명 배선 포함)을 직접 호출해 검증할 수 있다.
if (isMainModule(process.argv[1], import.meta.url)) {
  main().catch((err) => {
    console.error('[MCP Stdio] Fatal error:', err);
    process.exit(1);
  });
} else if (shouldWarnNotMain(process.argv[1], import.meta.url)) {
  // 보안 리뷰 Fix6 — 이 분기를 조용히 넘기지 않는다. 실제로 opencode/CLI 가 이 파일을
  // 스크립트로 스폰했는데(argv[1] 이 이 파일) 여기로 떨어졌다면 심링크/공백 등으로
  // isMainModule() 판정이 어긋난 것이다 — main() 이 안 불려 firehub MCP 도구가 조용히
  // 사라지는 바로 그 사고다. 두 값을 그대로 stderr 에 남겨 원인을 바로 알 수 있게 한다.
  //
  // 재검토 N8 — 단, 단순 import(테스트·다른 엔트리)에서는 찍지 않는다. 상시 노이즈가 되면
  // 정작 진짜 사고가 그 잡음에 묻힌다(shouldWarnNotMain 참고).
  console.error(
    `[MCP Stdio] isMainModule() 이 false 를 반환해 기동하지 않았습니다` +
      `(argv[1]=${process.argv[1] ?? '(undefined)'}, import.meta.url=${import.meta.url}).` +
      ` 이 파일을 스크립트로 띄우려 한 실행인데 경로 정규화가 어긋났습니다 —` +
      ` 심링크나 공백이 섞인 경로가 원인일 수 있습니다.`,
  );
}
