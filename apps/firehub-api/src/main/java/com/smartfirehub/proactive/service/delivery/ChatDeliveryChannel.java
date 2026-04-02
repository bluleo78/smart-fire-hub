package com.smartfirehub.proactive.service.delivery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.notification.dto.NotificationEvent;
import com.smartfirehub.notification.service.SseEmitterRegistry;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.repository.ProactiveMessageRepository;
import com.smartfirehub.proactive.util.ProactiveConfigParser;
import com.smartfirehub.proactive.util.ProactiveConfigParser.ChannelConfig;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatDeliveryChannel implements DeliveryChannel {

  private final ProactiveMessageRepository messageRepository;
  private final SseEmitterRegistry sseRegistry;
  private final ObjectMapper objectMapper;

  @Override
  public String type() {
    return "CHAT";
  }

  @Override
  public void deliver(ProactiveJobResponse job, Long executionId, ProactiveResult result) {
    try {
      // config에서 CHAT 채널의 recipientUserIds 추출
      Optional<ChannelConfig> chatConfig =
          ProactiveConfigParser.getChannelConfig(job.config(), "CHAT");

      List<Long> userIds =
          chatConfig
              .map(ChannelConfig::recipientUserIds)
              .filter(ids -> !ids.isEmpty())
              .orElse(List.of(job.userId())); // 미지정 시 생성자

      Map<String, Object> contentMap = objectMapper.convertValue(result, new TypeReference<>() {});

      for (Long userId : userIds) {
        try {
          Long messageId =
              messageRepository.create(userId, executionId, result.title(), contentMap, "REPORT");

          NotificationEvent event =
              new NotificationEvent(
                  UUID.randomUUID().toString(),
                  "PROACTIVE_MESSAGE",
                  "INFO",
                  result.title(),
                  "새로운 Proactive AI 리포트가 도착했습니다",
                  "PROACTIVE_JOB",
                  job.id(),
                  Map.of("messageId", messageId, "jobName", job.name()),
                  LocalDateTime.now());

          sseRegistry.broadcast(userId, event);
          log.info(
              "ChatDeliveryChannel delivered message {} to userId {} for job {}",
              messageId,
              userId,
              job.id());
        } catch (Exception e) {
          log.error("ChatDeliveryChannel failed for userId {}: {}", userId, e.getMessage());
          // 개별 전달 실패는 다른 수신자에게 영향을 주지 않도록 continue
        }
      }
    } catch (Exception e) {
      log.error("ChatDeliveryChannel delivery failed for job {}: {}", job.id(), e.getMessage(), e);
      throw new RuntimeException("Chat delivery failed: " + e.getMessage(), e);
    }
  }
}
