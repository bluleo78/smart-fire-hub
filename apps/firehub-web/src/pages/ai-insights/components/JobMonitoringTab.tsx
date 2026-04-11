import { BarChart3, Plus, X } from 'lucide-react';
import { useState } from 'react';

import type { AnomalyConfig, AnomalyMetricConfig, Sensitivity } from '@/api/proactive';
import { SYSTEM_METRICS } from '@/api/proactive';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Separator } from '@/components/ui/separator';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';

interface JobMonitoringTabProps {
  config: Record<string, unknown>;
  onChange: (config: Record<string, unknown>) => void;
  readonly?: boolean;
}

const DEFAULT_ANOMALY_CONFIG: AnomalyConfig = {
  enabled: false,
  metrics: [],
  sensitivity: 'medium',
  cooldownMinutes: 30,
};

const SENSITIVITY_OPTIONS: { value: Sensitivity; label: string }[] = [
  { value: 'low', label: '낮음 (Low)' },
  { value: 'medium', label: '보통 (Medium)' },
  { value: 'high', label: '높음 (High)' },
];

type AddFormType = 'system' | 'dataset' | null;

function generateId(): string {
  return `m_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
}

export function JobMonitoringTab({ config, onChange, readonly }: JobMonitoringTabProps) {
  const anomaly: AnomalyConfig = {
    ...DEFAULT_ANOMALY_CONFIG,
    ...((config.anomaly as Partial<AnomalyConfig>) ?? {}),
  };

  const [addFormType, setAddFormType] = useState<AddFormType>(null);

  // System metric add form state
  const [systemMetricKey, setSystemMetricKey] = useState('');
  const [systemPollingInterval, setSystemPollingInterval] = useState(300);

  // Dataset metric add form state
  const [datasetMetricName, setDatasetMetricName] = useState('');
  const [datasetId, setDatasetId] = useState('');
  const [datasetQuery, setDatasetQuery] = useState('');
  const [datasetPollingInterval, setDatasetPollingInterval] = useState(600);

  function updateAnomaly(patch: Partial<AnomalyConfig>) {
    onChange({ ...config, anomaly: { ...anomaly, ...patch } });
  }

  function removeMetric(id: string) {
    updateAnomaly({ metrics: anomaly.metrics.filter((m) => m.id !== id) });
  }

  function addSystemMetric() {
    if (!systemMetricKey) return;
    const def = SYSTEM_METRICS.find((m) => m.key === systemMetricKey);
    if (!def) return;
    const metric: AnomalyMetricConfig = {
      id: generateId(),
      name: def.label,
      source: 'system',
      metricKey: def.key,
      pollingInterval: systemPollingInterval,
    };
    updateAnomaly({ metrics: [...anomaly.metrics, metric] });
    setAddFormType(null);
    setSystemMetricKey('');
    setSystemPollingInterval(300);
  }

  function addDatasetMetric() {
    if (!datasetMetricName || !datasetId || !datasetQuery) return;
    const metric: AnomalyMetricConfig = {
      id: generateId(),
      name: datasetMetricName,
      source: 'dataset',
      datasetId: Number(datasetId),
      query: datasetQuery,
      pollingInterval: datasetPollingInterval,
    };
    updateAnomaly({ metrics: [...anomaly.metrics, metric] });
    setAddFormType(null);
    setDatasetMetricName('');
    setDatasetId('');
    setDatasetQuery('');
    setDatasetPollingInterval(600);
  }

  // Filter out already-added system metrics
  const availableSystemMetrics = SYSTEM_METRICS.filter(
    (sm) => !anomaly.metrics.some((m) => m.source === 'system' && m.metricKey === sm.key),
  );

  return (
    <div className="space-y-6">
      {/* Enable/Disable toggle */}
      <div className="flex items-center justify-between">
        <div className="space-y-0.5">
          <Label className="text-sm font-medium">이상 감지 활성화</Label>
          <p className="text-xs text-muted-foreground">
            메트릭을 모니터링하고 이상 발생 시 리포트를 생성합니다.
          </p>
        </div>
        <Switch
          checked={anomaly.enabled}
          onCheckedChange={(checked) => updateAnomaly({ enabled: checked })}
          disabled={readonly}
          aria-label="이상 감지 활성화"
        />
      </div>

      {/* Settings (shown when enabled) */}
      {anomaly.enabled && (
        <div className="space-y-6 animate-in fade-in duration-200">
          <Separator />

          <div className="grid grid-cols-2 gap-4">
            {/* Sensitivity */}
            <div className="space-y-1.5">
              <Label className="text-sm font-medium">감도</Label>
              <Select
                value={anomaly.sensitivity}
                onValueChange={(value) => updateAnomaly({ sensitivity: value as Sensitivity })}
                disabled={readonly}
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {SENSITIVITY_OPTIONS.map((opt) => (
                    <SelectItem key={opt.value} value={opt.value}>
                      {opt.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            {/* Cooldown */}
            <div className="space-y-1.5">
              <Label className="text-sm font-medium">재알림 방지</Label>
              <div className="flex items-center gap-2">
                <Input
                  type="number"
                  min={1}
                  value={anomaly.cooldownMinutes}
                  onChange={(e) => updateAnomaly({ cooldownMinutes: Number(e.target.value) || 1 })}
                  disabled={readonly}
                  className="w-24"
                />
                <span className="text-sm text-muted-foreground">분</span>
              </div>
            </div>
          </div>

          <Separator />

          {/* Metric list */}
          <div className="space-y-3">
            <Label className="text-sm font-medium">모니터링 메트릭</Label>

            {anomaly.metrics.length === 0 && (
              <p className="text-sm text-muted-foreground py-4 text-center">
                등록된 메트릭이 없습니다. 아래 버튼으로 메트릭을 추가하세요.
              </p>
            )}

            {anomaly.metrics.map((metric) => (
              <Card key={metric.id}>
                <CardContent className="flex items-center justify-between py-3 px-4">
                  <div className="flex items-center gap-3">
                    <BarChart3 className="size-4 text-muted-foreground" />
                    <span className="text-sm font-medium">{metric.name}</span>
                    <Badge variant="secondary" className="text-xs">
                      {metric.source === 'system' ? 'system' : 'dataset'}
                    </Badge>
                    <span className="text-xs text-muted-foreground">
                      {metric.pollingInterval}초 간격
                    </span>
                  </div>
                  {!readonly && (
                    <Button
                      variant="ghost"
                      size="sm"
                      className="size-7 p-0"
                      onClick={() => removeMetric(metric.id)}
                      aria-label={`${metric.name} 삭제`}
                    >
                      <X className="size-4" />
                    </Button>
                  )}
                </CardContent>
              </Card>
            ))}
          </div>

          {/* Add buttons */}
          {!readonly && addFormType === null && (
            <div className="flex gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => setAddFormType('system')}
                disabled={availableSystemMetrics.length === 0}
              >
                <Plus className="size-4 mr-1" />
                시스템 메트릭 추가
              </Button>
              <Button variant="outline" size="sm" onClick={() => setAddFormType('dataset')}>
                <Plus className="size-4 mr-1" />
                데이터셋 메트릭
              </Button>
            </div>
          )}

          {/* Add system metric inline form */}
          {!readonly && addFormType === 'system' && (
            <Card>
              <CardContent className="pt-4 space-y-4">
                <Label className="text-sm font-medium">시스템 메트릭 추가</Label>
                <div className="grid grid-cols-2 gap-3">
                  <div className="space-y-1.5">
                    <Label className="text-xs text-muted-foreground">메트릭</Label>
                    <Select value={systemMetricKey} onValueChange={setSystemMetricKey}>
                      <SelectTrigger>
                        <SelectValue placeholder="메트릭 선택" />
                      </SelectTrigger>
                      <SelectContent>
                        {availableSystemMetrics.map((m) => (
                          <SelectItem key={m.key} value={m.key}>
                            {m.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-1.5">
                    <Label className="text-xs text-muted-foreground">폴링 간격</Label>
                    <div className="flex items-center gap-2">
                      <Input
                        type="number"
                        min={60}
                        value={systemPollingInterval}
                        onChange={(e) => setSystemPollingInterval(Number(e.target.value) || 60)}
                        className="w-24"
                      />
                      <span className="text-xs text-muted-foreground">초</span>
                    </div>
                  </div>
                </div>
                <div className="flex gap-2 justify-end">
                  <Button variant="ghost" size="sm" onClick={() => setAddFormType(null)}>
                    취소
                  </Button>
                  <Button size="sm" onClick={addSystemMetric} disabled={!systemMetricKey}>
                    추가
                  </Button>
                </div>
              </CardContent>
            </Card>
          )}

          {/* Add dataset metric inline form */}
          {!readonly && addFormType === 'dataset' && (
            <Card>
              <CardContent className="pt-4 space-y-4">
                <Label className="text-sm font-medium">데이터셋 메트릭 추가</Label>
                <div className="grid grid-cols-2 gap-3">
                  <div className="space-y-1.5">
                    <Label className="text-xs text-muted-foreground">메트릭 이름</Label>
                    <Input
                      value={datasetMetricName}
                      onChange={(e) => setDatasetMetricName(e.target.value)}
                      placeholder="예: 일별 매출 합계"
                    />
                  </div>
                  <div className="space-y-1.5">
                    <Label className="text-xs text-muted-foreground">데이터셋 ID</Label>
                    <Input
                      type="number"
                      min={1}
                      value={datasetId}
                      onChange={(e) => setDatasetId(e.target.value)}
                      placeholder="데이터셋 ID"
                    />
                  </div>
                </div>
                <div className="space-y-1.5">
                  <Label className="text-xs text-muted-foreground">SQL 쿼리</Label>
                  <Textarea
                    value={datasetQuery}
                    onChange={(e) => setDatasetQuery(e.target.value)}
                    placeholder="SELECT COUNT(*) as value FROM ..."
                    rows={3}
                    className="resize-none font-mono text-xs"
                  />
                </div>
                <div className="space-y-1.5">
                  <Label className="text-xs text-muted-foreground">폴링 간격</Label>
                  <div className="flex items-center gap-2">
                    <Input
                      type="number"
                      min={60}
                      value={datasetPollingInterval}
                      onChange={(e) => setDatasetPollingInterval(Number(e.target.value) || 60)}
                      className="w-24"
                    />
                    <span className="text-xs text-muted-foreground">초</span>
                  </div>
                </div>
                <div className="flex gap-2 justify-end">
                  <Button variant="ghost" size="sm" onClick={() => setAddFormType(null)}>
                    취소
                  </Button>
                  <Button
                    size="sm"
                    onClick={addDatasetMetric}
                    disabled={!datasetMetricName || !datasetId || !datasetQuery}
                  >
                    추가
                  </Button>
                </div>
              </CardContent>
            </Card>
          )}
        </div>
      )}
    </div>
  );
}
