/**
 * geo-utils 단위 테스트 — rows → GeoJSON FeatureCollection 변환.
 */
import { describe, expect, it } from 'vitest';

import { toFeatureCollection } from './geo-utils';

describe('toFeatureCollection', () => {
  it('빈 배열은 빈 FeatureCollection', () => {
    const fc = toFeatureCollection([], 'geom');
    expect(fc.type).toBe('FeatureCollection');
    expect(fc.features).toHaveLength(0);
  });

  it('geometry가 null인 row는 필터링', () => {
    const rows = [
      { id: 1, geom: null, name: 'a' },
      { id: 2, geom: undefined, name: 'b' },
    ];
    const fc = toFeatureCollection(rows, 'geom');
    expect(fc.features).toHaveLength(0);
  });

  it('JSON 문자열 geometry를 파싱', () => {
    const rows = [
      {
        id: 1,
        geom: JSON.stringify({ type: 'Point', coordinates: [127.0, 37.5] }),
        name: 'Seoul',
      },
    ];
    const fc = toFeatureCollection(rows, 'geom');
    expect(fc.features).toHaveLength(1);
    expect(fc.features[0].type).toBe('Feature');
    expect(fc.features[0].geometry).toEqual({
      type: 'Point',
      coordinates: [127.0, 37.5],
    });
    expect(fc.features[0].properties).toEqual({ id: 1, name: 'Seoul' });
  });

  it('이미 object인 geometry는 그대로 사용', () => {
    const geom = { type: 'Point', coordinates: [0, 0] };
    const rows = [{ id: 1, geom, foo: 'bar' }];
    const fc = toFeatureCollection(rows, 'geom');
    expect(fc.features[0].geometry).toEqual(geom);
    expect(fc.features[0].properties).toEqual({ id: 1, foo: 'bar' });
  });

  it('geometry 컬럼은 properties에서 제외', () => {
    const rows = [
      {
        id: 1,
        geom: { type: 'Point', coordinates: [0, 0] },
      },
    ];
    const fc = toFeatureCollection(rows, 'geom');
    expect(fc.features[0].properties).not.toHaveProperty('geom');
  });
});
