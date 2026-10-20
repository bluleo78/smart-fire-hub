package com.smartfirehub.dataset.storage.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** 외부 DB 접속기 단위 테스트. 실제 Tibero 드라이버·서버 없이 주입한 접속 함수로 검증한다. */
class JdbcExternalDbConnectorTest {

  private static final ExternalDbTarget TARGET =
      new ExternalDbTarget(DataStoreBackend.TIBERO, "192.168.179.80", 8629, "tibero");

  /** 테스트 클래스패스에 실제로 있는 클래스 — 드라이버 사전 확인을 통과시키기 위해 쓴다. */
  private static final String PRESENT_CLASS = "java.lang.String";

  @Test
  void jdbcUrl_tibero() {
    assertThat(TARGET.jdbcUrl()).isEqualTo("jdbc:tibero:thin:@192.168.179.80:8629:tibero");
  }

  @Test
  void jdbcUrl_nonTibero_rejected() {
    ExternalDbTarget mysql = new ExternalDbTarget(DataStoreBackend.MYSQL, "h", 3306, "d");
    assertThatThrownBy(mysql::jdbcUrl).isInstanceOf(IllegalArgumentException.class);
  }

  /** 드라이버 없음 — 없는 클래스명을 주입해, 개발 머신에 실제 드라이버 jar 가 있어도 늘 같은 조건으로 검증한다. */
  @Test
  void open_missingDriver_clearMessage() {
    JdbcExternalDbConnector connector =
        new JdbcExternalDbConnector(
            "com.smartfirehub.test.NoSuchTiberoDriver",
            Duration.ofSeconds(2),
            (url, props) -> {
              throw new AssertionError("드라이버가 없으면 접속을 시도하지 않아야 한다");
            });
    try {
      assertThatThrownBy(() -> connector.open(TARGET, "u", "p"))
          .isInstanceOf(ExternalDbConnectionException.class)
          .hasMessage("Tibero JDBC 드라이버가 설치되지 않았습니다");
    } finally {
      connector.shutdown();
    }
  }

  @Test
  void open_success_returnsConnection() throws Exception {
    Connection conn = mock(Connection.class);
    JdbcExternalDbConnector connector =
        new JdbcExternalDbConnector(PRESENT_CLASS, Duration.ofSeconds(2), (url, props) -> conn);
    try {
      assertThat(connector.open(TARGET, "u", "p")).isSameAs(conn);
    } finally {
      connector.shutdown();
    }
  }

  @Test
  void open_timeout_boundedAndCancelled() throws Exception {
    AtomicBoolean interrupted = new AtomicBoolean();
    CountDownLatch done = new CountDownLatch(1);
    JdbcExternalDbConnector connector =
        new JdbcExternalDbConnector(
            PRESENT_CLASS,
            Duration.ofMillis(200),
            (url, props) -> {
              try {
                Thread.sleep(5000);
              } catch (InterruptedException e) {
                interrupted.set(true);
                done.countDown();
              }
              return mock(Connection.class);
            });
    try {
      long start = System.currentTimeMillis();
      assertThatThrownBy(() -> connector.open(TARGET, "u", "p"))
          .isInstanceOf(ExternalDbConnectionException.class)
          .hasMessageStartingWith("연결 시간이 초과되었습니다");
      assertThat(System.currentTimeMillis() - start).isLessThan(1000);
      // Future 취소가 작업 스레드를 인터럽트해야 한다.
      assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(interrupted).isTrue();
    } finally {
      connector.shutdown();
    }
  }

  @Test
  void open_lateConnectionAfterTimeout_isClosed() throws Exception {
    Connection late = mock(Connection.class);
    JdbcExternalDbConnector connector =
        new JdbcExternalDbConnector(
            PRESENT_CLASS,
            Duration.ofMillis(100),
            (url, props) -> {
              // 인터럽트를 무시하고 늦게 접속에 성공하는 드라이버를 흉내 낸다.
              long end = System.currentTimeMillis() + 400;
              while (System.currentTimeMillis() < end) {
                try {
                  Thread.sleep(50);
                } catch (InterruptedException ignored) {
                  // 무시
                }
              }
              return late;
            });
    try {
      assertThatThrownBy(() -> connector.open(TARGET, "u", "p"))
          .isInstanceOf(ExternalDbConnectionException.class);
      verify(late, timeout(3000)).close();
    } finally {
      connector.shutdown();
    }
  }

