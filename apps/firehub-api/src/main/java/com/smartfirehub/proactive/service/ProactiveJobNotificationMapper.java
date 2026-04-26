package com.smartfirehub.proactive.service;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.NotificationRequest;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.Recipient;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.util.ProactiveConfigParser;
import com.smartfirehub.proactive.util.ProactiveConfigParser.ChannelConfig;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ProactiveJob 실행 결과를 NotificationDispatcher가 받는 {@link NotificationRequest}로 변환.
 *
 * <p>config.channels(구·신 형식 모두 호환)를 순회하며 수신자별로 펼친다. 같은 사용자가 여러 채널로 지정되면 한 Recipient에 채널 집합을 묶어
 * outbox 행 수를 줄인다(멱등성 키 기반 중복 방지는 Dispatcher가 처리).
 */
public final class ProactiveJobNotificationMapper {

  private ProactiveJobNotificationMapper() {}

  public static NotificationRequest toRequest(
      ProactiveJobResponse job, Long executionId, ProactiveResult result) {
    // 채널 설정 파싱 → 사용자별로 수신 채널 집합 묶기
    List<ChannelConfig> configs = ProactiveConfigParser.parseChannels(job.config());

    // userId → 이 사용자에게 보낼 채널들의 집합
    Map<Long, EnumSet<ChannelType>> userChannels = new LinkedHashMap<>();
    // 외부 이메일 주소 (user id 없이 발송)
    List<Recipient> externalRecipients = new ArrayList<>();

    for (ChannelConfig cfg : configs) {
      ChannelType channelType = mapChannelType(cfg.type());
      if (channelType == null) continue; // 알 수 없는 타입은 skip

      // 수신자 사용자들
      List<Long> userIds = cfg.recipientUserIds();
      if (userIds == null || userIds.isEmpty()) {
        // 미지정이면 job 생성자 본인
        userIds = List.of(job.userId());
      }
      for (Long uid : userIds) {
        userChannels.computeIfAbsent(uid, k -> EnumSet.noneOf(ChannelType.class)).add(channelType);
      }

      // 외부 이메일 (EMAIL 채널에만 해당)
      if (channelType == ChannelType.EMAIL && cfg.recipientEmails() != null) {
        for (String email : cfg.recipientEmails()) {
          externalRecipients.add(new Recipient(null, email, EnumSet.of(ChannelType.EMAIL)));
        }
      }
    }

    List<Recipient> recipients = new ArrayList<>();
    for (var entry : userChannels.entrySet()) {
      recipients.add(new Recipient(entry.getKey(), null, (Set<ChannelType>) entry.getValue()));
    }
    recipients.addAll(externalRecipients);

    Payload payload = buildPayload(job, executionId, result);

    return new NotificationRequest(
        "PROACTIVE_RESULT",
        executionId,
        job.userId(),
        null, // dispatcher가 correlation UUID 생성
        payload,
        new NotificationRequest.PayloadRef("PROACTIVE_EXECUTION", executionId),
        recipients);
  }

  /** 채널 타입 문자열 → enum. 알 수 없는 타입이면 null. */
  private static ChannelType mapChannelType(String type) {
    if (type == null) return null;
    try {
      return ChannelType.valueOf(type.toUpperCase());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** Proactive 결과를 Standard Payload로 변환. metadata에 execution/job 정보를 싣는다. */
  private static Payload buildPayload(
      ProactiveJobResponse job, Long executionId, ProactiveResult result) {
    String title = result.effectiveTitle(job.name());
    String summary = result.effectiveSummary();

    Map<String, Object> metadata = new HashMap<>();
    metadata.put("executionId", executionId);
    metadata.put("jobId", job.id());
    metadata.put("jobName", job.name());
    metadata.put("messageType", "REPORT");

    return new Payload(
        Payload.PayloadType.STANDARD,
        title,
        summary == null ? "" : summary,
        List.of(),
        List.of(),
        List.of(),
        metadata,
        Map.of());
  }
}
