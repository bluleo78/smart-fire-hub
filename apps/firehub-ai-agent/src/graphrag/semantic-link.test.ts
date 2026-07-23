import { describe, it, expect, vi } from 'vitest';
import { buildLinkPrompt, parseLinkVerdict, link } from './semantic-link.js';

describe('buildLinkPrompt', () => {
  it('두 이름과 엔티티 타입을 프롬프트에 포함', () => {
    const p = buildLinkPrompt('전기적 요인', '분전반의 누전', 'Cause');
    expect(p).toContain('전기적 요인');
    expect(p).toContain('분전반의 누전');
    expect(p).toContain('Cause');
    expect(p).toMatch(/same/); // JSON 판정 스키마 안내
  });
});

describe('parseLinkVerdict', () => {
  it('same:true 를 파싱', () => {
    expect(parseLinkVerdict('```json\n{"same":true,"rationale":"동일 원인"}\n```')).toEqual({ same: true, rationale: '동일 원인' });
  });

  it('same:false 를 파싱', () => {
    expect(parseLinkVerdict('```json\n{"same":false,"rationale":"별개"}\n```')).toEqual({ same: false, rationale: '별개' });
  });

  it('JSON 코드블록 없이 순수 JSON만 와도 파싱', () => {
    expect(parseLinkVerdict('{"same":true,"rationale":"x"}')).toEqual({ same: true, rationale: 'x' });
  });

  it('파싱 실패 시 false 로 안전 폴백', () => {
    expect(parseLinkVerdict('설명만 있고 JSON 없음')).toEqual({ same: false, rationale: '' });
  });

  it('parseLinkVerdict는 rationale을 함께 반환한다', () => {
    const text = '```json\n{"same":true,"rationale":"둘 다 분전반 누전을 가리킴"}\n```';
    expect(parseLinkVerdict(text)).toEqual({ same: true, rationale: '둘 다 분전반 누전을 가리킴' });
  });
});

describe('link', () => {
  it('complete 결과를 파싱해 반환', async () => {
    const complete = vi.fn().mockResolvedValue('```json\n{"same":true,"rationale":"x"}\n```');
    const result = await link(complete, '전기적 요인', '분전반의 누전', 'Cause');
    expect(result).toEqual({ same: true, rationale: 'x' });
  });

  it('complete 를 빈 문자열이 아닌 nameA 를 userText(stdin)로 호출한다', async () => {
    // judge.ts 에서 실측된 빈 stdin 거부 버그를 반복하지 않기 위한 회귀 검증.
    const complete = vi.fn().mockResolvedValue('```json\n{"same":false,"rationale":"x"}\n```');
    await link(complete, '전기적 요인', '분전반의 누전', 'Cause');
    expect(complete).toHaveBeenCalledWith(expect.any(String), '전기적 요인');
  });

  it('complete 가 예외를 던지면 false 를 반환하고 전파하지 않는다', async () => {
    const complete = vi.fn().mockRejectedValue(new Error('CLI 종료 코드 1'));
    const result = await link(complete, '전기적 요인', '분전반의 누전', 'Cause');
    expect(result).toEqual({ same: false, rationale: '' });
  });
});
