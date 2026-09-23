import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '../../components/ui/alert-dialog';

/**
 * 설정 탭의 "설정 해제" 확인 다이얼로그 — AI 분류 탭과 이메일(SMTP) 탭이 함께 쓴다.
 *
 * 두 탭 모두 저장된 비밀(API 키·SMTP 비밀번호)을 복구할 수 없게 지우므로 파괴적(destructive)
 * 확인 버튼을 쓴다. `DeleteConfirmDialog` 를 쓰지 않는 이유: 고정 문구가 "삭제"라, 무엇이
 * 해제되고 그 뒤 무엇이 달라지는지를 탭마다 다르게 말해야 하는 이 자리와 맞지 않는다.
 *
 * 확인 버튼 문구는 `confirmLabel`(기본값: 제목)이다 — 다이얼로그를 연 버튼과 같은 말을 써서
 * "무엇을 확정하는지"가 누르는 순간에도 보이게 한다.
 */
export function ClearConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel = title,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  confirmLabel?: string;
  /** 확인을 누른 뒤 실행할 해제 동작. 다이얼로그는 먼저 닫힌다. */
  onConfirm: () => void;
}) {
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>취소</AlertDialogCancel>
          <AlertDialogAction
            variant="destructive"
            onClick={() => {
              onOpenChange(false);
              onConfirm();
            }}
          >
            {confirmLabel}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
