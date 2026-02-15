import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { datasetsApi } from '../../api/datasets';
import { updateColumnSchema } from '../../lib/validations/dataset';
import type { UpdateColumnFormData } from '../../lib/validations/dataset';
import type { DatasetColumnResponse } from '../../types/dataset';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Label } from '../ui/label';
import { Checkbox } from '../ui/checkbox';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '../ui/dialog';
import { ColumnTypeSelect } from './ColumnTypeSelect';
import { toast } from 'sonner';
import type { ErrorResponse } from '../../types/auth';
import axios from 'axios';

interface EditColumnDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  datasetId: number;
  column: DatasetColumnResponse | null;
  hasData: boolean;
}

export function EditColumnDialog({ open, onOpenChange, datasetId, column, hasData }: EditColumnDialogProps) {
  const queryClient = useQueryClient();

  const editForm = useForm<UpdateColumnFormData>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(updateColumnSchema) as any,
    defaultValues: {
      columnName: '',
      displayName: '',
      dataType: 'TEXT',
      maxLength: undefined,
      isNullable: true,
      isIndexed: false,
      description: '',
    },
  });

  useEffect(() => {
    if (column && open) {
      editForm.reset({
        columnName: column.columnName,
        displayName: column.displayName || '',
        dataType: column.dataType as UpdateColumnFormData['dataType'],
        maxLength: column.maxLength,
        isNullable: column.isNullable,
        isIndexed: column.isIndexed,
        description: column.description || '',
      });
    }
  }, [column, open, editForm]);

  const onSubmit = async (data: UpdateColumnFormData) => {
    if (!column) return;

    try {
      await datasetsApi.updateColumn(datasetId, column.id, {
        columnName: data.columnName,
        displayName: data.displayName || undefined,
        dataType: data.dataType,
        maxLength: data.maxLength,
        isNullable: data.isNullable,
        isIndexed: data.isIndexed,
        description: data.description || undefined,
      });
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
      toast.success('필드가 수정되었습니다.');
      onOpenChange(false);
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '필드 수정에 실패했습니다.');
      } else {
        toast.error('필드 수정에 실패했습니다.');
      }
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>필드 수정</DialogTitle>
        </DialogHeader>
        <form onSubmit={editForm.handleSubmit(onSubmit)} className="space-y-4">
          {hasData && (
            <div className="rounded-md bg-amber-50 border border-amber-200 p-3">
              <p className="text-sm text-amber-800">
                데이터가 있는 경우 필드명, 데이터 타입, 길이, NULL 허용 여부는 변경할 수 없습니다.
              </p>
            </div>
          )}

          <div className="space-y-2">
            <Label htmlFor="edit-columnName">필드명 *</Label>
            <Input
              id="edit-columnName"
              {...editForm.register('columnName')}
              placeholder="예: user_id"
              disabled={hasData}
            />
            {editForm.formState.errors.columnName && (
              <p className="text-sm text-destructive">
                {editForm.formState.errors.columnName.message}
              </p>
            )}
          </div>

          <div className="space-y-2">
            <Label htmlFor="edit-displayName">표시명</Label>
            <Input
              id="edit-displayName"
              {...editForm.register('displayName')}
              placeholder="예: 사용자 ID"
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="edit-dataType">데이터 타입 *</Label>
            <ColumnTypeSelect
              value={editForm.watch('dataType')}
              onChange={(value) => editForm.setValue('dataType', value as UpdateColumnFormData['dataType'])}
              disabled={hasData}
            />
            {editForm.formState.errors.dataType && (
              <p className="text-sm text-destructive">
                {editForm.formState.errors.dataType.message}
              </p>
            )}
          </div>

          {editForm.watch('dataType') === 'VARCHAR' && (
            <div className="space-y-2">
              <Label htmlFor="edit-maxLength">최대 길이 *</Label>
              <Input
                id="edit-maxLength"
                type="number"
                min={1}
                max={10000}
                {...editForm.register('maxLength', { valueAsNumber: true })}
                placeholder="예: 255"
                disabled={hasData}
              />
              {editForm.formState.errors.maxLength && (
                <p className="text-sm text-destructive">
                  {editForm.formState.errors.maxLength.message}
                </p>
              )}
            </div>
          )}

          <div className="flex items-center space-x-2">
            <Checkbox
              id="edit-isNullable"
              checked={editForm.watch('isNullable')}
              onCheckedChange={(checked) =>
                editForm.setValue('isNullable', checked as boolean)
              }
              disabled={hasData}
            />
            <Label htmlFor="edit-isNullable" className="text-sm font-normal cursor-pointer">
              NULL 허용
            </Label>
          </div>

          <div className="flex items-center space-x-2">
            <Checkbox
              id="edit-isIndexed"
              checked={editForm.watch('isIndexed')}
              onCheckedChange={(checked) =>
                editForm.setValue('isIndexed', checked as boolean)
              }
            />
            <Label htmlFor="edit-isIndexed" className="text-sm font-normal cursor-pointer">
              인덱스 생성
            </Label>
          </div>

          <div className="space-y-2">
            <Label htmlFor="edit-description">설명</Label>
            <Input
              id="edit-description"
              {...editForm.register('description')}
              placeholder="필드 설명"
            />
          </div>

          <div className="flex gap-2">
            <Button type="submit" className="flex-1" disabled={editForm.formState.isSubmitting}>
              {editForm.formState.isSubmitting ? '수정 중...' : '수정'}
            </Button>
            <Button
              type="button"
              variant="outline"
              onClick={() => onOpenChange(false)}
            >
              취소
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
