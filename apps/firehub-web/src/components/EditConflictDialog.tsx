import { AlertDialog as AlertDialogPrimitive } from 'radix-ui';
import { useRef, useState } from 'react';

import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';

import { PendingButton } from './PendingButton';

/** 충돌 창 버튼이 부르는 일 — 비동기면 실제 작업의 Promise 를 돌려줘야 창이 그 일이 끝날 때까지 잠긴다(WD-678) */
type ConflictAction = () => void | Promise<unknown>;

interface EditConflictDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 리소스 이름(예: '파이프라인') — 안내 문구에 들어간다 */
  resourceLabel: string;
  /** 최신 내용을 다시 불러오고 내 변경을 버린다 */
  onReload: ConflictAction;
  /** 기준 버전 없이 다시 저장해 먼저 저장된 변경을 내 변경으로 바꾼다 */
  onOverwrite: ConflictAction;
  /** 다른 곳에서 시작한 저장·불러오기가 진행 중이면 버튼을 막는다(창 안 잠금과 합쳐 본다) */
  pending?: boolean;
}

/**
 * 편집 충돌 안내 창(WD-353) — 저장이 409 EDIT_CONFLICT 로 거부됐을 때 띄우는 공통 창.
 *
 * 편집을 시작한 뒤 다른 사람(또는 다른 탭)이 먼저 저장했다는 사실을 알리고 두 길을 준다.
 * 예전에는 경고 없이 나중 저장이 먼저 저장된 변경을 되돌렸다 — 이제 사용자가 어느 쪽을 남길지 고른다.
 * 리소스마다 같은 창을 쓰도록 문구는 resourceLabel 로만 바꾼다(isEditConflict 와 짝).
 * 닫기(계속 편집)를 고르면 내 변경은 화면에 남고, 다시 저장하면 같은 충돌이 다시 난다.
 *
 * 진행 중 잠금(WD-678): 세 버튼은 `disabled` 대신 aria-disabled(PendingButton)로 잠가 키보드로 누른 버튼에 포커스를 남긴다.
 * 누르면 ref 로 그 자리에서 잠그고(같은 틱 두 번 누름 차단) 콜백의 Promise 가 끝날 때 푼다 — 부모 pending 은 늦게 오거나 불러오기를 덮지 않을 수 있다.
 * 잠긴 동안 「계속 편집」은 닫지 않지만 Escape 는 끝나지 않는 요청의 비상구로 허용한다.
 * 닫아도 잠금은 Promise 가 끝날 때까지 남아, 그 전에 다시 연 창에서도 요청이 두 번 나가지 않는다.
 */
export function EditConflictDialog({
  open,
  onOpenChange,
  resourceLabel,
  onReload,
  onOverwrite,
  pending = false,
}: EditConflictDialogProps) {
  const lockedRef = useRef(false);
  const [locked, setLocked] = useState(false);

  const runLocked = (action: ConflictAction) => {
    if (lockedRef.current) return;
    lockedRef.current = true;
    setLocked(true);
    const unlock = () => {
      lockedRef.current = false;
      setLocked(false);
    };
    // 동기 throw 도 거부로 받고, 거부는 잠금만 풀고 삼킨다 — 오류 안내는 각 화면의 콜백이 맡는다
    new Promise((resolve) => resolve(action())).then(unlock, unlock);
  };

  const busy = pending || locked;

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
          {/* AlertDialogCancel 과 같은 모양(outline)으로 잠금은 PendingButton 에 맡긴다 — 진행 중 preventDefault 면 Radix 가 닫기를 건너뛴다 */}
          <AlertDialogPrimitive.Cancel asChild>
            <PendingButton variant="outline" data-slot="alert-dialog-cancel" pending={busy}>
              계속 편집
            </PendingButton>
          </AlertDialogPrimitive.Cancel>
          {/* AlertDialogAction 은 누르면 창을 바로 닫는다 — 덮어쓰기 저장이 끝날 때까지 창을 두려고 일반 버튼을 쓴다 */}
          <PendingButton variant="outline" pending={busy} onClick={() => runLocked(onReload)}>
            다시 불러오기
          </PendingButton>
          <PendingButton variant="destructive" pending={busy} onClick={() => runLocked(onOverwrite)}>
            내 변경으로 덮어쓰기
          </PendingButton>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
