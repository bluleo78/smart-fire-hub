import { useFieldArray, useFormContext } from 'react-hook-form';
import { Button } from '../../../components/ui/button';
import { Input } from '../../../components/ui/input';
import { Label } from '../../../components/ui/label';
import { Card } from '../../../components/ui/card';
import { Checkbox } from '../../../components/ui/checkbox';
import { ColumnTypeSelect } from './ColumnTypeSelect';
import { KeyRound, Plus, X } from 'lucide-react';
import type { CreateDatasetFormData } from '../../../lib/validations/dataset';

export function SchemaBuilder() {
  const { control, register, setValue, watch, formState: { errors } } = useFormContext<CreateDatasetFormData>();
  const { fields, append, remove } = useFieldArray({
    control,
    name: 'columns',
  });

  const addColumn = () => {
    append({
      columnName: '',
      displayName: '',
      dataType: 'TEXT',
      isNullable: true,
      isIndexed: false,
      isPrimaryKey: false,
      description: '',
    });
  };

  return (
    <div className="space-y-4">
      {fields.map((field, index) => (
        <Card key={field.id} className="p-4">
          <div className="flex items-start justify-between mb-3">
            <h4 className="text-sm font-medium">칼럼 {index + 1}</h4>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => remove(index)}
              disabled={fields.length === 1}
            >
              <X className="h-4 w-4" />
            </Button>
          </div>

          <div className="grid gap-4">
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor={`columns.${index}.columnName`}>칼럼명 *</Label>
                <Input
                  id={`columns.${index}.columnName`}
                  {...register(`columns.${index}.columnName`)}
                  placeholder="예: user_id"
                />
                {errors.columns?.[index]?.columnName && (
                  <p className="text-sm text-destructive">
                    {errors.columns[index]?.columnName?.message}
                  </p>
                )}
              </div>

              <div className="space-y-2">
                <Label htmlFor={`columns.${index}.displayName`}>표시명</Label>
                <Input
                  id={`columns.${index}.displayName`}
                  {...register(`columns.${index}.displayName`)}
                  placeholder="예: 사용자 ID"
                />
              </div>
            </div>

            <div className="space-y-2">
              <Label htmlFor={`columns.${index}.dataType`}>데이터 타입 *</Label>
              <ColumnTypeSelect
                value={watch(`columns.${index}.dataType`)}
                onChange={(value) => {
                  setValue(`columns.${index}.dataType`, value as CreateDatasetFormData['columns'][number]['dataType']);
                }}
              />
              {errors.columns?.[index]?.dataType && (
                <p className="text-sm text-destructive">
                  {errors.columns[index]?.dataType?.message}
                </p>
              )}
            </div>

            {watch(`columns.${index}.dataType`) === 'VARCHAR' && (
              <div className="space-y-2">
                <Label htmlFor={`columns.${index}.maxLength`}>최대 길이 *</Label>
                <Input
                  id={`columns.${index}.maxLength`}
                  type="number"
                  min={1}
                  max={10000}
                  {...register(`columns.${index}.maxLength`, { valueAsNumber: true })}
                  placeholder="예: 255"
                />
                {errors.columns?.[index]?.maxLength && (
                  <p className="text-sm text-destructive">
                    {errors.columns[index]?.maxLength?.message}
                  </p>
                )}
              </div>
            )}

            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-4">
                <div className="flex items-center space-x-2">
                  <Checkbox
                    id={`columns.${index}.isPrimaryKey`}
                    checked={watch(`columns.${index}.isPrimaryKey`)}
                    onCheckedChange={(checked) => {
                      setValue(`columns.${index}.isPrimaryKey`, checked as boolean);
                      if (checked) {
                        setValue(`columns.${index}.isNullable`, false);
                      }
                    }}
                  />
                  <Label
                    htmlFor={`columns.${index}.isPrimaryKey`}
                    className="text-sm font-normal cursor-pointer flex items-center gap-1"
                  >
                    <KeyRound className="h-3 w-3" />
                    기본 키
                  </Label>
                </div>

                <div className="flex items-center space-x-2">
                  <Checkbox
                    id={`columns.${index}.isNullable`}
                    checked={watch(`columns.${index}.isNullable`)}
                    disabled={watch(`columns.${index}.isPrimaryKey`)}
                    onCheckedChange={(checked) => {
                      setValue(`columns.${index}.isNullable`, checked as boolean);
                    }}
                  />
                  <Label
                    htmlFor={`columns.${index}.isNullable`}
                    className="text-sm font-normal cursor-pointer"
                  >
                    NULL 허용
                  </Label>
                </div>

                <div className="flex items-center space-x-2">
                  <Checkbox
                    id={`columns.${index}.isIndexed`}
                    checked={watch(`columns.${index}.isIndexed`)}
                    onCheckedChange={(checked) => {
                      setValue(`columns.${index}.isIndexed`, checked as boolean);
                    }}
                  />
                  <Label
                    htmlFor={`columns.${index}.isIndexed`}
                    className="text-sm font-normal cursor-pointer"
                  >
                    인덱스 생성
                  </Label>
                </div>
              </div>
            </div>

            <div className="space-y-2">
              <Label htmlFor={`columns.${index}.description`}>설명</Label>
              <Input
                id={`columns.${index}.description`}
                {...register(`columns.${index}.description`)}
                placeholder="칼럼 설명"
              />
            </div>
          </div>
        </Card>
      ))}

      {errors.columns && typeof errors.columns.message === 'string' && (
        <p className="text-sm text-destructive">{errors.columns.message}</p>
      )}

      <Button type="button" variant="outline" onClick={addColumn} className="w-full">
        <Plus className="mr-2 h-4 w-4" />
        칼럼 추가
      </Button>
    </div>
  );
}
