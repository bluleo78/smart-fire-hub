import { KeyRound, Plus, X } from 'lucide-react';
import { useRef } from 'react';
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
  // (WD-642) 부모 페이지의 이탈 가드는 폼 값 전체를 기본값과 직접 비교해 dirty 를 판단한다.
  // 그래서 여기 setValue 들은 shouldDirty 를 줄 필요가 없고, 빠뜨려도 가드가 조용히 꺼지지 않는다.
  const { control, register, setValue, getValues, watch, formState: { errors } } = useFormContext<CreateDatasetFormData>();
  // VARCHAR 를 벗어날 때 지운 최대 길이를 칼럼별로 기억해 두는 곳 (WD-642).
  // 키는 index 가 아니라 useFieldArray 의 field.id — 앞 칼럼을 지워 index 가 당겨져도 길이가 엉뚱한 칼럼으로 옮겨가지 않는다.
  // 칼럼 삭제 때 해당 항목을 지운다. form.reset 은 새 field.id 를 발급하므로 옛 항목은 다시 쓰이지 않아 남아도 무해하다.
  const stashedVarcharLengthRef = useRef(new Map<string, number>());
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
              onClick={() => {
                // 지운 칼럼의 기억된 VARCHAR 길이도 함께 버린다 (WD-642)
                stashedVarcharLengthRef.current.delete(field.id);
                remove(index);
              }}
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
                  // (WD-642) 다만 지우기 전 값을 칼럼별로 기억해 두었다가 다시 VARCHAR 로 오면 되살린다 —
                  // 타입을 잠깐 바꿔 본 것만으로 사용자가 넣은 길이를 잃지 않게 한다.
                  // 저장 직전까지 값을 그대로 두지 않는 이유: 숨은 입력에 남은 NaN(입력 후 지움) 등이
                  // 보이지 않는 필드의 검증 오류로 제출을 막고, 폼도 계속 dirty 로 남기 때문이다.
                  const stash = stashedVarcharLengthRef.current;
                  const previousType = getValues(`columns.${index}.dataType`);
                  const leavingVarchar = previousType === 'VARCHAR' && value !== 'VARCHAR';
                  const enteringVarchar = previousType !== 'VARCHAR' && value === 'VARCHAR';
                  // VARCHAR 를 떠날 때만 기억한다 — TEXT→INTEGER 처럼 VARCHAR 아닌 타입끼리 오갈 때 기억을 지우면 되살릴 수 없다.
                  // 길이를 비운 채 떠났다면 예전 값을 되살리지 않도록 기억을 지운다.
                  if (leavingVarchar) {
                    const current = getValues(`columns.${index}.maxLength`);
                    if (typeof current === 'number' && Number.isFinite(current)) stash.set(field.id, current);
                    else stash.delete(field.id);
                  }
                  // VARCHAR 가 아니면 숨은 길이가 남지 않게 늘 비운다
                  if (value !== 'VARCHAR') setValue(`columns.${index}.maxLength`, undefined);
                  // VARCHAR 로 들어오면 기억한 길이를 되살린다 — 입력 칸이 다시 마운트되기 전에 폼 값을 넣어 두면 register 가 화면에 채운다.
                  const stashed = stash.get(field.id);
                  if (enteringVarchar && stashed !== undefined) setValue(`columns.${index}.maxLength`, stashed);
                  setValue(`columns.${index}.dataType`, value as CreateDatasetFormData['columns'][number]['dataType']);
                  // GEOMETRY 타입은 B-tree 인덱스 기반 PK 불가 — 선택 시 기본 키를 자동 해제한다 (#199)
                  if (value === 'GEOMETRY') {
                    setValue(`columns.${index}.isPrimaryKey`, false);
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
        <p role="alert" className="text-sm text-destructive">{errors.columns.message}</p>
      )}

      <Button type="button" variant="outline" onClick={addColumn} className="w-full">
        <Plus className="h-4 w-4" />
        칼럼 추가
      </Button>
    </div>
  );
}
