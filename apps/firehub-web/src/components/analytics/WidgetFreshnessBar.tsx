import { AlertCircle, Circle, RefreshCw } from 'lucide-react';
import { useEffect, useState } from 'react';

import { formatElapsedTime } from '../../lib/formatters';
import { Badge } from '../ui/badge';
import { Button } from '../ui/button';

interface WidgetFreshnessBarProps {
  dataUpdatedAt: number;       // TanStack Query's dataUpdatedAt (timestamp ms)
  isFetching: boolean;
  isError?: boolean;           // 마지막 새로고침(refetch)이 실패했는지 (#566)
  refreshSeconds?: number;     // dashboard autoRefreshSeconds
  onRefresh: () => void;       // manual refresh callback
  /** 위젯(차트) 이름 — 새로고침 버튼 이름에 넣어 위젯마다 어느 차트의 버튼인지 구분되게 한다(WD-423). 없으면 「새로고침」 */
  widgetName?: string;
}

export function WidgetFreshnessBar({
  dataUpdatedAt,
  isFetching,
  isError,
  refreshSeconds,
  onRefresh,
  widgetName,
}: WidgetFreshnessBarProps) {
  const [now, setNow] = useState(() => Date.now());

  // 경과 시간("방금", "N초 전" 등) 표시는 자동 갱신 여부와 무관하게 항상 흘러야 하므로,
  // refreshSeconds(자동 갱신 주기) 유무와 관계없이 10초 간격으로 now를 갱신한다.
  // (refreshSeconds는 isStale 판정에만 사용된다)
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 10_000);
    return () => clearInterval(timer);
  }, []);

  const elapsed = dataUpdatedAt > 0 ? now - dataUpdatedAt : null;
  const timeAgoText = elapsed !== null ? formatElapsedTime(elapsed) : null;

  const staleThresholdMs =
    refreshSeconds && refreshSeconds > 0 ? refreshSeconds * 1.5 * 1000 : null;
  const isStale =
    staleThresholdMs !== null && elapsed !== null && elapsed >= staleThresholdMs;

  // 인쇄(WD-424): 「N분 전」·새로고침 버튼은 종이에 의미가 없으므로 숨긴다 — 기준 시각은 대시보드 이름 아래 인쇄 시각 줄이 대신한다
  return (
    <div className="flex items-center justify-between px-1 pt-0.5 gap-1 print:hidden">
      <div className="flex items-center gap-1 min-w-0">
        {isFetching ? (
          <Badge
            variant="secondary"
            className="text-[10px] px-1.5 py-0 h-4 gap-1 bg-info/10 text-info border-info/20"
          >
            <RefreshCw className="h-2.5 w-2.5 animate-spin motion-reduce:animate-none" />
            갱신 중...
          </Badge>
        ) : isError ? (
          // 새로고침 API 요청이 실패한 경우 — placeholderData로 이전 데이터가 그대로
          // 보이더라도 실패 사실만은 눈에 띄게 노출한다(#566). 클릭 시 재시도.
          <Badge
            variant="secondary"
            className="text-[10px] px-1.5 py-0 h-4 gap-1 bg-destructive/10 text-destructive border-destructive/20"
            title="새로고침 실패 · 다시 시도하려면 새로고침 버튼을 누르세요"
          >
            <AlertCircle className="h-2.5 w-2.5" />
            새로고침 실패
          </Badge>
        ) : refreshSeconds && refreshSeconds > 0 ? (
          isStale ? (
            <Badge
              variant="secondary"
              className="text-[10px] px-1.5 py-0 h-4 gap-1 bg-caution-subtle text-caution border-caution/20"
            >
              <Circle className="h-2 w-2 fill-caution text-caution" />
              {timeAgoText ?? '-'}
            </Badge>
          ) : (
            <span className="flex items-center gap-1 text-[10px] text-muted-foreground">
              <Circle className="h-2 w-2 fill-success text-success" />
              {timeAgoText ?? '-'}
            </span>
          )
        ) : (
          <span className="text-[10px] text-muted-foreground">
            {timeAgoText ?? '-'}
          </span>
        )}
      </div>

      {/* WD-423: 위젯마다 이름이 똑같은 「새로고침」이라 스크린리더·음성 제어로 어느 차트의 버튼인지 알 수 없었다 — 이름에 위젯 이름을 넣는다.
          누르는 영역은 16px → 24px 로 넓히되, 위아래 음수 여백으로 바 높이(차트 영역)는 그대로 둔다. */}
      <Button
        variant="ghost"
        size="icon"
        className="-my-1 size-6 shrink-0 text-muted-foreground hover:text-foreground"
        onClick={(e) => {
          e.stopPropagation();
          onRefresh();
        }}
        title="새로고침"
        aria-label={widgetName ? `${widgetName} 새로고침` : '새로고침'}
      >
        <RefreshCw className="h-2.5 w-2.5" aria-hidden="true" />
      </Button>
    </div>
  );
}
