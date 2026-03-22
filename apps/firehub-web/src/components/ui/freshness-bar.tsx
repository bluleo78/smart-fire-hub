import { cn } from '../../lib/utils';

interface FreshnessBarProps {
  lastUpdated: string | null;
  className?: string;
}

function getFreshness(lastUpdated: string | null): { percent: number; level: 'fresh' | 'stale' | 'old' } {
  if (!lastUpdated) return { percent: 0, level: 'old' };

  const days = (Date.now() - new Date(lastUpdated).getTime()) / (1000 * 60 * 60 * 24);

  if (days <= 7) return { percent: Math.max(100 - (days / 7) * 30, 70), level: 'fresh' };
  if (days <= 14) return { percent: Math.max(60 - ((days - 7) / 7) * 30, 30), level: 'stale' };
  return { percent: Math.max(25 - ((days - 14) / 14) * 15, 10), level: 'old' };
}

export function FreshnessBar({ lastUpdated, className }: FreshnessBarProps) {
  const { percent, level } = getFreshness(lastUpdated);

  const levelColors = {
    fresh: 'bg-success',
    stale: 'bg-warning',
    old: 'bg-destructive',
  };

  return (
    <div className={cn('w-10 h-1 rounded-full bg-muted overflow-hidden', className)}>
      <div
        className={cn('h-full rounded-full transition-all', levelColors[level])}
        style={{ width: `${percent}%` }}
      />
    </div>
  );
}
