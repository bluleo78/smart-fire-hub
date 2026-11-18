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

// WD-775: 오탐 6·누락 2·성능 1 다듬기

/** WD-775: 대조해 경고된 수치 표기만 돌려준다 (아래 시험들의 반복을 줄인다). */
function unverifiedTexts(...args: Parameters<typeof run>): string[] {
  return run(...args).unverified.map((u) => u.text);
}

describe('WD-775 데이터 조회 도구 범위', () => {
  it('저장 쿼리 실행·차트 데이터·행 개수 도구 결과도 출처다', () => {
    expect(isDataQueryTool('mcp__firehub__run_saved_query')).toBe(true);
    expect(isDataQueryTool('mcp__firehub__get_chart_data')).toBe(true);
    expect(isDataQueryTool('firehub_get_row_count')).toBe(true);
  });

  it('행 검색 도구(search_dataset_rows) 결과도 원본 행 값이라 출처다', () => {
    expect(isDataQueryTool('mcp__firehub__search_dataset_rows')).toBe(true);
  });

  it('파일 데이터셋 요약(총 개수·용량)·그래프 구조 질의(속성값) 결과도 출처다', () => {
    expect(isDataQueryTool('mcp__firehub__summarize_dataset_files')).toBe(true);
    expect(isDataQueryTool('mcp__firehub__graphrag_structured_query')).toBe(true);
  });
});

describe('WD-775 허용 텍스트 값 비교', () => {
  it('context 의 1234 는 리포트 「1,234개」를 허용한다', () => {
    expect(run('대상 1,234개', ['{"n":1}'], ['{"total":1234}']).unverifiedTotal).toBe(0);
  });

  it('context 의 0.5 는 리포트 「50%」를, 350000000 은 「3.5억」을 허용한다', () => {
    expect(run('비율 50%, 매출 3.5억', ['{"n":1}'], ['{"r":0.5,"amt":350000000}']).unverifiedTotal).toBe(0);
  });

  it('허용 숫자만 있고 출처 숫자가 없으면 여전히 검증하지 않는다', () => {
    expect(run('대상 1,234개', ['[]'], ['{"total":1234}']).checked).toBe(false);
  });

  it('허용 텍스트는 날 글자로 읽어 따옴표 밖 「1,234」도 1234 한 값이다', () => {
    expect(run('대상 1234건', ['{"n":1}'], ['1,234']).unverifiedTotal).toBe(0);
    expect(unverifiedTexts('대상 1,234건 분석 결과 234건', ['{"n":1}'], ['대상 1,234건 분석'])).toEqual(['234']);
  });

  it('허용 텍스트의 단위·% 표기는 그 값으로 허용한다', () => {
    expect(run('매출 350,000,000원, 비율 12.5%', ['{"n":1}'], ['목표 3.5억, 비율 12.5%']).unverifiedTotal).toBe(0);
  });

  it('같은 표기도 값 비교로 허용된다', () => {
    expect(run('상위 15개', [SRC], ['상위 15개']).unverifiedTotal).toBe(0);
  });
});

