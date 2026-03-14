import type { SchemaTable } from '../../../types/analytics';

export interface TypeBadge {
  letter: string;
  color: string;
}

interface Snippet {
  label: string;
  sql: string;
}

const TYPE_BADGE_MAP: Record<string, TypeBadge> = {
  TEXT: { letter: 'T', color: 'text-blue-500' },
  VARCHAR: { letter: 'T', color: 'text-blue-500' },
  CHAR: { letter: 'T', color: 'text-blue-500' },
  INTEGER: { letter: '#', color: 'text-emerald-500' },
  BIGINT: { letter: '#', color: 'text-emerald-500' },
  SMALLINT: { letter: '#', color: 'text-emerald-500' },
  NUMERIC: { letter: '#', color: 'text-emerald-500' },
  DECIMAL: { letter: '#', color: 'text-emerald-500' },
  DOUBLE: { letter: '#', color: 'text-emerald-500' },
  FLOAT: { letter: '#', color: 'text-emerald-500' },
  REAL: { letter: '#', color: 'text-emerald-500' },
  TIMESTAMP: { letter: 'D', color: 'text-amber-500' },
  DATE: { letter: 'D', color: 'text-amber-500' },
  TIME: { letter: 'D', color: 'text-amber-500' },
  BOOLEAN: { letter: 'B', color: 'text-purple-500' },
  JSON: { letter: '{}', color: 'text-orange-500' },
  JSONB: { letter: '{}', color: 'text-orange-500' },
  GEOMETRY: { letter: 'G', color: 'text-rose-500' },
  UUID: { letter: 'U', color: 'text-cyan-500' },
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
