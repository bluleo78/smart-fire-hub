import { PendingButton } from '@/components/PendingButton';
import { DelayedStatus } from '@/components/ui/table-empty';
import { offlineListMessage, useOnlineStatus } from '@/hooks/useOnlineStatus';

interface LoadMoreFailureProps {
  /** 「다시 시도」 클릭 처리 */
  onRetry: () => void;
  /**
   * 「다시 시도」 진행 중 여부 — 눌러도 이 블록이 남는 화면(예: 무한 쿼리)만 넘긴다.
   * 누르면 블록이 바로 사라지는 화면은 생략한다(잠그지 않는다).
   */
  pending?: boolean;
}

/**
 * 목록 「더 보기」 조회 실패 안내 + 「다시 시도」 (WD-956).
 *
 * 왜 공용인가: 리포트·실행 이력·AI 검수 화면이 같은 문구·구조를 써야 해서 한 곳에 둔다.
 * 「다시 시도」는 alert 밖 바로 뒤 형제로 둔다 — DelayedStatus 가 내용을 갈아 끼워 안에 두면 초점이 끊긴다.
 * 잠금은 disabled 가 아닌 PendingButton(aria-disabled)이 맡아, 폴링·재조회 중에도 키보드 초점이 body 로 빠지지 않는다.
 */
export function LoadMoreFailure({ onRetry, pending = false }: LoadMoreFailureProps) {
  return (
    <div className="py-2 flex flex-col items-center gap-2">
      <DelayedStatus role="alert" className="text-sm text-destructive">
        다음 목록을 불러오지 못했습니다. 다시 시도하세요.
      </DelayedStatus>
      <PendingButton pending={pending} variant="outline" size="sm" onClick={onRetry}>
        다시 시도
      </PendingButton>
    </div>
  );
}

interface LoadMoreOfflineNoticeProps {
  /** 「더 보기」·「다시 시도」 응답을 기다리는 중인지 — 화면이 버튼을 잠그는 조건과 같은 값을 넘긴다 */
  waiting: boolean;
  /** 쿼리 isPaused — 오프라인뿐 아니라 탭 숨김으로 재시도가 멈춰도 참이라, 안에서 실제 온라인 여부와 함께 본다 */
  isPaused: boolean;
  /** 목록 이름(offlineListMessage 의 label) — 예: 실행 이력, 리포트 */
  label: string;
}

/**
 * 「더 보기」 기다림이 오프라인으로 멈췄을 때 버튼 자리 아래에 보이는 연결 대기 안내 (WD-1035).
 *
 * 왜 표 분기(useOfflinePaused)가 아닌가: 「더 보기」는 조건 변경이 아니라 이어 받기라 보던 행을 지워 표를 안내로 바꾸지 않는다.
 * 버튼(또는 실패 블록의 「다시 시도」)은 잠긴 채 남아 초점을 지키고, 연결되면 재조회와 함께 안내가 사라진다.
 * 버튼 보임 조건과 상관없이 그리도록 버튼 분기 밖에 둔다. DelayedStatus 로 그려 마운트와 함께 낭독되게 한다(WD-815).
 */
export function LoadMoreOfflineNotice({ waiting, isPaused, label }: LoadMoreOfflineNoticeProps) {
  const isOnline = useOnlineStatus();
  if (!(waiting && isPaused && !isOnline)) return null;
  return <DelayedStatus className="text-center text-sm text-muted-foreground">{offlineListMessage(label)}</DelayedStatus>;
}
