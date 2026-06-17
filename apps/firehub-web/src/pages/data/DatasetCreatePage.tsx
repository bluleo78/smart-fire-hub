import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useRef, useState } from 'react';
import { FormProvider,useForm } from 'react-hook-form';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';

import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Card } from '../../components/ui/card';
import { FormField } from '../../components/ui/form-field';
import { Input } from '../../components/ui/input';
import { SearchableSelect } from '../../components/ui/searchable-select';
import { useCategories, useCreateDataset, useDatasets } from '../../hooks/queries/useDatasets';
import { handleApiError } from '../../lib/api-error';
import { getOriginTypeLabel, getStorageTypeLabel } from '../../lib/formatters';
import type { CreateDatasetFormData } from '../../lib/validations/dataset';
import { createDatasetSchema } from '../../lib/validations/dataset';
import { SchemaBuilder } from './components/SchemaBuilder';

export default function DatasetCreatePage() {
  const navigate = useNavigate();
  const { data: categoriesData } = useCategories();
  const createDataset = useCreateDataset();

  const categories = categoriesData || [];

  // 유형은 더 이상 폼에서 선택하지 않고 URL 쿼리(생성 유형 선택 모달에서 전달)로 고정한다.
  const [searchParams] = useSearchParams();
  const storageType = (searchParams.get('storageType') === 'DOCUMENT' ? 'DOCUMENT' : 'TABLE') as 'TABLE' | 'DOCUMENT';
  const originType = (() => {
    const o = searchParams.get('originType');
    return o === 'DERIVED' || o === 'TEMP' ? o : 'SOURCE';
  })() as 'SOURCE' | 'DERIVED' | 'TEMP';
  // DOCUMENT 유형 여부 — 칼럼·테이블명 UI 토글에 사용 (URL에서 파생되므로 폼 watch 불필요)
  const isDocument = storageType === 'DOCUMENT';

  const form = useForm<CreateDatasetFormData>({
    mode: 'onSubmit',
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(createDatasetSchema) as any,
    defaultValues: {
      name: '',
      // DOCUMENT는 동적 테이블/컬럼이 없어 tableName을 규칙([a-z][a-z0-9_]*) 만족하는 메타 식별자로 자동 생성
      tableName: isDocument ? `doc_${Date.now()}` : '',
      description: '',
      categoryId: undefined,
      storageType,
      originType,
      columns: isDocument
        ? []
        : [
            {
              columnName: '',
              displayName: '',
              dataType: 'TEXT',
              isNullable: true,
              isIndexed: false,
              isPrimaryKey: false,
              description: '',
            },
          ],
    },
  });

  // 폼 ref — Cmd/Ctrl+S 단축키에서 submit 트리거에 사용 (#100)
  const formRef = useRef<HTMLFormElement>(null);

  // 이름·테이블명 중복 검증을 위한 debounce 처리값 (#103).
  // 사용자가 입력을 멈추고 약 400ms 경과 후에만 검색 쿼리를 트리거하여 키 입력마다 API가 폭주하지 않도록 함.
  const watchedName = form.watch('name');
  const watchedTableName = form.watch('tableName');
  const [debouncedName, setDebouncedName] = useState('');
  const [debouncedTableName, setDebouncedTableName] = useState('');

  useEffect(() => {
    const t = setTimeout(() => setDebouncedName(watchedName?.trim() ?? ''), 400);
    return () => clearTimeout(t);
  }, [watchedName]);

  useEffect(() => {
    const t = setTimeout(() => setDebouncedTableName(watchedTableName?.trim() ?? ''), 400);
    return () => clearTimeout(t);
  }, [watchedTableName]);

  // 백엔드 search 파라미터로 부분 일치 결과를 받아 클라이언트에서 정확 일치를 검사한다 (#103).
  // 전용 check-name 엔드포인트가 없는 현재 환경에서 useDatasets 결과를 활용해 중복 여부를 판정.
  const { data: nameSearchData } = useDatasets({
    search: debouncedName,
    size: 20,
  });
  const { data: tableNameSearchData } = useDatasets({
    search: debouncedTableName,
    size: 20,
  });

  const isNameDuplicate =
    debouncedName.length > 0 &&
    !!nameSearchData?.content?.some((d) => d.name.toLowerCase() === debouncedName.toLowerCase());
  const isTableNameDuplicate =
    debouncedTableName.length > 0 &&
    !!tableNameSearchData?.content?.some(
      (d) => d.tableName.toLowerCase() === debouncedTableName.toLowerCase()
    );

  // 전역 Cmd/Ctrl+S 단축키로 폼 저장 (#100).
  // 브라우저 기본 "페이지 저장" 다이얼로그를 preventDefault하고 requestSubmit으로 폼 검증을 거쳐 onSubmit 실행.
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && (e.key === 's' || e.key === 'S')) {
        e.preventDefault();
        formRef.current?.requestSubmit();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  const onSubmit = async (data: CreateDatasetFormData) => {
    // 중복 인라인 검증 결과가 있으면 제출 차단 (#103) — 서버 왕복으로 알게 되는 비용을 줄임
    if (isNameDuplicate) {
      form.setError('name', { type: 'duplicate', message: '이미 사용 중인 이름입니다.' });
      return;
    }
    // DOCUMENT는 tableName을 자동 생성하므로 중복 검사에서 제외한다.
    if (!isDocument && isTableNameDuplicate) {
      form.setError('tableName', { type: 'duplicate', message: '이미 사용 중인 테이블명입니다.' });
      return;
    }
    try {
      const result = await createDataset.mutateAsync({
        name: data.name,
        tableName: data.tableName,
        description: data.description || undefined,
        categoryId: data.categoryId || undefined,
        storageType: data.storageType,
        originType: data.originType,
        columns: data.columns.map((col) => ({
          columnName: col.columnName,
          displayName: col.displayName || undefined,
          dataType: col.dataType,
          maxLength: col.maxLength ?? undefined,
          isNullable: col.isNullable,
          isIndexed: col.isIndexed,
          isPrimaryKey: col.isPrimaryKey,
          description: col.description || undefined,
        })),
      });
      toast.success('데이터셋이 생성되었습니다.');
      navigate(`/data/datasets/${result.data.id}`);
    } catch (error) {
      handleApiError(error, '데이터셋 생성에 실패했습니다.');
    }
  };

  return (
    <div className="max-w-2xl mx-auto space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">데이터셋 생성</h1>
      </div>

      <FormProvider {...form}>
        {/*
         * 폼 검증 실패 시 인라인 에러 메시지만 노출한다 (#69).
         * 과거에는 toast.error로도 동일한 안내를 띄웠으나, 인라인 에러와
         * 중복되어 사용자 시선을 분산시키고 어떤 필드가 잘못됐는지 알리지 못해 제거.
         * 대신 첫 번째 에러 필드 input으로 자동 스크롤·focus 이동시켜 사용자가
         * 즉시 어떤 필드를 수정해야 하는지 인지할 수 있도록 한다.
         * 단, 서버 에러(API 실패)는 onSubmit catch의 handleApiError로 toast 노출 유지.
         */}
        <form ref={formRef} onSubmit={form.handleSubmit(onSubmit, (errors) => {
          // 에러가 있는 첫 번째 필드를 찾아 focus + scrollIntoView
          const firstErrorKey = Object.keys(errors)[0];
          if (firstErrorKey) {
            const el = document.querySelector<HTMLElement>(
              `[name="${firstErrorKey}"], #${firstErrorKey}`
            );
            if (el) {
              el.scrollIntoView({ behavior: 'smooth', block: 'center' });
              el.focus({ preventScroll: true });
            }
          }
        })} className="space-y-6">
          <Card className="p-6">
            <h2 className="text-xl leading-7 font-semibold mb-4">기본 정보</h2>
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <FormField
                  label="데이터셋 이름"
                  htmlFor="name"
                  required
                  error={
                    form.formState.errors.name?.message ??
                    (isNameDuplicate ? '이미 사용 중인 이름입니다.' : undefined)
                  }
                >
                  <Input
                    id="name"
                    {...form.register('name')}
                    placeholder="예: 사용자 데이터"
                    aria-invalid={isNameDuplicate || !!form.formState.errors.name}
                  />
                  {/* 중복 검사 통과 시 양호 표시 (#103) */}
                  {!isNameDuplicate && debouncedName.length > 0 && !form.formState.errors.name && (
                    <p className="text-xs text-muted-foreground mt-1">사용 가능한 이름입니다.</p>
                  )}
                </FormField>

                {/* DOCUMENT 유형은 tableName을 자동 생성하므로 입력 필드를 숨긴다 */}
                {!isDocument && (
                  <FormField
                    label="테이블명"
                    htmlFor="tableName"
                    required
                    error={
                      form.formState.errors.tableName?.message ??
                      (isTableNameDuplicate ? '이미 사용 중인 테이블명입니다.' : undefined)
                    }
                  >
                    <Input
                      id="tableName"
                      {...form.register('tableName')}
                      placeholder="예: user_data"
                      className="font-mono"
                      aria-invalid={isTableNameDuplicate || !!form.formState.errors.tableName}
                    />
                    {!isTableNameDuplicate && debouncedTableName.length > 0 && !form.formState.errors.tableName && (
                      <p className="text-xs text-muted-foreground mt-1">사용 가능한 테이블명입니다.</p>
                    )}
                  </FormField>
                )}
              </div>

              <FormField label="설명" htmlFor="description">
                <Input
                  id="description"
                  {...form.register('description')}
                  placeholder="데이터셋 설명"
                />
              </FormField>

              <div className="grid grid-cols-2 gap-4">
                {/* 카테고리는 사용자 정의로 늘어날 수 있어 검색 가능한 SearchableSelect로 대체 (#104) */}
                <FormField label="카테고리" htmlFor="categoryId">
                  <SearchableSelect
                    id="categoryId"
                    value={form.watch('categoryId')?.toString()}
                    onChange={(value) => {
                      form.setValue('categoryId', value === undefined ? undefined : Number(value));
                    }}
                    options={categories.map((cat) => ({
                      value: cat.id.toString(),
                      label: cat.name,
                    }))}
                    placeholder="카테고리 선택"
                    searchPlaceholder="카테고리 검색..."
                    emptyText="일치하는 카테고리가 없습니다."
                    allowClear
                    clearLabel="선택 안 함"
                  />
                </FormField>

                {/* 유형은 생성 유형 선택 모달에서 정해 URL로 고정되므로 폼에서 변경 불가 — 읽기 전용으로 표시 */}
                <FormField label="데이터셋 유형" htmlFor="datasetTypeDisplay">
                  <div id="datasetTypeDisplay" className="flex items-center gap-2 h-9">
                    <Badge variant="secondary">{getStorageTypeLabel(storageType)}</Badge>
                    <Badge variant="secondary">{getOriginTypeLabel(originType)}</Badge>
                  </div>
                </FormField>
              </div>
            </div>
          </Card>

          {/* DOCUMENT 유형은 동적 칼럼이 없으므로 칼럼 정의 카드를 숨긴다 */}
          {!isDocument && (
            <Card className="p-6">
              <h2 className="text-xl leading-7 font-semibold mb-4">칼럼 정의</h2>
              <SchemaBuilder />
            </Card>
          )}

          <div className="flex gap-2">
            <Button
              type="submit"
              disabled={form.formState.isSubmitting || isNameDuplicate || isTableNameDuplicate}
            >
              {form.formState.isSubmitting ? '생성 중...' : '생성'}
            </Button>
            <Button
              type="button"
              variant="outline"
              onClick={() => navigate('/data/datasets')}
            >
              취소
            </Button>
          </div>
        </form>
      </FormProvider>
    </div>
  );
}
