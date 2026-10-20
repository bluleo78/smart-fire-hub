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
  // (#647) 이름만으로는 항목을 구분할 수 없는 화면(예: 이름 중복이 가능한 API 연결)에서 ID/URL
  // 등 추가 식별 정보를 함께 보여줘 오삭제를 방지한다. 지정하지 않으면 기존과 동일하게 이름만 표시.
  extraDetail?: string;
  // (WD-86) 확인 문구 아래에 덧붙일 블록 — 예: 함께 삭제되는 항목 안내 상자. Description(<p>) 밖에 두므로 목록 등
  // 블록 요소를 넣을 수 있다. 창 내용 안에서 렌더되므로 열려 있을 때만 마운트되고(닫힘 애니메이션 동안은 유지) 닫히면
  // 언마운트된다 — 열 때만 조회하는 안내를 그대로 넣을 수 있다. 지정하지 않으면 기존과 동일.
  details?: ReactNode;
  // (WD-86) 확인 버튼 비활성화 — 안내 내용을 아직 불러오는 중에 사용자가 내용을 보기 전에 확정하지 못하게 한다.
  confirmDisabled?: boolean;
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
  extraDetail,
  details,
  confirmDisabled,
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
            {/* (#647) 이름이 같은 다른 항목과 헷갈리지 않도록 ID/URL 등 보조 식별 정보를 함께 노출.
                Radix Description은 <p>로 렌더링되어 block 요소를 중첩할 수 없으므로 같은 문단 안에 이어 붙인다. */}
            {extraDetail && <> ({extraDetail})</>}
          </AlertDialogDescription>
        </AlertDialogHeader>
        {details}
        <AlertDialogFooter>
          <AlertDialogCancel>취소</AlertDialogCancel>
          {/* WD-5: 이 컴포넌트는 정의상 "되돌릴 수 없는 삭제" 확인이다(제목·문구 고정). 디자인 시스템 04 §D 에 따라
              확인 버튼은 항상 destructive — 호출처 prop 으로 열지 않는다(한 곳만 빠져도 같은 결함이 재발한다). */}
          <AlertDialogAction variant="destructive" onClick={onConfirm} disabled={confirmDisabled}>
            삭제
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
