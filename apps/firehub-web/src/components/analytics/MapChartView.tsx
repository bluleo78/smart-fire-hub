import type maplibregl from 'maplibre-gl';
import { useMemo, useState } from 'react';

import { FeaturePopup } from '@/components/map/FeaturePopup';
import { GeoJsonLayer } from '@/components/map/GeoJsonLayer';
import { MapView } from '@/components/map/MapView';
import { toFeatureCollection } from '@/lib/geo-utils';
import type { GeoJsonFeature } from '@/types/dataset';

import type { ChartConfig } from '../../types/analytics';

interface MapChartViewProps {
  config: ChartConfig;
  data: Record<string, unknown>[];
  height?: number;
}

export function MapChartView({ config, data, height = 400 }: MapChartViewProps) {
  const [mapInstance, setMapInstance] = useState<maplibregl.Map | null>(null);
  const [selectedFeature, setSelectedFeature] = useState<GeoJsonFeature | null>(null);

  const spatialColumn = config.spatialColumn;

  const featureCollection = useMemo(() => {
    if (!spatialColumn || data.length === 0) {
      return { type: 'FeatureCollection' as const, features: [] };
    }
    return toFeatureCollection(data, spatialColumn);
  }, [data, spatialColumn]);

  if (!spatialColumn) {
    return (
      <div
        className="flex items-center justify-center text-muted-foreground text-sm"
        style={{ height }}
      >
        공간 컬럼을 설정하세요.
      </div>
    );
  }

  if (data.length === 0) {
    return (
      <div
        className="flex items-center justify-center text-muted-foreground text-sm"
        style={{ height }}
      >
        데이터가 없습니다.
      </div>
    );
  }

  return (
    <div className="relative rounded-lg overflow-hidden" style={{ height }}>
      <MapView className="w-full h-full" onMapReady={setMapInstance} />
      {mapInstance && (
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
      )}
    </div>
  );
}
