package com.smartfirehub.notification.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Slack inbound event 처리 서비스.
 *
 * <p>현재는 Task 3 (SlackEventsController) 배선을 위한 stub 구현.
 * Task 5에서 AI 세션 연동 등 실제 로직으로 교체 예정.
 *
 * <p>@Async("slackInboundExecutor") — Task 4의 slackInboundExecutor bean 사용.
 * Task 4와 병렬 진행 중이므로 해당 bean이 없는 경우 Spring 기본 executor로 fallback.
 */
@Service
public class SlackInboundService {

    private static final Logger log = LoggerFactory.getLogger(SlackInboundService.class);

    /**
     * Slack event 처리 엔트리포인트.
     *
     * <p>Task 5에서 실구현. 현재는 수신 로그만 기록.
     *
     * @param teamId Slack team_id
     * @param event  event 노드 (event_callback.event 하위)
     */
    @Async("slackInboundExecutor")
    public void dispatch(String teamId, JsonNode event) {
        log.info("slack inbound dispatch — team={}, eventType={} (stub)",
                teamId, event.path("type").asText());
    }
}
