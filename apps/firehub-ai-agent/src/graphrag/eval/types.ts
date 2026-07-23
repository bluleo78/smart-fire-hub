// A1 GraphRAG 평가 하네스 공유 타입 — 전 태스크(질문셋/답변/심판/집계/오케스트레이션)가 소비한다.

// 질문 부류: multihop/relationship = 교차문서 조인(그래프 유리), lookup = 단일문서 사실(벡터 유리), attribute = 속성 기반.
export type QuestionClass = 'multihop' | 'relationship' | 'lookup' | 'attribute';

// 평가 질문 1건.
export interface EvalQuestion {
  id: string;
  question: string;
  class: QuestionClass;
  note?: string;
}

// LLM 심판의 원시 판정 결과(위치 스왑 전 A/B 슬롯 기준).
export interface JudgeVerdict {
  scoreA: number;
  scoreB: number;
  winner: 'A' | 'B' | 'tie';
  rationale: string;
}

// 한 질문의 평가 결과 — 슬롯 스왑을 해소해 graphrag/vector 관점으로 정규화한 값.
export interface EvalResult {
  id: string;
  class: QuestionClass;
  graphragAnswer: string;
  vectorAnswer: string;
  graphragScore: number;
  vectorScore: number;
  winner: 'graphrag' | 'vector' | 'tie';
  rationale: string;
}
