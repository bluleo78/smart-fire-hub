import { useCallback,useState } from 'react';

export function useRowSelection(allRowIds: number[]) {
  const [selectedRowIds, setSelectedRowIds] = useState<Set<number>>(new Set());

  const handleSelectAll = useCallback(
    (checked: boolean) => {
      if (checked) {
        setSelectedRowIds(new Set(allRowIds));
      } else {
        setSelectedRowIds(new Set());
      }
    },
    [allRowIds]
  );

  const handleSelectRow = useCallback((rowId: number, checked: boolean) => {
    setSelectedRowIds((prev) => {
      const next = new Set(prev);
      if (checked) next.add(rowId);
      else next.delete(rowId);
      return next;
    });
  }, []);

  const clearSelection = useCallback(() => {
    setSelectedRowIds(new Set());
  }, []);

  const selectedCount = selectedRowIds.size;
  const isAllSelected = allRowIds.length > 0 && selectedCount === allRowIds.length;
  const isIndeterminate = selectedCount > 0 && selectedCount < allRowIds.length;

  return {
    selectedRowIds,
    setSelectedRowIds,
    handleSelectAll,
    handleSelectRow,
    isAllSelected,
    isIndeterminate,
    selectedCount,
    clearSelection,
  };
}
