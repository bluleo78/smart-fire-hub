/**
 * registerAllTools 의 `options.credentials` → registerGraphragTools 전달 배선(hop) 테스트.
 *
 * 리뷰 라운드 1 지적: `graphrag-tools.test.ts` 는 registerGraphragTools 를 **직접** 호출해
 * credentials 인자를 검증하지만, firehub-mcp-server.ts:231(`registerGraphragTools(apiClient,
 * safeToolFn, jsonResultFn, options.credentials)`)이라는 통과 지점 자체는 어떤 테스트도 지나가지
 * 않았다 — `options.credentials` 를 `undefined` 로 바꿔도 전체 스위트가 GREEN 이었다. 운영에서
 * 이 hop 이 fail-closed 로 보이는 것은 Part 4(agent-opencode.ts 의 env 스크럽)가 ambient 키를
 * 자식 env 에서 미리 지우는 우연 덕분이지, 이 hop 자체가 지켜지기 때문이 아니다 — Part 4 가
 * 없으면 다시 살아나는 유출이다.
 *
 * 별도 파일로 분리한 이유: firehub-mcp-server.test.ts(기존 424줄)는 모든 register*Tools 를
 * 실제 구현으로 등록해 도구 호출까지 검증한다 — 그 파일에 graphrag-tools.js 모킹을 끼워 넣으면
 * 그 파일의 나머지 관심사(다른 도구 등록·호출)에 영향을 줄 위험이 있다.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

const registerGraphragToolsMock = vi.fn((..._args: unknown[]): unknown[] => []);
vi.mock('./tools/graphrag-tools.js', () => ({
  registerGraphragTools: (...args: unknown[]) => registerGraphragToolsMock(...args),
}));

import { registerAllTools } from './firehub-mcp-server.js';
import { FireHubApiClient } from './api-client.js';

function createMockClient(): FireHubApiClient {
  const client = Object.create(FireHubApiClient.prototype);
  const methodNames = Object.getOwnPropertyNames(FireHubApiClient.prototype).filter(
    (name) => name !== 'constructor',
  );
  for (const name of methodNames) {
    client[name] = vi.fn().mockResolvedValue({ mocked: true });
  }
  return client as FireHubApiClient;
}

describe('registerAllTools — options.credentials 가 registerGraphragTools 로 전달된다', () => {
  beforeEach(() => {
    registerGraphragToolsMock.mockClear();
  });

  it('opencode 자격증명을 그대로 registerGraphragTools 의 4번째 인자로 넘긴다', () => {
    const client = createMockClient();
    const safeTool = vi.fn();
    const jsonResult = vi.fn();
    const credentials = {
      agentType: 'opencode' as const,
      providerId: 'openai',
      baseUrl: 'https://api.openai.com/v1',
      apiKey: 'sk-tenant-key',
      model: 'openai/gpt-4o',
    };

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    registerAllTools(client, safeTool as any, jsonResult as any, { credentials });

    expect(registerGraphragToolsMock).toHaveBeenCalledTimes(1);
    expect(registerGraphragToolsMock.mock.calls[0][3]).toEqual(credentials);
  });

  it('credentials 를 생략하면 registerGraphragTools 는 undefined 를 받는다 (ambient 로 새지 않는다)', () => {
    const client = createMockClient();
    const safeTool = vi.fn();
    const jsonResult = vi.fn();

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    registerAllTools(client, safeTool as any, jsonResult as any);

    expect(registerGraphragToolsMock.mock.calls[0][3]).toBeUndefined();
  });
});
