import { useFormContext } from 'react-hook-form';
import { Card } from '../ui/card';
import { Input } from '../ui/input';
import { Label } from '../ui/label';
import { Button } from '../ui/button';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../ui/select';
import { Checkbox } from '../ui/checkbox';
import { Trash2 } from 'lucide-react';
import type { DatasetResponse } from '../../types/dataset';
import type { CreatePipelineFormData } from '../../lib/validations/pipeline';

interface StepEditorProps {
  index: number;
  datasets: DatasetResponse[];
  otherStepNames: string[];
  onRemove: () => void;
}

export function StepEditor({ index, datasets, otherStepNames, onRemove }: StepEditorProps) {
  const { register, watch, setValue, formState: { errors } } = useFormContext<CreatePipelineFormData>();

  const stepErrors = errors.steps?.[index];
  const inputDatasetIds = watch(`steps.${index}.inputDatasetIds`) || [];
  const dependsOnStepNames = watch(`steps.${index}.dependsOnStepNames`) || [];

  return (
    <Card className="p-4">
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-semibold">스텝 {index + 1}</h3>
          <Button type="button" variant="ghost" size="sm" onClick={onRemove}>
            <Trash2 className="h-4 w-4" />
          </Button>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label htmlFor={`steps.${index}.name`}>스텝 이름 *</Label>
            <Input
              id={`steps.${index}.name`}
              {...register(`steps.${index}.name`)}
              placeholder="예: extract_data"
            />
            {stepErrors?.name && (
              <p className="text-sm text-destructive">{stepErrors.name.message}</p>
            )}
          </div>

          <div className="space-y-2">
            <Label htmlFor={`steps.${index}.scriptType`}>스크립트 타입 *</Label>
            <Select
              value={watch(`steps.${index}.scriptType`)}
              onValueChange={(value) => setValue(`steps.${index}.scriptType`, value as 'SQL' | 'PYTHON')}
            >
              <SelectTrigger>
                <SelectValue placeholder="타입 선택" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="SQL">SQL</SelectItem>
                <SelectItem value="PYTHON">PYTHON</SelectItem>
              </SelectContent>
            </Select>
            {stepErrors?.scriptType && (
              <p className="text-sm text-destructive">{stepErrors.scriptType.message}</p>
            )}
          </div>
        </div>

        <div className="space-y-2">
          <Label htmlFor={`steps.${index}.description`}>설명</Label>
          <Input
            id={`steps.${index}.description`}
            {...register(`steps.${index}.description`)}
            placeholder="스텝 설명"
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor={`steps.${index}.scriptContent`}>스크립트 *</Label>
          <textarea
            id={`steps.${index}.scriptContent`}
            {...register(`steps.${index}.scriptContent`)}
            placeholder="스크립트를 입력하세요..."
            className="w-full min-h-[200px] rounded-md border border-input bg-background px-3 py-2 text-sm font-mono focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          />
          {stepErrors?.scriptContent && (
            <p className="text-sm text-destructive">{stepErrors.scriptContent.message}</p>
          )}
        </div>

        <div className="space-y-2">
          <Label htmlFor={`steps.${index}.outputDatasetId`}>출력 데이터셋 *</Label>
          <Select
            value={watch(`steps.${index}.outputDatasetId`)?.toString() || ''}
            onValueChange={(value) => setValue(`steps.${index}.outputDatasetId`, Number(value))}
          >
            <SelectTrigger>
              <SelectValue placeholder="데이터셋 선택" />
            </SelectTrigger>
            <SelectContent>
              {datasets.map((ds) => (
                <SelectItem key={ds.id} value={ds.id.toString()}>
                  {ds.name} ({ds.tableName})
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {stepErrors?.outputDatasetId && (
            <p className="text-sm text-destructive">{stepErrors.outputDatasetId.message}</p>
          )}
        </div>

        <div className="space-y-2">
          <Label>입력 데이터셋</Label>
          <div className="rounded-md border p-3 space-y-2 max-h-40 overflow-y-auto">
            {datasets.map((ds) => (
              <div key={ds.id} className="flex items-center space-x-2">
                <Checkbox
                  id={`steps.${index}.inputDatasets.${ds.id}`}
                  checked={inputDatasetIds.includes(ds.id)}
                  onCheckedChange={(checked) => {
                    if (checked) {
                      setValue(`steps.${index}.inputDatasetIds`, [...inputDatasetIds, ds.id]);
                    } else {
                      setValue(`steps.${index}.inputDatasetIds`, inputDatasetIds.filter(id => id !== ds.id));
                    }
                  }}
                />
                <label
                  htmlFor={`steps.${index}.inputDatasets.${ds.id}`}
                  className="text-sm cursor-pointer"
                >
                  {ds.name} ({ds.tableName})
                </label>
              </div>
            ))}
          </div>
        </div>

        {otherStepNames.length > 0 && (
          <div className="space-y-2">
            <Label>의존 스텝</Label>
            <div className="rounded-md border p-3 space-y-2 max-h-40 overflow-y-auto">
              {otherStepNames.map((stepName) => (
                <div key={stepName} className="flex items-center space-x-2">
                  <Checkbox
                    id={`steps.${index}.depends.${stepName}`}
                    checked={dependsOnStepNames.includes(stepName)}
                    onCheckedChange={(checked) => {
                      if (checked) {
                        setValue(`steps.${index}.dependsOnStepNames`, [...dependsOnStepNames, stepName]);
                      } else {
                        setValue(`steps.${index}.dependsOnStepNames`, dependsOnStepNames.filter(n => n !== stepName));
                      }
                    }}
                  />
                  <label
                    htmlFor={`steps.${index}.depends.${stepName}`}
                    className="text-sm cursor-pointer"
                  >
                    {stepName}
                  </label>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </Card>
  );
}
