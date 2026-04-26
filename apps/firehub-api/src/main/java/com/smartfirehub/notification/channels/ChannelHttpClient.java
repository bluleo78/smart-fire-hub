package com.smartfirehub.notification.channels;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * firehub-channel 서비스에 메시지 발송을 위임하는 HTTP 클라이언트. 실제 외부 API(Slack/Kakao/Email) 호출은 firehub-channel이
 * 담당한다.
 */
@Component
public class ChannelHttpClient {

  private static final Logger log = LoggerFactory.getLogger(ChannelHttpClient.class);

  private final WebClient webClient;
  private final String channelInternalToken;

  public ChannelHttpClient(
      @Value("${channel.service.url:http://firehub-channel:3002}") String channelServiceUrl,
      @Value("${channel.internal-token:}") String channelInternalToken) {
    this.webClient = WebClient.builder().baseUrl(channelServiceUrl).build();
    this.channelInternalToken = channelInternalToken;
  }

  /**
   * firehub-channel POST /send 호출.
   *
   * @throws ChannelHttpException 발송 실패 시 (401→auth_error, 5xx→upstream_error)
   */
  public void send(String channel, Map<String, Object> recipient, Map<String, Object> message) {
    send(channel, recipient, message, null);
  }

  public void send(
      String channel, Map<String, Object> recipient, Map<String, Object> message, String threadTs) {
    var body = new LinkedHashMap<String, Object>();
    body.put("channel", channel);
    body.put("recipient", recipient);
    body.put("message", message);
    if (threadTs != null) body.put("threadTs", threadTs);

    webClient
        .post()
        .uri("/send")
        .header("Authorization", "Internal " + channelInternalToken)
        .header("Content-Type", "application/json")
        .bodyValue(body)
        .retrieve()
        .onStatus(
            status -> status.value() == 401,
            res ->
                res.bodyToMono(String.class).map(b -> new ChannelHttpException("auth_error", 401)))
        .onStatus(
            status -> status.is5xxServerError(),
            res ->
                res.bodyToMono(String.class)
                    .map(b -> new ChannelHttpException("upstream_error", res.statusCode().value())))
        .toBodilessEntity()
        .block();

    log.debug("channel send 완료: channel={}", channel);
  }
}
