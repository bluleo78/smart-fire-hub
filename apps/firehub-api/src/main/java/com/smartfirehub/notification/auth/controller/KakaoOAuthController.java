package com.smartfirehub.notification.auth.controller;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.auth.KakaoOAuthService;
import com.smartfirehub.notification.auth.OAuthStateService;
import com.smartfirehub.notification.repository.OAuthStateRepository.ConsumedState;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kakao OAuth 인증 컨트롤러.
 *
 * <p>/auth-url — 인증 필요. state 발급 후 Kakao 인증 URL을 JSON으로 반환. 팝업이 Bearer 헤더를 전달할 수 없으므로 프론트엔드가 이 URL을
 * 받아 팝업으로 직접 연다. /callback — public (state가 CSRF 방어). 인증 완료 후 창 닫기 HTML 반환.
 */
@RestController
@RequestMapping("/api/v1/oauth/kakao")
public class KakaoOAuthController {

  private final KakaoOAuthService kakaoOAuthService;
  private final OAuthStateService oAuthStateService;

  public KakaoOAuthController(
      KakaoOAuthService kakaoOAuthService, OAuthStateService oAuthStateService) {
    this.kakaoOAuthService = kakaoOAuthService;
    this.oAuthStateService = oAuthStateService;
  }

  /**
   * Kakao OAuth 인증 URL 반환 — 인증 필요.
   *
   * <p>팝업은 Bearer 헤더를 전달할 수 없으므로, 프론트엔드가 이 엔드포인트를 먼저 호출하여 실제 Kakao 인증 URL을 받은 뒤 해당 URL을 팝업으로 직접 연다.
   * client_id가 미설정된 경우 빈 OAuth URL이 생성되어 반드시 실패하므로, 400을 반환하여 사전에 차단한다.
   *
   * @param authentication Spring Security 인증 객체 (principal = userId Long)
   * @return {"url": "https://kauth.kakao.com/oauth/authorize?..."} 또는 400 (자격증명 미설정)
   */
  @GetMapping("/auth-url")
  public ResponseEntity<Map<String, String>> authUrl(Authentication authentication) {
    // OAuth 자격증명 미설정 시 빈 client_id로 OAuth 흐름이 시작되는 것을 사전 차단
    if (!kakaoOAuthService.isConfigured()) {
      return ResponseEntity.badRequest()
          .body(Map.of("message", "카카오 OAuth 설정이 완료되지 않았습니다. 관리자에게 문의하세요."));
    }
    Long userId = (Long) authentication.getPrincipal();
    String state = oAuthStateService.issue(userId, ChannelType.KAKAO);
    String authorizeUrl = kakaoOAuthService.authorizeUrl(state);
    return ResponseEntity.ok(Map.of("url", authorizeUrl));
  }

  /**
   * Kakao OAuth 콜백 처리 — public.
   *
   * <p>state 소비로 CSRF 검증 후 authorization_code를 토큰으로 교환하여 저장한다. 완료 후 창을 닫는 HTML 페이지를 반환한다.
   *
   * <p><b>테넌트 복원 지점이다.</b> permitAll 이라 컨텍스트가 없는데 {@code user_channel_binding}
   * 은 쓰기 대상(RLS + tenant_id NOT NULL)이다. state 가 실어 온 테넌트(V106 [R7])를 되찾아
   * {@link TenantContext#runScopedGet} 으로 컨텍스트를 세운다. 되찾지 못하면 <b>fail-closed</b>
   * — {@code consume} 이 empty 를 돌려주고 아래 400 분기로 빠진다.
   *
   * @param code Kakao에서 전달한 authorization_code
   * @param state CSRF 방어용 state (OAuthStateService.issue로 발급)
   * @return 200 HTML (창 닫기 스크립트 포함) 또는 400 (유효하지 않은 state)
   */
  @GetMapping("/callback")
  public ResponseEntity<String> callback(
      @RequestParam("code") String code, @RequestParam("state") String state) {
    Optional<ConsumedState> consumed = oAuthStateService.consume(state);
    if (consumed.isEmpty()) {
      return ResponseEntity.badRequest().body("유효하지 않거나 만료된 state입니다.");
    }
    if (consumed.get().channelType() != ChannelType.KAKAO) {
      return ResponseEntity.badRequest().body("잘못된 채널 타입의 state입니다.");
    }

    long userId = consumed.get().userId();

    // 인증 필터를 거치지 않는 경로라 테넌트 컨텍스트가 없다. state 가 실어 온 테넌트(V106 [R7])로
    // 컨텍스트를 세운 뒤 user_channel_binding upsert 를 그 안에서 수행한다. 컨트롤러 경계에서
    // 세우는 이유는 TenantAwareTransactionManager.doBegin 이 트랜잭션 시작 시점에 GUC 를 읽기
    // 때문이다 — @Transactional 리포지토리 안쪽에서 세우면 이미 늦다.
    //
    // Kakao 에는 이 경로가 유일한 해법이다. Slack 은 워크스페이스(team_id)라는 외부 식별자가 있어
    // SECURITY DEFINER 해석기로도 테넌트를 되찾을 수 있지만, Kakao 에는 워크스페이스 개념이 없어
    // 대안 식별자가 아예 없다. 이것이 oauth_state 를 이 밴드에 넣은 결정적 근거다.
    return TenantContext.runScopedGet(
        consumed.get().tenantId(),
        () -> {
          kakaoOAuthService.completeAuthorization(userId, code);

          return ResponseEntity.ok()
              .contentType(MediaType.TEXT_HTML)
              .body(
                  "<html><body>카카오 연동 완료."
                      + " 창을 닫아주세요."
                      + "<script>window.close();</script></body></html>");
        });
  }
}
