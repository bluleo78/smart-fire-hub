import { z } from 'zod';

import { cellTimestampToLocalInput, isFormattableDateString } from '../../../lib/formatters';
import type { DatasetColumnResponse } from '../../../types/dataset';

// ---------------------------------------------------------------------------
// (WD-98) 물리 타입 한도 — 서버 행 API 엄격 모드(ColumnPhysicalType.enforceLimitsStrict)와 같은 판정
// ---------------------------------------------------------------------------

// 원본: apps/firehub-api/src/main/java/com/smartfirehub/dataset/service/ColumnPhysicalType.java — 값을 바꾸면 함께 바꾼다.
/** DECIMAL 의 물리 타입 NUMERIC(18,6) 정수부 허용 자릿수(18 - 6). */
const DECIMAL_INTEGER_DIGITS = 12;
/** DECIMAL 의 물리 타입 NUMERIC(18,6) 소수 자릿수. */
const DECIMAL_SCALE = 6;
/** 길이 미지정 VARCHAR 의 물리 타입 길이 — VARCHAR(255). */
const DEFAULT_VARCHAR_LENGTH = 255;
/** INTEGER 의 물리 타입 BIGINT 범위. */
const BIGINT_MIN = -(2n ** 63n);
const BIGINT_MAX = 2n ** 63n - 1n;

const NUMBER_FORMAT_MSG = '숫자를 입력하세요.';

