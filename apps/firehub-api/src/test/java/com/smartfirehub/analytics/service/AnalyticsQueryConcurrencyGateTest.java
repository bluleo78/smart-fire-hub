package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.exception.CodedApiException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 분석 쿼리 동시 실행 게이트 단위 테스트(WD-553) — 사용자당·전체 상한, 전체 실패 시 사용자 허가 되돌림, 반납 후 사용자 맵 비움, 끔(0 이하).
 *
 * <p>스프링 없이 게이트만 직접 만든다 — 상한·카운터 규칙만 보면 되고, 실제 실행 경로 배선은 AnalyticsQueryConcurrencyIntegrationTest 가
 * 본다.
 */
class AnalyticsQueryConcurrencyGateTest {

  /** 거절이 429 + QUERY_CONCURRENCY_LIMIT 코드인지 확인한다. */
  private static void assertRejected(AnalyticsQueryConcurrencyGate gate, long userId) {
    assertThatThrownBy(() -> gate.acquire(userId))
        .isInstanceOf(CodedApiException.class)
        .satisfies(
            e -> {
              CodedApiException c = (CodedApiException) e;
              assertThat(c.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
              assertThat(c.code()).isEqualTo("QUERY_CONCURRENCY_LIMIT");
              assertThat(c.getMessage()).contains("다시 시도");
            });
  }

  @Test
  void perUserLimit_rejectsThirdQueryOfSameUser_butAllowsOtherUser() {
    AnalyticsQueryConcurrencyGate gate = new AnalyticsQueryConcurrencyGate(10, 2);
    AnalyticsQueryConcurrencyGate.Permit a = gate.acquire(1L);
    AnalyticsQueryConcurrencyGate.Permit b = gate.acquire(1L);

    assertRejected(gate, 1L);
    // 다른 사용자는 자기 몫이 남아 있다.
    AnalyticsQueryConcurrencyGate.Permit other = gate.acquire(2L);

    a.close();
    // 하나 반납하면 같은 사용자가 다시 얻는다.
    AnalyticsQueryConcurrencyGate.Permit again = gate.acquire(1L);
    b.close();
    again.close();
    other.close();
    assertThat(gate.activeTotal()).isZero();
  }

  @Test
  void totalLimit_rejectsAcrossUsers() {
    AnalyticsQueryConcurrencyGate gate = new AnalyticsQueryConcurrencyGate(2, 2);
    AnalyticsQueryConcurrencyGate.Permit a = gate.acquire(1L);
    AnalyticsQueryConcurrencyGate.Permit b = gate.acquire(2L);

    assertRejected(gate, 3L);

    a.close();
    gate.acquire(3L).close();
    b.close();
    assertThat(gate.activeTotal()).isZero();
  }

  @Test
  void totalFailure_rollsBackPerUserPermit_andClearsMapEntry() {
    AnalyticsQueryConcurrencyGate gate = new AnalyticsQueryConcurrencyGate(1, 2);
    AnalyticsQueryConcurrencyGate.Permit holder = gate.acquire(1L);

    // 사용자 2 는 사용자 몫은 얻지만 전체 몫에서 거절된다 — 사용자 몫이 남으면 안 된다.
    assertRejected(gate, 2L);
    assertThat(gate.activeFor(2L)).isZero();
    assertThat(gate.trackedUsers()).containsExactly(1L);

    holder.close();
    assertThat(gate.activeTotal()).isZero();
    assertThat(gate.trackedUsers()).isEmpty();
  }

  @Test
  void release_isIdempotent_andRemovesUserEntryAtZero() {
    AnalyticsQueryConcurrencyGate gate = new AnalyticsQueryConcurrencyGate(4, 2);
    AnalyticsQueryConcurrencyGate.Permit p = gate.acquire(7L);
    assertThat(gate.activeFor(7L)).isEqualTo(1);

    p.close();
    // 두 번 닫아도 카운터가 음수가 되거나 다른 허가를 빼앗지 않는다.
    p.close();
    assertThat(gate.activeFor(7L)).isZero();
    assertThat(gate.trackedUsers()).isEmpty();
    assertThat(gate.activeTotal()).isZero();

    // 전체 상한도 정확히 복구됐다 — 4개를 다시 얻을 수 있다(사용자 둘에 나눠).
    List<AnalyticsQueryConcurrencyGate.Permit> ps = new ArrayList<>();
    ps.add(gate.acquire(1L));
    ps.add(gate.acquire(1L));
    ps.add(gate.acquire(2L));
    ps.add(gate.acquire(2L));
    assertRejected(gate, 3L);
    ps.forEach(AnalyticsQueryConcurrencyGate.Permit::close);
    assertThat(gate.activeTotal()).isZero();
  }

  @Test
  void permit_isReleasedByTryWithResources_evenWhenBodyThrows() {
    AnalyticsQueryConcurrencyGate gate = new AnalyticsQueryConcurrencyGate(1, 1);
    assertThatThrownBy(
            () -> {
              try (AnalyticsQueryConcurrencyGate.Permit ignored = gate.acquire(1L)) {
                throw new IllegalStateException("실행 실패");
              }
            })
        .isInstanceOf(IllegalStateException.class);
    assertThat(gate.activeTotal()).isZero();
    gate.acquire(1L).close();
  }

  @Test
  void zeroOrNegative_disablesEachLimitIndependently() {
    // 둘 다 끔 — 몇 개든 통과.
    AnalyticsQueryConcurrencyGate off = new AnalyticsQueryConcurrencyGate(0, -1);
    List<AnalyticsQueryConcurrencyGate.Permit> ps = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      ps.add(off.acquire(1L));
    }
    ps.forEach(AnalyticsQueryConcurrencyGate.Permit::close);
    assertThat(off.trackedUsers()).isEmpty();

    // 전체만 끔 — 사용자당 상한은 그대로.
    AnalyticsQueryConcurrencyGate perUserOnly = new AnalyticsQueryConcurrencyGate(0, 1);
    AnalyticsQueryConcurrencyGate.Permit p = perUserOnly.acquire(1L);
    assertRejected(perUserOnly, 1L);
    perUserOnly.acquire(2L).close();
    p.close();

    // 사용자당만 끔 — 전체 상한은 그대로(같은 사용자라도 전체까지 허용).
    AnalyticsQueryConcurrencyGate totalOnly = new AnalyticsQueryConcurrencyGate(2, 0);
    AnalyticsQueryConcurrencyGate.Permit x = totalOnly.acquire(1L);
    AnalyticsQueryConcurrencyGate.Permit y = totalOnly.acquire(1L);
    assertRejected(totalOnly, 2L);
    x.close();
    y.close();
    assertThat(totalOnly.activeTotal()).isZero();
    assertThat(totalOnly.trackedUsers()).isEmpty();
  }
}
