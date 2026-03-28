import { cn } from '../../../../lib/utils';

const STATUS_COLORS: Record<string, { dot: string; text: string }> = {
  정상: { dot: 'bg-emerald-400', text: 'text-emerald-400' },
  활성: { dot: 'bg-emerald-400', text: 'text-emerald-400' },
  완료: { dot: 'bg-emerald-400', text: 'text-emerald-400' },
  SUCCESS: { dot: 'bg-emerald-400', text: 'text-emerald-400' },
  ACTIVE: { dot: 'bg-emerald-400', text: 'text-emerald-400' },
  COMPLETED: { dot: 'bg-emerald-400', text: 'text-emerald-400' },
  점검중: { dot: 'bg-yellow-400', text: 'text-yellow-400' },
  경고: { dot: 'bg-yellow-400', text: 'text-yellow-400' },
  대기: { dot: 'bg-yellow-400', text: 'text-yellow-400' },
  PENDING: { dot: 'bg-yellow-400', text: 'text-yellow-400' },
  WARNING: { dot: 'bg-yellow-400', text: 'text-yellow-400' },
  수리중: { dot: 'bg-red-400', text: 'text-red-400' },
  오류: { dot: 'bg-red-400', text: 'text-red-400' },
  실패: { dot: 'bg-red-400', text: 'text-red-400' },
  FAILED: { dot: 'bg-red-400', text: 'text-red-400' },
  ERROR: { dot: 'bg-red-400', text: 'text-red-400' },
  INACTIVE: { dot: 'bg-red-400', text: 'text-red-400' },
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
