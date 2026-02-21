import React from 'react';
import type { ColumnStatsResponse } from '../../../types/dataset';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '../../../components/ui/popover';

// Mini histogram for numeric columns
export function NumericMiniChart({ stats }: { stats: ColumnStatsResponse }) {
  const bars = stats.topValues.slice(0, 5);
  if (bars.length === 0) return <div style={{ height: 20 }} />;
  const maxCount = Math.max(...bars.map((b) => b.count));
  return (
    <svg width="100%" height="20" style={{ display: 'block' }}>
      {bars.map((bar, i) => {
        const barWidth = maxCount > 0 ? (bar.count / maxCount) * 100 : 0;
        const x = (i / bars.length) * 100;
        const w = 100 / bars.length - 1;
        const barH = maxCount > 0 ? Math.max(2, (bar.count / maxCount) * 18) : 0;
        return (
          <rect
            key={i}
            x={`${x}%`}
            y={20 - barH}
            width={`${w}%`}
            height={barH}
            fill="hsl(var(--primary))"
            opacity={0.7 + (barWidth / 100) * 0.3}
          />
        );
      })}
    </svg>
  );
}

// Segmented bar for text columns
export function TextMiniChart({ stats }: { stats: ColumnStatsResponse }) {
  const top3 = stats.topValues.slice(0, 3);
  if (top3.length === 0) return <div style={{ height: 20 }} />;
  const total = top3.reduce((s, v) => s + v.count, 0);
  const colors = ['hsl(215, 70%, 60%)', 'hsl(215, 70%, 75%)', 'hsl(215, 70%, 88%)'];
  const pcts = top3.map((bar) => (total > 0 ? (bar.count / total) * 100 : 0));
  const offsets = pcts.reduce<number[]>((acc, _, i) => {
    acc.push(i === 0 ? 0 : acc[i - 1] + pcts[i - 1]);
    return acc;
  }, []);
  return (
    <svg width="100%" height="20" style={{ display: 'block' }}>
      {top3.map((bar, i) => {
        const pct = total > 0 ? (bar.count / total) * 100 : 0;
        return (
          <rect
            key={i}
            x={`${offsets[i]}%`}
            y={8}
            width={`${pct}%`}
            height={8}
            fill={colors[i]}
          />
        );
      })}
    </svg>
  );
}

// Two-color ratio bar for boolean columns
export function BooleanMiniChart({ stats }: { stats: ColumnStatsResponse }) {
  const trueEntry = stats.topValues.find((v) => v.value?.toLowerCase() === 'true');
  const falseEntry = stats.topValues.find((v) => v.value?.toLowerCase() === 'false');
  const trueCount = trueEntry?.count ?? 0;
  const falseCount = falseEntry?.count ?? 0;
  const total = trueCount + falseCount;
  if (total === 0) return <div style={{ height: 20 }} />;
  const truePct = (trueCount / total) * 100;
  return (
    <svg width="100%" height="20" style={{ display: 'block' }}>
      <rect x="0" y="8" width={`${truePct}%`} height={8} fill="hsl(142, 70%, 45%)" />
      <rect x={`${truePct}%`} y="8" width={`${100 - truePct}%`} height={8} fill="hsl(0, 60%, 60%)" />
    </svg>
  );
}

// Date range display
export function DateMiniDisplay({ stats }: { stats: ColumnStatsResponse }) {
  const min = stats.minValue ?? '';
  const max = stats.maxValue ?? '';
  if (!min && !max) return <div style={{ height: 20 }} />;
  return (
    <div style={{ height: 20, display: 'flex', alignItems: 'center' }}>
      <span className="text-[10px] text-muted-foreground truncate">
        {min} ~ {max}
      </span>
    </div>
  );
}

