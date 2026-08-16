package com.smartfirehub.notification.metrics;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
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

  /**
   * 게이지 맵의 key. 예전에는 {@code tenantId + "|" + channel} 문자열이었는데, 축출 때 그 문자열을
   * 다시 쪼개 태그를 복원해야 했다 — 이 클래스가 스스로 포맷한 값을 스스로 파싱하는 왕복이라
   * 포맷과 파서가 따로 놀 여지가 있었다. 레코드로 들고 있으면 축출이 필드를 그대로 쓴다.
   */
  record GaugeKey(long tenantId, ChannelType channel) {}

  /** 값은 등록된 게이지가 바라보는 갱신 대상이다. */
  private final Map<GaugeKey, AtomicLong> pendingGauges = new ConcurrentHashMap<>();

  /**
   * key 별 "연속 미출현 패스 횟수". {@link #gaugeEvictionPasses} 에 도달하면 축출한다.
   *
   * <p>실패로 carry-forward 된 key 는 이번 패스에도 "값이 정해진" 것으로 취급해 0 으로 리셋한다 —
   * 축출 후보가 아니라는 뜻이다(아래 {@link #refreshPendingGauges} javadoc 참조).
   */
  private final Map<GaugeKey, Integer> missPasses = new ConcurrentHashMap<>();

  /**
   * 미출현 축출 임계 패스 수. 기본값 {@code 10} × 기본 갱신 주기
   * {@code notification.metrics.refresh_interval_ms:30000}(30초) = <b>약 5분</b>의 유예다.
   *
   * <p>드레인 후 곧바로 재적체되는 정상 흐름에서 게이지가 등록/해제를 반복하지 않을 만큼 길게,
   * 그러나 삭제·휴면 테넌트의 잔재가 메모리(아래 {@link #refreshPendingGauges} javadoc 의
   * "축출" 참조)에 남는 시간은 짧게 잡은 절충값이다.
   *
   * <p><b>두 프로퍼티가 곱셈으로 묶여 있다</b> — {@code refresh_interval_ms} 를 바꾸면 이 값을
   * 그대로 둬도 실제 유예 시간(초 단위)이 함께 바뀐다. 유예 시간 자체를 고정하고 싶다면 이 값을
   * 스케줄 주기에 맞춰 같이 조정해야 한다.
   */
  private final int gaugeEvictionPasses;

  public NotificationMetrics(
      MeterRegistry registry,
      NotificationOutboxRepository outboxRepo,
      @Value("${notification.outbox.enabled:false}") boolean outboxEnabled,
      @Value("${notification.metrics.gauge_eviction_passes:10}") int gaugeEvictionPasses) {
    this.registry = registry;
    this.outboxRepo = outboxRepo;
    this.outboxEnabled = outboxEnabled;
    // 0·음수를 그대로 두면 첫 미출현 패스에서 바로 축출돼 유예 자체가 사라진다(드레인 후 재적체하는
    // 정상 흐름에서 등록/해제가 반복된다). 최솟값 1 = "한 패스 미출현이면 축출"로 바닥을 친다.
    this.gaugeEvictionPasses = Math.max(1, gaugeEvictionPasses);
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
   * (PENDING 행을 가진 적 있는 테넌트) × (채널 4종)으로 제한된다. 폭발이 우려되면 태그를
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
   * <p><b>축출(P2-g).</b> 삭제·휴면 테넌트의 (테넌트,채널) 게이지는 이전에는 영원히 남아
   * {@code pendingGauges} 맵과 {@link MeterRegistry} 미터에 <b>프로세스 수명 내내</b> 누적되고,
   * 매 패스 스왑 순회 대상이 됐다 — 실제로 유효한 근거는 이 메모리 누적과 순회 비용이다.
   * (스크레이프 페이로드가 함께 늘어나는 것도 사실이지만, 이건 Prometheus 등 exposition 레지스트리가
   * 붙어 있을 때만 성립한다. 2026-08-17 검증: 이 앱은 {@code micrometer-registry-prometheus}
   * 의존성이 없어 {@code /actuator/prometheus} 가 404 — 오늘은 스크레이프 자체가 존재하지 않는다.
   * 나중에 그 의존성을 추가하면 이 근거의 나머지 절반도 함께 살아난다.) 이번 패스 값이 정해지지
   * 않은 key 가 {@link #gaugeEvictionPasses} 패스 연속으로 반복되면 게이지를 완전히 제거한다
   * ({@code registry.remove} + {@code pendingGauges} 제거 — 맵만 지우고 registry 에 남기면
   * 메모리 누적이 고쳐지지 않는다).
   *
   * <p><b>즉시 축출하지 않고 유예를 두는 이유.</b> 드레인된 테넌트가 잠깐 0 이었다가 다시 쌓이는
   * 정상 흐름에서 곧바로 축출하면 게이지가 등록/해제를 반복해 스크레이프 사이 시계열이 끊긴다.
   *
   * <p><b>실패는 축출 후보가 아니다.</b> 조회에 실패한 key 는 위에서 이미 직전 값을 이월해 {@code
   * next} 에 채워 넣었으므로 "값이 정해진" 것으로 취급되어 미출현 카운터가 리셋된다. 실패를
   * "목록에 없음"으로 잘못 처리하면, DB 가 흔들리는 동안 carry-forward 가 막으려던 거짓 음성(적체
   * 게이지가 0 으로 보이는 것)을 축출이 뒷문으로 되살리게 된다.
   *
   * <p>이 규칙의 직접적인 귀결로, <b>이미 드레인돼 0 을 보고하던 테넌트가 그 뒤로 조회에 계속
   * 실패하면 그 게이지는 영원히 축출되지 않는다</b>(0 을 계속 이월할 뿐이다). 버그가 아니라
   * 의도된 트레이드오프다 — 실패 중에는 "정말 드레인된 것"과 "적체가 있는데 관측이 안 되는 것"을
   * 구분할 방법이 없으므로, 축출을 보류하는 쪽이 거짓 음성보다 안전하다.
   */
  @Scheduled(
      // 기동 직후 1회 실행이 기본(0). 노브 사유는 NotificationDispatchWorker.pollOnce 주석 참조.
      initialDelayString = "${notification.scheduler.initial_delay_ms:0}",
      fixedDelayString = "${notification.metrics.refresh_interval_ms:30000}")
  void refreshPendingGauges() {
    if (!outboxEnabled) return;

    // 이번 패스의 값을 여기 모았다가 마지막에 스왑한다(위 javadoc — "전부 0" 창 방지).
    Map<GaugeKey, Long> next = new HashMap<>();

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
                next.put(new GaugeKey(tenantId, ch), counts.getOrDefault(ch, 0L));
              }
            });
      } catch (Exception e) {
        // 한 테넌트의 조회 실패로 나머지 테넌트의 적체가 보이지 않게 되면 안 된다.
        // 실패한 테넌트는 직전 값을 그대로 이월한다 — 0 으로 두면 거짓 음성이다(위 javadoc).
        for (ChannelType ch : ChannelType.values()) {
          GaugeKey key = new GaugeKey(tenantId, ch);
          AtomicLong previous = pendingGauges.get(key);
          if (previous != null) {
            next.put(key, previous.get());
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
    // 드레인(또는 소멸) → 0. ConcurrentHashMap 의 keySet 이터레이터는 weakly-consistent 라
    // 순회 중 evict() 가 remove 해도 ConcurrentModificationException 이 없다 — 스냅샷 사본은 불필요.
    for (GaugeKey key : pendingGauges.keySet()) {
      Long value = next.get(key);
      if (value != null) {
        // 값이 정해졌다 = 성공 갱신 또는 실패 carry-forward. 둘 다 축출 후보가 아니다.
        // key 는 스냅샷이라 그 사이 사라졌을 수 있다 — 아래 유예 분기와 같은 방식으로 널을 막는다.
        AtomicLong gauge = pendingGauges.get(key);
        if (gauge != null) gauge.set(value);
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
   * 제거한다. {@code pendingGauges} 에서만 지우면 이미 등록된 게이지는 레지스트리에 그대로 남아
   * 메모리 누적이 고쳐지지 않는다(Prometheus 가 붙어 있다면 스크레이프 페이로드도 줄지 않는다)
   * — 그래서 {@code registry.remove} 를 반드시 함께 부른다.
   */
  private void evict(GaugeKey key) {
    registry
        .find("notification_outbox_pending_count")
        .tags(tagsOf(key))
        .meters()
        .forEach(registry::remove);
    pendingGauges.remove(key);
    missPasses.remove(key);
    log.info(
        "PENDING 게이지 축출 — 테넌트 {} 채널 {} ({}패스 연속 미출현)",
        key.tenantId(),
        key.channel(),
        gaugeEvictionPasses);
  }

  /** (테넌트, 채널) 게이지를 처음 볼 때 등록하고 그 뒤로는 같은 {@link AtomicLong} 을 재사용한다. */
  private AtomicLong gaugeFor(long tenantId, ChannelType channel) {
    return pendingGauges.computeIfAbsent(
        new GaugeKey(tenantId, channel),
        key -> registry.gauge("notification_outbox_pending_count", tagsOf(key), new AtomicLong()));
  }

  /** 등록과 축출이 같은 태그를 쓰도록 한 곳에서 만든다 — 어긋나면 축출이 조용히 아무것도 못 지운다. */
  private static Tags tagsOf(GaugeKey key) {
    return Tags.of("tenant", Long.toString(key.tenantId()), "channel", key.channel().name());
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
