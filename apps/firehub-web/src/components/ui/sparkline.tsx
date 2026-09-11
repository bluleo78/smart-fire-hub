import { cn } from '../../lib/utils';

interface SparklineProps {
  data: number[];
  color?: 'pipeline' | 'dataset' | 'dashboard';
  className?: string;
  /** 접근성 라벨 (예: "최근 7일 파이프라인 실행 추이") — 실데이터 배선 확인용 테스트에도 사용 */
  ariaLabel?: string;
  /** E2E 테스트에서 특정 스파크라인을 식별하기 위한 testid */
  testId?: string;
}

export function Sparkline({ data, color = 'dataset', className, ariaLabel, testId }: SparklineProps) {
  const max = Math.max(...data, 1);

  const colorMap = {
    pipeline: { bar: 'bg-pipeline/20', high: 'bg-pipeline' },
    dataset: { bar: 'bg-primary/20', high: 'bg-primary' },
    dashboard: { bar: 'bg-dashboard-accent/20', high: 'bg-dashboard-accent' },
  };

  const colors = colorMap[color];
  const threshold = max * 0.75;

  return (
    <div
      className={cn('flex items-end gap-[2px] h-5', className)}
      role="img"
      aria-label={ariaLabel}
      data-testid={testId}
    >
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
