// 평가 오케스트레이터 — 질문마다 양 경로 답변 생성, 순서 스왑 후 심판, graphrag/vector 관점으로 정규화.
// deps 주입으로 라이브 의존(retrieve/searchDocuments/LLM) 없이 유닛테스트 가능.
import type { CompleteFn } from '../llm-cli.js';
import { EvalQuestion, EvalResult } from './types.js';
import { answerFromContext } from './answer.js';
import { judge } from './judge.js';

export interface EvalDeps {
  graphragContext(question: string): Promise<string[]>; // retrieve → 컨텍스트 블록
  vectorContext(question: string): Promise<string[]>;   // searchDocuments → 컨텍스트 블록
  complete: CompleteFn;
}

// 문항 처리 단계 진행 알림(선택) — 라이브 CLI에서 진행 상황을 실시간으로 보여주기 위함. 순수 로직에는 영향 없음.
export type EvalProgress = (event: { index: number; total: number; id: string; stage: 'context' | 'answer' | 'judge' | 'done' }) => void;

export async function runEval(
  deps: EvalDeps, questions: EvalQuestion[], sourceDocs: string, onProgress?: EvalProgress,
): Promise<EvalResult[]> {
  const out: EvalResult[] = [];
  for (let i = 0; i < questions.length; i++) {
    const q = questions[i];
    const total = questions.length;
    onProgress?.({ index: i, total, id: q.id, stage: 'context' });
    const [gCtx, vCtx] = await Promise.all([deps.graphragContext(q.question), deps.vectorContext(q.question)]);
    onProgress?.({ index: i, total, id: q.id, stage: 'answer' });
    const [gAns, vAns] = await Promise.all([
      answerFromContext(deps.complete, q.question, gCtx),
      answerFromContext(deps.complete, q.question, vCtx),
    ]);
    // 결정적 순서 스왑: 짝수 index → graphrag=A, 홀수 → graphrag=B (위치편향 완화).
    const graphragIsA = i % 2 === 0;
    const answerA = graphragIsA ? gAns : vAns;
    const answerB = graphragIsA ? vAns : gAns;
    onProgress?.({ index: i, total, id: q.id, stage: 'judge' });
    const v = await judge(deps.complete, q.question, sourceDocs, answerA, answerB);
    // 슬롯을 graphrag/vector 관점으로 정규화.
    const graphragScore = graphragIsA ? v.scoreA : v.scoreB;
    const vectorScore = graphragIsA ? v.scoreB : v.scoreA;
    let winner: EvalResult['winner'] = 'tie';
    if (v.winner !== 'tie') {
      const graphragWon = (v.winner === 'A') === graphragIsA;
      winner = graphragWon ? 'graphrag' : 'vector';
    }
    out.push({ id: q.id, class: q.class, graphragAnswer: gAns, vectorAnswer: vAns, graphragScore, vectorScore, winner, rationale: v.rationale });
    onProgress?.({ index: i, total, id: q.id, stage: 'done' });
  }
  return out;
}
