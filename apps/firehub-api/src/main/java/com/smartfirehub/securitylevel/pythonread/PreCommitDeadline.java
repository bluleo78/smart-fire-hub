package com.smartfirehub.securitylevel.pythonread;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Duration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

/**
 * 커밋 직전 좁히기 한 번의 <b>시간 예산(마감 시각)</b>(WD-664 6 → WD-701 3차에서 두 백엔드 공용으로 옮김).
 *
 * <p><b>왜 상한이 필요한가.</b> 커밋 직전 좁히기는 사용자 트랜잭션의 커밋 직전 단계에서 <b>플랫폼 커넥션을 쥔 채</b> 다른 저장소에 접속해 REVOKE 를
 * 보낸다 — Tibero 판은 외부 직결 접속 → REVOKE DDL → (실패가 있으면) WD-645 재판정을, PG 분리 설치 판({@link
 * PythonReadGrantSync#narrowDatasetInCurrentTx})은 데이터 저장소 연결 → 잠금 → REVOKE → 커밋을 한다. 상한이 없으면 느린 저장소
 * 하나가 요청마다 플랫폼 풀 커넥션을 오래 쥐어 풀을 바닥낼 수 있다. 상한을 넘기면 남은 일을 하지 않고 "확인 못 함" 으로 돌아가 리스너의 대체 경로(토큰 무효화·슬롯
 * 끊기 + 커밋 뒤 실행기의 비동기 좁히기, 회수 필수 작업)로 막는다 — 커밋을 무한정 붙잡지 않고, 막기도 빠뜨리지 않는다(fail-closed).
 *
 * <p>남은 예산으로 잠금 대기·접속 상한·queryTimeout 을 정하고, 문장을 만들 때마다 예산을 확인한다. 문장 확인은 연결을 감싼 대리 객체({@link
 * #bind})가 한 곳에서 한다 — 카탈로그 질의가 문장을 직접 만들기 때문에 호출부마다 넣지 않는다.
 */
final class PreCommitDeadline {

  /**
   * 커밋 직전 좁히기의 시간 상한 묶음(WD-664 6).
   *
   * @param total 좁히기 전체 예산 — 테넌트 잠금 대기·접속·문장을 모두 포함한다
   * @param connect 접속(로그인 포함) 상한 — 남은 예산이 더 짧으면 그만큼
   * @param statement 문장·카탈로그 질의 하나의 queryTimeout 상한 — 남은 예산이 더 짧으면 그만큼(JDBC 는 초 단위라 올림, 최소 1초)
   */
  record Bounds(Duration total, Duration connect, Duration statement) {}

  /**
   * 운영 상한(WD-664 6) — 전체 8초·접속 3초·문장 3초. 두 백엔드(Tibero 커밋 직전 좁히기, PG 분리 설치 커밋 직전 좁히기)가 같은 값을 쓴다.
   *
   * <ul>
   *   <li><b>문장 3초</b>: 다른 커밋 직전 상한({@link PythonReadGrantSync#NARROW_LOCK_WAIT}, {@link
   *       TiberoPythonReadGrantSync#PRE_COMMIT_EPOCH_LOCK_WAIT})과 같은 값. 테이블 하나의 REVOKE·카탈로그 조회는
   *       정상이면 수십 ms 다.
   *   <li><b>접속 3초</b>: 같은 망의 로그인은 정상이면 1초 안이다. 운영 접속기 기본 10초는 운영자 경로(연결 테스트·생성) 기준이라 커밋 직전에는 길다.
   *   <li><b>전체 8초</b>: 잠금 대기 3초 + 접속 3초를 다 써도 문장 몇 개를 보낼 여유를 남기되, 최악의 커밋 지연을 한 자릿수 초로
   *       묶는다(queryTimeout 의 초 단위 올림으로 최대 1초쯤 더 걸릴 수 있다. 예산을 넘겨 대체 경로로 가면 리스너가 같은 트랜잭션에서 토큰 세대 번호를
   *       올리며 {@link TiberoPythonReadGrantSync#PRE_COMMIT_EPOCH_LOCK_WAIT}(3초)까지 더 기다릴 수 있어, 커밋 직전
   *       단계 전체의 최악은 약 12초다). 넘기는 일은 드물고, 넘기면 대체 경로가 막으므로 보안 공백은 없고 실행 중 스크립트가 한 번 끊기거나 토큰 무효로 실패하는
   *       비용만 든다.
   * </ul>
   *
   * 실서버 드라이버가 DDL 의 queryTimeout·접속 상한을 실제로 지키는지는 사람이 따로 확인한다(TC 는 가짜 연결·실제 PG 로 상한 값과 예산 초과 처리만
   * 본다). 드라이버가 queryTimeout 을 지키지 않아도 예산은 문장과 문장 사이에서 지켜진다 — 그때 최악 지연은 "예산 + 느린 문장 하나" 다.
   */
  static final Bounds DEFAULT_BOUNDS =
      new Bounds(Duration.ofSeconds(8), Duration.ofSeconds(3), Duration.ofSeconds(3));

  /**
   * PG 의 문장 취소(query_canceled) SQLState — pgjdbc 는 queryTimeout 취소를 SQLTimeoutException 이 아니라 이 상태로
   * 알린다.
   */
  private static final String PG_QUERY_CANCELED = "57014";

