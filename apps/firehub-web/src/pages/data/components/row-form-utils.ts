import { z } from 'zod';

import { cellTimestampToLocalInput, isFormattableDateString } from '../../../lib/formatters';
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
        // (#674) 이전에는 `.optional().or(z.literal('').transform(() => undefined))` 형태였는데
        // union의 첫 대안(coerce.number())이 먼저 시도되고 `Number('') === 0`이라 빈 문자열이
        // 0으로 "성공" 파싱돼 버렸다. 이 falsy-0 함정은 NOT NULL 컬럼에도 동일하게 적용되어
        // 필수 숫자 필드를 비워도 검증을 통과해 0이 저장되는 문제가 있었다(발견 당시 함께 수정).
        // z.preprocess로 빈 문자열을 coerce 이전에 undefined로 바꿔 원천 차단한다 — NOT NULL이면
        // undefined가 coerce.number()에서 NaN이 되어 그대로 검증 실패(필수 처리), NULL 허용이면
        // .optional()로 undefined를 통과시켜 cleanFormValues가 null로 변환하게 한다.
        field = z.preprocess((v) => (v === '' ? undefined : v), col.isNullable ? field.optional() : field);
        break;
      case 'DECIMAL':
        field = z.coerce.number({ error: '숫자를 입력하세요.' });
        // (#674) INTEGER와 동일한 이유로 preprocess 적용 (NOT NULL 포함).
        field = z.preprocess((v) => (v === '' ? undefined : v), col.isNullable ? field.optional() : field);
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

// ---------------------------------------------------------------------------
// (#772) 행 편집 — 날짜·시각 값 변환과 "바뀐 칸만 전송"
// ---------------------------------------------------------------------------

const DATE_ONLY_RE = /^\d{4}-\d{2}-\d{2}$/;
const HAS_TIME_RE = /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}/;

/** 편집 폼 기본값 변환 결과. raw=true 면 날짜 입력란이 담을 수 없어 원문 텍스트로 보여 줘야 하는 값이다. */
export interface TemporalFormValue {
  value: string;
  raw: boolean;
}

/**
 * (#772) 데이터 탭 API 가 준 DATE/TIMESTAMP 값을 편집 폼 입력값으로 바꾼다.
 *
 * - DATE: `YYYY-MM-DD` 이고 실재하는 날짜면 `type=date` 입력에 그대로 담는다.
 * - TIMESTAMP: API 는 정상 값을 UTC ISO(`2020-01-03T01:15:30.500+00:00`)로 주므로, 데이터 탭 셀
 *   (formatCellValue 의 `new Date(str)`)과 같은 브라우저 시간대 벽시계 시각
 *   `YYYY-MM-DDTHH:mm:ss[.SSS]` 로 바꿔 `type=datetime-local` 에 담는다. 이 형식은 서버
 *   `LocalDateTime.parse` 가 그대로 받는다. ※ 브라우저 시간대 = 서버 JVM 시간대 가정은 데이터 탭
 *   셀 표시·행 추가 경로와 같다.
 * - 그 밖의 값(±infinity, BC, 5자리 연도 등 #769 이후 PG 원문으로 오는 값)은 입력란이 거부해
 *   빈칸이 되고, 빈칸이 저장 시 NULL 로 바뀌어 기존 값을 덮었다. 그래서 raw=true 로 표시해
 *   원문 텍스트 입력으로 보여 준다(손대지 않으면 전송하지 않아 그대로 보존된다).
 */
export function toTemporalFormValue(value: string, dataType: 'DATE' | 'TIMESTAMP'): TemporalFormValue {
  if (dataType === 'DATE') {
    const ok = DATE_ONLY_RE.test(value) && isFormattableDateString(value);
    return { value, raw: !ok };
  }
  if (!HAS_TIME_RE.test(value)) return { value, raw: true };
  const local = cellTimestampToLocalInput(value);
  return local === null ? { value, raw: true } : { value: local, raw: false };
}

const LOCAL_DATETIME_RE = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})(?::(\d{2})(?:\.(\d+))?)?$/;

/**
 * 비교용 정규화 — null/undefined 는 빈 문자열로 본다(텍스트 입력의 "값 없음"과 같은 상태).
 * TIMESTAMP 는 브라우저가 datetime-local 값을 자체 규칙으로 정규화한다(`10:15:30.500` → `10:15:30.5`,
 * 0초면 초 생략). 칸을 지나가기만 해도 blur 시 DOM 값이 폼에 다시 읽히므로, 글자 비교 대신 같은 시각이면
 * 같은 값으로 보도록 `YYYY-MM-DDTHH:mm:ss.SSS` 로 맞춘다 (#772).
 */
function normalizeForCompare(v: unknown, dataType: string): string {
  if (v === null || v === undefined) return '';
  const str = String(v);
  if (dataType !== 'TIMESTAMP') return str;
  const m = LOCAL_DATETIME_RE.exec(str);
  if (!m) return str;
  const [, minute, sec = '00', frac = ''] = m;
  return `${minute}:${sec}.${frac.padEnd(3, '0').slice(0, 3)}`;
}

/**
 * (#772) 폼 기본값 대비 실제로 바뀐 컬럼 이름 집합.
 * 변경 표시(하이라이트)와 PUT 페이로드 선별이 같은 판정을 쓰도록 한 곳에 둔다.
 * 비교 대상은 zod 변환 전의 원시 폼 값(getValues/watch)이어야 한다.
 */
export function computeChangedFields(
  columns: DatasetColumnResponse[],
  defaults: Record<string, unknown>,
  values: Record<string, unknown>,
): Set<string> {
  const changed = new Set<string>();
  for (const col of columns) {
    const before = normalizeForCompare(defaults[col.columnName], col.dataType);
    if (before !== normalizeForCompare(values[col.columnName], col.dataType)) {
      changed.add(col.columnName);
    }
  }
  return changed;
}

/**
 * (#772) 바뀐 컬럼만 남긴 PUT 페이로드. 서버 행 수정은 부분 업데이트라(#672) 요청에 없는 컬럼은
 * DB 값을 그대로 유지한다 — 사용자가 손대지 않은 칸이 폼 변환·입력란 제약 때문에 다른 값(NULL 등)으로
 * 덮이는 일을 구조적으로 막는다.
 */
export function pickChangedValues(
  cleaned: Record<string, unknown>,
  changed: Set<string>,
): Record<string, unknown> {
  const picked: Record<string, unknown> = {};
  for (const [key, val] of Object.entries(cleaned)) {
    if (changed.has(key)) picked[key] = val;
  }
  return picked;
}
