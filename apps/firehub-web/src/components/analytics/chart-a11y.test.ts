import { describe, expect, it } from 'vitest';

import {
  buildChartAccessibleName,
  buildChartDataTable,
  formatChartCellValue,
} from './chart-a11y';
import { getChartUsedColumns } from './chart-missing-columns';

describe('buildChartAccessibleName (WD-826)', () => {
  it('제목이 없으면 타입 라벨과 축 정보로 만든다', () => {
    expect(buildChartAccessibleName('BAR', { xAxis: 'period', yAxis: ['amount'] })).toBe(
      '막대 차트: period별 amount',
    );
    expect(buildChartAccessibleName('LINE', { xAxis: 'd', yAxis: ['a', 'b'] })).toBe(
      '꺾은선 차트: d별 a, b',
    );
  });

  it('제목이 있으면 「제목」 타입 라벨 형태다', () => {
    expect(
      buildChartAccessibleName('BAR', { xAxis: 'period', yAxis: ['amount'] }, '매출 추이'),
    ).toBe('「매출 추이」 막대 차트');
  });

  it('공백뿐인 제목은 없는 것으로 본다', () => {
    expect(buildChartAccessibleName('BAR', { xAxis: 'period', yAxis: ['amount'] }, '   ')).toBe(
      '막대 차트: period별 amount',
    );
  });

  it('축 정보가 없으면 타입 라벨만 쓴다', () => {
    expect(buildChartAccessibleName('GAUGE', { xAxis: '', yAxis: [] })).toBe('게이지');
  });
});

describe('buildChartDataTable (WD-826)', () => {
  const config = { xAxis: 'period', yAxis: ['amount'] };

  it('설정이 쓰는 컬럼만 열로 삼고 값을 문자열로 바꾼다', () => {
    const data = [
      { period: '상반기', amount: 10, extra: 1 },
      { period: '하반기', amount: 20, extra: 2 },
    ];
    expect(buildChartDataTable('BAR', config, ['period', 'amount', 'extra'], data, 'N')).toEqual({
      caption: 'N 데이터',
      headers: ['period', 'amount'],
      rows: [
        ['상반기', '10'],
        ['하반기', '20'],
      ],
    });
  });

  it('x 와 y[0] 가 같아도 열을 중복하지 않는다', () => {
    const table = buildChartDataTable(
      'BAR',
      { xAxis: 'a', yAxis: ['a', 'b'] },
      ['a', 'b'],
      [{ a: 1, b: 2 }],
      'N',
    );
    expect(table.headers).toEqual(['a', 'b']);
  });

  it('설정이 비어 있으면 결과 컬럼 전체를 쓴다', () => {
    const table = buildChartDataTable('BAR', { xAxis: '', yAxis: [] }, ['c1', 'c2'], [], 'N');
    expect(table.headers).toEqual(['c1', 'c2']);
  });

  it('100행을 넘으면 잘라 내고 캡션에 알린다', () => {
    const data = Array.from({ length: 150 }, (_, i) => ({ period: `p${i}`, amount: i }));
    const table = buildChartDataTable('BAR', config, ['period', 'amount'], data, 'N');
    expect(table.rows).toHaveLength(100);
    expect(table.rows[99]).toEqual(['p99', '99']);
    expect(table.caption).toBe('N 데이터 (전체 150행 중 처음 100행)');
  });
});

describe('chart-a11y 보정 (WD-826)', () => {
  it('캡션의 전체 행 수는 천 단위 구분 기호를 쓴다', () => {
    const data = Array.from({ length: 1500 }, (_, i) => ({ period: `p${i}`, amount: i }));
    const table = buildChartDataTable(
      'BAR',
      { xAxis: 'period', yAxis: ['amount'] },
      ['period', 'amount'],
      data,
      'N',
    );
    expect(table.caption.endsWith(' (전체 1,500행 중 처음 100행)')).toBe(true);
  });

  it('긴 문자열은 자르지 않고 그대로 돌려준다', () => {
    const long = '가'.repeat(200);
    expect(formatChartCellValue(long)).toBe(long);
  });
});

