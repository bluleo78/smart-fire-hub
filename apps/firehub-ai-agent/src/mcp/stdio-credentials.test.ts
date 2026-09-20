import { describe, it, expect } from 'vitest';
import { resolveStdioCredentials } from './stdio-credentials.js';
import { OPENCODE_MCP_CREDENTIAL_ENV } from './opencode-mcp-credential-env.js';

describe('resolveStdioCredentials', () => {
  it('AI_CREDENTIAL_AGENT_TYPE=opencode 면 opencode provider 자격증명을 돌려주고 ambient Anthropic 자격증명을 읽지 않는다', () => {
    // Ruling #30 회귀 재현 조건: ambient 값이 실제로 세팅돼 있어야 "안 읽는다"는 단언이 의미
    // 있다 — 애초에 비어 있으면 "읽지 않아서" 인지 "값이 없어서" 인지 구분되지 않는다.
    const env: NodeJS.ProcessEnv = {
      [OPENCODE_MCP_CREDENTIAL_ENV.AGENT_TYPE]: 'opencode',
      [OPENCODE_MCP_CREDENTIAL_ENV.PROVIDER_ID]: 'openai',
      [OPENCODE_MCP_CREDENTIAL_ENV.BASE_URL]: 'https://api.openai.com/v1',
      [OPENCODE_MCP_CREDENTIAL_ENV.API_KEY]: 'sk-tenant-key',
      [OPENCODE_MCP_CREDENTIAL_ENV.REASONING_EFFORT]: 'medium',
      [OPENCODE_MCP_CREDENTIAL_ENV.MODEL]: 'openai/gpt-4o',
      ANTHROPIC_API_KEY: 'ambient-must-not-leak',
      CLAUDE_CODE_OAUTH_TOKEN: 'ambient-oauth-must-not-leak',
    };

    const creds = resolveStdioCredentials(env);

    expect(creds).toEqual({
      agentType: 'opencode',
      providerId: 'openai',
      baseUrl: 'https://api.openai.com/v1',
      apiKey: 'sk-tenant-key',
      reasoningEffort: 'medium',
      model: 'openai/gpt-4o',
    });
    // 위 toEqual 이 이미 apiKey 를 정확한 값으로 고정했지만(ambient 값과 다름), oauthToken
    // 필드 자체가 결과에 없는지도 `in` 으로 확인한다 — falsy 검사(`!creds.oauthToken`)는 빈
    // 문자열과 부재를 구분하지 못해 "빈 값으로 덮어썼을 뿐"인 뮤턴트를 놓친다.
    expect('oauthToken' in creds).toBe(false);
  });

  it('추론 강도가 빈 문자열이면 undefined 로 정규화한다', () => {
    const env: NodeJS.ProcessEnv = {
      [OPENCODE_MCP_CREDENTIAL_ENV.AGENT_TYPE]: 'opencode',
      [OPENCODE_MCP_CREDENTIAL_ENV.PROVIDER_ID]: 'openai',
      [OPENCODE_MCP_CREDENTIAL_ENV.BASE_URL]: 'https://api.openai.com/v1',
      [OPENCODE_MCP_CREDENTIAL_ENV.API_KEY]: 'sk',
      [OPENCODE_MCP_CREDENTIAL_ENV.REASONING_EFFORT]: '',
      [OPENCODE_MCP_CREDENTIAL_ENV.MODEL]: 'openai/gpt-4o',
    };

    expect(resolveStdioCredentials(env).reasoningEffort).toBeUndefined();
  });

  it('AI_CREDENTIAL_AGENT_TYPE 이 없으면 CLI 경로로 ambient Anthropic 자격증명을 읽는다 (기존 동작 유지)', () => {
    const env: NodeJS.ProcessEnv = {
      ANTHROPIC_API_KEY: 'sk-anthropic',
      CLAUDE_CODE_OAUTH_TOKEN: 'oat-anthropic',
    };

    expect(resolveStdioCredentials(env)).toEqual({
      apiKey: 'sk-anthropic',
      oauthToken: 'oat-anthropic',
    });
  });

  it('AI_CREDENTIAL_AGENT_TYPE 이 opencode 가 아닌 값이어도 CLI 경로로 취급한다', () => {
    const env: NodeJS.ProcessEnv = {
      [OPENCODE_MCP_CREDENTIAL_ENV.AGENT_TYPE]: 'sdk',
      ANTHROPIC_API_KEY: 'sk-anthropic',
    };

    const creds = resolveStdioCredentials(env);
    expect(creds).toEqual({ apiKey: 'sk-anthropic', oauthToken: undefined });
    expect('providerId' in creds).toBe(false);
  });
});
