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
        new ProactiveResult.Usage(100, 50, 150));
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
}
