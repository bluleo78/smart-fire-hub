import { describe, expect, it } from 'vitest';

import { checkReportNumbers, collectSourceNumbers, createToolNameResolver, isDataQueryTool } from './report-number-check.js';

const SRC = '[{"amount":350000000,"cnt":1234,"rate":0.123}]';

/** 출처 텍스트들을 라우트처럼 하나씩 누적기에 모은 뒤 대조한다. */
function run(report: string, sources: string[] = [SRC], allowed: string[] = []) {
  const sourceNumbers = new Set<number>();
  for (const t of sources) collectSourceNumbers(t, sourceNumbers);
  return checkReportNumbers({ reportTexts: [report], sourceNumbers, allowedTexts: allowed });
}

/** 출처 텍스트 하나에서 모은 숫자 집합 (출처 숫자 범위 시험용) */
function sourceSet(text: string): Set<number> {
  const out = new Set<number>();
  collectSourceNumbers(text, out);
  return out;
}

describe('checkReportNumbers', () => {
  it('출처와 맞는 단위·쉼표·% 표기는 통과한다', () => {
    const r = run('가중 3.5억 원, 1,234건, 전환율 12.3%');
    expect(r.checked).toBe(true);
    expect(r.unverifiedTotal).toBe(0);
  });

  it('출처에 없는 수치는 문맥과 함께 경고한다', () => {
    const r = run('가중 4.5억');
    expect(r.unverified).toHaveLength(1);
    expect(r.unverified[0].text).toBe('4.5억');
    expect(r.unverified[0].context).toContain('가중');
  });

  it('출처에 없는 100억·30% 는 모두 경고한다', () => {
    const r = run('연 100억 원 목표, 30% 이상');
    expect(r.unverified.map((u) => u.text)).toEqual(['100억', '30%']);
  });

  it('날짜·서수·분기·작은 정수·시각은 건너뛴다', () => {
    const r = run('2026년 11월 16일, 2026-11-16, 1위, Q3, 3개 섹션, 14:30');
    expect(r.unverifiedTotal).toBe(0);
  });

  it('allowedTexts 에 같은 표기가 있으면 통과한다', () => {
    expect(run('상위 15개', [SRC], ['상위 15개']).unverifiedTotal).toBe(0);
    expect(run('상위 15개').unverifiedTotal).toBe(1);
  });

  it('표시 소수 자릿수로 반올림해 비교한다', () => {
    expect(run('3.14', ['3.14159']).unverifiedTotal).toBe(0);
    expect(run('3.15', ['3.14159']).unverifiedTotal).toBe(1);
    expect(run('123만', ['1234567']).unverifiedTotal).toBe(0);
  });

  it('부호는 보지 않는다', () => {
    expect(run('3 감소 폭 33', ['-3', '33']).unverifiedTotal).toBe(0);
    expect(run('30 감소', ['-30']).unverifiedTotal).toBe(0);
  });

  it('같은 표기는 한 번만 올리고 목록은 20개로 자른다', () => {
    expect(run('4.5억 그리고 4.5억').unverified).toHaveLength(1);
    const many = Array.from({ length: 25 }, (_, i) => `${100 + i}억`).join(' ');
    const r = run(many);
    expect(r.unverified).toHaveLength(20);
    expect(r.unverifiedTotal).toBe(25);
  });

  it('출처(데이터 조회 결과)가 없으면 검증하지 않는다', () => {
    expect(run('4.5억', [])).toEqual({ checked: false, unverified: [], unverifiedTotal: 0 });
  });
});

describe('checkReportNumbers 보강', () => {
  it('단어의 일부인 만·천은 단위로 보지 않는다', () => {
    expect(run('1만큼 3천천히', ['1']).unverifiedTotal).toBe(0);
    expect(run('1만큼', ['1']).unverified).toEqual([]);
    expect(run('3.5억원', ['350000000']).unverifiedTotal).toBe(0);
  });

  it('짧은 날짜는 건너뛴다', () => {
    expect(run('11-16 그리고 11/16 와 3/4').unverifiedTotal).toBe(0);
  });

  it('숫자 없는 출처는 검증하지 않는다', () => {
    expect(run('4.5억', ['[]'])).toEqual({ checked: false, unverified: [], unverifiedTotal: 0 });
  });

  it('쉼표 천단위 출처 값을 읽는다', () => {
    expect(run('1,234건', ['{"v":"1,234"}']).unverifiedTotal).toBe(0);
  });
});

