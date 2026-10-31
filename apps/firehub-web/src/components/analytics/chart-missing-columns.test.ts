/**
 * findMissingChartColumns 단위 테스트 (WD-88 회귀 방지)
 *
 * 원본 쿼리의 컬럼 이름이 바뀌면 저장된 차트 설정이 결과에 없는 컬럼을 가리킨다. 이 함수가 그 이름을 찾아야
 * ChartRenderer 가 빈 격자 대신 안내를 보여 준다. 타입마다 렌더러가 실제로 읽는 필드만 검사해야 하므로
 * (읽지 않는 남은 필드로 오탐하지 않게) 타입별로 "검사함 / 검사하지 않음"을 함께 단언한다.
 */
import { describe, expect, it } from 'vitest';

import type { ChartConfig, ChartType } from '../../types/analytics';
import { findMissingChartColumns, normalizeYAxis, resolveChartAxes, toggleYAxisColumn } from './chart-missing-columns';

/** 기본 설정에 덮어쓸 값만 받는 헬퍼 */
function cfg(overrides: Partial<ChartConfig>): ChartConfig {
  return { xAxis: '', yAxis: [], ...overrides };
}

const COLUMNS = ['area', 'sum', 'cnt'];

describe('findMissingChartColumns — 공통 예외', () => {
  it('columns 가 비어 있으면(가림·결과 없음) 검사하지 않는다', () => {
    expect(findMissingChartColumns('BAR', cfg({ xAxis: 'region', yAxis: ['total'] }), [])).toEqual([]);
  });

  it('비어 있는 설정값(자동 추론 대상)은 검사하지 않는다', () => {
    expect(findMissingChartColumns('BAR', cfg({ xAxis: '', yAxis: [] }), COLUMNS)).toEqual([]);
    expect(findMissingChartColumns('BAR', cfg({ xAxis: 'area', yAxis: [''] }), COLUMNS)).toEqual([]);
  });

  it('설정이 모두 결과에 있으면 빈 배열', () => {
    expect(findMissingChartColumns('BAR', cfg({ xAxis: 'area', yAxis: ['sum', 'cnt'] }), COLUMNS)).toEqual([]);
  });

  it('같은 이름은 한 번만, 설정 순서대로 돌려준다', () => {
    expect(
      findMissingChartColumns('SCATTER', cfg({ xAxis: 'region', yAxis: ['total'], groupBy: 'region' }), COLUMNS),
    ).toEqual(['region', 'total']);
  });

  it('LLM 이 조립한 config 에 yAxis 가 빠져 있어도 깨지지 않는다', () => {
    const broken = { xAxis: 'region' } as unknown as ChartConfig;
    expect(findMissingChartColumns('BAR', broken, COLUMNS)).toEqual(['region']);
  });
});

