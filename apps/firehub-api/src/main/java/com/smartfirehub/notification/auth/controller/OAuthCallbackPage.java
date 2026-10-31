package com.smartfirehub.notification.auth.controller;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.auth.OAuthStateService;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.HtmlUtils;

/**
 * OAuth 콜백 팝업에 돌려줄 HTML 페이지 (WD-405).
 *
 * <p>콜백은 제공자(슬랙·카카오)가 팝업을 우리 주소로 되돌려 보낸 곳이라, JSON 이 아니라 사람이 읽을 페이지를 보여 주고 창을 닫아야 한다.
 * 예전에는 동의를 「취소」하면(code 없이 {@code error=access_denied}) 필수 파라미터 누락으로 500 JSON 원문이 팝업에 그대로 남았다.
 *
 * <p>결과는 {@code BroadcastChannel("firehub-oauth")} 로 원래 화면(OAuthConnectButton)에 알린다. 팝업은 opener 를 끊고
 * 열리므로({@code window.opener} 없음) postMessage 대신 같은 출처의 BroadcastChannel 을 쓴다 — 콜백 주소({@code
 * /api/v1/oauth/...})는 웹과 같은 출처로 배포된다. 실어 보내는 값은 채널·결과 상수뿐이고, 제공자가 붙인 오류 문자열은 화면 문구에만
 * HTML 이스케이프해 넣는다.
 */
final class OAuthCallbackPage {

  /** 원래 화면이 듣는 BroadcastChannel 이름 — 웹 OAuthConnectButton 의 OAUTH_RESULT_CHANNEL 과 같은 값. */
  static final String BROADCAST_CHANNEL = "firehub-oauth";

  private static final MediaType HTML = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

  private OAuthCallbackPage() {}

  /** 연결 성공 — 원래 화면에 connected 를 알리고 창을 닫는다. */
  static ResponseEntity<String> connected(ChannelType channel, String message) {
    return ResponseEntity.ok().contentType(HTML).body(page(channel, message, "connected"));
  }

  /**
   * 제공자가 {@code error} 로 돌려보낸 경우(code 없음) — 사용자가 동의를 취소(access_denied)했으면 cancelled, 그 밖의 사유면 failed.
   *
   * <p>state 를 소비해 다시 쓰이지 않게 하고, <b>이 채널로 발급한 유효한 state 일 때만</b> 원래 화면에 결과를 알린다. 콜백은 공개
   * 경로라 아무 페이지나 state 없이 {@code ?error=...} 로 열 수 있는데, 그때도 알리면 다른 사이트가 진행 중인 연결을 「취소됨」으로
   * 꾸며 원래 화면의 감시를 끊을 수 있다. 알리지 않을 때도 안내 문구는 보이고 창은 닫힌다.
   *
   * @param error 제공자가 붙인 오류 코드(신뢰하지 않는 입력 — 화면에는 이스케이프해 넣는다)
   */
  static ResponseEntity<String> providerError(
      OAuthStateService stateService, ChannelType channel, String state, String error) {
    boolean ownState =
        state != null
            && stateService.consume(state).map(c -> c.channelType() == channel).orElse(false);
    String name = displayName(channel);
    boolean cancelled = "access_denied".equals(error);
    String message =
        cancelled
            ? name + " 연결을 취소했습니다. 이 창은 닫아도 됩니다."
            : name + " 연결을 완료하지 못했습니다(사유: " + HtmlUtils.htmlEscape(error) + "). 다시 시도해 주세요.";
    String result = ownState ? (cancelled ? "cancelled" : "failed") : null;
    return ResponseEntity.ok().contentType(HTML).body(page(channel, message, result));
  }

  /**
   * code·state 가 없거나 state 가 유효하지 않은 요청 — 400 과 함께 다시 시도하라는 안내 페이지(예전에는 글자만 남았다).
   *
   * <p>누구의 연결 시도인지 확인할 수 없으므로 원래 화면에 알리지 않고, 사용자가 읽도록 창도 닫지 않는다.
   */
  static ResponseEntity<String> badRequest(ChannelType channel, String message) {
    return ResponseEntity.badRequest()
        .contentType(HTML)
        .body(
            "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"><title>"
                + displayName(channel)
                + " 연결</title></head><body><p>"
                + message
                + "</p></body></html>");
  }

  /** 화면 문구용 채널 이름. */
  private static String displayName(ChannelType channel) {
    return switch (channel) {
      case SLACK -> "슬랙";
      case KAKAO -> "카카오";
      default -> channel.name();
    };
  }

  /**
   * 결과 알림·창 닫기 페이지를 만든다. message 는 이미 안전한 문구(상수 또는 이스케이프한 값)여야 한다.
   *
   * <p>result 가 null 이면 알리지 않고 창만 닫는다. BroadcastChannel 이 없는 브라우저에서도 창은 닫히도록 알림을 try 로 감싼다.
   */
  private static String page(ChannelType channel, String message, String result) {
    String notify =
        result == null
            ? ""
            : "try{var c=new BroadcastChannel('"
                + BROADCAST_CHANNEL
                + "');c.postMessage({channel:'"
                + channel.name()
                + "',result:'"
                + result
                + "'});c.close();}catch(e){}";
    return "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"><title>"
        + displayName(channel)
        + " 연결</title></head><body><p>"
        + message
        + "</p><script>"
        + notify
        + "window.close();</script></body></html>";
  }
}