describe('formatChartCellValue (WD-826)', () => {
  it('null·undefined 는 빈 문자열', () => {
    expect(formatChartCellValue(null)).toBe('');
    expect(formatChartCellValue(undefined)).toBe('');
  });

  it('0·false 는 값을 보존한다', () => {
    expect(formatChartCellValue(0)).toBe('0');
    expect(formatChartCellValue(false)).toBe('false');
  });

  it('16~17자리 정수(타임스탬프·ID)는 끝자리를 바꾸지 않는다 (WD-831)', () => {
    expect(formatChartCellValue(1731999999123456)).toBe('1731999999123456');
    expect(formatChartCellValue(Number.MAX_SAFE_INTEGER)).toBe('9007199254740991');
    expect(formatChartCellValue(-1731999999123456)).toBe('-1731999999123456');
  });

  it('소수는 부동소수 잡음만 다듬고 작은 소수는 남긴다 (WD-831)', () => {
    expect(formatChartCellValue(0.1 + 0.2)).toBe('0.3');
    expect(formatChartCellValue(0.0004)).toBe('0.0004');
    expect(formatChartCellValue(NaN)).toBe('NaN');
  });

  it('객체는 JSON 으로 쓰고 길면 80자 + …로 자른다', () => {
    expect(formatChartCellValue({ type: 'Point', coordinates: [1, 2] })).toBe(
      '{"type":"Point","coordinates":[1,2]}',
    );
    const long = formatChartCellValue({ k: 'x'.repeat(200) });
    expect(long).toHaveLength(81);
    expect(long.endsWith('…')).toBe(true);
  });
});

describe('getChartUsedColumns (WD-826)', () => {
  it('타입별로 쓰는 컬럼을 설정 순서대로 돌려준다', () => {
    expect(
      getChartUsedColumns('CANDLESTICK', {
        xAxis: 'd',
        yAxis: [],
        open: 'o',
        high: 'h',
        low: 'l',
        close: 'c',
      }),
    ).toEqual(['d', 'o', 'h', 'l', 'c']);
  });
});

/**
 * 변환 값을 그리는 차트의 대체 표 (WD-826 코드 리뷰 지적 1·3·5·7)
 * 원본 행 대신 차트가 실제로 보여 주는 값(구간 빈도·누적·게이지 값과 범위·박스플롯 통계)을 담아야 한다.
 */
