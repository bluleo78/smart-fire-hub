import { describe, expect, it } from 'vitest';

import { toBoxStats } from './boxplot-stats';
import {
  computeGaugeReading,
  getScatterUsedY,
  groupSmallPieSlices,
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
    const { rows } = transformWaterfallData(
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

  it('증감은 유한해도 누적이 넘치면 행 없이 overflow 로 판정한다(WD-844)', () => {
    const data = [{ s: 'a', v: '1e308' }, { s: 'b', v: '1e308' }, { s: 'c', v: '-5' }];
    expect(transformWaterfallData(data, 's', 'v')).toEqual({ rows: [], overflow: true });
  });

  it('양·음 무한이 섞여 NaN 이 되기 전 중간에 Infinity 면 overflow 다(WD-844)', () => {
    const data = [1e308, 1e308, -1e308, -1e308, -1e308].map((v, i) => ({ s: `s${i}`, v }));
    expect(transformWaterfallData(data, 's', 'v').overflow).toBe(true);
  });

  it('누적이 아슬아슬하게 유한하면 overflow 가 아니다(WD-844)', () => {
    const data = [1e307, 1e307, 1e307].map((v, i) => ({ s: `s${i}`, v }));
    const result = transformWaterfallData(data, 's', 'v');
    expect(result.overflow).toBe(false);
    expect(result.rows).toHaveLength(3);
    expect(result.rows[2].cumulative).toBe(3e307);
  });

  it('누적은 모두 유한해도 Y축 범위(최대-최소)가 넘치면 overflow 다(WD-844)', () => {
    const data = [-1e308, 1e308, 1e308].map((v, i) => ({ s: `s${i}`, v }));
    expect(transformWaterfallData(data, 's', 'v')).toEqual({ rows: [], overflow: true });
  });

  it('단일 합계가 최댓값에 가까워 눈금 올림이 넘치면 overflow 다(WD-844)', () => {
    const result = transformWaterfallData([{ s: 'a', v: 1.7e308 }], 's', 'v');
    expect(result.overflow).toBe(true);
    expect(result.rows).toEqual([]);
  });

  it('빈 데이터는 overflow 가 아니다(WD-844)', () => {
    expect(transformWaterfallData([], 's', 'v')).toEqual({ rows: [], overflow: false });
  });
});

