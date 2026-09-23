import { describe, expect, it } from 'vitest';
import {
  buildClaudeChildEnv,
  isDeniedClaudeChildEnvName,
  resolveClaudeCredential,
  CHAT_MAX_RETRIES,
  VERIFY_MAX_RETRIES,
} from './claude-child-env.js';
import { HARD_DENIED } from './opencode-child-env.js';
import { MissingAiCredentialError } from './ai-auth-failure.js';

/**
 * Claude Code 자식 env 헬퍼 (#708, #711).
 *
 * <p>핵심은 "요청 자격증명 하나만 도달하고 ambient 인증 경로는 어떤 이름으로도 새지 않는다"와
 * "자격증명이 없으면 자식을 띄우기 전에 실패한다"이다.
 */
describe('buildClaudeChildEnv', () => {
  /** 실제 운영/개발 머신에 있을 법한 ambient 인증 경로 전부. */
  const AMBIENT: NodeJS.ProcessEnv = {
    ANTHROPIC_API_KEY: 'ambient-key',
    ANTHROPIC_AUTH_TOKEN: 'ambient-bearer',
    ANTHROPIC_BASE_URL: 'https://ambient.example',
    ANTHROPIC_CUSTOM_HEADERS: 'Authorization: Bearer x',
    ANTHROPIC_VERTEX_PROJECT_ID: 'proj',
    CLAUDE_CODE_OAUTH_TOKEN: 'ambient-oauth',
    CLAUDE_CODE_OAUTH_REFRESH_TOKEN: 'ambient-refresh',
    CLAUDE_CODE_USE_BEDROCK: '1',
    CLAUDE_CODE_USE_VERTEX: '1',
    CLAUDE_CODE_USE_FOUNDRY: '1',
    CLAUDE_CODE_SKIP_BEDROCK_AUTH: '1',
    AWS_ACCESS_KEY_ID: 'AKIA',
    AWS_SECRET_ACCESS_KEY: 'secret',
    AWS_REGION: 'us-east-1',
    GOOGLE_APPLICATION_CREDENTIALS: '/gcp.json',
    CLOUD_ML_REGION: 'us-east5',
    INTERNAL_SERVICE_TOKEN: 'internal',
    OPENAI_API_KEY: 'openai',
    CLAUDECODE: '1',
    CLAUDE_CODE_ENTRYPOINT: 'cli',
    CLAUDE_CODE_MAX_RETRIES: '10',
  };
  const KEEP: NodeJS.ProcessEnv = {
    PATH: '/usr/bin',
    HOME: '/home/app',
    HTTPS_PROXY: 'http://proxy:3128',
    NODE_EXTRA_CA_CERTS: '/etc/ssl/corp.pem',
    TMPDIR: '/tmp',
    LANG: 'ko_KR.UTF-8',
    CLAUDE_CONFIG_DIR: '/home/app/.claude',
  };

  it('CCE-01: API 키 요청이면 그 키 하나만 싣고 ambient 인증 경로는 전부 떨어진다', () => {
    const env = buildClaudeChildEnv({ ...AMBIENT, ...KEEP }, { apiKey: 'sk-request' });

    expect(env.ANTHROPIC_API_KEY).toBe('sk-request');
    for (const name of Object.keys(AMBIENT)) {
      if (name === 'ANTHROPIC_API_KEY' || name === 'CLAUDE_CODE_MAX_RETRIES') continue;
      expect(name in env, name).toBe(false);
    }
  });

  it('CCE-02: OAuth 토큰이 API 키보다 우선하고, 이때 API 키는 싣지 않는다', () => {
    const env = buildClaudeChildEnv(AMBIENT, { apiKey: 'sk-request', oauthToken: 'oat-request' });

    expect(env.CLAUDE_CODE_OAUTH_TOKEN).toBe('oat-request');
    expect('ANTHROPIC_API_KEY' in env).toBe(false);
  });

  it('CCE-03: PATH·HOME·프록시·CA·TMPDIR·로캘·CLAUDE_CONFIG_DIR 은 그대로 남는다(HOME 을 옮기지 않는다)', () => {
    const env = buildClaudeChildEnv({ ...AMBIENT, ...KEEP }, { apiKey: 'sk' });

    expect(env).toMatchObject(KEEP);
  });

  it('CCE-04: 자격증명이 없거나 공백뿐이면 ambient 값이 있어도 MissingAiCredentialError', () => {
    expect(() => buildClaudeChildEnv(AMBIENT, undefined)).toThrow(MissingAiCredentialError);
    expect(() => buildClaudeChildEnv(AMBIENT, {})).toThrow(MissingAiCredentialError);
    expect(() => buildClaudeChildEnv(AMBIENT, { apiKey: '  ', oauthToken: '' })).toThrow(
      MissingAiCredentialError,
    );
  });

  it('CCE-05: 재시도 횟수는 호스트 값이 아니라 호출부 값이다 (채팅 2 / 검증 0)', () => {
    expect(buildClaudeChildEnv(AMBIENT, { apiKey: 'sk' }).CLAUDE_CODE_MAX_RETRIES).toBe(CHAT_MAX_RETRIES);
    expect(CHAT_MAX_RETRIES).toBe('2');
    expect(
      buildClaudeChildEnv(AMBIENT, { apiKey: 'sk' }, VERIFY_MAX_RETRIES).CLAUDE_CODE_MAX_RETRIES,
    ).toBe('0');
  });

  it('CCE-06: 부모 env 객체를 변경하지 않는다(동시 요청 간 오염 방지)', () => {
    const parent = { ...AMBIENT };
    buildClaudeChildEnv(parent, { oauthToken: 'oat' });
    expect(parent).toEqual(AMBIENT);
  });

  it('CCE-07: opencode 의 HARD_DENIED 이름은 전부 차단된다(방어선 공유)', () => {
    for (const name of HARD_DENIED) expect(isDeniedClaudeChildEnvName(name), name).toBe(true);
  });
});

describe('resolveClaudeCredential', () => {
  it('OAuth 우선, 공백은 없음, 없으면 null', () => {
    expect(resolveClaudeCredential({ apiKey: 'k', oauthToken: 't' })).toEqual({ kind: 'oauthToken', value: 't' });
    expect(resolveClaudeCredential({ apiKey: 'k', oauthToken: ' ' })).toEqual({ kind: 'apiKey', value: 'k' });
    expect(resolveClaudeCredential({ apiKey: ' ' })).toBeNull();
    expect(resolveClaudeCredential(undefined)).toBeNull();
  });
});
