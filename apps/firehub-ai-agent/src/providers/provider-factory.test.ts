import { describe, it, expect, vi, beforeEach } from 'vitest';

// src/providers/__tests__/provider-factory.test.ts 에 있던 중복 파일을 여기로 합쳤다(전체 브랜치
// 리뷰 M7) — 이 앱의 테스트 파일 위치 규칙("소스 파일과 같은 디렉토리에 *.test.ts")과 어긋난
// __tests__ 서브디렉토리 변형이었다. 아래 세 mock 은 그 파일이 ClaudeSdkChatProvider/
// ClaudeCliChatProvider/ClaudeClassifyProvider 생성 경로를 격리하려고 둔 것을 그대로 가져왔다.
// 주의: vi.mock 은 파일 전체에 호이스팅되어 이 파일의 모든 describe 에 적용된다 — 위쪽
// "ProviderFactory opencode"/"sdk 케이스"/"createCompletionProvider" 블록들이 그래도 통과하는
// 이유는 그쪽 테스트가 애초에 이 세 함수(executeAgent/executeCliAgent/classifyBatch)를 호출하는
// 코드 경로를 타지 않기 때문이지, mock 이 describe 로 격리돼서가 아니다. describe 로 격리되는
// 것은 아래 beforeEach(vi.clearAllMocks()) 뿐이다 — "ProviderFactory
// (createChatProvider/createClassifyProvider)" 안에서만 매 테스트 전에 호출 기록을 지운다.
vi.mock('../agent/agent-sdk.js', () => ({
  executeAgent: vi.fn(),
}));

vi.mock('../agent/agent-cli.js', () => ({
  executeCliAgent: vi.fn(),
}));

vi.mock('../services/classification-service.js', () => ({
  classifyBatch: vi.fn(),
}));

import { ProviderFactory } from './provider-factory.js';
import { OpenCodeChatProvider } from './opencode-chat-provider.js';
import { ClaudeSdkChatProvider } from './claude-sdk-chat-provider.js';
import { ClaudeCliChatProvider } from './claude-cli-chat-provider.js';
import { ClaudeClassifyProvider } from './claude-classify-provider.js';
import { DEFAULT_MODEL } from '../constants.js';
import { MissingAiCredentialError } from '../agent/ai-auth-failure.js';

describe('ProviderFactory opencode', () => {
  it('agentType=opencode 이면 OpenCodeChatProvider 를 생성한다', () => {
    const provider = ProviderFactory.createChatProvider({
      agentType: 'opencode',
      providerId: 'openai',
      baseUrl: 'https://api.openai.com/v1',
    });
    expect(provider).toBeInstanceOf(OpenCodeChatProvider);
    expect(provider.name).toBe('opencode');
  });

  // 옵션 3 폐기(2026-09-19, 이슈 #693) — provider 설정이 없으면 buildOpenCodeConfig 가 배포 측
  // 전역 opencode 설정으로 조용히 떨어질 여지가 있으므로, 그 전에 팩토리가 먼저 크게 실패해야
  // 한다. createCompletionProvider 의 같은 가드(baseUrl 누락 시 throw)와 대칭.
  it('providerId/baseUrl 이 없으면 크게 실패한다 (배포 측 전역 설정으로 조용히 떨어지지 않는다)', () => {
    expect(() => ProviderFactory.createChatProvider({ agentType: 'opencode' })).toThrow(
      /providerId|baseUrl/,
    );
  });

  it('providerId 만 있고 baseUrl 이 없으면 크게 실패한다', () => {
    expect(() =>
      ProviderFactory.createChatProvider({ agentType: 'opencode', providerId: 'openai' }),
    ).toThrow(/providerId|baseUrl/);
  });
});

describe('ProviderFactory sdk 케이스 (Task 1: OAuth 인증)', () => {
  it('apiKey만 있어도 생성된다', () => {
    expect(ProviderFactory.createChatProvider({ agentType: 'sdk', apiKey: 'sk-1' }).name).toBe(
      'claude-sdk',
    );
  });
  it('oauthToken만 있어도 생성된다', () => {
    expect(
      ProviderFactory.createChatProvider({ agentType: 'sdk', oauthToken: 'oat-1' }).name,
    ).toBe('claude-sdk');
  });
  it('apiKey·oauthToken 모두 없으면 throw', () => {
    expect(() => ProviderFactory.createChatProvider({ agentType: 'sdk' })).toThrow();
  });
});

