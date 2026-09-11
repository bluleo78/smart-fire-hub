import { useId } from 'react';

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
  // 접근성: 라벨↔컨트롤 연결용 id 접두사 (#432).
  // 하드코딩하지 않는 이유 — 이 패널은 차트 타입별로 다시 마운트되고,
  // 향후 여러 곳(대시보드 위젯 편집 등)에서 동시에 열릴 수 있어 id 가 실제로 충돌한다.
  const baseId = useId();
  const update = (patch: Partial<ChartConfig>) => onChange({ ...config, ...patch });

  const showStackOption = chartType === 'BAR' || chartType === 'AREA';
  const showGroupBy = chartType === 'SCATTER';
  const isPieOrDonut = chartType === 'PIE' || chartType === 'DONUT';
  const isMap = chartType === 'MAP';
  const isCandlestick = chartType === 'CANDLESTICK';
  const isHeatmap = chartType === 'HEATMAP';

  if (isHeatmap) {
    // 히트맵은 xAxis=행, yAxis[0]=열, valueColumn=셀 색상 값 3개 역할이 필요하다 (#664).
    // 범용 X/Y(다중 선택) UI를 그대로 쓰면 "열" 선택이 "Y축 다중 선택"으로 오인되고
    // valueColumn을 바꿀 컨트롤이 아예 없어 열 카테고리와 값 컬럼이 겹쳐버린다.
    // 캔들스틱(#663)과 동일하게 세 필드를 각각 독립된 단일 Select로 분리한다.
    return (
      <div className="space-y-4">
        {/* 행 — xAxis */}
        <div className="space-y-1.5">
          <Label
            htmlFor={`${baseId}-heatmap-row`}
            className="text-xs font-semibold text-muted-foreground uppercase tracking-wide"
          >
            행 (카테고리)
          </Label>
          <Select
            value={config.xAxis || NO_COLUMN}
            onValueChange={(v) => update({ xAxis: v === NO_COLUMN ? '' : v })}
          >
            <SelectTrigger id={`${baseId}-heatmap-row`} className="h-8 text-sm">
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

        {/* 열 — yAxis[0] (다중 체크박스가 아니라 단일 Select) */}
        <div className="space-y-1.5">
          <Label
            htmlFor={`${baseId}-heatmap-col`}
            className="text-xs font-semibold text-muted-foreground uppercase tracking-wide"
          >
            열 (카테고리)
          </Label>
          <Select
            value={config.yAxis[0] || NO_COLUMN}
            onValueChange={(v) => update({ yAxis: v === NO_COLUMN ? [] : [v] })}
          >
            <SelectTrigger id={`${baseId}-heatmap-col`} className="h-8 text-sm">
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

        {/* 값 — valueColumn (셀 색상 기준) */}
        <div className="space-y-1.5">
          <Label
            htmlFor={`${baseId}-heatmap-value`}
            className="text-xs font-semibold text-muted-foreground uppercase tracking-wide"
          >
            값 (색상 기준)
          </Label>
          <Select
            value={config.valueColumn || NO_COLUMN}
            onValueChange={(v) => update({ valueColumn: v === NO_COLUMN ? undefined : v })}
          >
            <SelectTrigger id={`${baseId}-heatmap-value`} className="h-8 text-sm" aria-label="값 (색상 기준)">
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
      </div>
    );
  }

  if (isCandlestick) {
    // 캔들스틱은 yAxis가 아니라 시가/고가/저가/종가 4개 컬럼을 개별 매핑해야 렌더링된다 (#663).
    // 쿼리 컬럼명이 open/high/low/close와 다르면 자동 감지가 실패하므로, 직접 선택할 UI가 필요하다.
    const ohlcFields: { key: 'open' | 'high' | 'low' | 'close'; label: string }[] = [
      { key: 'open', label: '시가 (Open)' },
      { key: 'high', label: '고가 (High)' },
      { key: 'low', label: '저가 (Low)' },
      { key: 'close', label: '종가 (Close)' },
    ];
    return (
      <div className="space-y-4">
        {/* X축 — 캔들 라벨(보통 날짜) */}
        <div className="space-y-1.5">
          <Label
            htmlFor={`${baseId}-x-axis`}
            className="text-xs font-semibold text-muted-foreground uppercase tracking-wide"
          >
            X축
          </Label>
          <Select
            value={config.xAxis || NO_COLUMN}
            onValueChange={(v) => update({ xAxis: v === NO_COLUMN ? '' : v })}
          >
            <SelectTrigger id={`${baseId}-x-axis`} className="h-8 text-sm">
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

        {/* OHLC 컬럼 매핑 */}
        {ohlcFields.map(({ key, label }) => (
          <div key={key} className="space-y-1.5">
            <Label
              htmlFor={`${baseId}-${key}`}
              className="text-xs font-semibold text-muted-foreground uppercase tracking-wide"
            >
              {label}
            </Label>
            <Select
              value={config[key] || NO_COLUMN}
              onValueChange={(v) => update({ [key]: v === NO_COLUMN ? undefined : v })}
            >
              <SelectTrigger id={`${baseId}-${key}`} className="h-8 text-sm" aria-label={label}>
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
        ))}
      </div>
    );
  }

  if (isMap) {
    const mode = config.mapDisplayMode ?? 'points';
    return (
      <div className="space-y-4">
        {/* 표시 모드 — 점 / 히트맵 토글 (#119) */}
        <div className="space-y-1.5">
          {/*
            탭 묶음(tablist)은 htmlFor 로 가리킬 수 있는 단일 입력이 아니므로 label 이 아니라
            span 으로 두고, aria-labelledby 로 tablist 에 이름을 준다 (#432).
            Label 기본 스타일(flex/leading-none)을 보충해 시각 결과를 유지한다.
          */}
          <span
            id={`${baseId}-map-mode`}
            className="flex items-center gap-2 leading-none text-xs font-semibold text-muted-foreground uppercase tracking-wide"
          >
            표시 모드
          </span>
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
            <TabsList className="h-8" aria-labelledby={`${baseId}-map-mode`}>
              <TabsTrigger value="points" className="text-xs">점</TabsTrigger>
              <TabsTrigger value="heatmap" className="text-xs">히트맵</TabsTrigger>
            </TabsList>
          </Tabs>
        </div>

        {/* 공간 컬럼 (필수, 공통) */}
        <div className="space-y-1.5">
          <Label
            htmlFor={`${baseId}-spatial`}
            className="text-xs font-semibold text-muted-foreground uppercase tracking-wide"
          >
            공간 컬럼
          </Label>
          <Select
            value={config.spatialColumn || NO_COLUMN}
            onValueChange={(v) => update({ spatialColumn: v === NO_COLUMN ? undefined : v })}
          >
            <SelectTrigger id={`${baseId}-spatial`} className="h-8 text-sm" aria-label="공간 컬럼">
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
            <Label
              htmlFor={`${baseId}-color-by`}
              className="text-xs font-semibold text-muted-foreground uppercase tracking-wide"
            >
              색상 기준 (선택사항)
            </Label>
            <Select
              value={config.colorByColumn || NO_COLUMN}
              onValueChange={(v) => update({ colorByColumn: v === NO_COLUMN ? undefined : v })}
            >
              <SelectTrigger id={`${baseId}-color-by`} className="h-8 text-sm">
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
            <Label
              htmlFor={`${baseId}-weight`}
              className="text-xs font-semibold text-muted-foreground uppercase tracking-wide"
            >
              가중치 컬럼 (선택사항)
            </Label>
            <Select
              value={config.weightColumn || NO_COLUMN}
              onValueChange={(v) => update({ weightColumn: v === NO_COLUMN ? undefined : v })}
            >
              <SelectTrigger
                id={`${baseId}-weight`}
                className="h-8 text-sm"
                aria-label="가중치 컬럼"
                aria-describedby={`${baseId}-weight-help`}
              >
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
            {/* 도움말 문구를 aria-describedby 로 컨트롤에 연결한다 (#432, §J). */}
            <p id={`${baseId}-weight-help`} className="text-xs text-muted-foreground">
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
        <Label
          htmlFor={`${baseId}-x-axis`}
          className="text-xs font-semibold text-muted-foreground uppercase tracking-wide"
        >
          {isPieOrDonut ? '이름 (X축)' : 'X축'}
        </Label>
        <Select
          value={config.xAxis || NO_COLUMN}
          onValueChange={(v) => update({ xAxis: v === NO_COLUMN ? '' : v })}
        >
          <SelectTrigger id={`${baseId}-x-axis`} className="h-8 text-sm">
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
          {/*
            체크박스 묶음이라 가리킬 단일 입력이 없다 — label 대신 span 으로 두고
            group 컨테이너에 aria-labelledby 로 이름을 준다 (#432).
          */}
          <span
            id={`${baseId}-y-axis`}
            className="flex items-center gap-2 leading-none text-xs font-semibold text-muted-foreground uppercase tracking-wide"
          >
            Y축 (다중 선택)
          </span>
          <div
            role="group"
            aria-labelledby={`${baseId}-y-axis`}
            className="space-y-1 max-h-40 overflow-y-auto rounded-md border p-2"
          >
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
          <Label
            htmlFor={`${baseId}-y-value`}
            className="text-xs font-semibold text-muted-foreground uppercase tracking-wide"
          >
            값 (Y축)
          </Label>
          <Select
            value={config.yAxis[0] || NO_COLUMN}
            onValueChange={(v) => update({ yAxis: v === NO_COLUMN ? [] : [v] })}
          >
            <SelectTrigger id={`${baseId}-y-value`} className="h-8 text-sm">
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
          <Label
            htmlFor={`${baseId}-group-by`}
            className="text-xs font-semibold text-muted-foreground uppercase tracking-wide"
          >
            그룹
          </Label>
          <Select
            value={config.groupBy || NO_COLUMN}
            onValueChange={(v) => update({ groupBy: v === NO_COLUMN ? undefined : v })}
          >
            <SelectTrigger id={`${baseId}-group-by`} className="h-8 text-sm">
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
      <div className="space-y-2 pt-1" role="group" aria-labelledby={`${baseId}-options`}>
        {/* 스위치 묶음의 섹션 제목 — 단일 컨트롤이 없으므로 span + group aria-labelledby (#432). */}
        <span
          id={`${baseId}-options`}
          className="flex items-center gap-2 leading-none text-xs font-semibold text-muted-foreground uppercase tracking-wide"
        >
          옵션
        </span>

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
