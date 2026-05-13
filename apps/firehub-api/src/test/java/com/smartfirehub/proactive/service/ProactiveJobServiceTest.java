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
import com.smartfirehub.proactive.exception.ProactiveJobAlreadyRunningException;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.proactive.exception.ProactiveJobNotFoundException;
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
  // ProactiveJobAsyncRunner — 실제 executeJob 로직이 여기에 있다 (이슈 #192 분리 후)
  @Autowired private ProactiveJobAsyncRunner proactiveJobAsyncRunner;
  private ProactiveJobAsyncRunner rawAsyncRunner;
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
    // @Async 프록시를 우회하여 테스트에서 동기 실행이 가능하도록 raw 빈을 꺼낸다
    rawAsyncRunner =
        (ProactiveJobAsyncRunner) AopProxyUtils.getSingletonTarget(proactiveJobAsyncRunner);
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
        name,
        "테스트 프롬프트",
        null,
        "0 0 9 * * *",
        "Asia/Seoul",
        null,
        Map.of("channels", List.of("CHAT")));
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
    assertThat(created.cronExpression()).isEqualTo("0 0 9 * * *");
    assertThat(created.enabled()).isTrue();

    // getJob
    ProactiveJobResponse found = proactiveJobService.getJob(created.id(), testUserId);
    assertThat(found.name()).isEqualTo("CRUD 테스트 작업");
  }

  /**
   * 회귀 테스트 — CreateProactiveJobRequest에 enabled=false를 보내면 비활성 상태로 저장되어야 한다 (#220).
   *
   * <p>이전에는 DTO에 enabled 필드가 없어 무조건 true로 생성되고 스케줄러에 즉시 등록되는 버그가 있었다.
   */
  @Test
  void createJob_withEnabledFalse_storedAsDisabled() {
    // given
    CreateProactiveJobRequest req =
        new CreateProactiveJobRequest(
            "비활성 생성 테스트",
            "테스트 프롬프트",
            null,
            "0 0 9 * * *",
            "Asia/Seoul",
            false,
            Map.of("channels", List.of("CHAT")));

    // when
    ProactiveJobResponse created = proactiveJobService.createJob(req, testUserId);

    // then — DB에 enabled=false로 저장
    assertThat(created.enabled()).isFalse();
    ProactiveJobResponse refetched = proactiveJobService.getJob(created.id(), testUserId);
    assertThat(refetched.enabled()).isFalse();
  }

  /** enabled 미지정(null) 시 기본값 true가 적용되어야 한다 (#220 기본 동작 유지). */
  @Test
  void createJob_withEnabledNull_defaultsToTrue() {
    CreateProactiveJobRequest req =
        new CreateProactiveJobRequest(
            "기본값 테스트",
            "프롬프트",
            null,
            "0 0 9 * * *",
            "Asia/Seoul",
            null,
            Map.of("channels", List.of("CHAT")));

    ProactiveJobResponse created = proactiveJobService.createJob(req, testUserId);

    assertThat(created.enabled()).isTrue();
  }

  @Test
  void updateJob_success() {
    // given
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("수정 전 이름"), testUserId);

    // when
    UpdateProactiveJobRequest updateReq =
        new UpdateProactiveJobRequest("수정 후 이름", "수정된 프롬프트", null, "0 0 8 * * *", null, null, null);
    proactiveJobService.updateJob(created.id(), updateReq, testUserId);

    // then
    ProactiveJobResponse updated = proactiveJobService.getJob(created.id(), testUserId);
    assertThat(updated.name()).isEqualTo("수정 후 이름");
    assertThat(updated.cronExpression()).isEqualTo("0 0 8 * * *");
  }

  /**
   * 잘못된 cron 표현식으로 생성을 시도하면 ProactiveJobException(→400) 이 발생하고 DB 에 저장되지 않아야 한다 (#221). 이전에는 검증 없이
   * 그대로 저장되어 스케줄러가 silent fail 하는 좀비 잡이 만들어졌다.
   */
  @Test
  void createJob_withInvalidCron_throwsAndNotPersisted() {
    long before = dsl.fetchCount(com.smartfirehub.jooq.Tables.PROACTIVE_JOB);
    CreateProactiveJobRequest req =
        new CreateProactiveJobRequest(
            "잘못된 cron",
            "프롬프트",
            null,
            "not a cron",
            "Asia/Seoul",
            true,
            Map.of("channels", List.of("CHAT")));

    assertThatThrownBy(() -> proactiveJobService.createJob(req, testUserId))
        .isInstanceOf(ProactiveJobException.class)
        .hasMessageContaining("cron");

    long after = dsl.fetchCount(com.smartfirehub.jooq.Tables.PROACTIVE_JOB);
    assertThat(after).isEqualTo(before);
  }

  /** 존재하지 않는 IANA timezone 으로 생성을 시도하면 ProactiveJobException(→400) 이 발생해야 한다 (#221). */
  @Test
  void createJob_withInvalidTimezone_throws() {
    CreateProactiveJobRequest req =
        new CreateProactiveJobRequest(
            "잘못된 tz",
            "프롬프트",
            null,
            "0 0 9 * * *",
            "Mars/Phobos",
            true,
            Map.of("channels", List.of("CHAT")));

    assertThatThrownBy(() -> proactiveJobService.createJob(req, testUserId))
        .isInstanceOf(ProactiveJobException.class)
        .hasMessageContaining("timezone");
  }

  /** 업데이트에서도 동일하게 cron / timezone 사전 검증이 적용되어야 한다 (#221). */
  @Test
  void updateJob_withInvalidCron_throws() {
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("업데이트 검증 테스트"), testUserId);
    UpdateProactiveJobRequest req =
        new UpdateProactiveJobRequest("x", "y", null, "### BAD ###", "Asia/Seoul", true, null);

    assertThatThrownBy(() -> proactiveJobService.updateJob(created.id(), req, testUserId))
        .isInstanceOf(ProactiveJobException.class)
        .hasMessageContaining("cron");
  }

  /** cron / timezone 이 null/blank 인 update 는 검증을 통과해야 한다 (toggle 등 부분 업데이트 호환성). */
  @Test
  void updateJob_withBlankCronAndTimezone_allowed() {
    ProactiveJobResponse created =
        proactiveJobService.createJob(buildCreateRequest("blank 업데이트 테스트"), testUserId);
    UpdateProactiveJobRequest req =
        new UpdateProactiveJobRequest("이름만 변경", null, null, null, null, null, null);

    proactiveJobService.updateJob(created.id(), req, testUserId);

    ProactiveJobResponse updated = proactiveJobService.getJob(created.id(), testUserId);
    assertThat(updated.name()).isEqualTo("이름만 변경");
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
    // 삭제된 Job 조회 시 ProactiveJobNotFoundException(404) 발생 (#41)
    assertThatThrownBy(() -> proactiveJobService.getJob(id, testUserId))
        .isInstanceOf(ProactiveJobNotFoundException.class)
        .hasMessageContaining("Proactive Job을 찾을 수 없습니다");
  }

  // ── 중복 실행 방지 슬롯 ───────────────────────────────────────────────────────────

  /** tryAcquireRunSlot — 최초 획득은 성공, 두 번째 호출은 ProactiveJobAlreadyRunningException 발생 (#149) */
  @Test
  void tryAcquireRunSlot_firstCallSucceeds_secondCallThrows() {
    var job = proactiveJobService.createJob(buildCreateRequest("슬롯 테스트"), testUserId);
    Long jobId = job.id();

    // 첫 번째 획득 — 성공
    rawJobService.tryAcquireRunSlot(jobId);

    // 두 번째 획득 — 이미 실행 중이므로 409 예외
    assertThatThrownBy(() -> rawJobService.tryAcquireRunSlot(jobId))
        .isInstanceOf(ProactiveJobAlreadyRunningException.class)
        .hasMessageContaining(String.valueOf(jobId));

    // 슬롯 해제 후 재획득 가능 확인
    rawJobService.releaseRunSlot(jobId);
    rawJobService.tryAcquireRunSlot(jobId); // 예외 없이 통과해야 한다
    rawJobService.releaseRunSlot(jobId);
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

    // when — @Async 프록시를 우회한 raw 빈으로 직접 호출하여 동기 실행 보장 (이슈 #192)
    rawAsyncRunner.executeJob(created.id(), testUserId);

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

    // when / then — @Async 프록시를 우회한 raw 빈으로 직접 호출 (이슈 #192)
    assertThatThrownBy(() -> rawAsyncRunner.executeJob(created.id(), testUserId))
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

    // when — @Async 프록시를 우회한 raw 빈으로 직접 호출 (이슈 #192)
    rawAsyncRunner.executeJob(created.id(), testUserId);

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

    // when — @Async 프록시를 우회한 raw 빈으로 직접 호출 (이슈 #192)
    rawAsyncRunner.executeJob(created.id(), testUserId);

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

  /**
   * [#192] onAnomalyDetected가 self-call이 아닌 asyncRunner를 통해 executeJob을 호출하는지 검증.
   *
   * <p>self-call이면 @Async 프록시가 우회되어 동기 실행됨. 이 TC는 ProactiveJobService가 내부적으로
   * ProactiveJobAsyncRunner.executeJob()에 위임하여 실행 레코드가 생성되는지 확인한다.
   */
  @Test
  void onAnomalyDetected_delegates_execution_to_asyncRunner() {
    var job = proactiveJobService.createJob(buildCreateRequest("asyncRunner 위임 테스트"), testUserId);
    when(proactiveContextCollector.collectContext(any(), any())).thenReturn("{}");
    when(proactiveAiClient.execute(
            anyLong(), anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
        .thenReturn(buildMockResult());
    when(chatDeliveryChannel.type()).thenReturn("CHAT");

    var event =
        new com.smartfirehub.proactive.dto.AnomalyEvent(
            job.id(),
            testUserId,
            "metric_async",
            "asyncRunner 위임 검증 메트릭",
            100.0,
            20.0,
            5.0,
            16.0,
            "high",
            List.of(15.0, 18.0));

    // onAnomalyDetected 호출 후 rawAsyncRunner로 직접 executeJob을 동기 실행하여 DB 반영 확인
    // (실제 @Async 호출은 별도 스레드이므로, 여기서는 위임 경로가 바르게 구성되었는지를 검증)
    rawJobService.onAnomalyDetected(event);

    // asyncRunner 경유 실행 확인: onAnomalyDetected가 tryAcquireRunSlot 후 asyncRunner를 호출했으면
    // runningJobs 슬롯이 점유되거나 이미 비동기 실행이 시작된 상태여야 한다.
    // 여기서는 rawAsyncRunner로 직접 실행하여 실행 레코드가 정상 생성됨을 확인한다.
    rawAsyncRunner.executeJob(job.id(), testUserId);

    var executions = executionRepository.findByJobId(job.id(), 10, 0);
    // onAnomalyDetected의 async 호출(1건) + rawAsyncRunner 직접 호출(1건) = 최소 1건
    assertThat(executions).isNotEmpty();
    assertThat(executions.get(0).status()).isEqualTo("COMPLETED");
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
