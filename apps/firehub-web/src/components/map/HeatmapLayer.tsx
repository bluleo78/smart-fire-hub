import type maplibregl from 'maplibre-gl';
import { useEffect } from 'react';

import type { GeoJsonFeatureCollection } from '@/types/dataset';

/**
 * maplibre native heatmap layer 마운트 컴포넌트.
 *
 * - source id: 'heatmap-src', layer id: 'heatmap-lyr' (MapView 당 1개 가정).
 * - weightProperty 가 있으면 ['to-number', ['get', col], 0] 으로 가중치 컬럼 값을
 *   숫자로 강제하여 NULL/문자열 행은 0 으로 처리.
 * - 데이터/가중치 변경 시 source.setData 또는 setPaintProperty 로 in-place 갱신해
 *   불필요한 add/remove 를 피한다.
 * - 언마운트 시 layer → source 순서로 제거.
 */
interface HeatmapLayerProps {
  map: maplibregl.Map;
  data: GeoJsonFeatureCollection;
  weightProperty?: string;
}

const SOURCE_ID = 'heatmap-src';
const LAYER_ID = 'heatmap-lyr';

function buildWeightExpression(weightProperty?: string): unknown {
  if (!weightProperty) return 1;
  return ['to-number', ['get', weightProperty], 0];
}

function buildPaint(weightProperty?: string): Record<string, unknown> {
  return {
    'heatmap-weight': buildWeightExpression(weightProperty),
    // 줌이 깊어질수록 intensity 증가
    'heatmap-intensity': [
      'interpolate', ['linear'], ['zoom'],
      0, 1,
      15, 3,
    ],
    // 0 (투명) → 청 → 녹 → 황 → 적
    'heatmap-color': [
      'interpolate', ['linear'], ['heatmap-density'],
      0, 'rgba(33,102,172,0)',
      0.2, 'rgba(103,169,207,0.6)',
      0.4, 'rgba(209,229,240,0.7)',
      0.6, 'rgba(253,219,199,0.8)',
      0.8, 'rgba(239,138,98,0.9)',
      1, 'rgba(178,24,43,1)',
    ],
    // 줌이 깊어질수록 픽셀 반경 확대
    'heatmap-radius': [
      'interpolate', ['linear'], ['zoom'],
      0, 4,
      15, 30,
    ],
    'heatmap-opacity': 0.85,
  };
}

export function HeatmapLayer({ map, data, weightProperty }: HeatmapLayerProps) {
  // mount: source + layer 추가, unmount: 제거
  useEffect(() => {
    const ensureMounted = () => {
      if (!map.getSource(SOURCE_ID)) {
        map.addSource(SOURCE_ID, { type: 'geojson', data: data as never });
      }
      if (!map.getLayer(LAYER_ID)) {
        map.addLayer({
          id: LAYER_ID,
          type: 'heatmap',
          source: SOURCE_ID,
          paint: buildPaint(weightProperty),
        });
      }
    };

    ensureMounted();
    // 테마/스타일 재로드 시 layer 가 초기화되므로 재마운트 (#258 패턴)
    map.on('styledata', ensureMounted);

    return () => {
      map.off('styledata', ensureMounted);
      if (map.getLayer(LAYER_ID)) map.removeLayer(LAYER_ID);
      if (map.getSource(SOURCE_ID)) map.removeSource(SOURCE_ID);
    };
    // mount/unmount 1회 — data/weightProperty 변경은 별도 effect 에서 in-place 갱신
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map]);

  // data 변경 시 source 만 갱신
  useEffect(() => {
    const src = map.getSource(SOURCE_ID) as maplibregl.GeoJSONSource | undefined;
    if (src && typeof src.setData === 'function') {
      src.setData(data as never);
    }
  }, [map, data]);

  // weightProperty 변경 시 paint 만 갱신
  useEffect(() => {
    if (!map.getLayer(LAYER_ID)) return;
    map.setPaintProperty(LAYER_ID, 'heatmap-weight', buildWeightExpression(weightProperty));
  }, [map, weightProperty]);

  return null;
}
