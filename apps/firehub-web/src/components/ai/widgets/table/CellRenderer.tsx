import { cn } from '../../../../lib/utils';

/* 셀 상태 표시 색상 — 시맨틱 토큰 사용 */
const STATUS_COLORS: Record<string, { dot: string; text: string }> = {
  정상: { dot: 'bg-success', text: 'text-success' },
  활성: { dot: 'bg-success', text: 'text-success' },
  완료: { dot: 'bg-success', text: 'text-success' },
  SUCCESS: { dot: 'bg-success', text: 'text-success' },
  ACTIVE: { dot: 'bg-success', text: 'text-success' },
  COMPLETED: { dot: 'bg-success', text: 'text-success' },
  점검중: { dot: 'bg-warning', text: 'text-warning' },
  경고: { dot: 'bg-warning', text: 'text-warning' },
  대기: { dot: 'bg-warning', text: 'text-warning' },
  PENDING: { dot: 'bg-warning', text: 'text-warning' },
  WARNING: { dot: 'bg-warning', text: 'text-warning' },
  수리중: { dot: 'bg-destructive', text: 'text-destructive' },
  오류: { dot: 'bg-destructive', text: 'text-destructive' },
  실패: { dot: 'bg-destructive', text: 'text-destructive' },
  FAILED: { dot: 'bg-destructive', text: 'text-destructive' },
  ERROR: { dot: 'bg-destructive', text: 'text-destructive' },
  INACTIVE: { dot: 'bg-destructive', text: 'text-destructive' },
};

interface CellRendererProps {
  value: unknown;
  isNumericColumn?: boolean;
}

export function CellRenderer({ value, isNumericColumn }: CellRendererProps) {
  if (value === null || value === undefined) {
    return <span className="text-muted-foreground/50">—</span>;
  }

  const isNumber =
    isNumericColumn ||
    typeof value === 'number' ||
    (typeof value === 'string' && value.trim() !== '' && !isNaN(Number(value)));

  if (isNumber) {
    const num = typeof value === 'number' ? value : Number(value);
    return (
      <span
        className={cn('font-medium tabular-nums', 'text-foreground')}
        style={{ fontVariantNumeric: 'tabular-nums' }}
      >
        {num.toLocaleString('ko-KR')}
      </span>
    );
  }

  const str = String(value);
  const statusColor = STATUS_COLORS[str];
  if (statusColor) {
    return (
      <span className="flex items-center gap-1">
        <span className={cn('inline-block h-1.5 w-1.5 rounded-full shrink-0', statusColor.dot)} />
        <span className={cn('font-medium', statusColor.text)}>{str}</span>
      </span>
    );
  }

  if (str.length > 40) {
    return (
      <span className="block max-w-[160px] truncate text-foreground" title={str}>
        {str}
      </span>
    );
  }

  return <span className="text-foreground">{str}</span>;
}
