package com.smartfirehub.proactive.service.delivery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.notification.dto.NotificationEvent;
import com.smartfirehub.notification.service.SseEmitterRegistry;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.repository.ProactiveMessageRepository;
import java.time.LocalDateTime;
import java.util.Map;
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
      Map<String, Object> contentMap = objectMapper.convertValue(result, new TypeReference<>() {});
      Long messageId =
          messageRepository.create(job.userId(), executionId, result.title(), contentMap, "REPORT");

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

      sseRegistry.broadcast(job.userId(), event);
      log.info("ChatDeliveryChannel delivered message {} for job {}", messageId, job.id());
    } catch (Exception e) {
      log.error("ChatDeliveryChannel delivery failed for job {}: {}", job.id(), e.getMessage(), e);
      throw new RuntimeException("Chat delivery failed: " + e.getMessage(), e);
    }
  }
}
