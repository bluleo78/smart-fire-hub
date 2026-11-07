package com.smartfirehub.global.platformdb.tibero;

import com.smartfirehub.global.platformdb.ConditionalOnTiberoPlatformDb;
import com.smartfirehub.global.platformdb.OutboxWakeupChannel;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.stereotype.Component;

/**
 * {@link OutboxWakeupChannel} 의 Tibero 구현(WD-493) — {@code DBMS_ALERT} 의 {@code SIGNAL} / {@code
 * WAITONE} 으로 PG 의 {@code NOTIFY}/{@code LISTEN} 을 대신한다.
 *
 * <p><b>발행.</b> 풀 커넥션을 잠깐 빌려 자동 커밋으로 {@code SIGNAL} 한다. DBMS_ALERT 의 신호는 발행 트랜잭션이 커밋돼야 수신자에게 전달되므로
 * 자동 커밋이어야 호출자 트랜잭션과 무관하게 즉시 간다(인터페이스 계약).
 *
 * <p><b>구독.</b> HikariCP 풀 밖의 전용 연결 하나로 {@code REGISTER} 한 뒤 {@code WAITONE} 을 반복한다. {@code
 * WAITONE} 은 최대 N초 서버에서 블록하므로 풀 커넥션을 오래 점유하지 않도록 PG 판과 같이 풀 밖 연결을 쓴다. 종료할 때는 {@code REMOVE} 로 등록을
 * 지워 신호가 서버에 쌓이지 않게 한다 — 등록이 남으면 구독자가 없는데도 알림이 계속 쌓인다.
 *
 * <p><b>알림 이름은 스키마별.</b> DBMS_ALERT 이름은 인스턴스 전역이다 — 같은 Tibero 인스턴스의 다른 설치본·테스트 계정이 같은 이름을 쓰면 남의 신호에
 * 깨어나고 남을 깨운다. 그래서 {@link #ALERT_NAME_PREFIX} 뒤에 플랫폼 계정 현재 스키마의 짧은 해시(16진 8자)를 붙인다(총 22자, 30자 제한
 * 안). 스키마는 풀 커넥션에서 처음 쓸 때 한 번 읽어 둔다.
 *
 * <p><b>테넌트 배선 제외.</b> 도메인 행을 읽거나 쓰지 않으므로 RLS 의 영향을 받지 않는다. DBMS_ALERT 는 PUBLIC 실행 권한이라 추가 GRANT 가
 * 필요 없다.
 */
@Component
@ConditionalOnTiberoPlatformDb
public class TiberoOutboxWakeupChannel implements OutboxWakeupChannel {

  private static final Logger log = LoggerFactory.getLogger(TiberoOutboxWakeupChannel.class);

  /** 아웃박스 신호 이름의 앞부분 — 뒤에 스키마 해시가 붙는다(DBMS_ALERT 이름은 대문자·30자 이내가 안전하다). */
  static final String ALERT_NAME_PREFIX = "FH_OUTBOX_NEW_";

  /** DBMS_ALERT 이름 길이 상한(Oracle 규칙과 같다). */
  static final int MAX_ALERT_NAME_LENGTH = 30;

  /** WAITONE 호출 — 구독 루프 밖에서 한 번 준비해 재사용한다. */
  static final String WAITONE_SQL = "BEGIN DBMS_ALERT.WAITONE(?, ?, ?, ?); END;";

  /** WAITONE 상태: 신호를 받음. */
  private static final int STATUS_SIGNALED = 0;

  /** WAITONE 상태: 시간 초과(신호 없음). */
  private static final int STATUS_TIMEOUT = 1;

  /** 발행용 풀 커넥션 원천. */
  private final DataSource dataSource;

  /** 구독 전용 커넥션 공급자 — 운영에서는 풀 밖 DriverManager 연결, 테스트에서는 주입. */
  private final Callable<Connection> listenConnection;

  /** 처음 쓸 때 풀 커넥션의 스키마로 정한 알림 이름 — 이후로는 바뀌지 않는다. */
  private volatile String alertName;

  /** 운영 생성자 — 구독 연결은 풀 밖에서 {@link DriverManager} 로 직접 연다. */
  public TiberoOutboxWakeupChannel(DataSource dataSource, DataSourceProperties properties) {
    this(
        dataSource,
        () ->
            DriverManager.getConnection(
                properties.determineUrl(),
                properties.determineUsername(),
                properties.determinePassword()));
  }

  /** 테스트용 생성자 — 구독 연결 공급자를 직접 받는다. */
  TiberoOutboxWakeupChannel(DataSource dataSource, Callable<Connection> listenConnection) {
    this.dataSource = dataSource;
    this.listenConnection = listenConnection;
  }

