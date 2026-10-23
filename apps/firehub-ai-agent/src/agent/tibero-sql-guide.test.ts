import { describe, it, expect } from 'vitest';
import { TIBERO_SQL_GUIDE } from './tibero-sql-guide.js';
import { findPgOnlySyntax, hasTiberoCoreRules } from './tibero-prompt-lint.fixture.js';

/**
 * TIBERO_SQL_GUIDE 내용 고정(WD-79).
 *
 * 메인·opencode·Proactive·data-analyst 가 같은 Tibero 규칙을 쓰도록 한 곳에 둔 문구다.
 * 규칙이 빠지면 모델이 PostgreSQL 문법으로 돌아가 Tibero 에서 SQL 이 실패하므로 항목별로 고정한다.
 */
describe('TIBERO_SQL_GUIDE (WD-79)', () => {
  it('비어 있지 않고 PG 전용 문법이 예시·규칙으로 남아 있지 않다', () => {
    expect(TIBERO_SQL_GUIDE.length).toBeGreaterThan(500);
    expect(findPgOnlySyntax(TIBERO_SQL_GUIDE)).toEqual([]);
    expect(hasTiberoCoreRules(TIBERO_SQL_GUIDE)).toBe(true);
  });

  it('규칙 1·2: get_data_schema 의 schema 로 genia_t{id}.테이블 한정, DUAL 예외, 따옴표 없는 이름', () => {
    expect(TIBERO_SQL_GUIDE).toContain('get_data_schema');
    expect(TIBERO_SQL_GUIDE).toContain('`schema`');
    expect(TIBERO_SQL_GUIDE).toMatch(/genia_t5\.sales/);
    expect(TIBERO_SQL_GUIDE).toContain('400');
    expect(TIBERO_SQL_GUIDE).toContain('DUAL');
    // 따옴표 소문자 이름은 Tibero 에서 다른(없는) 이름이 된다 — 큰따옴표를 쓰지 말라고 가르친다.
    expect(TIBERO_SQL_GUIDE).toContain('큰따옴표');
  });

  it('규칙 3: 형변환은 CAST·TO_NUMBER·TO_CHAR·TO_DATE', () => {
    for (const fn of ['CAST(', 'TO_NUMBER(', 'TO_CHAR(', 'TO_DATE']) expect(TIBERO_SQL_GUIDE).toContain(fn);
  });

  it("규칙 4: 날짜 묶기 TRUNC(…,'MM'/'IW'/'DD'/'YYYY')·ADD_MONTHS·SYSDATE", () => {
    for (const unit of ["'MM'", "'IW'", "'DD'", "'YYYY'"]) expect(TIBERO_SQL_GUIDE).toContain(unit);
    expect(TIBERO_SQL_GUIDE).toContain('ADD_MONTHS(');
    expect(TIBERO_SQL_GUIDE).toContain('SYSDATE');
    // 주 단위는 ISO 주(월요일 시작)임을 밝힌다(실서버 확인).
    expect(TIBERO_SQL_GUIDE).toContain('월요일');
  });

  it('규칙 5(Ruling 5): 상위 N 은 FETCH FIRST 가 기본, ROWNUM 이 대안, LIMIT 은 쓰지 않는다', () => {
    expect(TIBERO_SQL_GUIDE).toMatch(/FETCH FIRST \d+ ROWS ONLY/);
    expect(TIBERO_SQL_GUIDE).toMatch(/ROWNUM <= \d+/);
    expect(TIBERO_SQL_GUIDE).toMatch(/`LIMIT` 은 쓰지 않는다/);
  });

  it('규칙 6·7: UPPER LIKE 검색, ROUND·NVL·COALESCE, ||', () => {
    expect(TIBERO_SQL_GUIDE).toContain("UPPER(");
    expect(TIBERO_SQL_GUIDE).toContain('LIKE UPPER(');
    expect(TIBERO_SQL_GUIDE).toContain('ROUND(');
    expect(TIBERO_SQL_GUIDE).toContain('NVL(');
    expect(TIBERO_SQL_GUIDE).toContain('COALESCE(');
    expect(TIBERO_SQL_GUIDE).toContain('||');
  });

  it('규칙 8: 공간 함수·행 검색·jsonb·generate_series 는 쓸 수 없다(도구 이름은 적지 않는다)', () => {
    expect(TIBERO_SQL_GUIDE).toContain('ST_*');
    expect(TIBERO_SQL_GUIDE).toContain('jsonb');
    expect(TIBERO_SQL_GUIDE).toContain('generate_series');
    expect(TIBERO_SQL_GUIDE).toContain('행 검색');
    // Tibero 테넌트에는 행 검색 도구가 숨겨지므로 이름을 꺼내지 않는다(메인 프롬프트 검사와 같은 기준).
    expect(TIBERO_SQL_GUIDE).not.toContain('search_dataset_rows');
  });

  it('규칙 9: 오류 자기 교정은 api 의 Tibero 오류 문구·음수 코드를 기준으로 한다(SQLState 규칙 없음)', () => {
    // PipelineSqlErrorMessages.describeTiberoQueryError 가 실제로 내보내는 문구를 그대로 인용한다.
    expect(TIBERO_SQL_GUIDE).toContain('Tibero 오류: ');
    expect(TIBERO_SQL_GUIDE).toContain('SQL 에서 참조한 테이블이 없습니다. 테이블 이름과 스키마 한정자를 확인하세요.');
    expect(TIBERO_SQL_GUIDE).toContain('실행 시간 제한(30초)을 넘어 중단했습니다.');
    // 검증기(SqlValidator) 거부 문구
    expect(TIBERO_SQL_GUIDE).toContain('테이블 참조에 스키마가 없습니다');
    expect(TIBERO_SQL_GUIDE).toContain('허용되지 않는 함수 호출');
    // 음수 코드(JDBC-NNNN) — api·실서버 확인 목록에 있는 것만
    for (const code of ['JDBC-8033', 'JDBC-8006', 'JDBC-8036']) expect(TIBERO_SQL_GUIDE).toContain(code);
    expect(TIBERO_SQL_GUIDE).not.toMatch(/SQLState|42703|42P01|42601/);
  });
});
