package com.smartfirehub.dataset.storage.external;

import com.smartfirehub.dataset.storage.tibero.TiberoDialect;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * JDBC 로 외부 DB(현재 Tibero)에 접속하는 구현(WD-73).
 *
 * <p>Tibero 드라이버는 클래스패스에 없을 수 있어 접속 전에 {@code Class.forName} 으로 확인하고, 없으면 명확한 한국어 사유를 낸다.
 *
 * <p>접속 시간 제한은 {@code DriverManager.setLoginTimeout} 이 JVM 전역이라 쓰지 않는다. 대신 접속을 전용 데몬 스레드에서 실행하고
 * {@code Future.get(timeout)} 으로 기다린다. 시간이 지나면 작업을 취소(인터럽트)하고, 취소를 무시한 드라이버가 나중에 접속에 성공해도 그 커넥션을 닫아
 * 누수를 막는다.
 *
 * <p><b>스레드 수에 상한을 두지 않는다(캐시 풀).</b> 인터럽트를 무시하고 멈춘 접속은 시간 초과 뒤에도 스레드를 계속 붙잡는다. 고정 크기 풀이면 그런 접속 몇 개가
 * 모든 스레드를 차지해 이후의 모든 {@link #open} — 롤백 보상 DROP 접속까지 — 이 대기열에서 굶다가 시간 초과로 실패한다(대기 시간까지 시간 제한에
 * 포함되므로). 호출자는 운영자 전용 경로(연결 테스트·생성·검증)라 동시 접속 수가 작아 상한 없는 풀이 감당할 만하고, 쓰지 않는 스레드는 60초 뒤 사라진다. 멈춘 스레드
 * 자체를 끝내려면 드라이버의 접속 단위 connect-timeout 속성이 필요하며 WD-71 에서 실서버로 확인한다.
 */
@Slf4j
@Component
public class JdbcExternalDbConnector implements ExternalDbConnector {

  static final String DEFAULT_DRIVER_CLASS = TiberoDialect.DRIVER_CLASS;
  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  /** 테스트에서 주입하는 접속 함수. SQLException 을 그대로 던질 수 있다. */
  @FunctionalInterface
  interface ConnectFunction {
    Connection connect(String url, Properties props) throws SQLException;
  }

  private final String driverClassName;
  private final Duration timeout;
  private final ConnectFunction connect;
  private final ExecutorService executor;

  /** 스프링용 기본 생성자: Tibero 드라이버, 10초, {@code DriverManager.getConnection}. */
  public JdbcExternalDbConnector() {
    this(DEFAULT_DRIVER_CLASS, DEFAULT_TIMEOUT, DriverManager::getConnection);
  }

  /** 테스트용 생성자: 드라이버 클래스명·시간 제한·접속 함수를 주입한다. */
  JdbcExternalDbConnector(String driverClassName, Duration timeout, ConnectFunction connect) {
    this.driverClassName = driverClassName;
    this.timeout = timeout;
    this.connect = connect;
    // 멈춘 접속 하나가 다른 접속을 막지 않도록 상한 없는 캐시 풀을 쓴다(클래스 Javadoc). 멈춘 스레드를 끝내는
    // 드라이버 자체 connect-timeout 속성은 WD-71 에서 실서버로 확인한다.
    this.executor = Executors.newCachedThreadPool(new DaemonThreadFactory());
  }

