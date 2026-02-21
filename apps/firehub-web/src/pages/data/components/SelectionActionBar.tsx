import { Button } from '../../../components/ui/button';

interface SelectionActionBarProps {
  selectedCount: number;
  onDeleteSelected: () => void;
}

export function SelectionActionBar({ selectedCount, onDeleteSelected }: SelectionActionBarProps) {
  if (selectedCount === 0) return null;
  return (
    <div className="flex items-center gap-3 px-3 py-2 bg-muted rounded-md">
      <span className="text-sm font-medium">{selectedCount}개 행 선택됨</span>
      <Button variant="destructive" size="sm" onClick={onDeleteSelected}>
        삭제
      </Button>
    </div>
  );
}
