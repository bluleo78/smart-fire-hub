package com.smartfirehub.proactive.service;

import static com.smartfirehub.jooq.Tables.PROACTIVE_JOB;
import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.notification.service.SseEmitterRegistry;
import com.smartfirehub.proactive.dto.AnomalyEvent;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.repository.AnomalyEventRepository;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.proactive.service.delivery.DeliveryChannel;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * ProactiveJobService의 이상 탐지 이벤트 처리 로직을 검증하는 통합 테스트.
 *
 * <p>onAnomalyDetected() 호출 시 다음 동작을 확인한다:
 * <ol>
 *   <li>AnomalyEventRepository.save() 가 호출되어 이벤트가 DB에 영속화된다.
 *   <li>SseEmitterRegistry.broadcast() 가 올바른 userId 및 ANOMALY_DETECTED 이벤트 타입으로 호출된다.
 *   <li>executeJob() 이 트리거된다.
 *   <li>쿨다운 내 두 번째 호출은 건너뛴다.
 * </ol>
 */
@Transactional
class ProactiveJobServiceAnomalyTest extends IntegrationTestBase {

  @Autowired private ProactiveJobService proactiveJobService;
  @Autowired private DSLContext dsl;

  // 이상 탐지 이벤트 저장 Repository — spy로 등록하여 실제 저장도 수행하면서 호출 검증도 진행한다
  @MockitoSpyBean private AnomalyEventRepository anomalyEventRepository;
  // SSE 에미터 레지스트리 — mock으로 등록하여 broadcast() 호출 여부를 검증한다
  @MockitoBean private SseEmitterRegistry sseEmitterRegistry;
  // AI 클라이언트 — executeJob 이 실제로 완료되도록 stub (쿨다운 recordCooldown 호출을 위해 필요)
  @MockitoBean private ProactiveAiClient proactiveAiClient;
  // 컨텍스트 수집기 — executeJob 의존성 stub
  @MockitoBean private ProactiveContextCollector proactiveContextCollector;
  // 딜리버리 채널 — executeJob 의존성 stub
  @MockitoBean private DeliveryChannel chatDeliveryChannel;

  // 프록시를 우회한 raw 서비스 인스턴스 (비동기 어노테이션을 우회하여 동기 호출)
  private ProactiveJobService rawJobService;

  /** 각 테스트용 userId와 jobId */
  private Long testUserId;
  private Long testJobId;

  @BeforeEach
  void setUp() {
    // AopProxy 래퍼를 걷어낸 실제 서비스 인스턴스 획득
    rawJobService = (ProactiveJobService) AopProxyUtils.getSingletonTarget(proactiveJobService);

    // FK 순서에 맞게 user → proactive_job 순으로 선행 데이터 삽입
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "anomaly_svc_test_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Anomaly Svc Test")
            .set(USER.EMAIL, "anomaly_svc@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    testJobId =
        dsl.insertInto(PROACTIVE_JOB)
            .set(PROACTIVE_JOB.USER_ID, testUserId)
            .set(PROACTIVE_JOB.NAME, "Anomaly Service Test Job")
            .set(PROACTIVE_JOB.PROMPT, "Test prompt")
            .set(PROACTIVE_JOB.CRON_EXPRESSION, "0 9 * * *")
            .set(PROACTIVE_JOB.TIMEZONE, "Asia/Seoul")
            .set(PROACTIVE_JOB.ENABLED, true)
            .returning(PROACTIVE_JOB.ID)
            .fetchOne()
            .getId();
  }

