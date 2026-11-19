package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.notification.service.SseEmitterRegistry;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.exception.ProactiveJobAlreadyRunningException;
import com.smartfirehub.proactive.repository.AnomalyEventRepository;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.proactive.repository.ReportTemplateRepository;
import com.smartfirehub.tenant.repository.TenantRepository;
import com.smartfirehub.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

/**
 * WD-778: 스마트 작업 실행 슬롯(작업마다 하나)의 획득·해제가 세 경로(지금 실행·예약 실행·이상 감지)에서 짝이 맞는지 본다 — 왜 한 메서드인지는 {@link
 * ProactiveJobService#startRun} 참고.
 *
 * <p>Spring 컨텍스트 없이 서비스·스케줄러를 직접 만들고 러너는 목으로 둔다 — 제출만 보고 실제 실행은 하지 않으므로, 슬롯은 러너의 {@code finally} 가
 * 아니라 이 시험이 놓기 전까지 잡힌 채로 남는다(= 「실행 중」).
 */
class ProactiveRunSlotTest {

  private static final long TENANT_ID = 1L;
  private static final long JOB_ID = 7L;
  private static final long OWNER_ID = 3L;

  /** {@link #fireAtNineKst()} 회차 다음의 「다음 실행」 — 다음 날 같은 시각(UTC). */
  private static final LocalDateTime NEXT_SLOT = LocalDateTime.of(2026, 11, 17, 0, 0);

  private ProactiveJobAsyncRunner runner;
  private ProactiveJobRepository jobRepository;
  private ProactiveJobService service;
  private ProactiveJobSchedulerService scheduler;

