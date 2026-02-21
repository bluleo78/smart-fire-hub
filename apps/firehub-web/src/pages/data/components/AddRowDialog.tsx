import { useMemo } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useAddRow } from '../../../hooks/queries/useDatasets';
import type { DatasetColumnResponse } from '../../../types/dataset';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '../../../components/ui/dialog';
import { Button } from '../../../components/ui/button';
import { Loader2 } from 'lucide-react';
import { toast } from 'sonner';
import { handleApiError } from '../../../lib/api-error';
import { buildRowZodSchema, cleanFormValues } from './row-form-utils';
import { RowFormFields } from './RowFormFields';

interface AddRowDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  datasetId: number;
  columns: DatasetColumnResponse[];
}

export function AddRowDialog({ open, onOpenChange, datasetId, columns }: AddRowDialogProps) {
  const editableColumns = useMemo(() => columns.filter((c) => !c.isPrimaryKey), [columns]);
  const schema = useMemo(() => buildRowZodSchema(columns), [columns]);

  const defaultValues = useMemo(() => {
    const vals: Record<string, unknown> = {};
    for (const col of editableColumns) {
      if (col.dataType === 'BOOLEAN') vals[col.columnName] = false;
      else vals[col.columnName] = '';
    }
    return vals;
  }, [editableColumns]);

  const form = useForm({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(schema) as any,
    defaultValues,
  });

  const addRow = useAddRow(datasetId);

  const onSubmit = async (data: Record<string, unknown>) => {
    const cleaned = cleanFormValues(data, columns);
    try {
      await addRow.mutateAsync(cleaned);
      toast.success('행이 추가되었습니다.');
      onOpenChange(false);
      form.reset(defaultValues);
    } catch (error) {
      handleApiError(error, '행 추가에 실패했습니다.');
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[80vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>행 추가</DialogTitle>
        </DialogHeader>
        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
          <RowFormFields columns={columns} form={form} idPrefix="add" />

          <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
            {form.formState.isSubmitting ? (
              <>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                추가 중...
              </>
            ) : (
              '추가'
            )}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}
