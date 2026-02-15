import { useNavigate } from 'react-router-dom';
import { useForm, FormProvider, useFieldArray } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useDatasets } from '../../hooks/queries/useDatasets';
import { useCreatePipeline } from '../../hooks/queries/usePipelines';
import { createPipelineSchema } from '../../lib/validations/pipeline';
import type { CreatePipelineFormData } from '../../lib/validations/pipeline';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import { Card } from '../../components/ui/card';
import { StepEditor } from '../../components/pipeline/StepEditor';
import { Plus } from 'lucide-react';
import { toast } from 'sonner';
import type { ErrorResponse } from '../../types/auth';
import axios from 'axios';

export function PipelineCreatePage() {
  const navigate = useNavigate();
  const { data: datasetsData } = useDatasets({ page: 0, size: 1000 });
  const createPipeline = useCreatePipeline();

  const datasets = datasetsData?.content || [];

  const form = useForm<CreatePipelineFormData>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(createPipelineSchema) as any,
    defaultValues: {
      name: '',
      description: '',
      steps: [
        {
          name: '',
          description: '',
          scriptType: 'SQL' as const,
          scriptContent: '',
          outputDatasetId: 0,
          inputDatasetIds: [],
          dependsOnStepNames: [],
        },
      ],
    },
  });

  const { fields, append, remove } = useFieldArray({
    control: form.control,
    name: 'steps',
  });

  const onSubmit = async (data: CreatePipelineFormData) => {
    try {
      const result = await createPipeline.mutateAsync({
        name: data.name,
        description: data.description || undefined,
        steps: data.steps.map((step) => ({
          name: step.name,
          description: step.description || undefined,
          scriptType: step.scriptType,
          scriptContent: step.scriptContent,
          outputDatasetId: step.outputDatasetId,
          inputDatasetIds: step.inputDatasetIds,
          dependsOnStepNames: step.dependsOnStepNames,
        })),
      });
      toast.success('파이프라인이 생성되었습니다.');
      navigate(`/pipelines/${result.data.id}`);
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '파이프라인 생성에 실패했습니다.');
      } else {
        toast.error('파이프라인 생성에 실패했습니다.');
      }
    }
  };

  return (
    <div className="max-w-5xl space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">파이프라인 생성</h1>
      </div>

      <FormProvider {...form}>
        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
          <Card className="p-6">
            <h2 className="text-lg font-semibold mb-4">기본 정보</h2>
            <div className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="name">파이프라인 이름 *</Label>
                <Input
                  id="name"
                  {...form.register('name')}
                  placeholder="예: 일일 데이터 처리"
                />
                {form.formState.errors.name && (
                  <p className="text-sm text-destructive">
                    {form.formState.errors.name.message}
                  </p>
                )}
              </div>

              <div className="space-y-2">
                <Label htmlFor="description">설명</Label>
                <Input
                  id="description"
                  {...form.register('description')}
                  placeholder="파이프라인 설명"
                />
              </div>
            </div>
          </Card>

          <div className="space-y-4">
            <div className="flex items-center justify-between">
              <h2 className="text-lg font-semibold">스텝 정의</h2>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => append({
                  name: '',
                  description: '',
                  scriptType: 'SQL' as const,
                  scriptContent: '',
                  outputDatasetId: 0,
                  inputDatasetIds: [],
                  dependsOnStepNames: [],
                })}
              >
                <Plus className="mr-2 h-4 w-4" />
                스텝 추가
              </Button>
            </div>

            {form.formState.errors.steps?.root && (
              <p className="text-sm text-destructive">
                {form.formState.errors.steps.root.message}
              </p>
            )}

            {fields.map((field, index) => {
              const allStepNames = form.watch('steps').map(s => s.name).filter(Boolean);
              const otherStepNames = allStepNames.filter((_, i) => i !== index);

              return (
                <StepEditor
                  key={field.id}
                  index={index}
                  datasets={datasets}
                  otherStepNames={otherStepNames}
                  onRemove={() => remove(index)}
                />
              );
            })}
          </div>

          <div className="flex gap-2">
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {form.formState.isSubmitting ? '생성 중...' : '생성'}
            </Button>
            <Button
              type="button"
              variant="outline"
              onClick={() => navigate('/pipelines')}
            >
              취소
            </Button>
          </div>
        </form>
      </FormProvider>
    </div>
  );
}
