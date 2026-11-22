import { PendingButton } from '@/components/PendingButton';
import { DelayedStatus } from '@/components/ui/table-empty';

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
