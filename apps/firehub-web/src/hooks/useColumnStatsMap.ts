import { useMemo } from 'react';
import { useColumnStats } from '@/hooks/queries/useDatasets';
import type { ColumnStatsResponse } from '@/types/dataset';

export function useColumnStatsMap(
  datasetId: number,
  enabled = true
): Map<string, ColumnStatsResponse> {
  const { data: columnStats } = useColumnStats(datasetId, enabled);

  return useMemo(() => {
    const map = new Map<string, ColumnStatsResponse>();
    if (columnStats) {
      for (const s of columnStats) {
        map.set(s.columnName, s);
      }
    }
    return map;
  }, [columnStats]);
}
