import type maplibregl from 'maplibre-gl';

import { geometryPositions, isValidPosition } from '@/lib/geo-utils';
import type { GeoJsonFeatureCollection } from '@/types/dataset';

/**
 * 데이터 범위에 맞춰 지도를 이동·확대한다 — 점 하나면 그 점으로(zoom 13), 여럿이면 감싸는 영역으로.
 * 점(GeoJsonLayer)·히트맵(HeatmapLayer) 모드가 함께 쓴다(WD-348 — 히트맵만 맞춤이 없어 서울 밖 데이터가 빈 지도로 보였다).
 */
export function fitMapToData(map: maplibregl.Map, data: GeoJsonFeatureCollection) {
  if (data.features.length === 0) return;
  let minLon = Infinity, minLat = Infinity, maxLon = -Infinity, maxLat = -Infinity;

  // 그릴 수 없는 도형은 toFeatureCollection 이 이미 뺀다(WD-340). 다른 경로로 들어온 범위 밖 위치가 있어도
  // maplibre 가 경계 계산에서 예외(Invalid LngLat)를 던져 화면 전체가 깨지지 않게 유효한 좌표만 쓴다 — 점·히트맵 모드 공통.
  for (const feature of data.features) {
    const positions = (geometryPositions(feature.geometry) ?? []).filter(isValidPosition) as [number, number][];
    for (const [lon, lat] of positions) {
      if (lon < minLon) minLon = lon;
      if (lat < minLat) minLat = lat;
      if (lon > maxLon) maxLon = lon;
      if (lat > maxLat) maxLat = lat;
    }
  }

  if (minLon === Infinity) return;
  if (minLon === maxLon && minLat === maxLat) {
    map.flyTo({ center: [minLon, minLat], zoom: 13 });
  } else {
    map.fitBounds([[minLon, minLat], [maxLon, maxLat]], { padding: 60, maxZoom: 15 });
  }
}
