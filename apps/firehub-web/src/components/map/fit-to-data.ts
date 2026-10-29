import type maplibregl from 'maplibre-gl';

import type { GeoJsonFeatureCollection } from '@/types/dataset';

/**
 * 데이터 범위에 맞춰 지도를 이동·확대한다 — 점 하나면 그 점으로(zoom 13), 여럿이면 감싸는 영역으로.
 * 점(GeoJsonLayer)·히트맵(HeatmapLayer) 모드가 함께 쓴다(WD-348 — 히트맵만 맞춤이 없어 서울 밖 데이터가 빈 지도로 보였다).
 */
export function fitMapToData(map: maplibregl.Map, data: GeoJsonFeatureCollection) {
  if (data.features.length === 0) return;
  let minLon = Infinity, minLat = Infinity, maxLon = -Infinity, maxLat = -Infinity;

  for (const feature of data.features) {
    const geom = feature.geometry as { type: string; coordinates: unknown };
    if (!geom || !geom.coordinates) continue;
    const coords = flattenCoordinates(geom.type, geom.coordinates);
    for (const [lon, lat] of coords) {
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

/** geometry 종류별 좌표 배열을 [lon, lat] 목록으로 펼친다. */
function flattenCoordinates(geomType: string, coords: unknown): [number, number][] {
  if (geomType === 'Point') return [coords as [number, number]];
  if (geomType === 'LineString' || geomType === 'MultiPoint') return coords as [number, number][];
  if (geomType === 'Polygon' || geomType === 'MultiLineString') {
    return (coords as [number, number][][]).flat();
  }
  if (geomType === 'MultiPolygon') {
    return (coords as [number, number][][][]).flat(2);
  }
  return [];
}
