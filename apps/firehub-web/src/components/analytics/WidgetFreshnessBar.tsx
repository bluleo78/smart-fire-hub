import { Circle, RefreshCw } from 'lucide-react';
import { useEffect, useState } from 'react';

import { formatElapsedTime } from '../../lib/formatters';
import { Badge } from '../ui/badge';
import { Button } from '../ui/button';

interface WidgetFreshnessBarProps {
  dataUpdatedAt: number;       // TanStack Query's dataUpdatedAt (timestamp ms)
  isFetching: boolean;
  refreshSeconds?: number;     // dashboard autoRefreshSeconds
  onRefresh: () => void;       // manual refresh callback
}

export function WidgetFreshnessBar({
  dataUpdatedAt,
  isFetching,
  refreshSeconds,
  onRefresh,
}: WidgetFreshnessBarProps) {
  const [now, setNow] = useState(() => Date.now());

  // Only tick when auto-refresh is active; otherwise compute once on mount
  useEffect(() => {
    if (!refreshSeconds || refreshSeconds <= 0) return;
    const timer = setInterval(() => setNow(Date.now()), 10_000);
    return () => clearInterval(timer);
  }, [refreshSeconds]);

  const elapsed = dataUpdatedAt > 0 ? now - dataUpdatedAt : null;
  const timeAgoText = elapsed !== null ? formatElapsedTime(elapsed) : null;

  const staleThresholdMs =
    refreshSeconds && refreshSeconds > 0 ? refreshSeconds * 1.5 * 1000 : null;
  const isStale =
    staleThresholdMs !== null && elapsed !== null && elapsed >= staleThresholdMs;

  return (
    <div className="flex items-center justify-between px-1 pt-0.5 gap-1">
      <div className="flex items-center gap-1 min-w-0">
        {isFetching ? (
          <Badge
            variant="secondary"
            className="text-[10px] px-1.5 py-0 h-4 gap-1 bg-blue-500/10 text-blue-600 dark:text-blue-400 border-blue-500/20"
          >
            <RefreshCw className="h-2.5 w-2.5 animate-spin motion-reduce:animate-none" />
            갱신 중...
          </Badge>
        ) : refreshSeconds && refreshSeconds > 0 ? (
          isStale ? (
            <Badge
              variant="secondary"
              className="text-[10px] px-1.5 py-0 h-4 gap-1 bg-orange-500/10 text-orange-600 dark:text-orange-400 border-orange-500/20"
            >
              <Circle className="h-2 w-2 fill-orange-500 text-orange-500" />
              {timeAgoText ?? '-'}
            </Badge>
          ) : (
            <span className="flex items-center gap-1 text-[10px] text-muted-foreground">
              <Circle className="h-2 w-2 fill-green-500 text-green-500" />
              {timeAgoText ?? '-'}
            </span>
          )
        ) : (
          <span className="text-[10px] text-muted-foreground">
            {timeAgoText ?? '-'}
          </span>
        )}
      </div>

      <Button
        variant="ghost"
        size="icon"
        className="h-4 w-4 shrink-0 text-muted-foreground hover:text-foreground"
        onClick={(e) => {
          e.stopPropagation();
          onRefresh();
        }}
        title="새로고침"
      >
        <RefreshCw className="h-2.5 w-2.5" />
      </Button>
    </div>
  );
}
