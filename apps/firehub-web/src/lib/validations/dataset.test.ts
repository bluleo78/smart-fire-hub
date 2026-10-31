import { describe, expect, it } from 'vitest';

import {
  addColumnSchema,
  COLUMN_NAME_DUPLICATE_MESSAGE,
  COLUMN_NAME_MAX_MESSAGE,
  columnNameLengthError,
  createDatasetSchema,
  FIELD_NAME_DUPLICATE_MESSAGE,
  findDuplicateColumnIndexes,
  MAX_COLUMN_NAME_LENGTH,
  MAX_TABLE_NAME_LENGTH,
  RESERVED_COLUMN_NAME_MESSAGE,
  reservedColumnNameError,
  updateColumnSchema,
} from './dataset';

/**
 * 테이블명·칼럼명 길이 상한 (WD-95) — 백엔드 DataTableService 의 상한(56/63)과 같은지, 경계에서 정확히 갈리는지 고정한다.
 */

/** prefix 뒤를 'x' 로 채워 정확히 length 자인 이름을 만든다. */
const nameOf = (prefix: string, length: number) => prefix + 'x'.repeat(length - prefix.length);

const column = (columnName: string) => ({
  columnName,
  displayName: '',
  dataType: 'TEXT' as const,
  isNullable: true,
  isIndexed: false,
  isPrimaryKey: false,
  description: '',
});

const dataset = (tableName: string, columnName = 'col1') => ({
  name: '데이터셋',
  tableName,
  description: '',
  storageType: 'TABLE' as const,
  originType: 'SOURCE' as const,
  columns: [column(columnName)],
});

/** 실패 시 해당 경로의 첫 메시지를 꺼낸다. */
const messageAt = (
  result: { success: boolean; error?: { issues: { path: PropertyKey[]; message: string }[] } },
  key: string,
) => result.error?.issues.find((i) => i.path.includes(key))?.message;

describe('데이터셋 이름 길이 상한 (WD-95)', () => {
  it('백엔드와 같은 상한 값이다', () => {
    expect(MAX_TABLE_NAME_LENGTH).toBe(56);
    expect(MAX_COLUMN_NAME_LENGTH).toBe(63);
  });

  it('테이블명 56자는 통과, 57자는 거부된다', () => {
    expect(createDatasetSchema.safeParse(dataset(nameOf('t_', 56))).success).toBe(true);
    const r = createDatasetSchema.safeParse(dataset(nameOf('t_', 57)));
    expect(r.success).toBe(false);
    expect(messageAt(r, 'tableName')).toBe('테이블명은 56자 이하여야 합니다');
  });

  it('생성 폼 칼럼명 63자는 통과, 64자는 거부된다', () => {
    expect(createDatasetSchema.safeParse(dataset('t1', nameOf('c_', 63))).success).toBe(true);
    const r = createDatasetSchema.safeParse(dataset('t1', nameOf('c_', 64)));
    expect(r.success).toBe(false);
    expect(messageAt(r, 'columnName')).toBe('칼럼명은 63자 이하여야 합니다');
  });

  it('칼럼 추가·수정 스키마도 63자 경계를 따른다', () => {
    expect(addColumnSchema.safeParse(column(nameOf('c_', 63))).success).toBe(true);
    expect(addColumnSchema.safeParse(column(nameOf('c_', 64))).success).toBe(false);
    expect(updateColumnSchema.safeParse(column(nameOf('c_', 63))).success).toBe(true);
    const r = updateColumnSchema.safeParse(column(nameOf('c_', 64)));
    expect(r.success).toBe(false);
    expect(messageAt(r, 'columnName')).toBe('칼럼명은 63자 이하여야 합니다');
  });

  it('즉시 반영 입력용 columnNameLengthError 는 63자 경계에서 같은 문구를 돌려준다', () => {
    expect(columnNameLengthError(nameOf('c_', 63))).toBeUndefined();
    expect(columnNameLengthError(nameOf('c_', 64))).toBe(COLUMN_NAME_MAX_MESSAGE);
    expect(COLUMN_NAME_MAX_MESSAGE).toBe('칼럼명은 63자 이하여야 합니다');
  });
});

