import { describe, it, expect } from 'vitest';
import { parseDbDialect, DB_DIALECT_ENV } from './db-dialect.js';

describe('parseDbDialect (WD-79)', () => {
  it("정확히 'TIBERO' 만 TIBERO", () => {
    expect(parseDbDialect('TIBERO')).toBe('TIBERO');
  });
  it.each([undefined, null, 'tibero', 'MYSQL', 'POSTGRESQL', 42, ''])('%s → POSTGRESQL', (v) => {
    expect(parseDbDialect(v)).toBe('POSTGRESQL');
  });
  it('환경변수 이름', () => {
    expect(DB_DIALECT_ENV).toBe('DB_DIALECT');
  });
});