describe('WD-775 「조」 단위 판정', () => {
  it('「제3조」 조문 번호는 경고하지 않는다', () => {
    expect(run('근거는 제13조와 제3조의12 이다', ['{"n":1}']).unverifiedTotal).toBe(0);
  });

  it('「제 13조」처럼 제 뒤에 공백이 있어도 조문 번호로 가린다', () => {
    expect(run('근거는 제 13조와 제 15조의12', ['{"n":1}']).unverifiedTotal).toBe(0);
    // 「제」가 낱말 끝(규제)이면 조문 번호가 아니라 조(兆) 금액이다
    expect(unverifiedTexts('규제 3조원 절감', ['{"n":1}'])).toEqual(['3조']);
  });

  it('「2조 편성」「2조 2교대」「3조로 나누어」처럼 작은 정수 + 조(組) 쓰임은 조 단위로 읽지 않는다', () => {
    expect(run('2조 편성, 2조 2교대, 3조로 나누어 운영', ['{"n":12}']).unverifiedTotal).toBe(0);
  });

  it('「추경 15조 편성」「1.5조 편성」은 조(兆) 예산이다 — 10 넘는 수·소수는 조(組) 예외를 쓰지 않는다', () => {
    expect(run('추경 15조 편성', ['15000000000000']).unverifiedTotal).toBe(0);
    expect(unverifiedTexts('추경 15조 편성', ['15'])).toEqual(['15조']);
    expect(run('추경 1.5조 편성', ['1500000000000']).unverifiedTotal).toBe(0);
    expect(unverifiedTexts('추경 1.5조 편성', ['15'])).toEqual(['1.5조']);
  });

  it('「3조 원」「1.2조」「3조5천억」은 여전히 조 단위로 읽는다', () => {
    expect(run('예산 3조 원', ['3000000000000']).unverifiedTotal).toBe(0);
    expect(unverifiedTexts('예산 3조 원', ['3'])).toEqual(['3조']);
    expect(run('1.2조', ['1200000000000']).unverifiedTotal).toBe(0);
    expect(unverifiedTexts('1.2조', ['12'])).toEqual(['1.2조']);
    expect(run('3조5천억', ['3500000000000']).unverifiedTotal).toBe(0);
    expect(unverifiedTexts('3조가 투입', ['3'])).toEqual(['3조']);
    expect(unverifiedTexts('예산 2조로 늘어', ['2'])).toEqual(['2조']);
    expect(unverifiedTexts('3조원', ['3'])).toEqual(['3조']);
  });
});

describe('WD-775 목차 번호', () => {
  it('줄 첫머리의 「11. 권고사항」「## 12. 부록」은 번호라 건너뛴다', () => {
    expect(run('11. 권고사항\n## 12. 부록\n  13. 기타', ['{"n":1}']).unverifiedTotal).toBe(0);
  });

  it('줄 첫머리 다단계 절 번호 「## 3.1 출동 현황」「### 2.4 소결」「12.3. 기타」는 건너뛴다', () => {
    expect(run('## 3.1 출동 현황\n### 2.4 소결\n12.3. 기타\n  4.10.2 세부', ['{"n":1}']).unverifiedTotal).toBe(0);
  });

  it('줄 첫머리 「3.5억 원」「12.5% 증가」「3.5 억」과 문장 안 「3.1」은 계속 대조한다', () => {
    expect(unverifiedTexts('3.5억 원\n12.5% 증가\n13.5 억\n증가율 3.1 수준', ['{"n":1}'])).toEqual([
      '3.5억',
      '12.5%',
      '13.5',
      '3.1',
    ]);
  });

  it('소수·퍼센트·문장 안 「11. 」은 그대로 대조한다', () => {
    expect(unverifiedTexts('11.5%\n증가율 11.5', ['{"n":1}'])).toEqual(['11.5%', '11.5']);
    expect(unverifiedTexts('증가는 11. 그리고', ['{"n":1}'])).toEqual(['11']);
  });
});

describe('WD-775 분수·범위는 날짜로 가리지 않는다', () => {
  it('개수 단위가 바로 붙은 「10/20건」「3-15건」은 대조한다', () => {
    expect(unverifiedTexts('처리 10/20건', ['{"n":1}'])).toEqual(['20']);
    expect(unverifiedTexts('범위 3-15건', ['{"n":1}'])).toEqual(['15']);
    expect(run('처리 10/20건', ['20']).unverifiedTotal).toBe(0);
  });

  it('단위 없는 짧은 날짜는 지금처럼 가린다', () => {
    expect(run('11/16 기준, 3-15 사이, 11/16대비 증가, 11/16회의', ['{"n":1}']).unverifiedTotal).toBe(0);
  });
});

