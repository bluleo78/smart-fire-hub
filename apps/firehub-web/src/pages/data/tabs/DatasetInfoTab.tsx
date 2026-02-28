import { zodResolver } from '@hookform/resolvers/zod';
import axios from 'axios';
import { Clock,Columns, Database, Edit, Tag } from 'lucide-react';
import React, { useCallback } from 'react';
import { useForm } from 'react-hook-form';
import { toast } from 'sonner';

import { Badge } from '../../../components/ui/badge';
import { Button } from '../../../components/ui/button';
import { Card } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { Label } from '../../../components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../../components/ui/select';
import { useUpdateDataset } from '../../../hooks/queries/useDatasets';
import { formatDate } from '../../../lib/formatters';
import type { UpdateDatasetFormData } from '../../../lib/validations/dataset';
import { updateDatasetSchema } from '../../../lib/validations/dataset';
import type { ErrorResponse } from '../../../types/auth';
import type { CategoryResponse,DatasetDetailResponse } from '../../../types/dataset';

interface DatasetInfoTabProps {
  dataset: DatasetDetailResponse;
  categories: CategoryResponse[];
  datasetId: number;
}

function getRelativeTime(dateStr: string | null): string {
  if (!dateStr) return '-';
  const diff = Date.now() - new Date(dateStr).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return '방금 전';
  if (mins < 60) return `${mins}분 전`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}시간 전`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}일 전`;
  const months = Math.floor(days / 30);
  return `${months}개월 전`;
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
    <div>
      {/* Top Stats Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {/* Row Count */}
        <Card className="p-4">
          <Database size={20} className="text-muted-foreground mb-2" />
          <p className="text-2xl font-bold">{dataset.rowCount.toLocaleString()}</p>
          <p className="text-sm text-muted-foreground">행</p>
        </Card>

        {/* Column Count */}
        <Card className="p-4">
          <Columns size={20} className="text-muted-foreground mb-2" />
          <p className="text-2xl font-bold">{dataset.columns.length}</p>
          <p className="text-sm text-muted-foreground">개 컬럼</p>
        </Card>

        {/* Type */}
        <Card className="p-4">
          <Tag size={20} className="text-muted-foreground mb-2" />
          <div className="my-1">
            <Badge variant={dataset.datasetType === 'SOURCE' ? 'default' : 'secondary'}>
              {dataset.datasetType === 'SOURCE' ? 'SOURCE' : 'DERIVED'}
            </Badge>
          </div>
          <p className="text-sm text-muted-foreground">유형</p>
        </Card>

        {/* Last Modified */}
        <Card className="p-4">
          <Clock size={20} className="text-muted-foreground mb-2" />
          <p className="text-2xl font-bold leading-tight">
            {getRelativeTime(dataset.updatedAt || dataset.createdAt)}
          </p>
          <p className="text-sm text-muted-foreground">최근 수정</p>
        </Card>
      </div>

      {/* Bottom Detail Section */}
      <Card className="p-6 mt-6">
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
          <dl className="grid grid-cols-2 md:grid-cols-3 gap-4">
            <div>
              <dt className="text-sm text-muted-foreground">이름</dt>
              <dd className="text-sm font-medium">{dataset.name}</dd>
            </div>
            <div>
              <dt className="text-sm text-muted-foreground">설명</dt>
              <dd className="text-sm">{dataset.description || '-'}</dd>
            </div>
            <div>
              <dt className="text-sm text-muted-foreground">카테고리</dt>
              <dd className="text-sm">{dataset.category?.name || '-'}</dd>
            </div>
            <div>
              <dt className="text-sm text-muted-foreground">테이블명</dt>
              <dd className="text-sm font-mono">{dataset.tableName}</dd>
            </div>
            <div>
              <dt className="text-sm text-muted-foreground">데이터셋 타입</dt>
              <dd>
                <Badge variant={dataset.datasetType === 'SOURCE' ? 'default' : 'secondary'}>
                  {dataset.datasetType === 'SOURCE' ? '원본' : '파생'}
                </Badge>
              </dd>
            </div>
            <div>
              <dt className="text-sm text-muted-foreground">생성자</dt>
              <dd className="text-sm">{dataset.createdBy}</dd>
            </div>
            <div>
              <dt className="text-sm text-muted-foreground">생성일</dt>
              <dd className="text-sm">{formatDate(dataset.createdAt)}</dd>
            </div>
            <div>
              <dt className="text-sm text-muted-foreground">수정일</dt>
              <dd className="text-sm">{formatDate(dataset.updatedAt)}</dd>
            </div>
            {dataset.updatedBy && (
              <div>
                <dt className="text-sm text-muted-foreground">수정자</dt>
                <dd className="text-sm">{dataset.updatedBy}</dd>
              </div>
            )}
          </dl>
        )}
      </Card>
    </div>
  );
});
