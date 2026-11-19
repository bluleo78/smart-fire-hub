import { describe, expect, it } from 'vitest';

import { toBoxStats } from './boxplot-stats';
import {
  getScatterUsedY,
  isScatterPoint,
  readHistogramValues,
  splitPieData,
  transformWaterfallData,
} from './chart-derived-data';

// WD-831: 차트 숫자 읽기를 toPlotNumber 로 통일하고, 파이 음수 거르기를 공용 함수로 옮긴 것을 검증한다.
describe('readHistogramValues', () => {
  it('빈 값·숫자 아닌 값은 버리고 숫자 문자열은 읽는다', () => {
    expect(readHistogramValues([{ v: null }, { v: '' }, { v: '3' }, { v: 4 }, { v: 'x' }], 'v')).toEqual([3, 4]);
  });
});

describe('transformWaterfallData', () => {
  it('읽을 수 없는 증감은 0 으로 보고 누적이 항상 유한하다', () => {
    const rows = transformWaterfallData(
      [
        { s: 'a', v: '10' },
        { s: 'b', v: '1,234' },
        { s: 'c', v: -3 },
      ],
      's',
      'v',
    );
    expect(rows.map((r) => r.cumulative)).toEqual([10, 10, 7]);
    expect(rows.every((r) => Number.isFinite(r.cumulative))).toBe(true);
  });
});

describe('splitPieData', () => {
  it('음수 행만 빼고 개수를 센다', () => {
    const { pieData, negativeCount } = splitPieData([{ v: 50 }, { v: -20 }, { v: '30' }, { v: null }], 'v');
    expect(negativeCount).toBe(1);
    // 남은 값은 recharts 가 세는 숫자로 바뀐다 — 숫자 문자열은 수로, 읽을 수 없는 값은 0(WD-831)
    expect(pieData.map((r) => r.v)).toEqual([50, 30, 0]);
  });

  it('숫자 문자열이 있으면 복사해 숫자로 바꾸고 원본은 건드리지 않는다', () => {
    const data = [{ k: 'A', v: '2' }];
    const { pieData } = splitPieData(data, 'v');
    expect(pieData).toEqual([{ k: 'A', v: 2 }]);
    expect(data[0].v).toBe('2');
  });

  it('공백뿐인 문자열·불리언은 음수로 세지 않고 0 으로 본다(toPlotNumber 규칙)', () => {
    const { pieData, negativeCount } = splitPieData([{ v: ' ' }, { v: true }, { v: '-3' }], 'v');
    expect(negativeCount).toBe(1);
    expect(pieData.map((r) => r.v)).toEqual([0, 0]);
  });
});

describe('toBoxStats', () => {
  it('읽을 수 없는 통계는 0 으로 본다', () => {
    expect(toBoxStats([{ c: 'A', min: '1,0', q1: '2', median: 3, q3: null, max: '5' }], 'c')).toEqual([
      { category: 'A', min: 0, q1: 2, median: 3, q3: 0, max: 5 },
    ]);
  });
});

describe('getScatterUsedY / isScatterPoint (WD-831)', () => {
  it('groupBy 가 있으면 첫 Y 하나만, 없으면 전체 Y 를 쓴다', () => {
    expect(getScatterUsedY({ xAxis: 'x', yAxis: ['a', 'b'], groupBy: 'g' })).toEqual(['a']);
    expect(getScatterUsedY({ xAxis: 'x', yAxis: ['a', 'b'] })).toEqual(['a', 'b']);
  });
  it('x·y 가 모두 숫자로 읽힐 때만 점이다', () => {
    expect(isScatterPoint(1, 2)).toBe(true);
    expect(isScatterPoint('1.5', '2')).toBe(true);
    expect(isScatterPoint(null, 2)).toBe(false);
    expect(isScatterPoint(1, 'a')).toBe(false);
  });
});
