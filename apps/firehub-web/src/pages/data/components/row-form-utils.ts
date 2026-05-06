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
      case 'GEOMETRY': {
        // GeoJSON 유효성 검증: type 필드가 GeoJSON 표준 값인지 확인
        const GEOJSON_TYPES = [
          'Point', 'LineString', 'Polygon',
          'MultiPoint', 'MultiLineString', 'MultiPolygon',
          'GeometryCollection', 'Feature', 'FeatureCollection',
        ];
        const geoField = z.string().refine(
          (v) => {
            try {
              const parsed = JSON.parse(v);
              return GEOJSON_TYPES.includes(parsed?.type);
            } catch {
              return false;
            }
          },
          { message: 'GeoJSON 형식으로 입력하세요. 예: {"type":"Point","coordinates":[126.97,37.56]}' },
        );
        if (col.isNullable) {
          field = geoField.optional().or(z.literal(''));
        } else {
          field = geoField;
        }
        break;
      }
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
