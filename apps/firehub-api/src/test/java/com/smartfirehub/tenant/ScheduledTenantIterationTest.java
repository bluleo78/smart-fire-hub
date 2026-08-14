package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantScopedRunner;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 1차 밴드 테이블(dataset 등)을 읽는 @Scheduled 경로가 테넌트 순회 없이 남아 있지 않은지 고정한다.
 *
 * <p>스케줄러는 원 HTTP 요청이 없어 TaskDecorator 로 해결되지 않는다. 순회를 빼먹으면 RLS 하에서
 * 조용히 무동작이 되고, 다음 주기에도 계속 무동작이라 증상이 "기능이 그냥 안 돈다"로 나타난다.
 *
 * <p>본문 검사는 리플렉션으로 불가능하므로 "TenantScopedRunner 를 협력자로 갖는가"를 대리 지표로
 * 쓴다. 약한 단언이지만, 이 배선이 통째로 사라지는 회귀는 확실히 잡는다.
 */
class ScheduledTenantIterationTest {

  @Test
  void triggerEventServiceIteratesTenantsForDatasetChangePolling() {
    assertUsesRunner(com.smartfirehub.pipeline.service.TriggerEventService.class);
    assertScheduled(
        com.smartfirehub.pipeline.service.TriggerEventService.class, "pollDatasetChanges");
  }

  @Test
  void metricPollerServiceIteratesTenants() {
    assertUsesRunner(com.smartfirehub.proactive.service.MetricPollerService.class);
    // @Scheduled 는 poll() 에 있고 본문은 pollMetrics() 로 분리돼 있다.
    assertScheduled(com.smartfirehub.proactive.service.MetricPollerService.class, "poll");
  }

  private void assertUsesRunner(Class<?> type) {
    boolean hasRunnerField =
        Arrays.stream(type.getDeclaredFields())
            .anyMatch(f -> f.getType().equals(TenantScopedRunner.class));
    assertThat(hasRunnerField)
        .as("%s 는 TenantScopedRunner 를 주입받아 ACTIVE 테넌트를 순회해야 한다", type.getSimpleName())
        .isTrue();
  }

  private void assertScheduled(Class<?> type, String methodName) {
    Method target =
        Arrays.stream(type.getDeclaredMethods())
            .filter(m -> m.getName().equals(methodName))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        type.getSimpleName() + "." + methodName + " 를 찾을 수 없다"));
    assertThat(target.isAnnotationPresent(Scheduled.class))
        .as("%s.%s 는 @Scheduled 여야 한다", type.getSimpleName(), methodName)
        .isTrue();
  }
}
