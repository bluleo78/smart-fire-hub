package com.smartfirehub.securitylevel.pythonread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 커밋 직전 좁히기 시간 예산의 실패 판정(WD-701 3차 — Tibero 판에서 두 백엔드 공용으로 옮김). PG 분리 설치 판이 쓰려면 pgjdbc 의 문장
 * 취소(queryTimeout)를 알아봐야 한다 — pgjdbc 는 SQLTimeoutException 이 아니라 SQLState 57014(query_canceled)로
 * 알린다. 못 알아보면 "시간과 무관한 실패" 로 보아 모든 슬롯을 막는다(과하게 막음).
 */
class PreCommitDeadlineTest {

  private static PreCommitDeadline roomy() {
    return new PreCommitDeadline(
        new PreCommitDeadline.Bounds(
            Duration.ofMinutes(1), Duration.ofSeconds(3), Duration.ofSeconds(3)));
  }

  /** 원인 사슬의 57014(PG 문장 취소)는 시간 상한 실패다. 고치기 전 판정은 JDBC·Spring 시간 초과 예외만 보았다. */
  @Test
  void pgQueryCanceled_inCauseChain_isTimeBound() {
    RuntimeException wrapped =
        new RuntimeException("jOOQ", new SQLException("canceling statement", "57014"));
    assertThat(roomy().exceededBy(wrapped)).isTrue();
  }

  /** 시간과 무관한 SQL 오류(권한 등)는 시간 상한 실패가 아니다 — 부르는 쪽이 그대로 던진다. */
  @Test
  void otherSqlState_isNotTimeBound() {
    assertThat(roomy().exceededBy(new RuntimeException(new SQLException("denied", "42501"))))
        .isFalse();
  }

  /** 예산을 다 쓰면 원인과 무관하게 시간 상한 실패이고, 다음 문장 전에 확인이 던진다. */
  @Test
  void exhaustedBudget_isTimeBound_andCheckThrows() {
    PreCommitDeadline spent =
        new PreCommitDeadline(
            new PreCommitDeadline.Bounds(
                Duration.ofNanos(1), Duration.ofNanos(1), Duration.ofNanos(1)));
    assertThat(spent.exceededBy(new IllegalStateException("x"))).isTrue();
    try {
      spent.check("문장");
      throw new AssertionError("예산 초과면 던져야 한다");
    } catch (PreCommitDeadline.BudgetExceeded expected) {
      assertThat(expected).hasMessageContaining("문장");
    }
  }

  /** 따로 빌린 연결의 네트워크 상한(WD-701 3차 재리뷰) — 남은 예산이지만 최소 1초다(0 은 무제한, 예산을 넘긴 뒤 되돌리기·닫기에도 여유). */
  @Test
  void networkTimeout_isRemainingBudget_withOneSecondFloor() {
    assertThat(roomy().networkTimeoutMillis()).isBetween(50_000, 60_000);
    PreCommitDeadline spent =
        new PreCommitDeadline(
            new PreCommitDeadline.Bounds(
                Duration.ofNanos(1), Duration.ofNanos(1), Duration.ofNanos(1)));
    assertThat(spent.networkTimeoutMillis())
        .isEqualTo(PreCommitDeadline.MIN_NETWORK_TIMEOUT_MILLIS);
  }

  /** 네트워크 상한은 연결에 그대로 걸리고, 드라이버가 거부해도(예외) 좁히기 경로를 깨지 않는다. */
  @Test
  void applyNetworkTimeout_setsMillis_andSwallowsDriverFailure() {
    AtomicInteger seen = new AtomicInteger(-1);
    Connection ok = connection((millis) -> seen.set(millis));
    PythonReadGrantSync.applyNetworkTimeout(ok, 4321);
    assertThat(seen.get()).isEqualTo(4321);

    Connection failing =
        connection(
            (millis) -> {
              throw new SQLException("not supported", "0A000");
            });
    assertThatCode(() -> PythonReadGrantSync.applyNetworkTimeout(failing, 1000))
        .doesNotThrowAnyException();
  }

  @FunctionalInterface
  private interface NetworkTimeoutSetter {
    void set(int millis) throws SQLException;
  }

  /** setNetworkTimeout 만 받는 테스트용 연결. */
  private static Connection connection(NetworkTimeoutSetter setter) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              if (method.getName().equals("setNetworkTimeout")) {
                setter.set((Integer) args[1]);
                return null;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }
}
