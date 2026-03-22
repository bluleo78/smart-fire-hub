import { cn } from '../../lib/utils';

interface SparklineProps {
  data: number[];
  color?: 'pipeline' | 'dataset' | 'dashboard';
  className?: string;
}

export function Sparkline({ data, color = 'dataset', className }: SparklineProps) {
  const max = Math.max(...data, 1);

  const colorMap = {
    pipeline: { bar: 'bg-pipeline/20', high: 'bg-pipeline' },
    dataset: { bar: 'bg-primary/20', high: 'bg-primary' },
    dashboard: { bar: 'bg-dashboard-accent/20', high: 'bg-dashboard-accent' },
  };

  const colors = colorMap[color];
  const threshold = max * 0.75;

  return (
    <div className={cn('flex items-end gap-[2px] h-5', className)}>
      {data.map((value, i) => (
        <div
          key={i}
          className={cn(
            'w-1 rounded-sm min-h-[2px] transition-all',
            value >= threshold ? colors.high : colors.bar
          )}
          style={{ height: `${(value / max) * 100}%` }}
        />
      ))}
    </div>
  );
}
