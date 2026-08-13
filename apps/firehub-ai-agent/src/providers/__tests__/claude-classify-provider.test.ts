import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../../services/classification-service.js', () => ({
  classifyBatch: vi.fn(),
}));

import { ClaudeClassifyProvider } from '../claude-classify-provider.js';
import { classifyBatch } from '../../services/classification-service.js';

const mockClassifyBatch = vi.mocked(classifyBatch);

const mockResponse = {
  results: [{ source_id: 1, label: '긍정', confidence: 0.95, reason: '만족 표현' }],
  processed: 1,
  model: 'claude-haiku-4-5-20251001',
  usage: { promptTokens: 100, completionTokens: 50 },
};

describe('ClaudeClassifyProvider', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // CC-01: classify() 가 요청 바디의 자격증명·모델을 classifyBatch 로 그대로 넘기는지 검증
  it('CC-01: classify() delegates to classifyBatch with credentials and model', async () => {
    mockClassifyBatch.mockResolvedValue(mockResponse);

    const provider = new ClaudeClassifyProvider();
    const options = {
      rows: [{ id: 1, free_comment: '서비스가 좋았습니다' }],
      prompt: '감성 분류하세요',
      outputColumns: [{ name: 'label', type: 'TEXT' as const }],
      model: 'claude-haiku-4-5',
      apiKey: 'sk-abc',
      oauthToken: 'oauth-xyz',
      userId: 42,
    };

    const result = await provider.classify(options);

    expect(result).toEqual(mockResponse);
    expect(mockClassifyBatch).toHaveBeenCalledOnce();
    expect(mockClassifyBatch).toHaveBeenCalledWith(
      { rows: options.rows, prompt: options.prompt, outputColumns: options.outputColumns },
      { apiKey: 'sk-abc', oauthToken: 'oauth-xyz' },
      'claude-haiku-4-5',
    );
  });

  // CC-02: 모델 미지정 시 DEFAULT_MODEL 로 폴백하는지 검증
  it('CC-02: falls back to DEFAULT_MODEL when model is omitted', async () => {
    mockClassifyBatch.mockResolvedValue(mockResponse);

    const provider = new ClaudeClassifyProvider();
    await provider.classify({
      rows: [{ id: 1 }],
      prompt: 'classify',
      outputColumns: [{ name: 'label', type: 'TEXT' as const }],
      oauthToken: 'tok',
    });

    const called = mockClassifyBatch.mock.calls[0];
    expect(called[1]).toEqual({ apiKey: undefined, oauthToken: 'tok' });
    expect(typeof called[2]).toBe('string');
    expect(called[2]).toBeTruthy();
  });
});
