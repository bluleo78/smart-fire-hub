import { describe, it, expect, beforeEach, vi } from 'vitest';

// CompletionProvider를 목킹해 실제 Agent SDK 실행 없이 프롬프트 구성·응답 파싱·타입 강제만 검증한다.
const completeMock = vi.fn();
const createCompletionProviderMock = vi.fn((_config?: unknown) => ({
  name: 'mock-completion',
  complete: completeMock,
}));
vi.mock('../providers/provider-factory.js', () => ({
  ProviderFactory: {
    createCompletionProvider: (config?: unknown) => createCompletionProviderMock(config),
  },
}));

const { classifyBatch, classifyTimeoutMs } = await import('./classification-service.js');

/** LLM이 JSON 배열 텍스트를 돌려준 것으로 가정한 completion 결과를 만든다. */
function completionOf(payload: unknown, usage = { inputTokens: 200, outputTokens: 100 }) {
  return { text: typeof payload === 'string' ? payload : JSON.stringify(payload), usage };
}

const CREDS = { oauthToken: 'oauth-token' };
const MODEL = 'claude-sonnet-5';

describe('classifyBatch', () => {
  beforeEach(() => {
    completeMock.mockReset();
    createCompletionProviderMock.mockClear();
  });

  const validRequest = {
    rows: [
      { id: 1, name: '홍길동', free_comment: '서비스가 좋았습니다' },
      { id: 2, name: '김철수', free_comment: '별로였어요' },
    ],
    prompt: '각 행의 free_comment를 감성 분류하세요',
    outputColumns: [
      { name: 'label', type: 'TEXT' as const },
      { name: 'confidence', type: 'DECIMAL' as const },
      { name: 'reason', type: 'TEXT' as const },
    ],
  };

  it('행을 분류하고 usage/model을 반환한다', async () => {
    completeMock.mockResolvedValue(
      completionOf([
        { source_id: 1, label: '긍정', confidence: 0.94, reason: '만족 표현' },
        { source_id: 2, label: '부정', confidence: 0.91, reason: '불만 표현' },
      ]),
    );

    const result = await classifyBatch(validRequest, CREDS, MODEL);

    expect(result.results).toHaveLength(2);
    expect(result.results[0]).toMatchObject({ source_id: 1, label: '긍정', confidence: 0.94 });
    expect(result.results[1]).toMatchObject({ source_id: 2, label: '부정', confidence: 0.91 });
    expect(result.processed).toBe(2);
    expect(result.model).toBe(MODEL);
    expect(result.usage.promptTokens).toBe(200);
    expect(result.usage.completionTokens).toBe(100);
  });

  it('자격증명과 모델을 CompletionProvider 생성에 전달한다', async () => {
    completeMock.mockResolvedValue(completionOf([{ source_id: 1, label: 'a' }]));

    await classifyBatch(validRequest, { apiKey: 'sk-abc', oauthToken: 'oauth-xyz' }, 'claude-haiku-4-5');

    expect(createCompletionProviderMock).toHaveBeenCalledWith({
      apiKey: 'sk-abc',
      oauthToken: 'oauth-xyz',
      model: 'claude-haiku-4-5',
    });
  });

  it('자격증명이 비어 있어도 막지 않고 환경/키체인 폴백에 맡긴다', async () => {
    // GraphRAG 경로와 동일한 계약 — 컨테이너 env 나 로컬 CLI 키체인에 인증이 있을 수 있다.
    completeMock.mockResolvedValue(completionOf([{ source_id: 1, label: '긍정' }]));

    const result = await classifyBatch(validRequest, {}, MODEL);

    expect(result.results[0].label).toBe('긍정');
    expect(createCompletionProviderMock).toHaveBeenCalledWith({
      apiKey: undefined,
      oauthToken: undefined,
      model: MODEL,
    });
  });

  it('OAuth 토큰만 있어도 동작한다 (prod 구성)', async () => {
    completeMock.mockResolvedValue(completionOf([{ source_id: 1, label: '긍정' }]));

    const result = await classifyBatch(validRequest, { oauthToken: 'oauth-only' }, MODEL);

    expect(result.results[0].label).toBe('긍정');
  });

  it('completion이 실패하면 에러를 전파한다', async () => {
    completeMock.mockRejectedValue(new Error('[completion] SDK 실행 실패'));

    await expect(classifyBatch(validRequest, CREDS, MODEL)).rejects.toThrow(/SDK 실행 실패/);
  });

  it('마크다운 코드블록으로 감싼 응답을 처리한다', async () => {
    completeMock.mockResolvedValue(
      completionOf(
        '```json\n' +
          JSON.stringify([
            { source_id: 1, label: '긍정', confidence: 0.9, reason: '좋음' },
            { source_id: 2, label: '부정', confidence: 0.85, reason: '나쁨' },
          ]) +
          '\n```',
      ),
    );

    const result = await classifyBatch(validRequest, CREDS, MODEL);

    expect(result.results[0].label).toBe('긍정');
    expect(result.results[1].label).toBe('부정');
  });

  it('빈 응답이면 에러를 던진다', async () => {
    completeMock.mockResolvedValue(completionOf('   '));

    await expect(classifyBatch(validRequest, CREDS, MODEL)).rejects.toThrow(/empty response/);
  });

  it('JSON이 아니면 에러를 던진다', async () => {
    completeMock.mockResolvedValue(completionOf('죄송합니다, 처리할 수 없습니다.'));

    await expect(classifyBatch(validRequest, CREDS, MODEL)).rejects.toThrow(/Failed to parse/);
  });

  it('타입 강제: TEXT는 문자열, DECIMAL은 숫자, null은 null', async () => {
    completeMock.mockResolvedValue(
      completionOf([
        { source_id: 1, label: 42, confidence: '0.9', reason: null },
        { source_id: 2, label: true, confidence: '0.85', reason: 'ok' },
      ]),
    );

    const result = await classifyBatch(validRequest, CREDS, MODEL);

    expect(typeof result.results[0].label).toBe('string');
    expect(result.results[0].label).toBe('42');
    expect(typeof result.results[0].confidence).toBe('number');
    expect(result.results[0].confidence).toBe(0.9);
    expect(result.results[0].reason).toBeNull();
  });

  it('타입 강제: INTEGER 파싱', async () => {
    completeMock.mockResolvedValue(completionOf([{ source_id: 1, count: '15' }]));

    const result = await classifyBatch(
      {
        rows: [{ id: 1, value: '42' }],
        prompt: 'extract integer',
        outputColumns: [{ name: 'count', type: 'INTEGER' as const }],
      },
      CREDS,
      MODEL,
    );

    expect(result.results[0].count).toBe(15);
    expect(typeof result.results[0].count).toBe('number');
  });

  it('타입 강제: BOOLEAN 파싱', async () => {
    completeMock.mockResolvedValue(
      completionOf([
        { source_id: 1, is_positive: 'true' },
        { source_id: 2, is_positive: false },
      ]),
    );

    const result = await classifyBatch(
      {
        rows: [
          { id: 1, text: 'yes' },
          { id: 2, text: 'no' },
        ],
        prompt: 'classify as true/false',
        outputColumns: [{ name: 'is_positive', type: 'BOOLEAN' as const }],
      },
      CREDS,
      MODEL,
    );

    expect(result.results[0].is_positive).toBe(true);
    expect(result.results[1].is_positive).toBe(false);
  });
});

