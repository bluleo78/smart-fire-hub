package com.smartfirehub.notification.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartfirehub.ai.dto.AiSessionResponse;
import com.smartfirehub.ai.repository.AiSessionRepository;
import com.smartfirehub.ai.service.AiAgentBatchClient;
import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.channels.SlackChannel;
import com.smartfirehub.notification.channels.slack.SlackApiClient;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository.SlackWorkspace;
import com.smartfirehub.notification.repository.SlackWorkspaceTenantResolver;
import com.smartfirehub.notification.repository.SlackWorkspaceTenantResolver.SlackWorkspaceRef;
import com.smartfirehub.notification.repository.UserChannelBinding;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * SlackInboundService 단위 테스트.
 *
 * <p>외부 의존성(SlackApiClient, AiAgentBatchClient 등)은 모두 Mockito mock으로 교체하여 서비스 로직만 검증한다. @Async는
 * 테스트에서 동기 실행되므로 별도 설정 불필요.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SlackInboundServiceTest {

  @Mock private UserChannelBindingRepository bindingRepo;
  @Mock private SlackWorkspaceRepository workspaceRepo;
  @Mock private SlackWorkspaceTenantResolver tenantResolver;
  @Mock private AiSessionRepository aiSessionRepo;
  @Mock private AiAgentBatchClient aiAgentClient;
  @Mock private SlackApiClient slackApiClient;
  @Mock private SlackChannel slackChannel;
  @Mock private EncryptionService encryption;
  @Mock private SlackInboundMetrics metrics;

  @InjectMocks private SlackInboundService service;

  private static final String TEAM_ID = "T123";
  private static final String CHANNEL = "C123";
  private static final String SLACK_USER = "U123";
  private static final String TS = "123.456";
  private static final String THREAD_TS = "123.456";
  private static final long USER_ID = 42L;
  private static final String AGENT_SESSION_ID = "agent-session-abc";
  private static final String BOT_TOKEN = "xoxb-test-token";
  private static final String BOT_TOKEN_ENC = "enc::" + BOT_TOKEN;
  private static final long TENANT_ID = 7L;

  /** 기본 stub 세팅 — 각 테스트에서 필요에 따라 override. */
  @BeforeEach
  void setUp() {
    // 테넌트 해석 stub — permitAll 웹훅에는 컨텍스트가 없으므로 이것이 모든 처리의 전제다.
    when(tenantResolver.resolveByTeamId(TEAM_ID))
        .thenReturn(Optional.of(new SlackWorkspaceRef(1L, TENANT_ID)));

    // 워크스페이스 stub
    when(workspaceRepo.findByTeamId(TEAM_ID))
        .thenReturn(
            Optional.of(
                new SlackWorkspace(
                    1L, TEAM_ID, "TestWS", "B001", BOT_TOKEN_ENC, null, null, null, null)));
    when(encryption.decrypt(BOT_TOKEN_ENC)).thenReturn(BOT_TOKEN);

    // 사용자 binding stub
    when(bindingRepo.findByExternalId(TEAM_ID, SLACK_USER))
        .thenReturn(
            Optional.of(
                new UserChannelBinding(
                    10L,
                    USER_ID,
                    ChannelType.SLACK,
                    1L,
                    SLACK_USER,
                    "U123@TestWS",
                    null,
                    null,
                    null,
                    "ACTIVE",
                    null,
                    Instant.now(),
                    Instant.now())));

    // AI 세션 — 기본: 없음 (새로 생성)
    when(aiSessionRepo.findBySlackContext(TEAM_ID, CHANNEL, THREAD_TS))
        .thenReturn(Optional.empty());
    when(aiAgentClient.createSession(USER_ID, "Slack " + THREAD_TS)).thenReturn(AGENT_SESSION_ID);
    when(aiAgentClient.chat(AGENT_SESSION_ID, USER_ID, "hi")).thenReturn("AI 응답 텍스트");
  }

  /** 이벤트 JSON 생성 헬퍼. */
  private ObjectNode makeEvent(String text) {
    ObjectMapper om = new ObjectMapper();
    ObjectNode node = om.createObjectNode();
    node.put("type", "message");
    node.put("channel", CHANNEL);
    node.put("user", SLACK_USER);
    node.put("text", text);
    node.put("ts", TS);
    return node;
  }

  @Test
  @DisplayName("정상 흐름 — 새 세션 생성 후 AI 응답 replyTo 호출")
  void dispatch_successfulFlow_createsSessionAndReplies() {
    // given
    when(aiSessionRepo.createSlackSession(
            anyLong(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(99L);

    // when
    service.dispatch(TEAM_ID, makeEvent("hi"));

    // then: 새 세션 생성 확인
    verify(aiAgentClient).createSession(USER_ID, "Slack " + THREAD_TS);
    verify(aiSessionRepo)
        .createSlackSession(
            eq(USER_ID),
            eq(AGENT_SESSION_ID),
            eq(TEAM_ID),
            eq(CHANNEL),
            eq(THREAD_TS),
            anyString());

    // AI 호출 확인
    verify(aiAgentClient).chat(AGENT_SESSION_ID, USER_ID, "hi");

    // replyTo 호출 확인 (workspaceId=1, channel, threadTs, aiResponse)
    verify(slackChannel).replyTo(1L, CHANNEL, THREAD_TS, "AI 응답 텍스트");
  }

  @Test
  @DisplayName("미연동 사용자 — ephemeral 안내 메시지 전송, chat/replyTo 미호출")
  void dispatch_unmappedUser_sendsEphemeralAndReturns() {
    // given: binding 없음
    when(bindingRepo.findByExternalId(TEAM_ID, SLACK_USER)).thenReturn(Optional.empty());

    // when
    service.dispatch(TEAM_ID, makeEvent("hi"));

    // then: ephemeral 전송
    verify(slackApiClient).postEphemeral(eq(BOT_TOKEN), eq(CHANNEL), eq(SLACK_USER), anyString());
    // AI 호출 없음
    verify(aiAgentClient, never()).chat(anyString(), anyLong(), anyString());
    // replyTo 없음
    verify(slackChannel, never()).replyTo(anyLong(), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("AI chat 예외 — warning reaction + ephemeral 전송")
  void dispatch_aiChatFailure_sendsWarningReaction() {
    // given: AI 호출 예외
    when(aiAgentClient.chat(AGENT_SESSION_ID, USER_ID, "hi"))
        .thenThrow(new RuntimeException("ai-agent timeout"));
    when(aiSessionRepo.createSlackSession(
            anyLong(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(99L);

    // when
    service.dispatch(TEAM_ID, makeEvent("hi"));

    // then: warning reaction 추가
    verify(slackApiClient).reactionsAdd(BOT_TOKEN, CHANNEL, TS, "warning");
    // 오류 ephemeral 전송
    verify(slackApiClient).postEphemeral(eq(BOT_TOKEN), eq(CHANNEL), eq(SLACK_USER), anyString());
    // replyTo 없음
    verify(slackChannel, never()).replyTo(anyLong(), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("기존 세션 재사용 — createSession 미호출, chat은 기존 agentSessionId 사용")
  void dispatch_existingSessionReused() {
    // given: 이미 존재하는 세션
    AiSessionResponse existingSession =
        new AiSessionResponse(
            5L,
            USER_ID,
            AGENT_SESSION_ID,
            null,
            null,
            "Slack 대화",
            LocalDateTime.now(),
            LocalDateTime.now(),
            "SLACK",
            TEAM_ID,
            CHANNEL,
            THREAD_TS);
    when(aiSessionRepo.findBySlackContext(TEAM_ID, CHANNEL, THREAD_TS))
        .thenReturn(Optional.of(existingSession));

    // when
    service.dispatch(TEAM_ID, makeEvent("hi"));

    // then: 새 세션 생성 없음
    verify(aiAgentClient, never()).createSession(anyLong(), anyString());
    verify(aiSessionRepo, never())
        .createSlackSession(
            anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());

    // 기존 sessionId로 chat 호출
    verify(aiAgentClient).chat(AGENT_SESSION_ID, USER_ID, "hi");
    verify(slackChannel).replyTo(1L, CHANNEL, THREAD_TS, "AI 응답 텍스트");
  }

  @Test
  @DisplayName("알 수 없는 워크스페이스 — 아무 외부 호출 없이 반환")
  void dispatch_unknownWorkspace_logsAndReturns() {
    // given: 워크스페이스 없음
    when(workspaceRepo.findByTeamId(TEAM_ID)).thenReturn(Optional.empty());

    // when
    service.dispatch(TEAM_ID, makeEvent("hi"));

    // then: Slack API 호출 없음
    verify(slackApiClient, never())
        .reactionsAdd(anyString(), anyString(), anyString(), anyString());
    verify(slackApiClient, never())
        .postEphemeral(anyString(), anyString(), anyString(), anyString());
    verify(aiAgentClient, never()).chat(anyString(), anyLong(), anyString());
    verify(slackChannel, never()).replyTo(anyLong(), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("테넌트 해석 실패 — 이벤트를 조용히 버리고 DB 조회조차 하지 않는다")
  void dispatch_tenantResolutionFails_dropsEventBeforeAnyQuery() {
    // given: 해석기가 0행 (미등록 팀이거나 revoke 된 워크스페이스)
    when(tenantResolver.resolveByTeamId(TEAM_ID)).thenReturn(Optional.empty());

    // when
    service.dispatch(TEAM_ID, makeEvent("hi"));

    // then: 워크스페이스 조회 자체가 일어나지 않아야 한다. 이 순서가 핵심이다 —
    // 해석 전에 조회하면 RLS 아래 조용한 0행이 되어 "unknown workspace" 로 오진된다.
    verify(workspaceRepo, never()).findByTeamId(anyString());
    // 외부로 어떤 반응도 내보내지 않는다 — 응답이 갈리면 team_id 존재 여부가 누출된다.
    verify(slackApiClient, never())
        .reactionsAdd(anyString(), anyString(), anyString(), anyString());
    verify(slackApiClient, never())
        .postEphemeral(anyString(), anyString(), anyString(), anyString());
    verify(slackChannel, never()).replyTo(anyLong(), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("본 처리는 해석된 테넌트 컨텍스트 안에서 돈다 — 진입 전 컨텍스트는 복원된다")
  void dispatch_runsBodyInsideResolvedTenantScope() {
    // given: 컨텍스트가 없는 상태를 재현한다(permitAll 웹훅에는 JWT 가 없다).
    TenantContext.clear();
    // 본 처리 중에 실제로 보이는 테넌트 값을 리포지토리 호출 시점에 포착한다.
    // 단순히 "예외가 안 났다" 로는 판별력이 없다 — 값 자체를 봐야 한다.
    AtomicReference<Long> seenInWorkspaceLookup = new AtomicReference<>();
    AtomicReference<Long> seenInSessionCreate = new AtomicReference<>();
    when(workspaceRepo.findByTeamId(TEAM_ID))
        .thenAnswer(
            inv -> {
              seenInWorkspaceLookup.set(TenantContext.get());
              return Optional.of(
                  new SlackWorkspace(
                      1L, TEAM_ID, "TestWS", "B001", BOT_TOKEN_ENC, null, null, null, null));
            });
    when(aiSessionRepo.createSlackSession(
            anyLong(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(
            inv -> {
              seenInSessionCreate.set(TenantContext.get());
              return 99L;
            });

    // when
    service.dispatch(TEAM_ID, makeEvent("hi"));

    // then: RLS 대상 조회·삽입이 모두 해석된 테넌트 안에서 일어났다.
    assertThat(seenInWorkspaceLookup.get()).isEqualTo(TENANT_ID);
    assertThat(seenInSessionCreate.get()).isEqualTo(TENANT_ID);
    // 그리고 진입 전 상태(컨텍스트 없음)로 복원됐다 — 스레드 풀 재사용 시 남의 테넌트를
    // 물려주지 않기 위한 조건이다.
    assertThat(TenantContext.get()).isNull();
  }

  /**
   * 가드 회귀 보호 — 컨텍스트 없이 본 처리에 진입하면 예외.
   *
   * <p><b>이 단언이 없으면 가드에 판별력이 0이다.</b> {@code dispatch} 를 통해 부르는 테스트는
   * 전부 {@code runScoped} 가 컨텍스트를 세워 주므로 가드를 지워도 초록이다(리뷰 실측). 가드의
   * 존재 이유는 "미래에 {@code slackInboundExecutor} 빈이 복원될 때의 기계적 방어" 이므로,
   * 가장 필요한 시점에 보호가 없으면 안 된다. 그래서 {@code process} 를 직접 부른다.
   */
  @Test
  @DisplayName("가드 — 테넌트 스코프 없이 process 에 진입하면 예외를 던진다")
  void process_withoutTenantScope_throws() {
    TenantContext.clear();

    assertThatThrownBy(() -> service.process(TEAM_ID, makeEvent("hi")))
        .isInstanceOf(IllegalStateException.class)
        // 메시지가 이 지점을 가리켜야 한다 — 발화한 날 운영자가 JobRunr 를 뒤지면 안 된다.
        .hasMessageContaining("Slack inbound")
        .hasMessageContaining(TEAM_ID);

    // 가드가 막았으므로 RLS 대상 조회는 한 번도 일어나지 않아야 한다.
    verify(workspaceRepo, never()).findByTeamId(anyString());
  }
}
