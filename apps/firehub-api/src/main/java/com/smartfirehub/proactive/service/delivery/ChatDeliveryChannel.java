package com.smartfirehub.proactive.service.delivery;

import com.smartfirehub.notification.dto.NotificationEvent;
import com.smartfirehub.notification.service.SseEmitterRegistry;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.repository.ProactiveMessageRepository;
import com.smartfirehub.proactive.util.ProactiveConfigParser;
import com.smartfirehub.proactive.util.ProactiveConfigParser.ChannelConfig;
import java.time.LocalDateTime;
import java.util.HashMap;
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

      // htmlContent는 수십 KB일 수 있으므로 채팅 메시지 contentMap에서 제외한다.
      // 대신 summary 텍스트만 저장하고, executionId를 메타데이터로 포함해
      // 프론트엔드에서 "리포트 보기" 링크를 생성할 수 있도록 한다.
      // 채팅 메시지에 저장할 내용: summary + 메타데이터 (htmlContent는 제외)
      String title = result.effectiveTitle(job.name());
      Map<String, Object> contentMap = new HashMap<>();
      contentMap.put("title", title);
      contentMap.put("summary", result.effectiveSummary());
      contentMap.put("executionId", String.valueOf(executionId));
      contentMap.put("jobId", String.valueOf(job.id()));

      for (Long userId : userIds) {
        try {
          Long messageId =
              messageRepository.create(userId, executionId, title, contentMap, "REPORT");

          Map<String, Object> metadata = new HashMap<>();
          metadata.put("messageId", messageId);
          metadata.put("jobName", job.name());
          metadata.put("executionId", executionId);

          NotificationEvent event =
              new NotificationEvent(
                  UUID.randomUUID().toString(),
                  "PROACTIVE_MESSAGE",
                  "INFO",
                  title,
                  "새로운 Proactive AI 리포트가 도착했습니다",
                  "PROACTIVE_JOB",
                  job.id(),
                  metadata,
                  LocalDateTime.now());

          sseRegistry.broadcast(userId, event);
          log.info(
              "ChatDeliveryChannel delivered message {} to userId {} for job {}",
              messageId,
              userId,
              job.id());
        } catch (Exception e) {
          log.error("ChatDeliveryChannel failed for userId {}: {}", userId, e.getMessage(), e);
          // 개별 전달 실패는 다른 수신자에게 영향을 주지 않도록 continue
        }
      }
    } catch (Exception e) {
      log.error("ChatDeliveryChannel delivery failed for job {}: {}", job.id(), e.getMessage(), e);
      throw new RuntimeException("Chat delivery failed: " + e.getMessage(), e);
    }
  }
}
