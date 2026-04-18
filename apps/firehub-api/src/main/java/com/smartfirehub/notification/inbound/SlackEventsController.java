package com.smartfirehub.notification.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slack Events API 요청 처리 엔드포인트.
 *
 * <ul>
 *   <li>url_verification: challenge 반환 (Slack 앱 URL 등록 시 필요, 서명 검증 없음)
 *   <li>event_callback: 서명 검증 → @Async dispatch → 즉시 200 ack (3초 응답 의무)
 * </ul>
 *
 * <p>이 엔드포인트는 SecurityConfig에서 public으로 설정.
 * Slack 서명이 JWT 인증을 대체한다.
 */
@RestController
@RequestMapping("/api/v1/channels/slack")
public class SlackEventsController {

    private static final Logger log = LoggerFactory.getLogger(SlackEventsController.class);

    private final SlackSignatureVerifier verifier;
    private final SlackInboundService inboundService;
    private final ObjectMapper objectMapper;

    public SlackEventsController(SlackSignatureVerifier verifier,
                                 SlackInboundService inboundService,
                                 ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.inboundService = inboundService;
        this.objectMapper = objectMapper;
    }

    /**
     * Slack Events API 수신 핸들러.
     *
     * <p>Slack은 이벤트 발생 시 이 엔드포인트로 POST 요청을 보낸다.
     * 3초 이내에 응답하지 않으면 Slack이 재시도하므로 처리는 항상 비동기로 위임한다.
     *
     * @param signature X-Slack-Signature 헤더 (형식: "v0=<hex>")
     * @param timestamp X-Slack-Request-Timestamp 헤더 (유닉스 초)
     * @param rawBody   요청 원본 본문 (서명 검증에 파싱 전 원본 필요)
     */
    @PostMapping("/events")
    public ResponseEntity<?> events(
            @RequestHeader("X-Slack-Signature") String signature,
            @RequestHeader("X-Slack-Request-Timestamp") String timestamp,
            @RequestBody String rawBody) throws IOException {

        JsonNode node = objectMapper.readTree(rawBody);
        String type = node.path("type").asText();

        // 1. url_verification: Slack 앱 URL 등록 시 Slack이 랜덤 challenge를 전송한다.
        //    서명 검증 없이 challenge를 그대로 반환해야 설정이 완료된다 (Slack 공식 문서).
        if ("url_verification".equals(type)) {
            return ResponseEntity.ok(Map.of("challenge", node.path("challenge").asText()));
        }

        // 2. event_callback: 서명 검증 필수 (replay 공격 및 위변조 방어)
        String teamId = node.path("team_id").asText();
        if (!verifier.verify(teamId, timestamp, rawBody, signature)) {
            log.warn("slack events rejected — signature verification failed, team={}", teamId);
            return ResponseEntity.status(401).build();
        }

        // 3. event 내용 추출 및 필터링
        if ("event_callback".equals(type)) {
            JsonNode event = node.path("event");
            String eventType = event.path("type").asText();
            // message.im 또는 app_mention만 처리.
            // subtype 있는 메시지(bot_message, message_changed 등)는 무시하여
            // bot 메시지 루프 방지 및 불필요한 처리 제거.
            if (("message".equals(eventType) || "app_mention".equals(eventType))
                    && !event.has("subtype")) {
                // @Async dispatch — 즉시 반환 후 별도 스레드에서 처리 (3초 응답 의무 준수)
                inboundService.dispatch(teamId, event);
            }
        }

        // Slack 3초 응답 의무 — 항상 200 OK 즉시 반환
        return ResponseEntity.ok().build();
    }
}
