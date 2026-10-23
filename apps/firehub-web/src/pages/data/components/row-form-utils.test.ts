/**
 * (#772) 행 편집 — 날짜·시각 폼 변환과 "바뀐 칸만 전송" 판정 단위 테스트.
 * vitest.config 의 TZ=Asia/Seoul 기준(데이터 탭 셀 표시와 같은 브라우저 시간대 해석).
 */
import { describe, expect, it } from 'vitest';

import type { DatasetColumnResponse } from '../../../types/dataset';
import {
  buildRowZodSchema,
  computeChangedFields,
  decimalInputError,
  geoJsonInputError,
  integerInputError,
  pickChangedValues,
  toTemporalFormValue,
} from './row-form-utils';

const col = (columnName: string, dataType: string) =>
  ({ columnName, dataType, isNullable: true, isPrimaryKey: false }) as DatasetColumnResponse;

describe('toTemporalFormValue', () => {
  it('API 의 UTC ISO TIMESTAMP 를 브라우저 시간대 datetime-local 값으로 바꾼다', () => {
    expect(toTemporalFormValue('2020-01-03T01:15:30.500+00:00', 'TIMESTAMP')).toEqual({
      value: '2020-01-03T10:15:30.500',
      raw: false,
    });
    expect(toTemporalFormValue('2020-02-02T00:00:00.000+00:00', 'TIMESTAMP')).toEqual({
      value: '2020-02-02T09:00:00',
      raw: false,
    });
  });

  it('정상 DATE 는 그대로, 입력란이 담을 수 없는 값은 원문(raw)으로 둔다', () => {
    expect(toTemporalFormValue('2020-01-03', 'DATE')).toEqual({ value: '2020-01-03', raw: false });
    for (const v of ['infinity', '-infinity', '0044-03-15 BC', '10000-01-01', '2024-02-30']) {
      expect(toTemporalFormValue(v, 'DATE')).toEqual({ value: v, raw: true });
    }
    for (const v of ['infinity', '0044-03-15 10:00:00 BC', '10000-01-01T00:00:00', '2020-01-03']) {
      expect(toTemporalFormValue(v, 'TIMESTAMP')).toEqual({ value: v, raw: true });
    }
  });

  it('(#774) 브라우저 시간대에 없는 벽시계 시각(DST 공백)은 다른 시각으로 바꾸지 않고 원문(raw)으로 둔다', () => {
    for (const v of ['1988-05-08 02:30:00', '1987-05-10 02:30', '1908-04-01 00:00:00']) {
      expect(toTemporalFormValue(v, 'TIMESTAMP')).toEqual({ value: v, raw: true });
    }
    // 실재하는 오프셋 없는 벽시계 값은 그대로 datetime-local 값이 된다
    expect(toTemporalFormValue('1988-05-08 03:00:00', 'TIMESTAMP')).toEqual({
      value: '1988-05-08T03:00:00',
      raw: false,
    });
  });
});

describe('computeChangedFields / pickChangedValues', () => {
  const columns = [col('label', 'TEXT'), col('d', 'DATE'), col('ts', 'TIMESTAMP'), col('flag', 'BOOLEAN')];
  const defaults = { label: 'a', d: 'infinity', ts: '2020-01-03T10:15:30.500', flag: null };

  it('브라우저가 정규화한 같은 시각(.5·초 생략)은 바뀐 것으로 보지 않는다', () => {
    const values = { label: 'a', d: 'infinity', ts: '2020-01-03T10:15:30.5', flag: null };
    expect(computeChangedFields(columns, defaults, values)).toEqual(new Set());
    expect(
      computeChangedFields(columns, { ...defaults, ts: '2020-02-02T09:00:00' }, { ...values, ts: '2020-02-02T09:00' }),
    ).toEqual(new Set());
  });

  it('실제로 바뀐 칸(지운 칸 포함)만 골라 페이로드에 남긴다', () => {
    const values = { label: 'b', d: '', ts: '2020-01-03T10:15:31', flag: false };
    const changed = computeChangedFields(columns, defaults, values);
    expect(changed).toEqual(new Set(['label', 'd', 'ts', 'flag']));
    expect(pickChangedValues({ label: 'b', d: null, ts: 'x', flag: false }, new Set(['d']))).toEqual({ d: null });
  });
});

// ---------------------------------------------------------------------------
// (WD-98) 소수 자릿수·VARCHAR 문자 수 — 서버 물리 타입 한도(NUMERIC(18,6)·VARCHAR(n), 기본 255)와 같은 판정
// ---------------------------------------------------------------------------

const INTEGER_PART_MSG = '정수부는 최대 12자리까지 입력할 수 있습니다.';
const FRACTION_MSG = '소수점 아래는 최대 6자리까지 입력할 수 있습니다.';

