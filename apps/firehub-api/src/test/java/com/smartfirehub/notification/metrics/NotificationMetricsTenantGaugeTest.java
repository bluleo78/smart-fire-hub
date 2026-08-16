package com.smartfirehub.notification.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.notification.repository.NotificationOutboxRepository.NotificationOutboxRow;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * PENDING 적체 게이지가 테넌트별로 갱신되는지 검증한다(P2-f Task 3).
 *
 * <p>이전 구현은 Micrometer 게이지 콜백 안에서 DB 를 조회했는데, 그 콜백은 컨텍스트가 없는 스크레이프
 * 스레드에서 실행돼 정책(V107) 이후 영원히 0 을 보고한다. 이 테스트는 <b>컨텍스트 없이</b> 갱신을
 * 부르고, 테넌트 태그가 붙은 게이지가 실제 적체 수를 보고하는지 본다.
 */
@TestPropertySource(
    // 5개 알림 통합 테스트가 완전히 동일한 프로퍼티 집합을 공유한다 — 스프링 컨텍스트 캐시
    // 키가 프로퍼티 배열이라, 한 글자만 달라도 컨텍스트가 하나 더 뜬다. 컨텍스트마다 스케줄러
    // 스레드가 따로 도는데 그중 일부는 @MockitoBean 을 건드려, 컨텍스트 수가 늘면 무관한 테스트의
    // 스터빙과 경합해 전체 스위트에서만 재현되는 플레이크가 난다(실측: DataExportServiceExtTest).
    // 값 자체는 각 테스트가 필요로 하는 것의 합집합이며 서로 무해하다.
    properties = {
      "notification.outbox.enabled=true",
      // 세 스케줄러의 기동 1회 실행 지연(notification.scheduler.initial_delay_ms)은
      // application-test.yml 로 옮겼다 — 스위트 전체에 같은 값이 필요하고, 이 노브의 실소비자가
      // 테스트뿐이라 프로덕션 프로퍼티로 남길 이유가 없었다.
      "notification.worker.poll_interval_ms=3600000",
      "notification.worker.listen_notify=false",
      "notification.worker.zombie_age_minutes=0",
      "notification.retention.sent_days=3650",
      "notification.retention.permanent_failure_days=3650",
      // 주기 자체도 스위트 길이보다 길게 잡아 두 번째 실행이 아예 오지 않게 한다.
      "notification.metrics.refresh_interval_ms=3600000"
    })
class NotificationMetricsTenantGaugeTest extends IntegrationTestBase {

  @Autowired private NotificationMetrics metrics;
  @Autowired private NotificationOutboxRepository outboxRepo;
  @Autowired private MeterRegistry registry;
  @Autowired private DSLContext dsl;

  private long tenantId;

  @BeforeEach
  void createScratchTenant() {
    tenantId = TenantRlsTestSupport.createActiveTenant(dsl, "outbox-gauge");
    TenantContext.set(tenantId);
  }

  @AfterEach
  void cleanupScratchTenant() {
    inTenantFixture(tenantId, () -> TenantRlsTestSupport.deleteChannelCascade(dsl, tenantId));
    TenantRlsTestSupport.deleteTenants(dsl, tenantId);
  }

  @Test
  void refreshPendingGauges_reportsPerTenantAndZeroesDrainedTenants() {
    UUID corr = UUID.randomUUID();
    outboxRepo.insertIfAbsent(sampleRow("gauge-" + corr, corr));

    // 스크레이프 스레드 재현 — 컨텍스트 없이 부른다.
    TenantContext.clear();
    metrics.refreshPendingGauges();

    assertThat(isolatedGauge(registry))
        .as("테넌트 태그가 붙은 게이지가 이 테넌트의 적체를 보고해야 한다")
        .isEqualTo(1.0);

    // 큐가 비면 그 테넌트는 outbox_tenant_ids 목록에서 사라진다. 0 으로 내려야 마지막 값이
    // 영원히 남는 거짓 경보가 되지 않는다.
    inTenantFixture(tenantId, () -> TenantRlsTestSupport.deleteChannelCascade(dsl, tenantId));
    TenantContext.clear();
    metrics.refreshPendingGauges();

    assertThat(isolatedGauge(registry)).as("적체가 사라진 테넌트의 게이지는 0 이어야 한다").isEqualTo(0.0);
    TenantContext.set(tenantId); // teardown 을 위해 복구
  }

