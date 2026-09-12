import { zodResolver } from '@hookform/resolvers/zod';
import { Loader2 } from 'lucide-react';
import { useEffect,useMemo } from 'react';
import { useForm } from 'react-hook-form';
import { toast } from 'sonner';

import { Button } from '../../../components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '../../../components/ui/dialog';
import { Input } from '../../../components/ui/input';
import { Label } from '../../../components/ui/label';
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

// (#670) BOOLEAN 값이 NULL인 경우, NULL 허용 컬럼이면 NULL을 그대로 보존해야 한다.
// 과거에는 무조건 false로 변환해 "값 없음"과 "거짓"을 구분할 수 없게 만들었다.
function toFormValue(value: unknown, dataType: string, isNullable: boolean): unknown {
  if (value === null || value === undefined) {
    if (dataType === 'BOOLEAN') return isNullable ? null : false;
    return '';
  }
  if (dataType === 'BOOLEAN') return value === true || value === 'true';
  return String(value);
}

export function EditRowDialog({ open, onOpenChange, datasetId, columns, rowId, initialData }: EditRowDialogProps) {
  const editableColumns = useMemo(() => columns.filter((c) => !c.isPrimaryKey), [columns]);
  // (#672) 사용자 정의 PK(NOT NULL) 컬럼 — 편집 폼에서는 읽기 전용으로만 노출한다.
  const primaryKeyColumns = useMemo(() => columns.filter((c) => c.isPrimaryKey), [columns]);
  const schema = useMemo(() => buildRowZodSchema(columns), [columns]);

  const defaultValues = useMemo(() => {
    const vals: Record<string, unknown> = {};
    for (const col of editableColumns) {
      vals[col.columnName] = toFormValue(initialData[col.columnName], col.dataType, col.isNullable);
    }
    // (#672) PK 컬럼은 입력 필드로 렌더링하지 않지만, 기존 값을 폼 상태에 그대로 실어 두어
    // 저장 시 PUT 페이로드에 값이 유지되도록 한다(백엔드 부분 업데이트 병합의 보조 안전장치).
    // 표시용 문자열이 아니라 원본 값(숫자/불리언 등)을 그대로 사용해야 백엔드 타입 검증을 통과한다.
    for (const col of primaryKeyColumns) {
      vals[col.columnName] = initialData[col.columnName];
    }
    return vals;
  }, [editableColumns, primaryKeyColumns, initialData]);

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
      const initial = toFormValue(initialData[col.columnName], col.dataType, col.isNullable);
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
      <DialogContent className="max-h-[80vh] overflow-y-auto overscroll-contain">
        <DialogHeader>
          <DialogTitle>행 편집 (ID: {rowId})</DialogTitle>
          <DialogDescription className="sr-only">선택한 행의 데이터를 편집합니다.</DialogDescription>
        </DialogHeader>
        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
          {/* (#672) PK 컬럼은 수정 불가 — 읽기 전용으로 값만 보여준다. 폼 상태(defaultValues)에는
              값이 유지되어 있어 저장 시 페이로드에서 값이 사라지지 않는다. */}
          {primaryKeyColumns.map((col) => {
            const label = col.displayName || col.columnName;
            const rawValue = initialData[col.columnName];
            const displayValue = rawValue === null || rawValue === undefined ? '' : String(rawValue);
            return (
              <div key={col.columnName} className="space-y-1.5">
                <Label htmlFor={`edit-pk-${col.columnName}`}>
                  {label}
                  <span className="text-muted-foreground text-xs ml-1">(기본 키, 수정 불가)</span>
                </Label>
                <Input id={`edit-pk-${col.columnName}`} type="text" value={displayValue} disabled readOnly />
              </div>
            );
          })}

          <RowFormFields columns={columns} form={form} idPrefix="edit" changedFields={changedFields} />

          <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
            {form.formState.isSubmitting ? (
              <>
                <Loader2 className="h-4 w-4 animate-spin" />
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
