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
import {
  buildRowZodSchema,
  cleanFormValues,
  computeChangedFields,
  pickChangedValues,
  toTemporalFormValue,
} from './row-form-utils';
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
  // (#772) DATE/TIMESTAMP 는 입력란 형식으로 변환한다(표현 불가 값은 원문 그대로 — 텍스트 입력으로 렌더링)
  if (dataType === 'DATE' || dataType === 'TIMESTAMP') return toTemporalFormValue(String(value), dataType).value;
  return String(value);
}

export function EditRowDialog({ open, onOpenChange, datasetId, columns, rowId, initialData }: EditRowDialogProps) {
  const editableColumns = useMemo(() => columns.filter((c) => !c.isPrimaryKey), [columns]);
  // (#672) 사용자 정의 PK(NOT NULL) 컬럼 — 편집 폼에서는 읽기 전용으로만 노출한다.
  const primaryKeyColumns = useMemo(() => columns.filter((c) => c.isPrimaryKey), [columns]);
  const schema = useMemo(() => buildRowZodSchema(columns), [columns]);

  // (#672·#772) PK 컬럼은 폼 상태에 싣지 않는다 — 저장은 바뀐 칸만 보내고, 서버 부분 업데이트가
  // 요청에 없는 컬럼(PK 포함)의 DB 값을 그대로 유지한다.
  const defaultValues = useMemo(() => {
    const vals: Record<string, unknown> = {};
    for (const col of editableColumns) {
      vals[col.columnName] = toFormValue(initialData[col.columnName], col.dataType, col.isNullable);
    }
    return vals;
  }, [editableColumns, initialData]);

  // (#772) date/datetime-local 입력이 담을 수 없는 기존 값(±infinity·BC·5자리 연도 등)을 가진 컬럼 —
  // 원문 텍스트 입력으로 렌더링해 빈칸으로 보이거나 빈칸이 NULL 로 저장되는 일을 막는다.
  const rawTemporalColumns = useMemo(() => {
    const set = new Set<string>();
    for (const col of editableColumns) {
      const v = initialData[col.columnName];
      if ((col.dataType === 'DATE' || col.dataType === 'TIMESTAMP') && v !== null && v !== undefined) {
        if (toTemporalFormValue(String(v), col.dataType).raw) set.add(col.columnName);
      }
    }
    return set;
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
  const changedFields = useMemo(
    () => computeChangedFields(editableColumns, defaultValues, watchedValues),
    [editableColumns, defaultValues, watchedValues],
  );

  const onSubmit = async (data: Record<string, unknown>) => {
    // (#772) 사용자가 실제로 바꾼 칸만 전송한다. 판정은 zod 변환 전 원시 폼 값으로 한다
    // (zod 결과는 숫자 coerce 등으로 모양이 달라 기본값과 비교할 수 없다).
    const changed = computeChangedFields(editableColumns, defaultValues, form.getValues());
    const payload = pickChangedValues(cleanFormValues(data, columns), changed);
    try {
      await updateRow.mutateAsync({ rowId, data: payload });
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
          {/* (#672) PK 컬럼은 수정 불가 — 읽기 전용으로 값만 보여준다. 페이로드에 싣지 않아도
              서버 부분 업데이트가 DB 값을 그대로 유지한다(#772). */}
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

          <RowFormFields
            columns={columns}
            form={form}
            idPrefix="edit"
            changedFields={changedFields}
            rawTemporalColumns={rawTemporalColumns}
          />

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
