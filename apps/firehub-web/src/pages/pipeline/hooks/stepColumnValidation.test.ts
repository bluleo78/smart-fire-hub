import { describe, expect, it } from 'vitest';

import { RESERVED_COLUMN_NAME_MESSAGE } from '@/lib/validations/dataset';

import {
  aiOutputColumnNameField,
  EMPTY_MAPPING_ROW_MESSAGE,
  mappingRowField,
  mappingSourceField,
  mappingTargetField,
  OUTPUT_COLUMN_NAME_REQUIRED_MESSAGE,
  SOURCE_FIELD_REQUIRED_MESSAGE,
  TARGET_COLUMN_REQUIRED_MESSAGE,
  validateAiOutputColumns,
  validateApiFieldMappings,
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
