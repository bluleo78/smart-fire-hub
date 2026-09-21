package com.smartfirehub.pipeline.service;

import com.smartfirehub.global.tenant.DataSchema;

/**
 * 출력 데이터셋 비우기 문장을 만드는 유일한 지점.
 *
 * <p>TRUNCATE 가 아닌 DELETE 를 쓰는 이유: 실행기 테넌트 롤에 TRUNCATE 권한이 없고(실측), DELETE 는 MVCC 라
 * 트랜잭션이 끝날 때까지 다른 조회가 이전 데이터를 계속 본다. firehub-executor 의 선행 문장 검증 정규식과
 * 형태가 정확히 일치해야 한다.
 *
 * <p><b>{@link DataSchema#qualify}를 그대로 쓰지 않는 이유.</b> {@code qualify()} 는 스키마 부분을
 * 인용하지 않는 형태(스키마 뒤에 바로 점 + 인용된 테이블명)를 돌려준다(대부분의 SQL 조립에는 문제 없다 —
 * PostgreSQL 은 소문자 미인용 식별자와 인용된 소문자 식별자를 동일하게 해석한다). 그런데 firehub-executor 의
 * 선행 문장 화이트리스트는 {@code ^DELETE FROM "(data|data_t\d+)"\."[a-z0-9_]+"$} 로 스키마·테이블
 * <b>양쪽 모두</b> 인용을 요구한다 — 실측(Task 3, sql_validator.py) 결과다. {@code qualify()} 자체를 바꾸면
 * 그 출력 형태에 의존하는 기존 계약(예: {@code DataSchemaResolutionTest} 의 스키마 미인용 단언)이 깨지므로,
 * 여기서는 스키마·테이블명을 직접 인용해 조립한다 — {@link DataSchema#current()} 로 스키마 식별자를 얻는
 * 것은 그대로 "스키마명을 유일한 지점에서 얻는다"는 규약을 따른다.
 */
public final class OutputClearStatement {
  private OutputClearStatement() {}

  /**
   * 출력 테이블 전체를 비우는 DELETE 문을 만든다.
   *
   * @param tableName 비울 테이블명(스키마 미포함, 인용 없음). 큰따옴표는 이중화해 인젝션을 막는다.
   * @return {@code DELETE FROM "<schema>"."<table>"} 형태의 문장 — 세미콜론·WHERE 없음.
   */
  public static String deleteAll(String tableName) {
    String schema = DataSchema.current();
    String escapedTable = tableName.replace("\"", "\"\"");
    return "DELETE FROM \"" + schema + "\".\"" + escapedTable + "\"";
  }
}
