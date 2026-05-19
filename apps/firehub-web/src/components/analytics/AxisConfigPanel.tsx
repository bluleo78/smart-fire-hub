import type { ChartConfig, ChartType } from '../../types/analytics';
import { Checkbox } from '../ui/checkbox';
import { Label } from '../ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../ui/select';
import { Switch } from '../ui/switch';
import { Tabs, TabsList, TabsTrigger } from '../ui/tabs';

interface AxisConfigPanelProps {
  chartType: ChartType;
  columns: string[];
  config: ChartConfig;
  onChange: (config: ChartConfig) => void;
}

const NO_COLUMN = '__none__';

export function AxisConfigPanel({ chartType, columns, config, onChange }: AxisConfigPanelProps) {
  const update = (patch: Partial<ChartConfig>) => onChange({ ...config, ...patch });

  const showStackOption = chartType === 'BAR' || chartType === 'AREA';
  const showGroupBy = chartType === 'SCATTER';
  const isPieOrDonut = chartType === 'PIE' || chartType === 'DONUT';
  const isMap = chartType === 'MAP';

  if (isMap) {
    const mode = config.mapDisplayMode ?? 'points';
    return (
      <div className="space-y-4">
        {/* 표시 모드 — 점 / 히트맵 토글 (#119) */}
        <div className="space-y-1.5">
          <Label className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">
            표시 모드
          </Label>
          <Tabs
            value={mode}
            onValueChange={(next) =>
              update({
                mapDisplayMode: next as 'points' | 'heatmap',
                // 모드 전환 시 반대 모드 전용 필드 초기화
                weightColumn: next === 'points' ? undefined : config.weightColumn,
                colorByColumn: next === 'heatmap' ? undefined : config.colorByColumn,
              })
            }
          >
            <TabsList className="h-8">
              <TabsTrigger value="points" className="text-xs">점</TabsTrigger>
              <TabsTrigger value="heatmap" className="text-xs">히트맵</TabsTrigger>
            </TabsList>
          </Tabs>
        </div>

        {/* 공간 컬럼 (필수, 공통) */}
        <div className="space-y-1.5">
          <Label className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">
            공간 컬럼
          </Label>
          <Select
            value={config.spatialColumn || NO_COLUMN}
            onValueChange={(v) => update({ spatialColumn: v === NO_COLUMN ? undefined : v })}
          >
            <SelectTrigger className="h-8 text-sm" aria-label="공간 컬럼">
              <SelectValue placeholder="컬럼 선택" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={NO_COLUMN}>선택 안 함</SelectItem>
              {columns.map((col) => (
                <SelectItem key={col} value={col}>
                  {col}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        {/* points 모드: 색상 기준 */}
        {mode === 'points' && (
          <div className="space-y-1.5">
            <Label className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">
              색상 기준 (선택사항)
            </Label>
            <Select
              value={config.colorByColumn || NO_COLUMN}
              onValueChange={(v) => update({ colorByColumn: v === NO_COLUMN ? undefined : v })}
            >
              <SelectTrigger className="h-8 text-sm">
                <SelectValue placeholder="선택 안 함" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={NO_COLUMN}>선택 안 함</SelectItem>
                {columns
                  .filter((col) => col !== config.spatialColumn)
                  .map((col) => (
                    <SelectItem key={col} value={col}>
                      {col}
                    </SelectItem>
                  ))}
              </SelectContent>
            </Select>
          </div>
        )}

        {/* heatmap 모드: 가중치 컬럼 */}
        {mode === 'heatmap' && (
          <div className="space-y-1.5">
            <Label className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">
              가중치 컬럼 (선택사항)
            </Label>
            <Select
              value={config.weightColumn || NO_COLUMN}
              onValueChange={(v) => update({ weightColumn: v === NO_COLUMN ? undefined : v })}
            >
              <SelectTrigger className="h-8 text-sm" aria-label="가중치 컬럼">
                <SelectValue placeholder="없음 — 균등 가중치" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={NO_COLUMN}>없음 — 균등 가중치</SelectItem>
                {columns
                  .filter((col) => col !== config.spatialColumn)
                  .map((col) => (
                    <SelectItem key={col} value={col}>
                      {col}
                    </SelectItem>
                  ))}
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">
              값이 클수록 해당 위치의 밀도 기여가 커집니다.
            </p>
          </div>
        )}
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* X Axis */}
      <div className="space-y-1.5">
        <Label className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">
          {isPieOrDonut ? '이름 (X축)' : 'X축'}
        </Label>
        <Select
          value={config.xAxis || NO_COLUMN}
          onValueChange={(v) => update({ xAxis: v === NO_COLUMN ? '' : v })}
        >
          <SelectTrigger className="h-8 text-sm">
            <SelectValue placeholder="컬럼 선택" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={NO_COLUMN}>선택 안 함</SelectItem>
            {columns.map((col) => (
              <SelectItem key={col} value={col}>
                {col}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* Y Axis — multi-select for non-pie */}
      {!isPieOrDonut ? (
        <div className="space-y-1.5">
          <Label className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">
            Y축 (다중 선택)
          </Label>
          <div className="space-y-1 max-h-40 overflow-y-auto rounded-md border p-2">
            {columns.length === 0 ? (
              <p className="text-xs text-muted-foreground px-1">컬럼 없음</p>
            ) : (
              columns.map((col) => {
                const checked = config.yAxis.includes(col);
                return (
                  <div key={col} className="flex items-center gap-2">
                    <Checkbox
                      id={`y-${col}`}
                      checked={checked}
                      onCheckedChange={(v) => {
                        if (v) {
                          update({ yAxis: [...config.yAxis, col] });
                        } else {
                          update({ yAxis: config.yAxis.filter((c) => c !== col) });
                        }
                      }}
                    />
                    <label
                      htmlFor={`y-${col}`}
                      className="text-sm cursor-pointer select-none leading-none"
                    >
                      {col}
                    </label>
                  </div>
                );
              })
            )}
          </div>
        </div>
      ) : (
        /* Pie/Donut: single value column */
        <div className="space-y-1.5">
          <Label className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">
            값 (Y축)
          </Label>
          <Select
            value={config.yAxis[0] || NO_COLUMN}
            onValueChange={(v) => update({ yAxis: v === NO_COLUMN ? [] : [v] })}
          >
            <SelectTrigger className="h-8 text-sm">
              <SelectValue placeholder="컬럼 선택" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={NO_COLUMN}>선택 안 함</SelectItem>
              {columns.map((col) => (
                <SelectItem key={col} value={col}>
                  {col}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}

      {/* Group By (scatter only) */}
      {showGroupBy && (
        <div className="space-y-1.5">
          <Label className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">
            그룹
          </Label>
          <Select
            value={config.groupBy || NO_COLUMN}
            onValueChange={(v) => update({ groupBy: v === NO_COLUMN ? undefined : v })}
          >
            <SelectTrigger className="h-8 text-sm">
              <SelectValue placeholder="그룹 없음" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={NO_COLUMN}>그룹 없음</SelectItem>
              {columns.map((col) => (
                <SelectItem key={col} value={col}>
                  {col}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}

      {/* Options */}
      <div className="space-y-2 pt-1">
        <Label className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">
          옵션
        </Label>

        <div className="flex items-center justify-between">
          <label className="text-sm cursor-pointer select-none" htmlFor="opt-legend">
            범례 표시
          </label>
          <Switch
            id="opt-legend"
            checked={config.showLegend ?? true}
            onCheckedChange={(v) => update({ showLegend: v })}
          />
        </div>

        {!isPieOrDonut && (
          <div className="flex items-center justify-between">
            <label className="text-sm cursor-pointer select-none" htmlFor="opt-grid">
              격자 표시
            </label>
            <Switch
              id="opt-grid"
              checked={config.showGrid ?? true}
              onCheckedChange={(v) => update({ showGrid: v })}
            />
          </div>
        )}

        {showStackOption && (
          <div className="flex items-center justify-between">
            <label className="text-sm cursor-pointer select-none" htmlFor="opt-stacked">
              스택 모드
            </label>
            <Switch
              id="opt-stacked"
              checked={config.stacked ?? false}
              onCheckedChange={(v) => update({ stacked: v })}
            />
          </div>
        )}
      </div>
    </div>
  );
}
