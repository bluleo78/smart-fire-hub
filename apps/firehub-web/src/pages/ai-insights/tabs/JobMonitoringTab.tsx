import { Activity, AlertTriangle, Database, Plus, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { type UseFormReturn } from 'react-hook-form';

import type { AnomalyConfig, Sensitivity } from '@/api/proactive';
import { SYSTEM_METRICS } from '@/api/proactive';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
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
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { useDatasets } from '@/hooks/queries/useDatasets';
import { useAnomalyEvents } from '@/hooks/queries/useProactiveMessages';
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

/** 커스텀 메트릭 추가 모달의 폼 상태 */
interface CustomMetricForm {
  customName: string;
  customDatasetId: number | '';
  customQuery: string;
  customInterval: number;
}

const DEFAULT_CUSTOM_FORM: CustomMetricForm = {
  customName: '',
  customDatasetId: '',
  customQuery: '',
  customInterval: 600,
};

interface JobMonitoringTabProps {
  form: UseFormReturn<ProactiveJobFormValues>;
  isEditing: boolean;
  /** 이상 탐지 이력 조회용 작업 ID (신규 작업이면 0) */
  jobId?: number;
}

/** 이상 탐지 이력 섹션 컴포넌트 */
function AnomalyHistorySection({ jobId }: { jobId: number }) {
  // jobId가 0(신규)이면 훅이 비활성화되므로 안전
  const { data: events = [], isLoading } = useAnomalyEvents(jobId);

  return (
    <div className="rounded-lg border p-4 space-y-4">
      <div className="flex items-center gap-2">
        <AlertTriangle className="h-4 w-4 text-warning" />
        <h3 className="text-sm font-semibold">최근 이상 탐지</h3>
        {/* 감지 건수 배지 */}
        <Badge variant="secondary">{events.length}</Badge>
      </div>

      {isLoading ? (
        <div className="h-20 bg-muted animate-pulse rounded" />
      ) : events.length === 0 ? (
        <p className="text-sm text-muted-foreground text-center py-6">
          감지된 이상이 없습니다
        </p>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>감지 시간</TableHead>
              <TableHead>메트릭</TableHead>
              <TableHead className="text-right">현재 값</TableHead>
              <TableHead className="text-right">평균</TableHead>
              <TableHead className="text-right">편차(σ)</TableHead>
              <TableHead>민감도</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {events.map((event) => (
              <TableRow key={event.id}>
                <TableCell className="text-xs text-muted-foreground whitespace-nowrap">
                  {new Date(event.detectedAt).toLocaleString('ko-KR')}
                </TableCell>
                <TableCell className="text-sm font-medium">{event.metricName}</TableCell>
                <TableCell className="text-right text-sm">
                  {event.currentValue.toFixed(2)}
                </TableCell>
                <TableCell className="text-right text-sm text-muted-foreground">
                  {event.mean.toFixed(2)}
                </TableCell>
                <TableCell className="text-right text-sm">
                  <Badge
                    variant={Math.abs(event.deviation) >= 3 ? 'destructive' : 'outline'}
                    className="tabular-nums"
                  >
                    {event.deviation >= 0 ? '+' : ''}{event.deviation.toFixed(2)}σ
                  </Badge>
                </TableCell>
                <TableCell className="text-sm">
                  {SENSITIVITY_OPTIONS.find((s) => s.value === event.sensitivity)?.label ?? event.sensitivity}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  );
}

export default function JobMonitoringTab({ form, isEditing, jobId = 0 }: JobMonitoringTabProps) {
  const { watch, setValue } = form;
  const anomalyConfig: AnomalyConfig = (watch('config.anomaly') as AnomalyConfig | undefined) ?? DEFAULT_ANOMALY_CONFIG;

  // 시스템 메트릭 Select 리셋용 키 — 메트릭 추가 후 값을 초기화하기 위해 증가시킨다
  const [selectKey, setSelectKey] = useState(0);

  // 커스텀 메트릭 모달 표시 여부
  const [showCustomModal, setShowCustomModal] = useState(false);
  // 커스텀 메트릭 폼 상태
  const [customForm, setCustomForm] = useState<CustomMetricForm>(DEFAULT_CUSTOM_FORM);

  // 데이터셋 목록 — 커스텀 메트릭의 소스 선택에 사용
  const { data: datasetsPage } = useDatasets({ size: 100 });
  const datasets = datasetsPage?.content ?? [];

  const updateAnomaly = (patch: Partial<AnomalyConfig>) => {
    setValue('config.anomaly', { ...anomalyConfig, ...patch }, { shouldDirty: true });
  };

  /**
   * 시스템 메트릭을 추가한다.
   * 추가 후 selectKey를 증가시켜 Select 컴포넌트를 리마운트하여 선택값을 초기화한다.
   * (Radix Select는 controlled value 없이 내부 상태를 유지하므로 key로 강제 리셋)
   */
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
    // Select를 리마운트하여 선택값 초기화
    setSelectKey((k) => k + 1);
  };

  /** 커스텀(데이터셋 기반) 메트릭을 추가한다 */
  const addCustomMetric = () => {
    if (!customForm.customName || !customForm.customDatasetId || !customForm.customQuery) return;

    updateAnomaly({
      metrics: [
        ...anomalyConfig.metrics,
        {
          id: crypto.randomUUID(),
          name: customForm.customName,
          source: 'dataset' as const,
          datasetId: Number(customForm.customDatasetId),
          query: customForm.customQuery,
          pollingInterval: customForm.customInterval,
        },
      ],
    });
    // 모달 닫기 및 폼 초기화
    setShowCustomModal(false);
    setCustomForm(DEFAULT_CUSTOM_FORM);
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

  // 아직 추가되지 않은 시스템 메트릭 목록
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

              {/* 모니터링 메트릭 목록 */}
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

        {/* 이상 탐지 이력 — 저장된 작업(jobId > 0)일 때만 표시 */}
        {jobId > 0 && <AnomalyHistorySection jobId={jobId} />}
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
                <div className="flex items-center gap-2">
                  {/* 시스템 메트릭 추가 Select
                      key를 addSystemMetric 호출마다 증가시켜 Radix Select를 리마운트하면
                      내부 선택 상태가 초기화되어 동일한 항목을 반복 선택할 수 있다 */}
                  {availableSystemMetrics.length > 0 && (
                    <Select key={selectKey} onValueChange={addSystemMetric}>
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
                  {/* 커스텀(데이터셋 기반) 메트릭 추가 버튼 */}
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="h-8 text-xs gap-1"
                    onClick={() => setShowCustomModal(true)}
                  >
                    <Database className="h-3 w-3" />
                    커스텀 메트릭
                  </Button>
                </div>
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
                        type="button"
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

      {/* 이상 탐지 이력 — 편집 모드에서도 저장된 작업이면 표시 */}
      {jobId > 0 && <AnomalyHistorySection jobId={jobId} />}

      {/* 커스텀 메트릭 추가 모달 */}
      <Dialog open={showCustomModal} onOpenChange={setShowCustomModal}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Database className="h-4 w-4" />
              커스텀 메트릭 추가
            </DialogTitle>
            <DialogDescription className="sr-only">모니터링할 커스텀 데이터셋 메트릭을 추가합니다.</DialogDescription>
          </DialogHeader>

          <div className="space-y-4">
            {/* 메트릭 이름 */}
            <div className="space-y-2">
              <Label htmlFor="custom-name">메트릭 이름</Label>
              <Input
                id="custom-name"
                placeholder="예: 신규 주문 건수"
                value={customForm.customName}
                onChange={(e) => setCustomForm((f) => ({ ...f, customName: e.target.value }))}
              />
            </div>

            {/* 데이터셋 선택 */}
            <div className="space-y-2">
              <Label htmlFor="custom-dataset">데이터셋</Label>
              <Select
                value={customForm.customDatasetId !== '' ? String(customForm.customDatasetId) : ''}
                onValueChange={(v) =>
                  setCustomForm((f) => ({ ...f, customDatasetId: Number(v) }))
                }
              >
                <SelectTrigger id="custom-dataset">
                  <SelectValue placeholder="데이터셋 선택" />
                </SelectTrigger>
                <SelectContent>
                  {datasets.map((ds) => (
                    <SelectItem key={ds.id} value={String(ds.id)}>
                      {ds.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            {/* 집계 쿼리 */}
            <div className="space-y-2">
              <Label htmlFor="custom-query">집계 쿼리</Label>
              <Textarea
                id="custom-query"
                placeholder="SELECT COUNT(*) FROM table WHERE ..."
                rows={3}
                value={customForm.customQuery}
                onChange={(e) => setCustomForm((f) => ({ ...f, customQuery: e.target.value }))}
              />
              <p className="text-xs text-muted-foreground">
                단일 숫자 값을 반환하는 SQL 쿼리를 입력하세요
              </p>
            </div>

            {/* 폴링 주기 */}
            <div className="space-y-2">
              <Label htmlFor="custom-interval">폴링 주기 (초)</Label>
              <Input
                id="custom-interval"
                type="number"
                min={60}
                value={customForm.customInterval}
                onChange={(e) =>
                  setCustomForm((f) => ({ ...f, customInterval: Number(e.target.value) || 600 }))
                }
              />
            </div>
          </div>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                setShowCustomModal(false);
                setCustomForm(DEFAULT_CUSTOM_FORM);
              }}
            >
              취소
            </Button>
            <Button
              type="button"
              disabled={!customForm.customName || !customForm.customDatasetId || !customForm.customQuery}
              onClick={addCustomMetric}
            >
              추가
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
