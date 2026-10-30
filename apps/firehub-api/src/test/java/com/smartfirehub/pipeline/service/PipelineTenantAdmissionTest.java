package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

/**
 * {@link PipelineTenantAdmission} 단위 테스트(WD-439) — 테넌트별 상한, 먼저 온 순서, 자리 반환과 다음 제출, 취소한 대기자 빼기, 대기
 * 한도, 제출 실패 처리, 관문 끔(0).
 */
class PipelineTenantAdmissionTest {

  /** 손으로 움직이는 시계 — 대기 한도를 시간 흐름 없이 검증한다. */
  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-10-31T00:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final MutableClock clock = new MutableClock();
  private final PipelineTenantAdmission admission =
      new PipelineTenantAdmission(2, Duration.ofMinutes(30), clock);

  /** 제출된 실행 id 를 순서대로 모은다. */
  private final List<Long> dispatched = new ArrayList<>();

  private final List<Long> timedOut = new ArrayList<>();

  private boolean admit(long tenant, long execution) {
    return admission.admit(
        tenant, execution, () -> dispatched.add(execution), () -> timedOut.add(execution));
  }

  @Test
  void runsUpToLimitPerTenant_thenQueuesTheRest() {
    assertThat(admit(1, 101)).isTrue();
    assertThat(admit(1, 102)).isTrue();
    assertThat(admit(1, 103)).isFalse();

    assertThat(dispatched).containsExactly(101L, 102L);
    assertThat(admission.isWaiting(103)).isTrue();
  }

  /** 상한은 테넌트마다 따로다 — 한 테넌트가 꽉 차도 다른 테넌트는 곧바로 돈다. */
  @Test
  void limitIsPerTenant() {
    admit(1, 101);
    admit(1, 102);
    admit(1, 103);

    assertThat(admit(2, 201)).isTrue();
    assertThat(dispatched).containsExactly(101L, 102L, 201L);
  }

  /** 자리를 돌려주면 먼저 온 대기자부터 제출된다. */
  @Test
  void releaseDispatchesWaitersInArrivalOrder() {
    admit(1, 101);
    admit(1, 102);
    admit(1, 103);
    admit(1, 104);

    admission.release(101);
    assertThat(dispatched).containsExactly(101L, 102L, 103L);

    admission.release(102);
    assertThat(dispatched).containsExactly(101L, 102L, 103L, 104L);
    assertThat(admission.isWaiting(104)).isFalse();
  }

  /** 자리를 쥐지 않은 실행의 해제(대기자·관문 끈 때·두 번째 해제)는 아무것도 바꾸지 않는다. */
  @Test
  void releaseOfUnadmittedExecutionIsNoOp() {
    admit(1, 101);
    admit(1, 102);
    admit(1, 103);

    admission.release(103);
    admission.release(999);
    assertThat(dispatched).containsExactly(101L, 102L);

    admission.release(101);
    admission.release(101);
    assertThat(dispatched).containsExactly(101L, 102L, 103L);
    // 101 을 두 번 풀어도 자리가 하나만 돌아왔다 — 새 실행은 기다린다.
    assertThat(admit(1, 104)).isFalse();
  }

  /** 취소된 대기자는 빼면 자리가 나도 제출되지 않는다. */
  @Test
  void forgottenWaiterIsNotDispatched() {
    admit(1, 101);
    admit(1, 102);
    admit(1, 103);
    admit(1, 104);

    assertThat(admission.forget(103)).isTrue();
    assertThat(admission.forget(103)).isFalse();
    admission.release(101);

    assertThat(dispatched).containsExactly(101L, 102L, 104L);
  }

  /** 대기 한도를 넘긴 대기자만 빼고 실패 콜백을 부른다. */
  @Test
  void expireWaitingClosesOnlyWaitersPastTheLimit() {
    admit(1, 101);
    admit(1, 102);
    admit(1, 103);
    clock.advance(Duration.ofMinutes(20));
    admit(1, 104);
    clock.advance(Duration.ofMinutes(10));

    assertThat(admission.expireWaiting()).isEqualTo(1);
    assertThat(timedOut).containsExactly(103L);
    assertThat(admission.isWaiting(103)).isFalse();
    assertThat(admission.isWaiting(104)).isTrue();

    // 뺀 대기자는 자리가 나도 제출되지 않는다.
    admission.release(101);
    assertThat(dispatched).containsExactly(101L, 102L, 104L);
  }

