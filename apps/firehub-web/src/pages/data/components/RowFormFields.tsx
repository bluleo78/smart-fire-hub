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
}

export function RowFormFields({ columns, form, idPrefix, changedFields }: RowFormFieldsProps) {
  const editableColumns = columns.filter((c) => !c.isPrimaryKey);

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
