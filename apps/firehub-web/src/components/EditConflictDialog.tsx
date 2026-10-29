import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Button } from '@/components/ui/button';

interface EditConflictDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 리소스 이름(예: '파이프라인') — 안내 문구에 들어간다 */
  resourceLabel: string;
  /** 최신 내용을 다시 불러오고 내 변경을 버린다 */
  onReload: () => void;
  /** 기준 버전 없이 다시 저장해 먼저 저장된 변경을 내 변경으로 바꾼다 */
  onOverwrite: () => void;
  /** 다시 불러오기·덮어쓰기가 진행 중이면 버튼을 막는다(두 번 누름 방지) */
  pending?: boolean;
}

/**
 * 편집 충돌 안내 창(WD-353) — 저장이 409 EDIT_CONFLICT 로 거부됐을 때 띄우는 공통 창.
 *
 * 편집을 시작한 뒤 다른 사람(또는 다른 탭)이 먼저 저장했다는 사실을 알리고 두 길을 준다.
 * 예전에는 경고 없이 나중 저장이 먼저 저장된 변경을 되돌렸다 — 이제 사용자가 어느 쪽을 남길지 고른다.
 * 리소스마다 같은 창을 쓰도록 문구는 resourceLabel 로만 바꾼다(isEditConflict 와 짝).
 * 닫기(계속 편집)를 고르면 내 변경은 화면에 남고, 다시 저장하면 같은 충돌이 다시 난다.
 */
export function EditConflictDialog({
  open,
  onOpenChange,
  resourceLabel,
  onReload,
  onOverwrite,
  pending = false,
}: EditConflictDialogProps) {
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>다른 곳에서 먼저 변경되었습니다</AlertDialogTitle>
          <AlertDialogDescription>
            이 {resourceLabel}은(는) 편집을 시작한 뒤 다른 사용자 또는 다른 탭에서 변경되었습니다.
            「다시 불러오기」는 최신 내용을 불러오고 내 변경을 버립니다. 「내 변경으로 덮어쓰기」는 먼저 저장된 변경을 내 변경으로 바꿉니다.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={pending}>계속 편집</AlertDialogCancel>
          {/* AlertDialogAction 은 누르면 창을 바로 닫는다 — 덮어쓰기 저장이 끝날 때까지 창을 두려고 일반 버튼을 쓴다 */}
          <Button variant="outline" onClick={onReload} disabled={pending}>
            다시 불러오기
          </Button>
          <Button variant="destructive" onClick={onOverwrite} disabled={pending}>
            내 변경으로 덮어쓰기
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