// Full stats popover content
export function ColumnStatsPopoverContent({ stats }: { stats: ColumnStatsResponse }) {
  const isNumeric = stats.dataType === 'INTEGER' || stats.dataType === 'DECIMAL';
  return (
    <div className="space-y-2 text-sm">
      <div>
        <span className="font-semibold">{stats.columnName}</span>
        <span className="ml-2 text-xs text-muted-foreground bg-muted px-1 py-0.5 rounded">
          {stats.dataType}
        </span>
      </div>
      <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs">
        <span className="text-muted-foreground">Total</span>
        <span>{stats.totalCount.toLocaleString()}</span>
        <span className="text-muted-foreground">Null</span>
        <span>{stats.nullCount.toLocaleString()} ({stats.nullPercent.toFixed(1)}%)</span>
        <span className="text-muted-foreground">Distinct</span>
        <span>{stats.distinctCount.toLocaleString()}</span>
        {isNumeric && (
          <>
            <span className="text-muted-foreground">Min</span>
            <span>{stats.minValue ?? '-'}</span>
            <span className="text-muted-foreground">Max</span>
            <span>{stats.maxValue ?? '-'}</span>
            <span className="text-muted-foreground">Avg</span>
            <span>{stats.avgValue != null ? stats.avgValue.toFixed(2) : '-'}</span>
          </>
        )}
      </div>
      {stats.topValues.length > 0 && (
        <div>
          <div className="text-xs font-medium mb-1">Top values</div>
          <div className="space-y-0.5">
            {stats.topValues.slice(0, 5).map((v, i) => (
              <div key={i} className="flex justify-between text-xs">
                <span className="text-muted-foreground truncate max-w-[140px]">{v.value ?? 'NULL'}</span>
                <span className="ml-2 font-mono">{v.count.toLocaleString()}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

// Mini chart dispatcher
export function ColumnMiniChart({
  stats,
}: {
  stats: ColumnStatsResponse | undefined;
}) {
  if (!stats) return <div style={{ height: 20 }} />;

  const dt = stats.dataType;

  let chart: React.ReactNode;
  if (dt === 'INTEGER' || dt === 'DECIMAL') {
    chart = <NumericMiniChart stats={stats} />;
  } else if (dt === 'TEXT' || dt === 'VARCHAR') {
    chart = <TextMiniChart stats={stats} />;
  } else if (dt === 'BOOLEAN') {
    chart = <BooleanMiniChart stats={stats} />;
  } else if (dt === 'DATE' || dt === 'TIMESTAMP') {
    chart = <DateMiniDisplay stats={stats} />;
  } else {
    chart = <div style={{ height: 20 }} />;
  }

  return (
    <Popover>
      <PopoverTrigger asChild>
        <div
          style={{ cursor: 'pointer', height: 20 }}
          title="클릭하여 통계 보기"
          onClick={(e) => e.stopPropagation()}
        >
          {chart}
        </div>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-64">
        <ColumnStatsPopoverContent stats={stats} />
      </PopoverContent>
    </Popover>
  );
}

// Null progress bar colored by percentage
export function NullProgressBar({ percent }: { percent: number }) {
  const color =
    percent === 0 ? 'bg-green-500' : percent <= 30 ? 'bg-yellow-500' : 'bg-red-500';
  return (
    <div className="flex items-center gap-1.5">
      <div className="w-[60px] h-1.5 bg-muted rounded-full overflow-hidden">
        <div
          className={`h-full rounded-full ${color}`}
          style={{ width: `${Math.min(percent, 100)}%` }}
        />
      </div>
      <span className="text-xs text-muted-foreground">{percent.toFixed(1)}%</span>
    </div>
  );
}

// Expanded stats panel for column detail view
export function ColumnExpandedStats({
  stats,
  dataType,
}: {
  stats: ColumnStatsResponse;
  dataType: string;
}) {
  const isNumeric = dataType === 'INTEGER' || dataType === 'DECIMAL';
  const isText = dataType === 'TEXT' || dataType === 'VARCHAR';
  const isDate = dataType === 'DATE' || dataType === 'TIMESTAMP';
  const isBoolean = dataType === 'BOOLEAN';

  const maxTopCount = stats.topValues.length > 0 ? Math.max(...stats.topValues.map((v) => v.count)) : 1;

  return (
    <div className="p-4 bg-muted/30 space-y-4">
      {/* Common: null summary */}
      <div className="space-y-1">
        <div className="text-xs font-medium text-muted-foreground">
          NULL: {stats.nullCount.toLocaleString()} / {stats.totalCount.toLocaleString()}
        </div>
        <div className="w-full h-2 bg-muted rounded-full overflow-hidden">
          <div
            className={`h-full rounded-full ${
              stats.nullPercent === 0
                ? 'bg-green-500'
                : stats.nullPercent <= 30
                ? 'bg-yellow-500'
                : 'bg-red-500'
            }`}
            style={{ width: `${Math.min(stats.nullPercent, 100)}%` }}
          />
        </div>
      </div>

      {/* Numeric */}
      {isNumeric && (
        <div className="grid grid-cols-3 gap-3">
          {[
            { label: '최솟값', value: stats.minValue ?? '-' },
            { label: '최댓값', value: stats.maxValue ?? '-' },
            {
              label: '평균값',
              value: stats.avgValue != null ? Number(stats.avgValue).toFixed(2) : '-',
            },
          ].map(({ label, value }) => (
            <div key={label} className="rounded-md border bg-background p-3 text-center">
              <div className="text-xs text-muted-foreground mb-1">{label}</div>
              <div className="text-sm font-semibold font-mono">{value}</div>
            </div>
          ))}
        </div>
      )}

      {/* Text: top 5 frequency values */}
      {isText && stats.topValues.length > 0 && (
        <div className="space-y-1.5">
          <div className="text-xs font-medium text-muted-foreground">상위 빈도 값</div>
          {stats.topValues.slice(0, 5).map((tv) => (
            <div key={tv.value} className="flex items-center gap-2">
              <span className="text-xs font-mono w-32 truncate text-right shrink-0">
                {tv.value}
              </span>
              <div className="flex-1 h-4 bg-muted rounded overflow-hidden">
                <div
                  className="h-full bg-blue-500 rounded"
                  style={{ width: `${(tv.count / maxTopCount) * 100}%` }}
                />
              </div>
              <span className="text-xs text-muted-foreground w-12 shrink-0">
                {tv.count.toLocaleString()}
              </span>
            </div>
          ))}
        </div>
      )}

      {/* Date */}
      {isDate && (
        <div className="flex items-center gap-2 text-sm">
          <span className="text-muted-foreground text-xs">범위:</span>
          <span className="font-mono text-xs">{stats.minValue ?? '-'}</span>
          <span className="text-muted-foreground">~</span>
          <span className="font-mono text-xs">{stats.maxValue ?? '-'}</span>
        </div>
      )}

      {/* Boolean */}
      {isBoolean && stats.topValues.length > 0 && (
        <div className="space-y-1">
          <div className="text-xs font-medium text-muted-foreground">True / False 비율</div>
          {(() => {
            const trueEntry = stats.topValues.find(
              (v) => v.value.toLowerCase() === 'true'
            );
            const falseEntry = stats.topValues.find(
              (v) => v.value.toLowerCase() === 'false'
            );
            const trueCount = trueEntry?.count ?? 0;
            const falseCount = falseEntry?.count ?? 0;
            const total = trueCount + falseCount || 1;
            const truePct = (trueCount / total) * 100;
            const falsePct = (falseCount / total) * 100;
            return (
              <div className="flex items-center gap-2">
                <div className="flex-1 h-4 rounded overflow-hidden flex">
                  <div
                    className="h-full bg-green-500"
                    style={{ width: `${truePct}%` }}
                    title={`true: ${trueCount}`}
                  />
                  <div
                    className="h-full bg-red-500"
                    style={{ width: `${falsePct}%` }}
                    title={`false: ${falseCount}`}
                  />
                </div>
                <span className="text-xs text-muted-foreground shrink-0">
                  T:{trueCount} / F:{falseCount}
                </span>
              </div>
            );
          })()}
        </div>
      )}

      {/* Sampled notice */}
      {stats.sampled && (
        <p className="text-xs italic text-muted-foreground">
          * 10만행 초과 데이터셋으로 샘플링된 통계입니다
        </p>
      )}
    </div>
  );
}