describe('buildChartDataTable — 차트가 그리는 값 (WD-826)', () => {
  it('히스토그램은 구간(뷰의 X축 라벨)별 빈도를 준다 — 숫자 아닌 값은 빼고 센다', () => {
    const data = [{ x: 1 }, { x: 2 }, { x: 3 }, { x: 4 }, { x: 10 }, { x: '가' }];
    expect(buildChartDataTable('HISTOGRAM', { xAxis: 'x', yAxis: [], bins: 3 }, ['x'], data, 'N')).toEqual({
      caption: 'N 데이터',
      headers: ['구간', '빈도'],
      rows: [
        ['1.0', '3'],
        ['4.0', '1'],
        ['7.0', '1'],
      ],
    });
  });

  it('히스토그램 값이 모두 같으면 구간 하나에 전부 센다', () => {
    const table = buildChartDataTable('HISTOGRAM', { xAxis: 'x', yAxis: [] }, ['x'], [{ x: 5 }, { x: 5 }], 'N');
    expect(table.rows).toEqual([['5', '2']]);
  });

  it('폭포 차트는 단계·증감·누적을 준다', () => {
    const data = [
      { step: '시작', v: 100 },
      { step: '비용', v: -30 },
      { step: '수익', v: 50 },
    ];
    expect(buildChartDataTable('WATERFALL', { xAxis: 'step', yAxis: ['v'] }, ['step', 'v'], data, 'N')).toEqual({
      caption: 'N 데이터',
      headers: ['step', 'v', '누적'],
      rows: [
        ['시작', '100', '100'],
        ['비용', '-30', '70'],
        ['수익', '50', '120'],
      ],
    });
  });

  it('게이지는 첫 행 값과 설정한 최솟값·최댓값 한 행만 준다', () => {
    const data = [{ v: 42 }, { v: 99 }];
    expect(
      buildChartDataTable('GAUGE', { xAxis: '', yAxis: ['v'], min: 10, max: 200 }, ['v'], data, 'N'),
    ).toEqual({
      caption: 'N 데이터',
      headers: ['v', '최솟값', '최댓값'],
      rows: [['42', '10', '200']],
    });
  });

  it('게이지 최댓값이 없으면 뷰처럼 값의 120%(올림)를 쓴다', () => {
    const table = buildChartDataTable('GAUGE', { xAxis: '', yAxis: ['v'] }, ['v'], [{ v: 42 }], 'N');
    expect(table.rows).toEqual([['42', '0', '51']]);
  });

  it('박스 플롯은 카테고리와 통계 열(별칭 q0·q2·q4 포함)을 뷰가 읽는 값으로 준다', () => {
    const data = [
      { cat: 'A', min: 1, q1: 2, median: 3, q3: 4, max: 5, extra: 9 },
      { cat: 'B', q0: 0, q1: 1, q2: 2, q3: 3, q4: 4 },
    ];
    expect(buildChartDataTable('BOXPLOT', { xAxis: 'cat', yAxis: [] }, ['cat'], data, 'N')).toEqual({
      caption: 'N 데이터',
      headers: ['cat', 'min', 'q1', 'median', 'q3', 'max'],
      rows: [
        ['A', '1', '2', '3', '4', '5'],
        ['B', '0', '1', '2', '3', '4'],
      ],
    });
  });

  it('박스 플롯은 결과에 있는 통계 열만 싣는다', () => {
    const table = buildChartDataTable('BOXPLOT', { xAxis: 'cat', yAxis: [] }, ['cat'], [{ cat: 'A', q1: 2, q3: 4 }], 'N');
    expect(table.headers).toEqual(['cat', 'q1', 'q3']);
    expect(table.rows).toEqual([['A', '2', '4']]);
  });

  it('박스 플롯은 앞 이름이 읽히지 않으면 별칭 값을 싣는다(WD-835)', () => {
    const t = buildChartDataTable('BOXPLOT', { xAxis: 'cat', yAxis: [] }, ['cat'], [{ cat: 'A', min: '', q0: 5, q1: 6, median: 7, q3: 8, max: 9 }], 'N');
    expect(t.rows[0][t.headers.indexOf('min')]).toBe('5');
  });

  it('변환 표도 100행 제한·캡션 규칙을 따른다', () => {
    const data = Array.from({ length: 150 }, (_, i) => ({ step: `s${i}`, v: 1 }));
    const table = buildChartDataTable('WATERFALL', { xAxis: 'step', yAxis: ['v'] }, ['step', 'v'], data, 'N');
    expect(table.rows).toHaveLength(100);
    expect(table.rows[99]).toEqual(['s99', '1', '100']);
    expect(table.caption).toBe('N 데이터 (전체 150행 중 처음 100행)');
  });
});

