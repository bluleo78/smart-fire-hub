// stdio-server.ts(MCP 자식 프로세스)의 GraphRAG completion 자격증명 해석.
//
// stdio-server.ts 자체는 import 되는 순간 main() 을 실행하는 스크립트라(node dist/mcp/
// stdio-server.js 직접 실행 전제, 가드 없음) 단위 테스트가 그 파일을 직접 import 할 수 없다.
// 이 함수만 부작용 없는 별도 파일로 뽑아 stdio-server.ts 가 가져다 쓰게 한다.
import type { BuildToolsOptions } from './firehub-mcp-server.js';
import { OPENCODE_MCP_CREDENTIAL_ENV } from './opencode-mcp-credential-env.js';

/**
 * GraphRAG 도구(registerAllTools 가 등록)가 쓸 LLM completion 자격증명을 이 프로세스의 env 에서
 * 뽑는다.
 *
 * <p>두 경로가 있다.
 * - **opencode 경로**: `AI_CREDENTIAL_AGENT_TYPE=opencode` 가 있으면 이 MCP 자식은 opencode
 *   CLI 가 spawn 한 것이다(agent-opencode.ts 의 buildOpenCodeConfig 가 mcp.firehub.environment
 *   에 `AI_CREDENTIAL_*` 키로 테넌트의 opencode provider 자격증명을 심는다). 이때는
 *   `ANTHROPIC_API_KEY`/`CLAUDE_CODE_OAUTH_TOKEN` 을 **절대 읽지 않는다** — 읽으면 opencode
 *   세션 안의 GraphRAG 호출이 컨테이너의 ambient Anthropic 자격증명으로 새어 플랫폼 계정에
 *   과금되는 6b1c6383 과 같은 모양의 회귀가 이 경로에만 재발한다(Ruling #30).
 * - **CLI(claude) 경로**: 위 키가 없으면 이 프로세스는 agent-cli.ts 의 buildMcpConfig 가 spawn
 *   한 것이고, 그쪽이 이미 이 프로세스 env 에 `ANTHROPIC_API_KEY`/`CLAUDE_CODE_OAUTH_TOKEN` 을
 *   실어 보낸다(기존 동작 그대로 유지).
 */
export function resolveStdioCredentials(
  env: NodeJS.ProcessEnv,
): NonNullable<BuildToolsOptions['credentials']> {
  if (env[OPENCODE_MCP_CREDENTIAL_ENV.AGENT_TYPE] === 'opencode') {
    return {
      agentType: 'opencode',
      providerId: env[OPENCODE_MCP_CREDENTIAL_ENV.PROVIDER_ID],
      baseUrl: env[OPENCODE_MCP_CREDENTIAL_ENV.BASE_URL],
      apiKey: env[OPENCODE_MCP_CREDENTIAL_ENV.API_KEY],
      reasoningEffort: env[OPENCODE_MCP_CREDENTIAL_ENV.REASONING_EFFORT] || undefined,
      model: env[OPENCODE_MCP_CREDENTIAL_ENV.MODEL],
    };
  }
  return {
    apiKey: env.ANTHROPIC_API_KEY,
    oauthToken: env.CLAUDE_CODE_OAUTH_TOKEN,
  };
}
