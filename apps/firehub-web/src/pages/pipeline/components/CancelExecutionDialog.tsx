import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';

interface CancelExecutionDialogProps {
  /** 열림 여부 — 실행 상세 헤더와 실행 이력 목록이 같은 창을 쓰도록 페이지에서 제어한다. */
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 「실행 취소」 확정 — 이 때만 취소 API 를 부른다. */
  onConfirm: () => void;
  /** 취소할 실행 id — 제목 아래에 표시해 어떤 실행을 멈추는지 분명히 한다. */
  executionId: number | null;
}

/**
 * 파이프라인 실행 취소 확인 창(WD-153).
 *
 * 왜 확인을 받는가: 취소는 되돌릴 수 없고, 즉시 멈추는 것이 아니라 "진행 중인 작업이 끝난 뒤 멈추는" 협력적 취소라
 * 결과(남는 데이터)가 적재 방식에 따라 다르다. 사용자가 그 의미를 알고 누르도록 설명을 함께 보여 준다.
 *
 * 왜 페이지 수준 한 곳에 두는가: 실행 이력 행은 클릭 시 상세로 이동하는데, React 합성 이벤트는 포털을 거쳐서도 부모로
 * 전파되므로 행 안에 창을 두면 창 버튼 클릭이 행 이동으로 이어진다. 목록 밖에 하나만 두고 대상 실행 id 로 제어한다.
 *
 * 닫기 버튼을 「취소」 대신 「계속 실행」으로 둔 이유: 확정 버튼 「실행 취소」 옆에 「취소」가 있으면 어느 쪽이 실행을
 * 멈추는지 헷갈린다.
 */
export function CancelExecutionDialog({ open, onOpenChange, onConfirm, executionId }: CancelExecutionDialogProps) {
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      {/* 행 클릭(상세 이동)으로 이벤트가 번지지 않도록 막는다 — DeleteConfirmDialog 와 같은 처리 */}
      <AlertDialogContent onClick={(e) => e.stopPropagation()}>
        <AlertDialogHeader>
          <AlertDialogTitle>실행을 취소할까요?</AlertDialogTitle>
          <AlertDialogDescription>
            {executionId !== null && <>실행 #{executionId}을(를) 멈춥니다. </>}
            지금 진행 중인 API 호출이나 SQL 은 끝까지 마친 뒤 멈추고, 남은 스텝은 실행하지 않습니다. 교체(REPLACE)
            방식 스텝은 기존 데이터가 그대로 유지되고, 추가(APPEND) 방식 스텝은 취소 전까지 적재된 행이 남습니다.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>계속 실행</AlertDialogCancel>
          <AlertDialogAction variant="destructive" onClick={onConfirm}>
            실행 취소
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
