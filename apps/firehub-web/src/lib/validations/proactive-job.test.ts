import { describe, expect, it } from 'vitest';

import { MAX_JOB_PROMPT_LENGTH, proactiveJobSchema } from './proactive-job';

/**
 * 스마트 작업 이름·분석 프롬프트 공백 검증 (WD-500) — 공백만 채운 값은 빈 값과 같이 거부하고, 앞뒤 공백은 잘라 저장하는지 고정한다.
 */

/** 스키마를 통과하는 최소 작업 폼 값 — 이름·프롬프트만 바꿔 쓴다. */
const job = (name: string, prompt: string) => ({
  name,
  prompt,
  cronExpression: '0 9 * * *',
  timezone: 'Asia/Seoul',
  triggerType: 'SCHEDULE' as const,
  config: { channels: [] },
});

/** 실패 시 해당 경로의 첫 메시지를 꺼낸다. */
const messageAt = (
  result: { success: boolean; error?: { issues: { path: PropertyKey[]; message: string }[] } },
  key: string,
) => result.error?.issues.find((i) => i.path.includes(key))?.message;

describe('스마트 작업 이름·프롬프트 공백 검증 (WD-500)', () => {
  it.each([
    ['스페이스', '   '],
    ['탭', '\t\t'],
    ['줄바꿈', '\n \n'],
  ])('%s만 있는 이름·프롬프트는 빈 값 문구로 거부된다', (_label, blank) => {
    const r = proactiveJobSchema.safeParse(job(blank, blank));
    expect(r.success).toBe(false);
    expect(messageAt(r, 'name')).toBe('작업 이름을 입력하세요');
    expect(messageAt(r, 'prompt')).toBe('분석 프롬프트를 입력하세요');
  });

  it('앞뒤 공백은 잘라 낸 값으로 통과한다', () => {
    const r = proactiveJobSchema.safeParse(job('  일일 리포트  ', '\n 어제 결과를 요약하세요 \t'));
    expect(r.success).toBe(true);
    expect(r.data?.name).toBe('일일 리포트');
    expect(r.data?.prompt).toBe('어제 결과를 요약하세요');
  });

  it('정상 값은 그대로 통과한다', () => {
    const r = proactiveJobSchema.safeParse(job('일일 리포트', '어제 결과를 요약하세요'));
    expect(r.success).toBe(true);
    expect(r.data?.name).toBe('일일 리포트');
    expect(r.data?.prompt).toBe('어제 결과를 요약하세요');
  });

  it('기존 상한 문구는 그대로다 — 이름 200자, 프롬프트 10,000자', () => {
    expect(messageAt(proactiveJobSchema.safeParse(job('가'.repeat(201), '질문')), 'name')).toBe(
      '이름은 200자 이내여야 합니다',
    );
    expect(
      messageAt(proactiveJobSchema.safeParse(job('이름', 'x'.repeat(MAX_JOB_PROMPT_LENGTH + 1))), 'prompt'),
    ).toBe('분석 프롬프트는 10,000자 이내여야 합니다');
    // trim 후 길이로 상한을 본다 — 앞뒤 공백 때문에 상한을 넘었다고 거부하지 않는다
    expect(proactiveJobSchema.safeParse(job('이름', ` ${'x'.repeat(MAX_JOB_PROMPT_LENGTH)} `)).success).toBe(true);
  });
});
