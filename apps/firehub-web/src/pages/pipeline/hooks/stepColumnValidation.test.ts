import { describe, expect, it } from 'vitest';

import { COLUMN_NAME_MAX_MESSAGE, RESERVED_COLUMN_NAME_MESSAGE } from '@/lib/validations/dataset';

import {
  aiOutputColumnNameField,
  API_PAGE_SIZE_MESSAGE,
  apiPageSizeError,
  EMPTY_MAPPING_ROW_MESSAGE,
  mappingRowField,
  mappingSourceField,
  mappingTargetField,
  OUTPUT_COLUMN_NAME_REQUIRED_MESSAGE,
  pythonOutputColumnNameField,
  SOURCE_FIELD_REQUIRED_MESSAGE,
  TARGET_COLUMN_REQUIRED_MESSAGE,
  validateAiOutputColumns,
  validateApiFieldMappings,
  validatePythonOutputColumns,
} from './stepColumnValidation';

// WD-154 — 스텝 출력 컬럼 저장 시점 검증 규칙(서버 PipelineService 와 같은 규칙·문장)
const m = (sourceField: string, targetColumn: string) => ({ sourceField, targetColumn });

describe('validateApiFieldMappings', () => {
  it('정상 매핑은 오류가 없다', () => {
    expect(validateApiFieldMappings([m('a', 'col_a'), m('b', 'col_b')], true, null)).toEqual([]);
  });

  it('매핑이 배열이 아니거나 비어 있으면 검사하지 않는다', () => {
    expect(validateApiFieldMappings(undefined, true, null)).toEqual([]);
    expect(validateApiFieldMappings([], false, ['col1'])).toEqual([]);
  });

  it('양쪽 다 빈 행은 행 단위 오류로 알린다 (조용히 버리지 않는다)', () => {
    expect(validateApiFieldMappings([m('a', 'a'), m(' ', '')], true, null)).toEqual([
      { field: mappingRowField(1), message: EMPTY_MAPPING_ROW_MESSAGE },
    ]);
  });

  it('한쪽만 빈 행은 비어 있는 칸을 알린다', () => {
    expect(validateApiFieldMappings([m('a', ''), m('', 'b')], true, null)).toEqual([
      { field: mappingTargetField(0), message: TARGET_COLUMN_REQUIRED_MESSAGE },
      { field: mappingSourceField(1), message: SOURCE_FIELD_REQUIRED_MESSAGE },
    ]);
  });

  it('대상 컬럼 형식 위반은 규칙과 이름을 담아 알린다', () => {
    expect(validateApiFieldMappings([m('a', 'Bad Col')], false, null)).toEqual([
      {
        field: mappingTargetField(0),
        message: '컬럼 이름은 영문 소문자로 시작하고 영문 소문자·숫자·밑줄만 쓸 수 있습니다: Bad Col',
      },
    ]);
  });

  it('시스템 컬럼은 자동 생성 출력에서만 예약어로 막고, 지정 출력에서는 있는 컬럼으로 본다', () => {
    expect(validateApiFieldMappings([m('id', 'id')], true, null)).toEqual([
      { field: mappingTargetField(0), message: RESERVED_COLUMN_NAME_MESSAGE },
    ]);
    expect(validateApiFieldMappings([m('id', 'id')], false, ['col1'])).toEqual([]);
  });

  it('같은 대상 컬럼은 뒤에 나온 행에 중복으로 알린다', () => {
    expect(validateApiFieldMappings([m('a', 'dup'), m('b', 'dup')], true, null)).toEqual([
      { field: mappingTargetField(1), message: '대상 컬럼이 중복됩니다: dup' },
    ]);
  });

  it('출력 데이터셋 컬럼을 알 때만 없는 컬럼을 알린다', () => {
    const rows = [m('a', 'col1'), m('b', 'no_such_col')];
    expect(validateApiFieldMappings(rows, false, ['col1'])).toEqual([
      { field: mappingTargetField(1), message: '출력 데이터셋에 없는 컬럼입니다: no_such_col' },
    ]);
    // 컬럼을 모르면(볼 수 없음·조회 실패) 건너뛴다 — 서버가 판정한다
    expect(validateApiFieldMappings(rows, false, null)).toEqual([]);
  });
});

