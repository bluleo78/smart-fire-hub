package com.smartfirehub.notification.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** firehub-channel로부터 Slack inbound 이벤트를 수신하는 Internal 전용 엔드포인트. */
@RestController
@RequestMapping("/api/v1/channels/slack")
public class SlackInboundController {

  private final SlackInboundService slackInboundService;
  private final String channelInternalToken;

  public SlackInboundController(
      SlackInboundService slackInboundService,
      @Value("${channel.internal-token:}") String channelInternalToken) {
    this.slackInboundService = slackInboundService;
    this.channelInternalToken = channelInternalToken;
  }

  @PostMapping("/inbound")
  public ResponseEntity<Void> inbound(
      @RequestHeader(value = "Authorization", required = false) String authHeader,
      @RequestBody InboundRequest request) {
    if (!isValidInternalToken(authHeader)) {
      return ResponseEntity.status(401).build();
    }
    slackInboundService.dispatch(request.teamId(), request.event());
    return ResponseEntity.ok().build();
  }

  /** Internal 토큰 유효성 검증 — "Internal {token}" 형식 확인 후 토큰 비교. */
  private boolean isValidInternalToken(String authHeader) {
    if (authHeader == null || !authHeader.startsWith("Internal ")) return false;
    String provided = authHeader.substring("Internal ".length());
    return provided.equals(channelInternalToken);
  }

  /** firehub-channel에서 전송하는 Slack inbound 요청 DTO. */
  public record InboundRequest(String teamId, JsonNode event) {}
}
