package com.smartfirehub.notification.auth.controller;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.auth.KakaoOAuthService;
import com.smartfirehub.notification.auth.OAuthStateService;
import com.smartfirehub.notification.repository.OAuthStateRepository.ConsumedState;
import java.util.Map;
import java.util.Optional;
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
   * <p><b>테넌트 복원 지점이다.</b> permitAll 이라 컨텍스트가 없는데 {@code user_channel_binding} 은 쓰기 대상(RLS +
   * tenant_id NOT NULL)이다. state 가 실어 온 테넌트(V106 [R7])를 되찾아 {@link TenantContext#runScopedGet} 으로
   * 컨텍스트를 세운다. 되찾지 못하면 <b>fail-closed</b> — {@code consume} 이 empty 를 돌려주고 아래 400 분기로 빠진다.
   *
   * @param code Kakao에서 전달한 authorization_code (동의를 취소하면 없다)
   * @param state CSRF 방어용 state (OAuthStateService.issue로 발급)
   * @param error 제공자가 돌려보낸 오류 코드 (예: 동의 취소 access_denied) — 있으면 code 대신 온다
   * @return 200 HTML (결과 알림·창 닫기 스크립트 포함) 또는 400 (유효하지 않은 state, code·state 누락)
   */
  @GetMapping("/callback")
  public ResponseEntity<String> callback(
      @RequestParam(value = "code", required = false) String code,
      @RequestParam(value = "state", required = false) String state,
      @RequestParam(value = "error", required = false) String error) {
    // 동의 화면에서 「취소」하면 제공자는 code 없이 error(access_denied)와 state 만 붙여 돌려보낸다(WD-405).
    // 예전에는 code 가 필수라 500 JSON 이 팝업에 남았다. 안내 페이지로 바꾸고, 쓰기는 하지 않는다.
    if (error != null) {
      return OAuthCallbackPage.providerError(oAuthStateService, ChannelType.KAKAO, state, error);
    }
    if (code == null || state == null) {
      return OAuthCallbackPage.badRequest(
          ChannelType.KAKAO, "연결 요청이 올바르지 않습니다. 원래 화면에서 다시 시도해 주세요.");
    }
    Optional<ConsumedState> consumed = oAuthStateService.consume(state);
    if (consumed.isEmpty()) {
      return OAuthCallbackPage.badRequest(
          ChannelType.KAKAO, "연결 요청이 만료됐거나 올바르지 않습니다. 원래 화면에서 다시 시도해 주세요.");
    }
    if (consumed.get().channelType() != ChannelType.KAKAO) {
      return OAuthCallbackPage.badRequest(
          ChannelType.KAKAO, "다른 채널의 연결 요청입니다. 원래 화면에서 다시 시도해 주세요.");
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

          // 연동 완료 — 원래 화면에 알리고 팝업 창 닫기
          return OAuthCallbackPage.connected(ChannelType.KAKAO, "카카오 연동 완료. 창을 닫아주세요.");
        });
  }
}