describe('WD-775 tool_result 짝짓기', () => {
  it('모르는 id·\'unknown\' 은 직전 도구 이름으로 넘어가지 않아 출처가 아니다', () => {
    const r = createToolNameResolver();
    r.onUse({ toolName: 'mcp__firehub__execute_sql_query', toolUseId: 'toolu_a' });
    expect(r.resolve({ toolName: 'toolu_x', toolUseId: 'toolu_x' })).toBe('');
    expect(isDataQueryTool(r.resolve({ toolName: 'toolu_x' }))).toBe(false);
    expect(isDataQueryTool(r.resolve({ toolName: 'unknown' }))).toBe(false);
    expect(r.resolve({ toolName: 'toolu_a', toolUseId: 'toolu_a' })).toBe('mcp__firehub__execute_sql_query');
  });

  it('id 도 이름 자리 값도 없으면 직전 도구 이름이다', () => {
    const r = createToolNameResolver();
    r.onUse({ toolName: 'mcp__firehub__execute_sql_query', toolUseId: 'toolu_a' });
    expect(r.resolve({})).toBe('mcp__firehub__execute_sql_query');
    expect(r.resolve({ toolName: '' })).toBe('mcp__firehub__execute_sql_query');
  });

  it('실제 도구 이름을 싣는 공급자(opencode, callID 없음)는 그 이름으로 판정한다', () => {
    const r = createToolNameResolver();
    r.onUse({ toolName: 'mcp__firehub__execute_sql_query' });
    expect(r.resolve({ toolName: 'mcp__firehub__execute_sql_query' })).toBe('mcp__firehub__execute_sql_query');
    r.onUse({ toolName: 'mcp__firehub__list_datasets' });
    expect(r.resolve({ toolName: 'mcp__firehub__list_datasets' })).toBe('mcp__firehub__list_datasets');
  });
});

describe('WD-775 대조 성능', () => {
  // 벽시계 단정이 부하에 흔들리지 않도록(WD-235 유형) 작업량을 크게 잡고 한도는 넉넉히 둔다 — 토큰마다 출처 전체를
  // 훑던 예전 방식은 이 작업량(출처 20만 × 토큰 2만 1천)에서 실측 약 15초로 한도를 크게 넘고, 색인 방식은 약 0.1초라
  // 부하로 수십 배 느려져도 한도 안이다.
  it('출처 20만 개 + 서로 다른 토큰 2만여 개를 3초 안에 대조한다', () => {
    const sourceNumbers = new Set<number>();
    for (let i = 0; i < 200_000; i++) sourceNumbers.add(i * 7 + 0.123);
    const parts: string[] = [];
    for (let i = 0; i < 7000; i++) parts.push(`${1000 + i}.${i % 10}`, `${2000 + i}억`, `${i + 11}.5%`);
    const report = parts.join(' ');
    const t0 = performance.now();
    const r = checkReportNumbers({ reportTexts: [report], sourceNumbers, allowedTexts: [] });
    const elapsed = performance.now() - t0;
    expect(r.checked).toBe(true);
    expect(elapsed).toBeLessThan(3000);
  });

  it('퍼센트·배수·자릿수 조합을 섞지 않는다', () => {
    // 0.123 → 12.3% 또는 0.123%, 350000000 → 3.5억 은 맞고, 3.5% 는 아니다
    expect(run('12.3% 0.12% 3.5억').unverifiedTotal).toBe(0);
    expect(unverifiedTexts('3.5%')).toEqual(['3.5%']);
    expect(run('35000만').unverifiedTotal).toBe(0);
    expect(run('3.50억').unverifiedTotal).toBe(0);
    expect(unverifiedTexts('3.6억')).toEqual(['3.6억']);
  });
});

describe('WD-783 줄 첫머리 소수', () => {
  // htmlToText 는 블록 태그를 줄바꿈으로 바꾸므로 「<td>4.5 시간</td>」「<p>3.2 배 증가</p>」는 줄 첫머리 소수가 된다
  it('표지 없는 2단계 「4.5 시간」「3.2 배 증가」는 소수로 대조한다', () => {
    expect(unverifiedTexts('\n4.5 시간\n\n3.2 배 증가\n', ['{"n":1}'])).toEqual(['4.5', '3.2']);
  });

  it('「# 」 표지·끝 마침표·3단계 이상 번호는 여전히 절 번호로 가린다', () => {
    expect(run('## 3.1 출동 현황\n12.3. 기타\n4.10.2 세부\n### 2.4 소결', ['{"n":1}']).unverifiedTotal).toBe(0);
  });
});

