package com.smartfirehub.dataset.storage.tibero;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Tibero(Oracle 계열) 오류 판별 공용 헬퍼. DDL 저장소·행 저장소·커넥션 래퍼의 오류 번역이 같은 규칙으로 오류 코드를 보도록 한 곳에 둔다.
 *
 * <p>공개 클래스인 이유: {@code external} 패키지의 커넥션 래퍼(오류 번역기)도 {@link #hasCode} 를 쓴다.
 */
@Slf4j
public final class TiberoErrors {

  private TiberoErrors() {}

  /**
   * 예외 체인(최대 10단계)에 오류 코드가 있는지 — {@link SQLException#getErrorCode()} 또는 메시지 표기({@link #tag}). 커넥션
   * 래퍼가 {@link SQLException} 을 Spring 예외로 감싸므로 원인을 따라가고, 드라이버가 코드를 비워 두고 메시지에만 싣는 경우를 위해 메시지 표기도
   * 받아들인다.
   *
   * <p>코드는 부호까지 정확히 비교한다 — Tibero 자체 코드는 음수, Oracle 호환 코드는 양수라 절댓값으로 비교하면 서로 다른 오류가 섞인다.
   */
  public static boolean hasCode(Throwable error, int code) {
    String tag = tag(code);
    Throwable t = error;
    for (int depth = 0; t != null && depth < 10; depth++, t = t.getCause()) {
      if (t instanceof SQLException se && se.getErrorCode() == code) {
        return true;
      }
      String message = t.getMessage();
      if (message != null && message.contains(tag)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 서버가 응답하지 않거나 연결이 끊겨 난 실패인지(WD-80) — 문장 하나의 문제가 아니라 같은 커넥션의 다음 문장도 똑같이 실패할 상황이다.
   *
   * <p>시간 초과({@link SQLTimeoutException}, Tibero 는 문장 취소 {@code -12040} 으로도 알린다 — {@link
   * TiberoDialect#STATEMENT_CANCELED})와 연결 계열 예외·SQLState {@code 08xxx} 를 참으로 본다. 테이블 없음·권한 없음처럼
   * 서버가 문장을 거절한 실패는 거짓이다. 통계(행 수·빈 테이블 판정)가 남은 테이블마다 시간 제한만큼 기다리지 않고 그 회차를 멈추는 데 쓴다.
   */
  static boolean isUnresponsive(SQLException e) {
    if (e instanceof SQLTimeoutException
        || e instanceof SQLRecoverableException
        || e instanceof SQLTransientConnectionException
        || e instanceof SQLNonTransientConnectionException) {
      return true;
    }
    String state = e.getSQLState();
    if (state != null && state.startsWith("08")) {
      return true;
    }
    return hasAnyCode(e, TiberoDialect.STATEMENT_CANCELED);
  }

  /** 예외 체인에 주어진 코드 중 하나라도 있는지({@link TiberoDialect} 의 코드 묶음용). */
  public static boolean hasAnyCode(Throwable error, Set<Integer> codes) {
    for (int code : codes) {
      if (hasCode(error, code)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 오류 문자열에 주어진 코드 중 하나의 표기({@code JDBC-10021:}·{@code ORA-30926})가 있는지. 예외가 아니라 문자열만 있는
   * 경로(db-executor 응답에 구조화 코드가 없을 때의 폴백, WD-77)가 쓴다.
   */
  public static boolean messageHasAnyCode(String message, Set<Integer> codes) {
    if (message == null) {
      return false;
    }
    for (int code : codes) {
      if (message.contains(tag(code))) {
        return true;
      }
    }
    return false;
  }

  /**
   * 오류 코드의 메시지 표기. Oracle 호환 양수 코드는 {@code 942 → "ORA-00942"}, Tibero 음수 코드는 {@code -8033 →
   * "JDBC-8033:"}(실서버 메시지 형식 — 뒤의 콜론으로 -80330 같은 더 긴 코드와 구분한다).
   */
  private static String tag(int code) {
    return code < 0 ? "JDBC" + code + ":" : String.format(Locale.ROOT, "ORA-%05d", code);
  }

  /**
   * 주어진 커넥션에서 DDL 한 문장을 실행하되, 지정한 "객체 없음" 오류는 무시한다(그 밖의 오류는 그대로 던진다). DDL 저장소와 행 저장소가 같은 규칙을 쓰도록 한
   * 곳에 둔다. SQL 문장은 로그에 남기지 않는다.
   *
   * @return 실제로 실행됐으면 true, 무시할 "없음" 오류로 건너뛰었으면 false
   */
  static boolean runIgnoring(Connection c, String sql, TiberoDialect.NotFound... ignorable)
      throws SQLException {
    try (Statement st = c.createStatement()) {
      executeDdl(st, sql);
      return true;
    } catch (SQLException e) {
      for (TiberoDialect.NotFound nf : ignorable) {
        if (nf.matches(e)) {
          return false;
        }
      }
      throw e;
    }
  }

  /** 다른 DDL 이 같은 객체를 잡고 있어 기다리지 않고 즉시 실패(NOWAIT)했다는 Tibero 오류 코드. */
  static final int LOCK_NOWAIT = -12033;

  /** {@link #LOCK_NOWAIT} 재시도 사이의 대기(ms). 길이 4 → 시도는 최대 5회(첫 시도 + 재시도 4번). */
  static final long[] BACKOFF_MILLIS = {50, 100, 200, 400};

  /** 재시도 대기 주입점 — 시험이 실제로 자지 않고 대기값만 기록하게 한다. */
  @FunctionalInterface
  interface Sleeper {
    void sleep(long millis) throws InterruptedException;
  }

  /** 운영용 — 실제로 잔다. */
  static void executeDdl(Statement st, String sql) throws SQLException {
    executeDdl(st, sql, Thread::sleep);
  }

  /**
   * DDL 한 문장을 실행하되 {@link #LOCK_NOWAIT}(-12033) 으로만 짧게 다시 보낸다(WD-739).
   *
   * <p>왜: Tibero 는 세션에 DDL 잠금 대기 설정이 없어(DDL_LOCK_TIMEOUT 은 -7197) 겹친 DDL 이 기다리지 않고 즉시 실패한다. 커밋 뒤 슬롯
   * GRANT 동기화와 사용자 DDL 이 겹치면 그 실패가 사용자에게 보였다. NOWAIT 실패는 문장이 실행되지 않았다는 뜻이라 같은 한 문장을 다시 보내도 안전하다 —
   * 여러 문장을 묶은 작업 전체를 다시 돌리는 일은 하지 않는다. 다른 오류는 재시도하지 않고, 대기가 소진되면 마지막 예외를 그대로 던진다. 대기 중 인터럽트되면 표시를
   * 되살리고 그 시도의 예외를 던진다.
   */
  static void executeDdl(Statement st, String sql, Sleeper sleeper) throws SQLException {
    for (int attempt = 0; ; attempt++) {
      try {
        st.execute(sql);
        return;
      } catch (SQLException e) {
        // 코드를 비우고 메시지에만 싣는 드라이버도 같은 규칙으로 잡는다.
        if (!hasCode(e, LOCK_NOWAIT)) {
          throw e;
        }
        if (attempt >= BACKOFF_MILLIS.length) {
          log.warn("DDL -12033 재시도 소진 — 시도 {}회, 문장: {}", attempt + 1, head(sql));
          throw e;
        }
        log.debug("DDL -12033 — {}ms 뒤 재시도, 문장: {}", BACKOFF_MILLIS[attempt], head(sql));
        try {
          sleeper.sleep(BACKOFF_MILLIS[attempt]);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
  }

  private static String head(String sql) {
    return sql.length() <= 80 ? sql : sql.substring(0, 80);
  }
}
