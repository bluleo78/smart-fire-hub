import { z } from 'zod';

import type { DatasetColumnResponse } from '../../../types/dataset';

export function buildRowZodSchema(columns: DatasetColumnResponse[]) {
  const shape: Record<string, z.ZodTypeAny> = {};
  for (const col of columns) {
    if (col.isPrimaryKey) continue; // Skip auto-generated PK
    let field: z.ZodTypeAny;
    switch (col.dataType) {
      case 'INTEGER':
        field = z.coerce.number({ error: '숫자를 입력하세요.' }).int({ error: '정수를 입력하세요.' });
        if (col.isNullable) field = field.optional().or(z.literal('').transform(() => undefined));
        break;
      case 'DECIMAL':
        field = z.coerce.number({ error: '숫자를 입력하세요.' });
        if (col.isNullable) field = field.optional().or(z.literal('').transform(() => undefined));
        break;
      case 'BOOLEAN':
        field = z.boolean();
        if (col.isNullable) field = field.optional();
        break;
      case 'VARCHAR':
        field = z.string();
        if (col.maxLength) field = (field as z.ZodString).max(col.maxLength, `최대 ${col.maxLength}자`);
        if (col.isNullable) field = field.optional().or(z.literal(''));
        else field = (field as z.ZodString).min(1, '필수 입력 항목입니다.');
        break;
      case 'DATE':
      case 'TIMESTAMP':
      case 'TEXT':
      default:
        field = z.string();
        if (col.isNullable) field = field.optional().or(z.literal(''));
        else field = (field as z.ZodString).min(1, '필수 입력 항목입니다.');
        break;
    }
    shape[col.columnName] = field;
  }
  return z.object(shape);
}

export function cleanFormValues(
  values: Record<string, unknown>,
  columns: DatasetColumnResponse[],
): Record<string, unknown> {
  const cleaned: Record<string, unknown> = {};
  for (const col of columns) {
    if (col.isPrimaryKey) continue;
    const val = values[col.columnName];
    if (val === '' || val === undefined) {
      if (col.isNullable) cleaned[col.columnName] = null;
      // If not nullable, validation should have caught it
    } else {
      cleaned[col.columnName] = val;
    }
  }
  return cleaned;
}
