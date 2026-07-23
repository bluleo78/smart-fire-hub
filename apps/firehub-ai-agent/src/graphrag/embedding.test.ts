import { describe, it, expect } from 'vitest';
import { cosineSimilarity } from './embedding.js';

describe('cosineSimilarity', () => {
  it('동일 벡터는 1에 가깝다', () => {
    expect(cosineSimilarity([1, 2, 3], [1, 2, 3])).toBeCloseTo(1, 6);
  });

  it('직교 벡터는 0에 가깝다', () => {
    expect(cosineSimilarity([1, 0], [0, 1])).toBeCloseTo(0, 6);
  });

  it('영벡터는 0을 반환한다', () => {
    expect(cosineSimilarity([0, 0], [1, 1])).toBe(0);
  });
});
