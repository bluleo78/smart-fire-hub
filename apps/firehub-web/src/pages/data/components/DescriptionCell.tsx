import { Loader2,Pencil } from 'lucide-react';
import React, { useCallback } from 'react';
import { toast } from 'sonner';

import { Input } from '../../../components/ui/input';
import { useUpdateColumn } from '../../../hooks/queries/useDatasets';
import { handleApiError } from '../../../lib/api-error';
import type { DatasetColumnResponse } from '../../../types/dataset';

interface DescriptionCellProps {
  col: DatasetColumnResponse;
  datasetId: number;
  isEditing: boolean;
  onStartEdit: () => void;
  onEndEdit: () => void;
}

export function DescriptionCell({
  col,
  datasetId,
  isEditing,
  onStartEdit,
  onEndEdit,
}: DescriptionCellProps) {
  const [value, setValue] = React.useState(col.description ?? '');
  const [saving, setSaving] = React.useState(false);
  const updateColumn = useUpdateColumn(datasetId, col.id);

  React.useEffect(() => {
    if (!isEditing) {
      setValue(col.description ?? '');
    }
  }, [col.description, isEditing]);

  const handleSave = useCallback(async () => {
    setSaving(true);
    try {
      await updateColumn.mutateAsync({ description: value });
      toast.success('설명이 저장되었습니다');
      onEndEdit();
    } catch (error) {
      handleApiError(error, '설명 저장에 실패했습니다.');
      setValue(col.description ?? '');
      onEndEdit();
    } finally {
      setSaving(false);
    }
  }, [value, col.description, updateColumn, onEndEdit]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLInputElement>) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        handleSave();
      } else if (e.key === 'Escape') {
        setValue(col.description ?? '');
        onEndEdit();
      }
    },
    [handleSave, col.description, onEndEdit]
  );

  if (isEditing) {
    return (
      <div className="flex items-center gap-1">
        <Input
          className="h-7 text-sm py-0 px-2"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={handleKeyDown}
          disabled={saving}
          autoFocus
        />
        {saving && <Loader2 className="animate-spin shrink-0" size={14} />}
      </div>
    );
  }

  return (
    <div
      className="group flex items-center gap-1 cursor-pointer max-w-xs"
      onClick={onStartEdit}
    >
      <span className="truncate text-sm">
        {col.description || <span className="text-muted-foreground">-</span>}
      </span>
      <Pencil
        size={14}
        className="shrink-0 text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity"
      />
    </div>
  );
}
