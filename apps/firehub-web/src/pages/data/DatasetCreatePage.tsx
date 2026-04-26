import { zodResolver } from '@hookform/resolvers/zod';
import { FormProvider,useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { Button } from '../../components/ui/button';
import { Card } from '../../components/ui/card';
import { FormField } from '../../components/ui/form-field';
import { Input } from '../../components/ui/input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import { useCategories, useCreateDataset } from '../../hooks/queries/useDatasets';
import { handleApiError } from '../../lib/api-error';
import type { CreateDatasetFormData } from '../../lib/validations/dataset';
import { createDatasetSchema } from '../../lib/validations/dataset';
import { SchemaBuilder } from './components/SchemaBuilder';

export default function DatasetCreatePage() {
  const navigate = useNavigate();
  const { data: categoriesData } = useCategories();
  const createDataset = useCreateDataset();

  const categories = categoriesData || [];

  const form = useForm<CreateDatasetFormData>({
    mode: 'onSubmit',
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(createDatasetSchema) as any,
    defaultValues: {
      name: '',
      tableName: '',
      description: '',
      categoryId: undefined,
      datasetType: 'SOURCE',
      columns: [
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

  const onSubmit = async (data: CreateDatasetFormData) => {
    try {
      const result = await createDataset.mutateAsync({
        name: data.name,
        tableName: data.tableName,
        description: data.description || undefined,
        categoryId: data.categoryId || undefined,
        datasetType: data.datasetType,
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
        <form onSubmit={form.handleSubmit(onSubmit, (errors) => {
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
                  error={form.formState.errors.name?.message}
                >
                  <Input
                    id="name"
                    {...form.register('name')}
                    placeholder="예: 사용자 데이터"
                  />
                </FormField>

                <FormField
                  label="테이블명"
                  htmlFor="tableName"
                  required
                  error={form.formState.errors.tableName?.message}
                >
                  <Input
                    id="tableName"
                    {...form.register('tableName')}
                    placeholder="예: user_data"
                    className="font-mono"
                  />
                </FormField>
              </div>

              <FormField label="설명" htmlFor="description">
                <Input
                  id="description"
                  {...form.register('description')}
                  placeholder="데이터셋 설명"
                />
              </FormField>

              <div className="grid grid-cols-2 gap-4">
                <FormField label="카테고리" htmlFor="categoryId">
                  <Select
                    value={form.watch('categoryId')?.toString() || '__none__'}
                    onValueChange={(value) => {
                      form.setValue('categoryId', value === '__none__' ? undefined : Number(value));
                    }}
                  >
                    <SelectTrigger>
                      <SelectValue placeholder="카테고리 선택" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="__none__">선택 안 함</SelectItem>
                      {categories.map((cat) => (
                        <SelectItem key={cat.id} value={cat.id.toString()}>
                          {cat.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </FormField>

                <FormField
                  label="데이터셋 유형"
                  htmlFor="datasetType"
                  required
                  error={form.formState.errors.datasetType?.message}
                >
                  <Select
                    value={form.watch('datasetType')}
                    onValueChange={(value) => {
                      form.setValue('datasetType', value as 'SOURCE' | 'DERIVED');
                    }}
                  >
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="SOURCE">원본</SelectItem>
                      <SelectItem value="DERIVED">파생</SelectItem>
                    </SelectContent>
                  </Select>
                </FormField>
              </div>
            </div>
          </Card>

          <Card className="p-6">
            <h2 className="text-xl leading-7 font-semibold mb-4">칼럼 정의</h2>
            <SchemaBuilder />
          </Card>

          <div className="flex gap-2">
            <Button type="submit" disabled={form.formState.isSubmitting}>
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
