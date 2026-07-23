import { describe, it, expect, vi } from 'vitest';
import { buildJudgePrompt, parseJudgeVerdict, judge } from './judge.js';

describe('judge', () => {
  it('심판 프롬프트에 원문·두 답변·JSON 판정 지침이 포함', () => {
    const p = buildJudgePrompt('질문?', '원문내용', '답A', '답B');
    expect(p).toContain('원문내용');
    expect(p).toContain('답A');
    expect(p).toContain('답B');
    expect(p).toMatch(/scoreA|winner/); // JSON 판정 스키마 안내
  });

  it('parseJudgeVerdict 는 JSON 코드블록을 파싱', () => {
    const v = parseJudgeVerdict('```json\n{"scoreA":4,"scoreB":2,"winner":"A","rationale":"근거충실"}\n```');
    expect(v).toEqual({ scoreA: 4, scoreB: 2, winner: 'A', rationale: '근거충실' });
  });

  it('parseJudgeVerdict 는 파싱 실패 시 tie 폴백', () => {
    const v = parseJudgeVerdict('설명만 있고 JSON 없음');
    expect(v.winner).toBe('tie');
  });

  it('judge 는 complete 결과를 파싱해 반환', async () => {
    const complete = vi.fn().mockResolvedValue('```json\n{"scoreA":3,"scoreB":5,"winner":"B","rationale":"x"}\n```');
    const v = await judge(complete, 'q', 'docs', 'a', 'b');
    expect(v.winner).toBe('B');
  });
});