describe('splitPieData', () => {
  it('NULL·숫자 아닌 값은 결측으로 빼고 개수를 센다 — 0 으로 바꾸지 않는다(WD-835)', () => {
    const { pieData, negativeCount, missingCount, nonNumericCount } = splitPieData(
      [{ v: 50 }, { v: -20 }, { v: '30' }, { v: null }, { v: '측정 안 함' }, { v: '' }, { v: ' ' }, { v: true }, { v: undefined }],
      'v',
    );
    expect(negativeCount).toBe(1);
    expect(missingCount).toBe(6);
    // WD-838: 비어 있지 않은 숫자 아님은 '측정 안 함'·true 2개 (null·''·' '·undefined 는 빈 값이라 제외)
    expect(nonNumericCount).toBe(2);
    expect(pieData.map((r) => r.v)).toEqual([50, 30]);
  });

  it('실제 0 은 결측이 아니라 남긴다', () => {
    const { pieData, missingCount } = splitPieData([{ v: 0 }, { v: '0' }, { v: 5 }], 'v');
    expect(missingCount).toBe(0);
    expect(pieData.map((r) => r.v)).toEqual([0, 0, 5]);
  });

  it('total 은 그릴 행(pieData)의 값 합이다 — 음수·결측은 더하지 않는다(WD-841)', () => {
    expect(splitPieData([{ v: 3 }, { v: -1 }, { v: null }, { v: 2 }], 'v').total).toBe(5);
    expect(splitPieData([{ v: 0 }, { v: '0' }], 'v').total).toBe(0);
    expect(splitPieData([], 'v').total).toBe(0);
  });

  it('emptyReason: 합계가 유한하지 않으면 overflow, 아슬아슬하게 유한하면 null(WD-843)', () => {
    expect(splitPieData([{ v: 1e308 }, { v: 1e308 }], 'v').emptyReason).toBe('overflow');
    expect(splitPieData([{ v: 1e307 }, { v: 1e307 }], 'v').emptyReason).toBeNull();
  });

  it('emptyReason: 전부 숫자 아님 → nonNumeric, 합 0 → nothing, 그릴 값 있음 → null(WD-841)', () => {
    expect(splitPieData([{ v: '서울' }, { v: null }], 'v').emptyReason).toBe('nonNumeric');
    expect(splitPieData([{ v: 0 }, { v: -1 }], 'v').emptyReason).toBe('nothing');
    expect(splitPieData([{ v: 3 }, { v: '서울' }], 'v').emptyReason).toBeNull();
  });

  it('모두 결측·음수면 빈 배열', () => {
    expect(splitPieData([{ v: null }, { v: -1 }], 'v')).toEqual({ pieData: [], negativeCount: 1, missingCount: 1, nonNumericCount: 0, total: 0, emptyReason: 'nothing' });
  });

  it('nonNumericCount: 값이 전부 문자열이면 그릴 행·음수 없이 문자 개수만 센다(WD-838)', () => {
    const r = splitPieData([{ v: '서울' }, { v: '부산' }, { v: null }], 'v');
    expect(r.pieData).toEqual([]);
    expect(r.negativeCount).toBe(0);
    expect(r.missingCount).toBe(3);
    expect(r.nonNumericCount).toBe(2);
  });

  it('공백뿐인 값은 빈 값이라 결측으로만 센다(WD-838)', () => {
    const r = splitPieData([{ v: ' ' }, { v: '서울' }], 'v');
    expect(r.missingCount).toBe(2);
    expect(r.nonNumericCount).toBe(1);
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

// WD-838: 공백뿐인 값은 빈 값이다 — 게이지·퍼널 뷰는 isNonNumericValue 가 false 일 때 안내 없이 0 으로 그린다.
describe('공백뿐인 값 (게이지·퍼널, WD-838)', () => {
  it('computeGaugeReading: 첫 값이 공백이면 던지지 않고 0 으로 읽는다', () => {
    expect(computeGaugeReading([{ v: ' ' }], { yAxis: ['v'] } as never)).toEqual({
      value: 0,
      min: 0,
      max: 100,
      invalidRange: false,
    });
  });
});

// WD-848: 최댓값 ≤ 최솟값이거나 범위가 유한하지 않으면 호·바늘 각도가 NaN 이 되므로 invalidRange 로 판정한다.
describe('computeGaugeReading 범위 판정 (WD-848)', () => {
  const read = (config: Record<string, unknown>, v = 50) =>
    computeGaugeReading([{ v }], { yAxis: ['v'], ...config } as never);

  it.each([
    ['최댓값 = 최솟값', { min: 100, max: 100 }],
    ['최댓값 < 최솟값', { min: 100, max: 60 }],
  ])('%s 이면 잘못된 범위', (_name, config) => {
    expect(read(config).invalidRange).toBe('not-increasing');
  });
  // 한쪽만 설정하면 빠진 끝은 120% 규칙으로 정하고, 이미 바르게 그리던 눈금은 그대로 둔다(WD-848)
  it.each([
    [{ min: 99 }, 83, 99, 119],
    [{ min: 100 }, 83, 100, 120],
    [{ min: 200 }, 100, 200, 240],
    [{ min: 1 }, 0.5, 1, 2],
    [{ min: -1000 }, 10, -1000, 12],
    [{ min: -50 }, -60, -50, -40],
    [{ min: 0 }, 0, 0, 100],
    [{ min: 0 }, 50, 0, 60],
    [{ max: 100 }, 50, 0, 100],
    [{ max: 100 }, -20, 0, 100],
    [{ max: -10 }, 5, -12, -10],
    [{ max: 0 }, 5, -6, 0],
    [{ max: 0 }, 0, -100, 0],
    [{ max: 0 }, -1000, -1200, 0],
    [{ max: -10 }, -50, -60, -10],
  ])('한쪽만 설정 %j 값 %s → %s ~ %s', (config, v, min, max) => {
    const r = read(config, v);
    expect(r).toMatchObject({ min, max, invalidRange: false });
    expect(r.max).toBeGreaterThan(r.min);
  });
  it('NaN·Infinity 는 비유한 범위', () => {
    expect(read({ min: 0, max: Number.NaN }).invalidRange).toBe('non-finite');
    expect(read({ min: 0, max: Infinity }).invalidRange).toBe('non-finite');
    expect(read({ min: -Infinity, max: 10 }).invalidRange).toBe('non-finite');
  });
  it('정상 범위는 잘못된 범위가 아니다', () => {
    expect(read({ min: 0, max: 100 }, 70).invalidRange).toBe(false);
  });
});

// WD-852: 천 개 가까운 범주의 파이에서 눈에 안 보이는 작은 조각을 「기타」 한 조각으로 묶고 범례를 상위 항목으로 줄인다.
describe('groupSmallPieSlices (WD-852)', () => {
  /** 이름·값 행 만들기 */
  const rows = (values: number[], prefix = 'c'): Record<string, unknown>[] =>
    values.map((v, i) => ({ name: `${prefix}${i}`, v }));
  const sum = (values: number[]) => values.reduce((a, b) => a + b, 0);

  it('0.5° 미만 조각 2개 이상을 기타 한 조각으로 끝에 묶는다', () => {
    const values = [1000, 800, ...Array.from({ length: 100 }, () => 1)];
    const r = groupSmallPieSlices(rows(values), 'name', 'v', sum(values));
    expect(r.slices).toHaveLength(3);
    const last = r.slices[2];
    expect(last.isOther).toBe(true);
    expect(last.row.name).toBe('기타 (100개)');
    expect(last.row.v).toBe(100);
    expect(last.colorIndex).toBe(-1);
    expect(r.otherCount).toBe(100);
    expect(r.slices[0].colorIndex).toBe(0);
    expect(r.slices[1].colorIndex).toBe(1);
  });

  it('경계: 정확히 0.5° 이상이면 묶지 않는다', () => {
    // total 720 에서 값 1 = 0.5° → 큰 조각, 값 0.4 는 작은 조각(2개 이상이라 묶임)
    const values = [1, 717.8, 0.4, 0.4, 0.4];
    const r = groupSmallPieSlices(rows(values), 'name', 'v', 720);
    expect(r.otherCount).toBe(3);
    expect(r.slices).toHaveLength(3);
    expect(r.slices[0].isOther).toBe(false);
    expect(r.slices[0].row.v).toBe(1);
  });

  it('경계: 0.5° 바로 아래(0.999, total 720)는 작은 조각 묶음에 든다', () => {
    // 0.999 * 360 = 359.64 < 0.5 * 720 = 360 → 작은 조각. 0.4 와 함께 2개라 기타로 묶인다.
    const values = [719.001, 0.999, 0.4];
    const r = groupSmallPieSlices(rows(values), 'name', 'v', 720);
    expect(r.otherCount).toBe(2);
    expect(r.slices).toHaveLength(2);
    expect(r.slices[0].row.v).toBe(719.001);
    expect(r.slices[1].isOther).toBe(true);
  });

  it('기타 합은 부동소수 오차 없이 반올림된다 (0.1 × 3 = 0.3)', () => {
    // 0.1+0.1+0.1 은 0.30000000000000004 — total 1000 에서 0.1 은 0.036° 라 모두 작은 조각이다.
    const values = [999.7, 0.1, 0.1, 0.1];
    const r = groupSmallPieSlices(rows(values), 'name', 'v', 1000);
    expect(r.otherCount).toBe(3);
    expect(r.slices[1].row.v).toBe(0.3);
  });

  it('기타 합이 큰 정수면 반올림하지 않고 그대로 둔다 (1234567890123)', () => {
    // 정수 합에 toPrecision(12) 를 쓰면 1234567890120 으로 끝자리가 바뀐다 — 정수는 그대로 둔다.
    // total 1e15 에서 4.1e11 은 0.15° 라 모두 작은 조각이다.
    const values = [1e15, 411522630041, 411522630041, 411522630041];
    const r = groupSmallPieSlices(rows(values), 'name', 'v', sum(values));
    expect(r.otherCount).toBe(3);
    expect(r.slices[1].row.v).toBe(1234567890123);
  });

  it('작은 조각이 1개면 묶지 않는다', () => {
    const values = [1000, 800, 1];
    const r = groupSmallPieSlices(rows(values), 'name', 'v', sum(values));
    expect(r.otherCount).toBe(0);
    expect(r.slices).toHaveLength(3);
    expect(r.slices.some((s) => s.isOther)).toBe(false);
  });

  it('모두 작으면 기타 하나', () => {
    const values = Array.from({ length: 1000 }, () => 1);
    const r = groupSmallPieSlices(rows(values), 'name', 'v', 1000);
    expect(r.slices).toHaveLength(1);
    expect(r.slices[0].row.name).toBe('기타 (1,000개)');
    expect(r.legend).toEqual([{ name: '기타 (1,000개)', colorIndex: -1, kind: 'other' }]);
  });

  it('범례는 큰 순 50개 + 기타(묶인 수) + 외 k개', () => {
    // 100..159 를 섞인 순서로(7 과 60 은 서로소)
    const big = Array.from({ length: 60 }, (_, i) => 100 + ((i * 7) % 60));
    const small = Array.from({ length: 900 }, () => 0.01);
    const values = [...big, ...small];
    const r = groupSmallPieSlices(rows(values), 'name', 'v', sum(values));
    expect(r.legend).toHaveLength(52);
    expect(r.legend.slice(0, 50).every((l) => l.kind === 'slice')).toBe(true);
    const maxIdx = big.indexOf(159);
    expect(r.legend[0].name).toBe(`c${maxIdx}`);
    expect(r.legend[0].colorIndex).toBe(maxIdx);
    expect(r.legend[50]).toEqual({ name: '기타 (900개)', colorIndex: -1, kind: 'other' });
    expect(r.legend[51]).toEqual({ name: '외 10개', colorIndex: -1, kind: 'rest' });
  });

  it('큰 조각이 50개 이하면 외 항목 없음, 안 묶었으면 기타 항목 없음', () => {
    const values = [500, 300, 200];
    const r = groupSmallPieSlices(rows(values), 'name', 'v', 1000);
    expect(r.legend).toHaveLength(3);
    expect(r.legend.every((l) => l.kind === 'slice')).toBe(true);
  });

  it('큰 조각 60개 + 작은 조각 0개면 기타 없이 50개 + 외 10개', () => {
    const values = Array.from({ length: 60 }, (_, i) => 100 + i);
    const r = groupSmallPieSlices(rows(values), 'name', 'v', sum(values));
    expect(r.otherCount).toBe(0);
    expect(r.legend).toHaveLength(51);
    expect(r.legend.some((l) => l.kind === 'other')).toBe(false);
    expect(r.legend[50]).toEqual({ name: '외 10개', colorIndex: -1, kind: 'rest' });
  });

  it('큰 조각 60개 + 작은 조각 1개(안 묶임)면 그 작은 조각도 후보라 외 11개', () => {
    const values = [...Array.from({ length: 60 }, (_, i) => 100 + i), 0.01];
    const r = groupSmallPieSlices(rows(values), 'name', 'v', sum(values));
    expect(r.otherCount).toBe(0);
    expect(r.legend).toHaveLength(51);
    expect(r.legend.some((l) => l.kind === 'other')).toBe(false);
    expect(r.legend[50]).toEqual({ name: '외 11개', colorIndex: -1, kind: 'rest' });
  });

  it('이름이 「기타」인 실제 행은 일반 조각으로 남는다', () => {
    const data = [
      { name: '기타', v: 900 },
      { name: 'a', v: 1 },
      { name: 'b', v: 1 },
    ];
    const r = groupSmallPieSlices(data, 'name', 'v', 902);
    expect(r.slices[0].row.name).toBe('기타');
    expect(r.slices[0].isOther).toBe(false);
    expect(r.slices[1].isOther).toBe(true);
  });

  it('total 0 이면 그대로', () => {
    const r = groupSmallPieSlices(rows([0, 0, 0]), 'name', 'v', 0);
    expect(r.otherCount).toBe(0);
    expect(r.slices).toHaveLength(3);
  });
});
