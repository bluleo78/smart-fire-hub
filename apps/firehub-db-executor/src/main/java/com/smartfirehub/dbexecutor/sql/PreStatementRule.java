package com.smartfirehub.dbexecutor.sql;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 선행 문장 규칙(스펙 §4.3-1·3). api 가 REPLACE 적재 때 보내는 {@code DELETE FROM "<스키마>"."<테이블>"} 한 형태만, 그 스키마가
 * 요청 테넌트의 스키마일 때만 받는다.
 *
 * <p>통과하면 같은 테이블의 {@code LOCK TABLE … IN EXCLUSIVE MODE} 를 돌려준다. 실행기는 DELETE 직전에 이 잠금을 별도 문장으로 걸어,
 * 같은 출력에 대한 REPLACE 두 개가 겹쳐도 뒤 실행의 DELETE 가 앞 실행의 커밋 결과를 보게 한다(Python executor 의 {@code
 * pg_advisory_xact_lock} 역할 — 트랜잭션에 묶여 COMMIT·ROLLBACK 때 풀린다).
 */
public final class PreStatementRule {

  /** 거부 문구(사용자에게 그대로 보인다). */
  public static final String REJECTED = "허용되지 않은 선행 문장입니다.";

  /**
   * api {@code TiberoPipelineSql.deleteAll} 결과와 정확히 같은 모양. {@code matches()} 로 전체 일치를 요구하므로 뒤에 붙은
   * {@code WHERE}·세미콜론·개행·두 번째 문장은 모두 거부된다.
   */
  private static final Pattern DELETE_ALL =
      Pattern.compile("^DELETE FROM \"([A-Z0-9_]+)\"\\.\"([A-Z0-9_$#]+)\"$");

  private PreStatementRule() {}

  /**
   * 선행 DELETE 를 검증하고 그 테이블의 배타 잠금 문장을 돌려준다.
   *
   * @param deleteStmt api 가 보낸 선행 문장
   * @param tenantSchema 기술자의 스키마(대문자, 예: {@code GENIA_T5})
   * @throws TiberoSqlGuard.Rejected 모양이 다르거나 다른 스키마일 때
   */
  public static String lockFor(String deleteStmt, String tenantSchema) {
    if (deleteStmt == null || tenantSchema == null) {
      throw new TiberoSqlGuard.Rejected(REJECTED);
    }
    Matcher m = DELETE_ALL.matcher(deleteStmt);
    if (!m.matches() || !m.group(1).equals(tenantSchema)) {
      throw new TiberoSqlGuard.Rejected(REJECTED);
    }
    // 검증한 그룹으로 다시 조립한다(입력 문자열을 잠금 문장에 그대로 잇지 않는다).
    return "LOCK TABLE \"" + m.group(1) + "\".\"" + m.group(2) + "\" IN EXCLUSIVE MODE";
  }
}
