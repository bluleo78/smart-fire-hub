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
  it('NULL·숫자 아닌 값은 결측으로 빼고 개수를 센다 — 0 으로 바꾸지 않는다(WD-835)', () => {
    const { pieData, negativeCount, missingCount } = splitPieData(
      [{ v: 50 }, { v: -20 }, { v: '30' }, { v: null }, { v: '측정 안 함' }, { v: '' }, { v: ' ' }, { v: true }, { v: undefined }],
      'v',
    );
    expect(negativeCount).toBe(1);
    expect(missingCount).toBe(6);
    expect(pieData.map((r) => r.v)).toEqual([50, 30]);
  });

  it('실제 0 은 결측이 아니라 남긴다', () => {
    const { pieData, missingCount } = splitPieData([{ v: 0 }, { v: '0' }, { v: 5 }], 'v');
    expect(missingCount).toBe(0);
    expect(pieData.map((r) => r.v)).toEqual([0, 0, 5]);
  });

  it('모두 결측·음수면 빈 배열', () => {
    expect(splitPieData([{ v: null }, { v: -1 }], 'v')).toEqual({ pieData: [], negativeCount: 1, missingCount: 1 });
  });

  it('숫자 문자열이 있으면 복사해 숫자로 바꾸고 원본은 건드리지 않는다', () => {
    const data = [{ k: 'A', v: '2' }];
    const { pieData } = splitPieData(data, 'v');
    expect(pieData).toEqual([{ k: 'A', v: 2 }]);
    expect(data[0].v).toBe('2');
  });
});

describe('toBoxStats', () => {
  it('읽을 수 없는 통계는 0 으로 본다', () => {
    expect(toBoxStats([{ c: 'A', min: '1,0', q1: '2', median: 3, q3: null, max: '5' }], 'c')).toEqual([
      { category: 'A', min: 0, q1: 2, median: 3, q3: 0, max: 5 },
    ]);
  });

  it('앞 이름이 읽히지 않으면 읽히는 별칭을 쓴다(WD-835)', () => {
    expect(toBoxStats([{ c: 'A', min: '', q0: 5, q1: 6, median: 'N/A', q2: '7', q3: 8, max: null, q4: 9 }], 'c')).toEqual([
      { category: 'A', min: 5, q1: 6, median: 7, q3: 8, max: 9 },
    ]);
  });

  it('앞 이름이 읽히면 별칭이 있어도 앞을 쓴다', () => {
    expect(toBoxStats([{ c: 'A', min: 1, q0: 5, q1: 2, median: 3, q3: 4, max: 6, q4: 99 }], 'c')[0]).toMatchObject({ min: 1, max: 6 });
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
