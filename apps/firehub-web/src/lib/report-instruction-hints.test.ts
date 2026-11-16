import { describe, expect, it } from 'vitest';

import {
  findUnsupportedValueWords,
  formatUnsupportedValueHint,
  REPORT_NUMBER_SOURCE_GUIDE,
} from './report-instruction-hints';

describe('findUnsupportedValueWords (WD-774)', () => {
  it('주의 낱말이 없으면 빈 배열을 돌려준다', () => {
    expect(findUnsupportedValueWords('이번 주 처리 건수를 요약하세요.')).toEqual([]);
  });

  it('주의 낱말 하나를 찾는다', () => {
    expect(findUnsupportedValueWords('다음 달 전망을 적어 주세요.')).toEqual(['전망']);
  });

  it('「목표 대비 달성률」에서 목표·달성률을 찾는다', () => {
    expect(findUnsupportedValueWords('목표 대비 달성률')).toEqual(['목표', '달성률']);
  });

  it('여러 낱말은 지시에서 처음 나온 순서대로, 중복 없이 돌려준다', () => {
    // 낱말 목록 순서(목표→전망→달성률→예측)가 아니라 지시의 순서를 따른다 — 문장을 넘어서도 마찬가지다
    expect(findUnsupportedValueWords('예측과 달성률을 보여 줘. 그리고 목표와 목표치, 다시 예측')).toEqual([
      '예측',
      '달성률',
      '목표',
    ]);
  });

  it('다른 낱말 안에 들어간 경우(목표치)도 잡는다', () => {
    expect(findUnsupportedValueWords('월 목표치를 함께 표시')).toEqual(['목표']);
  });

  it('빈 문자열·공백뿐인 지시는 빈 배열을 돌려준다', () => {
    expect(findUnsupportedValueWords('')).toEqual([]);
    expect(findUnsupportedValueWords('   \n\t')).toEqual([]);
  });

  it('금지하는 문장(「~는 쓰지 마세요」)의 낱말은 주의하지 않는다', () => {
    expect(findUnsupportedValueWords('계산한 값·목표·전망 수치는 쓰지 마세요')).toEqual([]);
    expect(findUnsupportedValueWords('예측값은 제외하고 실적만 요약')).toEqual([]);
    expect(findUnsupportedValueWords('목표 대비 비교는 하지 않는다')).toEqual([]);
  });

  it('한 문장은 목표를 요구하고 다른 문장은 전망을 금지하면 목표만 잡는다', () => {
    expect(findUnsupportedValueWords('월 목표 대비 실적을 표로 정리하세요. 전망 수치는 쓰지 마세요.')).toEqual(['목표']);
    // 줄바꿈·물음표·느낌표도 문장 경계다
    expect(findUnsupportedValueWords('전망 금지\n목표를 함께 보여 줄까?')).toEqual(['목표']);
  });

  it('영어 지시의 target·goal·forecast·projection 을 대소문자 없이 소문자 낱말로 찾는다', () => {
    expect(findUnsupportedValueWords('Compare sales against the monthly TARGET and the Q3 Forecast.')).toEqual([
      'target',
      'forecast',
    ]);
    expect(findUnsupportedValueWords('Show goal attainment and revenue projection')).toEqual(['goal', 'projection']);
    expect(findUnsupportedValueWords('Summarize weekly order counts.')).toEqual([]);
  });
});

describe('formatUnsupportedValueHint (WD-774)', () => {
  it('찾은 낱말을 「」로 감싸 · 로 잇고, WD-767 결과(데이터 없음·출처 미확인 경고)로 알린다', () => {
    expect(formatUnsupportedValueHint(['목표', '달성률'])).toBe(
      '지시에 「목표」·「달성률」 같은 값이 있습니다 — 조회 결과에 그 값이 없으면 「데이터 없음」으로 나오거나 출처 미확인 경고가 붙습니다.',
    );
    expect(formatUnsupportedValueHint(['forecast'])).toBe(
      '지시에 「forecast」 같은 값이 있습니다 — 조회 결과에 그 값이 없으면 「데이터 없음」으로 나오거나 출처 미확인 경고가 붙습니다.',
    );
  });

  it('낱말이 없으면 빈 문자열을 돌려준다', () => {
    expect(formatUnsupportedValueHint([])).toBe('');
  });
});

describe('REPORT_NUMBER_SOURCE_GUIDE (WD-774)', () => {
  it('수치 출처·데이터 없음·출처 미확인 경고·SQL 계산 안내를 담는다', () => {
    expect(REPORT_NUMBER_SOURCE_GUIDE).toContain('조회(SQL) 결과');
    expect(REPORT_NUMBER_SOURCE_GUIDE).toContain('데이터 없음');
    expect(REPORT_NUMBER_SOURCE_GUIDE).toContain('출처 미확인');
    expect(REPORT_NUMBER_SOURCE_GUIDE).toContain('SQL 에서 계산');
  });
});
