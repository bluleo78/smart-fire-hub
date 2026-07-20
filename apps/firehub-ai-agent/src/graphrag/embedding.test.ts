import { describe, it, expect, afterEach } from 'vitest';
import nock from 'nock';
import { embedTexts, cosineSimilarity } from './embedding.js';

const BASE_URL = 'http://localhost:11434';

afterEach(() => nock.cleanAll());

describe('embedTexts', () => {
  it('입력 순서를 보존하며 각 텍스트마다 벡터를 반환한다', async () => {
    const scope = nock(BASE_URL)
      .post('/api/embeddings', { model: 'bge-m3', prompt: '스프링클러' })
      .reply(200, { embedding: [1, 0, 0] })
      .post('/api/embeddings', { model: 'bge-m3', prompt: '스프링클러 설비' })
      .reply(200, { embedding: [0.9, 0.1, 0] });

    const vectors = await embedTexts(['스프링클러', '스프링클러 설비'], { baseUrl: BASE_URL });

    expect(vectors).toEqual([[1, 0, 0], [0.9, 0.1, 0]]);
    expect(scope.isDone()).toBe(true);
  });
});

describe('cosineSimilarity', () => {
  it('동일 벡터는 1에 가깝다', () => {
    expect(cosineSimilarity([1, 2, 3], [1, 2, 3])).toBeCloseTo(1, 6);
  });

  it('직교 벡터는 0에 가깝다', () => {
    expect(cosineSimilarity([1, 0], [0, 1])).toBeCloseTo(0, 6);
  });
});
