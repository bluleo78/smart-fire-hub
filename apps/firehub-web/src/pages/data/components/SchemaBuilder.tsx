import { KeyRound, Plus, X } from 'lucide-react';
import { useFieldArray, useFormContext } from 'react-hook-form';

import { Button } from '../../../components/ui/button';
import { Card } from '../../../components/ui/card';
import { Checkbox } from '../../../components/ui/checkbox';
import { Input } from '../../../components/ui/input';
import { Label } from '../../../components/ui/label';
import { useDataStoreFeatures } from '../../../hooks/queries/useDataStoreCapabilities';
import type { CreateDatasetFormData } from '../../../lib/validations/dataset';
import { ColumnTypeSelect } from './ColumnTypeSelect';

export function SchemaBuilder() {
  // 저장소 GEOMETRY 지원 여부 — 조회 전·실패 시 지원으로 취급한다. (WD-80)
  // VARCHAR 길이 입력 상한도 저장소를 따른다(WD-636, PG 10000·Tibero 4000) — 검증은 부모 폼의 zod 스키마가 같은 값으로 한다.
  const { geometrySupported, maxVarcharLength } = useDataStoreFeatures();
  // (WD-577) 타입 선택·체크박스는 setValue 로 바뀌는데, 기본 setValue 는 isDirty 를 갱신하지 않는다.
  // 부모 페이지의 이탈 가드가 칼럼 편집도 미저장 입력으로 인식하도록 모든 setValue 에 shouldDirty 를 준다.
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
      // 페이지 기본 칼럼과 같은 모양(maxLength 키 포함)으로 추가한다 (WD-577).
      maxLength: undefined,
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
              // 아이콘(×)뿐인 버튼이라 접근 이름이 없다 — 카드 제목 「칼럼 N」과 같은 번호로 이름을 준다(WD-512)
              aria-label={`칼럼 ${index + 1} 삭제`}
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
                  // 오류 문구를 입력과 연결해 스크린 리더가 어느 칼럼의 오류인지 읽게 한다(WD-515)
                  aria-invalid={!!errors.columns?.[index]?.columnName}
                  aria-describedby={errors.columns?.[index]?.columnName ? `columns.${index}.columnName-error` : undefined}
                />
                {errors.columns?.[index]?.columnName && (
                  <p id={`columns.${index}.columnName-error`} role="alert" className="text-sm text-destructive">
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
                id={`columns.${index}.dataType`}
                value={watch(`columns.${index}.dataType`)}
                // 저장소가 GEOMETRY 를 지원하지 않으면(Tibero) 목록에서 숨긴다. 조회 전·실패 시엔 그대로 둔다. (WD-80)
                hiddenOptions={geometrySupported ? undefined : ['GEOMETRY']}
                onChange={(value) => {
                  // (WD-577) maxLength 입력은 VARCHAR 일 때만 보이지만 숨겨진 뒤에도 등록 값이 남는다.
                  // 그대로 두면 폼이 계속 dirty 이고 낡은 길이가 제출되므로, VARCHAR 가 아니면 기본값(undefined)으로 되돌린다.
                  // dataType 보다 먼저 지워야 이어지는 shouldDirty 재계산이 지워진 값으로 isDirty 를 판단한다.
                  if (value !== 'VARCHAR') {
                    setValue(`columns.${index}.maxLength`, undefined, { shouldDirty: true });
                  }
                  setValue(`columns.${index}.dataType`, value as CreateDatasetFormData['columns'][number]['dataType'], { shouldDirty: true });
                  // GEOMETRY 타입은 B-tree 인덱스 기반 PK 불가 — 선택 시 기본 키를 자동 해제한다 (#199)
                  if (value === 'GEOMETRY') {
                    setValue(`columns.${index}.isPrimaryKey`, false, { shouldDirty: true });
                  }
                }}
              />
              {errors.columns?.[index]?.dataType && (
                <p role="alert" className="text-sm text-destructive">
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
                  max={maxVarcharLength}
                  {...register(`columns.${index}.maxLength`, { valueAsNumber: true })}
                  placeholder="예: 255"
                  aria-invalid={!!errors.columns?.[index]?.maxLength}
                  aria-describedby={errors.columns?.[index]?.maxLength ? `columns.${index}.maxLength-error` : undefined}
                />
                {errors.columns?.[index]?.maxLength && (
                  <p id={`columns.${index}.maxLength-error`} role="alert" className="text-sm text-destructive">
                    {errors.columns[index]?.maxLength?.message}
                  </p>
                )}
              </div>
            )}

            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-4">
                <div className="flex items-center space-x-2">
                  {/* GEOMETRY 타입은 B-tree UNIQUE INDEX 생성 불가 → PK 설정 비활성화 (#199) */}
                  <Checkbox
                    id={`columns.${index}.isPrimaryKey`}
                    checked={watch(`columns.${index}.isPrimaryKey`)}
                    disabled={watch(`columns.${index}.dataType`) === 'GEOMETRY'}
                    onCheckedChange={(checked) => {
                      setValue(`columns.${index}.isPrimaryKey`, checked as boolean, { shouldDirty: true });
                      if (checked) {
                        setValue(`columns.${index}.isNullable`, false, { shouldDirty: true });
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
                      setValue(`columns.${index}.isNullable`, checked as boolean, { shouldDirty: true });
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
                      setValue(`columns.${index}.isIndexed`, checked as boolean, { shouldDirty: true });
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
        <p role="alert" className="text-sm text-destructive">{errors.columns.message}</p>
      )}

      <Button type="button" variant="outline" onClick={addColumn} className="w-full">
        <Plus className="h-4 w-4" />
        칼럼 추가
      </Button>
    </div>
  );
}
