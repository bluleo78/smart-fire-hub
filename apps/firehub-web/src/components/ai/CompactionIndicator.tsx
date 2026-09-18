import { Loader2 } from 'lucide-react';
import { useEffect, useState } from 'react';

interface CompactionIndicatorProps {
  /** 압축 시작 시각(ms). 경과 시간 계산 기준 — null 이면 0초부터 센다. */
  startedAt?: number | null;
}

/**
 * 컨텍스트 자동 압축(요약) 진행 표시 (#692).
 *
 * 압축은 1분 이상 걸리는 일이 흔한데, 그동안 대화 영역에는 아무 변화가 없어
 * 사용자가 응답이 멈춘 것으로 오해했다. 헤더 구석의 칩만으로는 시선이 닿지 않으므로
 * 완료 시스템 메시지가 붙는 것과 같은 자리(대화 영역 하단)에 진행 표시를 둔다.
 * 경과 초를 함께 보여주어 "고장인가"와 "기다리면 되는가"를 구분할 수 있게 한다.
 */
export function CompactionIndicator({ startedAt }: CompactionIndicatorProps) {
  // 1초마다 현재 시각만 갱신하고 경과 초는 렌더에서 파생한다
  // (effect 본문에서 직접 setState 하지 않기 위한 구조).
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  const elapsedSec = startedAt != null ? Math.max(0, Math.floor((now - startedAt) / 1000)) : 0;

  return (
    <div className="flex w-full justify-start" data-testid="compaction-indicator">
      <div className="max-w-[85%] rounded-lg bg-muted px-3 py-2">
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="h-3.5 w-3.5 animate-spin" />
          <span>대화가 길어져 컨텍스트를 요약하는 중 · {elapsedSec}초</span>
        </div>
        <p className="mt-1 text-xs text-muted-foreground">
          요약이 끝나면 이어서 답변합니다. 잠시만 기다려 주세요.
        </p>
      </div>
    </div>
  );
}
