/**
 * AI 챗 인라인 차트 rows 숫자 문자열 정규화 단위 테스트 (WD-104)
 *
 * 값 축 칸의 숫자 문자열("1.5")만 Number 로 바꾸고, 카테고리(x축·groupBy) 칸의 숫자처럼 생긴 라벨("007", "2024")과
 * 숫자가 아닌 문자열은 그대로 두는지 확인한다. 바꿀 값이 없으면 원본 배열 참조를 그대로 돌려줘야 한다.
 */
import { describe, expect, it } from 'vitest';

import { chartValueColumns, normalizeChartRows } from './chart-numeric-rows';

describe('normalizeChartRows (WD-104)', () => {
  it('y축 칸의 숫자 문자열만 Number 로 바꾸고 x축 라벨은 그대로 둔다', () => {
    const rows = [
      { code: '007', sum: '120.5' },
      { code: '2024', sum: '80' },
    ];
    const result = normalizeChartRows('BAR', { xAxis: 'code', yAxis: ['sum'] }, ['code', 'sum'], rows);
    expect(result).toEqual([
      { code: '007', sum: 120.5 },
      { code: '2024', sum: 80 },
    ]);
    expect(typeof result[0].sum).toBe('number');
    expect(typeof result[0].code).toBe('string');
    // 원본 행은 바꾸지 않는다
    expect(rows[0].sum).toBe('120.5');
  });

  it('음수·정수·소수는 바꾸고 정규식에 맞지 않는 문자열(지수·쉼표·공백·빈 값·+부호·점으로 끝남)은 그대로 둔다', () => {
    const rows = [
      { x: 'a', v: '-3.25' },
      { x: 'b', v: '1e3' },
      { x: 'c', v: '1,000' },
      { x: 'd', v: ' 5' },
      { x: 'e', v: '' },
      { x: 'f', v: '+5' },
      { x: 'g', v: '5.' },
      { x: 'h', v: '없음' },
      { x: 'i', v: null },
      { x: 'j', v: 7 },
    ];
    const result = normalizeChartRows('LINE', { xAxis: 'x', yAxis: ['v'] }, ['x', 'v'], rows);
    expect(result.map((r) => r.v)).toEqual([-3.25, '1e3', '1,000', ' 5', '', '+5', '5.', '없음', null, 7]);
  });

  it('여러 y축 시리즈를 모두 바꾸고 groupBy 칸은 바꾸지 않는다', () => {
    const rows = [{ month: '01', a: '1.5', b: '2', grp: '10' }];
    const result = normalizeChartRows(
      'BAR',
      { xAxis: 'month', yAxis: ['a', 'b'], groupBy: 'grp' },
      ['month', 'a', 'b', 'grp'],
      rows,
    );
    expect(result).toEqual([{ month: '01', a: 1.5, b: 2, grp: '10' }]);
  });

  it('파이 차트도 값 칸(yAxis[0])만 바꾸고 이름 칸은 그대로 둔다', () => {
    const rows = [
      { year: '2023', total: '10.5' },
      { year: '2024', total: '20' },
    ];
    const result = normalizeChartRows('PIE', { xAxis: 'year', yAxis: ['total'] }, ['year', 'total'], rows);
    expect(result).toEqual([
      { year: '2023', total: 10.5 },
      { year: '2024', total: 20 },
    ]);
  });

  it('yAxis 가 비면 ChartRenderer 자동 추론과 같이 첫 칸을 x축, 나머지를 값 칸으로 본다', () => {
    const rows = [{ name: '007', v1: '1.5', v2: '3' }];
    const result = normalizeChartRows('BAR', { xAxis: '', yAxis: [] }, ['name', 'v1', 'v2'], rows);
    expect(result).toEqual([{ name: '007', v1: 1.5, v2: 3 }]);
  });

  it('산점도·히스토그램은 x축도 수치 축이라 바꾼다', () => {
    const scatter = normalizeChartRows('SCATTER', { xAxis: 'x', yAxis: ['y'] }, ['x', 'y'], [{ x: '1.5', y: '2' }]);
    expect(scatter).toEqual([{ x: 1.5, y: 2 }]);
    const hist = normalizeChartRows('HISTOGRAM', { xAxis: 'score', yAxis: [] }, ['score'], [{ score: '88.5' }]);
    expect(hist).toEqual([{ score: 88.5 }]);
  });

  it('산점도는 그룹이 없으면 Y축 전부를 숫자로 바꾼다 — Y마다 계열을 그린다(WD-308)', () => {
    const rows = [{ x: '1', a: '2.5', b: '3' }];
    expect(normalizeChartRows('SCATTER', { xAxis: 'x', yAxis: ['a', 'b'] }, ['x', 'a', 'b'], rows)).toEqual([
      { x: 1, a: 2.5, b: 3 },
    ]);
  });

  it('TABLE 차트는 숫자 문자열을 바꾸지 않는다 — TableView 가 String(val) 로 그려 정밀도·앞자리 0 이 보존돼야 한다', () => {
    const rows = [{ code: '007', price: '123456789012.123456', big: '9007199254740993' }];
    const result = normalizeChartRows('TABLE', { xAxis: '', yAxis: [] }, ['code', 'price', 'big'], rows);
    expect(result).toBe(rows);
    expect(result[0]).toEqual({ code: '007', price: '123456789012.123456', big: '9007199254740993' });
  });

  it('HEATMAP 은 열 카테고리(yAxis[0])·행(xAxis) 칸을 원문 그대로 두고 셀 값만 바꾼다', () => {
    const rows = [{ month: '01', code: '007', cnt: '12.5' }];
    const result = normalizeChartRows(
      'HEATMAP',
      { xAxis: 'month', yAxis: ['code'], valueColumn: 'cnt' },
      ['month', 'code', 'cnt'],
      rows,
    );
    expect(result).toEqual([{ month: '01', code: '007', cnt: 12.5 }]);
  });

  it('MAP 차트는 가중치·색상·팝업 속성 칸을 바꾸지 않는다', () => {
    const rows = [{ geom: '{"type":"Point"}', w: '3.5', grade: '007' }];
    const result = normalizeChartRows(
      'MAP',
      { xAxis: '', yAxis: [], spatialColumn: 'geom', weightColumn: 'w', colorByColumn: 'grade' },
      ['geom', 'w', 'grade'],
      rows,
    );
    expect(result).toBe(rows);
  });

  it('바꿀 값이 없으면(숫자로 온 응답) 원본 배열 참조를 그대로 돌려준다', () => {
    const rows = [{ area: '서울', sum: 120 }];
    expect(normalizeChartRows('BAR', { xAxis: 'area', yAxis: ['sum'] }, ['area', 'sum'], rows)).toBe(rows);
  });
});

