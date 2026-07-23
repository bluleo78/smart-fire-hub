import { describe, it, expect } from 'vitest';
import { loadQuestions } from './questions.js';

describe('eval 질문 셋', () => {
  it('고유 id, 유효 class, 부류 균형(그래프-유리·벡터-유리 모두 포함)', () => {
    const qs = loadQuestions();
    expect(qs.length).toBeGreaterThanOrEqual(10);
    expect(new Set(qs.map((q) => q.id)).size).toBe(qs.length); // 고유
    const classes = new Set(qs.map((q) => q.class));
    // 정직성: 그래프-유리(multihop/relationship)와 벡터-유리(lookup) 둘 다 존재
    expect(classes.has('multihop') || classes.has('relationship')).toBe(true);
    expect(classes.has('lookup')).toBe(true);
    qs.forEach((q) => expect(['multihop', 'relationship', 'lookup', 'attribute']).toContain(q.class));
  });
});
