package com.smartfirehub.notification.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.channels.kakao.KakaoApiClient;
import com.smartfirehub.notification.repository.UserChannelBinding;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Kakao OAuth 인증 흐름 서비스.
 *
 * <p>사용자가 카카오 로그인을 완료하면 authorization_code를 받아 access/refresh 토큰으로 교환하고 user_channel_binding 테이블에
 * upsert한다. 토큰은 AES-256-GCM으로 암호화하여 저장한다.
 *
 * <p>설정값은 환경변수로 주입받는다 (application.yml notification.kakao.* 블록).
 */
@Service
public class KakaoOAuthService {

  private final KakaoApiClient kakaoApiClient;
  private final UserChannelBindingRepository bindingRepo;
  private final EncryptionService encryption;

  /** 카카오 앱 REST API 키 */
  @Value("${notification.kakao.client_id:}")
  private String clientId;

  /** 카카오 앱 Client Secret */
  @Value("${notification.kakao.client_secret:}")
  private String clientSecret;

  /** 카카오 개발자 콘솔에 등록된 redirect_uri */
  @Value("${notification.kakao.redirect_uri:}")
  private String redirectUri;

  public KakaoOAuthService(
      KakaoApiClient kakaoApiClient,
      UserChannelBindingRepository bindingRepo,
      EncryptionService encryption) {
    this.kakaoApiClient = kakaoApiClient;
    this.bindingRepo = bindingRepo;
    this.encryption = encryption;
  }

  /**
   * 카카오 OAuth 인증 URL 생성.
   *
   * <p>프론트엔드가 새 창으로 열어 사용자 카카오 로그인을 유도한다. scope=talk_message 권한을 요청한다 (나에게 보내기에 필요).
   *
   * @param state CSRF 방어용 state (OAuthStateService.issue()로 발급)
   * @return 카카오 로그인 페이지 URL
   */
  public String authorizeUrl(String state) {
    return "https://kauth.kakao.com/oauth/authorize?response_type=code"
        + "&client_id="
        + clientId
        + "&redirect_uri="
        + redirectUri
        + "&scope=talk_message"
        + "&state="
        + state;
  }

  /**
   * OAuth 콜백의 code를 토큰으로 교환하고 binding을 저장.
   *
   * <p>access_token, refresh_token 모두 AES-256-GCM으로 암호화하여 DB에 저장한다. 이미 연동된 경우 ON CONFLICT upsert로
   * 토큰을 갱신한다.
   *
   * @param userId 인증을 완료한 사용자 ID
   * @param code 카카오 OAuth 콜백의 authorization_code
   */
  public void completeAuthorization(long userId, String code) {
    JsonNode resp = kakaoApiClient.exchangeCode(code, clientId, clientSecret, redirectUri);

    String accessToken = resp.path("access_token").asText();
    String refreshToken = resp.path("refresh_token").asText();
    long expiresInSeconds = resp.path("expires_in").asLong();

    Instant now = Instant.now();
    bindingRepo.upsert(
        new UserChannelBinding(
            null,
            userId,
            ChannelType.KAKAO,
            null, // workspace_id: 카카오는 워크스페이스 개념 없음
            null, // external_user_id: 필요 시 /v2/user/me로 조회 가능
            null, // display_address: 동일
            encryption.encrypt(accessToken),
            encryption.encrypt(refreshToken),
            now.plusSeconds(expiresInSeconds),
            "ACTIVE",
            null, // last_verified_at
            now,
            now));
  }
}
