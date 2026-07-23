import { describe, it, expect, vi } from 'vitest';
import { buildAnswerPrompt, answerFromContext } from './answer.js';

it('프롬프트에 컨텍스트 블록과 근거-한정 지침이 포함된다', () => {
  const p = buildAnswerPrompt(['블록A', '블록B']);
  expect(p).toContain('블록A');
  expect(p).toContain('블록B');
  expect(p).toMatch(/제공된 (컨텍스트|근거)/); // 근거-한정
});

it('answerFromContext 는 complete 를 systemPrompt+question 으로 호출한다', async () => {
  const complete = vi.fn().mockResolvedValue('답변');
  const out = await answerFromContext(complete, '질문?', ['ctx']);
  expect(out).toBe('답변');
  expect(complete).toHaveBeenCalledWith(expect.stringContaining('ctx'), '질문?');
});