// Task 8: opencode 테넌트의 OpenAI 호환 completion 프로바이더 분기.
// 이 분기가 없으면 opencode 자격증명(OpenAI 호환 키)이 Claude SDK 로 흘러 401 이거나,
// 비어 있으면 컨테이너의 ambient ANTHROPIC_API_KEY 로 새어 플랫폼 계정에 과금된다(6b1c6383).
describe('ProviderFactory.createCompletionProvider (Task 8: opencode 분기)', () => {
  it('opencode 면 OpenAI 호환 프로바이더를 고른다', () => {
    const p = ProviderFactory.createCompletionProvider({
      agentType: 'opencode',
      baseUrl: 'https://x/v1',
      apiKey: 'k',
      model: 'openai/gpt-4o',
    });
    expect(p.name).toBe('openai-compat');
  });

  it('나머지 유형은 Claude SDK 프로바이더를 고른다', () => {
    for (const agentType of ['sdk', 'cli', 'cli-api'] as const) {
      expect(ProviderFactory.createCompletionProvider({ agentType, apiKey: 'k' }).name).toBe(
        'claude-sdk-completion',
      );
    }
  });

  // #708: 무인자 호출(ambient 폴백)은 없어졌다. 빈 자격증명으로 생성은 되지만(MCP 자식 기동 시 도구
  // 등록이 죽지 않도록) 첫 complete() 가 ambient 값이 있어도 실패한다.
  it('빈 자격증명 config 는 생성되지만 complete() 가 ambient 값을 쓰지 않고 실패한다', async () => {
    const ORIGINAL = process.env.ANTHROPIC_API_KEY;
    process.env.ANTHROPIC_API_KEY = 'ambient-must-not-leak';
    try {
      const provider = ProviderFactory.createCompletionProvider({});
      expect(provider.name).toBe('claude-sdk-completion');
      await expect(provider.complete('sys', 'user')).rejects.toThrow(/AI 자격증명/);
    } finally {
      if (ORIGINAL === undefined) delete process.env.ANTHROPIC_API_KEY;
      else process.env.ANTHROPIC_API_KEY = ORIGINAL;
    }
  });

  // stdio-server.ts(CLI 자격증명은 부모가 심어 준 env 로만 오가 agentType 자체가 없다)와 단독
  // 스크립트 호출부가 이 모양으로 부른다 — 여기서 거부하면 그 호출부가 깨진다.
  // "agentType 없는 바디를 sdk+ambient 로 취급하지 말라"는 요구는 라우트 경계(/chat, /proactive,
  // classify)가 진다 — 이 팩토리는 의도적으로 관대하다(위 클래스 docstring 참고).
  it('agentType 이 없는 config 도 Claude SDK 로 간다 (정당한 무-agentType 호출부)', () => {
    expect(ProviderFactory.createCompletionProvider({ apiKey: 'k' }).name).toBe(
      'claude-sdk-completion',
    );
  });

  it('opencode 인데 baseUrl 이 없으면 크게 실패한다 (ambient 키로 조용히 새지 않는다)', () => {
    expect(() =>
      ProviderFactory.createCompletionProvider({ agentType: 'opencode', apiKey: '' }),
    ).toThrow(/baseUrl/);
  });

  // 설계서 "테스트로 고정할 것" 2번 — apiKey 가 빈 문자열이어도 팩토리가 ambient
  // ANTHROPIC_API_KEY 로 메우지 않는다(`config.apiKey ?? ''` 가 `||` 로 바뀌면 이 테스트가 잡는다).
  it('opencode 이고 apiKey 가 빈 문자열이어도 ambient ANTHROPIC_API_KEY 로 메우지 않는다', async () => {
    const ORIGINAL = process.env.ANTHROPIC_API_KEY;
    process.env.ANTHROPIC_API_KEY = 'ambient-must-not-leak';
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ choices: [{ message: { content: '' } }] }),
      text: async () => '{}',
    } as Response);

    try {
      // 203.0.113.10 은 RFC5737 TEST-NET-3(IP 리터럴) — ssrf-guard.ts(보안 리뷰 Fix2)가
      // 매 complete() 호출마다 도는 실제 SSRF 가드를 DNS 조회 없이 결정적으로 통과시킨다.
      const p = ProviderFactory.createCompletionProvider({
        agentType: 'opencode',
        baseUrl: 'https://203.0.113.10/v1',
        apiKey: '',
        model: 'openai/gpt-4o',
      });
      await p.complete('s', 'u');

      const headers = (fetchSpy.mock.calls[0][1] as RequestInit).headers as Record<
        string,
        string
      >;
      expect(headers.Authorization).toBe('Bearer ');
      expect(headers.Authorization).not.toContain('ambient-must-not-leak');
    } finally {
      fetchSpy.mockRestore();
      if (ORIGINAL === undefined) delete process.env.ANTHROPIC_API_KEY;
      else process.env.ANTHROPIC_API_KEY = ORIGINAL;
    }
  });
});

