import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { KeyRound } from 'lucide-react';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { toast } from 'sonner';

import { datasetsApi } from '../../../api/datasets';
import { Button } from '../../../components/ui/button';
import { Checkbox } from '../../../components/ui/checkbox';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '../../../components/ui/dialog';
import { Input } from '../../../components/ui/input';
import { Label } from '../../../components/ui/label';
import { useAddColumn } from '../../../hooks/queries/useDatasets';
import { handleApiError } from '../../../lib/api-error';
import type { AddColumnFormData, UpdateColumnFormData } from '../../../lib/validations/dataset';
import { addColumnSchema, updateColumnSchema } from '../../../lib/validations/dataset';
import type { DatasetColumnResponse } from '../../../types/dataset';
import { ColumnTypeSelect } from './ColumnTypeSelect';

interface ColumnDialogProps {
  mode: 'add' | 'edit';
  datasetId: number;
  column?: DatasetColumnResponse | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  hasData?: boolean;
}

const DEFAULT_VALUES: AddColumnFormData = {
  columnName: '',
  displayName: '',
  dataType: 'TEXT',
  maxLength: undefined,
  isNullable: true,
  isIndexed: false,
  isPrimaryKey: false,
  description: '',
};

export function ColumnDialog({
  mode,
  datasetId,
  column,
  open,
  onOpenChange,
  hasData = false,
}: ColumnDialogProps) {
  const title = mode === 'add' ? '필드 추가' : '필드 수정';
  const submitLabel = mode === 'add' ? '추가' : '수정';
  const submittingLabel = mode === 'add' ? '추가 중...' : '수정 중...';

  const queryClient = useQueryClient();
  const addColumn = useAddColumn(datasetId);

  const schema = mode === 'add' ? addColumnSchema : updateColumnSchema;

  const form = useForm<AddColumnFormData>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(schema) as any,
    defaultValues: DEFAULT_VALUES,
  });

  // Populate form when editing an existing column
  useEffect(() => {
    if (mode === 'edit' && column && open) {
      form.reset({
        columnName: column.columnName,
        displayName: column.displayName || '',
        dataType: column.dataType as AddColumnFormData['dataType'],
        maxLength: column.maxLength ?? undefined,
        isNullable: column.isNullable,
        isIndexed: column.isIndexed,
        isPrimaryKey: column.isPrimaryKey,
        description: column.description || '',
      });
    } else if (mode === 'add' && !open) {
      form.reset(DEFAULT_VALUES);
    }
  }, [column, open, mode, form]);

  const onSubmit = async (data: AddColumnFormData | UpdateColumnFormData) => {
    try {
      if (mode === 'add') {
        await addColumn.mutateAsync({
          columnName: data.columnName,
          displayName: data.displayName || undefined,
          dataType: data.dataType,
          maxLength: data.maxLength || undefined,
          isNullable: data.isNullable,
          isIndexed: data.isIndexed,
          isPrimaryKey: data.isPrimaryKey ?? false,
          description: data.description || undefined,
        });
        toast.success('필드가 추가되었습니다.');
        form.reset(DEFAULT_VALUES);
      } else {
        if (!column) return;
        await datasetsApi.updateColumn(datasetId, column.id, {
          columnName: data.columnName,
          displayName: data.displayName || undefined,
          dataType: data.dataType,
          maxLength: data.maxLength ?? undefined,
          isNullable: data.isNullable,
          isIndexed: data.isIndexed,
          isPrimaryKey: data.isPrimaryKey,
          description: data.description || undefined,
        });
        queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
        toast.success('필드가 수정되었습니다.');
      }
      onOpenChange(false);
    } catch (error) {
      handleApiError(error, mode === 'add' ? '필드 추가에 실패했습니다.' : '필드 수정에 실패했습니다.');
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
          {mode === 'edit' && hasData && (
            <div className="rounded-md bg-amber-50 border border-amber-200 p-3">
              <p className="text-sm text-amber-800">
                데이터가 있는 경우 필드명, 데이터 타입, 길이, NULL 허용 여부는 변경할 수 없습니다.
              </p>
            </div>
          )}

          <div className="space-y-2">
            <Label htmlFor="columnName">필드명 *</Label>
            <Input
              id="columnName"
              {...form.register('columnName')}
              placeholder="예: user_id"
              disabled={mode === 'edit' && hasData}
            />
            {form.formState.errors.columnName && (
              <p className="text-sm text-destructive">
                {form.formState.errors.columnName.message}
              </p>
            )}
          </div>

          <div className="space-y-2">
            <Label htmlFor="displayName">표시명</Label>
            <Input
              id="displayName"
              {...form.register('displayName')}
              placeholder="예: 사용자 ID"
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="dataType">데이터 타입 *</Label>
            <ColumnTypeSelect
              value={form.watch('dataType')}
              onChange={(value) => form.setValue('dataType', value as AddColumnFormData['dataType'])}
              disabled={mode === 'edit' && hasData}
            />
            {form.formState.errors.dataType && (
              <p className="text-sm text-destructive">
                {form.formState.errors.dataType.message}
              </p>
            )}
          </div>

          {form.watch('dataType') === 'VARCHAR' && (
            <div className="space-y-2">
              <Label htmlFor="maxLength">최대 길이 *</Label>
              <Input
                id="maxLength"
                type="number"
                min={1}
                max={10000}
                {...form.register('maxLength', { valueAsNumber: true })}
                placeholder="예: 255"
                disabled={mode === 'edit' && hasData}
              />
              {form.formState.errors.maxLength && (
                <p className="text-sm text-destructive">
                  {form.formState.errors.maxLength.message}
                </p>
              )}
            </div>
          )}

          <div className="flex items-center space-x-2">
            <Checkbox
              id="isPrimaryKey"
              checked={form.watch('isPrimaryKey') ?? false}
              onCheckedChange={(checked) => {
                form.setValue('isPrimaryKey', checked as boolean);
                if (checked) {
                  form.setValue('isNullable', false);
                }
              }}
              disabled={mode === 'edit' && hasData}
            />
            <Label htmlFor="isPrimaryKey" className="text-sm font-normal cursor-pointer flex items-center gap-1">
              <KeyRound className="h-3 w-3" />
              기본 키
            </Label>
          </div>

          <div className="flex items-center space-x-2">
            <Checkbox
              id="isNullable"
              checked={form.watch('isNullable')}
              onCheckedChange={(checked) =>
                form.setValue('isNullable', checked as boolean)
              }
              disabled={(mode === 'edit' && hasData) || (form.watch('isPrimaryKey') ?? false)}
            />
            <Label htmlFor="isNullable" className="text-sm font-normal cursor-pointer">
              NULL 허용
            </Label>
          </div>

          <div className="flex items-center space-x-2">
            <Checkbox
              id="isIndexed"
              checked={form.watch('isIndexed')}
              onCheckedChange={(checked) =>
                form.setValue('isIndexed', checked as boolean)
              }
            />
            <Label htmlFor="isIndexed" className="text-sm font-normal cursor-pointer">
              인덱스 생성
            </Label>
          </div>

          <div className="space-y-2">
            <Label htmlFor="description">설명</Label>
            <Input
              id="description"
              {...form.register('description')}
              placeholder="필드 설명"
            />
          </div>

          <div className="flex gap-2">
            <Button type="submit" className="flex-1" disabled={form.formState.isSubmitting}>
              {form.formState.isSubmitting ? submittingLabel : submitLabel}
            </Button>
            {mode === 'edit' && (
              <Button
                type="button"
                variant="outline"
                onClick={() => onOpenChange(false)}
              >
                취소
              </Button>
            )}
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
