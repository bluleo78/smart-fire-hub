import type { ReactNode } from 'react';

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
} from '@/components/ui/alert-dialog';
import type { EntityTypeDef, Triple } from '@/types/ontology';

interface Props {
  entity: EntityTypeDef;
  // 이 타입을 끝점으로 쓰는 관계 — 삭제 시 FK CASCADE로 서버가 함께 지운다. 삭제 전에 몇 개가,
  // 어떤 이름으로 함께 사라지는지 사용자에게 미리 보여줘야 한다(브리프 필수 항목).
  affectedRelations: Triple[];
  onConfirm: () => void;
  trigger: ReactNode;
  // 삭제 확정 시 트리거(entity-delete-trigger) 자신이 EntityInspector와 함께 사라진다(삭제 성공
  // → onDeleted → 선택 해제 → 인스펙터가 다른 내용으로 교체) — 공유 dialog.tsx의 기본 포커스
  // 복귀(트리거로 되돌리기)가 겨냥할 대상이 DOM에서 없어진다(#328류, M-2 Task 6 리뷰). 그 자리에
  // 계속 남아 있는 대체 대상(ModelOutline의 "타입 추가" 버튼)을 대신 지정한다 — DatasetMappingTab의
  // entityAddRef/relationAddRef와 같은 패턴.
  restoreFocusRef?: React.RefObject<HTMLElement | null>;
}

// 엔티티 타입 삭제 확인 — src/components/ui/delete-confirm-dialog.tsx(범용)는 고정 문구만 지원해
// "함께 지워질 관계 목록"처럼 항목별 커스텀 본문을 넣을 수 없다. 범용화는 후속 백로그
// (DeleteConfirmDialog 일반화)로 남겨두고, 이 화면 전용으로 별도 컴포넌트를 둔다(YAGNI).
export default function DeleteTypeConfirm({ entity, affectedRelations, onConfirm, trigger, restoreFocusRef }: Props) {
  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>{trigger}</AlertDialogTrigger>
      <AlertDialogContent restoreFocusRef={restoreFocusRef}>
        <AlertDialogHeader>
          <AlertDialogTitle>{entity.type} 타입 삭제</AlertDialogTitle>
          <AlertDialogDescription asChild>
            <div className="space-y-2 text-left">
              <p>&quot;{entity.type}&quot; 타입을 삭제하면 되돌릴 수 없습니다.</p>
              {affectedRelations.length > 0 && (
                <div data-testid="delete-type-affected-relations">
                  <p className="font-medium text-foreground">
                    함께 삭제되는 관계 {affectedRelations.length}개
                  </p>
                  <ul className="list-disc pl-4">
                    {affectedRelations.map((r) => (
                      <li key={r.id}>
                        {r.subject} → {r.relation} → {r.object}
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>취소</AlertDialogCancel>
          <AlertDialogAction onClick={onConfirm} data-testid="entity-delete-confirm">
            삭제
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