/**
 * 생성 폼 필드 목록의 이름 중복 (WD-99) — 서버의 영어 「duplicate entry」 대신 뒤에 나온 칸에 한국어 안내가 뜨고 제출이 막혀야 한다.
 */
describe('생성 폼 칼럼명 중복 (WD-99)', () => {
  const withColumns = (...names: string[]) => ({
    ...dataset('t1'),
    columns: names.map((n) => column(n)),
  });

  /** columns.<index>.columnName 경로의 메시지 목록. */
  const columnNameIssues = (names: string[]) => {
    const r = createDatasetSchema.safeParse(withColumns(...names));
    return (r.error?.issues ?? [])
      .filter((i) => i.path[0] === 'columns' && i.path[2] === 'columnName')
      .map((i) => ({ index: i.path[1], message: i.message }));
  };

  it('같은 이름이 둘이면 뒤에 나온 칸에만 안내하고 실패한다', () => {
    expect(createDatasetSchema.safeParse(withColumns('code', 'code')).success).toBe(false);
    expect(columnNameIssues(['code', 'code'])).toEqual([
      { index: 1, message: COLUMN_NAME_DUPLICATE_MESSAGE },
    ]);
  });

  it('셋 이상 겹치면 첫 칸을 뺀 나머지 모두에 안내한다', () => {
    expect(columnNameIssues(['code', 'name', 'code', 'code'])).toEqual([
      { index: 2, message: COLUMN_NAME_DUPLICATE_MESSAGE },
      { index: 3, message: COLUMN_NAME_DUPLICATE_MESSAGE },
    ]);
  });

  it('서로 다른 이름은 통과한다', () => {
    expect(createDatasetSchema.safeParse(withColumns('code', 'name')).success).toBe(true);
  });

  it('빈 이름끼리는 중복으로 세지 않는다(입력 필요 안내만)', () => {
    expect(findDuplicateColumnIndexes(['', ''])).toEqual([]);
    const messages = columnNameIssues(['', '']).map((i) => i.message);
    expect(messages).not.toContain(COLUMN_NAME_DUPLICATE_MESSAGE);
  });
});

describe('이름 안내 문구·예약어 (WD-99)', () => {
  it('중복 문구는 화면 라벨을 따른다(생성=칼럼명, 다이얼로그=필드명)', () => {
    expect(COLUMN_NAME_DUPLICATE_MESSAGE).toBe('이미 있는 칼럼명입니다');
    expect(FIELD_NAME_DUPLICATE_MESSAGE).toBe('이미 있는 필드명입니다');
  });

  it('시스템 예약어(백엔드 SYSTEM_COLUMNS 와 같은 4개)는 대소문자 없이 예약어 안내를 돌려준다', () => {
    for (const name of ['id', 'import_id', 'created_at', '_updated_at', 'ID']) {
      expect(reservedColumnNameError(name)).toBe(RESERVED_COLUMN_NAME_MESSAGE);
    }
    expect(reservedColumnNameError('user_id')).toBeUndefined();
  });

  it('예약어는 중복으로 세지 않아 예약어 안내가 중복 안내에 가려지지 않는다', () => {
    expect(findDuplicateColumnIndexes(['id', 'id', 'code', 'code'])).toEqual([3]);
  });
});

/**
 * 이름·설명 길이 한도 메시지 (WD-360) — 메시지 없는 max() 는 zod 영어 기본 문구를 화면에 그대로 낸다.
 */
describe('길이 한도 메시지는 한국어다 (WD-360)', () => {
  it('데이터셋 이름 100자 초과는 한국어로 안내한다', () => {
    const r = createDatasetSchema.safeParse({ name: '가'.repeat(101), tableName: 'ok_table', columns: [] });
    const msgs = r.success ? [] : r.error.issues.filter((i) => i.path[0] === 'name').map((i) => i.message);
    expect(msgs).toContain('이름은 100자 이하여야 합니다');
  });
});