describe('validateAiOutputColumns', () => {
  const c = (name: string) => ({ name, type: 'TEXT' });

  it('정상 이름은 오류가 없다', () => {
    expect(validateAiOutputColumns([c('label'), c('score_1')], true)).toEqual([]);
  });

  it('빈 이름은 출력 컬럼 이름 입력을 안내한다', () => {
    expect(validateAiOutputColumns([c('label'), c('  ')], true)).toEqual([
      { field: aiOutputColumnNameField(1), message: OUTPUT_COLUMN_NAME_REQUIRED_MESSAGE },
    ]);
  });

  it('형식 위반·중복·자동 생성 출력의 예약어를 알린다', () => {
    expect(validateAiOutputColumns([c('Label'), c('x'), c('x'), c('created_at')], true)).toEqual([
      {
        field: aiOutputColumnNameField(0),
        message: '컬럼 이름은 영문 소문자로 시작하고 영문 소문자·숫자·밑줄만 쓸 수 있습니다: Label',
      },
      { field: aiOutputColumnNameField(2), message: '출력 컬럼 이름이 중복됩니다: x' },
      { field: aiOutputColumnNameField(3), message: RESERVED_COLUMN_NAME_MESSAGE },
    ]);
  });
});

// WD-352 — PYTHON 출력 컬럼도 AI 분류와 같은 규칙(자동 생성 출력 전용이라 예약어는 늘 막는다)
describe('validatePythonOutputColumns', () => {
  const c = (name: string) => ({ name, type: 'TEXT' });

  it('정상 이름은 오류가 없다', () => {
    expect(validatePythonOutputColumns([c('val'), c('score_1')])).toEqual([]);
  });

  it('빈 이름·형식 위반·중복·예약어를 PYTHON 오류 키로 알린다', () => {
    expect(validatePythonOutputColumns([c(' '), c('my col'), c('val'), c('val'), c('id')])).toEqual([
      { field: pythonOutputColumnNameField(0), message: OUTPUT_COLUMN_NAME_REQUIRED_MESSAGE },
      {
        field: pythonOutputColumnNameField(1),
        message: '컬럼 이름은 영문 소문자로 시작하고 영문 소문자·숫자·밑줄만 쓸 수 있습니다: my col',
      },
      { field: pythonOutputColumnNameField(3), message: '출력 컬럼 이름이 중복됩니다: val' },
      { field: pythonOutputColumnNameField(4), message: RESERVED_COLUMN_NAME_MESSAGE },
    ]);
  });

  it('63자를 넘는 이름은 길이 상한을 알린다', () => {
    expect(validatePythonOutputColumns([c('a'.repeat(64))])).toEqual([
      { field: pythonOutputColumnNameField(0), message: COLUMN_NAME_MAX_MESSAGE },
    ]);
  });

  it('배열이 아니면(설정 없음) 검사하지 않는다', () => {
    expect(validatePythonOutputColumns(undefined)).toEqual([]);
  });
});

// WD-362 — OFFSET 페이지 크기 범위(1~10000 정수, 서버 PipelineService 와 같은 규칙·문장)
describe('apiPageSizeError', () => {
  const offset = (pageSize: unknown) => ({ type: 'OFFSET', pageSize });

  it('범위 안의 정수와 경계값은 오류가 없다', () => {
    expect(apiPageSizeError(offset(1))).toBeUndefined();
    expect(apiPageSizeError(offset(100))).toBeUndefined();
    expect(apiPageSizeError(offset(10_000))).toBeUndefined();
  });

  it('0 이하·상한 초과·소수·숫자 아님은 범위 문구를 낸다', () => {
    for (const bad of [-5, 0, 10_001, 2.5, 'abc', Number.NaN]) {
      expect(apiPageSizeError(offset(bad))).toBe(API_PAGE_SIZE_MESSAGE);
    }
  });

  it('OFFSET 이 아니거나 페이지 크기가 비어 있으면 검사하지 않는다', () => {
    expect(apiPageSizeError({ type: 'NONE', pageSize: -5 })).toBeUndefined();
    expect(apiPageSizeError(offset(undefined))).toBeUndefined();
    expect(apiPageSizeError(undefined)).toBeUndefined();
  });
});
