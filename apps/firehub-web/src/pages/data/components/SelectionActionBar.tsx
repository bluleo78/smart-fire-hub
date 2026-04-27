import { Download, X } from 'lucide-react';

import { Button } from '../../../components/ui/button';

interface SelectionActionBarProps {
  selectedCount: number;
  onDeleteSelected: () => void;
  /**
   * 선택된 행만 CSV로 내보낸다 (클라이언트 측 변환).
   * 미지정 시 버튼 비표시.
   */
  onExportSelected?: () => void;
  /**
   * 현재 선택을 모두 해제한다.
   * 미지정 시 버튼 비표시.
   */
  onClearSelection?: () => void;
}

/**
 * 데이터 테이블 다중 선택 액션 바.
 *
 * 비파괴 액션(내보내기/선택 해제)을 좌측에, 파괴 액션(삭제)을 우측에 분리 배치하여
 * 사용자가 실수로 삭제를 누르는 위험을 줄인다 (이슈 #83).
 */
export function SelectionActionBar({
  selectedCount,
  onDeleteSelected,
  onExportSelected,
  onClearSelection,
}: SelectionActionBarProps) {
  if (selectedCount === 0) return null;
  return (
    <div className="flex items-center gap-3 px-3 py-2 bg-muted rounded-md">
      <span className="text-sm font-medium">{selectedCount}개 행 선택됨</span>

      {/* 비파괴 액션 — 좌측 그룹 */}
      {onExportSelected && (
        <Button variant="outline" size="sm" onClick={onExportSelected}>
          <Download className="h-4 w-4" />
          선택 행 CSV 내보내기
        </Button>
      )}
      {onClearSelection && (
        <Button variant="ghost" size="sm" onClick={onClearSelection}>
          <X className="h-4 w-4" />
          선택 해제
        </Button>
      )}

      {/* 파괴 액션 — 우측 분리 */}
      <div className="ml-auto">
        <Button variant="destructive" size="sm" onClick={onDeleteSelected}>
          삭제
        </Button>
      </div>
    </div>
  );
}
