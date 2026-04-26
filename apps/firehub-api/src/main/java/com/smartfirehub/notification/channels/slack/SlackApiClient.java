package com.smartfirehub.notification.channels.slack;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Slack REST API WebClient 래퍼.
 *
 * <p>baseUrl: https://slack.com/api
 *
 * <p>Slack API 응답은 항상 HTTP 200으로 반환되며, 성공 여부는 응답 JSON의 {@code ok} 필드로 판단한다. 각 메서드는 JsonNode를 그대로
 * 반환하며, 호출 측에서 ok=false 체크를 담당한다.
 */
@Component
public class SlackApiClient {

  private final WebClient webClient;
  private final ObjectMapper objectMapper;

  public SlackApiClient() {
    this.webClient = WebClient.builder().baseUrl("https://slack.com/api").build();
    this.objectMapper = new ObjectMapper();
  }

  /** 테스트용 생성자 — WireMock 등 커스텀 baseUrl 주입 가능. */
  SlackApiClient(WebClient webClient) {
    this.webClient = webClient;
    this.objectMapper = new ObjectMapper();
  }

  /**
   * OAuth 2.0 설치 코드를 봇 토큰으로 교환.
   *
   * <p>POST /api/oauth.v2.access (application/x-www-form-urlencoded). 응답: {@code {ok, access_token,
   * bot_user_id, team: {id, name}, ...}}
   *
   * @param code OAuth 콜백에서 받은 authorization_code
   * @param clientId Slack 앱 Client ID
   * @param clientSecret Slack 앱 Client Secret
   * @param redirectUri 등록된 redirect_uri
   * @return Slack API 응답 JSON (ok=true/false 포함)
   */
  public JsonNode oauthV2Access(
      String code, String clientId, String clientSecret, String redirectUri) {
    var form = new LinkedMultiValueMap<String, String>();
    form.add("code", code);
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);
    form.add("redirect_uri", redirectUri);