// 아래부터 옛 __tests__/provider-factory.test.ts 전체(PF-01~09) — createChatProvider 의
// sdk/cli/cli-api/unknown 분기와 createClassifyProvider 를 검증한다. 위 describe 들과 달리
// agent-sdk/agent-cli/classification-service 를 목으로 격리한다.
describe('ProviderFactory (createChatProvider/createClassifyProvider)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('createChatProvider', () => {
    // PF-01: SDK mode with apiKey returns ClaudeSdkChatProvider
    it('PF-01: SDK mode with apiKey returns ClaudeSdkChatProvider', () => {
      const provider = ProviderFactory.createChatProvider({
        agentType: 'sdk',
        apiKey: 'sk-test',
      });
      expect(provider).toBeInstanceOf(ClaudeSdkChatProvider);
      expect(provider.name).toBe('claude-sdk');
    });

    // PF-02: SDK mode without apiKey/oauthToken throws error
    it('PF-02: SDK mode without apiKey or oauthToken throws error', () => {
      expect(() =>
        ProviderFactory.createChatProvider({ agentType: 'sdk' }),
      ).toThrow(MissingAiCredentialError);
    });

    // PF-03: CLI mode returns ClaudeCliChatProvider with name 'claude-cli'
    it('PF-03: CLI mode returns ClaudeCliChatProvider with name claude-cli', () => {
      const provider = ProviderFactory.createChatProvider({ agentType: 'cli', oauthToken: 'oat-1' });
      expect(provider).toBeInstanceOf(ClaudeCliChatProvider);
      expect(provider.name).toBe('claude-cli');
    });

    // PF-03b (#708): 구독(cli) 모드도 OAuth 토큰이 필수다 — 없으면 호스트 키체인 로그인으로
    // 조용히 인증되던 경로를 생성 단계에서 막는다(공백 토큰도 "없음").
    it('PF-03b: CLI mode without oauthToken throws error', () => {
      expect(() => ProviderFactory.createChatProvider({ agentType: 'cli' })).toThrow(
        MissingAiCredentialError,
      );
      expect(() =>
        ProviderFactory.createChatProvider({ agentType: 'cli', oauthToken: '  ' }),
      ).toThrow(MissingAiCredentialError);
    });

    // PF-03c (#708): 세 Anthropic 유형 모두 한국어 안내·코드를 담은 MissingAiCredentialError 로 실패하고,
    // 공백 자격증명도 "없음"으로 본다(자식 env 와 같은 판정).
    it('PF-03c: 자격증명 없음은 한국어 안내와 코드를 담고, 공백 키도 거부된다', () => {
      let err: unknown;
      try {
        ProviderFactory.createChatProvider({ agentType: 'sdk', apiKey: '  ' });
      } catch (e) {
        err = e;
      }
      expect(err).toBeInstanceOf(MissingAiCredentialError);
      expect((err as Error).message).toContain('설정 › AI 에이전트');
      expect((err as MissingAiCredentialError).code).toBe('AGENT_AUTH_OR_QUOTA_FAILURE');
      expect(() => ProviderFactory.createChatProvider({ agentType: 'cli-api', apiKey: ' ' })).toThrow(
        MissingAiCredentialError,
      );
    });

    // PF-04: CLI-API mode with apiKey returns ClaudeCliChatProvider with name 'claude-cli-api'
    it('PF-04: CLI-API mode with apiKey returns ClaudeCliChatProvider with name claude-cli-api', () => {
      const provider = ProviderFactory.createChatProvider({
        agentType: 'cli-api',
        apiKey: 'sk-test',
      });
      expect(provider).toBeInstanceOf(ClaudeCliChatProvider);
      expect(provider.name).toBe('claude-cli-api');
    });

    // PF-05: CLI-API mode without apiKey throws error
    it('PF-05: CLI-API mode without apiKey throws error', () => {
      expect(() =>
        ProviderFactory.createChatProvider({ agentType: 'cli-api' }),
      ).toThrow(MissingAiCredentialError);
    });

    // PF-06: Unknown agentType throws error
    it('PF-06: unknown agentType throws error', () => {
      expect(() =>
        ProviderFactory.createChatProvider({
          agentType: 'unknown' as 'sdk',
        }),
      ).toThrow('Unknown agent type: unknown');
    });

    // PF-07: model parameter is passed through (SDK mode uses model or DEFAULT_MODEL)
    it('PF-07: model parameter is passed to SDK provider as defaultModel', () => {
      const provider = ProviderFactory.createChatProvider({
        agentType: 'sdk',
        apiKey: 'sk-test',
        model: 'claude-opus-4-6',
      }) as ClaudeSdkChatProvider;
      // Provider is created — verify it's the right type and DEFAULT_MODEL is used when model absent
      expect(provider).toBeInstanceOf(ClaudeSdkChatProvider);
    });

    // PF-08: SDK mode without model uses DEFAULT_MODEL
    it('PF-08: SDK mode without model uses DEFAULT_MODEL', () => {
      const provider = ProviderFactory.createChatProvider({
        agentType: 'sdk',
        apiKey: 'sk-test',
      }) as ClaudeSdkChatProvider;
      expect(provider).toBeInstanceOf(ClaudeSdkChatProvider);
      // The provider uses DEFAULT_MODEL internally — tested via execute() in sdk provider tests
      expect(DEFAULT_MODEL).toBeDefined();
    });
  });

  describe('createClassifyProvider', () => {
    // PF-09: createClassifyProvider returns ClaudeClassifyProvider
    it('PF-09: createClassifyProvider returns ClaudeClassifyProvider', () => {
      const provider = ProviderFactory.createClassifyProvider();
      expect(provider).toBeInstanceOf(ClaudeClassifyProvider);
      expect(provider.name).toBe('claude-classify');
    });
  });
});
