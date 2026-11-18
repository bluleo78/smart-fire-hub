package com.smartfirehub.dbexecutor.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.SQLExceptionOverride.Override;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;

/** HikariCP 6 에서 빠진 「문장 시간 초과 커넥션 퇴출」을 되살린 판정 규칙(WD-791). */
class HikariTimeoutEvictionOverrideTest {

  private final HikariTimeoutEvictionOverride override = new HikariTimeoutEvictionOverride();

  /** Tibero queryTimeout 취소(SQLTimeoutException, -12040)는 반드시 퇴출한다 — HikariCP 5.1.0 기본 동작. */
  @Test
  void timeoutMustEvict() {
    assertThat(override.adjudicate(new SQLTimeoutException("취소", "HY008", -12040)))
        .isEqualTo(Override.MUST_EVICT);
  }

  /** 그 밖의 예외는 Hikari 기본 판정(08xxx 등)에 맡긴다. */
  @Test
  void otherExceptionsFollowHikariDefaults() {
    assertThat(override.adjudicate(new SQLException("문법", "42000")))
        .isEqualTo(Override.CONTINUE_EVICT);
    assertThat(override.adjudicate(new SQLTransientConnectionException("대기", "08001")))
        .isEqualTo(Override.CONTINUE_EVICT);
  }
}
