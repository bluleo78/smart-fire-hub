import type maplibregl from 'maplibre-gl';
import { useMemo, useState } from 'react';

import { FeaturePopup } from '@/components/map/FeaturePopup';
import { GeoJsonLayer } from '@/components/map/GeoJsonLayer';
import { HeatmapLayer } from '@/components/map/HeatmapLayer';
import { MapView } from '@/components/map/MapView';
import { toFeatureCollection } from '@/lib/geo-utils';
import type { GeoJsonFeature } from '@/types/dataset';

import type { ChartConfig } from '../../types/analytics';

// heatmap 모드에서 행 수가 이 값을 초과하면 inline 경고 배지 노출. 렌더링은 시도. (#119 Risk)
const HEATMAP_ROW_WARN = 50_000;

interface MapChartViewProps {
  config: ChartConfig;
  data: Record<string, unknown>[];
  height?: number;
}

export function MapChartView({ config, data, height }: MapChartViewProps) {
  const [mapInstance, setMapInstance] = useState<maplibregl.Map | null>(null);
  const [selectedFeature, setSelectedFeature] = useState<GeoJsonFeature | null>(null);

  const spatialColumn = config.spatialColumn;

  // featureCollection과 파싱 오류 여부를 함께 계산 (#77)
  const { featureCollection, hasParseError } = useMemo(() => {
    if (!spatialColumn || data.length === 0) {
      return { featureCollection: { type: 'FeatureCollection' as const, features: [] }, hasParseError: false };
    }
    const collection = toFeatureCollection(data, spatialColumn);
    // 데이터는 있지만 피처가 0개면 비공간 컬럼 선택으로 간주
    const hasParseError = data.length > 0 && collection.features.length === 0;
    return { featureCollection: collection, hasParseError };
  }, [data, spatialColumn]);

  if (!spatialColumn) {
    return (
      <div
        className="flex items-center justify-center text-muted-foreground text-sm"
        style={{ height: height ?? '100%' }}
      >
        공간 컬럼을 설정하세요.
      </div>
    );
  }

  if (data.length === 0) {
    return (
      <div
        className="flex items-center justify-center text-muted-foreground text-sm"
        style={{ height: height ?? '100%' }}
      >
        데이터가 없습니다.
      </div>
    );
  }

  // 비공간 컬럼 선택 시 — 모든 행에서 geometry 파싱 실패 (#77)
  if (hasParseError) {
    return (
      <div
        className="flex items-center justify-center text-muted-foreground text-sm"
        style={{ height: height ?? '100%' }}
      >
        선택한 컬럼에 공간 데이터가 없습니다.
      </div>
    );
  }

  const isHeatmap = config.mapDisplayMode === 'heatmap';
  const showRowWarning = isHeatmap && data.length > HEATMAP_ROW_WARN;

  return (
    <div className="relative rounded-lg overflow-hidden" style={{ height: height ?? '100%' }}>
      <MapView className="w-full h-full" onMapReady={setMapInstance} />
      {mapInstance && (
        isHeatmap ? (
          <HeatmapLayer
            map={mapInstance}
            data={featureCollection}
            weightProperty={config.weightColumn}
          />
        ) : (
          <>
            <GeoJsonLayer
              map={mapInstance}
              data={featureCollection}
              onFeatureClick={setSelectedFeature}
            />
            <FeaturePopup
              map={mapInstance}
              feature={selectedFeature}
              onClose={() => setSelectedFeature(null)}
            />
          </>
        )
      )}
      {showRowWarning && (
        <div
          className="absolute top-2 right-2 z-10 rounded-md bg-yellow-100/90 text-yellow-900 px-2 py-1 text-xs shadow"
          role="status"
          aria-live="polite"
          data-testid="heatmap-row-warning"
        >
          데이터 {data.length.toLocaleString()}행 — 렌더링 성능 저하 가능
        </div>
      )}
    </div>
  );
}