  @Override
  public Connection open(ExternalDbTarget target, String username, String password)
      throws ExternalDbConnectionException {
    // 드라이버가 없으면 DriverManager 의 모호한 "No suitable driver" 대신 분명한 사유를 낸다.
    if (TiberoDialect.loadDriver(driverClassName) != null) {
      throw new ExternalDbConnectionException("Tibero JDBC 드라이버가 설치되지 않았습니다");
    }
    final String url;
    try {
      url = target.jdbcUrl();
    } catch (IllegalArgumentException e) {
      throw new ExternalDbConnectionException(e.getMessage());
    }
    Properties props = new Properties();
    props.setProperty("user", username);
    props.setProperty("password", password);

    // 늦게 도착한 커넥션을 닫을 수 있도록, 결과 전달 여부를 CompletableFuture 로 판정한다.
    CompletableFuture<Connection> result = new CompletableFuture<>();
    Future<?> task;
    try {
      task =
          executor.submit(
              () -> {
                try {
                  Connection c = connect.connect(url, props);
                  // 호출자가 이미 포기했다면(시간 초과) 아무도 받지 않으므로 직접 닫는다.
                  if (!result.complete(c)) {
                    JdbcClose.closeQuietly(c);
                  }
                } catch (Throwable t) {
                  result.completeExceptionally(t);
                }
              });
    } catch (RejectedExecutionException e) {
      throw new ExternalDbConnectionException("연결 실패: 접속기가 종료되었습니다");
    }

    try {
      return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      // 포기 표시를 먼저 하고 작업을 인터럽트한다. 그 사이 작업이 끝났다면 그 결과를 같은 규칙으로 돌려준다.
      if (result.completeExceptionally(e)) {
        task.cancel(true);
        throw new ExternalDbConnectionException("연결 시간이 초과되었습니다(" + timeout.toSeconds() + "초)");
      }
      return deliver(result, password);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      // 인터럽트 직전에 도착한 커넥션이 있으면 누수되지 않게 닫는다.
      try {
        // 이미 정상 도착한 커넥션만 닫는다. 실패로 끝난 경우 getNow 가 비검사 예외를 던질 수 있어 방어한다.
        if (!result.completeExceptionally(e)
            && result.isDone()
            && !result.isCompletedExceptionally()) {
          JdbcClose.closeQuietly(result.getNow(null));
        }
      } catch (RuntimeException ignored) {
        // 정리는 best-effort 이며 아래 취소와 예외 변환은 항상 실행돼야 한다.
      }
      task.cancel(true);
      throw new ExternalDbConnectionException("연결 실패: 대기 중 중단되었습니다");
    } catch (ExecutionException e) {
      throw mapFailure(e.getCause(), password);
    }
  }

  /** 이미 끝난 결과를 꺼낸다. 실패는 {@link ExternalDbConnectionException} 으로만 변환한다. */
  static Connection deliver(CompletableFuture<Connection> result, String password)
      throws ExternalDbConnectionException {
    try {
      return result.get();
    } catch (ExecutionException e) {
      throw mapFailure(e.getCause(), password);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ExternalDbConnectionException("연결 실패: 대기 중 중단되었습니다");
    } catch (java.util.concurrent.CancellationException e) {
      throw new ExternalDbConnectionException("연결 실패: 취소되었습니다");
    }
  }

  /** 접속 실패 원인을 사용자용 예외로 바꾼다. 드라이버 메시지에 비밀번호가 섞였으면 가린다. */
  private static ExternalDbConnectionException mapFailure(Throwable cause, String password) {
    if (cause instanceof SQLException sql) {
      String msg = sql.getMessage() == null ? "알 수 없는 오류" : sql.getMessage();
      if (password != null && !password.isEmpty()) {
        msg = msg.replace(password, "***");
      }
      return new ExternalDbConnectionException("연결 실패: " + msg);
    }
    // 비밀번호가 메시지에 섞일 가능성을 피하려고 타입만 알린다.
    log.warn("외부 DB 접속 중 예기치 않은 오류: {}", cause == null ? "unknown" : cause.getClass());
    return new ExternalDbConnectionException("연결 실패: 예기치 않은 오류가 발생했습니다");
  }

  /** 빈 종료 시 접속 스레드를 정리한다. */
  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }

  /** 이름 {@code external-db-connect-N} 의 데몬 스레드 — 응답 없는 접속이 JVM 종료를 막지 않게 한다. */
  private static final class DaemonThreadFactory implements ThreadFactory {
    private final AtomicInteger seq = new AtomicInteger(1);

    @Override
    public Thread newThread(Runnable r) {
      Thread t = new Thread(r, "external-db-connect-" + seq.getAndIncrement());
      t.setDaemon(true);
      return t;
    }
  }
}
