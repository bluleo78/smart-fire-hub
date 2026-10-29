import type { GeoJsonFeatureCollection } from '@/types/dataset';

/**
 * DataQueryResponse의 rows를 GeoJSON FeatureCollection으로 변환.
 * MapLibre GL JS의 GeoJSON source에 직접 사용 가능.
 * GEOMETRY 컬럼 값은 ST_AsGeoJSON()이 반환한 JSON 문자열이므로 파싱 처리.
 */
export function toFeatureCollection(
  rows: Record<string, unknown>[],
  geometryColumn: string,
): GeoJsonFeatureCollection {
  return {
    type: 'FeatureCollection',
    features: rows
      .filter(row => row[geometryColumn] != null)
      .flatMap(row => {
        const rawGeom = row[geometryColumn];
        // 비공간 데이터(JSON 파싱 실패)는 피처에서 제외 — 크래시 방지 (#77)
        try {
          const geometry =
            typeof rawGeom === 'string'
              ? (JSON.parse(rawGeom) as Record<string, unknown>)
              : (rawGeom as Record<string, unknown>);
          // 좌표가 없거나 WGS84 범위 밖인 도형은 지도에 그릴 수 없어 뺀다(WD-340)
          if (!hasDrawableCoordinates(geometry)) return [];
          return [{
            type: 'Feature' as const,
            geometry,
            properties: Object.fromEntries(
              Object.entries(row).filter(([k]) => k !== geometryColumn),
            ),
          }];
        } catch {
          return [];
        }
      }),
  };
}

/**
 * 도형의 모든 위치([경도, 위도, …])를 펼친다. GeometryCollection 은 안의 도형마다 펼친다.
 * coordinates 가 배열이 아니거나 중첩 구조가 깨졌으면 null — 그릴 수 없는 도형이다.
 */
export function geometryPositions(geometry: unknown): unknown[][] | null {
  const geom = geometry as { type?: unknown; coordinates?: unknown; geometries?: unknown } | null;
  if (!geom || typeof geom !== 'object') return null;
  if (geom.type === 'GeometryCollection') {
    if (!Array.isArray(geom.geometries)) return null;
    const all: unknown[][] = [];
    for (const child of geom.geometries) {
      const positions = geometryPositions(child);
      if (!positions) return null;
      all.push(...positions);
    }
    return all;
  }
  if (!Array.isArray(geom.coordinates)) return null;
  const out: unknown[][] = [];
  // 첫 원소가 배열이 아니면 위치 하나, 배열이면 한 단계 더 내려간다(Point·LineString·Polygon·Multi* 공통)
  const walk = (node: unknown[]): boolean => {
    if (node.length > 0 && !Array.isArray(node[0])) {
      out.push(node);
      return true;
    }
    return node.every((child) => Array.isArray(child) && walk(child));
  };
  return walk(geom.coordinates) ? out : null;
}

/** WGS84 범위(경도 -180~180, 위도 -90~90, 경계 포함) 안의 숫자 위치인지 */
export function isValidPosition(pos: unknown[]): boolean {
  const [lon, lat] = pos;
  return (
    typeof lon === 'number' && typeof lat === 'number' &&
    Number.isFinite(lon) && Number.isFinite(lat) &&
    lon >= -180 && lon <= 180 && lat >= -90 && lat <= 90
  );
}

/**
 * 지도에 그릴 수 있는 도형인지 — 위치가 하나 이상 있고 모두 WGS84 범위 안이어야 한다(WD-340).
 * 서버가 막기 전에 저장된 POINT(999 999) 같은 값이 섞이면 maplibre fitBounds 가 「Invalid LngLat latitude」를 던져
 * 화면 전체가 오류 화면이 되고, 경도만 벗어난 값은 360 으로 감겨 엉뚱한 곳에 그려진다. 그 도형만 빼고 나머지를 그린다.
 */
export function hasDrawableCoordinates(geometry: unknown): boolean {
  // 위치 배열을 모으지 않고 내려가며 검사해 첫 잘못된 위치에서 바로 멈춘다(행마다 도는 경로라 할당을 줄인다)
  const geom = geometry as { type?: unknown; coordinates?: unknown; geometries?: unknown } | null;
  if (!geom || typeof geom !== 'object') return false;
  if (geom.type === 'GeometryCollection') {
    return Array.isArray(geom.geometries) && geom.geometries.length > 0 && geom.geometries.every(hasDrawableCoordinates);
  }
  const valid = (node: unknown): boolean => {
    if (!Array.isArray(node) || node.length === 0) return false;
    return Array.isArray(node[0]) ? node.every(valid) : isValidPosition(node);
  };
  return valid(geom.coordinates);
}
