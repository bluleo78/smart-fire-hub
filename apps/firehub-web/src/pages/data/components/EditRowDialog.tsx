import { useMemo, useEffect } from 'react';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useUpdateRow } from '../../../hooks/queries/useDatasets';
import type { DatasetColumnResponse } from '../../../types/dataset';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '../../../components/ui/dialog';
import { Button } from '../../../components/ui/button';
import { Input } from '../../../components/ui/input';
import { Label } from '../../../components/ui/label';
import { Switch } from '../../../components/ui/switch';
import { Loader2 } from 'lucide-react';
import { toast } from 'sonner';
import axios from 'axios';
import type { ErrorResponse } from '../../../types/auth';

interface EditRowDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  datasetId: number;
  columns: DatasetColumnResponse[];
  rowId: number;
  initialData: Record<string, unknown>;
}

function buildZodSchema(columns: DatasetColumnResponse[]) {
  const shape: Record<string, z.ZodTypeAny> = {};
  for (const col of columns) {
    if (col.isPrimaryKey) continue;
    let field: z.ZodTypeAny;
    switch (col.dataType) {
      case 'INTEGER':
        field = z.coerce.number({ error: '숫자를 입력하세요.' }).int({ error: '정수를 입력하세요.' });
        if (col.isNullable) field = field.optional().or(z.literal('').transform(() => undefined));
        break;
      case 'DECIMAL':
        field = z.coerce.number({ error: '숫자를 입력하세요.' });
        if (col.isNullable) field = field.optional().or(z.literal('').transform(() => undefined));
        break;
      case 'BOOLEAN':
        field = z.boolean();
        if (col.isNullable) field = field.optional();
        break;
      case 'VARCHAR':
        field = z.string();
        if (col.maxLength) field = (field as z.ZodString).max(col.maxLength, `최대 ${col.maxLength}자`);
        if (col.isNullable) field = field.optional().or(z.literal(''));
        else field = (field as z.ZodString).min(1, '필수 입력 항목입니다.');
        break;
      case 'DATE':
      case 'TIMESTAMP':
      case 'TEXT':
      default:
        field = z.string();
        if (col.isNullable) field = field.optional().or(z.literal(''));
        else field = (field as z.ZodString).min(1, '필수 입력 항목입니다.');
        break;
    }
    shape[col.columnName] = field;
  }
  return z.object(shape);
}

function toFormValue(value: unknown, dataType: string): unknown {
  if (value === null || value === undefined) return dataType === 'BOOLEAN' ? false : '';
  if (dataType === 'BOOLEAN') return value === true || value === 'true';
  return String(value);
}

export function EditRowDialog({ open, onOpenChange, datasetId, columns, rowId, initialData }: EditRowDialogProps) {
  const editableColumns = useMemo(() => columns.filter((c) => !c.isPrimaryKey), [columns]);
  const schema = useMemo(() => buildZodSchema(columns), [columns]);

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
    const cleaned: Record<string, unknown> = {};
    for (const col of editableColumns) {
      const val = data[col.columnName];
      if (val === '' || val === undefined) {
        if (col.isNullable) cleaned[col.columnName] = null;
      } else {
        cleaned[col.columnName] = val;
      }
    }
    try {
      await updateRow.mutateAsync({ rowId, data: cleaned });
      toast.success('행이 수정되었습니다.');
      onOpenChange(false);
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '행 수정에 실패했습니다.');
      } else {
        toast.error('행 수정에 실패했습니다.');
      }
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[80vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>행 편집 (ID: {rowId})</DialogTitle>
        </DialogHeader>
        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
          {editableColumns.map((col) => {
            const label = col.displayName || col.columnName;
            const fieldError = form.formState.errors[col.columnName];
            const isChanged = changedFields.has(col.columnName);

            return (
              <div
                key={col.columnName}
                className="space-y-1.5"
                style={isChanged ? { borderLeft: '3px solid hsl(var(--primary))', paddingLeft: '8px' } : undefined}
              >
                <Label htmlFor={`edit-${col.columnName}`}>
                  {label}
                  {!col.isNullable ? (
                    <span className="text-destructive ml-0.5">*</span>
                  ) : (
                    <span className="text-muted-foreground text-xs ml-1">(선택)</span>
                  )}
                </Label>

                {col.dataType === 'BOOLEAN' ? (
                  <Controller
                    name={col.columnName}
                    control={form.control}
                    render={({ field }) => (
                      <div className="flex items-center gap-2">
                        <Switch
                          id={`edit-${col.columnName}`}
                          checked={!!field.value}
                          onCheckedChange={field.onChange}
                        />
                        <span className="text-sm text-muted-foreground">
                          {field.value ? 'true' : 'false'}
                        </span>
                      </div>
                    )}
                  />
                ) : col.dataType === 'DATE' ? (
                  <Input
                    id={`edit-${col.columnName}`}
                    type="date"
                    {...form.register(col.columnName)}
                  />
                ) : col.dataType === 'TIMESTAMP' ? (
                  <Input
                    id={`edit-${col.columnName}`}
                    type="datetime-local"
                    {...form.register(col.columnName)}
                  />
                ) : col.dataType === 'INTEGER' ? (
                  <Input
                    id={`edit-${col.columnName}`}
                    type="number"
                    step="1"
                    {...form.register(col.columnName)}
                  />
                ) : col.dataType === 'DECIMAL' ? (
                  <Input
                    id={`edit-${col.columnName}`}
                    type="number"
                    step="any"
                    {...form.register(col.columnName)}
                  />
                ) : (
                  <Input
                    id={`edit-${col.columnName}`}
                    type="text"
                    maxLength={col.dataType === 'VARCHAR' && col.maxLength ? col.maxLength : undefined}
                    {...form.register(col.columnName)}
                  />
                )}

                {fieldError && (
                  <p className="text-sm text-destructive">{fieldError.message as string}</p>
                )}
              </div>
            );
          })}

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
