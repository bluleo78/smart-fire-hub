package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link TriggerSchedulerService#registerSchedule} 의 nextFireTime write 경로를 순수 단위 테스트로 검증한다(#676).
 *
 * <p>이 write 경로는 {@code REQUIRES_NEW} 트랜잭션(TriggerRepository.mergeNextFireTime)으로 구현돼 있어, 클래스 레벨
 * {@code @Transactional}(롤백 전용) 통합 테스트 안에서는 검증할 수 없다 — REQUIRES_NEW 가 여는 새 물리 트랜잭션은 아직 커밋되지 않은(=통합
 * 테스트 트랜잭션 안에만 있는) 행을 볼 수 없다. 그래서 Spring 컨텍스트·DB 없이 Mockito로 상호작용만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class TriggerSchedulerServiceUnitTest {

  @Mock private TriggerRepository triggerRepository;
  @Mock private TriggerEventRepository triggerEventRepository;
  @Mock private TriggerService triggerService;
  @Mock private TenantScopedRunner tenantScopedRunner;

  private TriggerSchedulerService schedulerService;

  @BeforeEach
  void setUp() {
    schedulerService =
        new TriggerSchedulerService(
            triggerRepository, triggerEventRepository, triggerService, tenantScopedRunner);
    // registerSchedule() 은 TenantContext.require() 를 호출하므로 요청 스코프를 흉내 낸다.
    TenantContext.set(1L);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  /**
   * registerSchedule()이 trigger_state.nextFireTime을 실제로 기록하는지 검증한다. 이 write 경로가 없었던 것이 #676의 근본
   * 원인이었다 — "다음 실행" 표시와 missed-fire 감지가 전제하는 값 자체가 존재하지 않았다.
   */
  @Test
  void registerSchedule_writesNextFireTimeAsUtcInstantString() {
    Map<String, Object> config = Map.of("cron", "0 0 9 * * *", "timezone", "UTC");

    schedulerService.registerSchedule(100L, config);

    ArgumentCaptor<String> nextFireTimeCaptor = ArgumentCaptor.forClass(String.class);
    verify(triggerRepository).mergeNextFireTime(eq(100L), nextFireTimeCaptor.capture());

    // UTC ISO-8601(Instant.toString()) 형식이어야 detectMissedFire/프론트엔드가 일관되게 해석한다
    // (오프셋 없는 문자열=UTC 라는 프론트엔드 날짜 계약, #160).
    Instant nextFireInstant = Instant.parse(nextFireTimeCaptor.getValue());
    assertThat(nextFireInstant).isAfter(Instant.now());

    schedulerService.unregisterSchedule(100L);
  }

  /**
   * cron 표현식이 잘못되면 nextFireTime write 없이 등록이 실패해야 한다. 예전에는 로그만 남기고 조용히 돌아와 호출자가 성공으로 오인했다 — 이제는 예외로
   * 알린다(WD-107).
   */
  @Test
  void registerSchedule_invalidCron_throwsAndDoesNotWriteNextFireTime() {
    Map<String, Object> config = Map.of("cron", "invalid cron expr", "timezone", "UTC");

    assertThatThrownBy(() -> schedulerService.registerSchedule(101L, config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("스케줄을 등록할 수 없습니다");

    org.mockito.Mockito.verifyNoInteractions(triggerRepository);
  }

  /**
   * 잘못된 cron 으로 재등록을 시도해도 이미 돌고 있던 스케줄은 취소되지 않고 그대로 남아야 한다(WD-107). 예전에는 compute() 안에서 기존 스케줄을 먼저
   * cancel 한 뒤 생성에 실패해 null 을 돌려줘, 옛 스케줄까지 사라졌다.
   */
  @Test
  void registerSchedule_invalidCron_keepsExistingSchedule() {
    schedulerService.registerSchedule(103L, Map.of("cron", "0 0 9 * * *", "timezone", "UTC"));
    Future<?> before = scheduledTasks().get(103L);
    assertThat(before).isNotNull();

    assertThatThrownBy(
            () ->
                schedulerService.registerSchedule(
                    103L, Map.of("cron", "99 * * * *", "timezone", "UTC")))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(scheduledTasks().get(103L)).isSameAs(before);
    assertThat(before.isCancelled()).isFalse();
    // 실패한 시도는 nextFireTime 을 덮어쓰지 않는다 — 첫 등록의 기록 1회뿐이어야 한다.
    verify(triggerRepository, times(1)).mergeNextFireTime(eq(103L), anyString());

    schedulerService.unregisterSchedule(103L);
  }

  /** 잘못된 시간대도 같은 방식으로 실패하고 기존 스케줄을 남겨야 한다. */
  @Test
  void registerSchedule_invalidTimezone_keepsExistingSchedule() {
    schedulerService.registerSchedule(104L, Map.of("cron", "0 0 9 * * *", "timezone", "UTC"));
    Future<?> before = scheduledTasks().get(104L);

    assertThatThrownBy(
            () ->
                schedulerService.registerSchedule(
                    104L, Map.of("cron", "0 0 10 * * *", "timezone", "Mars/Base")))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(scheduledTasks().get(104L)).isSameAs(before);
    assertThat(before.isCancelled()).isFalse();

    schedulerService.unregisterSchedule(104L);
  }

  /**
   * 5필드 cron 도 정규화해 등록해야 한다(WD-107). 이 결함으로 5필드가 이미 저장된 운영 행이 재기동({@code reloadAllSchedules})·토글 시
   * 다시 등록되게 하기 위함이다. 정상 재등록은 새 스케줄로 교체하고 옛 스케줄을 취소한다.
   */
  @Test
  void registerSchedule_fiveFieldCron_isNormalizedAndReplacesExisting() {
    schedulerService.registerSchedule(105L, Map.of("cron", "0 0 3 * * *", "timezone", "UTC"));
    Future<?> before = scheduledTasks().get(105L);

    schedulerService.registerSchedule(105L, Map.of("cron", "0 9 * * *", "timezone", "UTC"));

    Future<?> after = scheduledTasks().get(105L);
    assertThat(after).isNotNull().isNotSameAs(before);
    assertThat(after.isCancelled()).isFalse();
    assertThat(before.isCancelled()).isTrue();

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(triggerRepository, times(2)).mergeNextFireTime(eq(105L), captor.capture());
    Instant next = Instant.parse(captor.getAllValues().get(1));
    assertThat(next.atZone(ZoneOffset.UTC).getHour()).isEqualTo(9);
    assertThat(next.atZone(ZoneOffset.UTC).getMinute()).isZero();

    schedulerService.unregisterSchedule(105L);
  }

  /**
   * 문법은 맞지만 다음 발화가 없는 cron(2월 30일)이면 schedule() 이 null 을 돌려준다(WD-107 리뷰). 새 설정으로는 돌 스케줄이 없으므로 옛
   * 스케줄은 취소·제거되고, "다음 실행"에 옛 시각이 남지 않게 nextFireTime 을 비워야 한다.
   */
  @Test
  void registerSchedule_cronWithoutNextExecution_clearsScheduleAndNextFireTime() {
    schedulerService.registerSchedule(106L, Map.of("cron", "0 0 9 * * *", "timezone", "UTC"));
    Future<?> before = scheduledTasks().get(106L);

    schedulerService.registerSchedule(106L, Map.of("cron", "0 0 0 30 2 *", "timezone", "UTC"));

    assertThat(before.isCancelled()).isTrue();
    assertThat(scheduledTasks()).doesNotContainKey(106L);
    assertThat(schedulerService.isScheduled(106L)).isFalse();
    verify(triggerRepository).clearNextFireTime(106L);
    verify(triggerRepository, times(1)).mergeNextFireTime(eq(106L), anyString());
  }

  /**
   * 커밋 후 경로의 재등록은 실패해도 예외를 던지지 않는다(WD-107 리뷰) — DB 는 이미 커밋돼 되돌릴 수 없으므로, DB 의 새 설정과 어긋나는 옛 스케줄을 내리고
   * nextFireTime 을 비운 "스케줄 없음" 상태로 정리한다.
   */
  @Test
  void registerScheduleAfterCommit_failure_doesNotThrowAndLeavesUnscheduled() {
    schedulerService.registerSchedule(107L, Map.of("cron", "0 0 9 * * *", "timezone", "UTC"));
    Future<?> before = scheduledTasks().get(107L);

    schedulerService.registerScheduleAfterCommit(
        107L, Map.of("cron", "99 * * * *", "timezone", "UTC"));

    assertThat(before.isCancelled()).isTrue();
    assertThat(schedulerService.isScheduled(107L)).isFalse();
    verify(triggerRepository).clearNextFireTime(107L);
  }

  @SuppressWarnings("unchecked")
  private Map<Long, Future<?>> scheduledTasks() {
    return (Map<Long, Future<?>>) ReflectionTestUtils.getField(schedulerService, "scheduledTasks");
  }

  /**
   * 발화 러너블 안에서 nextFireTime을 재계산해 갱신하는지는 실제 크론 발화를 기다리지 않고도 코드 경로로 보장된다 — registerSchedule 자체가 등록
   * 시점에 한 번은 반드시 쓰는 것을 위 테스트가 확인하며, 발화 후 재계산 로직은 동일한 {@code writeNextFireTime} 헬퍼를 재사용하므로
   * (TriggerSchedulerService.java 참고) 별도 검증이 중복되지 않는다.
   */
  @Test
  void registerSchedule_missingTenantContext_throws() {
    TenantContext.clear();
    Map<String, Object> config = Map.of("cron", "0 0 9 * * *", "timezone", "UTC");

    org.junit.jupiter.api.Assertions.assertThrows(
        Exception.class, () -> schedulerService.registerSchedule(102L, config));

    org.mockito.Mockito.verifyNoInteractions(triggerRepository);
    TenantContext.set(1L); // tearDown()의 clear()가 안전하게 동작하도록 복구
  }
}
