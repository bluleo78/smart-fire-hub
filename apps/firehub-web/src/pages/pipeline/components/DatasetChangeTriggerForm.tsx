import { useId, useMemo } from 'react';

import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { useDatasets } from '@/hooks/queries/useDatasets';

import DatasetCombobox from './DatasetCombobox';

interface DatasetChangeTriggerFormProps {
  config: {
    datasetIds: number[];
    pollingIntervalSeconds: number;
    debounceSeconds: number;
  };
  onChange: (config: DatasetChangeTriggerFormProps['config']) => void;
  errors?: Record<string, string>;
}

export default function DatasetChangeTriggerForm({ config, onChange, errors }: DatasetChangeTriggerFormProps) {
  const { data: datasetsData } = useDatasets({ size: 1000 });
  // 접근성: 라벨↔입력 연결용 id 접두사 (#432). 추가/수정 다이얼로그 양쪽에서 렌더되므로 useId.
  const baseId = useId();
  // DatasetCombobox 는 Popover 트리거 버튼(labelable)에 id 를 심어 라벨과 연결한다.
  const watchDatasetsId = `${baseId}-watch-datasets`;
  const pollingId = `${baseId}-polling`;
  const pollingHelpId = `${baseId}-polling-help`;
  const pollingErrorId = `${baseId}-polling-error`;
  const debounceId = `${baseId}-debounce`;
  const debounceHelpId = `${baseId}-debounce-help`;
  const debounceErrorId = `${baseId}-debounce-error`;

  const datasetOptions = useMemo(
    () =>
      datasetsData?.content?.map((d) => ({
        id: d.id,
        name: d.name,
        tableName: d.tableName,
      })) ?? [],
    [datasetsData],
  );

  return (
    <div className="space-y-4">
      <div className="space-y-1.5">
        <Label htmlFor={watchDatasetsId}>감시 대상 데이터셋</Label>
        <DatasetCombobox
          id={watchDatasetsId}
          mode="multi"
          datasets={datasetOptions}
          value={config.datasetIds}
          onChange={(datasetIds) => onChange({ ...config, datasetIds })}
        />
        {errors?.datasetIds && (
          <p className="text-sm text-destructive">{errors.datasetIds}</p>
        )}
      </div>

      <div className="space-y-1.5">
        <Label htmlFor={pollingId}>폴링 주기 (초)</Label>
        <Input
          id={pollingId}
          type="number"
          min={30}
          max={3600}
          value={config.pollingIntervalSeconds}
          /* 범위 안내와 오류 문구를 함께 연결한다 (오류는 조건부) */
          aria-describedby={
            [pollingHelpId, errors?.pollingIntervalSeconds ? pollingErrorId : null]
              .filter(Boolean)
              .join(' ')
          }
          aria-invalid={!!errors?.pollingIntervalSeconds}
          onChange={(e) =>
            onChange({ ...config, pollingIntervalSeconds: Number(e.target.value) || 60 })
          }
        />
        <p id={pollingHelpId} className="text-xs text-muted-foreground">30초 ~ 3600초 (1시간)</p>
        {errors?.pollingIntervalSeconds && (
          <p id={pollingErrorId} className="text-sm text-destructive">{errors.pollingIntervalSeconds}</p>
        )}
      </div>

      <div className="space-y-1.5">
        <Label htmlFor={debounceId}>디바운스 시간 (초)</Label>
        <Input
          id={debounceId}
          type="number"
          min={0}
          max={3600}
          value={config.debounceSeconds}
          aria-describedby={
            [debounceHelpId, errors?.debounceSeconds ? debounceErrorId : null]
              .filter(Boolean)
              .join(' ')
          }
          aria-invalid={!!errors?.debounceSeconds}
          onChange={(e) =>
            onChange({ ...config, debounceSeconds: Number(e.target.value) || 0 })
          }
        />
        <p id={debounceHelpId} className="text-xs text-muted-foreground">
          연속 변경 시 지정 시간 내 재트리거를 방지합니다. 0초 ~ 3600초 (1시간)
        </p>
        {errors?.debounceSeconds && (
          <p id={debounceErrorId} className="text-sm text-destructive">{errors.debounceSeconds}</p>
        )}
      </div>
    </div>
  );
}