describe('decimalInputError', () => {
  it('정수부 13자리 이상은 정수부 안내, 12자리는 통과', () => {
    expect(decimalInputError('12345678901234')).toBe(INTEGER_PART_MSG);
    expect(decimalInputError('-1234567890123')).toBe(INTEGER_PART_MSG);
    expect(decimalInputError('1e12')).toBe(INTEGER_PART_MSG); // 지수 표기 1,000,000,000,000(13자리)
    expect(decimalInputError('1e999999999')).toBe(INTEGER_PART_MSG);
    expect(decimalInputError('123456789012')).toBeNull();
    expect(decimalInputError('-999999999999.999999')).toBeNull();
  });

  it('소수부 7자리 이상은 소수 안내, 6자리는 통과', () => {
    expect(decimalInputError('0.0000001')).toBe(FRACTION_MSG);
    expect(decimalInputError('1e-7')).toBe(FRACTION_MSG);
    expect(decimalInputError(1e-7)).toBe(FRACTION_MSG); // 숫자로 들어온 경우
    expect(decimalInputError('1.1234567')).toBe(FRACTION_MSG);
    expect(decimalInputError('0.000001')).toBeNull();
    expect(decimalInputError('12.123456')).toBeNull();
  });

  it('값이 바뀌지 않는 앞 0·끝 0 은 자릿수에 넣지 않는다', () => {
    expect(decimalInputError('0000000000001')).toBeNull();
    expect(decimalInputError('1.5000000')).toBeNull();
    expect(decimalInputError('0.0000000')).toBeNull();
    expect(decimalInputError('0e99')).toBeNull();
  });

  it('숫자 형식이 아니면 형식 안내', () => {
    for (const v of ['abc', '', '.', '1,000', undefined]) expect(decimalInputError(v)).toBe('숫자를 입력하세요.');
  });
});

describe('integerInputError', () => {
  const RANGE_MSG = '정수 범위를 벗어났습니다(허용 범위: -9223372036854775808 ~ 9223372036854775807).';

  it('BIGINT 경계까지 정확히 통과 — 2^53 을 넘어도 double 로 비교하지 않는다', () => {
    for (const v of ['9223372036854775807', '-9223372036854775808', '9007199254740993', '5.0', '1e3', '0']) {
      expect(integerInputError(v)).toBeNull();
    }
  });

  it('범위 밖·정수 아님·형식 오류를 구분해 안내', () => {
    expect(integerInputError('9223372036854775808')).toBe(RANGE_MSG);
    expect(integerInputError('-9223372036854775809')).toBe(RANGE_MSG);
    expect(integerInputError('1e999999999')).toBe(RANGE_MSG);
    expect(integerInputError('1.9')).toBe('정수를 입력하세요.');
    expect(integerInputError('abc')).toBe('숫자를 입력하세요.');
  });
});