describe('findMissingChartColumns — 타입별 검사 필드', () => {
  it.each<ChartType>(['BAR', 'LINE', 'AREA', 'RADAR'])('%s: xAxis 와 yAxis 전체를 검사한다', (type) => {
    expect(findMissingChartColumns(type, cfg({ xAxis: 'region', yAxis: ['sum', 'total'] }), COLUMNS)).toEqual([
      'region',
      'total',
    ]);
  });

  it.each<ChartType>(['PIE', 'DONUT', 'TREEMAP', 'FUNNEL', 'WATERFALL'])(
    '%s: xAxis 와 yAxis[0] 만 검사한다(yAxis[1..] 은 렌더러가 읽지 않음)',
    (type) => {
      expect(findMissingChartColumns(type, cfg({ xAxis: 'region', yAxis: ['total'] }), COLUMNS)).toEqual([
        'region',
        'total',
      ]);
      expect(findMissingChartColumns(type, cfg({ xAxis: 'area', yAxis: ['sum', 'stale'] }), COLUMNS)).toEqual([]);
    },
  );

  it('HEATMAP: xAxis·yAxis[0]·valueColumn 을 검사한다', () => {
    expect(
      findMissingChartColumns('HEATMAP', cfg({ xAxis: 'area', yAxis: ['month'], valueColumn: 'total' }), COLUMNS),
    ).toEqual(['month', 'total']);
  });

  it('SCATTER: 그룹이 있으면 xAxis·yAxis[0]·groupBy, 없으면 xAxis 와 yAxis 전부를 검사한다(WD-308)', () => {
    expect(findMissingChartColumns('SCATTER', cfg({ xAxis: 'sum', yAxis: ['cnt'], groupBy: 'kind' }), COLUMNS)).toEqual(
      ['kind'],
    );
    // 그룹이 있으면 Y 첫 번째만 그리므로 나머지는 보지 않는다
    expect(
      findMissingChartColumns('SCATTER', cfg({ xAxis: 'sum', yAxis: ['cnt', 'stale'], groupBy: 'area' }), COLUMNS),
    ).toEqual([]);
    // 그룹이 없으면 Y축마다 계열을 그리므로 두 번째 Y 가 사라져도 알린다
    expect(findMissingChartColumns('SCATTER', cfg({ xAxis: 'sum', yAxis: ['cnt', 'stale'] }), COLUMNS)).toEqual([
      'stale',
    ]);
  });

  it('MAP: spatialColumn 을 검사하고, 남은 xAxis/yAxis 와 colorByColumn 은 무시한다', () => {
    expect(
      findMissingChartColumns(
        'MAP',
        cfg({ xAxis: 'stale_x', yAxis: ['stale_y'], spatialColumn: 'geom', colorByColumn: 'stale_color' }),
        COLUMNS,
      ),
    ).toEqual(['geom']);
  });

  it('MAP: weightColumn 은 밀도(heatmap) 모드에서만 검사한다', () => {
    const base = { spatialColumn: 'area', weightColumn: 'weight' };
    expect(findMissingChartColumns('MAP', cfg({ ...base, mapDisplayMode: 'heatmap' }), COLUMNS)).toEqual(['weight']);
    expect(findMissingChartColumns('MAP', cfg({ ...base, mapDisplayMode: 'points' }), COLUMNS)).toEqual([]);
  });

  it('GAUGE: yAxis[0] 만 검사하고 남은 xAxis 는 무시한다', () => {
    expect(findMissingChartColumns('GAUGE', cfg({ xAxis: 'stale', yAxis: ['total'] }), COLUMNS)).toEqual(['total']);
    expect(findMissingChartColumns('GAUGE', cfg({ xAxis: 'stale', yAxis: ['sum'] }), COLUMNS)).toEqual([]);
  });

  it.each<ChartType>(['HISTOGRAM', 'BOXPLOT'])('%s: xAxis 만 검사하고 남은 yAxis 는 무시한다', (type) => {
    expect(findMissingChartColumns(type, cfg({ xAxis: 'region', yAxis: ['stale'] }), COLUMNS)).toEqual(['region']);
    expect(findMissingChartColumns(type, cfg({ xAxis: 'sum', yAxis: ['stale'] }), COLUMNS)).toEqual([]);
  });

  it('CANDLESTICK: 라벨 xAxis 와 시가/고가/저가/종가를 검사한다', () => {
    const columns = ['day', 'o', 'h', 'l', 'c'];
    expect(
      findMissingChartColumns(
        'CANDLESTICK',
        cfg({ xAxis: 'date', yAxis: ['stale'], open: 'o', high: 'high', low: 'l', close: 'close' }),
        columns,
      ),
    ).toEqual(['date', 'high', 'close']);
    expect(
      findMissingChartColumns('CANDLESTICK', cfg({ xAxis: 'day', open: 'o', high: 'h', low: 'l', close: 'c' }), columns),
    ).toEqual([]);
  });

  it('TABLE: 설정 컬럼을 쓰지 않으므로 검사하지 않는다', () => {
    expect(findMissingChartColumns('TABLE', cfg({ xAxis: 'region', yAxis: ['total'] }), COLUMNS)).toEqual([]);
  });
});

describe('findMissingChartColumns — row 키도 결과 컬럼으로 인정 (WD-88 리뷰)', () => {
  it('AI 챗처럼 columns 가 표시 라벨이어도 row 키에 있으면 누락이 아니다', () => {
    expect(
      findMissingChartColumns('BAR', cfg({ xAxis: 'region', yAxis: ['total'] }), ['지역', '합계'], [
        { region: '서울', total: 1 },
      ]),
    ).toEqual([]);
  });

  it('첫 행에 없는 키도 다른 행에 있으면 인정한다', () => {
    expect(
      findMissingChartColumns('SCATTER', cfg({ xAxis: 'a', yAxis: ['b'], groupBy: 'g' }), ['a', 'b'], [
        { a: 1, b: 2 },
        { a: 1, b: 2, g: 'x' },
      ]),
    ).toEqual([]);
  });

  it('columns 에도 row 키에도 없으면 누락이다', () => {
    expect(
      findMissingChartColumns('BAR', cfg({ xAxis: 'region', yAxis: ['total'] }), ['area', 'sum'], [{ area: '서울', sum: 1 }]),
    ).toEqual(['region', 'total']);
  });
});