    return webClient
        .post()
        .uri("/oauth.v2.access")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .bodyValue(form)
        .retrieve()
        .bodyToMono(JsonNode.class)
        .block();
  }

  /**
   * Slack 채널(또는 DM 채널)에 메시지 전송.
   *
   * <p>POST /api/chat.postMessage (application/json). Authorization: Bearer {botToken}.
   *
   * @param botToken 워크스페이스 봇 토큰 (복호화 원문)
   * @param channel 전송 대상 채널 ID (DM 포함)
   * @param blocksJson Block Kit JSON 문자열 (null이면 전송 안 함)
   * @param fallbackText 푸시 알림·접근성용 대체 텍스트
   * @return Slack API 응답 JSON
   */
  public JsonNode chatPostMessage(
      String botToken, String channel, String blocksJson, String fallbackText) {
    // blocks가 없으면 단순 text 메시지로 전송
    var bodyBuilder = new StringBuilder("{\"channel\":\"").append(channel).append("\"");
    bodyBuilder.append(",\"text\":").append(jsonStringLiteral(fallbackText));
    if (blocksJson != null && !blocksJson.isBlank()) {
      bodyBuilder.append(",\"blocks\":").append(blocksJson);
    }
    bodyBuilder.append("}");

    return webClient
        .post()
        .uri("/chat.postMessage")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + botToken)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(bodyBuilder.toString())
        .retrieve()
        .bodyToMono(JsonNode.class)
        .block();
  }

  /**
   * Slack 사용자 프로필 조회.
   *
   * <p>GET /api/users.info?user={userId}. Authorization: Bearer {botToken}. displayName 추출 등에 사용.
   *
   * @param botToken 워크스페이스 봇 토큰 (복호화 원문)
   * @param userId 조회할 Slack 사용자 ID (예: U0123456)
   * @return Slack API 응답 JSON (user.profile.display_name 등 포함)
   */
  public JsonNode usersInfo(String botToken, String userId) {
    return webClient
        .get()
        .uri(uriBuilder -> uriBuilder.path("/users.info").queryParam("user", userId).build())
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + botToken)
        .retrieve()
        .bodyToMono(JsonNode.class)
        .block();
  }

  /**
   * 봇과 사용자 간 DM 채널 ID 획득.
   *
   * <p>POST /api/conversations.open (application/json). DM 채널이 없으면 새로 개설한다. 반환된 channel.id를
   * chatPostMessage의 channel 파라미터로 사용한다.
   *
   * @param botToken 워크스페이스 봇 토큰 (복호화 원문)
   * @param userId DM을 열 Slack 사용자 ID
   * @return Slack API 응답 JSON (channel.id 포함)
   */
  public JsonNode openConversation(String botToken, String userId) {
    String body = "{\"users\":\"" + userId + "\"}";

    return webClient
        .post()
        .uri("/conversations.open")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + botToken)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .retrieve()
        .bodyToMono(JsonNode.class)
        .block();
  }

  /**
   * reactions.add — 메시지에 리액션 추가.
   *
   * <p>inbound 처리 수신 확인용으로 :eyes: 등을 달 때 사용한다.
   *
   * @param botToken 워크스페이스 봇 토큰
   * @param channel 대상 채널 ID
   * @param timestamp 리액션을 달 메시지의 ts 값
   * @param name 이모지 이름 (콜론 없이, 예: eyes)
   * @return Slack API 응답 JSON
   */
  public JsonNode reactionsAdd(String botToken, String channel, String timestamp, String name) {
    var body = new HashMap<String, String>();
    body.put("channel", channel);
    body.put("timestamp", timestamp);
    body.put("name", name);
    return webClient
        .post()
        .uri("/reactions.add")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + botToken)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .retrieve()
        .bodyToMono(JsonNode.class)
        .block();
  }

  /**
   * chat.postEphemeral — 특정 사용자에게만 보이는 임시 메시지.
   *
   * <p>Slack 워크스페이스 매핑이 없는 사용자에게 안내 메시지를 보낼 때 사용한다.
   *
   * @param botToken 워크스페이스 봇 토큰
   * @param channel 채널 ID
   * @param user 메시지를 볼 Slack 사용자 ID
   * @param text 메시지 텍스트
   * @return Slack API 응답 JSON
   */
  public JsonNode postEphemeral(String botToken, String channel, String user, String text) {
    var body = new HashMap<String, String>();
    body.put("channel", channel);
    body.put("user", user);
    body.put("text", text);
    return webClient
        .post()
        .uri("/chat.postEphemeral")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + botToken)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .retrieve()
        .bodyToMono(JsonNode.class)
        .block();
  }

  /**
   * chat.postMessage with thread_ts — 기존 스레드에 AI 응답을 회신.
   *
   * <p>inbound 메시지 처리 후 AI 응답을 동일 스레드에 달 때 사용한다. blocksJson이 null이 아닌 경우 Block Kit으로 렌더링하고,
   * fallbackText는 항상 포함한다.
   *
   * @param botToken 워크스페이스 봇 토큰
   * @param channel 대상 채널 ID
   * @param threadTs 원본 메시지 ts (스레드 루트)
   * @param blocksJson Block Kit JSON 문자열 (null 가능)
   * @param fallbackText 푸시 알림·접근성용 대체 텍스트
   * @return Slack API 응답 JSON
   */
  public JsonNode chatPostMessageInThread(
      String botToken, String channel, String threadTs, String blocksJson, String fallbackText) {
    var body = new HashMap<String, Object>();
    body.put("channel", channel);
    body.put("thread_ts", threadTs);
    body.put("text", fallbackText != null ? fallbackText : "");
    if (blocksJson != null) {
      try {
        body.put("blocks", objectMapper.readTree(blocksJson));
      } catch (JsonProcessingException e) {
        // blocks 파싱 실패 시 text 단독 전송으로 폴백
        throw new IllegalArgumentException("blocksJson 파싱 실패: " + e.getMessage(), e);
      }
    }
    return webClient
        .post()
        .uri("/chat.postMessage")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + botToken)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .retrieve()
        .bodyToMono(JsonNode.class)
        .block();
  }

  /** 문자열을 JSON string literal로 변환. ObjectMapper를 주입하지 않으므로 간단히 이스케이프 처리. */
  private static String jsonStringLiteral(String s) {
    if (s == null) {
      return "\"\"";
    }
    return "\""
        + s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        + "\"";
  }
}
