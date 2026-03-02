import { AlertCircle, Info } from 'lucide-react';
import type maplibregl from 'maplibre-gl';
import { useCallback, useMemo, useState } from 'react';

import { FeaturePopup } from '@/components/map/FeaturePopup';
import { GeoJsonLayer } from '@/components/map/GeoJsonLayer';
import { MapView } from '@/components/map/MapView';
import { useDatasetData } from '@/hooks/queries/useDatasets';
import { toFeatureCollection } from '@/lib/geo-utils';
import type { DatasetDetailResponse, GeoJsonFeature } from '@/types/dataset';

interface DatasetMapTabProps {
  dataset: DatasetDetailResponse;
  datasetId: number;
}

const MAP_PAGE_SIZE = 2000;

export function DatasetMapTab({ dataset, datasetId }: DatasetMapTabProps) {
  const geometryColumn = dataset.columns.find(c => c.dataType === 'GEOMETRY')?.columnName;

  const { data, isLoading, isError } = useDatasetData(datasetId, {
    size: MAP_PAGE_SIZE,
  });

  const [mapInstance, setMapInstance] = useState<maplibregl.Map | null>(null);
  const [selectedFeature, setSelectedFeature] = useState<GeoJsonFeature | null>(null);

  const rows = useMemo(() => {
    if (!data) return [];
    return data.pages.flatMap(p => p.rows);
  }, [data]);

  const totalElements = data?.pages[0]?.totalElements ?? 0;

  const featureCollection = useMemo(() => {
    if (!geometryColumn) return { type: 'FeatureCollection' as const, features: [] };
    return toFeatureCollection(rows, geometryColumn);
  }, [rows, geometryColumn]);

  const handleMapReady = useCallback((map: maplibregl.Map) => {
    setMapInstance(map);
  }, []);

  const handleFeatureClick = useCallback((feature: GeoJsonFeature) => {
    setSelectedFeature(feature);
  }, []);

  const handlePopupClose = useCallback(() => {
    setSelectedFeature(null);
  }, []);

  if (!geometryColumn) {
    return (
      <div className="flex items-center gap-2 text-muted-foreground p-6">
        <AlertCircle className="h-4 w-4 flex-shrink-0" />
        <span>GEOMETRY 컬럼이 없습니다.</span>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {/* Warning banner: more than MAP_PAGE_SIZE rows */}
      {totalElements > MAP_PAGE_SIZE && (
        <div className="flex items-start gap-2 rounded-md border border-yellow-200 bg-yellow-50 dark:border-yellow-800 dark:bg-yellow-950/30 px-3 py-2 text-sm text-yellow-800 dark:text-yellow-300">
          <Info className="h-4 w-4 flex-shrink-0 mt-0.5" />
          <span>
            전체 {totalElements.toLocaleString()}건 중 최대 {MAP_PAGE_SIZE.toLocaleString()}건만 지도에 표시됩니다.
          </span>
        </div>
      )}

      {isError && (
        <div className="flex items-center gap-2 text-destructive p-4">
          <AlertCircle className="h-4 w-4 flex-shrink-0" />
          <span>데이터를 불러오는 중 오류가 발생했습니다.</span>
        </div>
      )}

      <div className="relative rounded-lg overflow-hidden border" style={{ height: '500px' }}>
        {isLoading && (
          <div className="absolute inset-0 z-10 flex items-center justify-center bg-background/60">
            <span className="text-sm text-muted-foreground">데이터 로딩 중...</span>
          </div>
        )}

        <MapView className="w-full h-full" onMapReady={handleMapReady} />

        {mapInstance && (
          <>
            <GeoJsonLayer
              map={mapInstance}
              data={featureCollection}
              onFeatureClick={handleFeatureClick}
            />
            <FeaturePopup
              map={mapInstance}
              feature={selectedFeature}
              onClose={handlePopupClose}
            />
          </>
        )}
      </div>

      <p className="text-xs text-muted-foreground">
        지도: <a href="https://openfreemap.org" target="_blank" rel="noopener noreferrer" className="underline">OpenFreeMap</a> &middot; 데이터: {featureCollection.features.length.toLocaleString()}건 표시
      </p>
    </div>
  );
}
