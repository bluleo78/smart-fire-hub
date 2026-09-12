package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link TriggerSchedulerService#registerSchedule} 의 nextFireTime write 경로를 순수 단위
 * 테스트로 검증한다(#676).
 *
 * <p>이 write 경로는 {@code REQUIRES_NEW} 트랜잭션(TriggerRepository.mergeNextFireTime)으로
 * 구현돼 있어, 클래스 레벨 {@code @Transactional}(롤백 전용) 통합 테스트 안에서는 검증할 수
 * 없다 — REQUIRES_NEW 가 여는 새 물리 트랜잭션은 아직 커밋되지 않은(=통합 테스트 트랜잭션 안에만
 * 있는) 행을 볼 수 없다. 그래서 Spring 컨텍스트·DB 없이 Mockito로 상호작용만 검증한다.
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
   * registerSchedule()이 trigger_state.nextFireTime을 실제로 기록하는지 검증한다. 이 write 경로가
   * 없었던 것이 #676의 근본 원인이었다 — "다음 실행" 표시와 missed-fire 감지가 전제하는 값 자체가
   * 존재하지 않았다.
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

  /** cron 표현식이 잘못되면 nextFireTime write 없이 등록이 실패해야 한다(기존 실패 경로 불변 확인). */
  @Test
  void registerSchedule_invalidCron_doesNotWriteNextFireTime() {
    Map<String, Object> config = Map.of("cron", "invalid cron expr", "timezone", "UTC");

    schedulerService.registerSchedule(101L, config);

    org.mockito.Mockito.verifyNoInteractions(triggerRepository);
  }

  /**
   * 발화 러너블 안에서 nextFireTime을 재계산해 갱신하는지는 실제 크론 발화를 기다리지 않고도
   * 코드 경로로 보장된다 — registerSchedule 자체가 등록 시점에 한 번은 반드시 쓰는 것을 위
   * 테스트가 확인하며, 발화 후 재계산 로직은 동일한 {@code writeNextFireTime} 헬퍼를 재사용하므로
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
