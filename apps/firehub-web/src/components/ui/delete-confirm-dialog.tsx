import type { ReactNode } from 'react';

import { eulReul } from '../../lib/utils';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from './alert-dialog';

interface CommonProps {
  entityName: string;
  itemName: string;
  onConfirm: () => void;
  // 트리거 없이 여는 사용처(#420 — OntologyPage 캔버스 Delete 키)를 위한 포커스 복귀 대상.
  // DeleteTypeConfirm.tsx의 restoreFocusRef와 동일한 이유: 삭제 성공 시 트리거 자신이 사라질 수
  // 있어 AlertDialogContent의 기본 "트리거로 복귀"가 불가능하다.
  restoreFocusRef?: React.RefObject<HTMLElement | null>;
}

// 클릭으로 여는 기존 사용처(예: RelationInspector의 "관계 삭제" 버튼)는 trigger를 넘긴다. 트리거
// 없이 여는 사용처(OntologyPage — 캔버스/아웃라인 Delete 키, #420)는 open/onOpenChange로 제어한다.
// DeleteTypeConfirm.tsx와 동일한 판별 유니온(리뷰 M-5)을 재사용해 "trigger도 open도 안 넘긴" 채
// 컴파일이 통과하는 것을 막는다.
type DeleteConfirmDialogProps = CommonProps &
  ({ trigger: ReactNode; open?: never; onOpenChange?: never } | { trigger?: never; open: boolean; onOpenChange: (open: boolean) => void });

export function DeleteConfirmDialog({
  entityName,
  itemName,
  onConfirm,
  trigger,
  open,
  onOpenChange,
  restoreFocusRef,
}: DeleteConfirmDialogProps) {
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      {trigger && (
        <AlertDialogTrigger asChild onClick={(e) => e.stopPropagation()}>
          {trigger}
        </AlertDialogTrigger>
      )}
      <AlertDialogContent onClick={(e) => e.stopPropagation()} restoreFocusRef={restoreFocusRef}>
        <AlertDialogHeader>
          <AlertDialogTitle>{entityName} 삭제</AlertDialogTitle>
          <AlertDialogDescription>
            &quot;{itemName}&quot; {entityName}{eulReul(entityName)} 정말 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>취소</AlertDialogCancel>
          <AlertDialogAction onClick={onConfirm}>삭제</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
