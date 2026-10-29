import type maplibregl from 'maplibre-gl';
import { useEffect, useMemo, useRef } from 'react';

import type { GeoJsonFeatureCollection } from '@/types/dataset';

import { fitMapToData } from './fit-to-data';
import { HEAT_WEIGHT_PROP, withNormalizedWeight } from './heatmap-weight';

/**
 * maplibre native heatmap layer 마운트 컴포넌트.
 *
 * - source id: 'heatmap-src', layer id: 'heatmap-lyr' (MapView 당 1개 가정).
 * - 가중치는 데이터의 최솟값~최댓값으로 정규화해 feature 속성(HEAT_WEIGHT_PROP)에 실어 보낸다(WD-354).
 *   원값을 그대로 heatmap-weight 에 넣으면 작은 값은 사라지고 큰 값은 포화됐다 — heatmap-weight.ts 참고.
 * - 데이터/가중치 변경 시 source.setData 로 in-place 갱신해 불필요한 add/remove 를 피한다.
 * - 언마운트 시 layer → source 순서로 제거.
 */
interface HeatmapLayerProps {
  map: maplibregl.Map;
  data: GeoJsonFeatureCollection;
  weightProperty?: string;
}

const SOURCE_ID = 'heatmap-src';
const LAYER_ID = 'heatmap-lyr';

/** 정규화된 가중치 속성을 그대로 읽는다 — 정규화는 withNormalizedWeight 가 데이터에 미리 해 둔다 */
const WEIGHT_EXPRESSION = ['get', HEAT_WEIGHT_PROP];

function buildPaint(): Record<string, unknown> {
  return {
    'heatmap-weight': WEIGHT_EXPRESSION,
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
  // 가중치를 정규화해 실은 데이터 — 가중치 컬럼이 바뀌어도 이 데이터만 다시 보낸다
  const weighted = useMemo(() => withNormalizedWeight(data, weightProperty), [data, weightProperty]);
  // 스타일 재로드(테마 전환) 때 다시 붙일 최신 데이터. 마운트 시점 값을 붙들면 바꾼 가중치가 처음 값으로 돌아간다.
  // 갱신은 아래 setData effect 에서 한다(렌더 중에 ref 를 바꾸지 않는다).
  const weightedRef = useRef(weighted);

  // mount: source + layer 추가, unmount: 제거
  useEffect(() => {
    const ensureMounted = () => {
      if (!map.getSource(SOURCE_ID)) {
        map.addSource(SOURCE_ID, { type: 'geojson', data: weightedRef.current as never });
      }
      if (!map.getLayer(LAYER_ID)) {
        map.addLayer({
          id: LAYER_ID,
          type: 'heatmap',
          source: SOURCE_ID,
          paint: buildPaint(),
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
    // mount/unmount 1회 — 데이터·가중치 변경은 아래 effect 에서 in-place 갱신
  }, [map]);

  // 데이터·가중치 변경 시 source 를 갱신한다
  useEffect(() => {
    weightedRef.current = weighted;
    const src = map.getSource(SOURCE_ID) as maplibregl.GeoJSONSource | undefined;
    if (src && typeof src.setData === 'function') {
      src.setData(weighted as never);
    }
  }, [map, weighted]);

  // 데이터가 바뀌면 지도를 데이터 범위로 맞춘다 — 점 모드(GeoJsonLayer)와 같은 동작(WD-348).
  // 맞추지 않으면 기본 위치(서울)에 머물러, 서울 밖 데이터는 행이 있는데도 빈 지도로 보였다.
  // 가중치만 바뀔 때는 맞추지 않는다 — 사용자가 옮겨 둔 화면이 가중치를 바꿀 때마다 튀지 않게.
  useEffect(() => {
    fitMapToData(map, data);
  }, [map, data]);

  return null;
}