  /**
   * 조회가 실패한 테넌트는 <b>직전 값을 이월</b>해야 한다(거짓 음성 방지).
   *
   * <p>0 으로 두면 "적체 없음"으로 보고되어, 테넌트 T 의 조회가 계속 실패하는 동안 큐가 무한정
   * 자라도 알람이 뜨지 않는다. 적체 알람에서 이 거짓 음성은 거짓 양성보다 나쁘다. 실패 자체는
   * 별도 카운터로 알람 대상이 된다.
   *
   * <p>공유 레지스트리를 더럽히지 않도록 {@link SimpleMeterRegistry} + 손으로 조립한
   * {@link NotificationMetrics} 를 쓰고, 리포지토리만 {@link Proxy} 로 감싸 실패를 주입한다.
   */
  @Test
  void refreshPendingGauges_carriesForwardLastValueWhenTenantQueryFails() {
    UUID corr = UUID.randomUUID();
    outboxRepo.insertIfAbsent(sampleRow("gauge-fail-" + corr, corr));

    AtomicBoolean failCountPending = new AtomicBoolean(false);
    MeterRegistry isolated = new SimpleMeterRegistry();
    // 축출 임계는 이 테스트의 관심사가 아니므로 프로퍼티 기본값(10)과 같은 값을 그대로 넘긴다.
    NotificationMetrics subject =
        new NotificationMetrics(isolated, failingRepo(failCountPending), true, 10);

    TenantContext.clear();
    subject.refreshPendingGauges();
    assertThat(isolatedGauge(isolated)).as("사전 조건: 정상 패스에서 1 을 본다").isEqualTo(1.0);

    // 이제 이 테넌트의 조회만 실패시킨다. 큐는 그대로 1건이다.
    failCountPending.set(true);
    subject.refreshPendingGauges();

    assertThat(isolatedGauge(isolated))
        .as("조회 실패 테넌트는 직전 값을 이월해야 한다 (0 이면 거짓 음성)")
        .isEqualTo(1.0);
    assertThat(isolated.find("notification_metrics_refresh_failures_total").counter())
        .as("실패 자체가 알람 대상이 되도록 카운터가 올라야 한다")
        .isNotNull();
    // 정확한 값으로 단언하지 않는다: 공유 테스트 DB 에는 다른 세션이 남긴 tenant_id=1 의 PENDING
    // 행이 있어 그 테넌트도 같은 패스에서 실패하므로, 실패 건수는 순회한 테넌트 수에 달려 있다.
    assertThat(isolated.find("notification_metrics_refresh_failures_total").counter().count())
        .isGreaterThanOrEqualTo(1.0);

    TenantContext.set(tenantId); // teardown 을 위해 복구
  }

  /** {@code countPendingByChannel} 만 선택적으로 실패시키는 리포지토리 래퍼. 나머지는 실제 구현에 위임한다. */
  private NotificationOutboxRepository failingRepo(AtomicBoolean fail) {
    return (NotificationOutboxRepository)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {NotificationOutboxRepository.class},
            (proxy, method, args) -> {
              if (fail.get() && method.getName().equals("countPendingByChannel")) {
                throw new IllegalStateException("게이지 조회 강제 실패");
              }
              try {
                return method.invoke(outboxRepo, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  /**
   * 주어진 레지스트리에서 (이 테스트의 테넌트, CHAT) 게이지 값을 읽는다.
   *
   * <p>주입된 {@code registry} 와 실패 케이스가 쓰는 격리 레지스트리 양쪽이 이 하나를 쓴다 —
   * 예전에는 {@code registry} 전용 사본이 따로 있었으나 본문이 한 글자도 다르지 않았다.
   */
  private Double isolatedGauge(MeterRegistry isolated) {
    Gauge gauge =
        isolated
            .find("notification_outbox_pending_count")
            .tag("tenant", Long.toString(tenantId))
            .tag("channel", ChannelType.CHAT.name())
            .gauge();
    assertThat(gauge).as("테넌트 태그가 붙은 게이지가 등록돼 있어야 한다").isNotNull();
    return gauge.value();
  }

  private NotificationOutboxRow sampleRow(String key, UUID corr) {
    return new NotificationOutboxRow(
        null,
        key,
        corr,
        "TEST_GAUGE",
        null,
        ChannelType.CHAT,
        null,
        null,
        null,
        null,
        "{\"t\":\"x\"}",
        "STANDARD",
        "PENDING",
        0,
        Instant.now());
  }
}
