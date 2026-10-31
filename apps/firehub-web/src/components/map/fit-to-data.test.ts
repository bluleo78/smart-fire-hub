/**
 * fitMapToData 단위 테스트 — 처음 화면 범위가 GeometryCollection 안의 도형까지 담는지 검증한다(WD-341).
 * maplibre Map 은 fitBounds·flyTo 만 쓰므로 그 둘만 모킹한다.
 */
import type maplibregl from 'maplibre-gl';
import { describe, expect, it, vi } from 'vitest';

import type { GeoJsonFeatureCollection } from '@/types/dataset';

import { fitMapToData } from './fit-to-data';

function createMap() {
  return { fitBounds: vi.fn(), flyTo: vi.fn() };
}

function fit(data: GeoJsonFeatureCollection) {
  const map = createMap();
  fitMapToData(map as unknown as maplibregl.Map, data);
  return map;
}

const seoul = { type: 'Point', coordinates: [126.97, 37.56] };
const busan = { type: 'Point', coordinates: [129.07, 35.18] };
// 이슈 재현 데이터 — 리우 GeometryCollection(점 + 선). coordinates 가 없고 geometries 만 가진다.
const rio = {
  type: 'GeometryCollection',
  geometries: [
    { type: 'Point', coordinates: [-43.2, -22.9] },
    { type: 'LineString', coordinates: [[-43.2, -22.9], [-46.6, -23.5]] },
  ],
};

function collection(...geometries: Record<string, unknown>[]): GeoJsonFeatureCollection {
  return {
    type: 'FeatureCollection',
    features: geometries.map((geometry) => ({ type: 'Feature', geometry, properties: {} })),
  } as GeoJsonFeatureCollection;
}

describe('fitMapToData', () => {
  it('GeometryCollection 안의 도형까지 감싸는 범위로 맞춘다 — 서울·부산·리우가 모두 들어온다', () => {
    const map = fit(collection(seoul, busan, rio));
    expect(map.fitBounds).toHaveBeenCalledTimes(1);
    const [bounds] = map.fitBounds.mock.calls[0];
    // [[minLon, minLat], [maxLon, maxLat]] — 리우 선의 서쪽 끝(-46.6, -23.5)과 부산의 동쪽 끝(129.07)·서울의 북쪽 끝(37.56)
    expect(bounds).toEqual([[-46.6, -23.5], [129.07, 37.56]]);
  });

  it('행이 GeometryCollection 하나뿐이어도 그 안의 도형 범위로 맞춘다', () => {
    const map = fit(collection(rio));
    expect(map.fitBounds.mock.calls[0][0]).toEqual([[-46.6, -23.5], [-43.2, -22.9]]);
  });

  it('GeometryCollection 안에 점 하나뿐이면 그 점으로 이동한다', () => {
    const map = fit(collection({ type: 'GeometryCollection', geometries: [{ type: 'Point', coordinates: [-43.2, -22.9] }] }));
    expect(map.flyTo).toHaveBeenCalledWith({ center: [-43.2, -22.9], zoom: 13 });
    expect(map.fitBounds).not.toHaveBeenCalled();
  });

  it('도형이 없으면 아무것도 하지 않는다', () => {
    const map = fit(collection());
    expect(map.fitBounds).not.toHaveBeenCalled();
    expect(map.flyTo).not.toHaveBeenCalled();
  });
});
