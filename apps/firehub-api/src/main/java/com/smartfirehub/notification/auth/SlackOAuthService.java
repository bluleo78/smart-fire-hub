package com.smartfirehub.notification.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.channels.slack.SlackApiClient;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository;
import com.smartfirehub.notification.repository.UserChannelBinding;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Slack Workspace OAuth 설치 및 사용자 매핑 서비스.
 *
 * <p>관리자가 Slack 앱을 워크스페이스에 1회 설치하면 봇 토큰을 slack_workspace 테이블에 저장한다. 이후 각 사용자가 자신의 Slack user ID를 수동
 * 입력하면 봇이 DM ping을 전송하여 연동을 확인한다.
 *
 * <p>봇 토큰은 AES-256-GCM으로 암호화하여 저장한다 (EncryptionService).
 *
 * <p>설정값은 환경변수로 주입받는다 (application.yml notification.slack.* 블록).
 */
@Service
public class SlackOAuthService {

  private final SlackApiClient slackApiClient;
  private final SlackWorkspaceRepository slackWorkspaceRepo;
  private final UserChannelBindingRepository userChannelBindingRepo;
  private final EncryptionService encryption;

  /** Slack 앱 Client ID */
  @Value("${notification.slack.client_id:}")
  private String clientId;

  /** Slack 앱 Client Secret */
  @Value("${notification.slack.client_secret:}")
  private String clientSecret;

  /** Slack 개발자 콘솔에 등록된 redirect_uri */
  @Value("${notification.slack.redirect_uri:}")
  private String redirectUri;

  /** 요청할 OAuth 권한 범위 (쉼표 구분) */
  @Value(
      "${notification.slack.scopes:chat:write,im:write,im:history,users:read,reactions:write,app_mentions:read}")
  private String scopes;

  public SlackOAuthService(
      SlackApiClient slackApiClient,
      SlackWorkspaceRepository slackWorkspaceRepo,
      UserChannelBindingRepository userChannelBindingRepo,
      EncryptionService encryption) {
    this.slackApiClient = slackApiClient;
    this.slackWorkspaceRepo = slackWorkspaceRepo;
    this.userChannelBindingRepo = userChannelBindingRepo;
    this.encryption = encryption;
  }

  /**
   * Slack OAuth 인증 URL 생성.
   *
   * <p>관리자가 새 창으로 열어 Slack 앱 설치를 진행한다. scope에는 봇 운영에 필요한 권한이 모두 포함된다.
   *
   * @param state CSRF 방어용 state (OAuthStateService.issue()로 발급)
   * @return Slack OAuth 앱 설치 페이지 URL
   */
  public String authorizeUrl(String state) {
    return "https://slack.com/oauth/v2/authorize"
        + "?client_id="
        + clientId
        + "&scope="
        + scopes
        + "&state="
        + state
        + "&redirect_uri="
        + redirectUri;
  }

  /**
   * OAuth 콜백의 code를 봇 토큰으로 교환하고 워크스페이스를 저장.
   *
   * <p>응답의 ok=false 이면 IllegalStateException을 던진다. 봇 토큰은 AES-256-GCM으로 암호화하여 DB에 upsert한다. 이미 설치된
   * 워크스페이스라면 ON CONFLICT로 갱신(재설치)한다.
   *
   * @param code Slack OAuth 콜백의 authorization_code
   * @param installedByUserId 설치를 완료한 관리자 사용자 ID
   * @return 설치 결과 (팀 ID, 팀 이름, 워크스페이스 DB ID)
   */
  public SlackWorkspaceInstalled completeAuthorization(String code, long installedByUserId) {
    JsonNode resp = slackApiClient.oauthV2Access(code, clientId, clientSecret, redirectUri);

    // Slack API는 HTTP 200 + ok=false로 실패를 전달한다
    if (!resp.path("ok").asBoolean(false)) {
      String error = resp.path("error").asText("unknown");
      throw new IllegalStateException("slack oauth failed: " + error);
    }

    String teamId = resp.path("team").path("id").asText();
    String teamName = resp.path("team").path("name").asText();
    String botUserId = resp.path("bot_user_id").asText();
    String accessToken = resp.path("access_token").asText();

    // 봇 토큰을 AES-256-GCM으로 암호화하여 저장
    String botTokenEnc = encryption.encrypt(accessToken);

    long workspaceId =
        slackWorkspaceRepo.upsertFromOAuth(
            teamId, teamName, botUserId, botTokenEnc, installedByUserId);

    return new SlackWorkspaceInstalled(teamId, teamName, workspaceId);
  }

  /**
   * 사용자의 Slack user ID를 매핑하고 DM ping을 전송하여 연동 확인.
   *
   * <p>워크스페이스 봇 토큰을 복호화하여 Slack API를 호출한다. - users.info로 display name을 조회한다. - conversations.open으로
   * DM 채널 ID를 획득한다. - chat.postMessage로 연동 확인 DM을 발송한다. 최종적으로 user_channel_binding 테이블에 upsert한다.
   *
   * @param userId Smart Fire Hub 사용자 ID
   * @param workspaceId 연결할 slack_workspace.id
   * @param slackUserId 사용자가 입력한 Slack user ID (예: U0123456)
   */
  public void linkUser(long userId, long workspaceId, String slackUserId) {
    // 워크스페이스 조회 및 봇 토큰 복호화
    SlackWorkspaceRepository.SlackWorkspace workspace =
        slackWorkspaceRepo
            .findById(workspaceId)
            .orElseThrow(
                () -> new IllegalArgumentException("slack workspace not found: " + workspaceId));
    String botToken = encryption.decrypt(workspace.botTokenEnc());

    // users.info로 display name 조회
    JsonNode userInfo = slackApiClient.usersInfo(botToken, slackUserId);
    String displayName = extractDisplayName(userInfo);

    // conversations.open으로 DM 채널 ID 획득
    JsonNode convResp = slackApiClient.openConversation(botToken, slackUserId);
    String dmChannelId = convResp.path("channel").path("id").asText();

    // 연동 확인 DM 발송 — blocks 없이 텍스트만
    slackApiClient.chatPostMessage(botToken, dmChannelId, null, "Smart Fire Hub 채널 연동이 확인되었습니다.");

    // user_channel_binding upsert
    Instant now = Instant.now();
    userChannelBindingRepo.upsert(
        new UserChannelBinding(
            null,
            userId,
            ChannelType.SLACK,
            workspaceId,
            slackUserId,
            "@" + displayName,
            null, // Slack은 access_token 없이 워크스페이스 봇 토큰만 사용
            null,
            null,
            "ACTIVE",
            now, // last_verified_at: DM 발송 시각으로 초기화
            now,
            now));
  }

  /**
   * users.info 응답에서 display name 추출.
   *
   * <p>display_name이 비어 있으면 real_name으로 fallback한다.
   *
   * @param userInfo Slack users.info 응답
   * @return 표시 이름 (빈 문자열일 수 있음)
   */
  private String extractDisplayName(JsonNode userInfo) {
    String displayName = userInfo.path("user").path("profile").path("display_name").asText("");
    if (displayName.isBlank()) {
      // display_name이 없는 경우 real_name으로 fallback
      displayName = userInfo.path("user").path("real_name").asText("");
    }
    return displayName;
  }

  /**
   * Slack 워크스페이스 설치 완료 결과.
   *
   * @param teamId Slack 팀(워크스페이스) ID
   * @param teamName 팀 이름
   * @param workspaceId DB에 저장된 slack_workspace.id
   */
  public record SlackWorkspaceInstalled(String teamId, String teamName, long workspaceId) {}
}