  @BeforeEach
  void setUp() {
    TenantContext.set(TENANT_ID);
    runner = mock(ProactiveJobAsyncRunner.class);
    jobRepository = mock(ProactiveJobRepository.class);
    TenantRepository tenantRepository = mock(TenantRepository.class);
    when(tenantRepository.isActive(TENANT_ID)).thenReturn(true);
    when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job()));
    service =
        new ProactiveJobService(
            jobRepository,
            mock(ProactiveJobExecutionRepository.class),
            mock(ProactiveJobSchedulerService.class),
            mock(UserRepository.class),
            mock(AnomalyEventRepository.class),
            mock(SseEmitterRegistry.class),
            runner,
            mock(MetricSqlAccessChecker.class),
            mock(ReportTemplateRepository.class),
            mock(ProactiveOwnerEligibility.class),
            tenantRepository,
            null,
            null,
            null,
            null);
    scheduler =
        new ProactiveJobSchedulerService(
            jobRepository, mock(TenantScopedRunner.class), service, tenantRepository);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  /** 예약 실행이 읽는 작업 행 — 주인만 의미가 있다. */
  private static ProactiveJobResponse job() {
    return job(null);
  }

  /** 「다음 실행」 값이 있는(스케줄러에 등록된) 활성 작업 행 — 매일 09:00 Asia/Seoul(= 00:00 UTC). */
  private static ProactiveJobResponse job(LocalDateTime nextExecuteAt) {
    return job(true, nextExecuteAt);
  }

  /** 활성 여부까지 정하는 작업 행 — 비활성·스케줄 해제 상태를 흉내 낼 때 쓴다. */
  private static ProactiveJobResponse job(boolean enabled, LocalDateTime nextExecuteAt) {
    return new ProactiveJobResponse(
        JOB_ID,
        OWNER_ID,
        null,
        null,
        "작업",
        "프롬프트",
        "0 0 9 * * *",
        "Asia/Seoul",
        enabled,
        null,
        Map.of(),
        null,
        nextExecuteAt,
        LocalDateTime.now(),
        LocalDateTime.now(),
        null);
  }

  /** 슬롯이 지금 잡혀 있는가 — 잡혀 있으면 획득이 409 예외로 끝난다. 확인용으로 잡은 슬롯은 곧바로 되돌린다. */
  private boolean slotHeld() {
    try {
      service.tryAcquireRunSlot(JOB_ID);
    } catch (ProactiveJobAlreadyRunningException e) {
      return true;
    }
    service.releaseRunSlot(JOB_ID);
    return false;
  }

  @Test
  void startRun_은_슬롯을_잡고_러너에_제출한다() {
    service.startRun(JOB_ID, OWNER_ID, null);

    verify(runner).executeJob(JOB_ID, OWNER_ID, null);
    // 러너가 끝나기 전(목이라 끝나지 않는다)이므로 슬롯은 잡힌 채다
    assertThat(slotHeld()).isTrue();
  }

  @Test
  void startRun_은_이미_돌고_있으면_409_예외로_끝나고_제출하지_않는다() {
    service.tryAcquireRunSlot(JOB_ID);

    assertThatThrownBy(() -> service.startRun(JOB_ID, OWNER_ID, null))
        .isInstanceOf(ProactiveJobAlreadyRunningException.class);
    verify(runner, never()).executeJob(anyLong(), anyLong(), any());
  }

  /** 큐가 가득 차 제출이 거절되면 슬롯을 놓고 예외를 그대로 올린다 — 놓지 않으면 재시작 전까지 「이미 실행 중」으로 막힌다. */
  @Test
  void startRun_제출이_거절되면_슬롯을_놓고_예외를_올린다() {
    doThrow(new TaskRejectedException("queue full"))
        .when(runner)
        .executeJob(JOB_ID, OWNER_ID, null);

    assertThatThrownBy(() -> service.startRun(JOB_ID, OWNER_ID, null))
        .isInstanceOf(TaskRejectedException.class);
    assertThat(slotHeld()).isFalse();
  }

  @Test
  void 예약_실행도_슬롯을_잡는다() {
    scheduler.runScheduledJob(JOB_ID);

    verify(runner).executeJob(JOB_ID, OWNER_ID, null);
    assertThat(slotHeld()).isTrue();
  }

  /**
   * 이미 돌고 있으면(수동 실행 중) 그 회차는 건너뛴다 — 제출하지 않고 예외도 올리지 않는다. 제출하지 않으므로 러너의 {@code finally} 가 수동 실행의 슬롯을
   * 풀 일도 없다: 수동 실행이 끝나기 전까지 슬롯은 잡힌 채다.
   */
  @Test
  void 예약_실행은_이미_돌고_있으면_건너뛰고_남의_슬롯을_풀지_않는다() {
    service.startRun(JOB_ID, OWNER_ID, null); // 수동 실행이 돌고 있다

    assertThatCode(() -> scheduler.runScheduledJob(JOB_ID)).doesNotThrowAnyException();

    // 러너 제출은 수동 실행의 한 번뿐이다
    verify(runner).executeJob(JOB_ID, OWNER_ID, null);
    assertThat(slotHeld()).isTrue();
  }

  /** 09:00 KST(00:00 UTC) 회차가 막 발화한 순간으로 스케줄러 시계를 고정한다 — 저장된 「다음 실행」은 그 회차라 이제 지나간 시각이다. */
  private void fireAtNineKst() {
    when(jobRepository.findById(JOB_ID))
        .thenReturn(Optional.of(job(LocalDateTime.of(2026, 11, 16, 0, 0))));
    scheduler.setClockForTest(Clock.fixed(Instant.parse("2026-11-16T00:00:01Z"), ZoneOffset.UTC));
  }

  /**
   * WD-778: 건너뛴 회차도 「다음 실행」을 앞으로 옮긴다 — 건너뜀은 러너를 거치지 않아 실행 뒤 기록이 돌지 않으므로, 옮기지 않으면 화면의 「다음 실행」이 방금
   * 지나간 시각에 머문다. 「마지막 실행」은 실행이 아니라서 건드리지 않는다.
   */
  @Test
  void 예약_실행을_건너뛰어도_다음_실행_시각을_앞으로_옮긴다() {
    fireAtNineKst();
    service.startRun(JOB_ID, OWNER_ID, null); // 수동 실행이 돌고 있다

    scheduler.runScheduledJob(JOB_ID);

    verify(jobRepository).updateNextExecuteAt(JOB_ID, NEXT_SLOT);
    verify(jobRepository, never()).updateLastExecuted(anyLong(), any(), any());
    // 건너뛴 회차는 제출하지 않았다 — 제출은 수동 실행의 한 번뿐이다
    verify(runner).executeJob(JOB_ID, OWNER_ID, null);
  }

  /** 정상 제출된 회차도 같은 값으로 옮긴다 — 실행이 끝날 때 러너의 실행 뒤 기록이 같은 다음 회차를 다시 쓸 뿐이다. */
  @Test
  void 예약_실행이_제출되면_다음_실행_시각을_다음_회차로_옮긴다() {
    fireAtNineKst();

    scheduler.runScheduledJob(JOB_ID);

    verify(runner).executeJob(JOB_ID, OWNER_ID, null);
    verify(jobRepository).updateNextExecuteAt(JOB_ID, NEXT_SLOT);
  }

  /** 제출이 거절돼도(큐 가득) 「다음 실행」은 옮긴다 — 이번 회차는 소진됐고 다음 주기에 다시 시도한다. */
  @Test
  void 예약_실행_제출이_거절돼도_다음_실행_시각을_옮긴다() {
    fireAtNineKst();
    doThrow(new TaskRejectedException("queue full"))
        .when(runner)
        .executeJob(JOB_ID, OWNER_ID, null);

    assertThatCode(() -> scheduler.runScheduledJob(JOB_ID)).doesNotThrowAnyException();
    verify(jobRepository).updateNextExecuteAt(JOB_ID, NEXT_SLOT);
  }

  /**
   * 발화 직후 읽은 행이 이미 비활성·스케줄 해제(「다음 실행」 비움) 상태면 「다음 실행」을 써넣지 않는다 — 러너의 실행 뒤 기록과 같은 가드. 비활성 잡에 오지 않을
   * 다음 실행 시각이 다시 생기지 않게 한다.
   */
  @Test
  void 예약_실행은_비활성_작업에_다음_실행_시각을_쓰지_않는다() {
    when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job(false, null)));
    scheduler.setClockForTest(Clock.fixed(Instant.parse("2026-11-16T00:00:01Z"), ZoneOffset.UTC));

    scheduler.runScheduledJob(JOB_ID);

    verify(jobRepository, never()).updateNextExecuteAt(anyLong(), any());
  }

  @Test
  void 예약_실행_제출이_거절되면_슬롯을_놓는다() {
    doThrow(new TaskRejectedException("queue full"))
        .when(runner)
        .executeJob(JOB_ID, OWNER_ID, null);

    assertThatCode(() -> scheduler.runScheduledJob(JOB_ID)).doesNotThrowAnyException();
    assertThat(slotHeld()).isFalse();
  }
}
