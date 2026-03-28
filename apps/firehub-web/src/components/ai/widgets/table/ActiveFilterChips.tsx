import { X } from 'lucide-react';
import { cn } from '../../../../lib/utils';

interface ActiveFilterChipsProps {
  filters: Record<string, string[]>;
  onRemove: (columnName: string, value: string) => void;
  onClearAll: () => void;
}

export function ActiveFilterChips({ filters, onRemove, onClearAll }: ActiveFilterChipsProps) {
  const entries = Object.entries(filters).flatMap(([col, values]) =>
    values.map((val) => ({ col, val })),
  );

  if (entries.length === 0) return null;

  return (
    <div className="flex flex-wrap items-center gap-1.5 border-b border-border px-3 py-1.5">
      {entries.map(({ col, val }) => (
        <span
          key={`${col}:${val}`}
          className={cn(
            'flex items-center gap-1 rounded-full border border-primary/30 bg-primary/10 px-2 py-0.5 text-xs text-primary',
          )}
        >
          <span className="font-medium">{col}:</span>
          <span>{val}</span>
          <button
            type="button"
            aria-label={`${col} ${val} 필터 해제`}
            onClick={() => onRemove(col, val)}
            className="ml-0.5 rounded-full hover:text-primary/70"
          >
            <X className="h-3 w-3" />
          </button>
        </span>
      ))}
      <button
        type="button"
        onClick={onClearAll}
        className="text-xs text-muted-foreground hover:text-foreground underline underline-offset-2"
      >
        전체 해제
      </button>
    </div>
  );
}
