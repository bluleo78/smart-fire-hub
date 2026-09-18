import { useNowMs } from '@/hooks/useNowMs';
import { formatDuration } from '@/lib/formatters';

interface DurationTextProps {
  startedAt: string | null;
  completedAt: string | null;
  /** 실행 중이면 1초마다 다시 그려 경과 시간이 흐르게 한다. */
  running: boolean;
}

/**
 * 실행/스텝의 소요 시간 텍스트 (#691).
 *
 * **잎 컴포넌트로 둔 이유**: 1초 타이머를 쓰는 쪽이 페이지 루트면 실행 중 내내 캔버스·설정 패널까지
 * 매초 다시 그린다(2시간짜리 실행이면 수천 번). 타이머를 이 텍스트 노드 안에 가두면 갱신 범위가
 * 이 한 칸으로 좁혀지고, 호출부마다 반복되던 "실행 중 판정 + 틱 상태" 도 여기 한 곳에만 남는다.
 */
export function DurationText({ startedAt, completedAt, running }: DurationTextProps) {
  const nowMs = useNowMs(running);
  return <>{formatDuration(startedAt, completedAt, nowMs)}</>;
}
