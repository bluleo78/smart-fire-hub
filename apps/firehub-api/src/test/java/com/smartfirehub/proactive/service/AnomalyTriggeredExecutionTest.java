package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.service.SseEmitterRegistry;
import com.smartfirehub.proactive.dto.AnomalyEvent;
import com.smartfirehub.proactive.repository.AnomalyEventRepository;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.proactive.repository.ReportTemplateRepository;
import com.smartfirehub.tenant.repository.TenantRepository;
import com.smartfirehub.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

/**
 * WD-171: 이상 탐지가 작업 실행을 일으킬 때 감지된 이상 이벤트를 실행기에 함께 넘긴다.
 *
 * <p>수정 전에는 이상 없이 {@code asyncRunner.executeJob(jobId, userId)} 로만 불러 이상 내용(지표·현재값·편차)이 리포트 컨텍스트에
 * 실리지 않았다 — 이상 탐지 리포트가 평소 예약 리포트와 구분되지 않았다.
 */
class AnomalyTriggeredExecutionTest {

  /** 이상 탐지 처리가 읽는 실행 테넌트(WD-174 워크스페이스 정지 확인). 운영에서는 지표 폴러가 테넌트 컨텍스트를 세운다. */
  private static final long TENANT_ID = 1L;

  @BeforeEach
  void setTenant() {
    TenantContext.set(TENANT_ID);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  /** ACTIVE 워크스페이스 — 기존 테스트의 전제. */
  private static TenantRepository activeTenant() {
    TenantRepository repo = mock(TenantRepository.class);
    when(repo.isActive(TENANT_ID)).thenReturn(true);
    return repo;
  }

  /**
   * WD-174: 정지된 워크스페이스의 이상 탐지는 이벤트 저장·SSE 알림·실행을 모두 하지 않는다. 주인 자격(WD-166)보다 먼저 보므로 자격 판정도 부르지 않는다 —
   * 정지 워크스페이스 실행이 「작업 주인이 정지됐거나…」 사유로 남을 여지가 없다.
   */
  @Test
  void onAnomalyDetected_suspendedTenant_noSaveNoNotificationNoRun() {
    ProactiveJobAsyncRunner runner = mock(ProactiveJobAsyncRunner.class);
    SseEmitterRegistry registry = mock(SseEmitterRegistry.class);
    AnomalyEventRepository anomalyRepo = mock(AnomalyEventRepository.class);
    ProactiveOwnerEligibility eligibility = mock(ProactiveOwnerEligibility.class);
    TenantRepository suspended = mock(TenantRepository.class);
    when(suspended.isActive(TENANT_ID)).thenReturn(false);
    ProactiveJobService service =
        new ProactiveJobService(
            mock(ProactiveJobRepository.class),
            mock(ProactiveJobExecutionRepository.class),
            mock(ProactiveJobSchedulerService.class),
            mock(UserRepository.class),
            anomalyRepo,
            registry,
            runner,
            mock(MetricSqlAccessChecker.class),
            mock(ReportTemplateRepository.class),
            eligibility,
            suspended,
            null,
            null,
            null);
    AnomalyEvent event =
        new AnomalyEvent(
            7L, 3L, "ds_total", "데이터셋수", 9.0, 8.08, 0.28, 3.3, "medium", List.of(8.0, 8.0));

    service.onAnomalyDetected(event);

    verify(anomalyRepo, never()).save(any());
    verify(registry, never()).broadcast(anyLong(), any());
    verify(runner, never()).executeJob(anyLong(), anyLong(), any());
    verifyNoInteractions(eligibility);
  }

  @Test
  void onAnomalyDetected_passesTheAnomalyEventToTheRunner() {
    ProactiveJobAsyncRunner runner = mock(ProactiveJobAsyncRunner.class);
    ProactiveJobService service =
        new ProactiveJobService(
            mock(ProactiveJobRepository.class),
            mock(ProactiveJobExecutionRepository.class),
            mock(ProactiveJobSchedulerService.class),
            mock(UserRepository.class),
            mock(AnomalyEventRepository.class),
            mock(SseEmitterRegistry.class),
            runner,
            mock(MetricSqlAccessChecker.class),
            mock(ReportTemplateRepository.class),
            mock(ProactiveOwnerEligibility.class),
            activeTenant(),
            null,
            null,
            null);
    AnomalyEvent event =
        new AnomalyEvent(
            7L, 3L, "ds_total", "데이터셋수", 9.0, 8.08, 0.28, 3.3, "medium", List.of(8.0, 8.0));

    service.onAnomalyDetected(event);

    // 이상 이벤트와 함께 정확히 한 번 제출한다 — 이벤트 없이(null) 제출되면 리포트가 이상 내용을 모른다
    verify(runner, times(1)).executeJob(anyLong(), anyLong(), any());
    verify(runner).executeJob(7L, 3L, event);
  }

  /** 자격을 잃은 주인(정지·제거·권한 회수)에게는 이상 탐지 SSE 알림을 보내지 않는다 — 실행은 실행기가 사유를 남기며 건너뛴다(WD-166). */
  @Test
  void onAnomalyDetected_ineligibleOwner_noNotificationButRunnerRecordsSkip() {
    ProactiveJobAsyncRunner runner = mock(ProactiveJobAsyncRunner.class);
    SseEmitterRegistry registry = mock(SseEmitterRegistry.class);
    ProactiveOwnerEligibility eligibility = mock(ProactiveOwnerEligibility.class);
    when(eligibility.findIneligibilityReason(3L))
        .thenReturn(Optional.of(ProactiveOwnerEligibility.REASON_OWNER_INACTIVE));
    ProactiveJobService service =
        new ProactiveJobService(
            mock(ProactiveJobRepository.class),
            mock(ProactiveJobExecutionRepository.class),
            mock(ProactiveJobSchedulerService.class),
            mock(UserRepository.class),
            mock(AnomalyEventRepository.class),
            registry,
            runner,
            mock(MetricSqlAccessChecker.class),
            mock(ReportTemplateRepository.class),
            eligibility,
            activeTenant(),
            null,
            null,
            null);
    AnomalyEvent event =
        new AnomalyEvent(
            7L, 3L, "ds_total", "데이터셋수", 9.0, 8.08, 0.28, 3.3, "medium", List.of(8.0, 8.0));

    service.onAnomalyDetected(event);

    verify(registry, never()).broadcast(anyLong(), any());
    // 실행기는 부른다 — 실행기가 주인 자격을 다시 보고 FAILED 사유를 이력에 남긴다
    verify(runner).executeJob(7L, 3L, event);
  }

  /**
   * WD-778: 큐가 가득 차 비동기 제출이 거절되면(TaskRejectedException) 잡은 실행 슬롯을 놓는다 — 수정 전에는 로그만 남기고 슬롯을 쥔 채라 그
   * 작업은 재시작 전까지 지금 실행·예약·이상 감지 모두 「이미 실행 중」으로 막혔다. 제출이 실패했으므로 쿨다운도 남기지 않는다 — 다음 이상은 곧바로 다시 시도한다.
   */
  @Test
  void onAnomalyDetected_submissionRejected_releasesSlotAndSkipsCooldown() {
    ProactiveJobAsyncRunner runner = mock(ProactiveJobAsyncRunner.class);
    ProactiveJobService service =
        new ProactiveJobService(
            mock(ProactiveJobRepository.class),
            mock(ProactiveJobExecutionRepository.class),
            mock(ProactiveJobSchedulerService.class),
            mock(UserRepository.class),
            mock(AnomalyEventRepository.class),
            mock(SseEmitterRegistry.class),
            runner,
            mock(MetricSqlAccessChecker.class),
            mock(ReportTemplateRepository.class),
            mock(ProactiveOwnerEligibility.class),
            activeTenant(),
            null,
            null,
            null);
    AnomalyEvent event =
        new AnomalyEvent(
            7L, 3L, "ds_total", "데이터셋수", 9.0, 8.08, 0.28, 3.3, "medium", List.of(8.0, 8.0));
    doThrow(new TaskRejectedException("queue full")).when(runner).executeJob(7L, 3L, event);

    // 리스너는 예외를 올리지 않는다(이벤트 발행자에게 전파하지 않는다)
    assertThatCode(() -> service.onAnomalyDetected(event)).doesNotThrowAnyException();

    // 슬롯이 풀렸다 — 다시 잡을 수 있다
    assertThatCode(() -> service.tryAcquireRunSlot(7L)).doesNotThrowAnyException();
    service.releaseRunSlot(7L);

    // 쿨다운이 남지 않았다 — 다음 이상에서 곧바로 다시 제출한다
    service.onAnomalyDetected(event);
    verify(runner, times(2)).executeJob(7L, 3L, event);
  }
}
