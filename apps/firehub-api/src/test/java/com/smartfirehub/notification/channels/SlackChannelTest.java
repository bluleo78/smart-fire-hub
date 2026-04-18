package com.smartfirehub.notification.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.quality.Strictness.LENIENT;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.DeliveryContext;
import com.smartfirehub.notification.DeliveryResult;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.PermanentFailureReason;
import com.smartfirehub.notification.channels.slack.SlackApiClient;
import com.smartfirehub.notification.channels.slack.SlackBlockKitRenderer;
import com.smartfirehub.notification.repository.SlackWorkspaceRepository;
import com.smartfirehub.notification.repository.UserChannelBinding;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;

/**
 * SlackChannel 단위 테스트.
 *
 * <p>실제 Slack API 호출 없이 주요 경로(정상 발송, binding 누락, workspace 누락, 인증 오류, rate limit)를 검증.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = LENIENT)
class SlackChannelTest {

    @Mock private SlackApiClient slackApiClient;
    @Mock private SlackBlockKitRenderer renderer;
    @Mock private UserChannelBindingRepository bindingRepo;
    @Mock private SlackWorkspaceRepository workspaceRepo;
    @Mock private EncryptionService encryptionService;

    private SlackChannel channel;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final long USER_ID = 42L;
    private static final long OUTBOX_ID = 100L;
    private static final long WORKSPACE_ID = 1L;
    private static final String SLACK_USER_ID = "U0123456";
    private static final String BOT_TOKEN_ENC = "enc:xoxb-test-token";
    private static final String BOT_TOKEN = "xoxb-test-token";
    private static final String DM_CHANNEL_ID = "D0123456";

    @BeforeEach
    void setUp() {
        channel = new SlackChannel(slackApiClient, renderer, bindingRepo, workspaceRepo, encryptionService);

        // 공통 stub — renderer는 모든 케이스에서 동일하게 동작
        when(renderer.renderBlocksJson(any())).thenReturn("[{\"type\":\"header\"}]");
        when(renderer.renderFallbackText(any())).thenReturn("제목\n요약");
    }

    // ----------------------------------------------------------------
    // 헬퍼
    // ----------------------------------------------------------------

    /** 기본 DeliveryContext 생성. */
    private DeliveryContext ctx() {
        Payload p = new Payload(Payload.PayloadType.STANDARD, "제목", "요약",
                List.of(), List.of(), List.of(), Map.of(), Map.of());
        return new DeliveryContext(OUTBOX_ID, UUID.randomUUID(), USER_ID, null, Optional.empty(), p);
    }

    /** 정상 활성 binding 생성. */
    private UserChannelBinding activeBinding() {
        return new UserChannelBinding(
                1L, USER_ID, ChannelType.SLACK, WORKSPACE_ID, SLACK_USER_ID,
                "slack-user@example.com", null, null,
                null, "ACTIVE",
                Instant.now(), Instant.now(), Instant.now());
    }

    /** SlackWorkspace 레코드 생성. */
    private SlackWorkspaceRepository.SlackWorkspace workspace() {
        return new SlackWorkspaceRepository.SlackWorkspace(
                WORKSPACE_ID, "T0123456", "Test Team", "B0123456",
                BOT_TOKEN_ENC, null, null, null, 1L);
    }

    /** conversations.open 성공 응답 생성. */
    private ObjectNode openConvOkResponse() {
        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("ok", true);
        ObjectNode channelNode = MAPPER.createObjectNode();
        channelNode.put("id", DM_CHANNEL_ID);
        resp.set("channel", channelNode);
        return resp;
    }

    /** chat.postMessage 성공 응답 생성. */
    private ObjectNode postMessageOkResponse(String ts) {
        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("ok", true);
        resp.put("ts", ts);
        resp.put("channel", DM_CHANNEL_ID);
        return resp;
    }

    /** chat.postMessage 실패 응답 생성. */
    private ObjectNode postMessageErrorResponse(String error) {
        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("ok", false);
        resp.put("error", error);
        return resp;
    }

    // ----------------------------------------------------------------
    // 정상 발송
    // ----------------------------------------------------------------

    /**
     * 정상 경로: binding 존재, workspace 존재, conversations.open 성공, chatPostMessage ok=true
     * → Sent(ts:channel) 반환.
     */
    @Test
    void deliver_success_returnsSent_withTs() {
        when(bindingRepo.findActive(USER_ID, ChannelType.SLACK)).thenReturn(Optional.of(activeBinding()));
        when(workspaceRepo.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(encryptionService.decrypt(BOT_TOKEN_ENC)).thenReturn(BOT_TOKEN);
        when(slackApiClient.openConversation(eq(BOT_TOKEN), eq(SLACK_USER_ID)))
                .thenReturn(openConvOkResponse());
        when(slackApiClient.chatPostMessage(eq(BOT_TOKEN), eq(DM_CHANNEL_ID), anyString(), anyString()))
                .thenReturn(postMessageOkResponse("1234567890.123456"));

        DeliveryResult result = channel.deliver(ctx());

        assertThat(result).isInstanceOfSatisfying(DeliveryResult.Sent.class,
                sent -> assertThat(sent.externalMessageId())
                        .isEqualTo("1234567890.123456:" + DM_CHANNEL_ID));
    }

    // ----------------------------------------------------------------
    // binding 없음
    // ----------------------------------------------------------------

    /**
     * binding이 없을 때 BINDING_REQUIRED PermanentFailure 반환 검증.
     */
    @Test
    void deliver_noBinding_returnsPermanentFailure() {
        when(bindingRepo.findActive(USER_ID, ChannelType.SLACK)).thenReturn(Optional.empty());

        DeliveryResult result = channel.deliver(ctx());

        assertThat(result).isInstanceOfSatisfying(DeliveryResult.PermanentFailure.class,
                pf -> assertThat(pf.reason()).isEqualTo(PermanentFailureReason.BINDING_REQUIRED));
    }

    // ----------------------------------------------------------------
    // workspace 없음 (revoked)
    // ----------------------------------------------------------------

    /**
     * workspace가 조회되지 않을 때 UNRECOVERABLE PermanentFailure 반환 검증.
     */
    @Test
    void deliver_workspaceMissing_returnsPermanentFailure() {
        when(bindingRepo.findActive(USER_ID, ChannelType.SLACK)).thenReturn(Optional.of(activeBinding()));
        when(workspaceRepo.findById(WORKSPACE_ID)).thenReturn(Optional.empty());

        DeliveryResult result = channel.deliver(ctx());

        assertThat(result).isInstanceOfSatisfying(DeliveryResult.PermanentFailure.class,
                pf -> assertThat(pf.reason()).isEqualTo(PermanentFailureReason.UNRECOVERABLE));
    }

    // ----------------------------------------------------------------
    // invalid_auth → TOKEN_EXPIRED
    // ----------------------------------------------------------------

    /**
     * chatPostMessage ok=false + error=invalid_auth → TOKEN_EXPIRED PermanentFailure 반환 검증.
     */
    @Test
    void deliver_invalidAuth_returnsPermanentFailureTokenExpired() {
        when(bindingRepo.findActive(USER_ID, ChannelType.SLACK)).thenReturn(Optional.of(activeBinding()));
        when(workspaceRepo.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(encryptionService.decrypt(BOT_TOKEN_ENC)).thenReturn(BOT_TOKEN);
        when(slackApiClient.openConversation(eq(BOT_TOKEN), eq(SLACK_USER_ID)))
                .thenReturn(openConvOkResponse());
        when(slackApiClient.chatPostMessage(eq(BOT_TOKEN), eq(DM_CHANNEL_ID), anyString(), anyString()))
                .thenReturn(postMessageErrorResponse("invalid_auth"));

        DeliveryResult result = channel.deliver(ctx());

        assertThat(result).isInstanceOfSatisfying(DeliveryResult.PermanentFailure.class,
                pf -> {
                    assertThat(pf.reason()).isEqualTo(PermanentFailureReason.TOKEN_EXPIRED);
                    assertThat(pf.details()).isEqualTo("invalid_auth");
                });
    }

    // ----------------------------------------------------------------
    // rate_limited → TransientFailure
    // ----------------------------------------------------------------

    /**
     * chatPostMessage ok=false + error=rate_limited → TransientFailure 반환 검증.
     */
    @Test
    void deliver_rateLimited_returnsTransientFailure() {
        when(bindingRepo.findActive(USER_ID, ChannelType.SLACK)).thenReturn(Optional.of(activeBinding()));
        when(workspaceRepo.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(encryptionService.decrypt(BOT_TOKEN_ENC)).thenReturn(BOT_TOKEN);
        when(slackApiClient.openConversation(eq(BOT_TOKEN), eq(SLACK_USER_ID)))
                .thenReturn(openConvOkResponse());
        when(slackApiClient.chatPostMessage(eq(BOT_TOKEN), eq(DM_CHANNEL_ID), anyString(), anyString()))
                .thenReturn(postMessageErrorResponse("rate_limited"));

        DeliveryResult result = channel.deliver(ctx());

        assertThat(result).isInstanceOf(DeliveryResult.TransientFailure.class);
    }
}
