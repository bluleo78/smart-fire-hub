package com.smartfirehub.global.platformdb.tibero;

import java.util.Arrays;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLErrorCodes;
import org.springframework.jdbc.support.SQLExceptionTranslator;

/**
 * Tibero 플랫폼 DB 의 스프링 예외 번역기(WD-496) — Tibero 오류를 PostgreSQL 과 같은 스프링 예외 타입으로 바꾼다.
 *
 * <p>왜 필요한가: 스프링 기본 오류 코드 표에는 제품명 "Tibero" 가 없고, Tibero 는 무결성 위반을 SQLState {@code 23000} 하나로만 보낸다.
 * 그대로 두면 유일 위반이 {@code DuplicateKeyException} 이 아니라 {@code DataIntegrityViolationException} 으로, 잠금
 * 실패는 {@code UncategorizedSQLException} 으로 번역돼 플랫폼 서비스의 {@code DuplicateKeyException} 처리(중복 이름 안내
 * 등)가 Tibero 에서만 빗나간다.
 *
 * <p>왜 클래스패스 {@code sql-error-codes.xml} 이 아닌가: 그 파일이 있으면 스프링이 앱 전체의 {@code JdbcTemplate} 번역 방식을 바꿔
 * PostgreSQL 쪽 번역까지 달라진다. 그래서 Tibero 를 쓰는 자리(MyBatis 세션 템플릿 등)에만 이 번역기를 꽂는다. 코드는 {@link
 * TiberoPlatformDbErrors} 의 상수 한 곳에서 온다.
 */
public final class TiberoSqlExceptionTranslator {

  private TiberoSqlExceptionTranslator() {}

  /** Tibero 오류 코드 표를 담은 번역기. 표에 없는 코드는 스프링 기본 대체 번역(SQLState·예외 하위 클래스)으로 넘어간다. */
  public static SQLExceptionTranslator create() {
    return new SQLErrorCodeSQLExceptionTranslator(errorCodes());
  }

  /** 번역 표 — 유일 위반, 그 밖의 무결성 위반, 잠금 실패. */
  static SQLErrorCodes errorCodes() {
    SQLErrorCodes codes = new SQLErrorCodes();
    codes.setDatabaseProductName("Tibero");
    codes.setDuplicateKeyCodes(
        strings(
            TiberoPlatformDbErrors.UNIQUE_VIOLATION,
            TiberoPlatformDbErrors.MULTI_INSERT_DUPLICATE));
    codes.setDataIntegrityViolationCodes(
        strings(
            TiberoPlatformDbErrors.NOT_NULL_VIOLATION,
            TiberoPlatformDbErrors.CHECK_VIOLATION,
            TiberoPlatformDbErrors.FK_MISSING_TARGET,
            TiberoPlatformDbErrors.FK_IN_USE,
            TiberoPlatformDbErrors.VALUE_TOO_LARGE));
    codes.setCannotAcquireLockCodes(
        strings(
            TiberoPlatformDbErrors.LOCK_NOWAIT_FAILED, TiberoPlatformDbErrors.LOCK_WAIT_TIMEOUT));
    return codes;
  }

  private static String[] strings(int... codes) {
    return Arrays.stream(codes).mapToObj(Integer::toString).toArray(String[]::new);
  }
}
