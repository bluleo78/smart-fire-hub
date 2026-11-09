import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { Clock,Columns, Database, Pencil, Tag } from 'lucide-react';
import React, { useCallback, useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { toast } from 'sonner';

import { datasetsApi } from '../../../api/datasets';
import { EditConflictDialog } from '../../../components/EditConflictDialog';
import { PendingSubmitButton } from '../../../components/PendingSubmitButton';
import { Badge } from '../../../components/ui/badge';
import { Button } from '../../../components/ui/button';
import { Card } from '../../../components/ui/card';
import { FormField } from '../../../components/ui/form-field';
import { Input } from '../../../components/ui/input';
import { Label } from '../../../components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../../components/ui/select';
import { useDatasetPermissions, useUpdateDataset } from '../../../hooks/queries/useDatasets';
import { useEditModeFocus } from '../../../hooks/useEditModeFocus';
import { useSubmitGuard } from '../../../hooks/useSubmitGuard';
import { isEditConflict } from '../../../lib/api-error';
import { formatDate, formatRelativeTime, getOriginTypeLabel, getStorageTypeLabel } from '../../../lib/formatters';
import type { UpdateDatasetFormData } from '../../../lib/validations/dataset';
import { updateDatasetSchema } from '../../../lib/validations/dataset';
import { MAX_DESCRIPTION_LENGTH } from '../../../lib/validations/description';
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

export const DatasetInfoTab = React.memo(function DatasetInfoTab({
  dataset,
  categories,
  datasetId,
  onDirtyChange,
}: DatasetInfoTabProps) {
  const [isEditing, setIsEditing] = React.useState(false);
  // 「수정」·「취소」·「저장」으로 폼과 보기가 바뀔 때 포커스를 첫 칸·「수정」 버튼으로 옮긴다(WD-415)
  const { editButtonRef, editRegionRef } = useEditModeFocus(isEditing);
  const updateDataset = useUpdateDataset(datasetId);
  // 기본 정보 수정(PUT /datasets/{id})은 dataset:write — 없으면 「수정」을 숨겨 조회 전용으로 보인다 (WD-139)
  const { canWriteDataset } = useDatasetPermissions();
  const queryClient = useQueryClient();
  // 정보 저장이 편집 충돌(409 EDIT_CONFLICT)로 거부됐는가(WD-575) — 참이면 EditConflictDialog 를 띄운다
  const [editConflict, setEditConflict] = React.useState(false);
  // 제출 잠금(WD-637) — 버튼을 disabled 대신 aria-disabled 로 잠가 포커스를 지키므로, 진행 중 재제출(입력칸 Enter 연타)은 여기서 무시한다
  const guardInfoSubmit = useSubmitGuard();

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

  /**
   * 정보 저장. 편집 기준 버전(expectedUpdatedAt)을 함께 보내 그사이 다른 곳에서 저장됐으면 서버가 409 로 거부한다(WD-575) — 예전엔 옛 화면에서
   * 설명만 고쳐 저장해도 옛 이름이 다시 써져 먼저 바꾼 이름이 경고 없이 사라졌다.
   * 기준은 지금 폼 내용을 채운 데이터셋의 updatedAt 이다 — 폼은 useForm 의 values 로 dataset 을 따라가므로(재조회되면 폼도 바뀐다) 둘이 어긋나지 않는다.
   * overwrite 는 충돌 안내의 「내 변경으로 덮어쓰기」다(기준을 뺀다).
   */
  const onInfoSubmit = useCallback(
    async (data: UpdateDatasetFormData, overwrite = false) => {
      try {
        await updateDataset.mutateAsync({
          name: data.name,
          description: data.description || undefined,
          categoryId: data.categoryId || undefined,
          // 서버 문자열 그대로 보낸다(Date 로 바꾸면 마이크로초가 잘려 매번 충돌한다)
          ...(overwrite ? {} : { expectedUpdatedAt: dataset.updatedAt }),
        });
        setEditConflict(false);
        toast.success('데이터셋 정보가 업데이트되었습니다.');
        setIsEditing(false);
      } catch (error) {
        // 편집 충돌은 토스트 대신 안내 창으로 — 다시 불러올지 덮어쓸지 고른다(WD-575)
        if (isEditConflict(error)) {
          setEditConflict(true);
          return;
        }
        if (axios.isAxiosError(error) && error.response?.data) {
          const errData = error.response.data as ErrorResponse;
          toast.error(errData.message || '업데이트에 실패했습니다.');
        } else {
          toast.error('업데이트에 실패했습니다.');
        }
      }
    },
    [updateDataset, dataset.updatedAt]
  );

  /**
   * 편집 충돌의 「다시 불러오기」(WD-575) — 최신 데이터셋을 서버에서 새로 받아 폼을 그것으로 바꾸고 내 변경은 버린다.
   * 재조회 결과가 폼 값과 같으면(구조 공유) values 동기화가 다시 돌지 않을 수 있어 reset 으로 직접 채운다.
   * 편집 모드는 닫는다 — 최신 내용을 보기 화면에서 확인한 뒤 다시 「수정」하게 한다.
   */
  const handleReloadLatest = useCallback(async () => {
    try {
      const latest = await queryClient.fetchQuery({
        queryKey: ['datasets', datasetId],
        queryFn: () => datasetsApi.getDatasetById(datasetId).then((r) => r.data),
        staleTime: 0,
      });
      infoForm.reset({
        name: latest.name,
        description: latest.description || '',
        categoryId: latest.category?.id,
      });
      setEditConflict(false);
      setIsEditing(false);
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '최신 내용을 불러오지 못했습니다.');
      } else {
        toast.error('최신 내용을 불러오지 못했습니다.');
      }
    }
  }, [queryClient, datasetId, infoForm]);

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
            {formatRelativeTime(dataset.updatedAt || dataset.createdAt)}
          </p>
          <p className="text-sm text-muted-foreground">최근 수정</p>
        </Card>
      </div>

      {/* Bottom Detail Section */}
      <Card className="p-6">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-xl leading-7 font-semibold">기본 정보</h2>
          {!isEditing && dataset.originType !== 'TEMP' && canWriteDataset && (
            <Button ref={editButtonRef} variant="outline" size="sm" onClick={() => setIsEditing(true)}>
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
          <form
            ref={(el) => {
              editRegionRef.current = el;
            }}
            // handleSubmit 은 두 번째 인자로 이벤트를 넘기므로 감싸서 부른다 — 그대로 넘기면 이벤트가 overwrite 로 읽혀 기준 없이 저장된다
            onSubmit={guardInfoSubmit(infoForm.handleSubmit((data) => onInfoSubmit(data)))}
            className="space-y-4"
          >
            <div className="space-y-2">
              <Label htmlFor="name">데이터셋 이름 *</Label>
              <Input id="name" {...infoForm.register('name')} />
              {infoForm.formState.errors.name && (
                <p className="text-sm text-destructive">
                  {infoForm.formState.errors.name.message}
                </p>
              )}
            </div>

            {/* 예전에 저장된 상한 초과 설명은 maxLength 가 자르지 않으므로 zod 문구를 FormField 로 안내한다(WD-520) */}
            <FormField label="설명" htmlFor="description" error={infoForm.formState.errors.description?.message}>
              <Input
                id="description"
                maxLength={MAX_DESCRIPTION_LENGTH}
                aria-invalid={!!infoForm.formState.errors.description}
                aria-describedby={infoForm.formState.errors.description ? 'description-error' : undefined}
                {...infoForm.register('description')}
              />
            </FormField>

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
              {/* 제출 중에도 포커스가 버튼에 남도록 aria-disabled 로 잠근다(WD-637) */}
              <PendingSubmitButton pending={infoForm.formState.isSubmitting}>
                저장
              </PendingSubmitButton>
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
          <dl className="grid grid-cols-2 md:grid-cols-3 gap-4 [&_dd]:wrap-anywhere">
            {/* 띄어쓰기 없는 긴 값도 칸 안에서 줄바꿈한다(WD-656) */}
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

      {/* 정보 저장 편집 충돌 안내(WD-575) — 다시 불러오기는 최신 정보로 되돌리고, 덮어쓰기는 기준 없이 다시 저장한다 */}
      <EditConflictDialog
        open={editConflict}
        onOpenChange={setEditConflict}
        resourceLabel="데이터셋"
        pending={infoForm.formState.isSubmitting}
        onReload={handleReloadLatest}
        onOverwrite={() => infoForm.handleSubmit((data) => onInfoSubmit(data, true))()}
      />
    </div>
  );
});
