import { z } from 'zod';

import type { DatasetColumnResponse } from '../../../types/dataset';

export function buildRowZodSchema(columns: DatasetColumnResponse[], mode: 'add' | 'edit' = 'edit') {
  const shape: Record<string, z.ZodTypeAny> = {};
  for (const col of columns) {
    // (#673) 이 앱의 테이블 생성 폼에는 auto-increment 옵션이 없어 PK는 항상 사용자가
    // 직접 값을 입력해야 한다. "행 추가"(add)에서는 PK도 다른 필수 컬럼처럼 검증 대상에
    // 포함시킨다. "행 편집"(edit)에서는 PK가 불변이라 EditRowDialog가 읽기 전용으로
    // 별도 렌더링하고 값은 passthrough()로 유지하므로 여기서는 계속 건너뛴다.
    if (col.isPrimaryKey && mode === 'edit') continue;
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
        // NULL 허용 컬럼은 true/false 외에 null(값 없음)도 유효한 값으로 허용한다.
        // (#670) 그렇지 않으면 tri-state UI가 null을 선택해도 Zod 검증에서 걸러진다.
        if (col.isNullable) field = field.nullable().optional();
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
  // (#672) PK 컬럼은 shape에 없는 키다. z.object 기본(strip) 모드는 스키마에 없는 키를
  // 파싱 결과에서 제거하므로, EditRowDialog가 폼 상태에 실어 보낸 PK 값(읽기 전용)이
  // zodResolver를 거치며 사라져 버린다. passthrough()로 알 수 없는 키를 그대로 통과시켜
  // PK 값이 onSubmit 데이터에 남도록 한다.
  return z.object(shape).passthrough();
}

export function cleanFormValues(
  values: Record<string, unknown>,
  columns: DatasetColumnResponse[],
): Record<string, unknown> {
  const cleaned: Record<string, unknown> = {};
  for (const col of columns) {
    // (#672) PK 컬럼은 읽기 전용이라 폼에서 직접 편집되지 않지만, EditRowDialog가 defaultValues에
    // 기존 값을 그대로 실어두므로 values에 존재하면 그대로 페이로드에 포함해 백엔드 저장을 돕는다.
    // (AddRowDialog는 애초에 PK 컬럼을 defaultValues에 넣지 않으므로 값이 없어 아래에서 자연히 제외된다.)
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
