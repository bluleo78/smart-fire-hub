import type { UseFormReturn } from 'react-hook-form';
import { Controller } from 'react-hook-form';

import { Input } from '../../../components/ui/input';
import { Label } from '../../../components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../../../components/ui/select';
import { Switch } from '../../../components/ui/switch';
import type { DatasetColumnResponse } from '../../../types/dataset';

// tri-state BOOLEAN Select에서 사용하는 문자열 표현 <-> 실제 폼 값(true/false/null) 변환
// (#670) NULL 허용 컬럼은 "값 없음(NULL)"을 명시적으로 선택할 수 있어야 한다.
function booleanToSelectValue(value: unknown): string {
  if (value === true) return 'true';
  if (value === false) return 'false';
  return 'null';
}

function selectValueToBoolean(value: string): boolean | null {
  if (value === 'true') return true;
  if (value === 'false') return false;
  return null;
}

interface RowFormFieldsProps {
  columns: DatasetColumnResponse[];
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  form: UseFormReturn<any>;
  idPrefix: string; // 'add' or 'edit' to differentiate htmlFor ids
  changedFields?: Set<string>; // optional: highlight changed fields (used by EditRowDialog)
  // (#673) 'add'에서는 PK도 입력 가능한 필드로 렌더링한다(자동생성 옵션이 없어 사용자가 직접 값을 채워야 함).
  // 'edit'(기본값)에서는 PK를 여기서 렌더링하지 않는다 — EditRowDialog가 읽기 전용으로 별도 렌더링한다.
  mode?: 'add' | 'edit';
  // (#772) date/datetime-local 입력이 담을 수 없는 기존 값을 가진 DATE/TIMESTAMP 컬럼(편집 전용).
  // 이 컬럼들은 원문 텍스트 입력으로 렌더링한다 — 손대지 않으면 전송되지 않아 값이 보존된다.
  rawTemporalColumns?: Set<string>;
}

export function RowFormFields({
  columns,
  form,
  idPrefix,
  changedFields,
  mode = 'edit',
  rawTemporalColumns,
}: RowFormFieldsProps) {
  const editableColumns = mode === 'add' ? columns : columns.filter((c) => !c.isPrimaryKey);

  return (
    <>
      {editableColumns.map((col) => {
        const label = col.displayName || col.columnName;
        const fieldError = form.formState.errors[col.columnName];
        const isChanged = changedFields?.has(col.columnName) ?? false;

        return (
          <div
            key={col.columnName}
            className="space-y-1.5"
            style={isChanged ? { borderLeft: '3px solid var(--primary)', paddingLeft: '8px' } : undefined}
          >
            <Label htmlFor={`${idPrefix}-${col.columnName}`}>
              {label}
              {/* (#673) 행 추가 시 PK 필드는 사용자가 직접 값을 정해야 함을 안내하는 뱃지 */}
              {col.isPrimaryKey && <span className="text-muted-foreground text-xs ml-1">(기본 키)</span>}
              {!col.isNullable ? (
                <span className="text-destructive ml-0.5">*</span>
              ) : (
                <span className="text-muted-foreground text-xs ml-1">(선택)</span>
              )}
            </Label>

            {col.dataType === 'BOOLEAN' && col.isNullable ? (
              // NULL 허용 BOOLEAN — 이진 Switch로는 "값 없음(NULL)" 상태를 표현할 수 없으므로
              // 3상태(예/아니오/비어있음) Select로 렌더링한다. (#670)
              <Controller
                name={col.columnName}
                control={form.control}
                render={({ field }) => (
                  <Select
                    value={booleanToSelectValue(field.value)}
                    onValueChange={(v) => field.onChange(selectValueToBoolean(v))}
                  >
                    <SelectTrigger id={`${idPrefix}-${col.columnName}`} className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="true">예</SelectItem>
                      <SelectItem value="false">아니오</SelectItem>
                      <SelectItem value="null">(비어있음)</SelectItem>
                    </SelectContent>
                  </Select>
                )}
              />
            ) : col.dataType === 'BOOLEAN' ? (
              // NOT NULL BOOLEAN — NULL 상태 자체가 불가능하므로 기존 이진 Switch 유지
              <Controller
                name={col.columnName}
                control={form.control}
                render={({ field }) => (
                  <div className="flex items-center gap-2">
                    <Switch
                      id={`${idPrefix}-${col.columnName}`}
                      checked={!!field.value}
                      onCheckedChange={field.onChange}
                    />
                    <span className="text-sm text-muted-foreground">
                      {field.value ? 'true' : 'false'}
                    </span>
                  </div>
                )}
              />
            ) : (col.dataType === 'DATE' || col.dataType === 'TIMESTAMP') &&
              rawTemporalColumns?.has(col.columnName) ? (
              // (#772) 입력란이 표현할 수 없는 값(±infinity·BC 등) — 빈칸 대신 원문을 보여 준다.
              // 바꾸지 않으면 전송되지 않고, 사용자가 지운 경우에만 NULL 이 된다.
              <>
                <Input
                  id={`${idPrefix}-${col.columnName}`}
                  type="text"
                  aria-describedby={`${idPrefix}-${col.columnName}-raw-hint`}
                  {...form.register(col.columnName)}
                />
                <p id={`${idPrefix}-${col.columnName}-raw-hint`} className="text-xs text-muted-foreground">
                  날짜 입력란으로 표시할 수 없는 값이라 원문 그대로 보여 줍니다. 바꾸지 않으면 그대로
                  유지됩니다. 새 값은 {col.dataType === 'DATE' ? 'YYYY-MM-DD' : 'YYYY-MM-DDTHH:mm:ss'} 형식으로
                  입력하세요.
                </p>
              </>
            ) : col.dataType === 'DATE' ? (
              <Input
                id={`${idPrefix}-${col.columnName}`}
                type="date"
                {...form.register(col.columnName)}
              />
            ) : col.dataType === 'TIMESTAMP' ? (
              <Input
                id={`${idPrefix}-${col.columnName}`}
                type="datetime-local"
                // (#772) 기본 step(60초)이면 초·밀리초가 있는 기존 값이 stepMismatch 로 브라우저 검증에
                // 걸려 제출이 막힌다. 'any' 로 초 단위 값을 그대로 담고 제출되게 한다.
                step="any"
                {...form.register(col.columnName)}
              />
            ) : col.dataType === 'INTEGER' ? (
              <Input
                id={`${idPrefix}-${col.columnName}`}
                type="number"
                step="1"
                {...form.register(col.columnName)}
              />
            ) : col.dataType === 'DECIMAL' ? (
              <Input
                id={`${idPrefix}-${col.columnName}`}
                type="number"
                step="any"
                {...form.register(col.columnName)}
              />
            ) : (
              <Input
                id={`${idPrefix}-${col.columnName}`}
                type="text"
                maxLength={col.dataType === 'VARCHAR' && col.maxLength ? col.maxLength : undefined}
                {...form.register(col.columnName)}
              />
            )}

            {fieldError && (
              <p className="text-sm text-destructive">{fieldError.message as string}</p>
            )}
          </div>
        );
      })}
    </>
  );
}
