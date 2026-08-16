package com.smartfirehub.notification.metrics;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Notification Outbox 관측 메트릭 등록·접근 헬퍼.
 *
 * <p>Gauge: (테넌트, 채널)별 PENDING 개수 — 주기 갱신(아래 {@link #refreshPendingGauges} 참조).
 * Counter/Timer: Worker에서 호출해 주입.
 */
@Component
public class NotificationMetrics {

  private static final Logger log = LoggerFactory.getLogger(NotificationMetrics.class);

  private final MeterRegistry registry;
  private final NotificationOutboxRepository outboxRepo;
  private final boolean outboxEnabled;

  /** key = {@code tenantId + "|" + channel}. 값은 등록된 게이지가 바라보는 갱신 대상이다. */
  private final Map<String, AtomicLong> pendingGauges = new ConcurrentHashMap<>();

  /**
   * key 별 "연속 미출현 패스 횟수". {@link #gaugeEvictionPasses} 에 도달하면 축출한다.
   *
   * <p>실패로 carry-forward 된 key 는 이번 패스에도 "값이 정해진" 것으로 취급해 0 으로 리셋한다 —
   * 축출 후보가 아니라는 뜻이다(아래 {@link #refreshPendingGauges} javadoc 참조).
   */
  private final Map<String, Integer> missPasses = new ConcurrentHashMap<>();

  private final int gaugeEvictionPasses;

  public NotificationMetrics(
      MeterRegistry registry,
      NotificationOutboxRepository outboxRepo,
      @Value("${notification.outbox.enabled:false}") boolean outboxEnabled,
      @Value("${notification.metrics.gauge_eviction_passes:10}") int gaugeEvictionPasses) {
    this.registry = registry;
    this.outboxRepo = outboxRepo;
    this.outboxEnabled = outboxEnabled;
    this.gaugeEvictionPasses = gaugeEvictionPasses;
  }

  /**
   * PENDING 적체 게이지를 주기적으로 갱신한다.
   *
   * <p><b>왜 스크레이프 콜백이 아니라 주기 갱신인가(P2-f).</b> 이전 구현은 Micrometer 게이지 콜백
   * 안에서 DB 를 조회했는데, 그 콜백은 <b>메트릭 스크레이프 스레드</b>에서 실행되므로 테넌트
   * 컨텍스트가 절대 없다 — 정책(V107) 이후 이 게이지는 영원히 0 을 보고한다. 컨텍스트는 우리가
   * 열어야 하고, 열려면 "어느 테넌트를 돌지"를 먼저 알아야 하므로 조회 주체가 우리 쪽으로 와야 한다.
   *
   * <p><b>왜 합산이 아니라 테넌트 태그인가.</b> 합산은 "어느 테넌트의 큐가 막혔는가"를 감춘다 —
   * 적체 알람의 목적 자체가 그것이라 합산 게이지는 알람으로 쓸 수 없다. 카디널리티는
   * (PENDING 행을 가진 적 있는 테넌트) × (채널 5종)으로 제한된다. 폭발이 우려되면 태그를
   * 떼고 합산 + 테넌트별 최대치로 되돌리는 것이 다음 선택지다.
   *
   * <p><b>이 게이지에서 {@code 0} 은 세 상태를 뭉갠 값이다 — 읽는 쪽이 반드시 알아야 한다.</b>
   *
   * <ul>
   *   <li><b>드레인</b>: 큐가 비었다. {@code outbox_tenant_ids} 는 <b>지금</b> PENDING 행이 있는
   *       테넌트만 돌려주므로 빈 테넌트는 목록에서 사라진다. 0 으로 내리지 않으면 마지막 적체 값을
   *       영원히 보고하는 <b>거짓 양성</b>이 된다.
   *   <li><b>미관측</b>: 아직 한 번도 등장하지 않은 (테넌트, 채널) — 게이지 자체가 없다.
   *   <li><b>조회 실패</b>: 아래에서 <b>직전 값을 이월</b>해 0 이 되지 않게 막는다. 실패를 0 으로
   *       두면 "적체 없음"이라는 <b>거짓 음성</b>이 되는데, 적체 알람에서 거짓 음성은 거짓 양성보다
   *       나쁘다 — 테넌트 T 의 커넥션 타임아웃이 반복되는 동안 큐가 무한정 자라도 알람이 뜨지 않는다.
   *       실패 자체는 {@code notification_metrics_refresh_failures_total} 로 따로 알람한다(어느
   *       테넌트인지는 로그에 있다).
   * </ul>
   *
   * <p><b>값은 마지막에 한 번에 스왑한다.</b> "전부 0 으로 내리고 순회하며 덮는" 방식은 순회가
   * 끝날 때까지 <b>모든 테넌트가 0</b> 으로 관측되는 창을 만든다(테넌트가 많으면 초 단위). 그
   * 사이 스크레이프가 들어오면 알람이 플랩한다. 그래서 이번 패스 값을 별도 맵에 모아 두고, 순회가
   * 끝난 뒤 게이지마다 한 번씩만 대입한다.
   *
   * <p><b>축출(P2-g).</b> 삭제·휴면 테넌트의 (테넌트,채널) 게이지는 이전에는 영원히 남아 매 패스
   * 순회 대상 + 매 스크레이프 페이로드에 실렸다. 이번 패스 값이 정해지지 않은 key 가 {@link
   * #gaugeEvictionPasses} 패스 연속으로 반복되면 게이지를 완전히 제거한다({@code registry.remove} +
   * {@code pendingGauges} 제거 — 맵만 지우고 registry 에 남기면 스크레이프 페이로드는 그대로다).
   *
   * <p><b>즉시 축출하지 않고 유예를 두는 이유.</b> 드레인된 테넌트가 잠깐 0 이었다가 다시 쌓이는
   * 정상 흐름에서 곧바로 축출하면 게이지가 등록/해제를 반복해 스크레이프 사이 시계열이 끊긴다.
   *
   * <p><b>실패는 축출 후보가 아니다.</b> 조회에 실패한 key 는 위에서 이미 직전 값을 이월해 {@code
   * next} 에 채워 넣었으므로 "값이 정해진" 것으로 취급되어 미출현 카운터가 리셋된다. 실패를
   * "목록에 없음"으로 잘못 처리하면, DB 가 흔들리는 동안 carry-forward 가 막으려던 거짓 음성(적체
   * 게이지가 0 으로 보이는 것)을 축출이 뒷문으로 되살리게 된다.
   */
  @Scheduled(
      // 기동 직후 1회 실행이 기본(0). 노브 사유는 NotificationDispatchWorker.pollOnce 주석 참조.
      initialDelayString = "${notification.scheduler.initial_delay_ms:0}",
      fixedDelayString = "${notification.metrics.refresh_interval_ms:30000}")
  void refreshPendingGauges() {
    if (!outboxEnabled) return;

    // 이번 패스의 값을 여기 모았다가 마지막에 스왑한다(위 javadoc — "전부 0" 창 방지).
    Map<String, Long> next = new HashMap<>();

    for (Long tenantId : outboxRepo.tenantIdsWithStatus("PENDING")) {
      try {
        TenantContext.runScoped(
            tenantId,
            () -> {
              // 채널별 개수는 한 쿼리로 받는다 — 채널마다 부르면 리포지토리의 클래스 레벨
              // @Transactional 때문에 호출마다 트랜잭션이 하나씩 열린다(리포지토리 주석 참조).
              // 조회는 반드시 이 runScoped 안에서 해야 GUC 가 이 테넌트로 심긴다.
              Map<ChannelType, Long> counts = outboxRepo.countPendingByChannel(tenantId);
              for (ChannelType ch : ChannelType.values()) {
                // 값은 next 에 모으고, 게이지 등록만 미리 해 둔다(신규 (테넌트,채널) 대응).
                gaugeFor(tenantId, ch);
                // PENDING 이 없는 채널은 맵에 없다 — 아래 스왑이 0 으로 내리는 것과 의미가 같다.
                next.put(gaugeKey(tenantId, ch), counts.getOrDefault(ch, 0L));
              }
            });
      } catch (Exception e) {
        // 한 테넌트의 조회 실패로 나머지 테넌트의 적체가 보이지 않게 되면 안 된다.
        // 실패한 테넌트는 직전 값을 그대로 이월한다 — 0 으로 두면 거짓 음성이다(위 javadoc).
        for (ChannelType ch : ChannelType.values()) {
          AtomicLong previous = pendingGauges.get(gaugeKey(tenantId, ch));
          if (previous != null) {
            next.put(gaugeKey(tenantId, ch), previous.get());
          }
        }
        Counter.builder("notification_metrics_refresh_failures_total").register(registry).increment();
        log.warn(
            "테넌트 {} PENDING 게이지 갱신 실패 — 직전 값을 이월하고 나머지 테넌트는 계속 갱신한다",
            tenantId,
            e);
      }
    }

    // 스왑 + 축출 판정. 이번 패스에 값이 정해지지 않은 게이지 = 목록에서 사라진 테넌트 =
    // 드레인(또는 소멸) → 0. key 집합을 먼저 스냅샷 떠 순회 중 evict() 의 remove 와 안전하게
    // 분리한다(ConcurrentHashMap 이라도 순회하며 지우는 것과 별도 컬렉션을 도는 것을 섞지 않는다).
    for (String key : new ArrayList<>(pendingGauges.keySet())) {
      Long value = next.get(key);
      if (value != null) {
        // 값이 정해졌다 = 성공 갱신 또는 실패 carry-forward. 둘 다 축출 후보가 아니다.
        pendingGauges.get(key).set(value);
        missPasses.remove(key);
        continue;
      }
      // 이번 패스 목록에 전혀 없었다 — 드레인/소멸 후보. 연속 횟수를 늘리고 임계 도달 시 축출한다.
      int misses = missPasses.merge(key, 1, Integer::sum);
      if (misses >= gaugeEvictionPasses) {
        evict(key);
      } else {
        // 아직 유예 기간 — 드레인과 동일하게 0 으로만 내리고 게이지 자체는 남긴다.
        AtomicLong gauge = pendingGauges.get(key);
        if (gauge != null) gauge.set(0L);
      }
    }
  }

  /**
   * key 에 해당하는 게이지를 {@link MeterRegistry} 와 {@link #pendingGauges} 양쪽에서 완전히
   * 제거한다. {@code pendingGauges} 에서만 지우면 이미 등록된 게이지는 레지스트리에 그대로
   * 남아 스크레이프 페이로드가 줄지 않는다 — 그래서 {@code registry.remove} 를 반드시 함께 부른다.
   */
  private void evict(String key) {
    int sep = key.indexOf('|');
    String tenantIdTag = key.substring(0, sep);
    String channelTag = key.substring(sep + 1);
    registry
        .find("notification_outbox_pending_count")
        .tag("tenant", tenantIdTag)
        .tag("channel", channelTag)
        .meters()
        .forEach(registry::remove);
    pendingGauges.remove(key);
    missPasses.remove(key);
    log.info("PENDING 게이지 축출 — 테넌트 {} 채널 {} ({}패스 연속 미출현)", tenantIdTag, channelTag, gaugeEvictionPasses);
  }

  /** (테넌트, 채널) 게이지를 처음 볼 때 등록하고 그 뒤로는 같은 {@link AtomicLong} 을 재사용한다. */
  private AtomicLong gaugeFor(long tenantId, ChannelType channel) {
    return pendingGauges.computeIfAbsent(
        gaugeKey(tenantId, channel),
        key ->
            registry.gauge(
                "notification_outbox_pending_count",
                Tags.of("tenant", Long.toString(tenantId), "channel", channel.name()),
                new AtomicLong()));
  }

  private static String gaugeKey(long tenantId, ChannelType channel) {
    return tenantId + "|" + channel.name();
  }

  /** deliver 결과(SENT/TRANSIENT/PERMANENT_FAILURE) 소요 시간 기록. */
  public void recordDeliveryDuration(ChannelType channel, String statusTag, Duration elapsed) {
    Timer.builder("channel_delivery_duration_seconds")
        .tags(Tags.of("channel", channel.name(), "status", statusTag))
        .register(registry)
        .record(elapsed);
  }

  /** 영구 실패 카운트 증가. */
  public void incrementPermanentFailure(ChannelType channel, String reason) {
    Counter.builder("notification_outbox_permanent_failure_total")
        .tags(Tags.of("channel", channel.name(), "reason", reason))
        .register(registry)
        .increment();
  }

  /** 좀비 회복 카운트 증가. */
  public void incrementZombieRecovered(int delta) {
    Counter.builder("notification_outbox_zombie_recovered_total")
        .register(registry)
        .increment(delta);
  }
}
