package com.smartfirehub.notification.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

  /**
   * Internal 토큰 유효성 검증 — "Internal {token}" 형식 확인 후 토큰 비교. 타이밍 공격 방지를 위해
   * {@link MessageDigest#isEqual(byte[], byte[])}로 상수 시간 비교한다.
   */
  private boolean isValidInternalToken(String authHeader) {
    if (authHeader == null || !authHeader.startsWith("Internal ")) return false;
    if (channelInternalToken == null || channelInternalToken.isEmpty()) return false;
    String provided = authHeader.substring("Internal ".length());
    // String.equals는 첫 불일치 문자에서 즉시 반환 → 응답 시간 차이로 토큰 추측 가능.
    // MessageDigest.isEqual은 길이가 같을 때 모든 바이트를 비교하여 상수 시간을 보장한다.
    return MessageDigest.isEqual(
        provided.getBytes(StandardCharsets.UTF_8),
        channelInternalToken.getBytes(StandardCharsets.UTF_8));
  }

  /** firehub-channel에서 전송하는 Slack inbound 요청 DTO. */
  public record InboundRequest(String teamId, JsonNode event) {}
}