describe('checkReportNumbers 보강 2', () => {
  it('JSON 숫자 배열을 합치지 않는다', () => {
    expect(run('200건', ['[100,200,300]']).unverifiedTotal).toBe(0);
    expect(run('123', ['[123,456]']).unverifiedTotal).toBe(0);
    expect(run('1,234건', ['{"v":"1,234"}']).unverifiedTotal).toBe(0);
  });

  it('단위 붙은 범위는 날짜로 지우지 않는다', () => {
    expect(run('2-3만원', ['30000']).unverifiedTotal).toBe(0);
    expect(run('2-3만원', ['5']).unverified.map((u) => u.text)).toEqual(['3만']);
    expect(run('10-30%', ['30']).unverifiedTotal).toBe(0);
    expect(run('10-30%', ['5']).unverified.map((u) => u.text)).toEqual(['30%']);
  });

  it('복합 단위를 읽는다', () => {
    expect(run('5천만원', ['50000000']).unverifiedTotal).toBe(0);
    expect(run('5천만원', ['5']).unverified.map((u) => u.text)).toEqual(['5천만']);
  });
});

describe('checkReportNumbers 조사', () => {
  it('단위 뒤 조사는 단위로 읽는다', () => {
    expect(run('3.5억이고', ['350000000']).unverifiedTotal).toBe(0);
    expect(run('3.5억은', ['350000000']).unverifiedTotal).toBe(0);
    expect(run('3만의', ['30000']).unverifiedTotal).toBe(0);
    expect(run('5억은', ['5']).unverified.map((u) => u.text)).toEqual(['5억']);
  });
});

describe('checkReportNumbers 섞인 한글 수 표기', () => {
  it('큰 단위 뒤에 작은 단위 수가 붙으면 한 토큰으로 합친다', () => {
    expect(run('3만5천명', ['35000']).unverifiedTotal).toBe(0);
    expect(run('1억2천만원', ['120000000']).unverifiedTotal).toBe(0);
    expect(run('2조3천억', ['2300000000000']).unverifiedTotal).toBe(0);
  });

  it('합친 값이 출처와 다르면 합친 표기 하나로 경고한다', () => {
    expect(run('3만5천명', ['30000']).unverified.map((u) => u.text)).toEqual(['3만5천']);
    // 표시 자릿수는 가장 작은 단위(천만) 기준 정수 — 1.25억(12.5천만 → 13)은 1억2천만(12)과 다르다
    expect(run('1억2천만원', ['125000000']).unverified.map((u) => u.text)).toEqual(['1억2천만']);
  });
});

describe('checkReportNumbers 날짜·서수 접미 경계', () => {
  it('접미 글자로 시작하는 명사가 이어지면 대조한다', () => {
    expect(run('출동 1,250차량').unverified.map((u) => u.text)).toEqual(['1,250']);
    expect(run('점검 35시설').unverified.map((u) => u.text)).toEqual(['35']);
    expect(run('120일반 건물').unverified.map((u) => u.text)).toEqual(['120']);
    expect(run('출동 1,250차량', ['1250']).unverifiedTotal).toBe(0);
  });

  it('시도·일대는 「13시 도」「15일 대비」 같은 시각·날짜 표기라 대조하지 않는다 (M2)', () => {
    expect(run('13시도 혼잡').unverifiedTotal).toBe(0);
    expect(run('15일대비 증가').unverifiedTotal).toBe(0);
  });

  it('날짜·서수 표기는 지금처럼 건너뛴다', () => {
    expect(run('3일간 2차례 5시간 1위 3주차').unverifiedTotal).toBe(0);
    expect(run('30일간 12차례 15시간 11위 13주차 24등').unverifiedTotal).toBe(0);
  });
});

