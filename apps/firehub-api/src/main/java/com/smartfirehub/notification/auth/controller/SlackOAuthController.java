package com.smartfirehub.notification.auth.controller;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.auth.OAuthStateService;
import com.smartfirehub.notification.auth.SlackOAuthService;
import com.smartfirehub.notification.repository.OAuthStateRepository.ConsumedState;
import java.util.Map;
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
 * <p>/start — 관리자 전용. state 발급 후 Slack 인증 페이지로 리다이렉트. /callback — public (state가 CSRF 방어). 설치 완료 후
 * 닫기 HTML 반환. /link-user — 인증 필요. 사용자가 자신의 Slack user ID를 연동.
 */
@RestController
@RequestMapping("/api/v1/oauth/slack")
public class SlackOAuthController {

  private final SlackOAuthService slackOAuthService;
  private final OAuthStateService oAuthStateService;

  public SlackOAuthController(
      SlackOAuthService slackOAuthService, OAuthStateService oAuthStateService) {
    this.slackOAuthService = slackOAuthService;
    this.oAuthStateService = oAuthStateService;
  }

  /**
   * Slack OAuth 인증 URL 반환 — 관리자 전용.
   *
   * <p>팝업은 Bearer 헤더를 전달할 수 없으므로, 프론트엔드가 이 엔드포인트를 먼저 호출하여 실제 Slack 인증 URL을 받은 뒤 해당 URL을 팝업으로 직접 연다.
   * client_id가 미설정된 경우 빈 OAuth URL이 생성되어 반드시 실패하므로, 400을 반환하여 사전에 차단한다.
   *
   * @param authentication Spring Security 인증 객체 (principal = userId Long)
   * @return {"url": "https://slack.com/oauth/v2/authorize?..."} 또는 400 (자격증명 미설정)
   */
  @GetMapping("/auth-url")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Map<String, String>> authUrl(Authentication authentication) {
    // OAuth 자격증명 미설정 시 빈 client_id로 OAuth 흐름이 시작되는 것을 사전 차단
    if (!slackOAuthService.isConfigured()) {
      return ResponseEntity.badRequest()
          .body(Map.of("message", "Slack OAuth 설정이 완료되지 않았습니다. 관리자에게 문의하세요."));
    }
    Long userId = (Long) authentication.getPrincipal();
    String state = oAuthStateService.issue(userId, ChannelType.SLACK);
    String authorizeUrl = slackOAuthService.authorizeUrl(state);
    return ResponseEntity.ok(Map.of("url", authorizeUrl));
  }

  /**
   * Slack OAuth 콜백 처리 — public.
   *
   * <p>state 소비로 CSRF 검증 후 oauth.v2.access를 호출하여 봇 토큰을 저장한다. 완료 후 창을 닫는 HTML 페이지를 반환한다.
   *
   * <p><b>테넌트 복원 지점이다.</b> 이 경로는 permitAll 이라 Bearer 헤더가 없고 따라서
   * {@code TenantContext} 도 없는데, {@code slack_workspace} 는 쓰기 대상(RLS + tenant_id NOT
   * NULL)이다. 그래서 state 가 발급 시점의 테넌트를 함께 실어 오고(V106 [R7]) 여기서 되찾아
   * {@link TenantContext#runScopedGet} 으로 컨텍스트를 세운다. 되찾지 못하면
   * <b>fail-closed</b> — {@code consume} 이 empty 를 돌려주고 아래 400 분기로 빠진다. 사용자에게는
   * 일반적인 오류만 보이고 구체 사유는 리포지토리 로그에 남는다.
   *
   * @param code Slack에서 전달한 authorization_code
   * @param state CSRF 방어용 state (OAuthStateService.issue로 발급)
   * @return 200 HTML (창 닫기 스크립트 포함) 또는 400 (유효하지 않은 state)
   */
  @GetMapping("/callback")
  public ResponseEntity<String> callback(
      @RequestParam("code") String code, @RequestParam("state") String state) {
    // state 소비 — 단일 사용(single-use), 만료 검사 포함
    Optional<ConsumedState> consumed = oAuthStateService.consume(state);
    if (consumed.isEmpty()) {
      return ResponseEntity.badRequest().body("유효하지 않거나 만료된 state입니다.");
    }
    if (consumed.get().channelType() != ChannelType.SLACK) {
      return ResponseEntity.badRequest().body("잘못된 채널 타입의 state입니다.");
    }

    long installedByUserId = consumed.get().userId();

    // 인증 필터를 거치지 않는 경로라 테넌트 컨텍스트가 없다. state 가 실어 온 테넌트(V106 [R7])로
    // 컨텍스트를 세운 뒤, slack_workspace upsert 를 포함한 나머지 전부를 그 안에서 수행한다.
    // 컨트롤러 경계에서 세우는 것이 중요하다 — TenantAwareTransactionManager.doBegin 은 트랜잭션이
    // 열리는 순간 GUC 를 심으므로, @Transactional 리포지토리 안쪽에서 세우면 이미 늦다.
    return TenantContext.runScopedGet(
        consumed.get().tenantId(),
        () -> {
          slackOAuthService.completeAuthorization(code, installedByUserId);

          // 설치 완료 후 팝업 창 닫기
          return ResponseEntity.ok()
              .contentType(MediaType.TEXT_HTML)
              .body(
                  "<html><body>Slack 워크스페이스 설치 완료."
                      + " 창을 닫아주세요."
                      + "<script>window.close();</script></body></html>");
        });
  }

  /**
   * 사용자 Slack user ID 수동 연동 — 인증 필요.
   *
   * <p>사용자가 입력한 slackUserId로 DM ping을 전송하고 user_channel_binding에 저장한다. Slack 봇 토큰은 지정된 워크스페이스에서
   * 조회한다.
   *
   * @param request workspaceId + slackUserId
   * @param authentication Spring Security 인증 객체
   * @return 204 No Content
   */
  @PostMapping("/link-user")
  public ResponseEntity<Void> linkUser(
      @RequestBody LinkUserRequest request, Authentication authentication) {
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
