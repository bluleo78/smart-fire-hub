import { useNavigate } from 'react-router-dom';
import { useForm, FormProvider } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useCategories, useCreateDataset } from '../../hooks/queries/useDatasets';
import { createDatasetSchema } from '../../lib/validations/dataset';
import type { CreateDatasetFormData } from '../../lib/validations/dataset';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Card } from '../../components/ui/card';
import { FormField } from '../../components/ui/form-field';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import { SchemaBuilder } from './components/SchemaBuilder';
import { toast } from 'sonner';
import { handleApiError } from '../../lib/api-error';

export default function DatasetCreatePage() {
  const navigate = useNavigate();
  const { data: categoriesData } = useCategories();
  const createDataset = useCreateDataset();

  const categories = categoriesData || [];

  const form = useForm<CreateDatasetFormData>({
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
    <div className="max-w-4xl space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">데이터셋 생성</h1>
      </div>

      <FormProvider {...form}>
        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
          <Card className="p-6">
            <h2 className="text-lg font-semibold mb-4">기본 정보</h2>
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
                    value={form.watch('categoryId')?.toString() || ''}
                    onValueChange={(value) => {
                      form.setValue('categoryId', value ? Number(value) : undefined);
                    }}
                  >
                    <SelectTrigger>
                      <SelectValue placeholder="카테고리 선택" />
                    </SelectTrigger>
                    <SelectContent>
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
            <h2 className="text-lg font-semibold mb-4">칼럼 정의</h2>
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
