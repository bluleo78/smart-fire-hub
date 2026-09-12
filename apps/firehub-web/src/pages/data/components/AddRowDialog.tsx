import { zodResolver } from '@hookform/resolvers/zod';
import { Loader2 } from 'lucide-react';
import { useMemo } from 'react';
import { useForm } from 'react-hook-form';
import { toast } from 'sonner';

import { Button } from '../../../components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '../../../components/ui/dialog';
import { useAddRow } from '../../../hooks/queries/useDatasets';
import { handleApiError } from '../../../lib/api-error';
import type { DatasetColumnResponse } from '../../../types/dataset';
import { buildRowZodSchema, cleanFormValues } from './row-form-utils';
import { RowFormFields } from './RowFormFields';

interface AddRowDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  datasetId: number;
  columns: DatasetColumnResponse[];
}

export function AddRowDialog({ open, onOpenChange, datasetId, columns }: AddRowDialogProps) {
  // (#673) 이 앱의 테이블 생성 폼에는 auto-increment 옵션이 없어 PK는 항상 사용자가 정의한
  // 일반 컬럼이고 값도 직접 입력해야 한다. 그래서 "행 추가"에서는 PK를 숨기지 않고 다른
  // 필수 컬럼과 동일하게 입력 필드로 노출한다(반대로 "행 수정"에서는 PK가 불변이라 읽기 전용).
  const schema = useMemo(() => buildRowZodSchema(columns, 'add'), [columns]);

  const defaultValues = useMemo(() => {
    const vals: Record<string, unknown> = {};
    for (const col of columns) {
      // (#670) NULL 허용 BOOLEAN 컬럼은 NULL로 초기화해야 애초에 NULL 값으로 행을 생성할 수 있다.
      if (col.dataType === 'BOOLEAN') vals[col.columnName] = col.isNullable ? null : false;
      else vals[col.columnName] = '';
    }
    return vals;
  }, [columns]);

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
      <DialogContent className="max-h-[80vh] overflow-y-auto overscroll-contain">
        <DialogHeader>
          <DialogTitle>행 추가</DialogTitle>
          <DialogDescription className="sr-only">데이터셋에 새 행을 추가합니다.</DialogDescription>
        </DialogHeader>
        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
          <RowFormFields columns={columns} form={form} idPrefix="add" mode="add" />

          <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
            {form.formState.isSubmitting ? (
              <>
                <Loader2 className="h-4 w-4 animate-spin" />
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
