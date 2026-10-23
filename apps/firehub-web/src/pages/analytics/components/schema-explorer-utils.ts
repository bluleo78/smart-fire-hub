import type { SchemaInfo, SchemaTable } from '../../../types/analytics';

export interface TypeBadge {
  letter: string;
  color: string;
}

interface Snippet {
  label: string;
  sql: string;
}

/* SQL 데이터 타입별 배지 — 시맨틱 dtype 토큰 사용 (index.css에 정의) */
const TYPE_BADGE_MAP: Record<string, TypeBadge> = {
  TEXT: { letter: 'T', color: 'text-dtype-text' },
  VARCHAR: { letter: 'T', color: 'text-dtype-text' },
  CHAR: { letter: 'T', color: 'text-dtype-text' },
  INTEGER: { letter: '#', color: 'text-dtype-number' },
  BIGINT: { letter: '#', color: 'text-dtype-number' },
  SMALLINT: { letter: '#', color: 'text-dtype-number' },
  NUMERIC: { letter: '#', color: 'text-dtype-number' },
  DECIMAL: { letter: '#', color: 'text-dtype-number' },
  DOUBLE: { letter: '#', color: 'text-dtype-number' },
  FLOAT: { letter: '#', color: 'text-dtype-number' },
  REAL: { letter: '#', color: 'text-dtype-number' },
  TIMESTAMP: { letter: 'D', color: 'text-dtype-date' },
  DATE: { letter: 'D', color: 'text-dtype-date' },
  TIME: { letter: 'D', color: 'text-dtype-date' },
  BOOLEAN: { letter: 'B', color: 'text-dtype-boolean' },
  JSON: { letter: '{}', color: 'text-dtype-json' },
  JSONB: { letter: '{}', color: 'text-dtype-json' },
  GEOMETRY: { letter: 'G', color: 'text-dtype-geometry' },
  UUID: { letter: 'U', color: 'text-dtype-uuid' },
};

export function quoteIdentifier(name: string): string {
  return `"${name.replace(/"/g, '""')}"`;
}

export function getTypeBadge(dataType: string): TypeBadge {
  return TYPE_BADGE_MAP[dataType.toUpperCase()] ?? {
    letter: '?',
    color: 'text-muted-foreground',
  };
}

/** 방언 판정에 필요한 스키마 응답 일부 */
export type DialectInfo = Pick<SchemaInfo, 'dialect' | 'schema'>;

/** Tibero 는 스키마 한정 이름(`schema.table`)을 쓴다 — 한정 규칙의 단일 판정 */
function isTiberoQualified(info: DialectInfo): info is DialectInfo & { schema: string } {
  return info.dialect === 'TIBERO' && !!info.schema;
}

/**
 * 테이블의 한정 이름. Tibero(+스키마)는 `schema.table`, 그 외(PG)는 테이블명 그대로.
 * 자동완성 키·편집기 삽입 텍스트가 같은 규칙을 쓰도록 한 곳에 둔다.
 */
export function qualifiedName(tableName: string, info: DialectInfo): string {
  return isTiberoQualified(info) ? `${info.schema}.${tableName}` : tableName;
}

/**
 * 편집기에 넣을 테이블 참조.
 * Tibero 는 따옴표를 쓰면 대소문자가 고정돼 조회가 어긋나므로 `schema.table` 을 따옴표 없이,
 * 그 외(PG)는 기존처럼 따옴표 식별자로 만든다.
 */
export function tableReference(table: SchemaTable, info: DialectInfo): string {
  return isTiberoQualified(info)
    ? qualifiedName(table.tableName, info)
    : quoteIdentifier(table.tableName);
}

/** 편집기에 넣을 컬럼 참조 — Tibero 는 따옴표 없이, 그 외는 따옴표 식별자 */
export function columnReference(name: string, dialect?: SchemaInfo['dialect']): string {
  return dialect === 'TIBERO' ? name : quoteIdentifier(name);
}

/** 방언별 행 수 제한 절 — Tibero 는 LIMIT 이 없어 `WHERE ROWNUM <= 100` 을 쓴다 */
function limitClause(dialect?: SchemaInfo['dialect']): string {
  return dialect === 'TIBERO' ? 'WHERE ROWNUM <= 100' : 'LIMIT 100';
}

/** 100행으로 제한한 `SELECT *` 텍스트(방언별) */
export function selectLimited(ref: string, dialect?: SchemaInfo['dialect']): string {
  return `SELECT *\nFROM ${ref}\n${limitClause(dialect)}`;
}

/**
 * 빠른 쿼리 스니펫. Tibero 는 LIMIT 이 없어 `WHERE ROWNUM <= 100` 으로 행 수를 제한한다.
 * 라벨은 방언 중립으로 둔다(PG 라벨은 기존 그대로).
 */
export function generateSnippets(table: SchemaTable, info: DialectInfo = {}): Snippet[] {
  const ref = tableReference(table, info);
  const isTibero = info.dialect === 'TIBERO';
  const allColumns = table.columns
    .map((column) => columnReference(column.columnName, info.dialect))
    .join(', ');

  return [
    { label: isTibero ? 'SELECT * (100행)' : 'SELECT * LIMIT 100', sql: selectLimited(ref, info.dialect) },
    { label: 'SELECT COUNT(*)', sql: `SELECT COUNT(*)\nFROM ${ref}` },
    {
      label: isTibero ? 'SELECT 모든 컬럼 (100행)' : 'SELECT 모든 컬럼',
      sql: `SELECT ${allColumns}\nFROM ${ref}\n${limitClause(info.dialect)}`,
    },
  ];
}

/**
 * 데이터셋 화면의 "쿼리 작성"/"차트 만들기" 바로가기용 미리보기 SQL.
 * Tibero 는 스키마 한정 이름이 필수이고 LIMIT 이 없어 빠른 스니펫(SELECT * 100행)과 같은 형태를 쓴다.
 * PG 는 기존 문자열(따옴표 없는 한 줄)을 바이트 단위로 그대로 유지한다.
 */
export function previewQuery(tableName: string, info: DialectInfo = {}): string {
  if (isTiberoQualified(info)) {
    return selectLimited(qualifiedName(tableName, info), info.dialect);
  }
  return `SELECT * FROM ${tableName} LIMIT 100`;
}
