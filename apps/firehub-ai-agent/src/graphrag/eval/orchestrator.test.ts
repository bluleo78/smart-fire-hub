import { it, expect, vi } from 'vitest';
import { runEval } from './orchestrator.js';

it('순서 스왑을 해소해 winner 를 graphrag/vector 로 정규화한다', async () => {
  const questions = [
    { id: 'q0', question: 'Q0', class: 'multihop' as const }, // index 0 → graphrag=A
    { id: 'q1', question: 'Q1', class: 'lookup' as const },   // index 1 → graphrag=B
  ];
  const deps = {
    graphragContext: vi.fn().mockResolvedValue(['G']),
    vectorContext: vi.fn().mockResolvedValue(['V']),
    // 답변: 컨텍스트를 그대로 반영해 구분 가능하게
    complete: vi.fn()
      // q0 answer A(graphrag), answer B(vector), judge → winner A
      .mockResolvedValueOnce('graphrag답').mockResolvedValueOnce('벡터답')
      .mockResolvedValueOnce('```json\n{"scoreA":5,"scoreB":2,"winner":"A","rationale":"r"}\n```')
      // q1 answer A(vector, 스왑), answer B(graphrag), judge → winner B
      .mockResolvedValueOnce('벡터답').mockResolvedValueOnce('graphrag답')
      .mockResolvedValueOnce('```json\n{"scoreA":2,"scoreB":5,"winner":"B","rationale":"r"}\n```'),
  };
  const res = await runEval(deps, questions, '원문');
  // q0: graphrag=A, judge A승 → graphrag 승; q1: graphrag=B, judge B승 → graphrag 승
  expect(res[0].winner).toBe('graphrag');
  expect(res[1].winner).toBe('graphrag');
  expect(res[0].graphragScore).toBe(5);
  expect(res[1].graphragScore).toBe(5); // B슬롯이 graphrag였으므로 scoreB=5가 graphragScore
});