describe('classifyTimeoutMs (#686)', () => {
  // 배치 하나는 LLM completion 한 번이고, 그 소요 시간은 출력 토큰 수(≈ 행 수 × 출력 컬럼 수)에
  // 비례한다. 상한이 고정이면 배치를 키우는 순간 반드시 넘긴다 — 운영에서 20행 배치가 50초
  // 고정 상한을 넘겨 통째로 날아갔다.
  it('행 수에 따라 늘어난다', () => {
    expect(classifyTimeoutMs(20)).toBeGreaterThan(classifyTimeoutMs(10));
  });

  it('운영에서 실패했던 크기를 실측 소요시간의 여유를 두고 담는다', () => {
    // 실측: 10행 41초. 20행이면 82초 안팎이고, 이전 상한 50초로는 담기지 않았다.
    expect(classifyTimeoutMs(20)).toBeGreaterThan(82_000);
    expect(classifyTimeoutMs(10)).toBeGreaterThan(41_000);
  });

  it('상한이 있고, 그 상한은 Spring AiAgentClient.TIMEOUT(330초)보다 작다', () => {
    // 이 대소 관계가 깨지면 Spring 이 먼저 끊어 원인 없는 타임아웃만 남는다.
    // batchSize 검증 상한은 100이므로 그 끝에서도 천장을 넘지 않아야 한다.
    expect(classifyTimeoutMs(100)).toBeLessThan(330_000);
    expect(classifyTimeoutMs(10_000)).toBeLessThan(330_000);
  });

  it('행이 없어도 음수가 되지 않는다', () => {
    expect(classifyTimeoutMs(0)).toBeGreaterThan(0);
    expect(classifyTimeoutMs(-1)).toBeGreaterThan(0);
  });
});
