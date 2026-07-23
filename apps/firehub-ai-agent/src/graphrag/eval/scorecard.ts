// 평가 결과 집계 + 마크다운 스코어카드 렌더(순수 함수).
import { EvalResult } from './types.js';

export interface ClassAgg { n: number; graphragAvg: number; vectorAvg: number; graphragWins: number; vectorWins: number; ties: number; }

function emptyAgg(): ClassAgg { return { n: 0, graphragAvg: 0, vectorAvg: 0, graphragWins: 0, vectorWins: 0, ties: 0 }; }

// 부류별 + 전체 집계.
export function aggregate(results: EvalResult[]): { byClass: Record<string, ClassAgg>; overall: ClassAgg } {
  const byClass: Record<string, ClassAgg> = {};
  const sums: Record<string, { g: number; v: number }> = {};
  let og = 0, ov = 0;
  const overall = emptyAgg();
  for (const r of results) {
    const a = (byClass[r.class] ??= emptyAgg());
    (sums[r.class] ??= { g: 0, v: 0 });
    a.n++; sums[r.class].g += r.graphragScore; sums[r.class].v += r.vectorScore;
    overall.n++; og += r.graphragScore; ov += r.vectorScore;
    if (r.winner === 'graphrag') { a.graphragWins++; overall.graphragWins++; }
    else if (r.winner === 'vector') { a.vectorWins++; overall.vectorWins++; }
    else { a.ties++; overall.ties++; }
  }
  for (const k of Object.keys(byClass)) {
    byClass[k].graphragAvg = sums[k].g / byClass[k].n;
    byClass[k].vectorAvg = sums[k].v / byClass[k].n;
  }
  overall.graphragAvg = overall.n ? og / overall.n : 0;
  overall.vectorAvg = overall.n ? ov / overall.n : 0;
  return { byClass, overall };
}

// 마크다운 스코어카드. 상단에 합성-잠정 한계를 반드시 명시.
export function renderScorecard(
  agg: { byClass: Record<string, ClassAgg>; overall: ClassAgg }, results: EvalResult[], label: string,
): string {
  const head = `# GraphRAG vs 벡터검색 스코어카드 (${label})

> ⚠️ **잠정-합성 결과 — 프로덕션 판정 아님.** 샘플은 그래프 유리하게 설계된 합성 데이터다. 실문서 판정은 후속.

| 부류 | n | GraphRAG평균 | 벡터평균 | G승 | V승 | 무 |
|---|---|---|---|---|---|---|`;
  const rows = Object.entries(agg.byClass).map(([c, a]) =>
    `| ${c} | ${a.n} | ${a.graphragAvg.toFixed(2)} | ${a.vectorAvg.toFixed(2)} | ${a.graphragWins} | ${a.vectorWins} | ${a.ties} |`);
  const o = agg.overall;
  const overall = `| **전체** | ${o.n} | ${o.graphragAvg.toFixed(2)} | ${o.vectorAvg.toFixed(2)} | ${o.graphragWins} | ${o.vectorWins} | ${o.ties} |`;
  const detail = results.map((r) => `- [${r.class}] ${r.id}: G=${r.graphragScore} V=${r.vectorScore} → ${r.winner} (${r.rationale})`).join('\n');
  return `${head}\n${rows.join('\n')}\n${overall}\n\n## 문항별\n${detail}\n`;
}