  /** SIGNAL 발행. 실패해도 폴백 폴링이 처리하므로 warn 로그만 남기고 삼킨다. */
  @Override
  public void publish() {
    try {
      String name = alertName();
      try (Connection conn = dataSource.getConnection()) {
        conn.setAutoCommit(true);
        try (CallableStatement cs = conn.prepareCall("BEGIN DBMS_ALERT.SIGNAL(?, ?); END;")) {
          cs.setString(1, name);
          // 빈 문자열은 NULL 로 취급돼 JDBC-14208(Invalid parameter)로 거절된다(실측) — 내용 없는 고정 값을 쓴다.
          cs.setString(2, "1");
          cs.execute();
        }
      }
    } catch (Exception e) {
      log.warn("Failed to SIGNAL outbox alert: {}", e.getMessage());
    }
  }

  /**
   * 전용 연결로 REGISTER 후 WAITONE 을 반복한다. 연결 오류는 던지고, 어떤 경우든 REMOVE 시도 후 연결을 닫는다.
   *
   * <p>WAITONE 상태가 0(신호)·1(시간 초과)이 아니면 알 수 없는 상태이므로 {@link IllegalStateException} 을 던져 호출자가 재연결하게
   * 한다.
   */
  @Override
  public void listen(BooleanSupplier running, Duration pollTimeout, Runnable onSignal)
      throws Exception {
    String name = alertName();
    Connection conn = null;
    CallableStatement wait = null;
    try {
      conn = listenConnection.call();
      conn.setAutoCommit(true); // 열린 트랜잭션을 남기지 않는다
      try (CallableStatement cs = conn.prepareCall("BEGIN DBMS_ALERT.REGISTER(?); END;")) {
        cs.setString(1, name);
        cs.execute();
      }
      // WAITONE 은 루프 밖에서 한 번만 준비한다 — 몇 초마다 도는 루프에서 매번 문장을 새로 준비하면 서버 커서를 쓸데없이 만들고 닫는다.
      wait = conn.prepareCall(WAITONE_SQL);
      wait.setString(1, name);
      wait.registerOutParameter(2, Types.VARCHAR);
      wait.registerOutParameter(3, Types.INTEGER);
      wait.setInt(4, waitSeconds(pollTimeout));
      while (running.getAsBoolean()) {
        wait.execute();
        int status = wait.getInt(3);
        if (status == STATUS_SIGNALED) {
          onSignal.run();
        } else if (status != STATUS_TIMEOUT) {
          throw new IllegalStateException("DBMS_ALERT.WAITONE 가 알 수 없는 상태를 돌려줌: " + status);
        }
      }
    } finally {
      if (wait != null) {
        try {
          wait.close();
        } catch (Exception closeEx) {
          log.debug("outbox WAITONE statement close error (ignored)", closeEx);
        }
      }
      if (conn != null) {
        // 등록을 지워 구독자 없이 신호가 쌓이지 않게 한다. 실패는 연결이 이미 죽은 경우라 무시한다.
        try (CallableStatement cs = conn.prepareCall("BEGIN DBMS_ALERT.REMOVE(?); END;")) {
          cs.setString(1, name);
          cs.execute();
        } catch (Exception removeEx) {
          log.debug("outbox alert REMOVE error (ignored)", removeEx);
        }
        try {
          conn.close();
        } catch (Exception closeEx) {
          log.debug("outbox listener connection close error (ignored)", closeEx);
        }
      }
    }
  }

  /**
   * 이 설치본의 알림 이름 — 처음 부를 때 풀 커넥션에서 현재 스키마를 읽어 정하고 이후로는 그 값을 쓴다. 발행과 구독이 반드시 같은 이름을 써야 하므로 한 번 정한 값을
   * 바꾸지 않는다.
   */
  String alertName() throws SQLException {
    String name = alertName;
    if (name == null) {
      synchronized (this) {
        name = alertName;
        if (name == null) {
          try (Connection conn = dataSource.getConnection()) {
            name = alertNameFor(TiberoPlatformLocks.currentSchema(conn));
          }
          alertName = name;
        }
      }
    }
    return name;
  }

  /** 스키마 → 알림 이름: 접두사 + 스키마 SHA-256 앞 4바이트의 대문자 16진 8자. 결정적이라 같은 스키마의 모든 인스턴스가 같은 이름을 쓴다. */
  static String alertNameFor(String schema) {
    int h = (int) (TiberoPlatformLocks.hash64(schema) >>> 32);
    return ALERT_NAME_PREFIX + String.format("%08X", h);
  }

  /**
   * 대기 시간을 WAITONE 의 초 단위로 환산한다 — 1초 미만 자투리는 올림하고 최소 1초(0 이면 즉시 반환해 공회전하므로), 최대 {@link
   * Integer#MAX_VALUE}(int 인자로 넘기므로 넘치지 않게 자른다).
   */
  static int waitSeconds(Duration pollTimeout) {
    long seconds = pollTimeout.getSeconds();
    if (pollTimeout.getNano() > 0 && seconds < Long.MAX_VALUE) {
      seconds++;
    }
    return (int) Math.min(Integer.MAX_VALUE, Math.max(1, seconds));
  }
}
