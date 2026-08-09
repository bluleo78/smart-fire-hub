import { AlertCircle, Check, Loader2 } from 'lucide-react';

import { Button } from '@/components/ui/button';
import type { SaveState } from '@/hooks/queries/useOntologyElement';

interface Props {
  state: SaveState;
  /** 마지막 실패 뮤테이션 재시도(Task 4). state==='error'일 때만 의미 있게 노출한다 —
   * 훅의 retry는 saveState==='error'와 항상 함께 붙어 있으므로(useOntologyElement.ts 참고)
   * 다른 상태에서 넘어와도 버튼 자체를 그리지 않아 무해하다. */
  onRetry?: () => void;
}

// 상태별 스크린리더 낭독 문구 — 시각 칩 문구와 동일하게 맞춘다.
const STATUS_MESSAGE: Record<SaveState, string> = {
  idle: '',
  saving: '저장 중',
  saved: '저장됨',
  error: '저장 실패',
};

// 자동 저장 상태 칩 — 필드별 인라인 표시자 대신 툴바 한 곳에서 저장 상태를 보여준다(사용자 명시 선택).
// (리뷰 IMP-2) 라이브 리전은 idle을 포함해 항상 마운트해 둔다 — 리전이 "생기는 시점"과 "내용이 채워지는
// 시점"이 같으면(예: state가 이미 'saving'/'error'인 채로 컴포넌트가 처음 마운트되면) 대부분의
// 스크린리더가 최초 내용을 낭독하지 않는다. idle일 때 빈 문자열로 먼저 자리를 잡아 두면 이후 saveState가
// 바뀔 때마다 "이미 마운트된 리전의 내용 변경"이 되어 안정적으로 낭독된다.
// (리뷰 IMP-5 → Task 4) onRetry를 되살렸다 — 이제 훅이 실제 뮤테이션과 retry()를 노출하므로
// 더 이상 배선할 곳 없는 죽은 prop이 아니다.
export default function SaveStatusChip({ state, onRetry }: Props) {
  return (
    <>
      <div role="status" aria-live="polite" className="sr-only" data-testid="save-status-live-region">
        {STATUS_MESSAGE[state]}
      </div>
      {/* idle(아직 아무 것도 편집하지 않음)은 시각적으로는 아무 것도 그리지 않는다 —
          편집을 시작하기 전까지 툴바에 잡음을 더하지 않기 위해. */}
      {state === 'saving' && (
        <div className="flex items-center gap-1.5 text-xs text-muted-foreground" data-testid="save-status-chip" data-state={state}>
          <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
          저장 중…
        </div>
      )}
      {state === 'error' && (
        <div className="flex items-center gap-1.5 text-xs text-destructive" data-testid="save-status-chip" data-state={state}>
          <AlertCircle className="h-3.5 w-3.5" aria-hidden="true" />
          저장 실패
          {onRetry && (
            <Button
              variant="ghost"
              size="sm"
              className="h-5 px-1.5 text-xs text-destructive underline-offset-2 hover:underline"
              onClick={onRetry}
              data-testid="save-status-retry"
            >
              재시도
            </Button>
          )}
        </div>
      )}
      {state === 'saved' && (
        <div className="flex items-center gap-1.5 text-xs text-muted-foreground" data-testid="save-status-chip" data-state={state}>
          <Check className="h-3.5 w-3.5" aria-hidden="true" />
          저장됨
        </div>
      )}
    </>
  );
}
