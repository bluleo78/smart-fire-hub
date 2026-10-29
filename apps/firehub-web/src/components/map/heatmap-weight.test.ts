/**
 * 히트맵 가중치 정규화 단위 테스트(WD-354) — 이슈의 세 컬럼(big_w·tiny_w·label)을 그대로 재현한다.
 */
import { describe, expect, it } from 'vitest';

import type { GeoJsonFeatureCollection } from '@/types/dataset';

import { HEAT_WEIGHT_PROP, WEIGHT_MIN, weightRange, withNormalizedWeight } from './heatmap-weight';

function fc(rows: Record<string, unknown>[]): GeoJsonFeatureCollection {
  return {
    type: 'FeatureCollection',
    features: rows.map((properties, i) => ({
      type: 'Feature',
      geometry: { type: 'Point', coordinates: [127 + i * 0.01, 37.5] },
      properties,
    })),
  };
}

const weights = (data: GeoJsonFeatureCollection) => data.features.map((f) => f.properties?.[HEAT_WEIGHT_PROP]);

describe('withNormalizedWeight', () => {
  it('큰 값(1·100000)도 포화되지 않고 상대 크기대로 WEIGHT_MIN~1 로 옮긴다', () => {
    const data = fc([{ big_w: 1 }, { big_w: 50000.5 }, { big_w: 100000 }]);
    const w = weights(withNormalizedWeight(data, 'big_w')) as number[];
    expect(w[0]).toBeCloseTo(WEIGHT_MIN);
    expect(w[1]).toBeCloseTo(WEIGHT_MIN + 0.5 * (1 - WEIGHT_MIN));
    expect(w[2]).toBe(1);
  });

  it('작은 값(0.0001~0.04)도 사라지지 않고 가장 큰 값이 1 이 된다 — DECIMAL 문자열도 숫자로 읽는다', () => {
    const data = fc([{ tiny_w: '0.0001' }, { tiny_w: 0.04 }]);
    expect(weights(withNormalizedWeight(data, 'tiny_w'))).toEqual([WEIGHT_MIN, 1]);
  });

  it('숫자 값이 없는 컬럼(문자)은 균등 가중치 1 — 히트맵이 사라지지 않는다', () => {
    const data = fc([{ label: '가' }, { label: '나' }]);
    expect(weightRange(data, 'label')).toBeNull();
    expect(weights(withNormalizedWeight(data, 'label'))).toEqual([1, 1]);
  });

  it('값이 모두 같으면 1, 숫자 컬럼의 NULL·빈 문자열 행은 0', () => {
    expect(weights(withNormalizedWeight(fc([{ w: 5 }, { w: 5 }]), 'w'))).toEqual([1, 1]);
    expect(weights(withNormalizedWeight(fc([{ w: 1 }, { w: null }, { w: '' }, { w: 3 }]), 'w'))).toEqual([
      WEIGHT_MIN,
      0,
      0,
      1,
    ]);
  });

  it('가중치 컬럼이 없으면 모두 1 이고, 원래 속성은 그대로 둔다', () => {
    const out = withNormalizedWeight(fc([{ name: 'A' }]));
    expect(out.features[0].properties).toEqual({ name: 'A', [HEAT_WEIGHT_PROP]: 1 });
  });
});
