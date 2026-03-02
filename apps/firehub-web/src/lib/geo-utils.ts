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
      .map(row => {
        const rawGeom = row[geometryColumn];
        // Backend returns geometry as JSON string from ST_AsGeoJSON()
        const geometry =
          typeof rawGeom === 'string'
            ? (JSON.parse(rawGeom) as Record<string, unknown>)
            : (rawGeom as Record<string, unknown>);
        return {
          type: 'Feature' as const,
          geometry,
          properties: Object.fromEntries(
            Object.entries(row).filter(([k]) => k !== geometryColumn),
          ),
        };
      }),
  };
}
