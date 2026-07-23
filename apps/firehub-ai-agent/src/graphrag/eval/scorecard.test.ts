import { it, expect } from 'vitest';
import { aggregate, renderScorecard } from './scorecard.js';

const results = [
  { id: 'mh-1', class: 'multihop', graphragAnswer: '', vectorAnswer: '', graphragScore: 5, vectorScore: 2, winner: 'graphrag', rationale: '' },
  { id: 'lk-1', class: 'lookup', graphragAnswer: '', vectorAnswer: '', graphragScore: 3, vectorScore: 4, winner: 'vector', rationale: '' },
] as const;

it('aggregate 는 부류별·전체 평균·승수를 집계', () => {
  const agg = aggregate(results as never);
  expect(agg.byClass.multihop.graphragWins).toBe(1);
  expect(agg.byClass.lookup.vectorWins).toBe(1);
  expect(agg.overall.n).toBe(2);
  expect(agg.overall.graphragAvg).toBeCloseTo(4); // (5+3)/2
});

it('renderScorecard 는 잠정-합성 한계 라벨을 포함', () => {
  const md = renderScorecard(aggregate(results as never), results as never, 'samples');
  expect(md).toMatch(/잠정|합성|프로덕션 판정 아님/);
});
