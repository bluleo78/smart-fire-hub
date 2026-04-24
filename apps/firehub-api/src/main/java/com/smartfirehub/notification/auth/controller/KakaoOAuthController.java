package com.smartfirehub.notification.auth.controller;

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
 * <p>/auth-url — 인증 필요. state 발급 후 Kakao 인증 URL을 JSON으로 반환. 팝업이 Bearer 헤더를 전달할 수
 * 없으므로 프론트엔드가 이 URL을 받아 팝업으로 직접 연다.
 * /callback — public (state가 CSRF 방어). 인증 완료 후 창 닫기 HTML 반환.
 */
@RestController
@RequestMapping("/api/v1/oauth/kakao")
public class KakaoOAuthController {

    private final KakaoOAuthService kakaoOAuthService;
    private final OAuthStateService oAuthStateService;

    public KakaoOAuthController(
            KakaoOAuthService kakaoOAuthService,
            OAuthStateService oAuthStateService) {
        this.kakaoOAuthService = kakaoOAuthService;
        this.oAuthStateService = oAuthStateService;
    }

    /**
     * Kakao OAuth 인증 URL 반환 — 인증 필요.
     *
     * <p>팝업은 Bearer 헤더를 전달할 수 없으므로, 프론트엔드가 이 엔드포인트를 먼저
     * 호출하여 실제 Kakao 인증 URL을 받은 뒤 해당 URL을 팝업으로 직접 연다.
     *
     * @param authentication Spring Security 인증 객체 (principal = userId Long)
     * @return {"url": "https://kauth.kakao.com/oauth/authorize?..."}
     */
    @GetMapping("/auth-url")
    public ResponseEntity<Map<String, String>> authUrl(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        String state = oAuthStateService.issue(userId, ChannelType.KAKAO);
        String authorizeUrl = kakaoOAuthService.authorizeUrl(state);
        return ResponseEntity.ok(Map.of("url", authorizeUrl));
    }

    /**
     * Kakao OAuth 콜백 처리 — public.
     *
     * <p>state 소비로 CSRF 검증 후 authorization_code를 토큰으로 교환하여 저장한다.
     * 완료 후 창을 닫는 HTML 페이지를 반환한다.
     *
     * @param code  Kakao에서 전달한 authorization_code
     * @param state CSRF 방어용 state (OAuthStateService.issue로 발급)
     * @return 200 HTML (창 닫기 스크립트 포함) 또는 400 (유효하지 않은 state)
     */
    @GetMapping("/callback")
    public ResponseEntity<String> callback(
            @RequestParam("code") String code,
            @RequestParam("state") String state) {
        Optional<ConsumedState> consumed = oAuthStateService.consume(state);
        if (consumed.isEmpty()) {
            return ResponseEntity.badRequest().body("유효하지 않거나 만료된 state입니다.");
        }
        if (consumed.get().channelType() != ChannelType.KAKAO) {
            return ResponseEntity.badRequest().body("잘못된 채널 타입의 state입니다.");
        }

        long userId = consumed.get().userId();
        kakaoOAuthService.completeAuthorization(userId, code);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body("<html><body>카카오 연동 완료."
                        + " 창을 닫아주세요."
                        + "<script>window.close();</script></body></html>");
    }
}
