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

  it('judge 는 complete 를 빈 문자열이 아닌 question 을 userText(stdin)로 호출한다', async () => {
    // 회귀: CLI 기반 complete()는 userText 를 stdin 으로 전달하는데, claude -p 는 빈 stdin 을
    // 거부해 라이브 실행이 즉시 실패했다(단위테스트는 complete 를 mock 하므로 이 결함을 못 잡았음).
    const complete = vi.fn().mockResolvedValue('```json\n{"scoreA":1,"scoreB":1,"winner":"tie","rationale":"x"}\n```');
    await judge(complete, 'q', 'docs', 'a', 'b');
    expect(complete).toHaveBeenCalledWith(expect.any(String), 'q');
  });
});