describe('buildRowZodSchema — 물리 타입 한도 (WD-98)', () => {
  const issues = (schema: ReturnType<typeof buildRowZodSchema>, value: Record<string, unknown>) => {
    const result = schema.safeParse(value);
    return result.success ? [] : result.error.issues.map((i) => i.message);
  };

  it('DECIMAL: 13자리·7자리 소수는 안내 하나만 내고, 통과한 값은 입력 문자열 그대로(double 변환 없이) 나간다', () => {
    const schema = buildRowZodSchema([col('price', 'DECIMAL')], 'add');
    expect(issues(schema, { price: '12345678901234' })).toEqual([INTEGER_PART_MSG]);
    expect(issues(schema, { price: '0.0000001' })).toEqual([FRACTION_MSG]);
    // 유효숫자 18자리 — Number 로 바꾸면 123456789012.12346 이 된다
    expect(schema.parse({ price: '123456789012.123456' }).price).toBe('123456789012.123456');
    expect(schema.parse({ price: ' 0.000001 ' }).price).toBe('0.000001');
    // NULL 허용 컬럼의 빈 값은 여전히 통과(#674)
    expect(schema.parse({ price: '' }).price).toBeUndefined();
  });

  it('INTEGER: 입력 문자열 그대로(앞뒤 공백만 제거) 나가고, 정수 아님·범위 밖은 안내', () => {
    const schema = buildRowZodSchema([col('num', 'INTEGER')], 'add');
    // Number 로 바꾸면 9007199254740992 가 된다
    expect(schema.parse({ num: ' 9007199254740993 ' }).num).toBe('9007199254740993');
    expect(issues(schema, { num: '1.5' })).toEqual(['정수를 입력하세요.']);
    expect(issues(schema, { num: '9223372036854775808' })).toHaveLength(1);
    expect(schema.parse({ num: '' }).num).toBeUndefined();
  });

  it('DECIMAL 편집: 초기값과 같은(손대지 않은) 칸은 자릿수 검사를 건너뛰고, 바꾼 칸은 검사한다', () => {
    // API 의 JSON 숫자 999999999999.999999 는 JS 에서 1e12 가 되어 편집 폼에 13자리로 보인다
    const shown = String(JSON.parse('999999999999.999999'));
    expect(shown).toBe('1000000000000');
    const schema = buildRowZodSchema([col('price', 'DECIMAL')], 'edit', { price: shown });
    expect(issues(schema, { price: shown })).toEqual([]);
    expect(issues(schema, { price: '1000000000001' })).toEqual([INTEGER_PART_MSG]);
    expect(issues(schema, { price: 'abc' })).toEqual(['숫자를 입력하세요.']);
  });

  it('DECIMAL(NOT NULL): 빈 값·형식 오류는 기존 문구 그대로', () => {
    const schema = buildRowZodSchema([{ ...col('price', 'DECIMAL'), isNullable: false }], 'add');
    expect(issues(schema, { price: '' })).toEqual(['숫자를 입력하세요.']);
    expect(issues(schema, { price: 'abc' })).toEqual(['숫자를 입력하세요.']);
  });

  it('VARCHAR: 문자(코드포인트) 수로 세고, 길이 미지정이면 255자', () => {
    const v5 = buildRowZodSchema([{ ...col('code', 'VARCHAR'), maxLength: 5 }], 'add');
    expect(issues(v5, { code: '😀😀😀😀😀' })).toEqual([]); // UTF-16 길이 10
    expect(issues(v5, { code: '😀😀😀😀😀😀' })).toEqual(['최대 5자']);

    const vDefault = buildRowZodSchema([col('code', 'VARCHAR')], 'add');
    expect(issues(vDefault, { code: 'a'.repeat(255) })).toEqual([]);
    expect(issues(vDefault, { code: 'a'.repeat(256) })).toEqual(['최대 255자']);
    expect(issues(vDefault, { code: '' })).toEqual([]);

    const required = buildRowZodSchema([{ ...col('code', 'VARCHAR'), isNullable: false, maxLength: 3 }], 'add');
    expect(issues(required, { code: '' })).toEqual(['필수 입력 항목입니다.']);
    expect(issues(required, { code: 'abcd' })).toEqual(['최대 3자']);
  });
});

describe('GEOMETRY 입력 검증 (WD-201)', () => {
  const FORMAT_MSG = 'GeoJSON 형식으로 입력하세요. 예: {"type":"Point","coordinates":[126.97,37.56]}';
  const FC_MSG =
    'FeatureCollection 은 저장할 수 없습니다. 한 칸에는 위치 하나만 들어가므로 Feature 하나나 geometry 를 입력하세요';

  it('geometry 와 Feature(안에 geometry 가 있는)는 통과한다', () => {
    expect(geoJsonInputError('{"type":"Point","coordinates":[126.97,37.56]}')).toBeNull();
    expect(
      geoJsonInputError('{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[1,2]}}'),
    ).toBeNull();
  });

  it('FeatureCollection 은 서버와 같은 한국어 문구로 거부한다', () => {
    expect(geoJsonInputError('{"type":"FeatureCollection","features":[]}')).toBe(FC_MSG);
  });

  it('geometry 가 null·없는 Feature, WKT, 모르는 type 은 거부한다', () => {
    expect(geoJsonInputError('{"type":"Feature","properties":{},"geometry":null}')).toBe(
      'Feature 에 geometry 가 없습니다. 위치가 없으면 값을 비워 두세요',
    );
    expect(geoJsonInputError('{"type":"Feature","geometry":{"type":"Feature"}}')).toBe(FORMAT_MSG);
    expect(geoJsonInputError('POINT(1 2)')).toBe(FORMAT_MSG);
    expect(geoJsonInputError('{"type":"Circle"}')).toBe(FORMAT_MSG);
    expect(geoJsonInputError('[1,2]')).toBe(FORMAT_MSG);
  });

  it('스키마: Feature 는 통과, FeatureCollection 은 칸 오류, NULL 허용 칸의 빈 값은 통과', () => {
    const schema = buildRowZodSchema([col('g', 'GEOMETRY')], 'add');
    const feature = '{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[1,2]}}';
    expect(schema.parse({ g: feature }).g).toBe(feature);
    const fc = schema.safeParse({ g: '{"type":"FeatureCollection","features":[]}' });
    expect(fc.success).toBe(false);
    expect(fc.success ? [] : fc.error.issues.map((i) => i.message)).toContain(FC_MSG);
    expect(schema.parse({ g: '' }).g).toBe('');
  });
});
