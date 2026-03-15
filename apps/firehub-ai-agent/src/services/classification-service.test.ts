import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
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
      { rowId: '1', text: 'This product is amazing!' },
      { rowId: '2', text: 'Terrible experience.' },
    ],
    labels: ['positive', 'neutral', 'negative'],
    promptTemplate:
      'Classify the following text into one of the allowed labels: {labels}. Text: {text}',
    promptVersion: 'v1',
  };

  const anthropicSuccessResponse = {
    id: 'msg_123',
    content: [
      {
        type: 'text',
        text: JSON.stringify([
          { rowId: '1', label: 'positive', confidence: 0.94, reason: 'expresses satisfaction' },
          { rowId: '2', label: 'negative', confidence: 0.91, reason: 'terrible is negative' },
        ]),
      },
    ],
    usage: { input_tokens: 150, output_tokens: 80 },
  };

  it('should classify rows successfully using env API key when settings API fails', async () => {
    // Settings API fails
    nock(API_BASE_URL).get('/settings').query(true).replyWithError('connection refused');

    // Anthropic API succeeds
    nock(ANTHROPIC_API_URL)
      .post('/v1/messages')
      .reply(200, anthropicSuccessResponse);

    const result = await classifyBatch(validRequest, API_BASE_URL, VALID_TOKEN);

    expect(result.results).toHaveLength(2);
    expect(result.results[0]).toMatchObject({ rowId: '1', label: 'positive', confidence: 0.94 });
    expect(result.results[1]).toMatchObject({ rowId: '2', label: 'negative', confidence: 0.91 });
    expect(result.processed).toBe(2);
    expect(result.cached).toBe(0);
    expect(result.usage.promptTokens).toBe(150);
    expect(result.usage.completionTokens).toBe(80);
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

  it('should return error results for all rows when Anthropic API fails', async () => {
    nock(API_BASE_URL).get('/settings').query(true).replyWithError('connection refused');
    nock(ANTHROPIC_API_URL).post('/v1/messages').replyWithError('Anthropic API unavailable');

    const result = await classifyBatch(validRequest, API_BASE_URL, VALID_TOKEN);

    expect(result.results).toHaveLength(2);
    for (const r of result.results) {
      expect(r).toHaveProperty('error');
      expect(r.confidence).toBe(0);
    }
  });

  it('should handle LLM response with markdown code blocks', async () => {
    nock(API_BASE_URL).get('/settings').query(true).replyWithError('connection refused');

    const responseWithCodeBlock = {
      ...anthropicSuccessResponse,
      content: [
        {
          type: 'text',
          text: '```json\n' + JSON.stringify([
            { rowId: '1', label: 'positive', confidence: 0.9, reason: 'good' },
            { rowId: '2', label: 'negative', confidence: 0.85, reason: 'bad' },
          ]) + '\n```',
        },
      ],
    };

    nock(ANTHROPIC_API_URL).post('/v1/messages').reply(200, responseWithCodeBlock);

    const result = await classifyBatch(validRequest, API_BASE_URL, VALID_TOKEN);

    expect(result.results[0].label).toBe('positive');
    expect(result.results[1].label).toBe('negative');
  });

  it('should handle missing rows in LLM response with error marker', async () => {
    nock(API_BASE_URL).get('/settings').query(true).replyWithError('connection refused');

    // LLM only returns result for row 1, not row 2
    nock(ANTHROPIC_API_URL)
      .post('/v1/messages')
      .reply(200, {
        ...anthropicSuccessResponse,
        content: [
          {
            type: 'text',
            text: JSON.stringify([
              { rowId: '1', label: 'positive', confidence: 0.94, reason: 'good' },
            ]),
          },
        ],
      });

    const result = await classifyBatch(validRequest, API_BASE_URL, VALID_TOKEN);

    expect(result.results).toHaveLength(2);
    const row2 = result.results.find((r) => r.rowId === '2');
    expect(row2).toHaveProperty('error');
    expect(row2?.confidence).toBe(0);
  });

  it('should clamp confidence values to 0.0-1.0 range', async () => {
    nock(API_BASE_URL).get('/settings').query(true).replyWithError('connection refused');

    nock(ANTHROPIC_API_URL)
      .post('/v1/messages')
      .reply(200, {
        ...anthropicSuccessResponse,
        content: [
          {
            type: 'text',
            text: JSON.stringify([
              { rowId: '1', label: 'positive', confidence: 1.5, reason: 'very good' },
              { rowId: '2', label: 'negative', confidence: -0.1, reason: 'bad' },
            ]),
          },
        ],
      });

    const result = await classifyBatch(validRequest, API_BASE_URL, VALID_TOKEN);

    expect(result.results[0].confidence).toBe(1.0);
    expect(result.results[1].confidence).toBe(0.0);
  });

  it('should throw when API key is not configured', async () => {
    delete process.env.ANTHROPIC_API_KEY;
    nock(API_BASE_URL)
      .get('/settings')
      .query(true)
      .reply(200, [{ key: 'ai.model', value: 'claude-haiku-4-5-20251001' }]);
    // No api key in settings response

    await expect(
      classifyBatch(validRequest, API_BASE_URL, VALID_TOKEN),
    ).rejects.toThrow('API key');
  });
});
