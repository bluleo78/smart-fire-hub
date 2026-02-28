import type { UseFormReturn } from 'react-hook-form';
import { Controller } from 'react-hook-form';

import { Input } from '../../../components/ui/input';
import { Label } from '../../../components/ui/label';
import { Switch } from '../../../components/ui/switch';
import type { DatasetColumnResponse } from '../../../types/dataset';

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
            style={isChanged ? { borderLeft: '3px solid hsl(var(--primary))', paddingLeft: '8px' } : undefined}
          >
            <Label htmlFor={`${idPrefix}-${col.columnName}`}>
              {label}
              {!col.isNullable ? (
                <span className="text-destructive ml-0.5">*</span>
              ) : (
                <span className="text-muted-foreground text-xs ml-1">(선택)</span>
              )}
            </Label>

            {col.dataType === 'BOOLEAN' ? (
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
