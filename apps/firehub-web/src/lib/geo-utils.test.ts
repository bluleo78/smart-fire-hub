/**
 * geo-utils 단위 테스트 — rows → GeoJSON FeatureCollection 변환.
 */
import { describe, expect, it } from 'vitest';

import { geometryPositions, hasDrawableCoordinates, toFeatureCollection } from './geo-utils';

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

describe('지도에 그릴 수 없는 도형 제외 (WD-340)', () => {
  it('좌표가 WGS84 범위 밖인 행은 빼고 나머지는 남긴다 — 경계값은 포함', () => {
    const rows = [
      { id: 1, geom: '{"type":"Point","coordinates":[999,999]}' },
      { id: 2, geom: '{"type":"Point","coordinates":[400,37]}' },
      { id: 3, geom: '{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,91],[0,0]]]}' },
      { id: 4, geom: '{"type":"Point","coordinates":[180,-90]}' },
      { id: 5, geom: '{"type":"LineString","coordinates":[[126.9,37.5],[129.0,35.1]]}' },
    ];
    const fc = toFeatureCollection(rows, 'geom');
    expect(fc.features.map((f) => f.properties.id)).toEqual([4, 5]);
  });

  it('좌표가 없거나 구조가 깨진 도형, 숫자가 아닌 좌표도 뺀다', () => {
    const rows = [
      { id: 1, geom: { type: 'Point', coordinates: null } },
      { id: 2, geom: { type: 'Foo' } },
      { id: 3, geom: { type: 'Point', coordinates: [127, '37'] } },
      { id: 4, geom: { type: 'MultiPoint', coordinates: [] } },
    ];
    expect(toFeatureCollection(rows, 'geom').features).toHaveLength(0);
  });

  it('GeometryCollection 은 안의 도형이 모두 범위 안일 때만 남긴다', () => {
    const ok = { type: 'GeometryCollection', geometries: [{ type: 'Point', coordinates: [127, 37] }] };
    const bad = {
      type: 'GeometryCollection',
      geometries: [{ type: 'Point', coordinates: [127, 37] }, { type: 'Point', coordinates: [0, 95] }],
    };
    expect(hasDrawableCoordinates(ok)).toBe(true);
    expect(hasDrawableCoordinates(bad)).toBe(false);
    expect(geometryPositions(ok)).toEqual([[127, 37]]);
  });
});