  /** 커밋 직전 좁히기가 시간 예산을 넘겼다는 표시(WD-664 6) — 남은 문장을 보내지 않고 대체 경로로 간다. */
  static final class BudgetExceeded extends RuntimeException {
    BudgetExceeded(String step) {
      super("커밋 직전 좁히기 시간 예산 초과(" + step + ")");
    }
  }

  private final Bounds bounds;
  private final long deadlineNanos;

  PreCommitDeadline(Bounds bounds) {
    this.bounds = bounds;
    this.deadlineNanos = System.nanoTime() + bounds.total().toNanos();
  }

  /** 이 예산의 상한 묶음 — 로그용. */
  Bounds bounds() {
    return bounds;
  }

  private long remainingNanos() {
    return deadlineNanos - System.nanoTime();
  }

  /** 남은 예산이 없으면 던진다. */
  void check(String step) {
    if (remainingNanos() <= 0) {
      throw new BudgetExceeded(step);
    }
  }

  /** {@code limit} 와 남은 예산 중 짧은 쪽(최소 1ms — 0 은 "상한 없음" 으로 읽히는 API 가 있다). */
  Duration cap(Duration limit) {
    long nanos = Math.min(limit.toNanos(), remainingNanos());
    return Duration.ofNanos(Math.max(nanos, Duration.ofMillis(1).toNanos()));
  }

  /** 접속 상한 — 접속 상한과 남은 예산 중 짧은 쪽. */
  Duration connectTimeout() {
    return cap(bounds.connect());
  }

  /** 이번 문장의 queryTimeout 초 — 문장 상한과 남은 예산 중 짧은 쪽을 초 단위로 올린다(0 은 무제한이라 최소 1초). */
  int queryTimeoutSeconds() {
    long millis = cap(bounds.statement()).toMillis();
    return (int) Math.max(1L, (millis + 999L) / 1000L);
  }

  /**
   * 남은 예산과 무관한 문장 상한(초, 최소 1초) — 예산을 넘긴 뒤에도 꼭 보내야 하는 정리 문장(SAVEPOINT 되돌리기)용. 그래도 느린 저장소에서 무한정 멈추지는
   * 않게 한다.
   */
  int statementTimeoutSeconds() {
    long millis = bounds.statement().toMillis();
    return (int) Math.max(1L, (millis + 999L) / 1000L);
  }

  /** 연결 네트워크 상한의 최소값(밀리초) — 0 은 무제한이고, 예산을 넘긴 뒤의 되돌리기·닫기에도 짧은 여유를 준다. */
  static final int MIN_NETWORK_TIMEOUT_MILLIS = 1000;

  /**
   * 따로 빌린 연결의 네트워크 상한(밀리초, {@link Connection#setNetworkTimeout}) — 남은 예산, 최소 {@link
   * #MIN_NETWORK_TIMEOUT_MILLIS}. 문장 queryTimeout 은 서버에 취소를 보내는 방식이라 네트워크가 소리 없이 패킷을 버리면
   * commit·rollback· 취소 자체가 응답을 영원히 기다린다(데이터 저장소 풀에 socketTimeout 설정 없음) — 소켓 읽기 상한으로 이를 끊는다.
   */
  int networkTimeoutMillis() {
    long millis = Math.max(0L, remainingNanos()) / 1_000_000L;
    return (int) Math.min(Integer.MAX_VALUE, Math.max(MIN_NETWORK_TIMEOUT_MILLIS, millis));
  }

  /**
   * 이 실패가 시간 상한·접속 때문인가 — 예산을 넘겼거나, 예산 초과 표시·질의 시간 초과(드라이버 queryTimeout — JDBC 표준 예외, Spring 번역 예외,
   * PG 의 query_canceled SQLState)가 원인 사슬에 있거나, 접속이 실패했다(접속 상한 초과 포함 — 접속하지 못했으니 아무것도 회수하지 않았다). 이런
   * 실패는 "어디까지 회수했는지 모름" 이라 부르는 쪽이 새 등급에서 못 읽는 모든 슬롯을 막는다.
   */
  boolean exceededBy(Throwable e) {
    if (remainingNanos() <= 0) {
      return true;
    }
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof BudgetExceeded
          || t instanceof SQLTimeoutException
          || t instanceof QueryTimeoutException
          || t instanceof DataAccessResourceFailureException) {
        return true;
      }
      if (t instanceof SQLException sql && PG_QUERY_CANCELED.equals(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  /**
   * 연결을 감싸 문장(Statement·PreparedStatement·CallableStatement)을 만들 때마다 예산을 확인하고(넘겼으면 {@link
   * BudgetExceeded}) queryTimeout 을 건다. 그 밖의 호출은 그대로 넘긴다. 연결을 닫거나 커밋·롤백하는 것은 대리 객체가 아니라 원래 연결로
   * 한다(예산을 넘긴 뒤에도 정리는 해야 하므로) — 부르는 쪽 몫이다.
   */
  Connection bind(Connection conn) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              String name = method.getName();
              boolean creates =
                  name.equals("createStatement")
                      || name.equals("prepareStatement")
                      || name.equals("prepareCall");
              if (creates) {
                check("문장 " + name);
              }
              Object result;
              try {
                result = method.invoke(conn, args);
              } catch (InvocationTargetException ite) {
                throw ite.getCause();
              }
              if (creates && result instanceof Statement st) {
                st.setQueryTimeout(queryTimeoutSeconds());
              }
              return result;
            });
  }
}