describe('toggleYAxisColumn — Y축 재선택 (WD-88)', () => {
  const columns = ['b', 'c', 'd'];

  it('새로 체크한 컬럼이 첫 누락 항목 자리를 차지한다 — yAxis[0] 만 읽는 타입이 고른 컬럼을 그리게', () => {
    expect(toggleYAxisColumn(['old', 'b'], 'c', true, columns)).toEqual(['c', 'b']);
  });

  it('누락 항목이 여럿이면 첫 자리만 새 컬럼으로 바꾸고 나머지 누락은 지운다', () => {
    expect(toggleYAxisColumn(['b', 'old1', 'd', 'old2'], 'c', true, columns)).toEqual(['b', 'c', 'd']);
  });

  it('누락 항목이 없으면 끝에 붙인다(기존 동작)', () => {
    expect(toggleYAxisColumn(['b'], 'c', true, columns)).toEqual(['b', 'c']);
  });

  it('해제하면 해당 컬럼과 누락 항목을 함께 지운다', () => {
    expect(toggleYAxisColumn(['old', 'b', 'c'], 'b', false, columns)).toEqual(['c']);
  });
});

describe('findMissingChartColumns — 후보 우선 판정 (WD-88 simplify)', () => {
  /** 순회하면 예외를 던지는 행 목록 — 행을 아예 보지 않았는지 확인한다 */
  function untouchableRows(): Record<string, unknown>[] {
    const rows: Record<string, unknown>[] = [];
    Object.defineProperty(rows, Symbol.iterator, {
      value: () => {
        throw new Error('행을 순회하면 안 된다');
      },
    });
    return rows;
  }

  it('설정 컬럼이 모두 columns 에 있으면 행을 보지 않고 바로 빈 배열을 돌려준다', () => {
    expect(findMissingChartColumns('BAR', cfg({ xAxis: 'area', yAxis: ['sum'] }), COLUMNS, untouchableRows())).toEqual(
      [],
    );
  });

  it('상속 속성 이름(toString 등)은 행 키로 인정하지 않는다', () => {
    expect(
      findMissingChartColumns('BAR', cfg({ xAxis: 'toString', yAxis: ['sum'] }), COLUMNS, [{ area: 'x', sum: 1 }]),
    ).toEqual(['toString']);
  });

  it('후보 일부만 행 키에 있으면 나머지만 누락으로 돌려준다', () => {
    expect(
      findMissingChartColumns('BAR', cfg({ xAxis: 'region', yAxis: ['total'] }), ['area'], [{ region: 'x' }]),
    ).toEqual(['total']);
  });
});

describe('normalizeYAxis (WD-88 simplify)', () => {
  it('yAxis 가 없으면 빈 배열로 채운 사본을 돌려주고 원본은 건드리지 않는다', () => {
    const raw = { xAxis: 'a' } as unknown as ChartConfig;
    const out = normalizeYAxis(raw);
    expect(out).toEqual({ xAxis: 'a', yAxis: [] });
    expect(out).not.toBe(raw);
    expect('yAxis' in raw).toBe(false);
  });
});

/** 축 자동 추론 — ChartRenderer 와 AI 챗 rows 정규화(WD-104)가 공유하는 규칙 */
describe('resolveChartAxes', () => {
  it('비어 있는 xAxis 는 첫 컬럼, yAxis 는 나머지 컬럼으로 채운다', () => {
    expect(resolveChartAxes({ xAxis: '', yAxis: [] }, ['a', 'b', 'c'])).toMatchObject({ xAxis: 'a', yAxis: ['b', 'c'] });
  });

  it('xAxis 만 있으면 그 칸을 뺀 나머지가 yAxis 가 되고, 나머지가 없으면 [columns[1]] 이다', () => {
    expect(resolveChartAxes({ xAxis: 'b', yAxis: [] }, ['a', 'b'])).toMatchObject({ xAxis: 'b', yAxis: ['a'] });
    expect(resolveChartAxes({ xAxis: 'a', yAxis: [] }, ['a', 'a'])).toMatchObject({ xAxis: 'a', yAxis: ['a'] });
  });

  it('컬럼이 2개 미만이면 추론하지 않고, 설정이 있으면 그대로 두며 원본을 바꾸지 않는다', () => {
    expect(resolveChartAxes({ xAxis: '', yAxis: [] }, ['a'])).toMatchObject({ xAxis: '', yAxis: [] });
    const raw = { xAxis: 'x', yAxis: ['y'] };
    const resolved = resolveChartAxes(raw, ['x', 'y', 'z']);
    expect(resolved).toMatchObject({ xAxis: 'x', yAxis: ['y'] });
    expect(resolved).not.toBe(raw);
    const missingY = { xAxis: '' } as unknown as ChartConfig;
    resolveChartAxes(missingY, ['a', 'b']);
    expect(missingY.xAxis).toBe('');
  });
});
