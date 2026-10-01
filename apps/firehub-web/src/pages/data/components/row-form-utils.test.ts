/**
 * (#772) 행 편집 — 날짜·시각 폼 변환과 "바뀐 칸만 전송" 판정 단위 테스트.
 * vitest.config 의 TZ=Asia/Seoul 기준(데이터 탭 셀 표시와 같은 브라우저 시간대 해석).
 */
import { describe, expect, it } from 'vitest';

import type { DatasetColumnResponse } from '../../../types/dataset';
import { computeChangedFields, pickChangedValues, toTemporalFormValue } from './row-form-utils';

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
