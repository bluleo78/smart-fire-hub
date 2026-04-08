package com.smartfirehub.proactive.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.proactive.dto.CreateProactiveJobRequest;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.dto.UpdateProactiveJobRequest;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.proactive.service.delivery.DeliveryChannel;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ProactiveJobServiceTest extends IntegrationTestBase {

  @Autowired private ProactiveJobService proactiveJobService;
  private ProactiveJobService rawJobService;
  @Autowired private ProactiveJobExecutionRepository executionRepository;
  @Autowired private DSLContext dsl;

  @MockitoBean private ProactiveAiClient proactiveAiClient;
  @MockitoBean private ProactiveContextCollector proactiveContextCollector;
  @MockitoBean private DeliveryChannel chatDeliveryChannel;

  @MockitoBean
  private com.smartfirehub.proactive.repository.AnomalyEventRepository anomalyEventRepository;

  @MockitoBean private com.smartfirehub.notification.service.SseEmitterRegistry sseEmitterRegistry;

  private Long testUserId;

  @BeforeEach
  void setUp() {
    rawJobService = (ProactiveJobService) AopProxyUtils.getSingletonTarget(proactiveJobService);
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "proactive_test_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Proactive Test User")
            .set(USER.EMAIL, "proactive_test@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
  }

  private CreateProactiveJobRequest buildCreateRequest(String name) {
    return new CreateProactiveJobRequest(
        name, "테스트 프롬프트", null, "0 9 * * *", "Asia/Seoul", Map.of("channels", List.of("CHAT")));
  }

  private ProactiveResult buildMockResult() {
    return new ProactiveResult(
        "테스트 리포트",
        List.of(new ProactiveResult.Section("summary", "요약", "요약 내용", null, null)),
        new ProactiveResult.Usage(100, 50, 150),
        null,
        null);
  }

  // ── CRUD ─────────────────────────────────────────────────────────────────────

  @Test
  void createJob_thenGetJob_success() {
    // when
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("CRUD 테스트 작업"), testUserId);

    // then
    assertThat(created.id()).isNotNull();
    assertThat(created.name()).isEqualTo("CRUD 테스트 작업");
    assertThat(created.prompt()).isEqualTo("테스트 프롬프트");
    assertThat(created.cronExpression()).isEqualTo("0 9 * * *");
    assertThat(created.enabled()).isTrue();

    // getJob
    ProactiveJobResponse found = proactiveJobService.getJob(created.id(), testUserId);
    assertThat(found.name()).isEqualTo("CRUD 테스트 작업");
  }

  @Test
  void updateJob_success() {
    // given
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("수정 전 이름"), testUserId);

    // when
    UpdateProactiveJobRequest updateReq =
        new UpdateProactiveJobRequest("수정 후 이름", "수정된 프롬프트", null, "0 8 * * *", null, null, null);
    proactiveJobService.updateJob(created.id(), updateReq, testUserId);

    // then
    ProactiveJobResponse updated = proactiveJobService.getJob(created.id(), testUserId);
    assertThat(updated.name()).isEqualTo("수정 후 이름");
    assertThat(updated.cronExpression()).isEqualTo("0 8 * * *");
  }

  @Test
  void deleteJob_thenGetJob_throwsException() {
    // given
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("삭제될 작업"), testUserId);
    Long id = created.id();

    // when
    proactiveJobService.deleteJob(id, testUserId);

    // then
    assertThatThrownBy(() -> proactiveJobService.getJob(id, testUserId))
        .isInstanceOf(ProactiveJobException.class)
        .hasMessageContaining("Proactive Job을 찾을 수 없습니다");
  }

  // ── Execute success ───────────────────────────────────────────────────────────

  @Test
  void executeJob_success_executionCompletedAndResultSaved() {
    // given
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("실행 테스트 작업"), testUserId);

    when(proactiveContextCollector.collectContext(any(), any())).thenReturn("{}");
    when(proactiveAiClient.execute(
            anyLong(), anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
        .thenReturn(buildMockResult());
    when(chatDeliveryChannel.type()).thenReturn("CHAT");

    // when — executeJob is not transactional so we call it directly
    rawJobService.executeJob(created.id(), testUserId);

    // then: execution row should exist and be COMPLETED
    var executions = executionRepository.findByJobId(created.id(), 10, 0);
    assertThat(executions).hasSize(1);
    assertThat(executions.get(0).status()).isEqualTo("COMPLETED");
    assertThat(executions.get(0).result()).isNotNull();
  }

  // ── Execute failure ───────────────────────────────────────────────────────────

  @Test
  void executeJob_aiClientThrows_executionFailed() {
    // given
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("실패 테스트 작업"), testUserId);

    when(proactiveContextCollector.collectContext(any(), any())).thenReturn("{}");
    when(proactiveAiClient.execute(
            anyLong(), anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
        .thenThrow(new RuntimeException("AI Agent 연결 실패"));

    // when / then
    assertThatThrownBy(() -> rawJobService.executeJob(created.id(), testUserId))
        .isInstanceOf(ProactiveJobException.class)
        .hasMessageContaining("Job 실행 실패");

    // execution should be FAILED
    var executions = executionRepository.findByJobId(created.id(), 10, 0);
    assertThat(executions).hasSize(1);
    assertThat(executions.get(0).status()).isEqualTo("FAILED");
    assertThat(executions.get(0).errorMessage()).contains("AI Agent 연결 실패");
  }

  // ── DeliveryChannel ───────────────────────────────────────────────────────────

  @Test
  void executeJob_chatChannelInConfig_chatDeliveryChannelCalled() {
    // given
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("채널 테스트 작업"), testUserId);

    when(proactiveContextCollector.collectContext(any(), any())).thenReturn("{}");
    when(proactiveAiClient.execute(
            anyLong(), anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
        .thenReturn(buildMockResult());
    when(chatDeliveryChannel.type()).thenReturn("CHAT");

    // when
    rawJobService.executeJob(created.id(), testUserId);

    // then: ChatDeliveryChannel.deliver() was called once
    verify(chatDeliveryChannel, times(1)).deliver(any(), anyLong(), any());
  }

  @Test
  void executeJob_deliveryChannelThrows_executionStillCompleted() {
    // given: channel throws but should not fail the execution
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("채널 오류 내성 테스트"), testUserId);

    when(proactiveContextCollector.collectContext(any(), any())).thenReturn("{}");
    when(proactiveAiClient.execute(
            anyLong(), anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
        .thenReturn(buildMockResult());
    when(chatDeliveryChannel.type()).thenReturn("CHAT");
    doThrow(new RuntimeException("채널 오류"))
        .when(chatDeliveryChannel)
        .deliver(any(), anyLong(), any());

    // when — should NOT throw despite channel failure
    rawJobService.executeJob(created.id(), testUserId);

    // then: execution is still COMPLETED
    var executions = executionRepository.findByJobId(created.id(), 10, 0);
    assertThat(executions).hasSize(1);
    assertThat(executions.get(0).status()).isEqualTo("COMPLETED");
  }

  // ── 단건 실행 조회 ──────────────────────────────────────────────────────────────

  /** 단건 실행 조회 — 정상 케이스 */
  @Test
  void getExecution_returns_single_execution() {
    ProactiveJobResponse job =
        proactiveJobService.createJob(buildCreateRequest("단건조회테스트"), testUserId);

    Long execId =
        dsl.insertInto(PROACTIVE_JOB_EXECUTION)
            .set(PROACTIVE_JOB_EXECUTION.JOB_ID, job.id())
            .set(PROACTIVE_JOB_EXECUTION.STATUS, "COMPLETED")
            .set(PROACTIVE_JOB_EXECUTION.STARTED_AT, java.time.LocalDateTime.now().minusMinutes(5))
            .set(PROACTIVE_JOB_EXECUTION.COMPLETED_AT, java.time.LocalDateTime.now())
            .returning(PROACTIVE_JOB_EXECUTION.ID)
            .fetchOne()
            .getId();

    var result = proactiveJobService.getExecution(execId);

    assertThat(result).isNotNull();
    assertThat(result.id()).isEqualTo(execId);
    assertThat(result.jobId()).isEqualTo(job.id());
    assertThat(result.status()).isEqualTo("COMPLETED");
  }

  /** 단건 실행 조회 — 존재하지 않는 ID */
  @Test
  void getExecution_throws_when_not_found() {
    assertThatThrownBy(() -> proactiveJobService.getExecution(999999L))
        .isInstanceOf(ProactiveJobException.class)
        .hasMessageContaining("999999");
  }

  // ── 이상 탐지 이벤트 테스트 ──

  /** onAnomalyDetected 호출 시 이벤트가 DB에 저장되고 SSE 알림이 전송되는지 검증 */
  @Test
  void onAnomalyDetected_saves_event_and_sends_sse_notification() {
    var job = proactiveJobService.createJob(buildCreateRequest("이상탐지 알림 테스트"), testUserId);
    var event =
        new com.smartfirehub.proactive.dto.AnomalyEvent(
            job.id(),
            testUserId,
            "metric1",
            "테스트 메트릭",
            50.0,
            10.0,
            3.0,
            13.33,
            "medium",
            List.of(8.0, 10.0, 12.0));

    rawJobService.onAnomalyDetected(event);

    verify(anomalyEventRepository).save(event);
    verify(sseEmitterRegistry)
        .broadcast(
            org.mockito.ArgumentMatchers.eq(testUserId),
            org.mockito.ArgumentMatchers.argThat(
                n -> "ANOMALY_DETECTED".equals(n.eventType()) && "WARNING".equals(n.severity())));
  }

  /** 쿨다운 내 재호출 시 save/broadcast가 1번만 호출되는지 검증 */
  @Test
  void onAnomalyDetected_respects_cooldown_on_second_call() {
    var job = proactiveJobService.createJob(buildCreateRequest("쿨다운 테스트"), testUserId);
    when(proactiveContextCollector.collectContext(any(), any())).thenReturn("{}");
    when(proactiveAiClient.execute(
            anyLong(), anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
        .thenReturn(buildMockResult());
    when(chatDeliveryChannel.type()).thenReturn("CHAT");

    var event =
        new com.smartfirehub.proactive.dto.AnomalyEvent(
            job.id(),
            testUserId,
            "metric_cd",
            "쿨다운 메트릭",
            100.0,
            20.0,
            5.0,
            16.0,
            "high",
            List.of(15.0, 18.0));

    rawJobService.onAnomalyDetected(event);
    rawJobService.onAnomalyDetected(event);

    verify(anomalyEventRepository, times(1)).save(event);
    verify(sseEmitterRegistry, times(1)).broadcast(anyLong(), any());
  }

  /** getAnomalyEvents가 저장된 이벤트를 반환하는지 검증 */
  @Test
  void getAnomalyEvents_returns_events() {
    var record1 =
        new com.smartfirehub.proactive.repository.AnomalyEventRepository.AnomalyEventRecord(
            1L, 1L, "m1", "메트릭1", 30.0, 10.0, 2.0, 10.0, "low", java.time.LocalDateTime.now());
    when(anomalyEventRepository.findByJobId(1L, 10)).thenReturn(List.of(record1));

    var results = proactiveJobService.getAnomalyEvents(1L, 10);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).metricName()).isEqualTo("메트릭1");
  }
}
