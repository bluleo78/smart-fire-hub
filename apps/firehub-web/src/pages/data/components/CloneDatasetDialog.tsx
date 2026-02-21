import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useNavigate } from 'react-router-dom';
import { useCloneDataset } from '../../../hooks/queries/useDatasets';
import type { DatasetDetailResponse } from '../../../types/dataset';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '../../../components/ui/dialog';
import { Button } from '../../../components/ui/button';
import { Input } from '../../../components/ui/input';
import { Label } from '../../../components/ui/label';
import { Switch } from '../../../components/ui/switch';
import { Textarea } from '../../../components/ui/textarea';
import { Loader2 } from 'lucide-react';
import { toast } from 'sonner';
import { handleApiError } from '../../../lib/api-error';

interface CloneDatasetDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  dataset: DatasetDetailResponse;
}

const cloneSchema = z.object({
  name: z.string().min(1, '데이터셋 이름을 입력하세요.'),
  tableName: z
    .string()
    .min(1, '테이블 이름을 입력하세요.')
    .regex(/^[a-z][a-z0-9_]*$/, '영소문자로 시작하며 영소문자, 숫자, _만 허용됩니다.'),
  description: z.string().optional(),
  includeData: z.boolean(),
  includeTags: z.boolean(),
});

type CloneFormData = z.infer<typeof cloneSchema>;

export function CloneDatasetDialog({ open, onOpenChange, dataset }: CloneDatasetDialogProps) {
  const navigate = useNavigate();
  const cloneDataset = useCloneDataset();

  const form = useForm<CloneFormData>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(cloneSchema) as any,
    defaultValues: {
      name: `${dataset.name}_copy`,
      tableName: `${dataset.tableName}_copy`,
      description: dataset.description || '',
      includeData: false,
      includeTags: true,
    },
  });

  const onSubmit = async (data: CloneFormData) => {
    try {
      const result = await cloneDataset.mutateAsync({
        datasetId: dataset.id,
        data: {
          name: data.name,
          tableName: data.tableName,
          description: data.description || undefined,
          includeData: data.includeData,
          includeTags: data.includeTags,
        },
      });
      toast.success('데이터셋이 복제되었습니다.');
      onOpenChange(false);
      navigate(`/data/datasets/${result.id}`);
    } catch (error) {
      handleApiError(error, '데이터셋 복제에 실패했습니다.');
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>데이터셋 복제</DialogTitle>
        </DialogHeader>

        {/* Source info */}
        <div className="rounded-md bg-muted/50 px-3 py-2 text-sm text-muted-foreground">
          원본: <span className="font-medium text-foreground">{dataset.name}</span>{' '}
          ({dataset.columns.length}열, {dataset.rowCount.toLocaleString()}행)
        </div>

        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="clone-name">데이터셋 이름 *</Label>
            <Input
              id="clone-name"
              {...form.register('name')}
            />
            {form.formState.errors.name && (
              <p className="text-sm text-destructive">{form.formState.errors.name.message}</p>
            )}
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="clone-tableName">테이블 이름 *</Label>
            <Input
              id="clone-tableName"
              {...form.register('tableName')}
            />
            <p className="text-xs text-muted-foreground">
              &#9432; 영소문자, 숫자, _ 만 허용
            </p>
            {form.formState.errors.tableName && (
              <p className="text-sm text-destructive">{form.formState.errors.tableName.message}</p>
            )}
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="clone-description">설명</Label>
            <Textarea
              id="clone-description"
              {...form.register('description')}
              rows={3}
            />
          </div>

          <div className="space-y-3">
            <Controller
              name="includeData"
              control={form.control}
              render={({ field }) => (
                <div className="flex items-center justify-between">
                  <div>
                    <Label className="text-sm">데이터 포함</Label>
                    <p className="text-xs text-muted-foreground">
                      {dataset.rowCount.toLocaleString()}행의 데이터를 복제
                    </p>
                  </div>
                  <Switch checked={field.value} onCheckedChange={field.onChange} />
                </div>
              )}
            />

            <Controller
              name="includeTags"
              control={form.control}
              render={({ field }) => (
                <div className="flex items-center justify-between">
                  <div>
                    <Label className="text-sm">태그 포함</Label>
                    {dataset.tags.length > 0 && (
                      <p className="text-xs text-muted-foreground">
                        {dataset.tags.join(', ')}
                      </p>
                    )}
                  </div>
                  <Switch checked={field.value} onCheckedChange={field.onChange} />
                </div>
              )}
            />
          </div>

          <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
            {form.formState.isSubmitting ? (
              <>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                복제 중...
              </>
            ) : (
              '복제'
            )}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}
