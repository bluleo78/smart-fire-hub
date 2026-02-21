import { useMemo } from 'react';
import { Label } from '@/components/ui/label';
import { Input } from '@/components/ui/input';
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
        <Label>감시 대상 데이터셋</Label>
        <DatasetCombobox
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
        <Label>폴링 주기 (초)</Label>
        <Input
          type="number"
          min={30}
          max={3600}
          value={config.pollingIntervalSeconds}
          onChange={(e) =>
            onChange({ ...config, pollingIntervalSeconds: Number(e.target.value) || 60 })
          }
        />
        <p className="text-xs text-muted-foreground">30초 ~ 3600초 (1시간)</p>
        {errors?.pollingIntervalSeconds && (
          <p className="text-sm text-destructive">{errors.pollingIntervalSeconds}</p>
        )}
      </div>

      <div className="space-y-1.5">
        <Label>디바운스 시간 (초)</Label>
        <Input
          type="number"
          min={0}
          max={3600}
          value={config.debounceSeconds}
          onChange={(e) =>
            onChange({ ...config, debounceSeconds: Number(e.target.value) || 0 })
          }
        />
        <p className="text-xs text-muted-foreground">
          연속 변경 시 지정 시간 내 재트리거를 방지합니다. 0초 ~ 3600초 (1시간)
        </p>
        {errors?.debounceSeconds && (
          <p className="text-sm text-destructive">{errors.debounceSeconds}</p>
        )}
      </div>
    </div>
  );
}
