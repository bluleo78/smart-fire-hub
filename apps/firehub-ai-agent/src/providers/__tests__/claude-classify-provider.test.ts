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

  // CC-01: classify() delegates to classifyBatch correctly
  it('CC-01: classify() delegates to classifyBatch with correct arguments', async () => {
    mockClassifyBatch.mockResolvedValue(mockResponse);

    const provider = new ClaudeClassifyProvider(
      'http://localhost:8080/api/v1',
      'test-token',
    );
    const options = {
      rows: [{ id: 1, free_comment: '서비스가 좋았습니다' }],
      prompt: '감성 분류하세요',
      outputColumns: [{ name: 'label', type: 'TEXT' as const }],
      userId: 42,
    };

    const result = await provider.classify(options);

    expect(result).toEqual(mockResponse);
    expect(mockClassifyBatch).toHaveBeenCalledOnce();
    expect(mockClassifyBatch).toHaveBeenCalledWith(
      { rows: options.rows, prompt: options.prompt, outputColumns: options.outputColumns },
      'http://localhost:8080/api/v1',
      'test-token',
      42,
    );
  });

  // CC-02: userId is passed through correctly
  it('CC-02: userId is passed through to classifyBatch', async () => {
    mockClassifyBatch.mockResolvedValue(mockResponse);

    const provider = new ClaudeClassifyProvider('http://api/v1', 'tok');
    await provider.classify({
      rows: [{ id: 1 }],
      prompt: 'classify',
      outputColumns: [{ name: 'label', type: 'TEXT' as const }],
      userId: 99,
    });

    const called = mockClassifyBatch.mock.calls[0];
    expect(called[3]).toBe(99);
  });
});
