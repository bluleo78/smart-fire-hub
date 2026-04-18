package com.smartfirehub.notification.auth.controller;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.auth.OAuthStateService;
import com.smartfirehub.notification.auth.SlackOAuthService;
import com.smartfirehub.notification.repository.OAuthStateRepository.ConsumedState;
import java.net.URI;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slack Workspace OAuth 설치 및 사용자 매핑 컨트롤러.
 *
 * <p>/start — 관리자 전용. state 발급 후 Slack 인증 페이지로 리다이렉트.
 * /callback — public (state가 CSRF 방어). 설치 완료 후 닫기 HTML 반환.
 * /link-user — 인증 필요. 사용자가 자신의 Slack user ID를 연동.
 */
@RestController
@RequestMapping("/api/v1/oauth/slack")
public class SlackOAuthController {

    private final SlackOAuthService slackOAuthService;
    private final OAuthStateService oAuthStateService;

    public SlackOAuthController(
            SlackOAuthService slackOAuthService,
            OAuthStateService oAuthStateService) {
        this.slackOAuthService = slackOAuthService;
        this.oAuthStateService = oAuthStateService;
    }

    /**
     * Slack 앱 설치 시작 — 관리자 전용.
     *
     * <p>CSRF 방어용 state를 발급하고 Slack OAuth 인증 페이지로 302 리다이렉트한다.
     * state에 userId를 포함하여 콜백에서 설치 주체를 추적한다.
     *
     * @param authentication Spring Security 인증 객체 (principal = userId Long)
     * @return 302 Redirect to Slack authorize URL
     */
    @GetMapping("/start")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> start(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        String state = oAuthStateService.issue(userId, ChannelType.SLACK);
        String authorizeUrl = slackOAuthService.authorizeUrl(state);
        return ResponseEntity.status(302)
                .location(URI.create(authorizeUrl))
                .build();
    }

    /**
     * Slack OAuth 콜백 처리 — public.
     *
     * <p>state 소비로 CSRF 검증 후 oauth.v2.access를 호출하여 봇 토큰을 저장한다.
     * 완료 후 창을 닫는 HTML 페이지를 반환한다.
     *
     * @param code  Slack에서 전달한 authorization_code
     * @param state CSRF 방어용 state (OAuthStateService.issue로 발급)
     * @return 200 HTML (창 닫기 스크립트 포함) 또는 400 (유효하지 않은 state)
     */
    @GetMapping("/callback")
    public ResponseEntity<String> callback(
            @RequestParam("code") String code,
            @RequestParam("state") String state) {
        // state 소비 — 단일 사용(single-use), 만료 검사 포함
        Optional<ConsumedState> consumed = oAuthStateService.consume(state);
        if (consumed.isEmpty()) {
            return ResponseEntity.badRequest().body("유효하지 않거나 만료된 state입니다.");
        }
        if (consumed.get().channelType() != ChannelType.SLACK) {
            return ResponseEntity.badRequest().body("잘못된 채널 타입의 state입니다.");
        }

        long installedByUserId = consumed.get().userId();
        slackOAuthService.completeAuthorization(code, installedByUserId);

        // 설치 완료 후 팝업 창 닫기
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body("<html><body>Slack 워크스페이스 설치 완료."
                        + " 창을 닫아주세요."
                        + "<script>window.close();</script></body></html>");
    }

    /**
     * 사용자 Slack user ID 수동 연동 — 인증 필요.
     *
     * <p>사용자가 입력한 slackUserId로 DM ping을 전송하고 user_channel_binding에 저장한다.
     * Slack 봇 토큰은 지정된 워크스페이스에서 조회한다.
     *
     * @param request      workspaceId + slackUserId
     * @param authentication Spring Security 인증 객체
     * @return 204 No Content
     */
    @PostMapping("/link-user")
    public ResponseEntity<Void> linkUser(
            @RequestBody LinkUserRequest request,
            Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        slackOAuthService.linkUser(userId, request.workspaceId(), request.slackUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * 사용자 Slack 연동 요청 바디.
     *
     * @param workspaceId 연결할 slack_workspace.id (DB PK)
     * @param slackUserId 사용자가 입력한 Slack user ID (예: U0123456)
     */
    public record LinkUserRequest(long workspaceId, String slackUserId) {}
}
