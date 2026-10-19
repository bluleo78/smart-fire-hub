import { describe, expect, it } from 'vitest';

import {
  addColumnSchema,
  COLUMN_NAME_MAX_MESSAGE,
  columnNameLengthError,
  createDatasetSchema,
  MAX_COLUMN_NAME_LENGTH,
  MAX_TABLE_NAME_LENGTH,
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