describe('collectSourceNumbers 출처 숫자 범위', () => {
  it('영문자·_ 에 붙은 숫자는 출처로 세지 않는다', () => {
    expect([...sourceSet('{"col_2023":"a1b2"}')]).toEqual([]);
    expect(sourceSet('{"id":"550e8400-e29b-41d4-a716-446655440000"}').has(446655440000)).toBe(false);
    expect(sourceSet('{"id":"550e8400-e29b-41d4-a716-446655440000"}').size).toBe(0);
  });

  it('음수 부호·날짜 각 부분·JSON 숫자는 지금처럼 센다', () => {
    expect(sourceSet('{"v":"-3.5","w": -12}')).toEqual(new Set([3.5, 12]));
    expect(sourceSet('{"d":"2026-11-16"}')).toEqual(new Set([2026, 11, 16]));
    expect(sourceSet('[{"amount":350000000,"rate":0.123,"e":1e5}]')).toEqual(new Set([350000000, 0.123, 100000]));
  });

  it('숫자 뒤에 단위 문자가 붙은 값·ISO 시각 부분은 출처로 센다 (M1)', () => {
    expect(sourceSet('{"d":"12km"}').has(12)).toBe(true);
    expect(sourceSet('{"t":"1200ms","s":"3.5GB"}')).toEqual(new Set([1200, 3.5]));
    expect(sourceSet('{"at":"2026-11-16T09:30:00Z"}')).toEqual(new Set([2026, 11, 16, 9, 30, 0]));
    expect(sourceSet('line\n1234').has(1234)).toBe(true);
    expect(run('거리 12km 이동', ['{"d":"12km"}']).unverifiedTotal).toBe(0);
  });

  it('UUID 의 영숫자 섞인 조각은 계속 제외한다 (M1)', () => {
    const s = sourceSet('{"id":"550e8400-e29b-41d4-a716-446655440000"}');
    expect(s.has(41)).toBe(false);
    expect(s.has(4)).toBe(false);
    expect(sourceSet('{"k":"a1b2"}').size).toBe(0);
  });

  it('출처에서 걸러진 UUID 조각은 리포트 수치의 출처가 되지 않는다', () => {
    expect(run('대상 446655440000건', ['{"id":"a716-446655440000","n":1}']).unverifiedTotal).toBe(1);
  });
});

describe('checkReportNumbers 대조 대상', () => {
  it('리포트 본문이 전부 비었거나 공백이면 검증하지 않는다', () => {
    const sourceNumbers = new Set([1]);
    expect(checkReportNumbers({ reportTexts: ['', '  \n'], sourceNumbers, allowedTexts: [] })).toEqual({
      checked: false,
      unverified: [],
      unverifiedTotal: 0,
    });
  });
});

describe('isDataQueryTool', () => {
  it('데이터 조회 도구만 true', () => {
    expect(isDataQueryTool('mcp__firehub__execute_analytics_query')).toBe(true);
    expect(isDataQueryTool('firehub_query_dataset_data')).toBe(true);
    expect(isDataQueryTool('execute_sql_query')).toBe(true);
    expect(isDataQueryTool('Write')).toBe(false);
    expect(isDataQueryTool('mcp__firehub__list_datasets')).toBe(false);
  });
});

describe('createToolNameResolver', () => {
  it('toolUseId 로 tool_use 와 결과를 짝짓는다', () => {
    const r = createToolNameResolver();
    r.onUse({ toolName: 'execute_sql_query', toolUseId: 'a' });
    r.onUse({ toolName: 'Write', toolUseId: 'b' });
    expect(r.resolve({ toolUseId: 'a' })).toBe('execute_sql_query');
    expect(r.resolve({ toolUseId: 'b' })).toBe('Write');
  });

  it('toolName 자리에 실린 tool_use_id 로도 짝짓는다', () => {
    const r = createToolNameResolver();
    r.onUse({ toolName: 'execute_sql_query', toolUseId: 'x1' });
    r.onUse({ toolName: 'Write', toolUseId: 'x2' });
    expect(r.resolve({ toolName: 'x1' })).toBe('execute_sql_query');
  });

  it('id 를 실었지만 모르는 호출이면 빈 문자열, id 가 전혀 없으면 직전 tool_use 이름', () => {
    const r = createToolNameResolver();
    r.onUse({ toolName: 'execute_sql_query' });
    expect(r.resolve({ toolUseId: 'unknown' })).toBe('');
    expect(r.resolve({})).toBe('execute_sql_query');
  });
});
