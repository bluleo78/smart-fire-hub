import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useAddColumn } from '../../../hooks/queries/useDatasets';
import { addColumnSchema } from '../../../lib/validations/dataset';
import type { AddColumnFormData } from '../../../lib/validations/dataset';
import { Button } from '../../../components/ui/button';
import { Input } from '../../../components/ui/input';
import { Label } from '../../../components/ui/label';
import { Checkbox } from '../../../components/ui/checkbox';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from '../../../components/ui/dialog';
import { ColumnTypeSelect } from './ColumnTypeSelect';
import { Plus } from 'lucide-react';
import { toast } from 'sonner';
import type { ErrorResponse } from '../../../types/auth';
import axios from 'axios';

interface AddColumnDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  datasetId: number;
}

export function AddColumnDialog({ open, onOpenChange, datasetId }: AddColumnDialogProps) {
  const addColumn = useAddColumn(datasetId);

  const columnForm = useForm<AddColumnFormData>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(addColumnSchema) as any,
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

  const onAddColumnSubmit = async (data: AddColumnFormData) => {
    try {
      await addColumn.mutateAsync({
        columnName: data.columnName,
        displayName: data.displayName || undefined,
        dataType: data.dataType,
        maxLength: data.maxLength || undefined,
        isNullable: data.isNullable,
        isIndexed: data.isIndexed,
        description: data.description || undefined,
      });
      toast.success('필드가 추가되었습니다.');
      onOpenChange(false);
      columnForm.reset();
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '필드 추가에 실패했습니다.');
      } else {
        toast.error('필드 추가에 실패했습니다.');
      }
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogTrigger asChild>
        <Button>
          <Plus className="mr-2 h-4 w-4" />
          필드 추가
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>필드 추가</DialogTitle>
        </DialogHeader>
        <form onSubmit={columnForm.handleSubmit(onAddColumnSubmit)} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="columnName">필드명 *</Label>
            <Input
              id="columnName"
              {...columnForm.register('columnName')}
              placeholder="예: user_id"
            />
            {columnForm.formState.errors.columnName && (
              <p className="text-sm text-destructive">
                {columnForm.formState.errors.columnName.message}
              </p>
            )}
          </div>

          <div className="space-y-2">
            <Label htmlFor="displayName">표시명</Label>
            <Input
              id="displayName"
              {...columnForm.register('displayName')}
              placeholder="예: 사용자 ID"
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="dataType">데이터 타입 *</Label>
            <ColumnTypeSelect
              value={columnForm.watch('dataType')}
              onChange={(value) => columnForm.setValue('dataType', value as AddColumnFormData['dataType'])}
            />
            {columnForm.formState.errors.dataType && (
              <p className="text-sm text-destructive">
                {columnForm.formState.errors.dataType.message}
              </p>
            )}
          </div>

          {columnForm.watch('dataType') === 'VARCHAR' && (
            <div className="space-y-2">
              <Label htmlFor="maxLength">최대 길이 *</Label>
              <Input
                id="maxLength"
                type="number"
                min={1}
                max={10000}
                {...columnForm.register('maxLength', { valueAsNumber: true })}
                placeholder="예: 255"
              />
              {columnForm.formState.errors.maxLength && (
                <p className="text-sm text-destructive">
                  {columnForm.formState.errors.maxLength.message}
                </p>
              )}
            </div>
          )}

          <div className="flex items-center space-x-2">
            <Checkbox
              id="isNullable"
              checked={columnForm.watch('isNullable')}
              onCheckedChange={(checked) =>
                columnForm.setValue('isNullable', checked as boolean)
              }
            />
            <Label htmlFor="isNullable" className="text-sm font-normal cursor-pointer">
              NULL 허용
            </Label>
          </div>

          <div className="flex items-center space-x-2">
            <Checkbox
              id="isIndexed"
              checked={columnForm.watch('isIndexed')}
              onCheckedChange={(checked) =>
                columnForm.setValue('isIndexed', checked as boolean)
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
              {...columnForm.register('description')}
              placeholder="필드 설명"
            />
          </div>

          <Button type="submit" className="w-full" disabled={columnForm.formState.isSubmitting}>
            {columnForm.formState.isSubmitting ? '추가 중...' : '추가'}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}