  @Test
  void open_sqlException_messageHasNoPassword() {
    String password = "S3cretPassw0rdXyz";
    JdbcExternalDbConnector connector =
        new JdbcExternalDbConnector(
            PRESENT_CLASS,
            Duration.ofSeconds(2),
            (url, props) -> {
              throw new SQLException("ORA-01017 invalid username/password");
            });
    try {
      assertThatThrownBy(() -> connector.open(TARGET, "scott", password))
          .isInstanceOf(ExternalDbConnectionException.class)
          .hasMessage("연결 실패: ORA-01017 invalid username/password")
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain(password));
    } finally {
      connector.shutdown();
    }
  }

  @Test
  void open_sqlExceptionEchoingPassword_isMasked() {
    String password = "S3cretPassw0rdXyz";
    JdbcExternalDbConnector connector =
        new JdbcExternalDbConnector(
            PRESENT_CLASS,
            Duration.ofSeconds(2),
            (url, props) -> {
              throw new SQLException("bad login for " + password + " rejected");
            });
    try {
      assertThatThrownBy(() -> connector.open(TARGET, "scott", password))
          .isInstanceOf(ExternalDbConnectionException.class)
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain(password));
    } finally {
      connector.shutdown();
    }
  }

  /**
   * 인터럽트를 무시하고 영원히 멈춘 접속 5개가 있어도 6번째 접속은 바로 성공해야 한다 — 고정 크기 풀이면 멈춘 스레드가 풀을 다 차지해 이후 접속(롤백 보상 DROP
   * 포함)이 대기열에서 굶다가 시간 초과로 실패한다.
   */
  @Test
  void open_hungConnectsIgnoringInterrupt_doNotStarveLaterConnect() throws Exception {
    Connection ok = mock(Connection.class);
    CountDownLatch release = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger hungStarted =
        new java.util.concurrent.atomic.AtomicInteger();
    JdbcExternalDbConnector connector =
        new JdbcExternalDbConnector(
            PRESENT_CLASS,
            Duration.ofMillis(300),
            (url, props) -> {
              if (!props.getProperty("user").startsWith("hung")) {
                return ok;
              }
              hungStarted.incrementAndGet();
              // 취소(인터럽트)를 무시하고 해제될 때까지 멈춘 드라이버를 흉내 낸다.
              while (true) {
                try {
                  release.await();
                  return mock(Connection.class);
                } catch (InterruptedException ignored) {
                  // 무시
                }
              }
            });
    try {
      for (int i = 1; i <= 5; i++) {
        String user = "hung" + i;
        assertThatThrownBy(() -> connector.open(TARGET, user, "p"))
            .isInstanceOf(ExternalDbConnectionException.class)
            .hasMessageStartingWith("연결 시간이 초과되었습니다");
      }
      // 다섯 개 모두 실제로 스레드를 잡고 멈춰 있다(대기열에서 기다린 게 아니다).
      assertThat(hungStarted.get()).isEqualTo(5);

      long start = System.currentTimeMillis();
      assertThat(connector.open(TARGET, "fresh", "p")).isSameAs(ok);
      assertThat(System.currentTimeMillis() - start).isLessThan(300);
    } finally {
      release.countDown();
      connector.shutdown();
    }
  }

  /** 시간 초과 직후 작업이 실패로 끝난 경합 분기: 비검사 예외가 새지 않고 접속 예외로만 변환돼야 한다. */
  @Test
  void deliver_failedAfterTimeout_mapsToConnectionException() {
    java.util.concurrent.CompletableFuture<Connection> failed =
        new java.util.concurrent.CompletableFuture<>();
    failed.completeExceptionally(new SQLException("ORA-12170 timeout"));
    assertThatThrownBy(() -> JdbcExternalDbConnector.deliver(failed, "pw"))
        .isInstanceOf(ExternalDbConnectionException.class)
        .hasMessage("연결 실패: ORA-12170 timeout");

    java.util.concurrent.CompletableFuture<Connection> unexpected =
        new java.util.concurrent.CompletableFuture<>();
    unexpected.completeExceptionally(new IllegalStateException("boom"));
    assertThatThrownBy(() -> JdbcExternalDbConnector.deliver(unexpected, "pw"))
        .isInstanceOf(ExternalDbConnectionException.class);
  }

  /** 대기 중 호출 스레드가 인터럽트되면 접속 예외로 끝나고, 플래그가 복원되며, 늦게 도착한 커넥션은 닫힌다. */
  @Test
  void open_interruptedWhileWaiting_restoresFlagAndClosesLateConnection() throws Exception {
    Connection late = mock(Connection.class);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    JdbcExternalDbConnector connector =
        new JdbcExternalDbConnector(
            PRESENT_CLASS,
            Duration.ofSeconds(10),
            (url, props) -> {
              started.countDown();
              while (true) {
                try {
                  if (release.await(5, TimeUnit.SECONDS)) {
                    return late;
                  }
                  return late;
                } catch (InterruptedException ignored) {
                  // 인터럽트를 무시하고 계속 기다리다 늦게 접속에 성공하는 드라이버를 흉내 낸다.
                }
              }
            });
    java.util.concurrent.atomic.AtomicReference<Throwable> thrown =
        new java.util.concurrent.atomic.AtomicReference<>();
    AtomicBoolean flag = new AtomicBoolean();
    Thread caller =
        new Thread(
            () -> {
              try {
                connector.open(TARGET, "u", "p");
              } catch (Throwable t) {
                thrown.set(t);
              }
              flag.set(Thread.currentThread().isInterrupted());
            });
    try {
      caller.start();
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      caller.interrupt();
      caller.join(3000);
      assertThat(thrown.get()).isInstanceOf(ExternalDbConnectionException.class);
      assertThat(flag).isTrue();
      release.countDown();
      verify(late, timeout(3000)).close();
    } finally {
      connector.shutdown();
    }
  }
}
