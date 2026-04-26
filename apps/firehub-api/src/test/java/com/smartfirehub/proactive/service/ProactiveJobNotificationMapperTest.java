package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.NotificationRequest;
import com.smartfirehub.notification.Recipient;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ProactiveJob config → NotificationRequest 매핑 단위 검증.
 *
 * <p>구 형식(["CHAT", "EMAIL"])과 신 형식([{type, recipientUserIds, recipientEmails}]) 모두 수용, 같은 사용자에게 여러
 * 채널은 한 Recipient에 묶이는지, 외부 이메일이 user id 없이 별도 Recipient로 떨어지는지 확인.
 */
class ProactiveJobNotificationMapperTest {

  @Test
  void newFormat_twoChannels_singleUser_mergedToOneRecipient() {
    Map<String, Object> config =
        Map.of(
            "channels",
            List.of(
                Map.of(
                    "type", "CHAT", "recipientUserIds", List.of(10), "recipientEmails", List.of()),
                Map.of(
                    "type",
                    "EMAIL",
                    "recipientUserIds",
                    List.of(10),
                    "recipientEmails",
                    List.of())));
    ProactiveJobResponse job = sampleJob(config, 99L);
    ProactiveResult result = sampleResult();

    NotificationRequest req = ProactiveJobNotificationMapper.toRequest(job, 100L, result);

    assertThat(req.eventType()).isEqualTo("PROACTIVE_RESULT");
    assertThat(req.eventSourceId()).isEqualTo(100L);
    assertThat(req.createdByUserId()).isEqualTo(99L);
    assertThat(req.recipients()).hasSize(1);
    Recipient r = req.recipients().get(0);
    assertThat(r.userId()).isEqualTo(10L);
    assertThat(r.requestedChannels())
        .containsExactlyInAnyOrder(ChannelType.CHAT, ChannelType.EMAIL);
  }

  @Test
  void newFormat_externalEmail_addedAsSeparateRecipient() {
    Map<String, Object> config =
        Map.of(
            "channels",
            List.of(
                Map.of(
                    "type",
                    "EMAIL",
                    "recipientUserIds",
                    List.of(10),
                    "recipientEmails",
                    List.of("external@example.com"))));
    ProactiveJobResponse job = sampleJob(config, 99L);

    NotificationRequest req = ProactiveJobNotificationMapper.toRequest(job, 100L, sampleResult());

    assertThat(req.recipients()).hasSize(2);
    // 첫 번째: user id 10, EMAIL
    assertThat(req.recipients().get(0).userId()).isEqualTo(10L);
    // 두 번째: 외부 이메일
    assertThat(req.recipients().get(1).userId()).isNull();
    assertThat(req.recipients().get(1).externalAddressIfAny()).isEqualTo("external@example.com");
    assertThat(req.recipients().get(1).requestedChannels()).containsExactly(ChannelType.EMAIL);
  }

  @Test
  void oldFormat_channelListOnly_defaultsToJobOwner() {
    // 구 형식: channels = ["CHAT", "EMAIL"]
    Map<String, Object> config = Map.of("channels", List.of("CHAT", "EMAIL"));
    ProactiveJobResponse job = sampleJob(config, 99L);

    NotificationRequest req = ProactiveJobNotificationMapper.toRequest(job, 100L, sampleResult());

    assertThat(req.recipients()).hasSize(1);
    assertThat(req.recipients().get(0).userId()).isEqualTo(99L); // job owner
    assertThat(req.recipients().get(0).requestedChannels())
        .containsExactlyInAnyOrder(ChannelType.CHAT, ChannelType.EMAIL);
  }

  @Test
  void payloadRef_populatedWithExecutionId() {
    Map<String, Object> config = Map.of("channels", List.of("CHAT"));
    ProactiveJobResponse job = sampleJob(config, 99L);

    NotificationRequest req = ProactiveJobNotificationMapper.toRequest(job, 555L, sampleResult());

    assertThat(req.payloadRef()).isNotNull();
    assertThat(req.payloadRef().type()).isEqualTo("PROACTIVE_EXECUTION");
    assertThat(req.payloadRef().id()).isEqualTo(555L);
  }

  @Test
  void payload_metadataIncludesExecutionJobInfo() {
    Map<String, Object> config = Map.of("channels", List.of("CHAT"));
    ProactiveJobResponse job = sampleJob(config, 99L);

    NotificationRequest req = ProactiveJobNotificationMapper.toRequest(job, 555L, sampleResult());

    assertThat(req.standardPayload().metadata())
        .containsEntry("executionId", 555L)
        .containsEntry("jobId", job.id())
        .containsEntry("messageType", "REPORT");
  }

  private ProactiveJobResponse sampleJob(Map<String, Object> config, long ownerUserId) {
    return new ProactiveJobResponse(
        42L,
        ownerUserId,
        null,
        null, // templateId, templateName
        "Test Job",
        "prompt",
        null,
        null, // cronExpression, timezone
        true,
        config,
        null,
        null, // lastExecutedAt, nextExecuteAt
        LocalDateTime.now(),
        LocalDateTime.now(),
        null); // lastExecution
  }

  private ProactiveResult sampleResult() {
    return new ProactiveResult("제목", List.of(), null, null, "요약");
  }
}