/** 부호·정수부·소수부·지수부로 된 숫자 리터럴(type=number 입력이 허용하는 `1e-7` 같은 지수 표기 포함). */
const NUMBER_LITERAL_RE = /^([+-]?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/;

interface NumberLiteral {
  negative: boolean;
  /** 앞 0·끝 0 을 뺀 유효숫자(값이 0 이면 빈 문자열). */
  significand: string;
  /** 정수부 자릿수(앞 0 제외). */
  integerDigits: number;
  /** 소수부 자릿수(끝 0 제외). */
  fractionDigits: number;
}

/**
 * 입력 원문을 숫자 리터럴로 해석한다 — 숫자로 바꾸지 않고 자릿수만 센다. `Number()` 를 거치면 `0.0000001` 이 `1e-7` 이 되고
 * 유효숫자 16자리 이상이 깎여 판정이 어긋난다. 앞 0·끝 0 은 값이 바뀌지 않으므로 자릿수에 넣지 않고(`0001.5000000` 은
 * 1자리·1자리), 지수는 자릿수 계산에만 써서 `1e999999999` 도 큰 수를 만들지 않는다. 숫자 리터럴이 아니면 null.
 */
function parseNumberLiteral(raw: unknown): NumberLiteral | null {
  if (typeof raw !== 'string' && typeof raw !== 'number') return null;
  const match = NUMBER_LITERAL_RE.exec(String(raw).trim());
  if (!match) return null;
  const [, sign, intPart = '', fracPart = '', expPart] = match;
  const digits = intPart + fracPart;
  if (digits.length === 0) return null;
  const firstNonZero = digits.search(/[1-9]/);
  if (firstNonZero < 0) return { negative: false, significand: '', integerDigits: 0, fractionDigits: 0 };
  const significantEnd = digits.replace(/0+$/, '').length;
  // digits 기준 소수점 위치. 지수가 너무 커 Number 가 Infinity 가 돼도 아래 비교는 그대로 맞다.
  const pointPos = intPart.length + (expPart ? Number(expPart) : 0);
  return {
    negative: sign === '-',
    significand: digits.slice(firstNonZero, significantEnd),
    integerDigits: Math.max(0, pointPos - firstNonZero),
    fractionDigits: Math.max(0, significantEnd - pointPos),
  };
}

/** (WD-201) 위치 칸에 넣을 수 있는 GeoJSON geometry 타입 — Feature 는 안의 geometry 를 이 목록으로 본다. */
const GEOJSON_GEOMETRY_TYPES = [
  'Point', 'LineString', 'Polygon',
  'MultiPoint', 'MultiLineString', 'MultiPolygon',
  'GeometryCollection',
];

const GEOJSON_FORMAT_ERROR = 'GeoJSON 형식으로 입력하세요. 예: {"type":"Point","coordinates":[126.97,37.56]}';

/**
 * (WD-201) GeoJSON 위치 입력값의 오류 문구를 돌려준다(문제없으면 null).
 * 서버 GeoJsonValues 와 같은 판정이다 — 클라이언트가 통과시킨 값을 서버가 영문 사유로 거부하던 불일치(Feature)를 막는다.
 */
export function geoJsonInputError(value: string): string | null {
  // 빈 값은 nullable 처리(optional/literal '')가 맡는다 — 필수 칸의 빈 값은 형식 오류로 본다.
  let parsed: unknown;
  try {
    parsed = JSON.parse(value);
  } catch {
    return GEOJSON_FORMAT_ERROR;
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return GEOJSON_FORMAT_ERROR;
  const obj = parsed as Record<string, unknown>;
  if (obj.type === 'FeatureCollection') {
    return 'FeatureCollection 은 저장할 수 없습니다. 한 칸에는 위치 하나만 들어가므로 Feature 하나나 geometry 를 입력하세요';
  }
  if (obj.type === 'Feature') {
    const geometry = obj.geometry;
    if (geometry === null || geometry === undefined) {
      return 'Feature 에 geometry 가 없습니다. 위치가 없으면 값을 비워 두세요';
    }
    if (typeof geometry !== 'object' || Array.isArray(geometry)) return GEOJSON_FORMAT_ERROR;
    return GEOJSON_GEOMETRY_TYPES.includes((geometry as Record<string, unknown>).type as string)
      ? null
      : GEOJSON_FORMAT_ERROR;
  }
  return GEOJSON_GEOMETRY_TYPES.includes(obj.type as string) ? null : GEOJSON_FORMAT_ERROR;
}

/** (WD-98) DECIMAL 입력 검사 — 형식 오류·정수부 12자리 초과·소수부 7자리 이상이면 안내 문구, 통과하면 null. */
export function decimalInputError(raw: unknown): string | null {
  const n = parseNumberLiteral(raw);
  if (!n) return NUMBER_FORMAT_MSG;
  if (n.integerDigits > DECIMAL_INTEGER_DIGITS) {
    return `정수부는 최대 ${DECIMAL_INTEGER_DIGITS}자리까지 입력할 수 있습니다.`;
  }
  if (n.fractionDigits > DECIMAL_SCALE) return `소수점 아래는 최대 ${DECIMAL_SCALE}자리까지 입력할 수 있습니다.`;
  return null;
}

/** (WD-98) INTEGER 입력 검사 — 형식 오류·정수 아님·BIGINT 범위 밖이면 안내 문구, 통과하면 null. 범위는 BigInt 로 정확히 비교한다. */
export function integerInputError(raw: unknown): string | null {
  const n = parseNumberLiteral(raw);
  if (!n) return NUMBER_FORMAT_MSG;
  if (n.fractionDigits > 0) return '정수를 입력하세요.';
  const outOfRange = `정수 범위를 벗어났습니다(허용 범위: ${BIGINT_MIN} ~ ${BIGINT_MAX}).`;
  // BIGINT 는 최대 19자리 — 그보다 길면 펼치지 않고 바로 범위 밖이다.
  if (n.integerDigits > 19) return outOfRange;
  const magnitude = n.significand + '0'.repeat(n.integerDigits - n.significand.length);
  const value = BigInt((n.negative ? '-' : '') + (magnitude || '0'));
  return value < BIGINT_MIN || value > BIGINT_MAX ? outOfRange : null;
}

/**
 * (WD-98) INTEGER·DECIMAL 폼 필드 — 입력 원문으로 검사하고, 숫자로 바꾸지 않고 앞뒤 공백만 뗀 문자열로 보낸다. JS 숫자(double)로
 * 보내면 2^53 을 넘는 정수(9007199254740993)나 유효숫자 16자리 이상의 소수가 조용히 다른 값으로 저장된다. 서버가 문자열을 정확히 파싱한다.
 *
 * @param initial 편집 폼 초기값(비교용 정규화 문자열). API 가 숫자를 JSON 숫자로 주어 JS double 로 표시되므로 저장값
 *   `999999999999.999999` 가 `1000000000000`(13자리)처럼 보인다 — 초기값과 같은(손대지 않은) 칸은 검사하지 않는다(어차피 전송 안 함, #772).
 */
function numericField(col: DatasetColumnResponse, check: (raw: unknown) => string | null, initial?: string) {
  const numeric = z
    .unknown()
    .superRefine((v, ctx) => {
      if (initial !== undefined && v !== undefined && normalizeForCompare(v, col.dataType) === initial) return;
      const message = check(v);
      if (message) ctx.addIssue({ code: 'custom', message });
    })
    .transform((v) => String(v).trim());
  // (#674) 빈 문자열은 검사 전에 undefined 로 바꾼다 — NOT NULL 이면 형식 오류(필수), NULL 허용이면 optional 로 통과해
  // cleanFormValues 가 null 로 보낸다(예전 coerce 경로에서 `Number('') === 0` 이 0 으로 저장되던 함정).
  return z.preprocess((v) => (v === '' ? undefined : v), col.isNullable ? numeric.optional() : numeric);
}

/**
 * (WD-98) VARCHAR 값이 한도 안인지 PostgreSQL 처럼 문자(코드포인트) 수로 판정한다 — `s.length`(UTF-16)는 이모지 같은
 * 보조 문자를 2자로 센다. 코드포인트 수 ≤ UTF-16 길이이므로 UTF-16 길이로 이미 한도 안이면 펼치지 않는다(대부분의 값).
 */
function withinCodePointLimit(s: string, limit: number): boolean {
  return s.length <= limit || [...s].length <= limit;
}

/** @param initialValues (WD-98) 편집 폼 초기값 — 손대지 않은 숫자 칸은 한도 검사를 건너뛴다(numericField 참고). */
export function buildRowZodSchema(
  columns: DatasetColumnResponse[],
  mode: 'add' | 'edit' = 'edit',
  initialValues?: Record<string, unknown>,
) {
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
      case 'DECIMAL': {
        const initial = initialValues ? normalizeForCompare(initialValues[col.columnName], col.dataType) : undefined;
        field = numericField(col, col.dataType === 'INTEGER' ? integerInputError : decimalInputError, initial);
        break;
      }
      case 'BOOLEAN':
        field = z.boolean();
        // NULL 허용 컬럼은 true/false 외에 null(값 없음)도 유효한 값으로 허용한다.
        // (#670) 그렇지 않으면 tri-state UI가 null을 선택해도 Zod 검증에서 걸러진다.
        if (col.isNullable) field = field.nullable().optional();
        break;
      case 'VARCHAR': {
        // (WD-98) 길이 미지정 VARCHAR 도 물리 타입은 VARCHAR(255) 라 같은 한도로 막는다. 길이는 서버·PG 와 같은
        // 문자(코드포인트) 수로 센다 — z.string().max() 는 UTF-16 단위라 이모지 5자를 VARCHAR(5) 초과로 잘못 막았다.
        const limit = col.maxLength ?? DEFAULT_VARCHAR_LENGTH;
        let str = z.string();
        if (!col.isNullable) str = str.min(1, '필수 입력 항목입니다.');
        const limited = str.refine((v) => withinCodePointLimit(v, limit), `최대 ${limit}자`);
        field = col.isNullable ? limited.optional().or(z.literal('')) : limited;
        break;
      }
      case 'GEOMETRY': {
        // (WD-201) 서버(GeoJsonValues)와 같은 규칙·문구로 검증한다. 한 칸에는 도형 하나만 들어가므로
        // geometry 타입과 Feature(서버가 안의 geometry 를 꺼내 저장)만 받고 FeatureCollection 은 거부한다.
        const geoField = z.string().superRefine((v, ctx) => {
          const message = geoJsonInputError(v);
          if (message) ctx.addIssue({ code: 'custom', message });
        });
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
 * - (#774) 브라우저 시간대에 존재하지 않는 벽시계 시각(서머타임 공백 `1988-05-08 02:30` 등)도
 *   `new Date` 가 다른 시각으로 옮기므로 같은 raw 경로로 원문 그대로 둔다(셀 표시와 같은 판정).
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
