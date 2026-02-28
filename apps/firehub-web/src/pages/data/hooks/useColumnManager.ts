import React, { useCallback } from 'react';
import { toast } from 'sonner';

import { useDeleteColumn, useReorderColumns } from '../../../hooks/queries/useDatasets';
import { handleApiError } from '../../../lib/api-error';
import type { DatasetColumnResponse,DatasetDetailResponse } from '../../../types/dataset';

interface UseColumnManagerOptions {
  dataset: DatasetDetailResponse;
  datasetId: number;
}

interface UseColumnManagerReturn {
  localColumns: DatasetColumnResponse[];
  expandedColumnId: number | null;
  selectedColumn: DatasetColumnResponse | null;
  editingColumnId: number | null;
  addColumnOpen: boolean;
  setAddColumnOpen: (open: boolean) => void;
  editColumnOpen: boolean;
  setEditColumnOpen: (open: boolean) => void;
  deleteColumnOpen: boolean;
  setDeleteColumnOpen: (open: boolean) => void;
  setEditingColumnId: (id: number | null) => void;
  isReorderPending: boolean;
  handlers: {
    moveColumn: (index: number, direction: 'up' | 'down') => void;
    deleteColumn: () => void;
    editClick: (column: DatasetColumnResponse) => void;
    deleteClick: (column: DatasetColumnResponse) => void;
    toggleExpand: (colId: number) => void;
  };
}

export function useColumnManager({
  dataset,
  datasetId,
}: UseColumnManagerOptions): UseColumnManagerReturn {
  const [addColumnOpen, setAddColumnOpen] = React.useState(false);
  const [editColumnOpen, setEditColumnOpen] = React.useState(false);
  const [deleteColumnOpen, setDeleteColumnOpen] = React.useState(false);
  const [selectedColumn, setSelectedColumn] = React.useState<DatasetColumnResponse | null>(null);
  const [localColumns, setLocalColumns] = React.useState<DatasetColumnResponse[] | null>(null);
  const [expandedColumnId, setExpandedColumnId] = React.useState<number | null>(null);
  const [editingColumnId, setEditingColumnId] = React.useState<number | null>(null);

  const hasData = dataset.rowCount > 0;
  const deleteColumn = useDeleteColumn(datasetId);
  const reorderColumns = useReorderColumns(datasetId);

  // Reset local state when dataset.columns changes
  React.useEffect(() => {
    setLocalColumns(null);
  }, [dataset.columns]);

  const moveColumn = useCallback(
    async (index: number, direction: 'up' | 'down') => {
      const currentColumns = localColumns ?? dataset.columns;
      const newIndex = direction === 'up' ? index - 1 : index + 1;
      if (newIndex < 0 || newIndex >= currentColumns.length) return;

      const reordered = [...currentColumns];
      [reordered[index], reordered[newIndex]] = [reordered[newIndex], reordered[index]];
      setLocalColumns(reordered);

      try {
        await reorderColumns.mutateAsync(reordered.map((c) => c.id));
      } catch (error) {
        setLocalColumns(null);
        handleApiError(error, '필드 순서 변경에 실패했습니다.');
      }
    },
    [localColumns, dataset.columns, reorderColumns]
  );

  const deleteColumnHandler = useCallback(async () => {
    if (!selectedColumn) return;
    try {
      await deleteColumn.mutateAsync(selectedColumn.id);
      toast.success('필드가 삭제되었습니다.');
      setDeleteColumnOpen(false);
      setSelectedColumn(null);
    } catch (error) {
      handleApiError(error, '필드 삭제에 실패했습니다.');
    }
  }, [selectedColumn, deleteColumn]);

  const editClick = useCallback((col: DatasetColumnResponse) => {
    setSelectedColumn(col);
    setEditColumnOpen(true);
  }, []);

  const deleteClick = useCallback((col: DatasetColumnResponse) => {
    setSelectedColumn(col);
    setDeleteColumnOpen(true);
  }, []);

  const toggleExpand = useCallback(
    (colId: number) => {
      if (!hasData) return;
      setExpandedColumnId((prev) => (prev === colId ? null : colId));
    },
    [hasData]
  );

  return {
    localColumns: localColumns ?? dataset.columns,
    expandedColumnId,
    selectedColumn,
    editingColumnId,
    addColumnOpen,
    setAddColumnOpen,
    editColumnOpen,
    setEditColumnOpen,
    deleteColumnOpen,
    setDeleteColumnOpen,
    setEditingColumnId,
    isReorderPending: reorderColumns.isPending,
    handlers: {
      moveColumn,
      deleteColumn: deleteColumnHandler,
      editClick,
      deleteClick,
      toggleExpand,
    },
  };
}
