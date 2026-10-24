package com.smartfirehub.dbexecutor.sql;

import java.util.regex.Pattern;

/**
 * 적재 대상 규칙(스펙 §3.3). Tibero 의 적재 대상은 언제나 이번 실행의 스테이징 테이블이라 {@code STG_<24 대문자 16진>} 이름만, 요청 테넌트의
 * 스키마에서만 받는다 — 일반 테이블·다른 스키마·따옴표가 섞인 이름은 모두 거부한다(선행 문장 규칙 {@link PreStatementRule} 과 같은 엄격함). 통과한
 * 이름은 검증한 조각으로 다시 조립해 돌려준다(입력 문자열을 SQL 에 그대로 잇지 않는다).
 */
public final class LoadTableRule {

  /** 거부 문구(사용자에게 그대로 보인다). */
  public static final String REJECTED = "적재할 수 없는 테이블입니다.";

  /** api {@code TiberoNames.stagingTableName()} 결과 모양 — {@code matches()} 라 앞뒤 공백·개행도 거부된다. */
  private static final Pattern STAGING = Pattern.compile("STG_[0-9A-F]{24}");

  /** 테넌트 스키마(대문자, 예: {@code GENIA_T5}) — {@link PreStatementRule} 과 같은 문자 집합. */
  private static final Pattern SCHEMA = Pattern.compile("[A-Z0-9_]+");

  private LoadTableRule() {}

  /**
   * 스테이징 테이블인지 확인하고 따옴표 친 한정 이름 {@code "SCHEMA"."STG_…"} 을 돌려준다.
   *
   * @param table 요청의 테이블 이름
   * @param schema 기술자의 스키마
   * @throws TiberoSqlGuard.Rejected 스테이징 이름 모양이 아니거나 스키마가 올바르지 않을 때
   */
  public static String requireStaging(String table, String schema) {
    if (table == null
        || schema == null
        || !STAGING.matcher(table).matches()
        || !SCHEMA.matcher(schema).matches()) {
      throw new TiberoSqlGuard.Rejected(REJECTED);
    }
    return "\"" + schema + "\".\"" + table + "\"";
  }
}
