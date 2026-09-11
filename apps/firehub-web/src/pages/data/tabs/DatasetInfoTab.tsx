import { zodResolver } from '@hookform/resolvers/zod';
import axios from 'axios';
import { Clock,Columns, Database, Pencil, Tag } from 'lucide-react';
import React, { useCallback, useEffect } from 'react';
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
import { formatDate, getOriginTypeLabel, getStorageTypeLabel } from '../../../lib/formatters';
import type { UpdateDatasetFormData } from '../../../lib/validations/dataset';
import { updateDatasetSchema } from '../../../lib/validations/dataset';
import type { ErrorResponse } from '../../../types/auth';
import type { CategoryResponse,DatasetDetailResponse } from '../../../types/dataset';

interface DatasetInfoTabProps {
  dataset: DatasetDetailResponse;
  categories: CategoryResponse[];
  datasetId: number;
  // 인라인 편집 폼의 미저장 변경 여부를 부모(DatasetDetailPage)에 보고한다 (#635).
  // 매핑 탭의 onDirtyChange와 동일한 콜백 패턴 — 부모가 useUnsavedChangesGuard에 합류시킨다.
  onDirtyChange?: (dirty: boolean) => void;
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
  onDirtyChange,
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

  // 편집 모드에서 실제로 값이 바뀐 경우에만 dirty로 간주한다 — 뷰 모드(isEditing=false)는
  // react-hook-form의 formState.isDirty와 무관하게 항상 안전(#635).
  const isInfoDirty = isEditing && infoForm.formState.isDirty;

  // dirty 변화를 부모로 실시간 보고 — DatasetMappingTab의 onDirtyChange와 동일한 패턴(#502 참고).
  // 부모는 이 값을 mappingDirty와 OR 합산해 useUnsavedChangesGuard에 전달한다.
  useEffect(() => {
    onDirtyChange?.(isInfoDirty);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- onDirtyChange는 부모의 setState라 항상 안정적 참조
  }, [isInfoDirty]);

  // 탭 전환 등으로 unmount될 때도 false로 정리해 부모 쪽 가드가 남지 않게 한다.
  useEffect(() => {
    return () => onDirtyChange?.(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 최초 마운트 시점의 onDirtyChange로 충분(부모 setState는 안정적)
  }, []);

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
    <div className="max-w-4xl space-y-6">
      {/* Top Stats Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {/* DOCUMENT 유형은 행/컬럼 개념이 없으므로 해당 카드 숨김 (#286) */}
        {dataset.storageType !== 'DOCUMENT' && (
          <>
            {/* Row Count */}
            <Card className="p-4">
              <Database size={20} className="text-muted-foreground mb-2" />
              <p className="text-2xl font-semibold font-mono tabular-nums">{dataset.rowCount != null ? dataset.rowCount.toLocaleString() : '-'}</p>
              <p className="text-sm text-muted-foreground">행</p>
            </Card>

            {/* Column Count */}
            <Card className="p-4">
              <Columns size={20} className="text-muted-foreground mb-2" />
              <p className="text-2xl font-semibold font-mono tabular-nums">{dataset.columns.length}</p>
              <p className="text-sm text-muted-foreground">개 컬럼</p>
            </Card>
          </>
        )}

        {/* Type */}
        <Card className="p-4">
          <Tag size={20} className="text-muted-foreground mb-2" />
          <div className="my-1 flex gap-1">
            {/* 이슈 #107: 영문 enum 노출 금지 — 저장 방식/출처를 한글 라벨 두 배지로 표시 */}
            <Badge variant="secondary">{getStorageTypeLabel(dataset.storageType)}</Badge>
            <Badge variant="secondary">{getOriginTypeLabel(dataset.originType)}</Badge>
          </div>
          <p className="text-sm text-muted-foreground">유형</p>
        </Card>

        {/* Last Modified */}
        <Card className="p-4">
          <Clock size={20} className="text-muted-foreground mb-2" />
          <p className="text-2xl font-semibold font-mono tabular-nums">
            {getRelativeTime(dataset.updatedAt || dataset.createdAt)}
          </p>
          <p className="text-sm text-muted-foreground">최근 수정</p>
        </Card>
      </div>

      {/* Bottom Detail Section */}
      <Card className="p-6">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-xl leading-7 font-semibold">기본 정보</h2>
          {!isEditing && dataset.originType !== 'TEMP' && (
            <Button variant="outline" size="sm" onClick={() => setIsEditing(true)}>
              <Pencil className="h-4 w-4" />
              수정
            </Button>
          )}
        </div>
        {dataset.originType === 'TEMP' && (
          <p className="text-sm text-muted-foreground mb-4 p-3 rounded-md bg-muted">
            파이프라인에서 자동 생성된 임시 데이터셋입니다.
          </p>
        )}

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
              <dd className="flex gap-1">
                {/* 저장 방식/출처를 분리하여 두 배지로 표시 */}
                <Badge variant="secondary">{getStorageTypeLabel(dataset.storageType)}</Badge>
                <Badge variant={dataset.originType === 'SOURCE' ? 'default' : 'secondary'}>
                  {getOriginTypeLabel(dataset.originType)}
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
