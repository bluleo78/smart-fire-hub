import type { SchemaTable } from '../../../types/analytics';

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

export function generateSnippets(table: SchemaTable): Snippet[] {
  const quotedTable = quoteIdentifier(table.tableName);
  const allColumns = table.columns
    .map((column) => quoteIdentifier(column.columnName))
    .join(', ');

  return [
    { label: 'SELECT * LIMIT 100', sql: `SELECT *\nFROM ${quotedTable}\nLIMIT 100` },
    { label: 'SELECT COUNT(*)', sql: `SELECT COUNT(*)\nFROM ${quotedTable}` },
    {
      label: 'SELECT 모든 컬럼',
      sql: `SELECT ${allColumns}\nFROM ${quotedTable}\nLIMIT 100`,
    },
  ];
}