  /**
   * onAnomalyDetected 호출 시 이벤트가 DB에 저장되고 SSE 알림이 전송되는지 검증한다.
   *
   * <p>이상 탐지 이벤트를 발생시키면: - AnomalyEventRepository.save(event) 가 1회 호출되어야 한다. -
   * SseEmitterRegistry.broadcast() 가 올바른 userId와 ANOMALY_DETECTED/WARNING 페이로드로 호출되어야 한다.
   */
  @Test
  void onAnomalyDetected_saves_event_and_sends_sse_notification() {
    // Given: 테스트용 이상 탐지 이벤트 생성
    var event =
        new AnomalyEvent(
            testJobId,
            testUserId,
            "metric1",
            "테스트 메트릭",
            50.0,
            10.0,
            3.0,
            13.33,
            "medium",
            List.of(8.0, 10.0, 12.0));

    // When: 이상 탐지 이벤트 처리 (raw 서비스로 @Async 우회)
    rawJobService.onAnomalyDetected(event);

    // Then 1: 이벤트가 Repository를 통해 저장되어야 한다
    verify(anomalyEventRepository).save(event);

    // Then 2: SSE broadcast 가 올바른 userId 및 이벤트 타입으로 호출되어야 한다
    verify(sseEmitterRegistry)
        .broadcast(
            eq(testUserId),
            argThat(
                n ->
                    "ANOMALY_DETECTED".equals(n.eventType())
                        && "WARNING".equals(n.severity())
                        && "PROACTIVE_JOB".equals(n.entityType())
                        && testJobId.equals(n.entityId())));
  }

  /**
   * onAnomalyDetected 가 쿨다운 시간 내 재호출될 경우 save/broadcast 가 두 번 호출되지 않음을 검증한다.
   *
   * <p>첫 번째 호출 이후에는 쿨다운 상태가 기록되므로 두 번째 호출은 save/broadcast 없이 조기 반환되어야 한다.
   * recordCooldown() 이 호출되려면 executeJob() 이 정상 완료되어야 하므로
   * ProactiveAiClient / ProactiveContextCollector 를 stub 해 두어야 한다.
   */
  @Test
  void onAnomalyDetected_respects_cooldown_on_second_call() {
    // Given: executeJob 이 성공적으로 완료될 수 있도록 의존성 stub
    var mockResult =
        new ProactiveResult(
            "테스트 리포트",
            List.of(new ProactiveResult.Section("summary", "요약", "요약 내용", null, null)),
            new ProactiveResult.Usage(100, 50, 150),
            null,
            null);
    when(proactiveContextCollector.collectContext(any(), any())).thenReturn("{}");
    when(proactiveAiClient.execute(
            anyLong(), anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
        .thenReturn(mockResult);
    when(chatDeliveryChannel.type()).thenReturn("CHAT");

    // Given: 동일한 이벤트를 두 번 생성
    var event =
        new AnomalyEvent(
            testJobId,
            testUserId,
            "metric_cooldown",
            "쿨다운 테스트 메트릭",
            100.0,
            20.0,
            5.0,
            16.0,
            "high",
            List.of(15.0, 18.0, 22.0));

    // When: 첫 번째 호출 → executeJob 완료 후 recordCooldown 기록됨
    rawJobService.onAnomalyDetected(event);
    // When: 두 번째 호출 → 쿨다운 내이므로 조기 반환 (save/broadcast 건너뜀)
    rawJobService.onAnomalyDetected(event);

    // Then: save 와 broadcast 는 각각 정확히 1번씩만 호출되어야 한다
    verify(anomalyEventRepository, times(1)).save(event); // 1번만 저장
    verify(sseEmitterRegistry, times(1)).broadcast(anyLong(), any()); // 1번만 알림
  }

  /**
   * getAnomalyEvents 는 저장된 이벤트 이력을 limit 범위 내에서 반환해야 한다.
   *
   * <p>DB에 직접 저장한 이벤트가 service 레이어를 통해 올바르게 조회되는지 검증한다.
   */
  @Test
  void getAnomalyEvents_returns_saved_events() {
    // Given: 이벤트 2개를 저장
    var event1 =
        new AnomalyEvent(
            testJobId, testUserId, "m1", "메트릭1", 30.0, 10.0, 2.0, 10.0, "low", List.of());
    var event2 =
        new AnomalyEvent(
            testJobId, testUserId, "m2", "메트릭2", 50.0, 10.0, 2.0, 20.0, "high", List.of());
    anomalyEventRepository.save(event1);
    anomalyEventRepository.save(event2);

    // When: 최대 10건 조회
    List<AnomalyEventRepository.AnomalyEventRecord> results =
        proactiveJobService.getAnomalyEvents(testJobId, 10);

    // Then: 저장한 2개가 반환되어야 한다
    assertThat(results).hasSize(2);
  }
}
