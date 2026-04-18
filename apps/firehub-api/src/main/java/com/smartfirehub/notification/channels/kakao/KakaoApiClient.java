package com.smartfirehub.notification.channels.kakao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Kakao REST API WebClient 래퍼.
 *
 * <p>인증 서버(kauth.kakao.com)와 API 서버(kapi.kakao.com) 두 개의 WebClient를 관리한다. - authorization_code →
 * token 교환 - refresh_token → access_token 갱신 - 나에게 보내기 (memo/default/send)
 */
@Component
public class KakaoApiClient {

  private final WebClient authClient; // kauth.kakao.com — 토큰 발급/갱신
  private final WebClient apiClient; // kapi.kakao.com  — 나에게 보내기 등 API 호출
  private final ObjectMapper objectMapper;

  public KakaoApiClient(ObjectMapper objectMapper) {
    this.authClient = WebClient.builder().baseUrl("https://kauth.kakao.com").build();
    this.apiClient = WebClient.builder().baseUrl("https://kapi.kakao.com").build();
    this.objectMapper = objectMapper;
  }

  /**
   * authorization_code → token 교환.
   *
   * @param code OAuth 콜백에서 받은 authorization_code
   * @param clientId 카카오 앱 REST API 키
   * @param clientSecret 카카오 앱 Client Secret
   * @param redirectUri 등록된 redirect_uri
   * @return access_token, refresh_token, expires_in 포함한 JSON 응답
   */
  public JsonNode exchangeCode(
      String code, String clientId, String clientSecret, String redirectUri) {
    var form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", "authorization_code");
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);
    form.add("redirect_uri", redirectUri);
    form.add("code", code);
    return authClient
        .post()
        .uri("/oauth/token")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .bodyValue(form)
        .retrieve()
        .bodyToMono(JsonNode.class)
        .block();
  }

  /**
   * refresh_token으로 access_token 갱신.
   *
   * <p>access_token 만료 시 호출하여 새 토큰을 발급받는다. 카카오는 refresh_token도 갱신 가능하므로 응답에 refresh_token이 포함될 수
   * 있다.
   *
   * @param refreshToken 저장된 refresh_token (복호화된 원문)
   * @param clientId 카카오 앱 REST API 키
   * @param clientSecret 카카오 앱 Client Secret
   * @return 새 access_token 등 포함한 JSON 응답
   */
  public JsonNode refresh(String refreshToken, String clientId, String clientSecret) {
    var form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", "refresh_token");
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);
    form.add("refresh_token", refreshToken);
    return authClient
        .post()
        .uri("/oauth/token")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .bodyValue(form)
        .retrieve()
        .bodyToMono(JsonNode.class)
        .block();
  }

  /**
   * 나에게 보내기 — text 템플릿으로 카카오톡 메시지 전송.
   *
   * <p>v2/api/talk/memo/default/send 엔드포인트를 사용한다. accessToken은 복호화된 원문이어야 한다.
   *
   * @param accessToken 유효한 카카오 access_token (복호화 원문)
   * @param text 전송할 텍스트 내용 (최대 1000자)
   * @param webUrl 링크로 첨부할 웹 URL
   */
  public void sendMemoText(String accessToken, String text, String webUrl) {
    // text 필드는 JSON 직렬화, webUrl은 URL 이스케이프만 적용
    String templateJson =
        "{\"object_type\":\"text\",\"text\":"
            + objectMapper.valueToTree(text)
            + ",\"link\":{\"web_url\":\""
            + escapeQuotes(webUrl)
            + "\"}}";
    var form = new LinkedMultiValueMap<String, String>();
    form.add("template_object", templateJson);
    apiClient
        .post()
        .uri("/v2/api/talk/memo/default/send")
        .header("Authorization", "Bearer " + accessToken)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .bodyValue(form)
        .retrieve()
        .bodyToMono(Void.class)
        .block();
  }

  /** JSON 문자열 내에 포함되는 URL의 큰따옴표 이스케이프. */
  private static String escapeQuotes(String s) {
    return s == null ? "" : s.replace("\"", "\\\"");
  }
}
