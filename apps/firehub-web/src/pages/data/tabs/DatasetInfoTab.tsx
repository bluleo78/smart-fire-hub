import React, { useCallback } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useUpdateDataset } from '../../../hooks/queries/useDatasets';
import { updateDatasetSchema } from '../../../lib/validations/dataset';
import type { UpdateDatasetFormData } from '../../../lib/validations/dataset';
import type { DatasetDetailResponse, CategoryResponse } from '../../../types/dataset';
import { Button } from '../../../components/ui/button';
import { Input } from '../../../components/ui/input';
import { Label } from '../../../components/ui/label';
import { Badge } from '../../../components/ui/badge';
import { Card } from '../../../components/ui/card';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../../components/ui/select';
import { Edit } from 'lucide-react';
import { toast } from 'sonner';
import type { ErrorResponse } from '../../../types/auth';
import axios from 'axios';
import { formatDate } from '../../../lib/formatters';

interface DatasetInfoTabProps {
  dataset: DatasetDetailResponse;
  categories: CategoryResponse[];
  datasetId: number;
}

export const DatasetInfoTab = React.memo(function DatasetInfoTab({
  dataset,
  categories,
  datasetId,
}: DatasetInfoTabProps) {
  const [isEditing, setIsEditing] = React.useState(false);
  const updateDataset = useUpdateDataset(datasetId);

  const infoForm = useForm<UpdateDatasetFormData>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(updateDatasetSchema) as any,
    values: {
      name: dataset.name,
      description: dataset.description || '',
      categoryId: dataset.category?.id,
    },
  });

  const onInfoSubmit = useCallback(
    async (data: UpdateDatasetFormData) => {
      try {
        await updateDataset.mutateAsync({
          name: data.name,
          description: data.description || undefined,
          categoryId: data.categoryId || undefined,
        });
        toast.success('데이터셋 정보가 업데이트되었습니다.');
        setIsEditing(false);
      } catch (error) {
        if (axios.isAxiosError(error) && error.response?.data) {
          const errData = error.response.data as ErrorResponse;
          toast.error(errData.message || '업데이트에 실패했습니다.');
        } else {
          toast.error('업데이트에 실패했습니다.');
        }
      }
    },
    [updateDataset]
  );

  return (
    <Card className="p-6">
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-lg font-semibold">기본 정보</h2>
        {!isEditing && (
          <Button variant="outline" size="sm" onClick={() => setIsEditing(true)}>
            <Edit className="mr-2 h-4 w-4" />
            수정
          </Button>
        )}
      </div>

      {isEditing ? (
        <form onSubmit={infoForm.handleSubmit(onInfoSubmit)} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="name">데이터셋 이름 *</Label>
            <Input id="name" {...infoForm.register('name')} />
            {infoForm.formState.errors.name && (
              <p className="text-sm text-destructive">
                {infoForm.formState.errors.name.message}
              </p>
            )}
          </div>

          <div className="space-y-2">
            <Label htmlFor="description">설명</Label>
            <Input id="description" {...infoForm.register('description')} />
          </div>

          <div className="space-y-2">
            <Label htmlFor="categoryId">카테고리</Label>
            <Select
              value={infoForm.watch('categoryId')?.toString() || '__none__'}
              onValueChange={(value) => {
                infoForm.setValue('categoryId', value === '__none__' ? undefined : Number(value));
              }}
            >
              <SelectTrigger>
                <SelectValue placeholder="카테고리 선택" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__none__">없음</SelectItem>
                {categories.map((cat) => (
                  <SelectItem key={cat.id} value={cat.id.toString()}>
                    {cat.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="flex gap-2">
            <Button type="submit" disabled={infoForm.formState.isSubmitting}>
              저장
            </Button>
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                setIsEditing(false);
                infoForm.reset();
              }}
            >
              취소
            </Button>
          </div>
        </form>
      ) : (
        <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
          <div>
            <p className="text-sm text-muted-foreground">설명</p>
            <p className="text-sm">{dataset.description || '-'}</p>
          </div>
          <div>
            <p className="text-sm text-muted-foreground">카테고리</p>
            <p className="text-sm">{dataset.category?.name || '-'}</p>
          </div>
          <div>
            <p className="text-sm text-muted-foreground">유형</p>
            <Badge variant={dataset.datasetType === 'SOURCE' ? 'default' : 'secondary'}>
              {dataset.datasetType === 'SOURCE' ? '원본' : '파생'}
            </Badge>
          </div>
          <div>
            <p className="text-sm text-muted-foreground">생성자</p>
            <p className="text-sm">{dataset.createdBy}</p>
          </div>
          <div>
            <p className="text-sm text-muted-foreground">행 수</p>
            <p className="text-sm">{dataset.rowCount.toLocaleString()}</p>
          </div>
          <div>
            <p className="text-sm text-muted-foreground">생성일</p>
            <p className="text-sm">{formatDate(dataset.createdAt)}</p>
          </div>
          <div>
            <p className="text-sm text-muted-foreground">수정일</p>
            <p className="text-sm">{formatDate(dataset.updatedAt)}</p>
          </div>
          {dataset.updatedBy && (
            <div>
              <p className="text-sm text-muted-foreground">수정자</p>
              <p className="text-sm">{dataset.updatedBy}</p>
            </div>
          )}
        </div>
      )}
    </Card>
  );
});