describe('대체 표 — 파이 비율·산점도 거르기·숫자 셀 형식 (WD-831)', () => {
  const table = (type: 'PIE' | 'DONUT' | 'SCATTER', config: Parameters<typeof buildChartDataTable>[1], data: Record<string, unknown>[]) =>
    buildChartDataTable(type, config, [], data, '차트');

  it.each(['PIE', 'DONUT'] as const)('%s: 음수를 빼고 비율 칸을 싣는다', (type) => {
    const t = table(type, { xAxis: 'k', yAxis: ['v'] }, [
      { k: 'A', v: 50 },
      { k: 'B', v: -20 },
      { k: 'C', v: 30 },
    ]);
    expect(t.headers).toEqual(['k', 'v', '비율']);
    expect(t.rows).toEqual([
      ['A', '50', '62.5%'],
      ['C', '30', '37.5%'],
    ]);
  });

  it('파이 합이 0 이하면 행을 싣지 않는다 — 예전엔 비율 칸만 비웠다(WD-841)', () => {
    const t = table('PIE', { xAxis: 'k', yAxis: ['v'] }, [
      { k: 'A', v: 0 },
      { k: 'B', v: -1 },
    ]);
    expect(t.rows).toEqual([]);
  });

  it('파이: 숫자 문자열은 값으로 읽는다', () => {
    const t = table('PIE', { xAxis: 'k', yAxis: ['v'] }, [
      { k: 'A', v: '30' },
      { k: 'C', v: 10 },
    ]);
    expect(t.rows).toEqual([
      ['A', '30', '75.0%'],
      ['C', '10', '25.0%'],
    ]);
  });

  it('PIE: NULL·숫자 아닌 Y 행은 표에 싣지 않고 비율은 남은 값으로 낸다(WD-835)', () => {
    const t = table('PIE', { xAxis: 'k', yAxis: ['v'] }, [
      { k: '서울', v: 30 },
      { k: '부산', v: null },
      { k: '대구', v: '측정 안 함' },
      { k: '인천', v: 10 },
    ]);
    expect(t.rows).toEqual([
      ['서울', '30', '75.0%'],
      ['인천', '10', '25.0%'],
    ]);
  });

  it('PIE: 그릴 값이 없으면(합계 0) 행을 싣지 않고 캡션 맨 앞에 「그릴 값 없음」을 적는다(WD-841)', () => {
    const t = table('PIE', { xAxis: 'k', yAxis: ['v'] }, [0, 0, -2, null].map((v, i) => ({ k: `k${i}`, v })));
    expect(t.rows).toHaveLength(0);
    expect(t.caption).toBe('차트 데이터 (그릴 값 없음, 음수 1행 제외, 값 없음·숫자 아님 1행 제외)');
    const zeros = table('PIE', { xAxis: 'k', yAxis: ['v'] }, [0, 0].map((v, i) => ({ k: `k${i}`, v })));
    expect(zeros.rows).toHaveLength(0);
    expect(zeros.caption).toBe('차트 데이터 (그릴 값 없음)');
  });

  it('PIE: 합계가 무한대면 행 없이 「값 합계가 너무 큼」, 아슬아슬하게 유한하면 비율을 싣는다(WD-843)', () => {
    const big = table('PIE', { xAxis: 'k', yAxis: ['v'] }, [1e308, 1e308, -1].map((v, i) => ({ k: `k${i}`, v })));
    expect(big.rows).toHaveLength(0);
    expect(big.caption).toBe('차트 데이터 (값 합계가 너무 큼, 음수 1행 제외)');
    const ok = table('PIE', { xAxis: 'k', yAxis: ['v'] }, [1e307, 1e307].map((v, i) => ({ k: `k${i}`, v })));
    expect(ok.rows.map((r) => r[2])).toEqual(['50.0%', '50.0%']);
  });

  it('PIE: 값 컬럼이 전부 숫자 아님이면 캡션 꼬리는 「숫자 값 컬럼 아님」, 행 없음(WD-841)', () => {
    const t = table('PIE', { xAxis: 'k', yAxis: ['v'] }, ['서울', '부산', null].map((v, i) => ({ k: `k${i}`, v })));
    expect(t.rows).toHaveLength(0);
    expect(t.caption).toBe('차트 데이터 (숫자 값 컬럼 아님)');
  });

  it('PIE: 값 없음 행 수 캡션은 천 단위 쉼표 형식이다(WD-841)', () => {
    const rows: { k: string; v: number | null }[] = [{ k: 'a', v: 1 }];
    for (let i = 0; i < 1000; i++) rows.push({ k: `m${i}`, v: null });
    expect(table('PIE', { xAxis: 'k', yAxis: ['v'] }, rows).caption).toBe('차트 데이터 (값 없음·숫자 아님 1,000행 제외)');
  });

  it('PIE: 합계가 양수면 지금처럼 꼬리 없이 비율을 싣는다(WD-841)', () => {
    const t = table('PIE', { xAxis: 'k', yAxis: ['v'] }, [3, 1].map((v, i) => ({ k: `k${i}`, v })));
    expect(t.caption).toBe('차트 데이터');
    expect(t.rows.map((r) => r[2])).toEqual(['75.0%', '25.0%']);
  });

  it('PIE: 뺀 행이 있으면 캡션 꼬리에 음수·값 없음 행 수를 적는다(WD-838)', () => {
    const rows = [10, -3, null, 'x', 5].map((v, i) => ({ k: `k${i}`, v }));
    const t = table('PIE', { xAxis: 'k', yAxis: ['v'] }, rows);
    expect(t.caption).toBe('차트 데이터 (음수 1행 제외, 값 없음·숫자 아님 2행 제외)');
  });

  it('PIE: 뺀 행이 없으면 캡션은 그대로, 한쪽만 있으면 그쪽만 적는다(WD-838)', () => {
    expect(table('PIE', { xAxis: 'k', yAxis: ['v'] }, [{ k: 'a', v: 1 }]).caption).toBe('차트 데이터');
    expect(table('DONUT', { xAxis: 'k', yAxis: ['v'] }, [{ k: 'a', v: 1 }, { k: 'b', v: -1 }]).caption).toBe('차트 데이터 (음수 1행 제외)');
    expect(table('PIE', { xAxis: 'k', yAxis: ['v'] }, [{ k: 'a', v: 1 }, { k: 'b', v: null }]).caption).toBe('차트 데이터 (값 없음·숫자 아님 1행 제외)');
  });

  it('PIE: 행 상한 문구와 같이 쓰면 한 괄호 안에 쉼표로 잇는다(WD-838)', () => {
    const rows = Array.from({ length: 150 }, (_, i) => ({ k: `k${i}`, v: i + 1 }));
    rows.push({ k: 'n1', v: -1 }, { k: 'n2', v: -2 });
    const t = table('PIE', { xAxis: 'k', yAxis: ['v'] }, rows);
    expect(t.caption).toBe('차트 데이터 (전체 150행 중 처음 100행, 음수 2행 제외)');
  });

  it('PIE: 행 상한·음수·결측이 함께면 항목을 쉼표로 나눈다(WD-838)', () => {
    const rows: { k: string; v: number | null }[] = Array.from({ length: 150 }, (_, i) => ({ k: `k${i}`, v: i + 1 }));
    rows.push({ k: 'n1', v: -1 }, { k: 'n2', v: -2 }, { k: 'm', v: null });
    const t = table('PIE', { xAxis: 'k', yAxis: ['v'] }, rows);
    expect(t.caption).toBe('차트 데이터 (전체 150행 중 처음 100행, 음수 2행 제외, 값 없음·숫자 아님 1행 제외)');
  });

  it('산점도: x 나 y 가 숫자가 아닌 행은 뺀다', () => {
    const t = table('SCATTER', { xAxis: 'x', yAxis: ['y'] }, [
      { x: 1, y: 2 },
      { x: 'a', y: 3 },
      { x: 2, y: 'b' },
      { x: 3, y: 4 },
    ]);
    expect(t.rows).toEqual([
      ['1', '2'],
      ['3', '4'],
    ]);
  });

  it('산점도: 두 Y 중 숫자 아닌 칸은 빈 칸이다', () => {
    const t = table('SCATTER', { xAxis: 'x', yAxis: ['y1', 'y2'] }, [{ x: 1, y1: 2, y2: 'z' }]);
    expect(t.rows).toEqual([['1', '2', '']]);
  });

  it('산점도: groupBy 칸은 그대로 싣는다', () => {
    const t = table('SCATTER', { xAxis: 'x', yAxis: ['y'], groupBy: 'g' }, [{ x: 1, y: 2, g: 'G' }]);
    expect(t.headers).toEqual(['x', 'y', 'g']);
    expect(t.rows).toEqual([['1', '2', 'G']]);
  });

  it('산점도: 그룹 값이 없으면 범례와 같은 unknown 으로 싣는다', () => {
    const t = table('SCATTER', { xAxis: 'x', yAxis: ['y'], groupBy: 'g' }, [{ x: 1, y: 2, g: null }]);
    expect(t.rows).toEqual([['1', '2', 'unknown']]);
  });

  it('formatChartCellValue: 부동소수 잡음은 다듬고 천 단위 구분은 넣지 않는다, 유한하지 않으면 그대로', () => {
    expect(formatChartCellValue(0.1 + 0.2)).toBe('0.3');
    // 연도·ID 같은 라벨 숫자가 축과 같게 읽히도록 구분 기호를 넣지 않는다(로캘과 무관)
    expect(formatChartCellValue(2024)).toBe('2024');
    expect(formatChartCellValue(1234567)).toBe('1234567');
    expect(formatChartCellValue(-12.5)).toBe('-12.5');
    // 작은 소수는 0 으로 줄지 않는다
    expect(formatChartCellValue(0.0004)).toBe('0.0004');
    expect(formatChartCellValue(NaN)).toBe('NaN');
    expect(formatChartCellValue('1,234')).toBe('1,234');
  });
});