  /** 곧바로 한 제출이 실패하면 자리를 돌려주고 예외를 호출자에게 그대로 던진다. */
  @Test
  void immediateDispatchFailure_returnsSlotAndRethrows() {
    assertThatThrownBy(
            () ->
                admission.admit(
                    1,
                    101,
                    () -> {
                      throw new TaskRejectedException("full");
                    },
                    () -> {}))
        .isInstanceOf(TaskRejectedException.class);

    assertThat(admit(1, 102)).isTrue();
    assertThat(admit(1, 103)).isTrue();
  }

  /** 대기열에서 한 제출이 거절되면 그 대기자를 맨 앞에 되돌리고 멈춘다 — 뒤 대기자를 줄줄이 실패시키지 않고, 주기 점검에서 다시 제출한다. */
  @Test
  void queuedDispatchRejection_keepsWaiterAtFrontAndRetriesLater() {
    admit(1, 101);
    admit(1, 102);
    java.util.concurrent.atomic.AtomicBoolean executorFull =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    admission.admit(
        1,
        103,
        () -> {
          if (executorFull.get()) {
            throw new TaskRejectedException("full");
          }
          dispatched.add(103L);
        },
        () -> timedOut.add(103L));
    admit(1, 104);

    admission.release(101);
    assertThat(dispatched).containsExactly(101L, 102L);
    assertThat(admission.isWaiting(103)).isTrue();
    assertThat(admission.isWaiting(104)).isTrue();

    executorFull.set(false);
    admission.expireWaiting();
    assertThat(dispatched).containsExactly(101L, 102L, 103L);
    assertThat(admission.isWaiting(104)).isTrue();
    assertThat(timedOut).isEmpty();
  }

  /** 기다리는 실행이 있으면 자리가 비어 보여도 새 실행은 뒤에 줄을 선다 — 먼저 온 대기자가 밀리지 않는다. */
  @Test
  void newExecutionQueuesBehindExistingWaiters() {
    admit(1, 101);
    admit(1, 102);
    java.util.concurrent.atomic.AtomicBoolean executorFull =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    admission.admit(
        1,
        103,
        () -> {
          if (executorFull.get()) {
            throw new TaskRejectedException("full");
          }
          dispatched.add(103L);
        },
        () -> {});
    // 103 의 제출이 거절돼 자리가 하나 비었지만 103 이 아직 기다린다.
    admission.release(101);

    assertThat(admit(1, 105)).isFalse();
    executorFull.set(false);
    admission.expireWaiting();
    assertThat(dispatched).containsExactly(101L, 102L, 103L);
    assertThat(admission.isWaiting(105)).isTrue();
  }

  /** 상한 0 은 관문을 끈다 — 늘 곧바로 제출한다(실행기를 켠 운영 기본값). */
  @Test
  void zeroLimitDisablesTheGate() {
    PipelineTenantAdmission off = new PipelineTenantAdmission(0, Duration.ofMinutes(30), clock);
    for (long id = 1; id <= 5; id++) {
      long execution = id;
      assertThat(off.admit(1, execution, () -> dispatched.add(execution), () -> {})).isTrue();
    }
    assertThat(dispatched).hasSize(5);
    assertThat(off.expireWaiting()).isZero();
  }

  /** 상한을 비우면 실행기를 끈 구성은 테넌트 연결 풀 크기, 켠 구성은 0(제한 없음)이다. */
  @Test
  void defaultLimitFollowsExecutorMode() {
    PipelineTenantAdmission local =
        new PipelineTenantAdmission(-1, 2, false, Duration.ofMinutes(30));
    PipelineTenantAdmission prod = new PipelineTenantAdmission(-1, 2, true, Duration.ofMinutes(30));
    List<Long> localRuns = new ArrayList<>();
    List<Long> prodRuns = new ArrayList<>();
    for (long id = 1; id <= 3; id++) {
      long execution = id;
      local.admit(1, execution, () -> localRuns.add(execution), () -> {});
      prod.admit(1, execution, () -> prodRuns.add(execution), () -> {});
    }
    assertThat(localRuns).containsExactly(1L, 2L);
    assertThat(prodRuns).containsExactly(1L, 2L, 3L);
  }
}
