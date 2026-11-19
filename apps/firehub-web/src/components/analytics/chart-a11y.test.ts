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

  it('변환 표도 100행 제한·캡션 규칙을 따른다', () => {
    const data = Array.from({ length: 150 }, (_, i) => ({ step: `s${i}`, v: 1 }));
    const table = buildChartDataTable('WATERFALL', { xAxis: 'step', yAxis: ['v'] }, ['step', 'v'], data, 'N');
    expect(table.rows).toHaveLength(100);
    expect(table.rows[99]).toEqual(['s99', '1', '100']);
    expect(table.caption).toBe('N 데이터 (전체 150행 중 처음 100행)');
  });
});
