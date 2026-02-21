import { useMemo } from 'react';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useAddRow } from '../../../hooks/queries/useDatasets';
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

interface AddRowDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  datasetId: number;
  columns: DatasetColumnResponse[];
}

function buildZodSchema(columns: DatasetColumnResponse[]) {
  const shape: Record<string, z.ZodTypeAny> = {};
  for (const col of columns) {
    if (col.isPrimaryKey) continue; // Skip auto-generated PK
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

export function AddRowDialog({ open, onOpenChange, datasetId, columns }: AddRowDialogProps) {
  const editableColumns = useMemo(() => columns.filter((c) => !c.isPrimaryKey), [columns]);
  const schema = useMemo(() => buildZodSchema(columns), [columns]);

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
    // Clean empty strings to null for nullable fields
    const cleaned: Record<string, unknown> = {};
    for (const col of editableColumns) {
      const val = data[col.columnName];
      if (val === '' || val === undefined) {
        if (col.isNullable) cleaned[col.columnName] = null;
        // If not nullable, validation should have caught it
      } else {
        cleaned[col.columnName] = val;
      }
    }
    try {
      await addRow.mutateAsync(cleaned);
      toast.success('행이 추가되었습니다.');
      onOpenChange(false);
      form.reset(defaultValues);
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '행 추가에 실패했습니다.');
      } else {
        toast.error('행 추가에 실패했습니다.');
      }
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[80vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>행 추가</DialogTitle>
        </DialogHeader>
        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
          {editableColumns.map((col) => {
            const label = col.displayName || col.columnName;
            const fieldError = form.formState.errors[col.columnName];

            return (
              <div key={col.columnName} className="space-y-1.5">
                <Label htmlFor={`add-${col.columnName}`}>
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
                          id={`add-${col.columnName}`}
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
                    id={`add-${col.columnName}`}
                    type="date"
                    {...form.register(col.columnName)}
                  />
                ) : col.dataType === 'TIMESTAMP' ? (
                  <Input
                    id={`add-${col.columnName}`}
                    type="datetime-local"
                    {...form.register(col.columnName)}
                  />
                ) : col.dataType === 'INTEGER' ? (
                  <Input
                    id={`add-${col.columnName}`}
                    type="number"
                    step="1"
                    {...form.register(col.columnName)}
                  />
                ) : col.dataType === 'DECIMAL' ? (
                  <Input
                    id={`add-${col.columnName}`}
                    type="number"
                    step="any"
                    {...form.register(col.columnName)}
                  />
                ) : (
                  <Input
                    id={`add-${col.columnName}`}
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
