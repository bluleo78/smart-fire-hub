import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { classifyBatch } from './classification-service.js';

const VALID_TOKEN = 'test-internal-token';
const API_BASE_URL = 'http://localhost:8080/api/v1';
const ANTHROPIC_API_URL = 'https://api.anthropic.com';

describe('classifyBatch', () => {
  beforeEach(() => {
    process.env.ANTHROPIC_API_KEY = 'test-api-key';
    nock.cleanAll();
    nock.disableNetConnect();
  });

  afterEach(() => {
    delete process.env.ANTHROPIC_API_KEY;
    nock.cleanAll();
    nock.enableNetConnect();
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

  const anthropicSuccessResponse = {
    id: 'msg_123',
    content: [
      {
        type: 'text',
        text: JSON.stringify([
          { source_id: 1, label: '긍정', confidence: 0.94, reason: '만족 표현' },
          { source_id: 2, label: '부정', confidence: 0.91, reason: '불만 표현' },
        ]),
      },
    ],
    usage: { input_tokens: 200, output_tokens: 100 },
  };

  it('should classify rows successfully using env API key when settings API fails', async () => {
    nock(API_BASE_URL).get('/settings').query(true).replyWithError('connection refused');

    nock(ANTHROPIC_API_URL)
      .post('/v1/messages')
      .reply(200, anthropicSuccessResponse);

    const result = await classifyBatch(validRequest, API_BASE_URL, VALID_TOKEN);

    expect(result.results).toHaveLength(2);
    expect(result.results[0]).toMatchObject({ source_id: 1, label: '긍정', confidence: 0.94 });
    expect(result.results[1]).toMatchObject({ source_id: 2, label: '부정', confidence: 0.91 });
    expect(result.processed).toBe(2);
    expect(result.usage.promptTokens).toBe(200);
    expect(result.usage.completionTokens).toBe(100);
  });

  it('should use model from settings API when available', async () => {
    nock(API_BASE_URL)
      .get('/settings')
      .query(true)
      .reply(200, [
        { key: 'ai.model', value: 'claude-sonnet-4-6' },
        { key: 'ai.api_key', value: 'settings-api-key' },
      ]);

    let capturedBody: Record<string, unknown> = {};
    nock(ANTHROPIC_API_URL)
      .post('/v1/messages', (body: Record<string, unknown>) => {
        capturedBody = body;
        return true;
      })
      .reply(200, { ...anthropicSuccessResponse });

    const result = await classifyBatch(validRequest, API_BASE_URL, VALID_TOKEN);

    expect(capturedBody.model).toBe('claude-sonnet-4-6');
    expect(result.model).toBe('claude-sonnet-4-6');
  });

  it('should throw when Anthropic API fails', async () => {
    nock(API_BASE_URL).get('/settings').query(true).replyWithError('connection refused');
    nock(ANTHROPIC_API_URL).post('/v1/messages').replyWithError('Anthropic API unavailable');

    await expect(classifyBatch(validRequest, API_BASE_URL, VALID_TOKEN)).rejects.toThrow();
  });

  it('should handle LLM response with markdown code blocks', async () => {
    nock(API_BASE_URL).get('/settings').query(true).replyWithError('connection refused');

    const responseWithCodeBlock = {
      ...anthropicSuccessResponse,
      content: [
        {
          type: 'text',
          text:
            '```json\n' +
            JSON.stringify([
              { source_id: 1, label: '긍정', confidence: 0.9, reason: '좋음' },
              { source_id: 2, label: '부정', confidence: 0.85, reason: '나쁨' },
            ]) +
            '\n```',
        },
      ],
    };

    nock(ANTHROPIC_API_URL).post('/v1/messages').reply(200, responseWithCodeBlock);

    const result = await classifyBatch(validRequest, API_BASE_URL, VALID_TOKEN);

    expect(result.results[0].label).toBe('긍정');
    expect(result.results[1].label).toBe('부정');
  });

  it('should apply type coercion: TEXT to string', async () => {
    nock(API_BASE_URL).get('/settings').query(true).replyWithError('connection refused');

    nock(ANTHROPIC_API_URL)
      .post('/v1/messages')
      .reply(200, {
        ...anthropicSuccessResponse,
        content: [
          {
            type: 'text',
            text: JSON.stringify([
              { source_id: 1, label: 42, confidence: '0.9', reason: null },
              { source_id: 2, label: true, confidence: '0.85', reason: 'ok' },
            ]),
          },
        ],
      });

    const result = await classifyBatch(validRequest, API_BASE_URL, VALID_TOKEN);

    // label is TEXT → should be string
    expect(typeof result.results[0].label).toBe('string');
    expect(result.results[0].label).toBe('42');
    // confidence is DECIMAL → should be number
    expect(typeof result.results[0].confidence).toBe('number');
    expect(result.results[0].confidence).toBe(0.9);
    // null TEXT → null
    expect(result.results[0].reason).toBeNull();
  });

  it('should apply type coercion: INTEGER parsing', async () => {
    nock(API_BASE_URL).get('/settings').query(true).replyWithError('connection refused');

    const intRequest = {
      rows: [{ id: 1, value: '42' }],
      prompt: 'extract integer',
      outputColumns: [{ name: 'count', type: 'INTEGER' as const }],
    };

    nock(ANTHROPIC_API_URL)
      .post('/v1/messages')
      .reply(200, {
        id: 'msg_1',
        content: [
          {
            type: 'text',
            text: JSON.stringify([{ source_id: 1, count: '15' }]),
          },
        ],
        usage: { input_tokens: 50, output_tokens: 20 },
      });

    const result = await classifyBatch(intRequest, API_BASE_URL, VALID_TOKEN);

    expect(result.results[0].count).toBe(15);
    expect(typeof result.results[0].count).toBe('number');
  });

  it('should apply type coercion: BOOLEAN parsing', async () => {
    nock(API_BASE_URL).get('/settings').query(true).replyWithError('connection refused');

    const boolRequest = {
      rows: [{ id: 1, text: 'yes' }, { id: 2, text: 'no' }],
      prompt: 'classify as true/false',
      outputColumns: [{ name: 'is_positive', type: 'BOOLEAN' as const }],
    };

    nock(ANTHROPIC_API_URL)
      .post('/v1/messages')
      .reply(200, {
        id: 'msg_1',
        content: [
          {
            type: 'text',
            text: JSON.stringify([
              { source_id: 1, is_positive: 'true' },
              { source_id: 2, is_positive: false },
            ]),
          },
        ],
        usage: { input_tokens: 50, output_tokens: 20 },
      });

    const result = await classifyBatch(boolRequest, API_BASE_URL, VALID_TOKEN);

    expect(result.results[0].is_positive).toBe(true);
    expect(result.results[1].is_positive).toBe(false);
  });

  it('should throw when API key is not configured', async () => {
    delete process.env.ANTHROPIC_API_KEY;
    nock(API_BASE_URL)
      .get('/settings')
      .query(true)
      .reply(200, [{ key: 'ai.model', value: 'claude-haiku-4-5-20251001' }]);

    nock(API_BASE_URL).get('/settings/ai-api-key').replyWithError('not found');

    await expect(classifyBatch(validRequest, API_BASE_URL, VALID_TOKEN)).rejects.toThrow('API key');
  });
});