describe('chartValueColumns (WD-104)', () => {
  it('HEATMAP 은 셀 값(valueColumn)만, CANDLESTICK 은 OHLC 를 값 칸으로 본다', () => {
    // HEATMAP 의 yAxis[0] 은 열 카테고리라 값 칸이 아니다
    expect(
      chartValueColumns('HEATMAP', { xAxis: 'r', yAxis: ['c'], valueColumn: 'v' }, ['r', 'c', 'v']),
    ).toEqual(new Set(['v']));
    expect(
      chartValueColumns(
        'CANDLESTICK',
        { xAxis: 'd', yAxis: [], open: 'o', high: 'h', low: 'l', close: 'c' },
        [],
      ),
    ).toEqual(new Set(['o', 'h', 'l', 'c']));
  });

  it('TABLE·MAP 은 값 칸이 없다(자동 추론이 일어나는 설정이어도)', () => {
    expect(chartValueColumns('TABLE', { xAxis: '', yAxis: [] }, ['code', 'price'])).toEqual(new Set());
    expect(
      chartValueColumns(
        'MAP',
        { xAxis: '', yAxis: [], spatialColumn: 'geom', weightColumn: 'w', colorByColumn: 'grade' },
        ['geom', 'w', 'grade'],
      ),
    ).toEqual(new Set());
  });

  it('yAxis 에 x축과 같은 칸이 있어도 카테고리 축이면 값 칸에서 뺀다', () => {
    expect(chartValueColumns('BAR', { xAxis: 'code', yAxis: ['code', 'sum'] }, ['code', 'sum'])).toEqual(
      new Set(['sum']),
    );
  });
});