describe('WD-783 허용 값은 반올림하지 않고 같은 값만 허용', () => {
  it('허용 텍스트의 datasetId 1024 는 리포트 「1천명」을 허용하지 않는다', () => {
    expect(unverifiedTexts('인원 1천명', ['{"n":5}'], ['datasetId 1024'])).toEqual(['1천']);
  });

  it('같은 값의 다른 표기(「1234」 → 「1,234개」)와 「12.5%」는 허용한다', () => {
    expect(run('대상 1,234개', ['{"n":5}'], ['1234']).unverifiedTotal).toBe(0);
    expect(run('비율 12.5%', ['{"n":5}'], ['12.5%']).unverifiedTotal).toBe(0);
  });
});

describe('checkReportNumbers — 단위 값 출처·계산한 증감률 (WD-781)', () => {
  const UNIT_SRC = '[{"올해억":1.3,"전년동기억":0.3},{"올해억":11.7,"전년동기억":7.7}]';
  const HOURS_SRC = '[{"전년":111.8,"올해":28.8}]';

  it('이미 억 단위로 집계된 출처 값과 같은 줄 확인된 두 값으로 계산한 증감률은 경고하지 않는다 (이슈 재현)', () => {
    const r = run(
      '- 구독 전년 동기 0.3억 → 1.3억\n- 기술 서비스 7.7억 → 11.7억\n- 평균 해결 시간 111.8 → 28.8시간 (-74.2%)',
      [UNIT_SRC, HOURS_SRC],
    );
    expect(r.checked).toBe(true);
    expect(r.unverified).toEqual([]);
    expect(r.unverifiedTotal).toBe(0);
  });

  it('같은 줄 확인된 두 값의 비율(b/a)도 허용한다', () => {
    // 28.8 / 111.8 × 100 = 25.76… → 25.8%
    const r = run('올해 28.8시간은 전년 111.8시간의 25.8% 수준', [HOURS_SRC]);
    expect(r.unverifiedTotal).toBe(0);
  });

  it('복합 단위 실제 값으로 증감률을 계산한다', () => {
    // 1억2천만 → 1억8천만: (1.8e8 − 1.2e8) / 1.2e8 = 50%
    const r = run('매출 1억2천만 → 1억8천만 (+50%)', ['[120000000,180000000]']);
    expect(r.unverifiedTotal).toBe(0);
  });

  it('틀린 증감률은 여전히 경고한다', () => {
    const r = run('평균 해결 시간 111.8 → 28.8시간 (-70.0%)', [HOURS_SRC]);
    expect(r.unverified.map((u) => u.text)).toEqual(['70.0%']);
  });

  it('출처로 확인되지 않은 수치끼리 만든 비율은 경고한다', () => {
    // 50·200 은 출처에 없으므로 300.0% 를 계산값으로 인정하지 않는다
    const r = run('지표 50 → 200 (+300.0%)', [HOURS_SRC]);
    expect(r.unverified.map((u) => u.text)).toEqual(['50', '200', '300.0%']);
  });

  it('다른 줄의 값으로 만든 증감률은 경고한다', () => {
    const r = run('전년 111.8시간\n올해 28.8시간 (-74.2%)', [HOURS_SRC]);
    expect(r.unverified.map((u) => u.text)).toEqual(['74.2%']);
  });

  it('줄에 확인된 수치가 하나뿐이거나 0 인 값만 있으면 증감률로 인정하지 않는다', () => {
    expect(run('올해 28.8시간 (-74.2%)', [HOURS_SRC]).unverified.map((u) => u.text)).toEqual(['74.2%']);
    // a=0 이면 나눗셈을 하지 않는다 (0 → 28.8 의 증감률은 정의되지 않음). 반대 방향(28.8 → 0)의 -100%·0% 만 맞는다.
    expect(run('0 → 28.8시간 (+50.0%)', ['[0,28.8]']).unverified.map((u) => u.text)).toEqual(['50.0%']);
  });

  it('같은 값 쌍(a=b)으로는 0%·100% 를 계산값으로 인정하지 않는다 — 같은 값이 둘만 있으면 어떤 0%·100% 든 통과해서 (WD-788)', () => {
    expect(run('전년 28.8시간 → 올해 28.8시간 (0.0%)', [HOURS_SRC]).unverified.map((u) => u.text)).toEqual(['0.0%']);
    expect(run('전년 28.8시간 → 올해 28.8시간 (100.0%)', [HOURS_SRC]).unverified.map((u) => u.text)).toEqual(['100.0%']);
    expect(run('전년 28.8시간 → 올해 28.8시간 (0%)', [HOURS_SRC]).unverified.map((u) => u.text)).toEqual(['0%']);
    // 값이 다른 쌍의 계산은 지금처럼 통과한다
    expect(run('전년 111.8시간 → 올해 28.8시간 (-74.2%)', [HOURS_SRC]).unverifiedTotal).toBe(0);
  });

  it('단위 값 그대로 비교는 소수가 있는 표기만 — 정수(「15억」)는 흔한 개수 값에 우연히 맞지 않게 경고한다', () => {
    expect(run('매출 15.5억', ['[{"매출억":15.5}]']).unverifiedTotal).toBe(0);
    expect(run('매출 15억', ['[{"매출억":15}]']).unverified.map((u) => u.text)).toEqual(['15억']);
  });

  it('한 줄에 확인된 수치가 9개 이상이면 증감률 판정을 하지 않는다 — 쌍이 폭증하고 우연히 맞기 쉬워서 (WD-788)', () => {
    // 100 → 130 은 30.0% 증가. 재료 9개(100·130·101~107)인 줄에서는 판정하지 않아 경고된다
    const nine = [100, 130, 101, 102, 103, 104, 105, 106, 107];
    const src = JSON.stringify(nine.map((v) => ({ v })));
    expect(run(`${nine.join(' ')} (30.0%)`, [src]).unverified.map((u) => u.text)).toEqual(['30.0%']);
    // 재료가 상한(8개) 이하면 같은 계산이 통과한다
    expect(run(`${nine.slice(0, 8).join(' ')} (30.0%)`, [src]).unverifiedTotal).toBe(0);
    expect(run('100 → 130 (30.0%)', [src]).unverifiedTotal).toBe(0);
  });

  it('재료가 4개를 넘는 줄에서는 정수 % 를 계산값으로 인정하지 않는다 — 지어낸 둥근 % 가 우연히 맞기 쉬워서 (WD-788)', () => {
    const five = [100, 130, 150, 200, 250];
    const src = JSON.stringify(five.map((v) => ({ v })));
    // 130/100 → 30% 로 맞지만 재료 5개 줄의 정수 % 는 경고한다
    expect(run(`${five.join(' ')} (30%)`, [src]).unverified.map((u) => u.text)).toEqual(['30%']);
    // 같은 줄이라도 소수 자릿수가 있는 % 는 계산값으로 인정한다
    expect(run(`${five.join(' ')} (30.0%)`, [src]).unverifiedTotal).toBe(0);
    // 재료가 4개 이하인 줄의 정수 % 는 지금처럼 인정한다 (「1억2천만 → 1억8천만 (+50%)」 같은 실제 사례)
    expect(run(`${five.slice(0, 4).join(' ')} (30%)`, [src]).unverifiedTotal).toBe(0);
  });

  it('같은 값이 여러 번 나와도 서로 다른 값 수로 재료 상한을 센다 — 100·100·100·100 → 130 (+30%) 는 계산값이다 (WD-796)', () => {
    const src = JSON.stringify([{ v: 100 }, { v: 130 }]);
    expect(run('100 · 100 · 100 · 100 → 130 (+30%)', [src]).unverified.map((u) => u.text)).not.toContain('30%');
  });

  it('서로 다른 값이 9개 이상이면 여전히 판정하지 않는다 (WD-796)', () => {
    const nine = [100, 130, 101, 102, 103, 104, 105, 106, 107];
    const src = JSON.stringify(nine.map((v) => ({ v })));
    // 같은 값을 반복해도 서로 다른 값이 9개이면 판정하지 않는다
    expect(run(`100 100 ${nine.join(' ')} (30.0%)`, [src]).unverified.map((u) => u.text)).toEqual(['30.0%']);
  });

  it('값이 모두 같은 줄은 쌍이 없어 % 를 계산값으로 인정하지 않는다 (WD-796)', () => {
    expect(run('100 · 100 · 100 (0%)', [JSON.stringify([{ v: 100 }])]).unverified.map((u) => u.text)).toEqual(['0%']);
  });

  it('출처 % 값과 이미 맞는 % 토큰은 지금처럼 통과한다', () => {
    expect(run('전환율 12.3%').unverifiedTotal).toBe(0);
  });
});
