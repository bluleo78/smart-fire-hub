import { Activity, Plus, Trash2 } from 'lucide-react';
import { type UseFormReturn } from 'react-hook-form';

import type { AnomalyConfig, Sensitivity } from '@/api/proactive';
import { SYSTEM_METRICS } from '@/api/proactive';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Switch } from '@/components/ui/switch';
import type { ProactiveJobFormValues } from '@/lib/validations/proactive-job';

const SENSITIVITY_OPTIONS: { value: Sensitivity; label: string; description: string }[] = [
  { value: 'low', label: '낮음', description: '큰 변동만 감지' },
  { value: 'medium', label: '보통', description: '일반적인 변동 감지' },
  { value: 'high', label: '높음', description: '작은 변동도 감지' },
];

const DEFAULT_ANOMALY_CONFIG: AnomalyConfig = {
  enabled: false,
  metrics: [],
  sensitivity: 'medium',
  cooldownMinutes: 30,
};

interface JobMonitoringTabProps {
  form: UseFormReturn<ProactiveJobFormValues>;
  isEditing: boolean;
}

export default function JobMonitoringTab({ form, isEditing }: JobMonitoringTabProps) {
  const { watch, setValue } = form;
  const anomalyConfig: AnomalyConfig = (watch('config.anomaly') as AnomalyConfig | undefined) ?? DEFAULT_ANOMALY_CONFIG;

  const updateAnomaly = (patch: Partial<AnomalyConfig>) => {
    setValue('config.anomaly', { ...anomalyConfig, ...patch }, { shouldDirty: true });
  };

  const addSystemMetric = (metricKey: string) => {
    const systemMetric = SYSTEM_METRICS.find((m) => m.key === metricKey);
    if (!systemMetric) return;
    if (anomalyConfig.metrics.some((m) => m.metricKey === metricKey)) return;

    updateAnomaly({
      metrics: [
        ...anomalyConfig.metrics,
        {
          id: crypto.randomUUID(),
          name: systemMetric.label,
          source: 'system' as const,
          metricKey: systemMetric.key,
          pollingInterval: 300,
        },
      ],
    });
  };

  const removeMetric = (id: string) => {
    updateAnomaly({
      metrics: anomalyConfig.metrics.filter((m) => m.id !== id),
    });
  };

  const updateMetricInterval = (id: string, interval: number) => {
    updateAnomaly({
      metrics: anomalyConfig.metrics.map((m) =>
        m.id === id ? { ...m, pollingInterval: interval } : m,
      ),
    });
  };

  // Available system metrics (not already added)
  const availableSystemMetrics = SYSTEM_METRICS.filter(
    (sm) => !anomalyConfig.metrics.some((m) => m.metricKey === sm.key),
  );

  // Read-only view
  if (!isEditing) {
    return (
      <div className="space-y-6 pt-4">
        {/* 이상 탐지 상태 */}
        <div className="rounded-lg border p-4 space-y-4">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold flex items-center gap-2">
              <Activity className="h-4 w-4" />
              이상 탐지
            </h3>
            <Badge variant={anomalyConfig.enabled ? 'default' : 'secondary'}>
              {anomalyConfig.enabled ? '활성' : '비활성'}
            </Badge>
          </div>

          {anomalyConfig.enabled && (
            <>
              <div className="grid grid-cols-2 gap-4">
                <div className="space-y-1">
                  <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider">민감도</p>
                  <p className="text-sm">
                    {SENSITIVITY_OPTIONS.find((s) => s.value === anomalyConfig.sensitivity)?.label ?? anomalyConfig.sensitivity}
                  </p>
                </div>
                <div className="space-y-1">
                  <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider">쿨다운</p>
                  <p className="text-sm">{anomalyConfig.cooldownMinutes}분</p>
                </div>
              </div>

              {/* 모니터링 메트릭 */}
              <div className="space-y-2">
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider">모니터링 메트릭</p>
                {anomalyConfig.metrics.length === 0 ? (
                  <p className="text-sm text-muted-foreground">설정된 메트릭이 없습니다.</p>
                ) : (
                  <div className="space-y-2">
                    {anomalyConfig.metrics.map((metric) => (
                      <div key={metric.id} className="flex items-center gap-2 text-sm">
                        <Badge variant="outline">
                          {metric.source === 'system' ? '시스템' : '데이터셋'}
                        </Badge>
                        <span>{metric.name}</span>
                        <span className="text-muted-foreground">({metric.pollingInterval}초)</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </>
          )}
        </div>
      </div>
    );
  }

  // Edit mode
  return (
    <div className="space-y-6 pt-4 max-w-2xl">
      {/* 이상 탐지 활성화 */}
      <div className="rounded-lg border p-4 space-y-4">
        <div className="flex items-center justify-between">
          <div className="space-y-0.5">
            <Label className="text-sm font-semibold flex items-center gap-2">
              <Activity className="h-4 w-4" />
              이상 탐지
            </Label>
            <p className="text-xs text-muted-foreground">
              메트릭 이상 발생 시 자동으로 분석을 실행합니다
            </p>
          </div>
          <Switch
            checked={anomalyConfig.enabled}
            onCheckedChange={(checked) => updateAnomaly({ enabled: checked })}
          />
        </div>

        {anomalyConfig.enabled && (
          <div className="space-y-4 pt-2 border-t">
            {/* 민감도 */}
            <div className="space-y-2">
              <Label htmlFor="sensitivity">민감도</Label>
              <Select
                value={anomalyConfig.sensitivity}
                onValueChange={(v) => updateAnomaly({ sensitivity: v as Sensitivity })}
              >
                <SelectTrigger id="sensitivity">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {SENSITIVITY_OPTIONS.map((opt) => (
                    <SelectItem key={opt.value} value={opt.value}>
                      {opt.label} - {opt.description}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            {/* 쿨다운 */}
            <div className="space-y-2">
              <Label htmlFor="cooldown">쿨다운 (분)</Label>
              <Input
                id="cooldown"
                type="number"
                min={1}
                value={anomalyConfig.cooldownMinutes}
                onChange={(e) => updateAnomaly({ cooldownMinutes: Number(e.target.value) || 30 })}
              />
              <p className="text-xs text-muted-foreground">
                이상 탐지 후 동일 메트릭에 대해 재실행까지 대기하는 시간
              </p>
            </div>

            {/* 모니터링 메트릭 */}
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <Label>모니터링 메트릭</Label>
                {availableSystemMetrics.length > 0 && (
                  <Select onValueChange={addSystemMetric}>
                    <SelectTrigger className="w-[200px] h-8">
                      <div className="flex items-center gap-1 text-xs">
                        <Plus className="h-3 w-3" />
                        시스템 메트릭 추가
                      </div>
                    </SelectTrigger>
                    <SelectContent>
                      {availableSystemMetrics.map((sm) => (
                        <SelectItem key={sm.key} value={sm.key}>
                          {sm.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                )}
              </div>

              {anomalyConfig.metrics.length === 0 ? (
                <div className="text-center py-6 text-sm text-muted-foreground border rounded-lg border-dashed">
                  모니터링할 메트릭을 추가하세요
                </div>
              ) : (
                <div className="space-y-2">
                  {anomalyConfig.metrics.map((metric) => (
                    <div
                      key={metric.id}
                      className="flex items-center gap-3 rounded-lg border p-3"
                    >
                      <Badge variant="outline" className="shrink-0">
                        {metric.source === 'system' ? '시스템' : '데이터셋'}
                      </Badge>
                      <span className="text-sm font-medium flex-1">{metric.name}</span>
                      <div className="flex items-center gap-2">
                        <Label className="text-xs text-muted-foreground shrink-0">폴링 주기</Label>
                        <Input
                          type="number"
                          min={60}
                          className="w-20 h-8 text-xs"
                          value={metric.pollingInterval}
                          onChange={(e) =>
                            updateMetricInterval(metric.id, Number(e.target.value) || 300)
                          }
                        />
                        <span className="text-xs text-muted-foreground">초</span>
                      </div>
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-8 w-8 shrink-0"
                        onClick={() => removeMetric(metric.id)}
                      >
                        <Trash2 className="h-3.5 w-3.5 text-destructive" />
                      </Button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
