package com.smartfirehub.notification.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code pendingGauges} 축출(eviction) 검증 (P2-g Task 3).
 *
 * <p>Spring 컨텍스트 없이 {@link SimpleMeterRegistry} + Mockito 리포지토리 목으로 순수 단위 테스트로
 * 짠다 — {@code refreshPendingGauges()} 를 N 번 직접 호출해야 하는데 {@code @Scheduled} 를 기다릴
 * 이유가 없고, 패키지가 같아 package-private 메서드를 그대로 부를 수 있다.
 */
class NotificationMetricsEvictionTest {

  private static final long TENANT_ID = 501L;
  private static final int EVICTION_PASSES = 3;

  @Test
  void evictsGauge_afterConsecutiveMissingPasses() {
    NotificationOutboxRepository repo = mock(NotificationOutboxRepository.class);
    MeterRegistry registry = new SimpleMeterRegistry();
    NotificationMetrics metrics = new NotificationMetrics(registry, repo, true, EVICTION_PASSES);

    // 1패스: 테넌트가 목록에 있다 — 게이지가 등록된다.
    when(repo.tenantIdsWithStatus("PENDING"))
        .thenReturn(List.of(TENANT_ID), List.of(), List.of(), List.of());
    when(repo.countPendingByChannel(TENANT_ID)).thenReturn(Map.of(ChannelType.CHAT, 3L));

    metrics.refreshPendingGauges();
    assertThat(gaugeValue(registry)).as("사전 조건: 게이지가 등록돼 있어야 한다").isEqualTo(3.0);

    // 2, 3패스: 테넌트가 목록에서 사라졌다(드레인) — 유예가 있어 임계 미만이면 남아 있어야 한다.
    metrics.refreshPendingGauges(); // 미출현 1회
    metrics.refreshPendingGauges(); // 미출현 2회
    assertThat(gaugeValue(registry))
        .as("축출 임계(%d패스) 미만이면 게이지가 아직 남아 있어야 한다", EVICTION_PASSES)
        .isNotNull();

    // 4패스: 미출현 3회 = 임계 도달 — 축출된다.
    metrics.refreshPendingGauges();

    assertThat(gaugeValue(registry)).as("축출 후 registry 에서 게이지를 찾을 수 없어야 한다").isNull();
    assertThat(pendingGaugesOf(metrics))
        .as("축출 후 pendingGauges 맵에도 남아 있으면 안 된다(맵만 지우고 registry 에 남는 누수 방지)")
        .doesNotContainKey(TENANT_ID + "|" + ChannelType.CHAT.name());
  }

  @Test
  void doesNotEvict_beforeThreshold() {
    NotificationOutboxRepository repo = mock(NotificationOutboxRepository.class);
    MeterRegistry registry = new SimpleMeterRegistry();
    NotificationMetrics metrics = new NotificationMetrics(registry, repo, true, EVICTION_PASSES);

    when(repo.tenantIdsWithStatus("PENDING")).thenReturn(List.of(TENANT_ID), List.of());
    when(repo.countPendingByChannel(TENANT_ID)).thenReturn(Map.of(ChannelType.CHAT, 1L));

    metrics.refreshPendingGauges(); // 등록
    metrics.refreshPendingGauges(); // 미출현 1회 (임계 3에 못 미침)

    assertThat(gaugeValue(registry)).as("유예 기간 안이므로 아직 살아 있어야 한다").isNotNull();
    assertThat(pendingGaugesOf(metrics)).containsKey(TENANT_ID + "|" + ChannelType.CHAT.name());
  }

  /**
   * carry-forward 와 축출의 상호작용을 고정하는 핵심 테스트.
   *
   * <p>조회에 <b>실패한</b> 테넌트는 목록에 계속 있지만({@code tenantIdsWithStatus} 는 성공) 카운트
   * 조회 자체({@code countPendingByChannel})가 매번 예외를 던진다. 이 경우 현재 값을 이월하는 것이지
   * "이번 패스에 없음"이 아니다 — 실패를 미출현으로 취급하면 DB 가 흔들리는 동안 적체 게이지가
   * 축출되어, carry-forward 가 막으려던 거짓 음성(적체가 0으로 보임)을 축출이 뒷문으로 되살린다.
   */
  @Test
  void failedTenantQuery_isNotEvictionCandidate() {
    NotificationOutboxRepository repo = mock(NotificationOutboxRepository.class);
    MeterRegistry registry = new SimpleMeterRegistry();
    NotificationMetrics metrics = new NotificationMetrics(registry, repo, true, EVICTION_PASSES);

    // 목록에는 매 패스 계속 등장한다 — 실제로 PENDING 행이 있다.
    when(repo.tenantIdsWithStatus("PENDING")).thenReturn(List.of(TENANT_ID));
    // 첫 조회만 성공(5), 이후로는 계속 실패.
    when(repo.countPendingByChannel(TENANT_ID))
        .thenReturn(Map.of(ChannelType.CHAT, 5L))
        .thenThrow(new RuntimeException("DB 커넥션 타임아웃 (테스트 주입)"));

    metrics.refreshPendingGauges(); // 성공 패스 — 값 5 확정
    assertThat(gaugeValue(registry)).isEqualTo(5.0);

    // 임계보다 많은 횟수를 실패시켜도 축출되면 안 된다.
    for (int i = 0; i < EVICTION_PASSES + 2; i++) {
      metrics.refreshPendingGauges();
    }

    assertThat(gaugeValue(registry))
        .as("조회 실패가 반복돼도 축출되면 안 되고, 직전 값(5)을 계속 이월해야 한다")
        .isEqualTo(5.0);
    assertThat(pendingGaugesOf(metrics)).containsKey(TENANT_ID + "|" + ChannelType.CHAT.name());
  }

  private Double gaugeValue(MeterRegistry registry) {
    Gauge gauge =
        registry
            .find("notification_outbox_pending_count")
            .tag("tenant", Long.toString(TENANT_ID))
            .tag("channel", ChannelType.CHAT.name())
            .gauge();
    return gauge == null ? null : gauge.value();
  }

  @SuppressWarnings("unchecked")
  private Map<String, AtomicLong> pendingGaugesOf(NotificationMetrics metrics) {
    return (Map<String, AtomicLong>) ReflectionTestUtils.getField(metrics, "pendingGauges");
  }
}
