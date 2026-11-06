package com.smartfirehub.global.platformdb.tibero;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.support.SQLExceptionTranslator;

/**
 * {@link TiberoSqlExceptionTranslator}(WD-496) — Tibero 오류를 PostgreSQL 과 같은 스프링 예외 타입으로 바꾸는지 본다.
 * 코드는 {@link TiberoPlatformDbErrors} 상수에서 오므로 판정과 번역이 같은 말을 한다.
 */
class TiberoSqlErrorCodesTest {

  private final SQLExceptionTranslator translator = TiberoSqlExceptionTranslator.create();

  private static SQLException integrity(int code, String body) {
    return new SQLIntegrityConstraintViolationException("JDBC" + code + ":" + body, "23000", code);
  }

  /** 플랫폼 서비스 여러 곳이 DuplicateKeyException 을 잡아 중복 안내를 한다 — Tibero 에서도 같은 타입이어야 한다. */
  @Test
  void 유일_위반과_다중_행_삽입_중복은_DuplicateKeyException() {
    assertThat(
            translator.translate(
                "insert", "INSERT", integrity(-10007, "UNIQUE constraint violation ('S'.'UQ').")))
        .isInstanceOf(DuplicateKeyException.class);
    assertThat(translator.translate("insert", "INSERT ALL", integrity(-4008, "duplicate")))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void 외래_키_체크_NOT_NULL_길이_초과는_DataIntegrityViolationException() {
    for (int code : new int[] {-10005, -10006, -10008, -10009, -11048}) {
      assertThat(translator.translate("dml", "DML", integrity(code, "violation")))
          .as("코드 %d", code)
          .isInstanceOf(DataIntegrityViolationException.class)
          .isNotInstanceOf(DuplicateKeyException.class);
    }
  }

  @Test
  void 잠금_실패는_CannotAcquireLockException() {
    for (int code : new int[] {-12033, -12034}) {
      SQLException e = new SQLException("JDBC" + code + ":Lock acquisition failed.", "ERRFR", code);

      assertThat(translator.translate("lock", "SELECT ... FOR UPDATE NOWAIT", e))
          .as("코드 %d", code)
          .isInstanceOf(CannotAcquireLockException.class);
    }
  }

  /**
   * 클래스패스 루트 {@code sql-error-codes.xml} 을 두지 않는다 — 그 파일이 있으면 스프링이 앱 전체 {@code JdbcTemplate} 의 번역
   * 방식을 바꿔 PostgreSQL 쪽 번역까지 달라진다.
   */
  @Test
  void 전역_오류_코드_파일을_두지_않는다() {
    assertThat(new ClassPathResource("sql-error-codes.xml").exists()).isFalse();
  }
}
