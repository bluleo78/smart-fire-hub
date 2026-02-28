import { zodResolver } from '@hookform/resolvers/zod';
import { Loader2 } from 'lucide-react';
import { useEffect,useMemo } from 'react';
import { useForm } from 'react-hook-form';
import { toast } from 'sonner';

import { Button } from '../../../components/ui/button';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '../../../components/ui/dialog';
import { useUpdateRow } from '../../../hooks/queries/useDatasets';
import { handleApiError } from '../../../lib/api-error';
import type { DatasetColumnResponse } from '../../../types/dataset';
import { buildRowZodSchema, cleanFormValues } from './row-form-utils';
import { RowFormFields } from './RowFormFields';

interface EditRowDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  datasetId: number;
  columns: DatasetColumnResponse[];
  rowId: number;
  initialData: Record<string, unknown>;
}

function toFormValue(value: unknown, dataType: string): unknown {
  if (value === null || value === undefined) return dataType === 'BOOLEAN' ? false : '';
  if (dataType === 'BOOLEAN') return value === true || value === 'true';
  return String(value);
}

export function EditRowDialog({ open, onOpenChange, datasetId, columns, rowId, initialData }: EditRowDialogProps) {
  const editableColumns = useMemo(() => columns.filter((c) => !c.isPrimaryKey), [columns]);
  const schema = useMemo(() => buildRowZodSchema(columns), [columns]);

  const defaultValues = useMemo(() => {
    const vals: Record<string, unknown> = {};
    for (const col of editableColumns) {
      vals[col.columnName] = toFormValue(initialData[col.columnName], col.dataType);
    }
    return vals;
  }, [editableColumns, initialData]);

  const form = useForm({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(schema) as any,
    defaultValues,
  });

  // Reset form when initialData changes (different row selected)
  useEffect(() => {
    form.reset(defaultValues);
  }, [rowId, defaultValues, form]);

  const updateRow = useUpdateRow(datasetId);

  // Track which fields have been changed
  const watchedValues = form.watch();
  const changedFields = useMemo(() => {
    const changed = new Set<string>();
    for (const col of editableColumns) {
      const initial = toFormValue(initialData[col.columnName], col.dataType);
      const current = watchedValues[col.columnName];
      if (String(initial) !== String(current)) {
        changed.add(col.columnName);
      }
    }
    return changed;
  }, [editableColumns, initialData, watchedValues]);

  const onSubmit = async (data: Record<string, unknown>) => {
    const cleaned = cleanFormValues(data, columns);
    try {
      await updateRow.mutateAsync({ rowId, data: cleaned });
      toast.success('행이 수정되었습니다.');
      onOpenChange(false);
    } catch (error) {
      handleApiError(error, '행 수정에 실패했습니다.');
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[80vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>행 편집 (ID: {rowId})</DialogTitle>
        </DialogHeader>
        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
          <RowFormFields columns={columns} form={form} idPrefix="edit" changedFields={changedFields} />

          <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
            {form.formState.isSubmitting ? (
              <>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                저장 중...
              </>
            ) : (
              '저장'
            )}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}
