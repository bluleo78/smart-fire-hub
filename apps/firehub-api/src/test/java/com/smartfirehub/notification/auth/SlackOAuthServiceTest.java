package com.smartfirehub.notification.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.auth.SlackOAuthService.SlackWorkspaceInstalled;
import com.smartfirehub.notification.channels.slack.SlackApiClient;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository.SlackWorkspace;
import com.smartfirehub.notification.repository.UserChannelBinding;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * SlackOAuthService 단위 테스트.
 *
 * <p>SlackApiClient·SlackWorkspaceRepository·UserChannelBindingRepository·EncryptionService를
 * Mockito로 모킹하여 외부 호출 없이 비즈니스 로직만 검증한다. EncryptionService는 package-private 생성자 제약으로 @Mock을 사용하고,
 * encrypt()가 "enc:{input}" 형태로 stub된다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SlackOAuthServiceTest {

  @Mock private SlackApiClient slackApiClient;
  @Mock private SlackWorkspaceRepository slackWorkspaceRepo;
  @Mock private UserChannelBindingRepository userChannelBindingRepo;

  /** EncryptionService는 생성자가 package-private이므로 Mock으로 stub */
  @Mock private EncryptionService encryption;

  @InjectMocks private SlackOAuthService slackOAuthService;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String TEST_CLIENT_ID = "test-slack-client-id";
  private static final String TEST_CLIENT_SECRET = "test-slack-secret";
  private static final String TEST_REDIRECT_URI =
      "https://app.example.com/api/v1/oauth/slack/callback";
  private static final String TEST_SCOPES =
      "chat:write,im:write,im:history,users:read,reactions:write,app_mentions:read";

  @BeforeEach
  void setUp() {
    // encrypt()가 호출되면 "enc:{원문}" 형태로 반환 — 암호화 여부(원문과 다름) 검증 가능
    when(encryption.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
    // decrypt()는 "enc:" prefix를 제거하여 원문 복원
    when(encryption.decrypt(anyString()))
        .thenAnswer(
            inv -> {
              String val = inv.getArgument(0);
              return val.startsWith("enc:") ? val.substring(4) : val;
            });

    // @Value 필드를 직접 주입 (Spring 컨텍스트 없이 단위 테스트)
    ReflectionTestUtils.setField(slackOAuthService, "clientId", TEST_CLIENT_ID);
    ReflectionTestUtils.setField(slackOAuthService, "clientSecret", TEST_CLIENT_SECRET);
    ReflectionTestUtils.setField(slackOAuthService, "redirectUri", TEST_REDIRECT_URI);
    ReflectionTestUtils.setField(slackOAuthService, "scopes", TEST_SCOPES);
  }

  // =========================================================================
  // completeAuthorization — 성공 케이스
  // =========================================================================

  /** 정상: code → 봇 토큰 교환 성공 시 upsertFromOAuth 호출, bot_token이 암호화되어 전달되어야 한다. */
  @Test
  void completeAuthorization_success_upsertsWorkspaceWithEncryptedBotToken() {
    // given
    long installedByUserId = 99L;
    String code = "slack-auth-code";
    String rawBotToken = "xoxb-raw-bot-token";
    String teamId = "T01234567";
    String teamName = "Test Workspace";
    String botUserId = "U99999";
    long expectedWorkspaceId = 42L;

    ObjectNode oauthResp = MAPPER.createObjectNode();
    oauthResp.put("ok", true);
    oauthResp.put("access_token", rawBotToken);
    oauthResp.put("bot_user_id", botUserId);
    ObjectNode team = oauthResp.putObject("team");
    team.put("id", teamId);
    team.put("name", teamName);

    when(slackApiClient.oauthV2Access(
            eq(code), eq(TEST_CLIENT_ID), eq(TEST_CLIENT_SECRET), eq(TEST_REDIRECT_URI)))
        .thenReturn(oauthResp);
    when(slackWorkspaceRepo.upsertFromOAuth(
            eq(teamId), eq(teamName), eq(botUserId), anyString(), eq(installedByUserId)))
        .thenReturn(expectedWorkspaceId);

    // when
    SlackWorkspaceInstalled result =
        slackOAuthService.completeAuthorization(code, installedByUserId);

    // then: 워크스페이스 정보가 올바르게 반환되어야 한다
    assertThat(result.teamId()).isEqualTo(teamId);
    assertThat(result.teamName()).isEqualTo(teamName);
    assertThat(result.workspaceId()).isEqualTo(expectedWorkspaceId);

    // upsertFromOAuth 호출 시 bot_token이 암호화된 값으로 전달되어야 한다
    ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
    verify(slackWorkspaceRepo)
        .upsertFromOAuth(
            eq(teamId), eq(teamName), eq(botUserId), tokenCaptor.capture(), eq(installedByUserId));

    String capturedTokenEnc = tokenCaptor.getValue();
    assertThat(capturedTokenEnc).isNotEqualTo(rawBotToken); // 원문이 아니어야 함
    assertThat(capturedTokenEnc).contains(":"); // enc:{원문} 형태
  }

  // =========================================================================
  // completeAuthorization — 실패 케이스
  // =========================================================================

  /** ok=false 응답 시 IllegalStateException을 던져야 한다. 에러 메시지에 Slack 에러 코드가 포함되어야 한다. */
  @Test
  void completeAuthorization_oauthFailureThrows() {
    // given
    ObjectNode oauthResp = MAPPER.createObjectNode();
    oauthResp.put("ok", false);
    oauthResp.put("error", "invalid_code");

    when(slackApiClient.oauthV2Access(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(oauthResp);

    // when/then
    assertThatThrownBy(() -> slackOAuthService.completeAuthorization("bad-code", 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("slack oauth failed")
        .hasMessageContaining("invalid_code");
  }

  // =========================================================================
  // linkUser — 성공 케이스
  // =========================================================================

  /**
   * 정상: linkUser 호출 시 usersInfo + openConversation + chatPostMessage 순으로 호출되고, UserChannelBinding이
   * SLACK 타입으로 upsert 되어야 한다.
   */
  @Test
  void linkUser_success_sendsDMAndCreatesBinding() {
    // given
    long userId = 7L;
    long workspaceId = 42L;
    String slackUserId = "U01234ABC";
    String rawBotToken = "xoxb-real-token";
    String encBotToken = "enc:" + rawBotToken;
    String dmChannelId = "D09876XYZ";
    String displayName = "홍길동";

    // 워크스페이스 조회 stub
    SlackWorkspace workspace =
        new SlackWorkspace(
            workspaceId, "T01234567", "Test WS", "U99999", encBotToken, "", null, null, 1L);
    when(slackWorkspaceRepo.findById(workspaceId)).thenReturn(Optional.of(workspace));

    // users.info 응답 stub
    ObjectNode userInfoResp = MAPPER.createObjectNode();
    ObjectNode user = userInfoResp.putObject("user");
    ObjectNode profile = user.putObject("profile");
    profile.put("display_name", displayName);
    when(slackApiClient.usersInfo(eq(rawBotToken), eq(slackUserId))).thenReturn(userInfoResp);

    // conversations.open 응답 stub
    ObjectNode convResp = MAPPER.createObjectNode();
    ObjectNode channel = convResp.putObject("channel");
    channel.put("id", dmChannelId);
    when(slackApiClient.openConversation(eq(rawBotToken), eq(slackUserId))).thenReturn(convResp);

    // chatPostMessage 응답 stub
    ObjectNode msgResp = MAPPER.createObjectNode();
    msgResp.put("ok", true);
    when(slackApiClient.chatPostMessage(anyString(), anyString(), isNull(), anyString()))
        .thenReturn(msgResp);

    // when
    slackOAuthService.linkUser(userId, workspaceId, slackUserId);

    // then: chatPostMessage가 DM 채널에 호출되었는지 확인
    verify(slackApiClient)
        .chatPostMessage(
            eq(rawBotToken), eq(dmChannelId), isNull(), eq("Smart Fire Hub 채널 연동이 확인되었습니다."));

    // then: UserChannelBinding이 SLACK 타입으로 upsert 되어야 한다
    ArgumentCaptor<UserChannelBinding> captor = forClass(UserChannelBinding.class);
    verify(userChannelBindingRepo).upsert(captor.capture());

    UserChannelBinding saved = captor.getValue();
    assertThat(saved.userId()).isEqualTo(userId);
    assertThat(saved.channelType()).isEqualTo(ChannelType.SLACK);
    assertThat(saved.workspaceId()).isEqualTo(workspaceId);
    assertThat(saved.externalUserId()).isEqualTo(slackUserId);
    assertThat(saved.displayAddress()).isEqualTo("@" + displayName);
    assertThat(saved.status()).isEqualTo("ACTIVE");
    assertThat(saved.lastVerifiedAt()).isNotNull();
  }

  // =========================================================================
  // authorizeUrl — URL 구성 검증
  // =========================================================================

  /** authorizeUrl이 client_id, scope, state, redirect_uri를 모두 포함해야 한다. */
  @Test
  void authorizeUrl_containsRequiredParams() {
    String state = "test-state-value";

    String url = slackOAuthService.authorizeUrl(state);

    assertThat(url).startsWith("https://slack.com/oauth/v2/authorize");
    assertThat(url).contains("client_id=" + TEST_CLIENT_ID);
    assertThat(url).contains("scope=" + TEST_SCOPES);
    assertThat(url).contains("state=" + state);
    assertThat(url).contains("redirect_uri=" + TEST_REDIRECT_URI);
  }
}
